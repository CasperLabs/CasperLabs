package io.casperlabs.node

import java.nio.file.Path
import java.util.concurrent.TimeUnit

import cats._
import cats.effect._
import cats.effect.concurrent.{Ref, Semaphore}
import cats.mtl.MonadState
import cats.syntax.applicative._
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.show._
import com.google.protobuf.ByteString
import com.olegpy.meow.effects._
import io.casperlabs.casper.DeploySelection.DeploySelection
import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper.MultiParentCasperImpl.Broadcaster
import io.casperlabs.casper.MultiParentCasperRef.MultiParentCasperRef
import io.casperlabs.casper._
import io.casperlabs.casper.consensus.Block
import io.casperlabs.casper.genesis.Genesis
import io.casperlabs.casper.util.CasperLabsProtocol
import io.casperlabs.catscontrib.Catscontrib._
import io.casperlabs.catscontrib.TaskContrib._
import io.casperlabs.catscontrib._
import io.casperlabs.catscontrib.effect.implicits.syncId
import io.casperlabs.comm._
import io.casperlabs.comm.discovery.NodeUtils._
import io.casperlabs.comm.discovery._
import io.casperlabs.comm.gossiping.WaitHandle
import io.casperlabs.comm.gossiping.relaying.{BlockRelaying, DeployRelaying}
import io.casperlabs.comm.grpc.SslContexts
import io.casperlabs.comm.rp._
import io.casperlabs.ipc.ChainSpec
import io.casperlabs.mempool.DeployBuffer
import io.casperlabs.metrics.Metrics
import io.casperlabs.node.api.{EventStream, VersionInfo}
import io.casperlabs.node.api.graphql.FinalizedBlocksStream
import io.casperlabs.node.casper.consensus.Consensus
import io.casperlabs.node.configuration.Configuration
import io.casperlabs.shared._
import io.casperlabs.smartcontracts.{ExecutionEngineService, GrpcExecutionEngineService}
import io.casperlabs.storage.SQLiteStorage
import io.casperlabs.storage.block._
import io.casperlabs.storage.dag._
import io.casperlabs.storage.deploy.DeployStorageWriter
import io.casperlabs.storage.era._
import io.casperlabs.storage.util.fileIO.IOError._
import io.casperlabs.storage.util.fileIO._
import io.netty.handler.ssl.ClientAuth
import monix.eval.Task
import monix.execution.Scheduler
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.Location

import scala.concurrent.duration._

