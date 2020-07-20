package io.casperlabs.comm.gossiping

import cats.data.NonEmptyList
import cats.effect._
import cats.effect.concurrent._
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus._
import io.casperlabs.comm.ServiceError
import io.casperlabs.comm.ServiceError.{Internal, InvalidArgument, NotFound, Unavailable}
import io.casperlabs.comm.discovery.NodeUtils._
import io.casperlabs.comm.discovery.{Node, NodeDiscovery}
import io.casperlabs.comm.gossiping.Utils._
import io.casperlabs.comm.gossiping.downloadmanager.BlockDownloadManager
import io.casperlabs.crypto.Keys
import io.casperlabs.crypto.Keys.PublicKey
import io.casperlabs.shared.Log
import io.casperlabs.shared.ByteStringPrettyPrinter.byteStringShow
import io.casperlabs.catscontrib.effect.implicits.fiberSyntax
import scala.concurrent.duration.FiniteDuration
import scala.util.Random
import scala.util.control.NonFatal

/** Accumulate approvals for the Genesis block. When enough of them is
  * present to pass a threshold which is the preorgative of this node,
  * let the rest of the system transition to processing blocks.
  * Keep accumulating and gossiping approvals to facilitate other joiners. */
trait GenesisApprover[F[_]] {

  /** Try to get the candidate, if we already have it. */
  def getCandidate: F[Either[ServiceError, GenesisCandidate]]

  /** Try to add the approval, if we already have the candidate and it matches.
    * If successful, relay it as well.
    * Return whether we have made the transition to processing. */
  def addApproval(
      blockHash: ByteString,
      approval: Approval
  ): F[Either[ServiceError, Boolean]]

  /** Triggered once when the Genesis candidate has gathered enough signatures that this node
    * can transition to processing blocks and deploys. */
  def awaitApproval: F[ByteString]
}

object GenesisApproverImpl {
  trait Backend[F[_]] {

    /** Check that the genesis we retrieved from the bootstrap nodes has the right content.
      * If this node is one of the bonded validators then it can add its approval as well. */
    def validateCandidate(block: Block): F[Either[Throwable, Option[Approval]]]

    /** Decide if the the currently accumulated validator public keys are enough to transition to processing blocks. */
    def canTransition(block: Block, signatories: Set[ByteString]): Boolean

    // Unfortunately we can't call Validate.signature from the `comm` module.
    def validateSignature(
        blockHash: ByteString,
        publicKey: ByteString,
        signature: Signature
    ): Boolean

    def getBlock(blockHash: ByteString): F[Option[Block]]
  }

  case class Status(candidate: GenesisCandidate, block: Block)

  /** Parameters we only need if we want to compare our Genesis to a set of bootstrap nodes,
    * or to download the genesis from there in the first place. */
  case class BootstrapParams[F[_]](
      bootstraps: NonEmptyList[Node],
      pollInterval: FiniteDuration,
      downloadManager: BlockDownloadManager[F]
  )

  def apply[F[_]: Concurrent: Log: Timer](
      backend: GenesisApproverImpl.Backend[F],
      nodeDiscovery: NodeDiscovery[F],
      connectToGossip: GossipService.Connector[F],
      relayFactor: Int,
      maybeBootstrapParams: Option[BootstrapParams[F]],
      maybeGenesis: Option[Block],
      maybeApproval: Option[Approval]
  ): Resource[F, GenesisApprover[F]] =
    Resource.make[F, (GenesisApprover[F], F[Unit])] {
      for {
        statusRef <- Ref[F].of {
                      maybeGenesis.map { genesis =>
                        Status(GenesisCandidate(genesis.blockHash), genesis)
                      }
                    }
        hasTransitionedRef <- Ref[F].of(false)
        deferredApproval   <- Deferred[F, ByteString]

        approver = new GenesisApproverImpl[F](
          statusRef,
          hasTransitionedRef,
          deferredApproval,
          backend,
          nodeDiscovery,
          connectToGossip,
          relayFactor
        )
        // Pull the Genesis from bootstrap nodes if given, so we get a list of approvals
        // that we can compare agains the known validators for transitioning.
        cancelPoll <- maybeBootstrapParams.fold(().pure[F].pure[F]) { params =>
                       Concurrent[F].start {
                         approver.pollBootstraps(
                           params.bootstraps.toList,
                           params.pollInterval,
                           params.downloadManager
                         )
                       } map { fiber =>
                         fiber.cancel.attempt.void
                       }
                     }

        // Gossip, trigger as usual.
        _ <- maybeGenesis.fold(().pure[F]) { g =>
              approver.addApprovals(g.blockHash, maybeApproval.toList).void
            }
      } yield (approver, cancelPoll)
    } {
      _._2
    } map {
      _._1
    }

