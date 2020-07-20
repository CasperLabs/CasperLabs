package io.casperlabs.casper.helper

import cats._
import cats.effect._
import cats.effect.concurrent._
import cats.implicits._
import cats.mtl.DefaultApplicativeAsk
import com.github.ghik.silencer.silent
import com.google.protobuf.ByteString
import fs2.interop.reactivestreams._
import io.casperlabs.casper
import io.casperlabs.casper.ValidatorIdentity
import io.casperlabs.casper.MultiParentCasperImpl.Broadcaster
import io.casperlabs.casper.consensus.info.DeployInfo
import io.casperlabs.casper.consensus.{Block, BlockSummary, Deploy, DeploySummary}
import io.casperlabs.casper.finality.MultiParentFinalizer
import io.casperlabs.casper.finality.votingmatrix.FinalityDetectorVotingMatrix
import io.casperlabs.casper.mocks.MockFinalityStorage
import io.casperlabs.casper.validation.{NCBValidationImpl, Validation}
import io.casperlabs.casper.{consensus, _}
import io.casperlabs.comm.discovery.{Node, NodeDiscovery, NodeIdentifier}
import io.casperlabs.comm.gossiping._
import io.casperlabs.comm.gossiping.downloadmanager._
import io.casperlabs.comm.gossiping.relaying._
import io.casperlabs.comm.gossiping.synchronization._
import io.casperlabs.crypto.Keys.PrivateKey
import io.casperlabs.mempool.DeployBuffer
import io.casperlabs.metrics.Metrics
import io.casperlabs.p2p.EffectsTestInstances._
import io.casperlabs.shared.ByteStringPrettyPrinter._
import io.casperlabs.shared._
import io.casperlabs.storage.block._
import io.casperlabs.storage.dag._
import io.casperlabs.storage.deploy.DeployStorage
import logstage.LogIO
import monix.execution.Scheduler
import monix.tail.Iterant

import scala.collection.immutable.Queue
import scala.concurrent.duration.{FiniteDuration, _}

@silent("is never used")
class GossipServiceCasperTestNode[F[_]](
    local: Node,
    genesis: consensus.Block,
    validatorId: ValidatorIdentity,
    semaphoresMap: SemaphoreMap[F, ByteString],
    semaphore: Semaphore[F],
    maybeMakeEE: Option[HashSetCasperTestNode.MakeExecutionEngineService[F]] = None,
    minTTL: FiniteDuration = 1.minute,
    chainName: String = "casperlabs",
    relaying: BlockRelaying[F],
    gossipService: GossipServiceCasperTestNodeFactory.TestGossipService[F]
)(
    implicit
    concurrentEffectF: ConcurrentEffect[F],
    blockStorage: BlockStorage[F],
    dagStorage: DagStorage[F],
    finalityStorage: FinalityStorage[F],
    deployStorage: DeployStorage[F],
    ancestorsStorage: AncestorsStorage[F],
    deployBuffer: DeployBuffer[F],
    finalityDetector: MultiParentFinalizer[F],
    val timeEff: LogicalTime[F],
    metricEff: Metrics[F],
    casperState: Cell[F, CasperState],
    val logEff: LogStub with LogIO[F]
) extends HashSetCasperTestNode[F](
      local,
      validatorId,
      genesis,
      maybeMakeEE
    ) (
      concurrentEffectF,
      blockStorage,
      dagStorage,
      deployStorage,
      deployBuffer,
      metricEff,
      casperState
    ) {

  val lastFinalizedBlockHashContainer = Ref.unsafe(genesis.blockHash)
  implicit val raiseInvalidBlock      = casper.validation.raiseValidateErrorThroughApplicativeError[F]
  implicit val broadcaster: Broadcaster[F] =
    Broadcaster.fromGossipServices(Some(validatorId), relaying)
  implicit val deploySelection = DeploySelection.create[F]()
  implicit val validationEff   = new NCBValidationImpl[F]
  implicit val eventEmitter    = NoOpsEventEmitter.create[F]

  // `addBlock` called in many ways:
  // - test proposes a block on the node that created it
  // - test tries to give a block created by node A to node B without gossiping
  // - the download manager tries to validate a block
  implicit val casperEff: MultiParentCasperImpl[F] =
    new MultiParentCasperImpl[F](
      semaphoresMap,
      new MultiParentCasperImpl.StatelessExecutor[F](
        Some(validatorId.publicKeyHashBS),
        chainName,
        upgrades = Nil,
        semaphore
      ),
      Some(validatorId),
      genesis,
      chainName,
      minTTL,
      upgrades = Nil,
      lfbRef = lastFinalizedBlockHashContainer
    )

  /** Allow RPC calls intended for this node to be processed and enqueue responses. */
  def receive(): F[Unit] = gossipService.receive()

  /** Forget RPC calls intended for this node. */
  def clearMessages(): F[Unit] = gossipService.clearMessages()

  override def tearDownNode(): F[Unit] =
    gossipService.shutdown >> super.tearDownNode()
}

