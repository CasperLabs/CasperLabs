package io.casperlabs.node.api

import cats.effect.Sync
import cats.implicits._
import cats.data.StateT
import com.google.protobuf.ByteString
import org.http4s.HttpRoutes
import java.time.Instant
import doobie.util.transactor.Transactor
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.crypto.Keys.{PublicKey, PublicKeyBS, PublicKeyHash}
import io.casperlabs.casper.consensus.{Block, Era}
import io.casperlabs.casper.ValidatorIdentity
import io.casperlabs.comm.discovery.NodeDiscovery
import io.casperlabs.node.casper.consensus.Consensus
import io.casperlabs.node.configuration.Configuration
import io.casperlabs.models.Message
import io.casperlabs.shared.{ByteStringPrettyPrinter, Time}
import io.casperlabs.storage.dag.{DagStorage, FinalityStorage}
import io.casperlabs.storage.era.EraStorage
import io.casperlabs.ipc.ChainSpec

object StatusInfo {
  import ByteStringPrettyPrinter._

  case class Status(
      version: String,
      ok: Boolean,
      checklist: CheckList
  )

  case class Check[T](
      ok: Boolean,
      message: String,
      details: Option[T] = None
  )
  object Check {
    implicit class BoolStateOps[F[_]: Sync, T](s: StateT[F, Boolean, T]) {
      def withoutAccumulation: StateT[F, Boolean, T] = StateT { acc =>
        s.run(acc).map {
          case (_, x) => (acc, x)
        }
      }
    }

    /** Constructor that will catch errors in the check function as well as
      * aggregate the overall `ok` field across all the checks.
      */
    def apply[F[_]: Sync, T](f: F[Check[T]]): StateT[F, Boolean, Check[T]] = StateT { acc =>
      f.attempt.map {
        case Left(ex) => (false, Check[T](ok = false, message = ex.getMessage, details = None))
        case Right(x) => (acc && x.ok, x)
      }
    }

    case class BlockDetails(
        blockHash: String,
        timestamp: String,
        jRank: Long
    )
    object BlockDetails {
      def apply(message: Message): BlockDetails =
        BlockDetails(
          blockHash = message.messageHash.show,
          timestamp = Instant.ofEpochMilli(message.timestamp).toString,
          jRank = message.jRank
        )
    }

    case class PeerDetails(
        count: Int
    )

    case class EraDetails(
        keyBlockHash: String,
        startTimestamp: String,
        endTimestamp: String
    )

    case class ErasDetails(
        eras: List[EraDetails]
    )
    object ErasDetails {
      def apply(eras: Set[Era]): ErasDetails =
        ErasDetails(
          eras.toList.map(
            era =>
              EraDetails(
                keyBlockHash = era.keyBlockHash.show,
                startTimestamp = Instant.ofEpochMilli(era.startTick).toString,
                endTimestamp = Instant.ofEpochMilli(era.endTick).toString
              )
          )
        )
    }
    case class GenesisDetails(
        genesisBlockHash: String,
        chainName: String,
        genesisLikeBlocks: List[BlockDetails]
    )

    case class ValidatorDetails(
        publicKeyHash: String
    )

    type Basic     = Check[Unit]
    type LastBlock = Check[BlockDetails]
    type Peers     = Check[PeerDetails]
    type Eras      = Check[ErasDetails]
    type Genesis   = Check[GenesisDetails]
    type Validator = Check[ValidatorDetails]
  }

  case class CheckList(
      database: Check.Basic,
      validator: Check.Validator,
      peers: Check.Peers,
      bootstrap: Check.Peers,
      initialSynchronization: Check.Basic,
      lastFinalizedBlock: Check.LastBlock,
      lastReceivedBlock: Check.LastBlock,
      lastCreatedBlock: Check.LastBlock,
      activeEras: Check.Eras,
      bondedEras: Check.Eras,
      genesisEra: Check.Eras,
      genesisBlock: Check.Genesis
  )
  object CheckList {
    import Check._