  /** Use by non-standalone nodes that don't construct Genesis,
    *  while there is no DAG to get it from either. */
  def fromBootstraps[F[_]: Concurrent: Log: Timer](
      backend: GenesisApproverImpl.Backend[F],
      nodeDiscovery: NodeDiscovery[F],
      connectToGossip: GossipService.Connector[F],
      relayFactor: Int,
      bootstraps: NonEmptyList[Node],
      pollInterval: FiniteDuration,
      downloadManager: BlockDownloadManager[F]
  ): Resource[F, GenesisApprover[F]] =
    apply(
      backend,
      nodeDiscovery,
      connectToGossip,
      relayFactor,
      Some(BootstrapParams(bootstraps, pollInterval, downloadManager)),
      maybeGenesis = None,
      maybeApproval = None
    )

  /** Use in standalone mode with the pre-constructed Genesis block. */
  def fromGenesis[F[_]: Concurrent: Log: Timer](
      backend: GenesisApproverImpl.Backend[F],
      nodeDiscovery: NodeDiscovery[F],
      connectToGossip: GossipService.Connector[F],
      relayFactor: Int,
      genesis: Block,
      maybeApproval: Option[Approval]
  ): Resource[F, GenesisApprover[F]] =
    apply(
      backend,
      nodeDiscovery,
      connectToGossip,
      relayFactor,
      maybeBootstrapParams = None,
      maybeGenesis = Some(genesis),
      maybeApproval = maybeApproval
    )
}

/** Maintain the state of the Genesis approval and handle gossiping.
  * Once instantiated start either the polling or inject it with a self-constructed Genesis. */