trait GossipServiceCasperTestNodeFactory extends HashSetCasperTestNodeFactory {
  type TestNode[F[_]] = GossipServiceCasperTestNode[F]

  import GossipServiceCasperTestNodeFactory._
  import HashSetCasperTestNode.peerNode

  override def standaloneF[F[_]](
      genesis: consensus.Block,
      validatorId: ValidatorIdentity,
      storageSize: Long = 1024L * 1024 * 10,
      faultToleranceThreshold: Double = 0.1
  )(
      implicit
      concurrentEffectF: ConcurrentEffect[F],
      parF: Parallel[F],
      timerF: Timer[F],
      contextShift: ContextShift[F],
      scheduler: Scheduler
  ): F[GossipServiceCasperTestNode[F]] = {
    val name               = "standalone"
    val identity           = peerNode(name, 40400)
    implicit val timeEff   = new LogicalTime[F]
    implicit val log       = LogStub[F](printEnabled = false)
    implicit val metricEff = new Metrics.MetricsNOP[F]
    implicit val em        = NoOpsEventEmitter.create[F]
    implicit val nodeAsk   = makeNodeAsk(identity)(concurrentEffectF)
    implicit val functorRaiseInvalidBlock =
      casper.validation.raiseValidateErrorThroughApplicativeError[F]
    implicit val validationEff = new NCBValidationImpl[F]

    // Standalone, so nobody to relay to.
    val relaying = BlockRelayingImpl(
      scheduler,
      new TestNodeDiscovery[F](Nil),
      connectToGossip = _ => ???,
      relayFactor = 0,
      relaySaturation = 0
    )

    initStorage().flatMap {
      case (blockStorage, dagStorage, deployStorage, _, ancestorsStorage) =>
        implicit val ds: DeployStorage[F]    = deployStorage
        implicit val bs: BlockStorage[F]     = blockStorage
        implicit val gs: DagStorage[F]       = dagStorage
        implicit val as: AncestorsStorage[F] = ancestorsStorage
        for {
          implicit0(fs: FinalityStorage[F]) <- MockFinalityStorage[F](genesis.blockHash)

          casperState  <- Cell.mvarCell[F, CasperState](CasperState())
          semaphoreMap <- SemaphoreMap[F, ByteString](1)
          semaphore    <- Semaphore[F](1)
          chainName    = "casperlabs"
          minTTL       = 1.minute
          deployBuffer = DeployBuffer.create[F](chainName, minTTL)
          dag          <- dagStorage.getRepresentation
          _            <- blockStorage.put(genesis.blockHash, genesis, Map.empty)
          finalityDetector <- FinalityDetectorVotingMatrix
                               .of[F](
                                 dag,
                                 genesis.blockHash,
                                 faultToleranceThreshold,
                                 isHighway = false
                               )
          multiParentFinalizer <- MultiParentFinalizer.create(
                                   dag,
                                   genesis.blockHash,
                                   finalityDetector
                                 )
          node = new GossipServiceCasperTestNode[F](
            identity,
            genesis,
            validatorId,
            semaphoreMap,
            semaphore,
            relaying = relaying,
            gossipService = new TestGossipService[F](),
            chainName = chainName,
            minTTL = minTTL
          ) (
            concurrentEffectF,
            bs,
            gs,
            fs,
            ds,
            as,
            deployBuffer,
            multiParentFinalizer,
            timeEff,
            metricEff,
            casperState,
            log
          )
          _ <- node.initialize()
        } yield node
    }
  }

