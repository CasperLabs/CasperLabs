package io.casperlabs.storage.deploy

import cats._
import cats.data.NonEmptyList
import cats.effect._
import cats.implicits._
import com.google.protobuf.ByteString
import doobie._
import doobie.implicits._
import io.casperlabs.casper.consensus.Block.ProcessedDeploy
import io.casperlabs.casper.consensus.info.DeployInfo
import io.casperlabs.casper.consensus.info.DeployInfo.ProcessingResult
import io.casperlabs.casper.consensus.{Block, Deploy, DeploySummary}
import io.casperlabs.crypto.Keys.PublicKeyHashBS
import io.casperlabs.metrics.Metrics
import io.casperlabs.metrics.Metrics.Source
import io.casperlabs.shared.Time
import io.casperlabs.storage.DeployStorageMetricsSource
import io.casperlabs.storage.DeployHash
import io.casperlabs.storage.block.SQLiteBlockStorage.blockInfoCols
import io.casperlabs.storage.util.DoobieCodecs

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._

class SQLiteDeployStorage[F[_]: Time: Sync](
    chunkSize: Int,
    readXa: Transactor[F],
    writeXa: Transactor[F]
)(
    implicit val
    metricsSource: Source,
    metrics: Metrics[F]
) extends DeployStorage[F]
    with DoobieCodecs {
  // Deploys not yet included in a block
  private val PendingStatusCode = 0
  // Deploys that have been processed at least once,
  // waiting to be finalized or orphaned
  private val ProcessedStatusCode = 1
  // Deploys that have been discarded for some reason and should be deleted after a while
  private val DiscardedStatusCode = 2

  private val StatusCodeToState = Map(
    PendingStatusCode   -> DeployInfo.State.PENDING,
    ProcessedStatusCode -> DeployInfo.State.PROCESSED,
    DiscardedStatusCode -> DeployInfo.State.DISCARDED
  ).withDefaultValue(DeployInfo.State.UNDEFINED)

  private val StateToStatusCode = StatusCodeToState.toSeq.map(_.swap).toMap.withDefaultValue(-1)

  private val readers: TrieMap[DeployInfo.View, DeployStorageReader[F]] = TrieMap.empty

  override val writer = new SQLiteDeployStorageWriter
    with DeployStorageWriter.MeteredDeployStorageWriter[F] {
    override implicit val m: Metrics[F] = metrics
    override implicit val ms: Source    = metricsSource
    override implicit val a: Apply[F]   = Sync[F]
  }

  def reader(implicit dv: DeployInfo.View) =
    readers.getOrElseUpdate(
      dv,
      new SQLiteDeployStorageReader(dv) with DeployStorageReader.MeteredDeployStorageReader[F] {
        override implicit val m: Metrics[F] = metrics
        override implicit val ms: Source    = metricsSource
        override implicit val a: Apply[F]   = Sync[F]
      }
    )

  class SQLiteDeployStorageWriter extends DeployStorageWriter[F] {

    override private[storage] def addAsExecuted(block: Block): F[Unit] = {
      val writeToDeploysTable = Update[(ByteString, ByteString, Long, ByteString, ByteString)](
        "INSERT OR IGNORE INTO deploys (hash, account, create_time_millis, summary, body) VALUES (?, ?, ?, ?, ?)"
      ).updateMany(
        block.getBody.deploys.toList.map(
          pd =>
            (
              pd.getDeploy.deployHash,
              pd.getDeploy.getHeader.accountPublicKeyHash,
              pd.getDeploy.getHeader.timestamp,
              pd.getDeploy.clearBody.toByteString,
              pd.getDeploy.getBody.toByteString
            )
        )
      )

      val writeToProcessResultsTable =
        Update[(ByteString, ByteString, Int, ByteString, Long, Long, Long, Option[String], Int)](
          """
          INSERT OR IGNORE INTO deploy_process_results
          (
           block_hash,
           deploy_hash,
           deploy_position,
           account,
           create_time_millis,
           execute_time_millis,
           cost,
           execution_error_message,
           stage
          ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
          """
        ).updateMany(
          block.getBody.deploys.zipWithIndex.map {
            case (pd, deployPosition) =>
              (
                block.blockHash,
                pd.getDeploy.deployHash,
                deployPosition,
                pd.getDeploy.getHeader.accountPublicKeyHash,
                pd.getDeploy.getHeader.timestamp,
                block.getHeader.timestamp,
                pd.cost,
                if (pd.isError) pd.errorMessage.some else none[String],
                pd.stage
              )
          }.toList
        )

      (writeToDeploysTable >> writeToProcessResultsTable).transact(writeXa).void
    }

    override def addAsPending(deploys: List[Deploy]): F[Unit] =
      insertNewDeploys(deploys, PendingStatusCode)

    override def addAsProcessed(deploys: List[Deploy]): F[Unit] =
      insertNewDeploys(deploys, ProcessedStatusCode)

    private def insertNewDeploys(
        deploys: List[Deploy],
        status: Int
    ): F[Unit] = {
      val writeToDeploysTable = Update[(ByteString, ByteString, Long, ByteString, ByteString)](
        "INSERT OR IGNORE INTO deploys (hash, account, create_time_millis, summary, body) VALUES (?, ?, ?, ?, ?)"
      ).updateMany(deploys.map { d =>
        (
          d.deployHash,
          d.getHeader.accountPublicKeyHash,
          d.getHeader.timestamp,
          d.clearBody.toByteString,
          d.getBody.toByteString
        )
      })

      def writeToBufferedDeploysTable(currentTimeEpochMillis: Long) =
        Update[(ByteString, Int, ByteString, Long, Long, ByteString, ByteString)](
          "INSERT OR IGNORE INTO buffered_deploys (hash, status, account, update_time_millis, receive_time_millis, summary, body) VALUES (?, ?, ?, ?, ?, ?, ?)"
        ).updateMany(deploys.map { d =>
            (
              d.deployHash,
              status,
              d.getHeader.accountPublicKeyHash,
              currentTimeEpochMillis,
              currentTimeEpochMillis,
              d.clearBody.toByteString,
              d.getBody.toByteString
            )
          })
          .void

      for {
        t <- Time[F].currentMillis
        _ <- (writeToDeploysTable >> writeToBufferedDeploysTable(t))
              .transact(writeXa)
        _ <- updateMetrics()
      } yield ()
    }

    override def markAsProcessedByHashes(hashes: List[ByteString]): F[Unit] =
      setStatus(hashes.map((_, none[String])), ProcessedStatusCode, PendingStatusCode)

    override def markAsPendingByHashes(hashes: List[ByteString]): F[Unit] =
      setStatus(hashes.map((_, none[String])), PendingStatusCode, ProcessedStatusCode)

    override def markAsFinalizedByHashes(hashes: List[ByteString]): F[Unit] =
      Update[(ByteString, Int)](
        s"DELETE FROM buffered_deploys WHERE hash=? AND status=?"
      ).updateMany(hashes.map(h => (h, ProcessedStatusCode)))
        .transact(writeXa)
        .void

    private def setStatus(
        hashesAndStatusMessages: List[(ByteString, Option[String])],
        newStatus: Int,
        prevStatus: Int
    ): F[Unit] =
      for {
        t <- Time[F].currentMillis
        _ <- Update[(Int, Long, Option[String], ByteString, Int)](
              s"UPDATE buffered_deploys SET status=?, update_time_millis=?, status_message=? WHERE hash=? AND status=?"
            ).updateMany(hashesAndStatusMessages.map {
                case (hash, maybeStatusMessage) =>
                  (newStatus, t, maybeStatusMessage, hash, prevStatus)
              })
              .transact(writeXa)
        _ <- updateMetrics()
      } yield ()

    override def markAsDiscardedByHashes(hashesAndReasons: List[(ByteString, String)]): F[Unit] =
      setStatus(hashesAndReasons.map {
        case (h, r) =>
          (h, r.some)
      }, DiscardedStatusCode, PendingStatusCode)

    override def cleanupDiscarded(expirationPeriod: FiniteDuration): F[Int] = {
      def transaction(threshold: Long) =
        for {
          hashes <- sql"SELECT hash FROM buffered_deploys WHERE status=$DiscardedStatusCode AND update_time_millis<$threshold"
                     .query[ByteString]
                     .to[List]
          _ <- Update[ByteString](s"DELETE FROM buffered_deploys WHERE hash=?").updateMany(hashes)
          deletedNum <- Update[ByteString](
                         s"""DELETE
                             FROM deploys
                             WHERE hash = ?
                               AND NOT EXISTS(SELECT 1
                                              FROM deploy_process_results dpr
                                              WHERE deploys.hash = dpr.deploy_hash)"""
                       ).updateMany(hashes)
        } yield deletedNum

      for {
        now        <- Time[F].currentMillis
        threshold  = now - expirationPeriod.toMillis
        deletedNum <- transaction(threshold).transact(writeXa)
      } yield deletedNum
    }

    override def markAsDiscarded(
        expirationPeriod: FiniteDuration,
        message: String
    ): F[Set[ByteString]] = {
      def transaction(now: Long, threshold: Long) =
        for {
          hashes <- sql"""
            SELECT hash
            FROM buffered_deploys
            WHERE status=$PendingStatusCode AND receive_time_millis<$threshold
            """.query[ByteString].to[List]

          _ <- Update[(Int, Long, String, ByteString)](s"""
                UPDATE buffered_deploys
                SET status=?, update_time_millis=?, status_message=?
                WHERE hash=?""").updateMany(hashes.map { h =>
                (DiscardedStatusCode, now, message, h)
              })
        } yield hashes

      for {
        now       <- Time[F].currentMillis
        threshold = now - expirationPeriod.toMillis
        hashes    <- transaction(now, threshold).transact(writeXa)
      } yield hashes.toSet
    }

    private def countByStatus(status: Int): F[Long] =
      sql"SELECT COUNT(hash) FROM buffered_deploys WHERE status=$status"
        .query[Long]
        .unique
        .transact(readXa)

    private def updateMetrics(): F[Unit] =
      for {
        pending   <- countByStatus(PendingStatusCode)
        processed <- countByStatus(ProcessedStatusCode)
        _         <- Metrics[F].setGauge("pending_deploys", pending)
        _         <- Metrics[F].setGauge("processed_deploys", processed)
      } yield ()

    override def clear(): F[Unit] =
      (for {
        _ <- sql"DELETE FROM deploys".update.run
        _ <- sql"DELETE FROM buffered_deploys".update.run
        _ <- sql"DELETE FROM deploy_process_results".update.run
      } yield ()).transact(writeXa)

    override def close(): F[Unit] = ().pure[F]
  }

  class SQLiteDeployStorageReader(dv: DeployInfo.View) extends DeployStorageReader[F] {
    private def bodyCol(alias: String = "") =
      if (dv == DeployInfo.View.BASIC) {
        fr"null"
      } else if (alias.isEmpty) {
        fr"body"
      } else {
        Fragment.const(s"${alias}.body")
      }

    private def toStream[T](items: Iterator[T]): fs2.Stream[F, T] =
      fs2.Stream.fromIterator[F](items)

    private def toStream[T](items: Iterable[T]): fs2.Stream[F, T] =
      toStream(items.toIterator)

    private def toStream[T](items: F[List[T]]): fs2.Stream[F, T] =
      fs2.Stream.eval(items).flatMap(toStream(_))

    override def contains(deployHash: DeployHash) =
      sql"SELECT 1 FROM deploys WHERE hash=${deployHash}"
        .query[Int]
        .option
        .map(_.isDefined)
        .transact(readXa)

    override def getDeploySummary(deployHash: DeployHash): F[Option[DeploySummary]] =
      sql"SELECT summary FROM deploys WHERE hash=${deployHash}"
        .query[DeploySummary]
        .option
        .transact(readXa)

    override def readProcessed: F[List[Deploy]] =
      readByStatus(ProcessedStatusCode)

    override def readPendingHeaders: F[List[Deploy.Header]] =
      readHeadersByStatus(PendingStatusCode)

    override def readPendingHashesAndHeaders: fs2.Stream[F, (ByteString, Deploy.Header)] =
      readHashesAndHeadersByStatus(PendingStatusCode)

    private def readHeadersByStatus(status: Int): F[List[Deploy.Header]] =
      sql"""SELECT summary, null
            FROM buffered_deploys
            WHERE  status=$status"""
        .query[Deploy]
        .to[List]
        .transact(readXa)
        .map(_.map(_.getHeader))

    private def readHashesAndHeadersByStatus(
        status: Int
    ): fs2.Stream[F, (ByteString, Deploy.Header)] =
      sql"""SELECT summary, null
            FROM buffered_deploys
            WHERE  status=$status"""
        .query[Deploy]
        .streamWithChunkSize(chunkSize)
        .transact(readXa)
        .map(d => d.deployHash -> d.getHeader)

    private def readByStatus(status: Int): F[List[Deploy]] =
      (fr"SELECT summary, " ++ bodyCol() ++ fr"""
          FROM buffered_deploys
          WHERE  status=$status""")
        .query[Deploy]
        .to[List]
        .transact(readXa)

    override def readProcessedByAccount(account: PublicKeyHashBS): F[List[Deploy]] =
      readByAccountAndStatus(account, ProcessedStatusCode)

    private def readByAccountAndStatus(account: PublicKeyHashBS, status: Int): F[List[Deploy]] =
      (fr"SELECT summary, " ++ bodyCol() ++ fr"""
          FROM buffered_deploys
          WHERE account=$account AND status=$status""")
        .query[Deploy]
        .to[List]
        .transact(readXa)

    override def readProcessedHashes: F[List[ByteString]] =
      readHashesByStatus(ProcessedStatusCode)

    override def readPending: F[List[Deploy]] =
      readByStatus(PendingStatusCode)

    override def readPendingHashes: F[List[ByteString]] =
      readHashesByStatus(PendingStatusCode)

    private def readHashesByStatus(status: Int): F[List[ByteString]] =
      sql"SELECT hash FROM buffered_deploys WHERE status=$status"
        .query[ByteString]
        .to[List]
        .transact(readXa)

    override def countsByBufferedStates(
        states: DeployInfo.State*
    ): F[Map[DeployInfo.State, Long]] = {
      val q = fr"""
        SELECT status, COUNT(hash)
        FROM   buffered_deploys
        """ ++ NonEmptyList.fromList(states.toList.map(StateToStatusCode)).fold(fr"") { ss =>
        fr"WHERE " ++ Fragments.in(fr"status", ss)
      } ++ fr"""
        GROUP BY status
        """

      q.query[(Int, Long)]
        .map {
          case (status, count) => StatusCodeToState(status) -> count
        }
        .to[List]
        .transact(readXa)
        .map(_.toMap)
    }

    override def getPendingOrProcessed(deployHash: DeployHash): F[Option[Deploy]] =
      (fr"SELECT summary, " ++ bodyCol() ++ fr"""
          FROM buffered_deploys
          WHERE hash=$deployHash AND (status=$PendingStatusCode OR status=$ProcessedStatusCode)""")
        .query[Deploy]
        .option
        .transact(readXa)

    override def getByHashes(
        deployHashes: Set[DeployHash]
    ): fs2.Stream[F, Deploy] =
      toStream(deployHashes.toList.grouped(chunkSize))
        .map(NonEmptyList.fromList(_))
        .flatMap {
          case None =>
            toStream(List.empty[Deploy])
          case Some(batch) =>
            val q = fr"SELECT summary, " ++ bodyCol() ++ fr" FROM deploys WHERE " ++ Fragments
              .in(fr"hash", batch) // "hash IN (…)"
            // Read batch into a list, which will be slowly drained by the stream consumer.
            toStream(q.query[Deploy].to[List].transact(readXa))
        }

    def getByHash(deployHash: DeployHash): F[Option[Deploy]] =
      getByHashes(Set(deployHash)).compile.last

    override def getProcessedDeploys(blockHash: ByteString): F[List[ProcessedDeploy]] =
      (fr"SELECT d.summary, " ++ bodyCol("d") ++ fr""", cost, execution_error_message, stage
          FROM deploy_process_results dpr
          JOIN deploys d
            ON d.hash = dpr.deploy_hash
          WHERE dpr.block_hash=$blockHash
          ORDER BY deploy_position""")
        .query[ProcessedDeploy]
        .to[List]
        .transact(readXa)

    override def getProcessingResults(
        deployHash: DeployHash
    ): F[List[(ByteString, ProcessedDeploy)]] = {
      val getDeploy =
        (fr"SELECT summary, " ++ bodyCol() ++ fr" FROM deploys WHERE hash=$deployHash")
          .query[Deploy]
          .unique
          .transact(readXa)

      val readProcessingResults =
        sql"""SELECT block_hash, cost, execution_error_message, stage
              FROM deploy_process_results
              WHERE deploy_hash=$deployHash
              ORDER BY execute_time_millis DESC"""
          .query[(ByteString, ProcessedDeploy)]
          .to[List]
          .transact(readXa)

      for {
        blockHashesAndProcessingResults <- readProcessingResults
        res <- if (blockHashesAndProcessingResults.isEmpty)
                blockHashesAndProcessingResults.pure[F]
              else
                getDeploy.map(
                  d =>
                    blockHashesAndProcessingResults.map {
                      case (blockHash, processingResult) =>
                        (blockHash, processingResult.withDeploy(d))
                    }
                )
      } yield res
    }

    override def getBufferedStatus(deployHash: DeployHash): F[Option[DeployInfo.Status]] =
      sql"""SELECT status, status_message
            FROM buffered_deploys
            WHERE hash=$deployHash """
        .query[(Int, Option[String])]
        .option
        .transact(readXa)
        .map(_.map {
          case (status, maybeMessage) =>
            DeployInfo.Status(
              state = StatusCodeToState(status),
              message = maybeMessage.getOrElse("")
            )
        })

    override def getDeployInfo(
        deployHash: DeployHash
    ): F[Option[DeployInfo]] =
      getByHash(deployHash) flatMap {
        case None =>
          none[DeployInfo].pure[F]
        case Some(deploy) =>
          getDeployInfos(List(deploy)).map(_.headOption)
      }

    override def getDeploysByAccount(
        account: PublicKeyHashBS,
        limit: Int,
        lastTimeStamp: Long,
        lastDeployHash: DeployHash,
        isNext: Boolean
    ): F[List[Deploy]] = {
      val sql = if (isNext) {
        (fr"SELECT summary, " ++ bodyCol() ++ fr""" FROM deploys
             WHERE account = $account AND
             (create_time_millis < $lastTimeStamp OR create_time_millis = $lastTimeStamp AND hash < $lastDeployHash)
             ORDER BY create_time_millis DESC, hash DESC
             LIMIT $limit""")
      } else {
        (fr"SELECT summary, " ++ bodyCol() ++ fr""" FROM deploys
             WHERE account = $account AND
             (create_time_millis > $lastTimeStamp OR create_time_millis = $lastTimeStamp AND hash > $lastDeployHash)
             ORDER BY create_time_millis ASC, hash ASC
             LIMIT $limit""")
      }
      sql
        .query[Deploy]
        .to[List]
        .map(l => {
          if (isNext) {
            l
          } else {
            l.reverse
          }
        })
        .transact(readXa)
    }

    override def getDeployInfos(deploys: List[Deploy]): F[List[DeployInfo]] = {
      val deployHashes = deploys.map(_.deployHash)

      def processingResults: F[Map[DeployHash, List[ProcessingResult]]] =
        NonEmptyList
          .fromList[ByteString](deployHashes)
          .fold(Map.empty[DeployHash, List[ProcessingResult]].pure[F])(nel => {
            val q = fr"""SELECT dpr.deploy_hash, dpr.cost, dpr.execution_error_message, dpr.stage,
                                """ ++ blockInfoCols("bm") ++ fr"""
                        FROM deploy_process_results dpr
                        JOIN block_metadata bm ON dpr.block_hash = bm.block_hash
                        WHERE """ ++ Fragments.in(fr"dpr.deploy_hash", nel)
            q.query[(DeployHash, ProcessingResult)]
              .to[List]
              .transact(readXa)
              .map(_.groupBy(_._1).map {
                case (deployHash: DeployHash, l: Seq[(DeployHash, ProcessingResult)]) =>
                  (deployHash, l.map(_._2).sortBy(-_.getBlockInfo.getSummary.getHeader.timestamp))
              })
          })

      def getStatus: F[List[(DeployHash, DeployInfo.Status)]] =
        NonEmptyList
          .fromList(deployHashes)
          .fold(List.empty[(DeployHash, DeployInfo.Status)].pure[F])(nel => {
            val statusSql =
              fr"""SELECT hash,status, status_message
                   FROM buffered_deploys
                   WHERE """ ++ Fragments.in(fr"hash", nel)

            statusSql
              .query[(Array[Byte], Int, Option[String])]
              .to[List]
              .transact(readXa)
              .map(_.map {
                case (deployHash, status, maybeMessage) =>
                  (
                    ByteString.copyFrom(deployHash),
                    DeployInfo.Status(
                      state = StatusCodeToState(status),
                      message = maybeMessage.getOrElse("")
                    )
                  )
              })
          })

      for {
        deployHashToProcessingResults <- processingResults
        deployHashToBufferedStatus    <- getStatus.map(_.toMap)
        deployInfos = deploys.map(d => {
          val bs = deployHashToBufferedStatus.get(d.deployHash)
          deployHashToProcessingResults.get(d.deployHash) match {
            case Some(prs) =>
              DeployInfo()
                .withDeploy(d)
                .withStatus(
                  // NOTE: Deploys that arrive in the body of other blocks will never
                  // go into the buffer, so they are straight away reported as finalized
                  // by any node other than the one where they were sent. If we could derive
                  // that status from the blocks themselves that would be more holistic.
                  bs getOrElse DeployInfo
                    .Status(DeployInfo.State.FINALIZED)
                )
                .withProcessingResults(prs)
            case None =>
              DeployInfo(status = bs).withDeploy(d)
          }
        })
      } yield deployInfos
    }
  }
}

object SQLiteDeployStorage {
  private implicit val metricsSource: Source = Metrics.Source(DeployStorageMetricsSource, "sqlite")

  private[storage] def create[F[_]: Metrics: Time: Sync](
      deployStorageChunkSize: Int,
      readXa: Transactor[F],
      writeXa: Transactor[F]
  ): F[DeployStorage[F]] =
    for {
      _ <- establishMetrics[F]
    } yield new SQLiteDeployStorage[F](deployStorageChunkSize, readXa, writeXa): DeployStorage[F]

  /** Export base 0 values so we have non-empty series for charts. */
  private def establishMetrics[F[_]: Monad: Metrics]: F[Unit] =
    for {
      _ <- Metrics[F].setGauge("pending_deploys", 0L)
      _ <- Metrics[F].setGauge("processed_deploys", 0L)
    } yield ()

}