class GenesisApproverImpl[F[_]: Concurrent: Log: Timer](
    statusRef: Ref[F, Option[GenesisApproverImpl.Status]],
    hasTransitionedRef: Ref[F, Boolean],
    deferredApproval: Deferred[F, ByteString],
    backend: GenesisApproverImpl.Backend[F],
    nodeDiscovery: NodeDiscovery[F],
    connectToGossip: GossipService.Connector[F],
    relayFactor: Int
) extends GenesisApprover[F] {
  import GenesisApproverImpl.Status

  val unavailable = Unavailable("The Genesis candidate is not yet available.")

  override def awaitApproval: F[ByteString] =
    deferredApproval.get

  override def getCandidate: F[Either[ServiceError, GenesisCandidate]] =
    statusRef.get.map {
      case None         => unavailable.asLeft[GenesisCandidate]
      case Some(status) => status.candidate.asRight[ServiceError]
    }

  /** Add a new approval if it checks out and transition if possible. */
  override def addApproval(
      blockHash: ByteString,
      approval: Approval
  ): F[Either[ServiceError, Boolean]] =
    tryAddApproval(blockHash, approval) flatMap {
      case Right(Some(newStatus)) =>
        for {
          _ <- Log[F].info(
                s"Added new approval for ${blockHash.show -> "candidate"} from ${approval.approverPublicKey.show -> "approver"}; got ${newStatus.candidate.approvals.size} in total."
              )
          transitioned <- tryTransition(newStatus)
          _            <- relayApproval(blockHash, approval).forkAndLog
        } yield {
          Right(transitioned)
        }

      case Right(None) =>
        false.asRight[ServiceError].pure[F]

      case Left(ex) =>
        ex.asLeft[Boolean].pure[F]
    }

  /** Add a batch of approvals, if possible; this can only come from the bootstrap.
    * Return if transition has happened. */
  private def addApprovals(blockHash: ByteString, approvals: List[Approval]): F[Boolean] =
    approvals.traverse {
      addApproval(blockHash, _) flatMap {
        case Left(ex) =>
          Log[F].warn(s"Cannot use approval from bootstrap: $ex") *> false.pure[F]
        case Right(transitioned) =>
          transitioned.pure[F]
      }
    } flatMap {
      case Nil =>
        statusRef.get flatMap { _.fold(false.pure[F])(tryTransition) }
      case transitioned =>
        transitioned.contains(true).pure[F]
    }

  /** Get the Genesis candidate from the bootstrap node and keep polling until we can do the transition. */
  private def pollBootstraps(
      bootstraps: List[Node],
      pollInterval: FiniteDuration,
      downloadManager: BlockDownloadManager[F]
  ): F[Unit] = {

    def download(bootstrap: Node, service: GossipService[F], blockHash: ByteString): F[Block] =
      for {
        maybeSummary <- service
                         .streamBlockSummaries(StreamBlockSummariesRequest(Seq(blockHash)))
                         .headOptionL
        summary <- maybeSummary.toOptionT[F].getOrElseF {
                    Sync[F].raiseError[BlockSummary](
                      NotFound("Cannot get Genesis summary from bootstrap.")
                    )
                  }
        watch      <- downloadManager.scheduleDownload(summary, bootstrap, relay = false)
        _          <- watch
        maybeBlock <- backend.getBlock(blockHash)
        block <- maybeBlock.toOptionT[F].getOrElseF {
                  Sync[F].raiseError[Block](Internal("Cannot retrieve downloaded block."))
                }
        _ <- Log[F].info(s"Downloaded Genesis ${blockHash.show -> "candidate"} from bootstrap.")
      } yield block

    // Establish th status by downloading the block if we don't have it yet.
    // Return our own approval if we can indeed sign it.
    def maybeDownload(
        bootstrap: Node,
        service: GossipService[F],
        blockHash: ByteString
    ): F[Option[Approval]] =
      statusRef.get.flatMap {
        case None =>
          for {
            maybeBlock <- backend.getBlock(blockHash)
            block <- maybeBlock.toOptionT[F].getOrElseF {
                      download(bootstrap, service, blockHash)
                    }
            maybeApproval <- Sync[F].rethrow(backend.validateCandidate(block))
            // Add empty candidate so we can verify all approvals one by one.
            status = Status(GenesisCandidate(blockHash), block)
            _      <- statusRef.set(Some(status))
          } yield maybeApproval

        case Some(_) =>
          none.pure[F]
      }

    def loop(bootstraps: List[Node], prevApprovals: Set[Approval]): F[Unit] = {
      val bootstrap = bootstraps.head

      val trySync: F[(Set[Approval], Boolean)] = for {
        service       <- connectToGossip(bootstrap)
        candidate     <- service.getGenesisCandidate(GetGenesisCandidateRequest())
        maybeApproval <- maybeDownload(bootstrap, service, candidate.blockHash)
        newApprovals  = candidate.approvals.toSet ++ maybeApproval -- prevApprovals
        transitioned  <- addApprovals(candidate.blockHash, newApprovals.toList)
      } yield (newApprovals ++ prevApprovals, transitioned)

      trySync
        .recoverWith {
          case NonFatal(ex) =>
            Log[F].warn(
              s"Failed to sync Genesis candidate with bootstrap ${bootstrap.show -> "peer"}: $ex"
            ) *>
              (prevApprovals, false).pure[F]
        }
        .flatMap {
          case (_, transitioned) if transitioned =>
            ().pure[F]
          case (checkedApprovals, _) =>
            // Cycle through bootstrap nodes.
            val nextBootstraps = bootstraps.tail :+ bootstrap
            Timer[F].sleep(pollInterval) >> loop(nextBootstraps, checkedApprovals)
        }
    }

    loop(bootstraps, Set.empty)
  }

  /** Add the approval to the state if it's new and matches what we accept. Return the new state if it changed. */
  private def tryAddApproval(
      blockHash: ByteString,
      approval: Approval
  ): F[Either[ServiceError, Option[Status]]] =
    // Just doing the checks first, to avoid locking in futility.
    statusRef.get.map {
      case None =>
        Left(unavailable)

      case Some(Status(candidate, _)) if candidate.approvals contains approval =>
        Right(false)

      case Some(Status(candidate, _)) if candidate.blockHash != blockHash =>
        Left(InvalidArgument("The block hash doesn't match the candidate."))

      case Some(Status(_, block))
          if !block.getHeader.getState.bonds
            .map(_.validatorPublicKeyHash)
            .contains(approverAccountHash(approval)) =>
        Left(InvalidArgument("The signatory is not one of the bonded validators."))

      case _
          if !backend.validateSignature(
            blockHash,
            approval.approverPublicKey,
            approval.getSignature
          ) =>
        Left(InvalidArgument("Could not validate signature."))

      case _ =>
        // It's new.
        Right(true)
    } flatMap {
      case Left(ex) =>
        (ex: ServiceError).asLeft[Option[Status]].pure[F]

      case Right(false) =>
        none.asRight[ServiceError].pure[F]

      case Right(true) =>
        statusRef.modify {
          case Some(status @ Status(candidate, _)) if !candidate.approvals.contains(approval) =>
            val ns = status.copy(
              candidate = candidate.copy(approvals = approval +: candidate.approvals)
            )
            ns.some -> ns.some.asRight[ServiceError]

          case status =>
            status -> none.asRight[ServiceError]
        }
    }

  private def approverAccountHash(a: Approval): ByteString = {
    val alg  = a.getSignature.sigAlgorithm
    val key  = PublicKey(a.approverPublicKey.toByteArray)
    val hash = Keys.publicKeyHash(alg)(key)
    ByteString.copyFrom(hash)
  }

  private def relayApproval(blockHash: ByteString, approval: Approval): F[Unit] = {
    val candidate = blockHash.show

    def relayTo(peer: Node): F[Boolean] = {
      val tryRelay = for {
        service <- connectToGossip(peer)
        _       <- service.addApproval(AddApprovalRequest(blockHash).withApproval(approval))
        _       <- Log[F].debug(s"Relayed an approval for $candidate to ${peer.show -> "peer"}")
      } yield true

      tryRelay.recoverWith {
        case NonFatal(ex) =>
          Log[F].warn(s"Could not relay the approval for $candidate to ${peer.show -> "peer"}: $ex") *> false
            .pure[F]
      }
    }

    def loop(peers: List[Node], relayed: Int): F[Unit] =
      peers match {
        case peer :: peers if relayed < relayFactor =>
          relayTo(peer) flatMap { ok =>
            loop(peers, relayed + (if (ok) 1 else 0))
          }
        case _ =>
          Log[F].debug(s"Relayed an approval for $candidate to $relayed peers.")
      }

    nodeDiscovery.recentlyAlivePeersAscendingDistance.flatMap { peers =>
      loop(Random.shuffle(peers), 0)
    }
  }

  /** Trigger the transition if we can. Return true if the transition has happened. */
  private def tryTransition(status: Status): F[Boolean] =
    hasTransitionedRef.get.ifM(
      true.pure[F],
      Sync[F]
        .delay {
          backend.canTransition(
            status.block,
            status.candidate.approvals.map(_.approverPublicKey).toSet
          )
        }
        .ifM(
          (
            deferredApproval.complete(status.candidate.blockHash) *>
              hasTransitionedRef.set(true) *>
              Log[F].info("Transitioned to approved genesis state.")
          ).attempt *> hasTransitionedRef.get,
          false.pure[F]
        )
    )
}
