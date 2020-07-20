package io.casperlabs.storage.deploy

import cats._
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.Block.ProcessedDeploy
import io.casperlabs.casper.consensus.{Block, Deploy, DeploySummary}
import io.casperlabs.casper.consensus.info.DeployInfo
import io.casperlabs.crypto.Keys.PublicKeyHashBS
import io.casperlabs.metrics.Metered
import io.casperlabs.storage.{BlockHash, DeployHash}
import simulacrum.typeclass

import scala.concurrent.duration._
import cats.mtl.ApplicativeAsk

@typeclass trait DeployBufferWriter[F[_]] {
  /* Should not fail if the same deploy added twice */
  def addAsPending(deploys: List[Deploy]): F[Unit]

  /* Should not fail if the same deploy added twice */
  def addAsProcessed(deploys: List[Deploy]): F[Unit]

  /* Will have an effect only on pending deploys */
  def markAsProcessedByHashes(hashes: List[ByteString]): F[Unit]

  /* Will have an effect only on pending deploys */
  def markAsProcessed(deploys: List[Deploy]): F[Unit] =
    markAsProcessedByHashes(deploys.map(_.deployHash))

  /* Will have an effect only on processed deploys */
  def markAsPendingByHashes(hashes: List[ByteString]): F[Unit]

  /* Will have an effect only on processed deploys */
  def markAsPending(deploys: List[Deploy]): F[Unit] =
    markAsPendingByHashes(deploys.map(_.deployHash))

  /** Will have an effect only on processed deploys.
    * After being finalized, deploys will be not be affected by any other 'mark*' methods. */
  def markAsFinalizedByHashes(hashes: List[ByteString]): F[Unit]

  /** Will have an effect only on processed deploys.
    * After being finalized, deploys will be not be affected by any other 'mark*' methods. */
  def markAsFinalized(deploys: List[Deploy]): F[Unit] =
    markAsFinalizedByHashes(deploys.map(_.deployHash))

  /** Will have an effect only on pending deploys.
    * After being discarded, deploys will be not be affected by any other 'mark*' methods. */
  def markAsDiscardedByHashes(hashesAndReasons: List[(ByteString, String)]): F[Unit]

  /** Will have an effect only on pending deploys.
    * After being discarded, deploys will be not be affected by any other 'mark*' methods. */
  def markAsDiscarded(deploysAndReasons: List[(Deploy, String)]): F[Unit] =
    markAsDiscardedByHashes(deploysAndReasons.map {
      case (d, message) => (d.deployHash, message)
    })

  /** Will have an effect only on pending deploys.
    * Marks deploys as discarded that were added as pending more than 'now - expirationPeriod' time ago. */
  def markAsDiscarded(expirationPeriod: FiniteDuration, message: String): F[Set[ByteString]]

  /** Deletes discarded deploys from buffer that are older than 'now - expirationPeriod'.
    * Won't delete bodies of deploys which were [[addAsExecuted]] before.
    * Otherwise all the data will be deleted.
    * @return Number of deleted deploys from buffer minus those who [[addAsExecuted]] */
  def cleanupDiscarded(expirationPeriod: FiniteDuration): F[Int]
}

@typeclass trait DeployStorageWriter[F[_]] extends DeployBufferWriter[F] {

  private[storage] def addAsExecuted(block: Block): F[Unit]

  def clear(): F[Unit]

  def close(): F[Unit]
}

@typeclass trait DeployBufferReader[F[_]] {
  def readProcessed: F[List[Deploy]]

  def readProcessedByAccount(account: PublicKeyHashBS): F[List[Deploy]]

  def readProcessedHashes: F[List[DeployHash]]

  def readPending: F[List[Deploy]]

  def readPendingHashes: F[List[DeployHash]]

  def readPendingHeaders: F[List[Deploy.Header]]

  def readPendingHashesAndHeaders: fs2.Stream[F, (DeployHash, Deploy.Header)]