  override def networkF[F[_]](
      validatorIds: IndexedSeq[ValidatorIdentity],
      genesis: consensus.Block,
      storageSize: Long = 1024L * 1024 * 10,
      faultToleranceThreshold: Double = 0.1,
      maybeMakeEE: Option[HashSetCasperTestNode.MakeExecutionEngineService[F]] = None
  )(
      implicit
      concurrentEffectF: ConcurrentEffect[F],
      parF: Parallel[F],
      timerF: Timer[F],
      contextShift: ContextShift[F],
      scheduler: Scheduler
  ): F[IndexedSeq[GossipServiceCasperTestNode[F]]] = {
    val n     = validatorIds.length
    val names = (0 until n).map(i => s"node-$i")
    val peers = names.map(peerNode(_, 40400))

    var gossipServices = Map.empty[Node, TestGossipService[F]]

    // Use common time so if node B creates a block after node B it gets a higher timestamp.
    implicit val timeEff = new LogicalTime[F]

    val nodesF = peers
      .zip(validatorIds)
      .toList
      .traverse {
        case (peer, validatorId) =>
          implicit val log       = LogStub[F](peer.host, printEnabled = false)
          implicit val metricEff = new Metrics.MetricsNOP[F]
          implicit val emitter   = NoOpsEventEmitter.create[F]
          implicit val nodeAsk   = makeNodeAsk(peer)(concurrentEffectF)
          implicit val functorRaiseInvalidBlock =
            casper.validation.raiseValidateErrorThroughApplicativeError[F]
          implicit val validationEff = new NCBValidationImpl[F]

          val gossipService = new TestGossipService[F]()
          gossipServices += peer -> gossipService

          // Simulate the broadcast semantics.
          val nodeDiscovery = new TestNodeDiscovery[F](peers.filterNot(_ == peer).toList)

          val connectToGossip: GossipService.Connector[F] =
            peer => gossipServices(peer).asInstanceOf[GossipService[F]].pure[F]

          val relaying = BlockRelayingImpl(
            scheduler,
            nodeDiscovery,
            connectToGossip = connectToGossip,
            relayFactor = peers.size - 1,
            relaySaturation = 100,
            // Some tests assume that once `addBlock` has finished all the notifications
            // have also been sent.
            isSynchronous = true
          )

          initStorage().flatMap {
            case (blockStorage, dagStorage, deployStorage, _, ancestorsStorage) =>
              implicit val ds: DeployStorage[F]    = deployStorage
              implicit val bs: BlockStorage[F]     = blockStorage
              implicit val gs: DagStorage[F]       = dagStorage
              implicit val as: AncestorsStorage[F] = ancestorsStorage
              for {
                casperState <- Cell.mvarCell[F, CasperState](
                                CasperState()
                              )
                semaphoreMap <- SemaphoreMap[F, ByteString](1)
                semaphore    <- Semaphore[F](1)
                _            <- bs.put(genesis.blockHash, genesis, Map.empty)
                dag          <- gs.getRepresentation
                finalityDetector <- FinalityDetectorVotingMatrix
                                     .of[F](dag, genesis.blockHash, 0.1, isHighway = false)
                implicit0(fs: FinalityStorage[F]) <- MockFinalityStorage[F](genesis.blockHash)
                multiParentFinalizer <- MultiParentFinalizer.create(
                                         dag,
                                         genesis.blockHash,
                                         finalityDetector
                                       )
                chainName    = "casperlabs"
                minTTL       = 1.minute
                deployBuffer = DeployBuffer.create[F](chainName, minTTL)
                node = new GossipServiceCasperTestNode[F](
                  peer,
                  genesis,
                  validatorId,
                  semaphoreMap,
                  semaphore,
                  relaying = relaying,
                  gossipService = gossipService,
                  maybeMakeEE = maybeMakeEE,
                  chainName = chainName,
                  minTTL = minTTL
                ) (
                  concurrentEffectF,
                  bs,
                  gs,
                  fs,
                  ds,
                  as,
                  deployBuffer,
                  multiParentFinalizer,
                  timeEff,
                  metricEff,
                  casperState,
                  log
                )
                _ <- gossipService.init(
                      node.casperEff,
                      bs,
                      ds,
                      relaying,
                      connectToGossip
                    )
              } yield node
          }
      }

    for {
      nodes <- nodesF
      _     <- nodes.traverse(_.initialize())
    } yield nodes.toVector
  }
}

object GossipServiceCasperTestNodeFactory {
  class TestNodeDiscovery[F[_]: Applicative](peers: List[Node]) extends NodeDiscovery[F] {
    def discover: F[Unit]                                  = ???
    def lookup(id: NodeIdentifier): F[Option[Node]]        = ???
    def recentlyAlivePeersAscendingDistance: F[List[Node]] = peers.pure[F]
    def banTemp(node: Node): F[Unit]                       = ???
  }

