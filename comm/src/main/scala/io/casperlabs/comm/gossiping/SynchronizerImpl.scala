package io.casperlabs.comm.gossiping

import cats.data._
import cats.effect._
import cats.implicits._
import com.google.protobuf.ByteString
import eu.timepit.refined._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.numeric._
import io.casperlabs.casper.consensus.BlockSummary
import io.casperlabs.comm.discovery.Node
import io.casperlabs.comm.discovery.NodeUtils.showNode
import io.casperlabs.comm.gossiping.Synchronizer.SyncError
import io.casperlabs.comm.gossiping.Synchronizer.SyncError.{
  MissingDependencies,
  TooDeep,
  TooWide,
  Unreachable,
  ValidationError
}
import io.casperlabs.shared.Log

import scala.collection.immutable.Queue
import scala.util.control.NonFatal

// TODO: Optimise to heap-safe
class SynchronizerImpl[F[_]: Sync: Log](
    connectToGossip: Node => F[GossipService[F]],
    backend: SynchronizerImpl.Backend[F],
    maxPossibleDepth: Int Refined Positive,
    maxBranchingFactor: Double Refined GreaterEqual[W.`1.0`.T],
    maxDepthAncestorsRequest: Int Refined Positive
) extends Synchronizer[F] {
  type Effect[A] = EitherT[F, SyncError, A]

  import io.casperlabs.comm.gossiping.SynchronizerImpl._

  override def syncDag(
      source: Node,
      targetBlockHashes: Set[ByteString]
  ): F[Either[SyncError, Vector[BlockSummary]]] = {
    val effect = for {
      service        <- connectToGossip(source)
      tips           <- backend.tips
      justifications <- backend.justifications
      syncStateOrError <- loop(
                           service,
                           targetBlockHashes.toList,
                           tips ::: justifications,
                           SyncState.empty
                         )
      res <- syncStateOrError.fold(
              _.asLeft[Vector[BlockSummary]].pure[F], { syncState =>
                missingDependencies(syncState.dag).map { missing =>
                  if (missing.isEmpty) {
                    topologicalSort(syncState, targetBlockHashes).asRight[SyncError]
                  } else {
                    MissingDependencies(missing.toSet).asLeft[Vector[BlockSummary]]
                  }
                }
              }
            )
    } yield res
    effect.onError {
      case NonFatal(e) =>
        Log[F].error(s"Failed to sync a DAG, source: ${source.show}, reason: $e", e)
    }
  }

  private def loop(
      service: GossipService[F],
      targetBlockHashes: List[ByteString],
      knownBlockHashes: List[ByteString],
      prevSyncState: SyncState
  ): F[Either[SyncError, SyncState]] =
    if (targetBlockHashes.isEmpty) {
      prevSyncState.asRight[SyncError].pure[F]
    } else {
      traverse(
        service,
        targetBlockHashes,
        knownBlockHashes,
        prevSyncState
      ).flatMap {
        case left @ Left(_) => (left: Either[SyncError, SyncState]).pure[F]
        case Right(newSyncState) =>
          missingDependencies(newSyncState.dag)
            .flatMap(
              missing =>
                if (prevSyncState.summaries == newSyncState.summaries)
                  prevSyncState.asRight[SyncError].pure[F]
                else loop(service, missing, knownBlockHashes, newSyncState)
            )
      }
    }

  private def missingDependencies(dag: Map[ByteString, Set[ByteString]]): F[List[ByteString]] =
    danglingParents(dag).toList.filterA(backend.notInDag)

  private def danglingParents(dag: Map[ByteString, Set[ByteString]]): Set[ByteString] = {
    val allParents  = dag.keySet
    val allChildren = dag.values.foldLeft(Set.empty[ByteString]) { case (a, b) => a union b }
    allParents.diff(allChildren)
  }

  private def topologicalSort(
      syncState: SyncState,
      targetBlockHashes: Set[ByteString]
  ): Vector[BlockSummary] = {
    val allChildren = syncState.dag.values.foldLeft(Set.empty[ByteString]) {
      case (acc, next) => acc ++ next
    }
    val initialParents = (syncState.dag.keySet diff allChildren).toList
    val queue          = Queue(initialParents: _*)
    @annotation.tailrec
    def loop(acc: Vector[BlockSummary], q: Queue[ByteString]): Vector[BlockSummary] =
      if (q.isEmpty) {
        acc
      } else {
        val (next, nextQ) = q.dequeue
        val children      = syncState.dag.getOrElse(next, Set.empty)
        val updatedQueue  = nextQ.enqueue(children)
        //If empty then we already had it before in our DAG
        loop(acc ++ syncState.summaries.get(next), updatedQueue)
      }
    loop(Vector.empty, queue)
  }

  private def traverse(
      service: GossipService[F],
      targetBlockHashes: List[ByteString],
      knownBlockHashes: List[ByteString],
      prevSyncState: SyncState
  ): F[Either[SyncError, SyncState]] =
    service
      .streamAncestorBlockSummaries(
        StreamAncestorBlockSummariesRequest(
          targetBlockHashes = targetBlockHashes,
          knownBlockHashes = knownBlockHashes,
          maxDepth = maxDepthAncestorsRequest
        )
      )
      .foldWhileLeftEvalL(prevSyncState.asRight[SyncError].pure[F]) {
        case (Right(syncState), summary) =>
          val effect = for {
            _ <- notTooDeep(syncState, targetBlockHashes.toSet)
            _ <- notTooWide(syncState)
            _ <- reachable(
                  syncState,
                  summary,
                  targetBlockHashes.toSet
                )
            _ <- EitherT(
                  backend
                    .validate(summary)
                    .as(().asRight[SyncError])
                    .handleError(e => ValidationError(summary, e).asLeft[Unit])
                )
          } yield syncState.append(summary)
          effect.value.map {
            case x @ Left(_) =>
              (x: Either[SyncError, SyncState])
                .asRight[Either[SyncError, SyncState]]
            case x @ Right(_) =>
              (x: Either[SyncError, SyncState])
                .asLeft[Either[SyncError, SyncState]]
          }
        // Never happens
        case _ => Sync[F].raiseError(new RuntimeException)
      }

  private def notTooDeep(
      syncState: SyncState,
      targetBlockHashes: Set[ByteString]
  ): EitherT[F, SyncError, Unit] = {
    @annotation.tailrec
    def loop(
        parents: Set[ByteString],
        counter: Int
    ): EitherT[F, SyncError, Unit] =
      if (counter === maxPossibleDepth) {
        EitherT(
          (TooDeep(parents, maxPossibleDepth): SyncError)
            .asLeft[Unit]
            .pure[F]
        )
      } else {
        val grandparents = parents.flatMap(dependenciesFromDag(syncState.dag, _))
        if (grandparents.isEmpty) {
          EitherT(().asRight[SyncError].pure[F])
        } else {
          loop(grandparents, counter + 1)
        }
      }
    loop(targetBlockHashes.flatMap(dependenciesFromDag(syncState.dag, _)), 1)
  }

  private def dependenciesFromDag(
      dag: Map[ByteString, Set[ByteString]],
      hash: ByteString
  ): Set[ByteString] =
    dag.filter {
      case (_, children) => children(hash)
    }.keySet

  private def notTooWide(syncState: SyncState): EitherT[F, SyncError, Unit] = {
    val summariesPerRankByAscendingRank =
      syncState.summaries.values
        .map(_.getHeader.rank)
        .groupBy(identity)
        .mapValues(_.size.toDouble)
        .toList
        .sortBy(_._1)

    val maybeBranchingFactor = summariesPerRankByAscendingRank
      .sliding(2)
      .collect {
        case (_, prev) :: (_, next) :: Nil if next / prev > maxBranchingFactor.toDouble =>
          next / prev
      }
      .toStream
      .headOption

    maybeBranchingFactor.fold(EitherT(().asRight[SyncError].pure[F])) { branchingFactor =>
      EitherT((TooWide(branchingFactor, maxBranchingFactor): SyncError).asLeft[Unit].pure[F])
    }
  }

  private def reachable(
      syncState: SyncState,
      toCheck: BlockSummary,
      targetBlockHashes: Set[ByteString]
  ): EitherT[F, SyncError, Unit] = {
    @annotation.tailrec
    def loop(children: Set[ByteString], counter: Int): EitherT[F, SyncError, Unit] =
      if (counter <= maxDepthAncestorsRequest && children.nonEmpty) {
        if (children(toCheck.blockHash)) {
          EitherT(().asRight[SyncError].pure[F])
        } else {
          val parents = children.flatMap(dependenciesFromDag(syncState.dag, _))
          loop(parents, counter + 1)
        }
      } else {
        EitherT((Unreachable(toCheck, maxDepthAncestorsRequest): SyncError).asLeft[Unit].pure[F])
      }
    loop(targetBlockHashes, 1)
  }
}

object SynchronizerImpl {
  trait Backend[F[_]] {
    def tips: F[List[ByteString]]
    def justifications: F[List[ByteString]]
    def validate(blockSummary: BlockSummary): F[Unit]
    def notInDag(blockHash: ByteString): F[Boolean]
  }

  final case class SyncState(
      summaries: Map[ByteString, BlockSummary],
      dag: Map[ByteString, Set[ByteString]]
  ) {
    def append(summary: BlockSummary): SyncState =
      SyncState(
        summaries + (summary.blockHash -> summary),
        dependencies(summary).foldLeft(dag) {
          case (acc, dependency) =>
            acc + (dependency -> (acc
              .getOrElse(dependency, Set.empty[ByteString]) + summary.blockHash))
        }
      )

  }

  private def dependencies(summary: BlockSummary): List[ByteString] =
    (summary.getHeader.justifications.map(_.latestBlockHash) ++ summary.getHeader.parentHashes).toList

  object SyncState {
    val empty = SyncState(Map.empty, Map.empty)
  }
}