    def apply[F[_]: Sync: Time: NodeDiscovery: DagStorage: FinalityStorage: EraStorage: Consensus](
        conf: Configuration,
        chainSpec: ChainSpec,
        genesis: Block,
        maybeValidatorId: Option[ValidatorIdentity],
        getIsSynced: F[Boolean],
        readXa: Transactor[F]
    ): StateT[F, Boolean, CheckList] =
      for {
        database               <- database[F](readXa)
        validator              <- validator(maybeValidatorId)
        peers                  <- peers[F](conf)
        bootstrap              <- bootstrap[F](conf, genesis)
        initialSynchronization <- initialSynchronization[F](getIsSynced)
        lastFinalizedBlock     <- lastFinalizedBlock[F](chainSpec, genesis)
        lastReceivedBlock      <- lastReceivedBlock[F](conf, chainSpec, maybeValidatorId)
        lastCreatedBlock       <- lastCreatedBlock[F](chainSpec, maybeValidatorId)
        activeEras             <- activeEras[F](conf)
        bondedEras             <- bondedEras[F](conf, maybeValidatorId)
        genesisEra             <- genesisEra[F](conf, genesis)
        genesisBlock           <- genesisBlock[F](genesis)
        checklist = CheckList(
          database = database,
          validator = validator,
          peers = peers,
          bootstrap = bootstrap,
          initialSynchronization = initialSynchronization,
          lastFinalizedBlock = lastFinalizedBlock,
          lastReceivedBlock = lastReceivedBlock,
          lastCreatedBlock = lastCreatedBlock,
          activeEras = activeEras,
          bondedEras = bondedEras,
          genesisEra = genesisEra,
          genesisBlock = genesisBlock
        )
      } yield checklist

    def database[F[_]: Sync](readXa: Transactor[F]) = Check {
      import doobie._
      import doobie.implicits._
      sql"""select 1""".query[Int].unique.transact(readXa).map { _ =>
        Check[Unit](ok = true, message = "Database is readable.")
      }
    }

    def validator[F[_]: Sync](maybeValidatorId: Option[ValidatorIdentity]) = Check {
      maybeValidatorId match {
        case None =>
          Check[ValidatorDetails](ok = true, "Running in read-only mode.").pure[F]
        case Some(id) =>
          Check(
            ok = true,
            "Running in validating mode.",
            details = ValidatorDetails(id.publicKeyHashBS.show).some
          ).pure[F]
      }
    }

    def peers[F[_]: Sync: NodeDiscovery](conf: Configuration) = Check {
      NodeDiscovery[F].recentlyAlivePeersAscendingDistance.map { nodes =>
        Check(
          ok = conf.casper.standalone || nodes.nonEmpty,
          message =
            if (conf.casper.standalone) s"Standalone mode, connected to ${nodes.length} peer(s)."
            else s"Connected to ${nodes.length} recently alive peer(s).",
          details = PeerDetails(count = nodes.size).some
        )
      }
    }

    def bootstrap[F[_]: Sync: NodeDiscovery](conf: Configuration, genesis: Block) = Check {
      val bootstrapNodes = conf.server.bootstrap.map(_.withChainId(genesis.blockHash))
      NodeDiscovery[F].recentlyAlivePeersAscendingDistance.map(_.toSet).map { nodes =>
        val connected = bootstrapNodes.filter(nodes)
        Check(
          ok = bootstrapNodes.isEmpty || connected.nonEmpty,
          message =
            if (bootstrapNodes.nonEmpty)
              s"Connected to ${connected.size} of the bootstrap node(s) out of the ${bootstrapNodes.size} configured."
            else
              "No bootstraps configured.",
          details = PeerDetails(count = connected.size).some
        )
      }
    }

    def initialSynchronization[F[_]: Sync](getIsSynced: F[Boolean]) =
      Check {
        getIsSynced.map { synced =>
          Check[Unit](
            ok = synced,
            message =
              if (synced) "Initial synchronization complete."
              else "Initial synchronization running."
          )
        }
      }.withoutAccumulation