  def makeNodeAsk[F[_]](node: Node)(implicit ev: Applicative[F]) =
    new DefaultApplicativeAsk[F, Node] {
      val applicative: Applicative[F] = ev
      def ask: F[Node]                = node.pure[F]
    }

  /** Accumulate messages until receive is called by the test. */
  class TestGossipService[F[_]: ContextShift: ConcurrentEffect: Timer: Time: Parallel: Log: Validation]()(
      implicit scheduler: Scheduler
  ) extends GossipService[F] {

    implicit val metrics  = new Metrics.MetricsNOP[F]
    implicit val versions = HashSetCasperTestNode.protocolVersions[F]

    /** Exercise the full underlying stack. It's what we are testing here, via the MultiParentCasper tests. */
    var underlying: GossipServiceServer[F] = _
    var shutdown: F[Unit]                  = ().pure[F]

    /** Casper is created a bit later then the TestGossipService instance. */
    def init(
        casper: MultiParentCasperImpl[F],
        blockStorage: BlockStorage[F],
        deployStorage: DeployStorage[F],
        relaying: BlockRelaying[F],
        connectToGossip: GossipService.Connector[F]
    ): F[Unit] = {

      def isInDag(blockHash: ByteString): F[Boolean] =
        for {
          dag  <- casper.dag
          cont <- dag.contains(blockHash)
        } yield cont

      for {
        blockDownloadManagerR <- BlockDownloadManagerImpl[F](
                                  maxParallelDownloads = 10,
                                  partialBlocksEnabled = true,
                                  cacheExpiry = 1.hour,
                                  connectToGossip = connectToGossip,
                                  backend = new BlockDownloadManagerImpl.Backend[F] {
                                    override def contains(blockHash: ByteString): F[Boolean] =
                                      isInDag(blockHash)

                                    override def validate(block: consensus.Block): F[Unit] =
                                      // Casper can only validate, store, but won't gossip because the Broadcaster we give it
                                      // will assume the DownloadManager will do that.
                                      // Doing this log here as it's evidently happened if we are here, and the tests expect it.
                                      Log[F].info(
                                        s"Requested missing ${block.blockHash.show -> "message"} Now validating."
                                      ) *>
                                        casper
                                          .addBlock(block) flatMap {
                                        case Valid =>
                                          Log[F].debug(
                                            s"Validated and stored block ${block.blockHash.show -> "message" -> null}"
                                          )

                                        case EquivocatedBlock =>
                                          Log[F].debug(
                                            s"Detected Equivocation on block ${block.blockHash.show -> "message" -> null}"
                                          )

                                        case other =>
                                          Log[F].debug(
                                            s"Received invalid block ${block.blockHash.show -> "message" -> null}: $other"
                                          ) *>
                                            Sync[F].raiseError(
                                              new RuntimeException(s"Non-valid status: $other")
                                            )
                                      }

                                    override def store(block: consensus.Block): F[Unit] =
                                      // Validation has already stored it.
                                      ().pure[F]

                                    override def onScheduled(summary: consensus.BlockSummary) =
                                      // The EquivocationDetector treats equivocations with children differently,
                                      // so let Casper know about the DAG dependencies up front.
                                      Log[F].debug(
                                        s"Feeding pending block to Casper: ${summary.blockHash.show -> "message" -> null}"
                                      ) *> {
                                        val partialBlock = consensus
                                          .Block()
                                          .withBlockHash(summary.blockHash)
                                          .withHeader(summary.getHeader)

                                        casper.addMissingDependencies(partialBlock)
                                      }

                                    override def onScheduled(
                                        summary: consensus.BlockSummary,
                                        source: Node
                                    ) = ().pure[F]

                                    override def onDownloaded(blockHash: ByteString) =
                                      // Calling `addBlock` during validation has already stored the block.
                                      Log[F].debug(
                                        s"Download ready for ${blockHash.show -> "message" -> null}"
                                      )

                                    override def onFailed(blockHash: ByteString) =
                                      Log[F].debug(
                                        s"Download failed for ${blockHash.show -> "message" -> null}"
                                      )

                                    override def readDeploys(deployHashes: Seq[DeployHash]) =
                                      deployStorage
                                        .reader(DeployInfo.View.FULL)
                                        .getByHashes(deployHashes.toSet)
                                        .compile
                                        .toList
                                  },
                                  relaying = relaying,
                                  retriesConf = BlockDownloadManagerImpl.RetriesConf.noRetries,
                                  egressScheduler = implicitly[Scheduler]
                                ).allocated

        deployDownloadManager = new NoOpsDeployDownloadManager[F] {}

        (blockDownloadManager, downloadManagerShutdown) = blockDownloadManagerR

        synchronizer <- SynchronizerImpl[F](
                         connectToGossip = connectToGossip,
                         backend = new SynchronizerImpl.Backend[F] {

                           override def justifications: F[List[ByteString]] =
                             for {
                               dag    <- casper.dag
                               latest <- dag.latestMessageHashes
                             } yield latest.values.flatten.toList

                           override def validate(blockSummary: consensus.BlockSummary): F[Unit] =
                             for {
                               _ <- Log[F].debug(
                                     s"Trying to validate block summary ${blockSummary.blockHash.show -> "message" -> null}"
                                   )
                               _ <- Validation[F].blockSummary(
                                     blockSummary,
                                     "casperlabs"
                                   )
                             } yield ()

                           override def notInDag(blockHash: ByteString): F[Boolean] =
                             isInDag(blockHash).map(!_)
                         },
                         maxPossibleDepth = Int.MaxValue,
                         minBlockCountToCheckWidth = Int.MaxValue,
                         maxBondingRate = 1.0,
                         maxDepthAncestorsRequest = 1, // Just so we don't see the full DAG being synced all the time. We should have justifications for early stop.
                         disableValidations = false,   // See any problems in testing.
                         maxParallel = Int.MaxValue
                       )

        server <- GossipServiceServer[F](
                   backend = new GossipServiceServer.Backend[F] {
                     override def getBlockSummary(
                         blockHash: ByteString
                     ): F[Option[consensus.BlockSummary]] =
                       Log[F].debug(
                         s"Retrieving block summary ${blockHash.show -> "message" -> null} from storage."
                       ) *> blockStorage.getBlockSummary(blockHash)

                     override def getDeploySummary(
                         deployHash: DeployHash
                     ): F[Option[DeploySummary]] =
                       Log[F].debug(
                         s"Retrieving deploy summary ${deployHash.show -> "message" -> null} from storage."
                       ) *> deployStorage.reader.getDeploySummary(deployHash)

                     override def getBlock(
                         blockHash: ByteString,
                         deploysBodiesExcluded: Boolean
                     ): F[Option[consensus.Block]] =
                       Log[F].debug(
                         s"Retrieving block ${blockHash.show -> "message" -> null} from storage."
                       ) *>
                         blockStorage
                           .get(blockHash)
                           .map(_.map(mwt => mwt.getBlockMessage))

                     override def getDeploys(deployHashes: Set[ByteString]): Iterant[F, Deploy] =
                       Iterant.fromReactivePublisher {
                         deployStorage
                           .reader(DeployInfo.View.FULL)
                           .getByHashes(deployHashes)
                           .toUnicastPublisher
                       }

                     override def latestMessages: F[Set[Block.Justification]] = ???

                     override def dagTopoSort(startRank: Long, endRank: Long) = ???
                   },
                   synchronizer = synchronizer,
                   connector = _ => ???,
                   deployDownloadManager = deployDownloadManager,
                   blockDownloadManager = blockDownloadManager,
                   // Not testing the genesis ceremony.
                   genesisApprover = new GenesisApprover[F] {
                     override def getCandidate = ???
                     override def addApproval(
                         blockHash: ByteString,
                         approval: consensus.Approval
                     )                          = ???
                     override def awaitApproval = ???
                   },
                   maxChunkSize = 1024 * 1024,
                   maxParallelBlockDownloads = 10,
                   deployGossipEnabled = false
                 )
      } yield {
        underlying = server
        shutdown = downloadManagerShutdown
      }
    }

    /** The tests assume we are using the TransportLayer and messages are fire-and-forget.
      * The main use of `receives and `clearMessages` is to pass over blocks and to simulate
      * dropping the block on the receiver end.
      * The RPC works differently when it comes to pulling dependencies, it doesn't ask one by one,
      * and because the tests assume to know how many exactly messages get passed and calls `receive`
      * so many times. But we can preserve most of the spirit of the test by returning `true` here
      * (they assume broadcast) and not execute the underlying call if `clearMessages` is called;
      * but we can let the other methods work out on their own without suspension. We just have to
      * make sure that if the test calls `receive` then all the async calls finish before we return
      * to do any assertions. */
    val notificationQueue = Ref.unsafe[F, Queue[F[Unit]]](Queue.empty)

    /** With the TransportLayer this would mean the target node receives the full block and adds it.
      * We have to allow `newBlocks` to return for the original block to be able to finish adding,
      * so maybe we can return `true`, and call the underlying service later. But then we have to
      * allow it to play out all async actions, such as downloading blocks, syncing the DAG, etc. */
    def receive(): F[Unit] = {
      // It can be tricky to line up the semantics of the notifications between the TransportLayer
      // and the GossipService. At least in one test the queue was longer and the node wasn't processing
      // the message the test was expecting it to, it was still reacting to an earlier one. To circumvent
      // these tests failures process all enqueued messages.
      def receiveOne =
        for {
          maybeNotification <- notificationQueue.modify { q =>
                                q dequeueOption match {
                                  case Some((notificaton, rest)) =>
                                    rest -> Some(notificaton)
                                  case None =>
                                    q -> None
                                }
                              }
          _ <- maybeNotification.fold(().pure[F])(identity)
        } yield maybeNotification.nonEmpty

      def loop(): F[Unit] =
        receiveOne flatMap {
          case false => ().pure[F]
          case true  => loop()
        }
      loop()
    }

    /** With the TransportLayer this would mean the target node won't process a message.
      * For us it could mean that it receives the `newBlocks` notification but after that
      * we don't let it play out the async operations, for example by returning errors for
      * all requests it started. */
    def clearMessages(): F[Unit] =
      for {
        q <- notificationQueue.get
        _ <- Log[F].debug(s"Forgetting ${q.size} notifications.")
        _ <- notificationQueue.set(Queue.empty)
      } yield ()

    override def newDeploys(request: NewDeploysRequest): F[NewDeploysResponse] =
      Log[F].info(
        s"Received notification about deploy ${PrettyPrinter.buildString(request.deployHashes.head) -> "message" -> null}"
      ) *>
        notificationQueue
          .update { q =>
            q enqueue underlying
              .newDeploys(request)
              .void
          }
          .as(NewDeploysResponse(isNew = true))

    override def newBlocks(request: NewBlocksRequest): F[NewBlocksResponse] =
      Log[F].info(
        s"Received notification about block ${PrettyPrinter.buildString(request.blockHashes.head) -> "message" -> null}"
      ) *>
        notificationQueue
          .update { q =>
            // Using `newBlocksSynchronous` so we finish as soon as it's done.
            q enqueue underlying
              .newBlocksSynchronous(request, skipRelaying = false)
              .void
          }
          .as(NewBlocksResponse(isNew = true))

    override def getBlockChunked(request: GetBlockChunkedRequest): Iterant[F, Chunk] =
      Iterant
        .liftF(
          Log[F].info(
            s"Received request for block ${PrettyPrinter.buildString(request.blockHash) -> "message" -> null} Response sent."
          )
        )
        .flatMap { _ =>
          underlying.getBlockChunked(request)
        }

    override def streamDeploysChunked(request: StreamDeploysChunkedRequest): Iterant[F, Chunk] =
      Iterant
        .liftF(
          Log[F].info(
            s"Received request for deploys ${request.deployHashes.map(PrettyPrinter.buildString).mkString("[", ",", "]") -> "deploys" -> null}."
          )
        )
        .flatMap { _ =>
          underlying.streamDeploysChunked(request)
        }

    override def streamAncestorBlockSummaries(
        request: StreamAncestorBlockSummariesRequest
    ): Iterant[F, consensus.BlockSummary] =
      Iterant
        .liftF(Log[F].info(s"Received request for ancestors of ${request.targetBlockHashes
          .map(PrettyPrinter.buildString) -> "blocks" -> null}"))
        .flatMap { _ =>
          underlying.streamAncestorBlockSummaries(request)
        }

    // The following methods are not tested in these suites.

    override def addApproval(request: AddApprovalRequest): F[Unit] = ???
    override def getGenesisCandidate(
        request: GetGenesisCandidateRequest
    ): F[consensus.GenesisCandidate] = ???
    override def streamLatestMessages(
        request: StreamLatestMessagesRequest
    ): Iterant[F, Block.Justification] = ???
    override def streamBlockSummaries(
        request: StreamBlockSummariesRequest
    ): Iterant[F, consensus.BlockSummary] = ???
    override def streamDeploySummaries(
        request: StreamDeploySummariesRequest
    ): Iterant[F, DeploySummary] = ???
    override def streamDagSliceBlockSummaries(
        request: StreamDagSliceBlockSummariesRequest
    ): Iterant[F, BlockSummary] = ???
  }
}
