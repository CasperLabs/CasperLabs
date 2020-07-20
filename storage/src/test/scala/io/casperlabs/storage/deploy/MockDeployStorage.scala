package io.casperlabs.storage.deploy

import cats.effect.Sync
import cats.effect.concurrent.Ref
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.info.DeployInfo
import io.casperlabs.casper.consensus.{Block, Deploy, DeploySummary}
import io.casperlabs.crypto.Keys.PublicKeyHashBS
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.models.DeployImplicits.DeployOps
import io.casperlabs.shared.Log
import io.casperlabs.storage.deploy.MockDeployStorage.Metadata
import io.casperlabs.storage.{BlockHash, DeployHash}

import scala.concurrent.duration.FiniteDuration

class MockDeployStorage[F[_]: Sync: Log](
    deploysWithMetadataRef: Ref[F, Map[Deploy, Metadata]]
) extends DeployStorage[F] {

  // Deploys not yet included in a block
  private val PendingStatusCode = 0
  // Deploys that have been processed at least once,
  // waiting to be finalized or orphaned
  private val ProcessedStatusCode = 1
  // Deploys that have been discarded for some reason and should be deleted after a while
  private val DiscardedStatusCode        = 2
  private val ExecutionErrorStatusCode   = 3
  private val ExecutionSuccessStatusCode = 4

  override val writer = new DeployStorageWriter[F] {

    override def addAsExecuted(block: Block): F[Unit] =
      logOperation(
        s"addAsExecuted(blockHash = ${blockHashToString(block)}, deploys = ${processedDeploysToString(block)})",
        deploysWithMetadataRef.update { deploys =>
          def metadata(pd: Block.ProcessedDeploy, acc: Map[Deploy, Metadata]) = {
            val prev = acc.getOrElse(
              pd.getDeploy,
              Metadata(
                if (pd.isError) ExecutionErrorStatusCode else ExecutionSuccessStatusCode,
                "",
                now,
                now,
                Nil
              )
            )
            prev.copy(processingResults = (block.blockHash, pd) :: prev.processingResults)
          }

          block.getBody.deploys.foldLeft(deploys) {
            case (acc, pd) => acc + (pd.getDeploy -> metadata(pd, acc))
          }
        }
      )

    override def addAsPending(deploys: List[Deploy]): F[Unit] =
      logOperation(
        s"addAsPending(deploys = ${deploysToString(deploys)})",
        addWithStatus(deploys, PendingStatusCode)
      )

    override def addAsProcessed(deploys: List[Deploy]): F[Unit] =
      logOperation(
        s"addAsProcessed(deploys = ${deploysToString(deploys)})",
        addWithStatus(deploys, ProcessedStatusCode)
      )

    private def addWithStatus(deploys: List[Deploy], status: Int): F[Unit] =
      deploysWithMetadataRef.update(
        deploys
          .map(
            d => (d, Metadata(status, "", now, now, Nil))
          )
          .toMap ++ _
      )

    override def markAsPendingByHashes(hashes: List[ByteString]): F[Unit] =
      logOperation(
        s"markAsPending(hashes = ${hashesToString(hashes)})",
        setStatus(hashes.map(_ -> ""), PendingStatusCode, ProcessedStatusCode)
      )

    override def markAsProcessedByHashes(hashes: List[ByteString]): F[Unit] =
      logOperation(
        s"markAsProcessed(hashes = ${hashesToString(hashes)})",
        setStatus(hashes.map(_ -> ""), ProcessedStatusCode, PendingStatusCode)
      )

    override def markAsFinalizedByHashes(hashes: List[ByteString]): F[Unit] =
      logOperation(
        s"markAsFinalized(hashes = ${hashesToString(hashes)})",
        deploysWithMetadataRef.update(_.filter {
          case (deploy, Metadata(`ProcessedStatusCode`, _, _, _, _))
              if hashes.contains(deploy.deployHash) =>
            false
          case _ => true
        })
      )

    override def markAsDiscardedByHashes(hashesAndReasons: List[(ByteString, String)]): F[Unit] =
      logOperation(
        s"markAsDiscarded(hashes = ${hashesToString(hashesAndReasons.map(_._1))})",
        setStatus(hashesAndReasons, DiscardedStatusCode, PendingStatusCode)
      )

    private def setStatus(
        hashesAndReasons: Seq[(ByteString, String)],
        newStatus: Int,
        prevStatus: Int
    ): F[Unit] = {
      val updates = hashesAndReasons.toMap
      deploysWithMetadataRef.update(_.map {
        case (deploy, Metadata(`prevStatus`, _, _, createdAt, processingResults))
            if updates contains deploy.deployHash =>
          (
            deploy,
            Metadata(newStatus, updates(deploy.deployHash), now, createdAt, processingResults)
          )
        case x => x
      }) >> logCurrentState()
    }

    override def markAsDiscarded(
        expirationPeriod: FiniteDuration,
        message: String
    ): F[Set[ByteString]] =
      logOperation(
        s"markAsDiscarded(expirationPeriod = ${expirationPeriod.toCoarsest.toString()})",
        deploysWithMetadataRef.modify { data =>
          val expired = data.collect {
            case (deploy, Metadata(`PendingStatusCode`, _, updatedAt, createdAt, processingResults))
                if updatedAt < now - expirationPeriod.toMillis =>
              (
                deploy,
                Metadata(DiscardedStatusCode, "Expired.", now, createdAt, processingResults)
              )
          }
          (data ++ expired, expired.keySet.map(_.deployHash))
        }
      )

    override def cleanupDiscarded(expirationPeriod: FiniteDuration): F[Int] = {
      val selectDiscarding: ((Deploy, Metadata)) => Boolean = (_: (Deploy, Metadata)) match {
        case (_, Metadata(`DiscardedStatusCode`, _, updatedAt, _, processingResults))
            if updatedAt < now - expirationPeriod.toMillis && processingResults.isEmpty =>
          true
        case _ => false
      }

      logOperation(
        "cleanupDiscarded",
        deploysWithMetadataRef.modify { ds =>
          val deletedNum = ds.count(selectDiscarding)
          (ds.filterNot { case (d, metadata) => selectDiscarding((d, metadata)) }, deletedNum)
        }
      )
    }

    override def clear(): F[Unit] = ().pure[F]

    override def close(): F[Unit] = ().pure[F]

  }

  override def reader(implicit dv: DeployInfo.View) = new DeployStorageReader[F] {

    override def contains(deployHash: DeployHash) = getByHash(deployHash).map(_.isDefined)

    override def getDeploySummary(deployHash: DeployHash): F[Option[DeploySummary]] =
      getByHash(deployHash).map(_.map(_.getSummary))

    override def getProcessingResults(
        hash: ByteString
    ): F[List[(BlockHash, Block.ProcessedDeploy)]] =
      deploysWithMetadataRef.get.map(_.collect {
        case (d, Metadata(_, _, _, _, processingResults)) if d.deployHash == hash =>
          processingResults
      }.toList.flatten)

    def getProcessedDeploys(
        blockHash: ByteString
    ): F[List[Block.ProcessedDeploy]] =
      deploysWithMetadataRef.get.map(_.flatMap {
        case (_, Metadata(_, _, _, _, processingResults)) => processingResults
      }.collect {
        case (hash, result) if blockHash == hash => result
      }.toList)

    override def readProcessed: F[List[Deploy]] = readByStatus(ProcessedStatusCode)

    override def readProcessedByAccount(account: PublicKeyHashBS): F[List[Deploy]] =
      readProcessed.map(_.filter(_.getHeader.accountPublicKeyHash == account))

    override def readProcessedHashes: F[List[ByteString]] = readProcessed.map(_.map(_.deployHash))

    override def readPending: F[List[Deploy]] = readByStatus(PendingStatusCode)

    override def readPendingHashes: F[List[ByteString]] = readPending.map(_.map(_.deployHash))

    override def readPendingHeaders: F[List[Deploy.Header]] = readPending.map(_.map(_.getHeader))

    override def readPendingHashesAndHeaders: fs2.Stream[F, (ByteString, Deploy.Header)] =
      fs2.Stream
        .eval(
          for {
            hashes  <- readPendingHashes
            headers <- readPendingHeaders
          } yield hashes.zip(headers)
        )
        .flatMap(result => fs2.Stream.fromIterator(result.toIterator))

    override def getByHashes(
        l: Set[ByteString]
    ): fs2.Stream[F, Deploy] = {
      val deploys =
        (readPending, readProcessed).mapN(_ ++ _).map(_.filter(d => l.contains(d.deployHash)))
      fs2.Stream
        .eval(deploys)
        .flatMap(all => fs2.Stream.fromIterator(all.toIterator))
    }

    def getByHash(hash: ByteString): F[Option[Deploy]] =
      getByHashes(Set(hash)).compile.last

    private def readByStatus(status: Int): F[List[Deploy]] =
      deploysWithMetadataRef.get.map(_.collect {
        case (deploy, Metadata(`status`, _, _, _, _)) => deploy
      }.toList)

    override def countsByBufferedStates(states: DeployInfo.State*): F[Map[DeployInfo.State, Long]] =
      for {
        processed <- readProcessed
        pending   <- readPending
        discarded <- readByStatus(DiscardedStatusCode)
      } yield Map(
        DeployInfo.State.PENDING   -> pending.size.toLong,
        DeployInfo.State.PROCESSED -> processed.size.toLong,
        DeployInfo.State.DISCARDED -> discarded.size.toLong
      ).filterKeys(x => states.contains(x) || states.isEmpty)

    override def getPendingOrProcessed(hash: ByteString): F[Option[Deploy]] =
      deploysWithMetadataRef.get.map(_.collectFirst {
        case (d, Metadata(`PendingStatusCode` | `ProcessedStatusCode`, _, _, _, _))
            if d.deployHash == hash =>
          d
      })

    override def getBufferedStatus(hash: ByteString): F[Option[DeployInfo.Status]] =
      deploysWithMetadataRef.get.map(_.collectFirst {
        case (d, Metadata(status, msg, _, _, _)) if d.deployHash == hash =>
          DeployInfo.Status(
            state = status match {
              case `PendingStatusCode`   => DeployInfo.State.PENDING
              case `ProcessedStatusCode` => DeployInfo.State.PROCESSED
              case `DiscardedStatusCode` => DeployInfo.State.DISCARDED
              case _                     => DeployInfo.State.UNDEFINED
            },
            message = msg
          )
      })

    override def getDeployInfo(
        deployHash: DeployHash
    ): F[Option[DeployInfo]] =
      none[DeployInfo].pure[F]

    override def getDeployInfos(deploys: List[Deploy]): F[List[DeployInfo]] =
      List.empty[DeployInfo].pure[F]

    override def getDeploysByAccount(
        account: PublicKeyHashBS,
        limit: Int,
        lastTimeStamp: Long,
        lastDeployHash: DeployHash,
        next: Boolean
    ): F[List[Deploy]] =
      List.empty[Deploy].pure[F]
  }

  private def logOperation[A](opMsg: String, op: F[A]): F[A] =
    for {
      _ <- Log[F].info(s"DeployBuffer.$opMsg")
      a <- op
      _ <- logCurrentState()
    } yield a

  private def logCurrentState(): F[Unit] = {
    def statusToString(s: Int): String = s match {
      case 0 => "pending"
      case 1 => "processed"
      case 2 => "discarded"
      case 3 => "execution error"
      case 4 => "execution success"
    }

    val getStateDesc = deploysWithMetadataRef.get.map { deploys =>
      deploys.toList
        .groupBy(_._2.status)
        .map {
          case (status, ds) =>
            val s = statusToString(status)
            val dss = ds
              .map {
                case (d, _) => deployToString(d)
              }
              .mkString(", ")
            s + ": " + dss
        }
        .mkString("; ")
    }

    def log(stateDesc: String): F[Unit] =
      Log[F].info(s"Current state of deploy buffer: $stateDesc")

    for {
      stateDesc <- getStateDesc
      _         <- log(stateDesc)
    } yield ()
  }

  private def deployToString(d: Deploy): String    = Base16.encode(d.deployHash.toByteArray).take(4)
  private def hashToString(hs: ByteString): String = Base16.encode(hs.toByteArray).take(4)

  private def deploysToString(ds: List[Deploy]): String    = ds.map(deployToString).mkString(", ")
  private def hashesToString(hs: List[ByteString]): String = hs.map(hashToString).mkString(", ")

  private def blockHashToString(b: Block): String = Base16.encode(b.blockHash.toByteArray).take(4)
  private def processedDeploysToString(b: Block): String =
    b.getBody.deploys.map(pd => deployToString(pd.getDeploy)).mkString(", ")

  private def now = System.currentTimeMillis()

}

object MockDeployStorage {
  case class Metadata(
      status: Int,
      message: String,
      updatedAt: Long,
      createdAt: Long,
      processingResults: List[(ByteString, Block.ProcessedDeploy)]
  )

  def create[F[_]: Sync: Log](): F[DeployStorage[F]] =
    for {
      ref <- Ref.of[F, Map[Deploy, Metadata]](Map.empty)
    } yield new MockDeployStorage[F](ref): DeployStorage[F]

  def unsafeCreate[F[_]: Sync: Log](): DeployStorage[F] =
    new MockDeployStorage[F](Ref.unsafe[F, Map[Deploy, Metadata]](Map.empty))
}
