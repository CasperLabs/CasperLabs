package io.casperlabs.node.casper

import java.util.concurrent.{TimeUnit, TimeoutException}

import cats._
import cats.data.NonEmptyList
import cats.effect._
import cats.effect.concurrent.Ref
import cats.effect.implicits._
import cats.implicits._
import com.google.protobuf.ByteString
import eu.timepit.refined.auto._
import fs2.interop.reactivestreams._
import io.casperlabs.casper._
import io.casperlabs.casper.consensus._
import io.casperlabs.casper.consensus.info.DeployInfo
import io.casperlabs.casper.util.CasperLabsProtocol
import io.casperlabs.casper.validation.Validation
import io.casperlabs.comm.ServiceError.{InvalidArgument, Unavailable}
import io.casperlabs.comm.discovery.NodeUtils._
import io.casperlabs.comm.discovery.{Node, NodeDiscovery}
import io.casperlabs.comm.gossiping._
import io.casperlabs.comm.gossiping.downloadmanager._
import io.casperlabs.comm.gossiping.relaying._
import io.casperlabs.comm.gossiping.synchronization._
import io.casperlabs.comm.grpc._
import io.casperlabs.comm.{CachedConnections, NodeAsk}
import io.casperlabs.crypto.Keys.PublicKey
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.mempool.DeployBuffer
import io.casperlabs.metrics.Metrics
import io.casperlabs.node.casper.consensus.Consensus
import io.casperlabs.node.configuration.Configuration
import io.casperlabs.shared.ByteStringPrettyPrinter._
import io.casperlabs.shared.{Log, Time}
import io.casperlabs.storage.block._
import io.casperlabs.storage.dag._
import io.casperlabs.storage.deploy.DeployStorage
import io.grpc.netty.{NegotiationType, NettyChannelBuilder}
import io.grpc.{ManagedChannel, Server}
import io.netty.handler.ssl.{ClientAuth, SslContext}
import monix.eval.TaskLike
import monix.execution.Scheduler
import monix.tail.Iterant

import scala.concurrent.duration._
import scala.util.Random
import scala.util.control.NonFatal