class NodeRuntime private[node] (
    conf: Configuration,
    chainSpec: ChainSpec,
    id: NodeIdentifier,
    mainScheduler: Scheduler
)(
    implicit log: Log[Task],
    logId: Log[Id],
    schedulerFactory: SchedulerFactory
) {
  import NodeRuntime.RelayingProxy

  private[this] val loopScheduler =
    schedulerFactory.fixedPool("loop", 2)

  // Bounded thread pool for incoming traffic. Limited thread pool size so loads of request cannot exhaust all resources.
  private[this] val ingressScheduler = {
    val cpus  = java.lang.Runtime.getRuntime.availableProcessors()
    val multi = conf.server.parallelismCpuMultiplier.value
    schedulerFactory.forkJoin(
      parallelism = Math.max((cpus * multi).toInt, conf.server.minParallelism.value),
      maxThreads = conf.server.ingressThreads.value,
      name = "ingress-io"
    )
  }

  // Unbounded thread pool for outgoing, blocking IO. It is recommended to have unlimited thread pools for waiting on IO.
  private[this] val egressScheduler =
    schedulerFactory.cached("egress-io", 2, Int.MaxValue)

  private[this] def dbConnScheduler(name: String, connections: Int, threads: Int) =
    schedulerFactory.cached(
      s"db-conn-$name",
      connections,
      math.max(connections, threads)
    )
  private[this] def dbIOScheduler(name: String, connections: Int) =
    schedulerFactory.cached(s"db-io-$name", connections, Int.MaxValue)

  implicit val raiseIOError: RaiseIOError[Task] = IOError.raiseIOErrorThroughSync[Task]

  implicit val concurrentEffectForEffect = {
    implicit val s = mainScheduler
    implicitly[ConcurrentEffect[Task]]
  }

  // intra-node gossiping port.
  private val port         = conf.server.port
  private val kademliaPort = conf.server.kademliaPort

  /**
    * Main node entry. It will:
    * 1. set up configurations
    * 2. create instances of typeclasses
    * 3. run the node program.
    */
  // TODO: Resolve scheduler chaos in Runtime, RuntimeManager and CasperPacketHandler

  val main: Task[Unit] = {
    implicit val metricsId: Metrics[Id] = diagnostics.effects.metrics[Id](syncId)

    // SSL context to use for the public facing API.
    val maybeApiSslContext = if (conf.grpc.useTls) {
      val (cert, key) = conf.tls.readPublicApiCertAndKey
      Option(SslContexts.forServer(cert, key, ClientAuth.NONE))
    } else None

    implicit val metrics = diagnostics.effects.metrics[Task]

    val resources = for {
      implicit0(executionEngineService: ExecutionEngineService[Task]) <- GrpcExecutionEngineService[
                                                                          Task
                                                                        ](
                                                                          conf.grpc.socket,
                                                                          conf.server.maxMessageSize.value,
                                                                          conf.server.engineParallelism.value
                                                                        )
      databaseMetrics <- Resource.liftF(diagnostics.HikariMetricsTrackerFactory[Task]())

      //TODO: We may want to adjust threading model for better performance
      (writeTransactor, readTransactor) <- effects.doobieTransactors(
                                            conf,
                                            connectEC = dbConnScheduler,
                                            transactEC = dbIOScheduler,
                                            metricsTrackerFactory = databaseMetrics
                                          )
      _ <- Resource.liftF(runRdmbsMigrations(conf.server.dataDir))

      _ <- databaseMetrics.periodicPoolMetrics()
      _ <- effects.periodicStorageSizeMetrics(conf)
      _ <- effects.periodicThreadPoolMetrics(schedulerFactory)

      implicit0(
        storage: SQLiteStorage.CombinedStorage[Task]
      ) <- Resource.liftF(
            SQLiteStorage.create[Task](
              dagStorageChunkSize = conf.blockstorage.dagStreamChunkSize,
              deployStorageChunkSize = conf.blockstorage.deployStreamChunkSize,
              tickUnit = TimeUnit.MILLISECONDS,
              readXa = readTransactor,
              writeXa = writeTransactor,
              wrapBlockStorage = (underlyingBlockStorage: BlockStorage[Task]) =>
                CachingBlockStorage[Task](
                  underlyingBlockStorage,
                  maxSizeBytes = conf.blockstorage.cacheMaxSizeBytes
                ),
              wrapDagStorage = (underlyingDagStorage: DagStorage[Task]
                with DagRepresentation[Task]
                with AncestorsStorage[Task]
                with FinalityStorage[Task]) =>
                CachingDagStorage[Task](
                  underlyingDagStorage,
                  maxSizeBytes = conf.blockstorage.cacheMaxSizeBytes,
                  neighborhoodAfter = conf.blockstorage.cacheNeighborhoodAfter,
                  neighborhoodBefore = conf.blockstorage.cacheNeighborhoodBefore
                ).map(
                  cache =>
                    // Compiler fails to infer the proper type without this
                    cache: DagStorage[Task] with DagRepresentation[Task] with FinalityStorage[
                      Task
                    ] with AncestorsStorage[Task]
                ),
              wrapEraStorage = (underlyingEraStorage: EraStorage[Task]) =>
                CachingEraStorage[Task](underlyingEraStorage)
            )
          )

      _ <- Resource.liftF {
            Task
              .delay {
                log.info("Cleaning storage ...")
                storage.clear()
              }
              .whenA(conf.server.cleanBlockStorage)
          }

      genesis <- makeGenesis[Task](chainSpec)

      maybeValidatorId <- Resource.liftF(ValidatorIdentity.fromConfig[Task](conf.casper))

      implicit0(eventStream: EventStream[Task]) <- Resource.pure[Task, EventStream[Task]](
                                                    EventStream
                                                      .create[Task](
                                                        egressScheduler,
                                                        conf.server.eventStreamBufferSize.value
                                                      )
                                                  )

      implicit0(deployBuffer: DeployBuffer[Task]) <- Resource.pure[Task, DeployBuffer[Task]](
                                                      DeployBuffer.create[Task](
                                                        chainSpec.getGenesis.name,
                                                        conf.casper.minTtl
                                                      )
                                                    )

      implicit0(state: MonadState[Task, RPConf]) <- Resource
                                                     .liftF((for {
                                                       local      <- localPeerNode[Task]()
                                                       bootstraps <- initPeers[Task]
                                                       conf <- rpConf[Task](
                                                                local
                                                                  .withChainId(genesis.blockHash),
                                                                bootstraps.map(
                                                                  _.withChainId(genesis.blockHash)
                                                                )
                                                              )
                                                     } yield conf.stateInstance))
      implicit0(nodeAsk: NodeAsk[Task])            = effects.peerNodeAsk(state)
      implicit0(boostrapsAsk: BootstrapsAsk[Task]) = effects.bootstrapsAsk(state)

      lfb <- Resource.liftF[Task, BlockHash](
              storage.getLastFinalizedBlock
            )

      implicit0(finalizedBlocksStream: FinalizedBlocksStream[Task]) <- Resource.suspend(
                                                                        FinalizedBlocksStream
                                                                          .of[Task](lfb)
                                                                      )

      implicit0(nodeDiscovery: NodeDiscovery[Task]) <- effects.nodeDiscovery(
                                                        id,
                                                        kademliaPort,
                                                        conf.server.defaultTimeout,
                                                        conf.server.alivePeersCacheExpirationPeriod,
                                                        conf.server.relayFactor.value,
                                                        conf.server.relaySaturation.value,
                                                        ingressScheduler,
                                                        egressScheduler
                                                      )

      // TODO: Only a loop started with the TransportLayer keeps filling this up,
      // so if we use the GossipService it's going to stay empty. The diagnostics
      // should use NodeDiscovery instead.
      implicit0(connectionsCell: Connect.ConnectionsCell[Task]) <- Resource.liftF(
                                                                    effects.rpConnections
                                                                  )

      implicit0(multiParentCasperRef: MultiParentCasperRef[Task]) <- Resource.liftF(
                                                                      MultiParentCasperRef
                                                                        .of[Task]
                                                                    )

      implicit0(protocolVersions: CasperLabsProtocol[Task]) <- Resource.liftF(
                                                                CasperLabsProtocol
                                                                  .fromChainSpec[Task](chainSpec)
                                                              )

      implicit0(deploySelection: DeploySelection[Task]) <- Resource.liftF(
                                                            DeploySelection
                                                              .createMetered[Task]
                                                              .pure[Task]
                                                          )

      implicit0(relayingProxy: RelayingProxy[Task]) <- Resource.liftF[Task, RelayingProxy[Task]](
                                                        RelayingProxy[Task]
                                                      )

      isSyncedRef <- Resource.liftF(Ref.of[Task, Boolean](false))

      implicit0(consensusEff: Consensus[Task]) <- {
        if (conf.highway.enabled)
          Resource.liftF(Log[Task].info(s"Starting in Highway mode.")) *>
            casper.consensus.Highway(conf, chainSpec, maybeValidatorId, genesis, isSyncedRef)
        else
          Resource.liftF(Log[Task].info(s"Starting in NCB mode.")) as {
            casper.consensus
              .NCB[Task](conf, chainSpec, maybeValidatorId)
          }
      }

      // Creating with 0 permits initially, enabled after the initial synchronization.
      blockApiLock <- Resource.liftF(Semaphore[Task](0))

      // Set up gossiping machinery and start synchronizing with the network.
      relaying <- casper.gossiping
                   .apply[
                     Task
                   ](
                     port,
                     conf,
                     maybeValidatorId,
                     genesis,
                     ingressScheduler,
                     egressScheduler,
                     // Enable block production after sync.
                     onInitialSyncCompleted = blockApiLock.releaseN(1) *> isSyncedRef.set(true)
                   )

      blockRelaying                                   = relaying._1
      implicit0(deployRelaying: DeployRelaying[Task]) = relaying._2

      // Update the relaying we passed to consensus to use the real one.
      _ <- Resource.liftF(relayingProxy.set(blockRelaying))

      // The BlockAPI does relaying of blocks its creating on its own and wants to have a broadcaster.
      implicit0(broadcaster: Broadcaster[Task]) <- Resource.liftF(
                                                    MultiParentCasperImpl.Broadcaster
                                                      .fromGossipServices(
                                                        maybeValidatorId,
                                                        blockRelaying
                                                      )
                                                      .pure[Task]
                                                  )

      // For now just either starting the auto-proposer or not, but ostensibly we
      // could pass it the flag to run or not and also wire it into the ControlService
      // so that the operator can turn it on/off on the fly.
      _ <- AutoProposer[Task](
            checkInterval = conf.casper.autoProposeCheckInterval,
            ballotInterval = conf.casper.autoProposeBallotInterval,
            accInterval = conf.casper.autoProposeAccInterval,
            accCount = conf.casper.autoProposeAccCount,
            blockApiLock = blockApiLock
          ).whenA(conf.casper.autoProposeEnabled && !conf.highway.enabled)

      statusSvc = new api.StatusInfo.Service[Task](
        conf,
        chainSpec,
        genesis,
        maybeValidatorId,
        isSyncedRef.get,
        readTransactor
      )

      _ <- api.Servers
            .internalServersR(
              conf.grpc.portInternal,
              conf.server.maxMessageSize.value,
              conf.server.shutdownTimeout,
              ingressScheduler,
              blockApiLock,
              maybeApiSslContext
            )

      _ <- api.Servers.externalServersR[Task](
            conf.grpc.portExternal,
            conf.server.maxMessageSize.value,
            conf.server.shutdownTimeout,
            ingressScheduler,
            maybeApiSslContext,
            isDeployEnabled = maybeValidatorId.nonEmpty || conf.server.deployGossipEnabled
          )

      _ <- api.Servers.httpServerR[Task](
            conf.server.httpPort,
            conf,
            statusSvc,
            id,
            ingressScheduler
          )
    } yield (nodeAsk, nodeDiscovery, storage.writer, deployBuffer)

    resources.allocated flatMap {
      case ((nodeAsk, nodeDiscovery, deployStorage, deployBuffer), release) =>
        handleUnrecoverableErrors {
          nodeProgram(nodeAsk, nodeDiscovery, deployStorage, deployBuffer, release)
        }
    }

  }

  private def runRdmbsMigrations(serverDataDir: Path): Task[Unit] =
    Task.delay {
      val db = serverDataDir.resolve("sqlite.db").toString
      val conf =
        Flyway
          .configure()
          .dataSource(s"jdbc:sqlite:$db", "", "")
          .locations(new Location("classpath:db/migration"))
      val flyway = conf.load()
      flyway.migrate()
      ()
    }

  /** Start periodic tasks as fibers. They'll automatically stop during shutdown. */
  private def nodeProgram(
      implicit
      localAsk: NodeAsk[Task],
      nodeDiscovery: NodeDiscovery[Task],
      deployStorageWriter: DeployStorageWriter[Task],
      deployBuffer: DeployBuffer[Task],
      release: Task[Unit]
  ): Task[Unit] = {

    val time = effects.time

    val info: Task[Unit] =
      if (conf.casper.standalone)
        Log[Task].info(s"Starting stand-alone node.")
      else
        Log[Task].info(
          s"Starting node that will bootstrap from ${conf.server.bootstrap.map(_.show).mkString(", ") -> "bootstraps"}"
        )

    val cleanupDiscardedDeploysLoop: Task[Unit] = for {
      _ <- deployStorageWriter.cleanupDiscarded(1.hour)
      _ <- time.sleep(1.minute)
    } yield ()

    val checkPendingDeploysExpirationLoop: Task[Unit] = for {
      _ <- DeployBuffer[Task].discardExpiredDeploys(24.hours)
      _ <- time.sleep(1.minute)
    } yield ()

    for {
      _ <- addShutdownHook(release)
      _ <- info
      _ <- Task
            .defer(cleanupDiscardedDeploysLoop.forever)
            .executeOn(loopScheduler)
            .start

      _ <- Task
            .defer(checkPendingDeploysExpirationLoop.forever)
            .executeOn(loopScheduler)
            .start

      localNode <- localAsk.ask
      _         <- Log[Task].info(s"Listening for traffic on ${localNode.show -> "peer"}.")
      // This loop will keep the program from exiting until shutdown is initiated.
      _ <- NodeDiscovery[Task].discover.attemptAndLog.executeOn(loopScheduler)
    } yield ()
  }

  private def shutdown(release: Task[Unit]): Unit = {
    implicit val s = egressScheduler
    // Everything has been moved to Resources.
    val task = for {
      _ <- log.info("Shutting down...")
      _ <- release
      _ <- log.info("Goodbye.")
    } yield ()
    // Run the release synchronously so that we can see the final message.
    task.runSyncUnsafe(1.minute)
  }

  private def addShutdownHook(release: Task[Unit]): Task[Unit] =
    Task.delay(
      sys.addShutdownHook(shutdown(release))
    )

  /**
    * Handles unrecoverable errors in program. Those are errors that should not happen in properly
    * configured environment and they mean immediate termination of the program
    */
  private def handleUnrecoverableErrors(prog: Task[Unit]): Task[Unit] =
    prog
      .onErrorHandleWith { th =>
        log.error("Caught unhandable error. Exiting. Stacktrace below.") *> Task.delay {
          th.printStackTrace()
        }
      } *> Task.delay(System.exit(1)).as(Right(()))

  private def rpConf[F[_]: Sync](local: Node, bootstraps: List[Node]) =
    Ref.of[F, RPConf](
      RPConf(
        local,
        bootstraps,
        conf.server.defaultTimeout,
        ClearConnectionsConf(
          conf.server.maxNumOfConnections.value,
          // TODO read from conf
          numOfConnectionsPinged = 10
        )
      )
    )

  private def localPeerNode[F[_]: Sync: Log]() =
    WhoAmI
      .fetchLocalPeerNode[F](
        conf.server.host,
        conf.server.port,
        conf.server.kademliaPort,
        conf.server.noUpnp,
        id,
        VersionInfo.get
      )

  private def initPeers[F[_]: MonadThrowable]: F[List[NodeWithoutChainId]] =
    conf.server.bootstrap match {
      case Nil if !conf.casper.standalone =>
        MonadThrowable[F].raiseError(
          new java.lang.IllegalStateException(
            "Not in standalone mode but there's no bootstrap configured!"
          )
        )
      case nodes =>
        nodes.pure[F]
    }

  private def makeGenesis[F[_]: MonadThrowable](chainSpec: ChainSpec)(
      implicit E: ExecutionEngineService[F],
      L: Log[F],
      B: BlockStorage[F],
      FS: FinalityStorage[F]
  ): Resource[F, Block] =
    Resource.liftF[F, Block] {
      for {
        _       <- Log[F].info(s"Constructing Genesis block...")
        genesis <- Genesis.fromChainSpec[F](chainSpec.getGenesis)
        _ <- Log[F].info(
              s"Genesis hash is ${PrettyPrinter.buildString(genesis.getBlockMessage.blockHash) -> "genesis" -> null}"
            )
      } yield genesis.getBlockMessage
    }
}

