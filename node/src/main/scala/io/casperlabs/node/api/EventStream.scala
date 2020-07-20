package io.casperlabs.node.api

import cats._
import cats.implicits._
import cats.effect._
import com.google.protobuf.ByteString
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.casper.DeployHash
import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper.api.BlockAPI
import io.casperlabs.casper.consensus.info.{BlockInfo, DeployInfo, Event}
import io.casperlabs.casper.consensus.Deploy
import io.casperlabs.casper.EventEmitter
import io.casperlabs.mempool.DeployBuffer
import io.casperlabs.metrics.Metrics
import io.casperlabs.node.Fs2StreamOps
import io.casperlabs.node.api.casper.StreamEventsRequest
import io.casperlabs.node.api.casper.StreamEventsRequest
import io.casperlabs.node.api.graphql.FinalizedBlocksStream
import io.casperlabs.storage.block.BlockStorage
import io.casperlabs.storage.dag.DagStorage
import io.casperlabs.storage.deploy.DeployStorage
import io.casperlabs.storage.event.EventStorage
import monix.execution.{Ack, Scheduler}
import monix.reactive.{Observable, OverflowStrategy}
import monix.reactive.subjects.ConcurrentSubject
import simulacrum.typeclass
import io.casperlabs.shared.Log

@typeclass trait EventStream[F[_]] extends EventEmitter[F] {
  def subscribe(request: StreamEventsRequest): Observable[Event]
}

object EventStream {
  def create[F[_]: ConcurrentEffect: DeployStorage: BlockStorage: DagStorage: EventStorage: Log: Metrics](
      scheduler: Scheduler,
      eventStreamBufferSize: Int
  ): EventStream[F] = {
    // Don't send code in the deploy bodies.
    implicit val deployView = DeployInfo.View.BASIC

    val source =
      ConcurrentSubject.publish[Event](OverflowStrategy.DropOld(eventStreamBufferSize))(scheduler)

    def push(event: Event): F[Unit] = {
      implicit val ec = scheduler
      Async[F].async { k =>
        source.onNext(event).onComplete { result =>
          k(result.toEither.void)
        }
      }
    }

    def emit(values: Event.Value*): F[Unit] =
      for {
        events <- EventStorage[F].storeEvents(values)
        _      <- events.traverse(push)
      } yield ()

    new EventStream[F] {
      import Event._

      override def subscribe(request: StreamEventsRequest): Observable[Event] =
        if (request.minEventId > 0) {
          // TODO (NODE-1302): Move filtering to the storage.
          EventStorage[F]
            .getEvents(request.minEventId, request.maxEventId)
            .filter(subscriptionFilter(request))
            .toMonixObservable
        } else {
          source.filter(subscriptionFilter(request))
        }

      private def subscriptionFilter(request: StreamEventsRequest): Event => Boolean = {
        import Event.Value._

        val accountFilter: ByteString => Boolean =
          request.getDeployFilter.accountPublicKeyHashes.toSet match {
            case hashes if hashes.nonEmpty => hashes.contains
            case _                         => _ => true
          }

        val deployHashFilter: DeployHash => Boolean =
          request.getDeployFilter.deployHashes.toSet match {
            case hashes if hashes.nonEmpty => hashes.contains
            case _                         => _ => true
          }

        def deployFilter(d: Deploy) =
          accountFilter(d.getHeader.accountPublicKeyHash) && deployHashFilter(d.deployHash)

        event => {
          event.value match {
            case Empty => false
            case Value.BlockAdded(_) =>
              request.blockAdded

            case Value.NewFinalizedBlock(_) =>
              request.blockFinalized

            case Value.DeployAdded(e) =>
              request.deployAdded && deployFilter(e.getDeploy)

            case Value.DeployDiscarded(e) =>
              request.deployDiscarded && deployFilter(e.getDeploy)

            case Value.DeployRequeued(e) =>
              request.deployRequeued && deployFilter(e.getDeploy)

            case Value.DeployProcessed(e) =>
              request.deployProcessed && deployFilter(e.getProcessedDeploy.getDeploy)

            case Value.DeployFinalized(e) =>
              request.deployFinalized && deployFilter(e.getProcessedDeploy.getDeploy)

            case Value.DeployOrphaned(e) =>
              request.deployOrphaned && deployFilter(e.getDeployInfo.getDeploy)
          }
        }
      }

      override def blockAdded(blockHash: BlockHash): F[Unit] = {
        BlockAPI.getBlockInfo[F](
          Base16.encode(blockHash.toByteArray),
          BlockInfo.View.FULL
        ) flatMap { blockInfo =>
          emit(Value.BlockAdded(BlockAdded().withBlock(blockInfo)))
        }
      } >> {
        DeployStorage[F].reader
          .getProcessedDeploys(blockHash)
          .flatMap { deploys =>
            emit(deploys.map { d =>
              Value
                .DeployProcessed(DeployProcessed().withBlockHash(blockHash).withProcessedDeploy(d))
            }: _*)
          }
          .void
      }

      override def newLastFinalizedBlock(
          lfb: BlockHash,
          indirectlyFinalized: Set[BlockHash],
          indirectlyOrphaned: Set[BlockHash]
      ): F[Unit] =
        emit(
          Value.NewFinalizedBlock(
            NewFinalizedBlock(lfb, indirectlyFinalized.toSeq, indirectlyOrphaned.toSeq)
          )
        ) >> {
          (lfb +: indirectlyFinalized.toList).traverse { blockHash =>
            DeployStorage[F].reader
              .getProcessedDeploys(blockHash)
              .flatMap { deploys =>
                emit(deploys.map { d =>
                  Value.DeployFinalized(
                    DeployFinalized().withBlockHash(blockHash).withProcessedDeploy(d)
                  )
                }: _*)
              }
          } >>
            indirectlyOrphaned.toList.traverse { blockHash =>
              val rdr = DeployStorage[F].reader
              for {
                processed <- rdr.getProcessedDeploys(blockHash)
                deploys   = processed.map(_.getDeploy)
                infos     <- rdr.getDeployInfos(deploys)
                _ <- emit(infos.map { info =>
                      Value.DeployOrphaned(
                        DeployOrphaned().withBlockHash(blockHash).withDeployInfo(info)
                      )
                    }: _*)
              } yield ()
            } void
        }

      override def deployAdded(deploy: Deploy): F[Unit] =
        emit(Value.DeployAdded(DeployAdded().withDeploy(deploy.clearBody)))

      override def deploysDiscarded(deployHashesWithReasons: Seq[(DeployHash, String)]): F[Unit] = {
        val reasons = deployHashesWithReasons.toMap
        DeployStorage[F].reader
          .getByHashes(reasons.keySet)
          .evalMap { deploy =>
            emit(
              Value.DeployDiscarded(
                DeployDiscarded().withDeploy(deploy).withMessage(reasons(deploy.deployHash))
              )
            )
          }
          .compile
          .drain
      }

      override def deploysRequeued(deployHashes: Seq[DeployHash]): F[Unit] =
        DeployStorage[F].reader
          .getByHashes(deployHashes.toSet)
          .evalMap { deploy =>
            emit(Value.DeployRequeued(DeployRequeued().withDeploy(deploy)))
          }
          .compile
          .drain
    }
  }
}