  def getPendingOrProcessed(deployHash: DeployHash): F[Option[Deploy]]

  def countPendingOrProcessed(implicit A: Applicative[F]): F[Long] =
    countsByBufferedStates(DeployInfo.State.PENDING, DeployInfo.State.PROCESSED).map(_.values.sum)

  /** Count the deploys in the buffer per state. If `states` are passed, filter by them, otherwise return all. */
  def countsByBufferedStates(states: DeployInfo.State*): F[Map[DeployInfo.State, Long]]

  def countByBufferedState(state: DeployInfo.State)(implicit A: Applicative[F]): F[Long] =
    countsByBufferedStates(state).map(_.getOrElse(state, 0))

  /** Read the status of a deploy from the buffer, if it's still in it. */
  def getBufferedStatus(deployHash: DeployHash): F[Option[DeployInfo.Status]]
}

@typeclass trait DeployStorageReader[F[_]] extends DeployBufferReader[F] {
  def contains(deployHash: DeployHash): F[Boolean]

  def getDeploySummary(deployHash: DeployHash): F[Option[DeploySummary]]

  def getByHash(deployHash: DeployHash): F[Option[Deploy]]

  def getByHashes(deployHashes: Set[DeployHash]): fs2.Stream[F, Deploy]

  /** @return List of blockHashes and processing results in descendant order by execution time (block creation timestamp)*/
  def getProcessingResults(deployHash: DeployHash): F[List[(BlockHash, ProcessedDeploy)]]

  /** Read all the processsed deploys in a block. */
  def getProcessedDeploys(blockHash: BlockHash): F[List[ProcessedDeploy]]

  def getDeployInfo(deployHash: DeployHash): F[Option[DeployInfo]]

  def getDeployInfos(deploys: List[Deploy]): F[List[DeployInfo]]

  /**
    * List of deploys created by specified account
    *
    *   If isNext is true, getting the next page, returns limit of deploys whose
    *      timestamp < lastTimestamp or timestamp == lastTimestamp && deployHash < lastDeployHash
    *   Else, getting the previous page, returns limit of deploys whose
    *      timestamp > firstTimestamp or timestamp == firstTimestamp && deployHash > firstDeployHash
    */
  def getDeploysByAccount(
      account: PublicKeyHashBS,
      limit: Int,
      lastTimeStamp: Long,
      lastDeployHash: DeployHash,
      isNext: Boolean
  ): F[List[Deploy]]
}

@typeclass trait DeployStorage[F[_]] {
  def writer: DeployStorageWriter[F]
  def reader(implicit dv: DeployInfo.View = DeployInfo.View.FULL): DeployStorageReader[F]
}

object DeployStorageWriter {
  implicit def fromStorage[F[_]](implicit ev: DeployStorage[F]): DeployStorageWriter[F] =
    ev.writer

  trait MeteredDeployStorageWriter[F[_]] extends DeployStorageWriter[F] with Metered[F] {
    private[storage] abstract override def addAsExecuted(block: Block) =
      incAndMeasure("addAsExecuted", super.addAsExecuted(block))

    abstract override def addAsPending(deploys: List[Deploy]) =
      incAndMeasure("addAsPending", super.addAsPending(deploys))

    abstract override def addAsProcessed(deploys: List[Deploy]) =
      incAndMeasure("addAsProcessed", super.addAsProcessed(deploys))

    abstract override def markAsProcessedByHashes(hashes: List[ByteString]) =
      incAndMeasure("markAsProcessedByHashes", super.markAsProcessedByHashes(hashes))

    abstract override def markAsPendingByHashes(hashes: List[ByteString]) =
      incAndMeasure("markAsPendingByHashes", super.markAsPendingByHashes(hashes))

    abstract override def markAsFinalizedByHashes(hashes: List[ByteString]) =
      incAndMeasure("markAsFinalizedByHashes", super.markAsFinalizedByHashes(hashes))