object NodeRuntime {
  def apply(
      conf: Configuration,
      chainSpec: ChainSpec
  )(
      implicit
      scheduler: Scheduler,
      schedulerFactory: SchedulerFactory,
      log: Log[Task],
      logId: Log[Id]
  ): Task[NodeRuntime] =
    for {
      id      <- NodeEnvironment.create(conf)
      runtime <- Task.delay(new NodeRuntime(conf, chainSpec, id, scheduler))
    } yield runtime

  /** There's a forward dependency between Highway consensus and the gossiping. */
  class RelayingProxy[F[_]: Monad](
      underlyingRef: Ref[F, Option[BlockRelaying[F]]]
  ) extends BlockRelaying[F] {
    override def relay(hashes: List[ByteString], sources: Set[Node]): F[WaitHandle[F]] =
      underlyingRef.get.flatMap {
        case None =>
          ().pure[F].pure[F]
        case Some(underlying) =>
          underlying.relay(hashes, sources)
      }

    def set(underlying: BlockRelaying[F]) =
      underlyingRef.set(Some(underlying))
  }
  object RelayingProxy {
    def apply[F[_]: Sync]: F[RelayingProxy[F]] =
      Ref.of[F, Option[BlockRelaying[F]]](None) map (new RelayingProxy(_))
  }

}