    private def isTooOld[F[_]: Sync: Time](
        chainSpec: ChainSpec,
        maybeMessage: Option[Message]
    ): F[Boolean] =
      maybeMessage.fold(false.pure[F]) { message =>
        for {
          now <- Time[F].currentMillis
          // Using the entropy duration, which is a timespan during which there must be some blocks.
          entropyDurationMillis = chainSpec.getGenesis.getHighwayConfig.entropyDurationMillis
          tooOld = if (entropyDurationMillis == 0) false
          else now - message.timestamp > entropyDurationMillis
        } yield tooOld
      }

    private def findLatest(messages: Set[Message]): Option[Message] =
      if (messages.isEmpty) none else messages.maxBy(_.timestamp).some

    def lastFinalizedBlock[F[_]: Sync: Time: FinalityStorage: DagStorage: Consensus](
        chainSpec: ChainSpec,
        genesis: Block
    ) =
      Check {
        for {
          lfbHash  <- FinalityStorage[F].getLastFinalizedBlock
          dag      <- DagStorage[F].getRepresentation
          lfb      <- dag.lookupUnsafe(lfbHash)
          isTooOld <- isTooOld(chainSpec, lfb.some)
        } yield Check(
          ok = !isTooOld,
          message =
            if (isTooOld) "Last block was finalized too long ago."
            else if (lfbHash == genesis.blockHash) "The last finalized block is the Genesis."
            else "The last finalized block was moved not too long ago.",
          details = BlockDetails(lfb).some
        )
      }

    // Returning the block hash can reveal the validator identity by process of elimination.
    def lastReceivedBlock[F[_]: Sync: Time: DagStorage: Consensus](
        conf: Configuration,
        chainSpec: ChainSpec,
        maybeValidatorId: Option[ValidatorIdentity]
    ) = Check {
      for {
        dag      <- DagStorage[F].getRepresentation
        tips     <- dag.latestGlobal
        messages <- tips.latestMessages
        received = messages.values.flatten.toSet.filter { m =>
          maybeValidatorId.fold(true)(_.publicKeyHashBS != m.validatorId)
        }
        latest   = findLatest(received)
        isTooOld <- isTooOld(chainSpec, latest)
      } yield Check[BlockDetails](
        ok = !isTooOld,
        message =
          if (isTooOld) "Last block was received too long ago."
          else if (latest.nonEmpty) "Received a block not too long ago."
          else if (conf.casper.standalone) "Running in standalone mode."
          else "Haven't received a block yet.",
        details = latest.map(BlockDetails(_))
      )
    }

    // Returning the block hash reveals the validator identity.
    def lastCreatedBlock[F[_]: Sync: Time: DagStorage: Consensus](
        chainSpec: ChainSpec,
        maybeValidatorId: Option[ValidatorIdentity]
    ) = Check {
      for {
        created <- maybeValidatorId.fold(Set.empty[Message].pure[F]) { id =>
                    for {
                      dag      <- DagStorage[F].getRepresentation
                      tips     <- dag.latestGlobal
                      messages <- tips.latestMessage(id.publicKeyHashBS)
                    } yield messages
                  }
        latest   = findLatest(created)
        isTooOld <- isTooOld(chainSpec, latest)
      } yield Check[BlockDetails](
        ok = !isTooOld,
        message =
          if (isTooOld) "The last created block was too long ago."
          else if (maybeValidatorId.isEmpty) "Running in read-only mode."
          else if (latest.isEmpty) "Haven't created any blocks yet."
          else "Created a block not too long ago.",
        details = latest.map(BlockDetails(_))
      )
    }

    def activeEras[F[_]: Sync: Time: Consensus](conf: Configuration) = Check {
      if (!conf.highway.enabled)
        Check[ErasDetails](ok = true, message = "Not in highway mode.").pure[F]
      else
        for {
          active <- Consensus[F].activeEras
          now    <- Time[F].currentMillis
          // Select the subset which is not in the voting period.
          (voting, current) = active.partition(era => era.endTick <= now)
        } yield {
          Check(
            ok = current.size == 1 || current.isEmpty && voting.nonEmpty,
            message = current.size match {
              case n if n > 1           => s"There are $n current eras! There should be only one."
              case 1                    => "There is 1 current era."
              case 0 if voting.nonEmpty => "There are no current eras but some are still voting."
              case _                    => "There are no active eras!"
            },
            details = ErasDetails(active).some
          )
        }
    }