    abstract override def markAsDiscardedByHashes(hashesAndReasons: List[(ByteString, String)]) =
      incAndMeasure("markAsDiscardedByHashes", super.markAsDiscardedByHashes(hashesAndReasons))

    abstract override def markAsDiscarded(expirationPeriod: FiniteDuration, message: String) =
      incAndMeasure("markAsDiscarded", super.markAsDiscarded(expirationPeriod, message))

    abstract override def cleanupDiscarded(expirationPeriod: FiniteDuration) =
      incAndMeasure("cleanupDiscarded", super.cleanupDiscarded(expirationPeriod))
  }
}

object DeployStorageReader {
  implicit def fromStorage[F[_]](
      implicit ev: DeployStorage[F],
      dv: DeployInfo.View = DeployInfo.View.FULL
  ): DeployStorageReader[F] =
    ev.reader

  trait MeteredDeployStorageReader[F[_]] extends DeployStorageReader[F] with Metered[F] {
    abstract override def contains(deployHash: DeployHash) =
      incAndMeasure("contains", super.contains(deployHash))

    abstract override def readProcessed =
      incAndMeasure("readProcessed", super.readProcessed)

    abstract override def readProcessedByAccount(account: PublicKeyHashBS) =
      incAndMeasure("readProcessedByAccount", super.readProcessedByAccount(account))

    abstract override def readProcessedHashes =
      incAndMeasure("readProcessedHashes", super.readProcessedHashes)

    abstract override def readPending =
      incAndMeasure("readPending", super.readPending)

    abstract override def readPendingHashes =
      incAndMeasure("readPendingHashes", super.readPendingHashes)

    abstract override def readPendingHeaders =
      incAndMeasure("readPendingHeaders", super.readPendingHeaders)

    abstract override def readPendingHashesAndHeaders: fs2.Stream[F, (DeployHash, Deploy.Header)] =
      incAndMeasure("readPendingHashesAndHeaders", super.readPendingHashesAndHeaders)

    abstract override def getPendingOrProcessed(deployHash: DeployHash) =
      incAndMeasure("getPendingOrProcessed", super.getPendingOrProcessed(deployHash))

    abstract override def countsByBufferedStates(states: DeployInfo.State*) =
      incAndMeasure("countsByBufferedStates", super.countsByBufferedStates(states: _*))

    abstract override def getByHash(deployHash: DeployHash) =
      incAndMeasure("getByHash", super.getByHash(deployHash))

    abstract override def getByHashes(deployHashes: Set[DeployHash]): fs2.Stream[F, Deploy] =
      incAndMeasure("getByHashes", super.getByHashes(deployHashes))

    abstract override def getProcessingResults(deployHash: DeployHash) =
      incAndMeasure("getProcessingResults", super.getProcessingResults(deployHash))

    abstract override def getProcessedDeploys(blockHash: BlockHash) =
      incAndMeasure("getProcessedDeploys", super.getProcessedDeploys(blockHash))

    abstract override def getBufferedStatus(deployHash: DeployHash) =
      incAndMeasure("getBufferedStatus", super.getBufferedStatus(deployHash))

    abstract override def getDeployInfo(deployHash: DeployHash) =
      incAndMeasure("getDeployInfo", super.getDeployInfo(deployHash))

    abstract override def getDeployInfos(deploys: List[Deploy]) =
      incAndMeasure("getDeployInfos", super.getDeployInfos(deploys))

    abstract override def getDeploysByAccount(
        account: PublicKeyHashBS,
        limit: Int,
        lastTimeStamp: Long,
        lastDeployHash: DeployHash,
        next: Boolean
    ) =
      incAndMeasure(
        "getDeploysByAccount",
        super.getDeploysByAccount(
          account,
          limit,
          lastTimeStamp,
          lastDeployHash,
          next
        )
      )

    abstract override def getDeploySummary(deployHash: DeployHash): F[Option[DeploySummary]] =
      incAndMeasure("getDeploySummary", super.getDeploySummary(deployHash))
  }
}