/** Create the Casper stack using the GossipService. */
package object gossiping {

  private implicit val metricsSource: Metrics.Source =
    Metrics.Source(Metrics.Source(Metrics.BaseSource, "node"), "gossiping")

  def apply[F[_]: ContextShift: Parallel: ConcurrentEffect: Log: Metrics: Time: Timer: BlockStorage: DagStorage: DeployStorage: NodeDiscovery: NodeAsk: CasperLabsProtocol: Consensus: DeployBuffer](
      port: Int,
      conf: Configuration,
      maybeValidatorId: Option[ValidatorIdentity],
      genesis: Block,
      ingressScheduler: Scheduler,
      egressScheduler: Scheduler,
      onInitialSyncCompleted: F[Unit]
  )(
      implicit logId: Log[Id],
      metricsId: Metrics[Id]
  ): Resource[F, (BlockRelaying[F], DeployRelaying[F])] = {

    val (cert, key) = conf.tls.readIntraNodeCertAndKey

    // SSL context to use when connecting to another node.
    val clientSslContext = SslContexts.forClient(cert, key)
    // SSL context to use when another node connects to us.
    val serverSslContext = SslContexts.forServer(cert, key, ClientAuth.REQUIRE)

    // For client stub to GossipService conversions.
    implicit val oi = ObservableIterant.default(implicitly[Effect[F]], egressScheduler)

    for {
      cachedConnections <- makeConnectionsCache(
                            conf,
                            clientSslContext,
                            egressScheduler
                          )

      connectToGossip: GossipService.Connector[F] = (node: Node) => {
        cachedConnections.connection(node, enforce = true) map { chan =>
          new GossipingGrpcMonix.GossipServiceStub(chan)
        } map { stub =>
          implicit val s = egressScheduler
          GrpcGossipService.toGossipService(
            stub,
            onError = {
              case Unavailable(_) | _: TimeoutException =>
                disconnect(cachedConnections, node) *> NodeDiscovery[F].banTemp(node)
            },
            timeout = conf.server.defaultTimeout
          )
        }
      }

      blockRelaying  <- makeBlockRelaying(conf, connectToGossip, egressScheduler)
      deployRelaying <- makeDeployRelaying(conf, connectToGossip, egressScheduler)

      synchronizer <- makeSynchronizer(
                       conf,
                       connectToGossip
                     )

      isInitialSyncDoneRef <- Resource.liftF(Ref.of[F, Boolean](false))

      deployDownloadManager <- makeDeployDownloadManager(
                                conf,
                                connectToGossip,
                                deployRelaying,
                                egressScheduler
                              )

      blockDownloadManager <- makeBlockDownloadManager(
                               conf,
                               connectToGossip,
                               blockRelaying,
                               synchronizer,
                               maybeValidatorId,
                               isInitialSyncDoneRef,
                               egressScheduler
                             )

      genesisApprover <- makeGenesisApprover(
                          conf,
                          maybeValidatorId,
                          connectToGossip,
                          blockDownloadManager,
                          genesis
                        )

      // Make sure consensus is initialised before the synchronizer is resumed.
      awaitApproval <- makeFiberResource {
                        genesisApprover.awaitApproval.flatMap(Consensus[F].onGenesisApproved(_))
                      }

      // Start syncing with the bootstrap and/or some others in the background.
      awaitSynchronization <- Resource
                               .pure[F, Boolean](
                                 conf.server.bootstrap.nonEmpty && !conf.casper.standalone
                               )
                               .ifM(
                                 makeInitialSynchronizer(
                                   conf,
                                   blockDownloadManager,
                                   synchronizer,
                                   connectToGossip,
                                   awaitApproval
                                 ),
                                 Resource.pure[F, F[Unit]](().pure[F])
                               )

      // Let the outside world know when we're done.
      _ <- makeFiberResource {
            awaitSynchronization >> isInitialSyncDoneRef.set(true) >> onInitialSyncCompleted
          }

      // The stashing synchronizer waits for Genesis approval and the initial synchronization
      // to complete before actually syncing anything. We had to create the underlying
      // synchronizer earlier because the Download Manager needs a reference to it,
      // and the Download Manager is needed by the Initial Synchronizer.
      stashingSynchronizer <- Resource.liftF {
                               StashingSynchronizer.wrap(
                                 synchronizer,
                                 awaitApproval >> awaitSynchronization,
                                 isInDag[F](_)
                               )
                             }

      rateLimiter <- makeRateLimiter[F](conf)

      gossipServiceServer <- makeGossipServiceServer(
                              conf,
                              stashingSynchronizer,
                              connectToGossip,
                              deployDownloadManager,
                              blockDownloadManager,
                              genesisApprover
                            )

      _ <- startGrpcServer(
            gossipServiceServer,
            rateLimiter,
            genesis.blockHash,
            serverSslContext,
            conf,
            port,
            ingressScheduler
          )

      _ <- makePeriodicSynchronizer(
            conf,
            gossipServiceServer,
            connectToGossip,
            awaitApproval >> awaitSynchronization
          )

      // Start a loop to periodically print peer count, new and disconnected peers, based on NodeDiscovery.
      _ <- makePeerCountPrinter

    } yield (blockRelaying, deployRelaying)
  }

  /** Cached connection resources, closed at the end. */
  private def makeConnectionsCache[F[_]: Concurrent: Log: Metrics](
      conf: Configuration,
      clientSslContext: SslContext,
      egressScheduler: Scheduler
  ): Resource[F, CachedConnections[F, Unit]] = Resource {
    for {
      makeCache <- CachedConnections[F, Unit]
      cache = makeCache {
        makeGossipChannel(
          _,
          conf,
          clientSslContext,
          egressScheduler
        )
      }
      shutdown = cache.read.flatMap { s =>
        s.connections.toList.traverse {
          case (peer, chan) =>
            Log[F].debug(s"Closing connection to ${peer.show -> "peer"}") *>
              Sync[F].delay(chan.shutdown()).attempt.void
        }.void
      }
    } yield (cache, shutdown)
  }

  /** Open a gRPC channel for gossiping. */
  private def makeGossipChannel[F[_]: Sync: Log](
      peer: Node,
      conf: Configuration,
      clientSslContext: SslContext,
      egressScheduler: Scheduler
  ): F[ManagedChannel] =
    for {
      _ <- Log[F].debug(s"Creating new channel to peer ${peer.show -> "peer"}")
      chan <- Sync[F].delay {
               NettyChannelBuilder
                 .forAddress(peer.host, peer.protocolPort)
                 .executor(egressScheduler)
                 .maxInboundMessageSize(conf.server.maxMessageSize)
                 .negotiationType(NegotiationType.TLS)
                 .sslContext(clientSslContext)
                 .overrideAuthority(Base16.encode(peer.id.toByteArray))
                 // https://github.com/grpc/grpc-java/issues/3470 indicates that the timeout will raise Unavailable when a connection is attempted.
                 .keepAliveTimeout(conf.server.defaultTimeout.toMillis, TimeUnit.MILLISECONDS)
                 .idleTimeout(60, TimeUnit.SECONDS) // This just puts the channel in a low resource state.
                 .build()
             }
    } yield chan

  /** Close and remove a cached connection. */
  private def disconnect[F[_]: Sync: Log](cache: CachedConnections[F, Unit], peer: Node): F[Unit] =
    cache.modify { s =>
      for {
        _ <- s.connections.get(peer).fold(().pure[F]) { c =>
              Log[F].debug(s"Disconnecting from peer ${peer.show -> "peer"}") *>
                Sync[F].delay(c.shutdown()).attempt.void
            }
      } yield s.copy(connections = s.connections - peer)
    }

  def makeBlockRelaying[F[_]: ContextShift: Concurrent: Parallel: Log: Metrics: NodeDiscovery: NodeAsk](
      conf: Configuration,
      connectToGossip: GossipService.Connector[F],
      egressScheduler: Scheduler
  ): Resource[F, BlockRelaying[F]] =
    Resource
      .liftF(BlockRelayingImpl.establishMetrics[F])
      .as(
        BlockRelayingImpl(
          egressScheduler,
          NodeDiscovery[F],
          connectToGossip = connectToGossip,
          relayFactor = conf.server.relayFactor,
          relaySaturation = conf.server.relaySaturation
        )
      )

  def makeDeployRelaying[F[_]: ContextShift: Concurrent: Parallel: Log: Metrics: NodeDiscovery: NodeAsk](
      conf: Configuration,
      connectToGossip: GossipService.Connector[F],
      egressScheduler: Scheduler
  ): Resource[F, DeployRelaying[F]] =
    Resource
      .liftF(DeployRelayingImpl.establishMetrics[F])
      .as(
        if (conf.server.deployGossipEnabled)
          DeployRelayingImpl(
            egressScheduler,
            NodeDiscovery[F],
            connectToGossip = connectToGossip,
            relayFactor = conf.server.relayFactor,
            relaySaturation = conf.server.relaySaturation
          )
        else
          new NoOpsDeployRelaying[F]
      )

  private def makeDeployDownloadManager[F[_]: ContextShift: Concurrent: Log: Time: Timer: Metrics: DagStorage: Consensus: DeployStorage: DeployBuffer](
      conf: Configuration,
      connectToGossip: GossipService.Connector[F],
      relaying: DeployRelaying[F],
      egressScheduler: Scheduler
  ): Resource[F, DeployDownloadManager[F]] =
    for {
      _ <- Resource.liftF(DeployDownloadManagerImpl.establishMetrics[F])
      downloadManager <- DeployDownloadManagerImpl[F](
                          maxParallelDownloads = conf.server.downloadMaxParallelDeploys,
                          cacheExpiry = conf.server.downloadCacheExpiry,
                          connectToGossip = connectToGossip,
                          backend = new DeployDownloadManagerImpl.Backend[F] {
                            override def contains(deployHash: ByteString): F[Boolean] =
                              DeployStorage[F].reader.contains(deployHash)

                            // Empty because deploy validated during adding into the DeployBuffer anyway
                            override def validate(deploy: Deploy): F[Unit] =
                              DeployBuffer[F].addDeploy(deploy).rethrow

                            override def store(deploy: Deploy): F[Unit] =
                              // Validation has already stored it.
                              ().pure[F]

                            override def onScheduled(summary: DeploySummary): F[Unit] = ().pure[F]

                            override def onScheduled(
                                summary: DeploySummary,
                                source: Node
                            ): F[Unit] = ().pure[F]

                            override def onDownloaded(deployHash: ByteString): F[Unit] = ().pure[F]

                            override def onFailed(deployHash: ByteString): F[Unit] = ().pure[F]
                          },
                          relaying = relaying,
                          retriesConf = DeployDownloadManagerImpl.RetriesConf(
                            maxRetries = conf.server.downloadMaxRetries,
                            initialBackoffPeriod = conf.server.downloadRetryInitialBackoffPeriod,
                            backoffFactor = conf.server.downloadRetryBackoffFactor
                          ),
                          egressScheduler
                        )
    } yield downloadManager

  private def makeBlockDownloadManager[F[_]: ContextShift: Concurrent: Log: Time: Timer: Metrics: DagStorage: Consensus: DeployStorage](
      conf: Configuration,
      connectToGossip: GossipService.Connector[F],
      relaying: BlockRelaying[F],
      synchronizer: Synchronizer[F],
      maybeValidatorId: Option[ValidatorIdentity],
      isInitialSyncDoneRef: Ref[F, Boolean],
      egressScheduler: Scheduler
  ): Resource[F, BlockDownloadManager[F]] =
    for {
      _ <- Resource.liftF(BlockDownloadManagerImpl.establishMetrics[F])
      maybeValidatorPublicKeyHash = maybeValidatorId
        .map(_.publicKeyHashBS)
        .filterNot(_.isEmpty)
      downloadManager <- BlockDownloadManagerImpl[F](
                          maxParallelDownloads = conf.server.downloadMaxParallelBlocks,
                          partialBlocksEnabled = conf.server.downloadPartialBlocks,
                          cacheExpiry = conf.server.downloadCacheExpiry,
                          connectToGossip = connectToGossip,
                          backend = new BlockDownloadManagerImpl.Backend[F] {
                            override def contains(blockHash: ByteString): F[Boolean] =
                              isInDag(blockHash)

                            override def validate(block: Block): F[Unit] =
                              isInitialSyncDoneRef.get.flatMap { isInitialSyncDone =>
                                Log[F]
                                  .warn(
                                    s"${block -> "message" -> null} seems to be created by a doppelganger using the same validator key!"
                                  )
                                  .whenA(
                                    isInitialSyncDone && maybeValidatorPublicKeyHash
                                      .contains(block.getHeader.validatorPublicKeyHash)
                                  )
                              } *>
                                Consensus[F].validateAndAddBlock(block)

                            override def store(block: Block): F[Unit] =
                              // Validation has already stored it.
                              ().pure[F]

                            override def onScheduled(summary: BlockSummary): F[Unit] =
                              Consensus[F].onScheduled(summary)

                            override def onScheduled(summary: BlockSummary, source: Node): F[Unit] =
                              synchronizer.onScheduled(summary, source)

                            override def onDownloaded(blockHash: ByteString): F[Unit] =
                              // Calling `addBlock` during validation has already stored the block,
                              // so we have nothing more to do here with the consensus.
                              synchronizer.onDownloaded(blockHash)

                            override def onFailed(blockHash: ByteString): F[Unit] =
                              synchronizer.onFailed(blockHash)

                            override def readDeploys(deployHashes: Seq[DeployHash]) =
                              DeployStorage[F]
                                .reader(DeployInfo.View.FULL)
                                .getByHashes(deployHashes.toSet)
                                .compile
                                .toList
                          },
                          relaying = relaying,
                          retriesConf = BlockDownloadManagerImpl.RetriesConf(
                            maxRetries = conf.server.downloadMaxRetries,
                            initialBackoffPeriod = conf.server.downloadRetryInitialBackoffPeriod,
                            backoffFactor = conf.server.downloadRetryBackoffFactor
                          ),
                          egressScheduler = egressScheduler
                        )
    } yield downloadManager

  // Even though we create the Genesis from the chainspec, the approver gives the green light to use it,
  // which could be based on the presence of other known validators, signaled by their approvals.
  // That just gives us the assurance that we are using the right chain spec because other are as well.
  private def makeGenesisApprover[F[_]: Concurrent: Log: Time: Timer: NodeDiscovery: BlockStorage: Consensus](
      conf: Configuration,
      maybeValidatorId: Option[ValidatorIdentity],
      connectToGossip: GossipService.Connector[F],
      downloadManager: BlockDownloadManager[F],
      genesis: Block
  ): Resource[F, GenesisApprover[F]] =
    for {
      _ <- Resource.liftF {
            maybeValidatorId match {
              case Some(validator) =>
                Log[F].info(
                  s"Starting with validator identity ${Base16.encode(validator.publicKeyHash) -> "validator"}"
                )
              case None =>
                Log[F].info("Starting without a validator identity.")
            }
          }

      _ <- Resource.liftF {
            for {
              // Validate it to make sure that code path is exercised.
              _ <- Log[F].info(
                    s"Trying to validate and run the Genesis ${genesis.blockHash.show -> "candidate"}"
                  )
              _ <- Consensus[F].validateAndAddBlock(genesis)
            } yield ()
          }

      knownValidators <- Resource.liftF {
                          // Based on `CasperPacketHandler.of` in default mode.
                          CasperConf.parseValidatorsFile[F](conf.casper.knownValidatorsFile)
                        }

      // Produce an approval for a valid candiate if this node is a Genesis validator.
      maybeApproveBlock = (block: Block) =>
        maybeValidatorId
          .filter { id =>
            block.getHeader.getState.bonds.exists { bond =>
              bond.validatorPublicKeyHash == id.publicKeyHashBS
            }
          }
          .map { id =>
            val sig = id.signature(block.blockHash.toByteArray)
            Approval()
              .withApproverPublicKey(ByteString.copyFrom(id.publicKey))
              .withSignature(sig)
          }

      backend = new GenesisApproverImpl.Backend[F] {
        // Everyone has to have the same chain spec, so approval is just a matter of checking the candidate hash.
        override def validateCandidate(
            block: Block
        ): F[Either[Throwable, Option[Approval]]] =
          if (block.blockHash == genesis.blockHash) {
            maybeApproveBlock(block).asRight[Throwable].pure[F]
          } else {
            InvalidArgument(
              s"${block.blockHash.show -> "candidate"} did not equal the expected Genesis ${genesis.blockHash.show -> "genesis"}"
            ).asLeft[Option[Approval]].pure[F].widen
          }

        override def canTransition(
            block: Block,
            signatories: Set[ByteString]
        ): Boolean =
          signatories.size >= conf.casper.requiredSigs &&
            knownValidators.forall(signatories(_))

        override def validateSignature(
            blockHash: ByteString,
            publicKey: ByteString,
            signature: Signature
        ): Boolean =
          Validation.signature(
            blockHash.toByteArray,
            signature,
            PublicKey(publicKey.toByteArray)
          )

        override def getBlock(blockHash: ByteString): F[Option[Block]] =
          BlockStorage[F]
            .get(blockHash)
            .map(_.map(_.getBlockMessage))
      }

      approver <- GenesisApproverImpl[F](
                   backend,
                   NodeDiscovery[F],
                   connectToGossip,
                   relayFactor = conf.server.approvalRelayFactor,
                   maybeBootstrapParams = NonEmptyList.fromList(
                     conf.server.bootstrap.map(_.withChainId(genesis.blockHash))
                   ) map { bootstraps =>
                     GenesisApproverImpl.BootstrapParams(
                       bootstraps,
                       conf.server.approvalPollInterval,
                       downloadManager
                     )
                   },
                   maybeGenesis = Some(genesis),
                   maybeApproval = maybeApproveBlock(genesis)
                 )
    } yield approver

  def makeSynchronizer[F[_]: Concurrent: Parallel: Log: Metrics: DagStorage: Consensus: CasperLabsProtocol](
      conf: Configuration,
      connectToGossip: GossipService.Connector[F]
  ): Resource[F, Synchronizer[F]] = Resource.liftF {
    for {
      _ <- SynchronizerImpl.establishMetrics[F]
      synchronizer <- SynchronizerImpl[F](
                       connectToGossip,
                       new SynchronizerImpl.Backend[F] {

                         override def justifications: F[List[ByteString]] =
                           for {
                             dag    <- DagStorage[F].getRepresentation
                             latest <- dag.latestMessageHashes
                           } yield latest.values.flatten.toList

                         override def validate(blockSummary: BlockSummary): F[Unit] =
                           Consensus[F].validateSummary(blockSummary)

                         override def notInDag(blockHash: ByteString): F[Boolean] =
                           isInDag(blockHash).map(!_)
                       },
                       maxPossibleDepth = conf.server.syncMaxPossibleDepth,
                       minBlockCountToCheckWidth = conf.server.syncMinBlockCountToCheckWidth,
                       maxBondingRate = conf.server.syncMaxBondingRate,
                       maxDepthAncestorsRequest = conf.server.syncMaxDepthAncestorsRequest,
                       disableValidations = conf.server.syncDisableValidations,
                       maxParallel = conf.server.syncMaxParallel.value
                     )
    } yield synchronizer
  }

  /** Check if we have a block yet. */
  private def isInDag[F[_]: Sync: DagStorage](blockHash: ByteString): F[Boolean] =
    for {
      dag  <- DagStorage[F].getRepresentation
      cont <- dag.contains(blockHash)
    } yield cont

  /** Create gossip service. */
  def makeGossipServiceServer[F[_]: ConcurrentEffect: Parallel: Log: Metrics: BlockStorage: DagStorage: DeployStorage: Consensus](
      conf: Configuration,
      synchronizer: Synchronizer[F],
      connector: GossipService.Connector[F],
      deployDownloadManager: DeployDownloadManager[F],
      blockDownloadManager: BlockDownloadManager[F],
      genesisApprover: GenesisApprover[F]
  ): Resource[F, GossipServiceServer[F]] =
    for {
      backend <- Resource.pure[F, GossipServiceServer.Backend[F]] {
                  new GossipServiceServer.Backend[F] {

                    override def getDeploySummary(
                        deployHash: DeployHash
                    ): F[Option[DeploySummary]] =
                      DeployStorage[F].reader.getDeploySummary(deployHash)

                    override def getBlockSummary(blockHash: ByteString): F[Option[BlockSummary]] =
                      BlockStorage[F]
                        .getBlockSummary(blockHash)

                    override def getBlock(
                        blockHash: ByteString,
                        deploysBodiesExcluded: Boolean
                    ): F[Option[Block]] =
                      BlockStorage[F]
                        .get(blockHash)(
                          if (deploysBodiesExcluded) DeployInfo.View.BASIC
                          else DeployInfo.View.FULL
                        )
                        .map(_.map(_.getBlockMessage))

                    override def getDeploys(deployHashes: Set[ByteString]): Iterant[F, Deploy] =
                      Iterant.fromReactivePublisher {
                        DeployStorage[F]
                          .reader(DeployInfo.View.FULL)
                          .getByHashes(deployHashes)
                          .toUnicastPublisher
                      }

                    /** Returns latest messages as seen currently by the node.
                      * NOTE: In the future we will remove redundant messages. */
                    override def latestMessages: F[Set[Block.Justification]] =
                      Consensus[F].latestMessages

                    override def dagTopoSort(
                        startRank: Long,
                        endRank: Long
                    ): Iterant[F, BlockSummary] = {
                      import fs2.interop.reactivestreams._
                      Iterant
                        .liftF(for {
                          dag <- DagStorage[F].getRepresentation
                          stream = dag
                            .topoSort(startRank, endRank)
                            .flatMap(blockInfo => fs2.Stream.emits(blockInfo.flatMap(_.summary)))
                          publisher = stream.toUnicastPublisher()
                          iterant   = Iterant.fromReactivePublisher(publisher)
                        } yield iterant)
                        .flatten
                    }
                  }
                }

      server <- Resource.liftF {
                 GossipServiceServer[F](
                   backend,
                   synchronizer,
                   connector,
                   deployDownloadManager,
                   blockDownloadManager,
                   genesisApprover,
                   maxChunkSize = conf.server.chunkSize,
                   maxParallelBlockDownloads = conf.server.relayMaxParallelBlocks,
                   deployGossipEnabled = conf.server.deployGossipEnabled
                 )
               }

    } yield server

  /** Initially sync with the bootstrap node and/or some others.
    * Returns handle which will be resolved when initial synchronization is finished. */
  private def makeInitialSynchronizer[F[_]: Concurrent: Parallel: Log: Timer: NodeDiscovery: DagStorage: Consensus](
      conf: Configuration,
      downloadManager: BlockDownloadManager[F],
      synchronizer: Synchronizer[F],
      connectToGossip: GossipService.Connector[F],
      awaitApproved: F[Unit]
  ): Resource[F, F[Unit]] =
    for {
      initialSync <- Resource.liftF {
                      Consensus[F].lastSynchronizedRank >>= { startRank =>
                        Sync[F].delay(
                          new InitialSynchronizationForwardImpl[F](
                            NodeDiscovery[F],
                            selectNodes =
                              ns => Random.shuffle(ns).take(conf.server.initSyncMaxNodes),
                            minSuccessful = conf.server.initSyncMinSuccessful,
                            memoizeNodes = conf.server.initSyncMemoizeNodes,
                            skipFailedNodesInNextRounds = conf.server.initSyncSkipFailedNodes,
                            connector = connectToGossip,
                            downloadManager = downloadManager,
                            synchronizer = synchronizer,
                            step = conf.server.initSyncStep,
                            rankStartFrom = startRank,
                            roundPeriod = conf.server.initSyncRoundPeriod
                          )
                        )
                      }
                    }
      handle <- makeFiberResource {
                 for {
                   _         <- awaitApproved
                   awaitSync <- initialSync.sync()
                   _         <- awaitSync
                   _         <- Log[F].info(s"Initial synchronization complete.")
                 } yield ()
               }
    } yield handle

  /** Periodically sync with a random node. */
  private def makePeriodicSynchronizer[F[_]: Concurrent: Parallel: Log: Timer: NodeDiscovery](
      conf: Configuration,
      gossipServiceServer: GossipServiceServer[F],
      connectToGossip: GossipService.Connector[F],
      awaitApproved: F[Unit]
  ): Resource[F, Unit] = {
    def loop(synchronizer: InitialSynchronization[F]): F[Unit] = {
      val syncOne: F[Unit] = for {
        _        <- Time[F].sleep(conf.server.periodicSyncRoundPeriod)
        hasPeers <- NodeDiscovery[F].recentlyAlivePeersAscendingDistance.map(_.nonEmpty)
        _        <- synchronizer.sync().flatMap(identity).whenA(hasPeers)
      } yield ()

      syncOne.attempt >> loop(synchronizer)
    }

    for {
      periodicSync <- Resource.pure[F, InitialSynchronization[F]] {
                       new InitialSynchronizationBackwardImpl[F](
                         NodeDiscovery[F],
                         gossipServiceServer,
                         selectNodes = ns => List(ns(Random.nextInt(ns.length))),
                         minSuccessful = 1,
                         memoizeNodes = false,
                         skipFailedNodesInNextRounds = false,
                         roundPeriod = conf.server.periodicSyncRoundPeriod,
                         connector = connectToGossip
                       )
                     }
      _ <- makeFiberResource {
            awaitApproved >> loop(periodicSync)
          }
    } yield ()
  }

  /** The TransportLayer setup prints the number of peers now and then which integration tests
    * may depend upon. We aren't using the `Connect` functionality so have to do it another way. */
  private def makePeerCountPrinter[F[_]: Concurrent: Time: Log: NodeDiscovery]
      : Resource[F, Unit] = {
    def loop(prevPeers: Set[Node]): F[Unit] = {
      // Based on Connecttions.removeConn
      val newPeers = for {
        peers <- NodeDiscovery[F].recentlyAlivePeersAscendingDistance.map(_.toSet)
        _     <- Log[F].info(s"Peers: ${peers.size}").whenA(peers.size != prevPeers.size)
        _ <- (prevPeers diff peers).toList.traverse { peer =>
              Log[F].info(s"Disconnected from ${peer.show -> "peer"}")
            }
        _ <- (peers diff prevPeers).toList.traverse { peer =>
              Log[F].info(s"Connected to ${peer.show -> "peer"}")
            }
        _ <- Time[F].sleep(15.seconds)
      } yield peers

      Time[F].sleep(5.seconds) *> newPeers >>= (loop(_))
    }

    makeFiberResource(loop(Set.empty)).void
  }

  /** Start something in a fiber. Make sure it stops if the resource is released. */
  private def makeFiberResource[F[_]: Concurrent: Log, A](f: F[A]): Resource[F, F[A]] =
    f.onError {
      case NonFatal(ex) => Log[F].error(s"Fiber resource died: $ex") *> Concurrent[F].raiseError(ex)
      case fatal        => Sync[F].delay(throw fatal)
    }.background

  /** Limit the rate of block downloads per peer. */
  private def makeRateLimiter[F[_]: Concurrent: Timer: Log](
      conf: Configuration
  ): Resource[F, RateLimiter[F, ByteString]] = {
    val elementsPerPeriod: Int = conf.server.blockUploadRateMaxRequests
    val period                 = conf.server.blockUploadRatePeriod
    val queueSize: Int         = conf.server.blockUploadRateMaxThrottled

    if (elementsPerPeriod == 0 || period == Duration.Zero) {
      Resource.liftF {
        for {
          _ <- Log[F].info("Disable rate limiting")
        } yield RateLimiter.noOp[F, ByteString]
      }
    } else {
      Resource.liftF[F, Unit](
        Log[F].info(s"Rate limiting enabled: $elementsPerPeriod requests every $period")
      ) >>
        RateLimiter.create[F, ByteString](
          elementsPerPeriod = elementsPerPeriod,
          period = period,
          maxQueueSize =
            if (queueSize == 0) Int.MaxValue
            else queueSize
        )
    }
  }

  def startGrpcServer[F[_]: Sync: TaskLike: ObservableIterant](
      server: GossipServiceServer[F],
      rateLimiter: RateLimiter[F, ByteString],
      chainId: ByteString,
      serverSslContext: SslContext,
      conf: Configuration,
      port: Int,
      ingressScheduler: Scheduler
  )(implicit m: Metrics[Id], l: Log[Id]): Resource[F, Server] = {
    // Start the gRPC server.
    implicit val s = ingressScheduler
    GrpcServer(
      port,
      services = List(
        (scheduler: Scheduler) =>
          Sync[F].delay {
            val svc = GrpcGossipService.fromGossipService[F](
              server,
              rateLimiter,
              chainId,
              blockChunkConsumerTimeout = conf.server.relayBlockChunkConsumerTimeout
            )
            GossipingGrpcMonix.bindService(svc, scheduler)
          }
      ),
      interceptors = List(
        new AuthInterceptor(),
        new MetricsInterceptor(),
        ErrorInterceptor.default
      ),
      sslContext = serverSslContext.some,
      maxMessageSize = conf.server.maxMessageSize.value.some
    )
  }
}