    def genesisEra[F[_]: Sync: Time: EraStorage](conf: Configuration, genesis: Block) = Check {
      if (!conf.highway.enabled)
        Check[ErasDetails](ok = true, message = "Not in highway mode.").pure[F]
      else
        for {
          now        <- Time[F].currentMillis
          genesisEra <- EraStorage[F].getEraUnsafe(genesis.blockHash)
        } yield {
          Check(
            ok = true,
            message = if (genesisEra.startTick > now) "Genesis era hasn't started yet." else "",
            details = ErasDetails(Set(genesisEra)).some
          )
        }
    }

    def bondedEras[F[_]: Sync: Consensus](
        conf: Configuration,
        maybeValidatorId: Option[ValidatorIdentity]
    ) = Check {
      maybeValidatorId match {
        case _ if !conf.highway.enabled =>
          Check[ErasDetails](ok = true, message = "Not in highway mode.")
            .pure[F]

        case None =>
          Check[ErasDetails](ok = true, message = "Running in read-only mode")
            .pure[F]

        case Some(id) =>
          for {
            active <- Consensus[F].activeEras
            bonded = active.filter(
              era => era.bonds.exists(_.validatorPublicKeyHash == id.publicKeyHashBS)
            )
          } yield {
            Check(
              ok = bonded.nonEmpty,
              message =
                if (bonded.isEmpty) "Not bonded in any active era."
                else s"Bonded in ${bonded.size} active era(s).",
              details = ErasDetails(bonded).some
            )
          }

      }
    }

    def genesisBlock[F[_]: Sync: DagStorage](genesis: Block) = Check {
      for {
        dag <- DagStorage[F].getRepresentation
        genesisLike <- dag
                        .topoSort(startBlockNumber = 0, endBlockNumber = 0)
                        .compile
                        .toList
                        .map(_.flatten.map(_.getSummary))
      } yield Check(
        ok = genesisLike.size == 1 && genesisLike.head.blockHash == genesis.blockHash,
        message = genesisLike match {
          case List(g) if g.blockHash == genesis.blockHash => ""
          case List(_) =>
            "There's a genesis block but it's not the expected one!"
          case Nil => "There's no genesis block!"
          case _   => "There are multiple genesis blocks!"
        },
        details = GenesisDetails(
          genesisBlockHash = genesis.blockHash.show,
          chainName = genesis.getHeader.chainName,
          genesisLike.map { s =>
            BlockDetails(Message.fromBlockSummary(s).get)
          }
        ).some
      )
    }

  }

  class Service[F[_]: Sync: Time: NodeDiscovery: DagStorage: FinalityStorage: EraStorage: Consensus](
      conf: Configuration,
      chainSpec: ChainSpec,
      genesis: Block,
      maybeValidatorId: Option[ValidatorIdentity],
      getIsSynced: F[Boolean],
      readXa: Transactor[F]
  ) {
    def getStatus: F[Status] =
      for {
        version <- Sync[F].delay(VersionInfo.get)
        (ok, checklist) <- CheckList[F](
                            conf,
                            chainSpec,
                            genesis,
                            maybeValidatorId,
                            getIsSynced,
                            readXa
                          ).run(true)
      } yield Status(version, ok, checklist)
  }

  def service[F[_]: Sync](
      service: Service[F]
  ): HttpRoutes[F] = {
    import io.circe.generic.auto._
    import io.circe.syntax._
    import org.http4s.circe.CirceEntityEncoder._

    val dsl = org.http4s.dsl.Http4sDsl[F]
    import dsl._

    HttpRoutes.of[F] {
      // Could return a different HTTP status code, but it really depends on what we want from this.
      // An 50x would mean the service is kaput, which may be too harsh.
      case GET -> Root => Ok(service.getStatus.map(_.asJson))
    }
  }
}
