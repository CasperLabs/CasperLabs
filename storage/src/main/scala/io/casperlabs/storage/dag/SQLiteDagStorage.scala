package io.casperlabs.storage.dag

import cats._
import cats.data._
import cats.effect._
import cats.implicits._
import com.google.protobuf.ByteString
import doobie._
import doobie.implicits._
import io.casperlabs.casper.consensus.info.BlockInfo
import io.casperlabs.casper.consensus.{Block, BlockSummary}
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.crypto.Keys.PublicKeyHash
import io.casperlabs.metrics.Metrics
import io.casperlabs.metrics.Metrics.Source
import io.casperlabs.models.BlockImplicits._
import io.casperlabs.models.Message
import io.casperlabs.storage.{BlockHash, DagStorageMetricsSource}
import io.casperlabs.storage.block.SQLiteBlockStorage.blockInfoCols
import io.casperlabs.storage.dag.DagRepresentation.Validator
import io.casperlabs.storage.dag.DagStorage.{
  MeteredDagRepresentation,
  MeteredDagStorage,
  MeteredTipRepresentation
}
import io.casperlabs.storage.util.DoobieCodecs

import scala.collection.JavaConverters._
import cats.effect.concurrent.Ref
import io.casperlabs.storage.dag.AncestorsStorage.MeteredAncestorsStorage
import io.casperlabs.storage.dag.FinalityStorage.MeteredFinalityStorage

class SQLiteDagStorage[F[_]: Sync](
    chunkSize: Int,
    readXa: Transactor[F],
    writeXa: Transactor[F]
)(implicit met: Metrics[F])
    extends DagStorage[F]
    with DagRepresentation[F]
    with AncestorsStorage[F]
    with FinalityStorage[F]
    with DoobieCodecs {
  import SQLiteDagStorage.{ranges, StreamOps}
  implicit val MT: MonadThrowable[F] = Sync[F]

  override def getRepresentation: F[DagRepresentation[F]] =
    (this: DagRepresentation[F]).pure[F]

  override def insert(block: Block): F[DagRepresentation[F]] = {
    val blockSummary     = BlockSummary.fromBlock(block)
    val deploys          = block.getBody.deploys
    val deployErrorCount = deploys.count(_.isError)
    val deployCostTotal  = deploys.map(_.cost).sum
    val deployGasPriceAvg =
      if (deployCostTotal == 0L) 0L
      else
        deploys
          .map(d => d.cost * d.getDeploy.getHeader.gasPrice)
          .sum / deployCostTotal

    val jRank    = block.getHeader.jRank
    val mainRank = block.getHeader.mainRank

    val isFinalized = false
    val isOrphaned  = false
    val insertBlockMetadata =
      (fr"""INSERT OR IGNORE INTO block_metadata
            (block_hash, validator, j_rank, main_rank, create_time_millis, """ ++ blockInfoCols() ++ fr""")
            VALUES (
              ${block.blockHash},
              ${block.validatorPublicKeyHash},
              $jRank,
              $mainRank,
              ${block.timestamp},
              ${blockSummary.toByteString},
              ${block.serializedSize},
              $deployErrorCount,
              $deployCostTotal,
              $deployGasPriceAvg,
              $isFinalized,
              $isOrphaned
            )
            """).update.run

    val insertJustifications =
      Update[(BlockHash, BlockHash)](
        """|INSERT OR IGNORE INTO block_justifications
           |(justification_block_hash, block_hash)
           |VALUES (?, ?)""".stripMargin
      ).updateMany(
        blockSummary.justifications
          .map(j => (j.latestBlockHash, blockSummary.blockHash))
          .toList
      )

    // The key block may or may not be an actual era ID, depending on whether
    // we're using Highway or NCB. We can find out if we select all the eras
    // that need updating. This requires that the era already exists, but
    // that should be fine, because the switch block that triggers the creation
    // of the era is processed before any block in the child era is downloaded.
    val keyBlockHash = block.getHeader.keyBlockHash

    val selectEraExists: ConnectionIO[Boolean] = {
      sql"""SELECT true
            FROM   eras
            WHERE  hash = $keyBlockHash"""
        .query[Boolean]
        .option
        .map(_ getOrElse false)
    }

    def upsertLatestMessages(keyBlockHash: BlockHash): ConnectionIO[Unit] =
      if (!block.isGenesisLike) {
        // CON-557 will add a validity condition that a block cannot cite multiple latest messages
        // from its creator, i.e. merging of swimlane is not allowed.
        val validatorPreviousMessage =
          Option(blockSummary.getHeader.validatorPrevBlockHash).filterNot(_.isEmpty)

        val insertQuery =
          sql""" INSERT OR IGNORE INTO validator_latest_messages (key_block_hash, validator, block_hash)
                 VALUES ($keyBlockHash, ${blockSummary.validatorPublicKeyHash}, ${blockSummary.blockHash})""".stripMargin

        validatorPreviousMessage
          .fold {
            // No previous message visible from the justifications.
            // This is the first block from this validator (at least according to the creator of the message).
            insertQuery.update.run.void
          } { lastMessageHash =>
            // Delete previous entry if the new block cites it.
            // Insert new one.
            sql"""DELETE FROM validator_latest_messages
                  WHERE key_block_hash = $keyBlockHash
                  AND validator = ${blockSummary.validatorPublicKeyHash}
                  AND block_hash = $lastMessageHash""".update.run >>
              insertQuery.update.run.void
          }
      } else ().pure[ConnectionIO]

    val insertTopologicalSorting =
      if (block.isGenesisLike) {
        ().pure[ConnectionIO]
      } else {
        Update[(BlockHash, BlockHash, Boolean)](
          """INSERT OR IGNORE INTO block_parents
             (parent_block_hash, child_block_hash, is_main)
             VALUES (?, ?, ?)"""
        ).updateMany(
            blockSummary.parentHashes.zipWithIndex.map {
              case (parentBlockHash, idx) =>
                (parentBlockHash, blockSummary.blockHash, idx == 0)
            }.toList
          )
          .void
      }

    def insertAncestorsSkipList(ancestors: List[(Long, BlockHash)]): ConnectionIO[Unit] =
      Update[(BlockHash, Long, BlockHash)]("""INSERT OR IGNORE INTO message_ancestors_skiplist
           (block_hash, distance, ancestor_hash) VALUES (?, ?, ?)""")
        .updateMany(ancestors.map {
          case (distance, ancestorHash) => (block.blockHash, distance, ancestorHash)
        })
        .void

    def transaction(ancestors: List[(Long, BlockHash)]) =
      for {
        _ <- insertBlockMetadata
        _ <- insertJustifications
        _ <- insertTopologicalSorting
        // Maintain a version of latest messages across the whole DAG, independent of eras,
        // for pull based gossiping, until era statuses are added which allows us to find active ones easily.
        _ <- upsertLatestMessages(ByteString.EMPTY)
        // Update era-specific latest messages in this era. Child eras don't need to be updated because the
        // application layer can track and cache it on its own.
        _ <- selectEraExists.ifM(
              upsertLatestMessages(keyBlockHash),
              ().pure[ConnectionIO]
            )
        _ <- insertAncestorsSkipList(ancestors).whenA(ancestors.nonEmpty)
      } yield ()

    for {
      ancestors <- collectMessageAncestors(block)
      _         <- transaction(ancestors).transact(writeXa)
      dag       <- getRepresentation
    } yield dag
  }

  override private[storage] def findAncestor(
      block: BlockHash,
      distance: Long
  ): F[Option[BlockHash]] =
    sql"""SELECT ancestor_hash FROM message_ancestors_skiplist
          WHERE block_hash=$block AND distance=$distance"""
      .query[BlockHash]
      .option
      .transact(readXa)

  override def checkpoint(): F[Unit] = ().pure[F]

  override def clear(): F[Unit] =
    (for {
      _ <- sql"DELETE FROM block_parents".update.run
      _ <- sql"DELETE FROM block_justifications".update.run
      _ <- sql"DELETE FROM validator_latest_messages".update.run
      _ <- sql"DELETE FROM block_metadata".update.run
    } yield ()).transact(writeXa)

  override def close(): F[Unit] = ().pure[F]

  override def children(blockHash: BlockHash): F[Set[BlockHash]] =
    sql"""|SELECT child_block_hash
          |FROM block_parents
          |WHERE parent_block_hash=$blockHash""".stripMargin
      .query[BlockHash]
      .to[Set]
      .transact(readXa)

  override def getMainChildren(blockHash: BlockHash): F[Set[BlockHash]] =
    sql"""|SELECT child_block_hash
          |FROM block_parents
          |WHERE parent_block_hash=$blockHash AND is_main=true""".stripMargin
      .query[BlockHash]
      .to[Set]
      .transact(readXa)

  override def justificationToBlocks(blockHash: BlockHash): F[Set[BlockHash]] =
    sql"""|SELECT block_hash
          |FROM block_justifications
          |WHERE justification_block_hash=$blockHash""".stripMargin
      .query[BlockHash]
      .to[Set]
      .transact(readXa)

  override def lookup(blockHash: BlockHash): F[Option[Message]] =
    sql"""|SELECT data
          |FROM block_metadata
          |WHERE block_hash=$blockHash""".stripMargin
      .query[BlockSummary]
      .option
      .transact(readXa)
      .flatMap(Message.fromOptionalSummary[F](_))

  override def contains(blockHash: BlockHash): F[Boolean] =
    sql"""|SELECT 1
          |FROM block_metadata
          |WHERE block_hash=$blockHash""".stripMargin
      .query[Long]
      .option
      .transact(readXa)
      .map(_.nonEmpty)

  override def topoSort(
      startBlockNumber: Long,
      endBlockNumber: Long
  ): fs2.Stream[F, Vector[BlockInfo]] = {
    val cols = blockInfoCols()
    // Query a list of ranks at a time, releasing the database connection in between.
    val batches = ranges(chunkSize)(startBlockNumber, endBlockNumber)
    val batchStream: fs2.Stream[F, List[(Long, BlockInfo)]] =
      fs2.Stream
        .fromIterator[F](batches.toIterator)
        .flatMap {
          case (startBlockNumber, endBlockNumber) =>
            // Return the range as a list first so we can check if it's empty and quit early.
            fs2.Stream.eval {
              (fr"""
              SELECT j_rank, """ ++ cols ++ fr"""
              FROM   block_metadata
              WHERE  j_rank >= $startBlockNumber AND j_rank <= $endBlockNumber
              ORDER BY j_rank
              """)
                .query[(Long, BlockInfo)]
                .to[List]
                .transact(readXa)
            }
        }

    batchStream
      .takeWhile(_.nonEmpty)
      .flatMap(xs => fs2.Stream.fromIterator[F](xs.toIterator))
      .groupByRank
  }

  override def topoSort(startBlockNumber: Long): fs2.Stream[F, Vector[BlockInfo]] =
    fs2.Stream.eval(getMaxJRank).flatMap(topoSort(startBlockNumber, _))

  override def topoSortTail(tailLength: Int): fs2.Stream[F, Vector[BlockInfo]] =
    fs2.Stream.eval(getMaxJRank).flatMap(x => topoSort(math.max(x - tailLength + 1, 0), x))

  private def getMaxJRank: F[Long] =
    sql"""SELECT max(j_rank) FROM block_metadata"""
      .query[Long]
      .option
      .transact(readXa)
      .map(_.getOrElse(0L))

  override def getBlockInfosByValidator(
      validator: Validator,
      limit: Int,
      lastTimeStamp: Long,
      lastBlockHash: BlockHash
  ) =
    (fr"SELECT " ++ blockInfoCols() ++ fr""" FROM block_metadata
             WHERE validator=$validator AND
             (create_time_millis < $lastTimeStamp OR create_time_millis = $lastTimeStamp AND block_hash < $lastBlockHash)
             ORDER BY create_time_millis DESC, block_hash DESC
             LIMIT $limit""")
      .query[BlockInfo]
      .to[List]
      .transact(readXa)

  override def latestInEra(keyBlockHash: BlockHash): F[EraTipRepresentation[F]] = Sync[F].delay {
    SQLiteTipRepresentation.era(keyBlockHash): EraTipRepresentation[F]
  }

  override def latestGlobal: F[GlobalTipRepresentation[F]] =
    globalTipRepresentation.value.pure[F]

  private val globalTipRepresentation = Eval.later {
    SQLiteTipRepresentation.global: GlobalTipRepresentation[F]
  }

  // There will be a global representation with an empty key block hash,
  // and the era-specific versions. We can use the global for gossiping,
  // without having to worry about filtering for which era is active.
  abstract class SQLiteTipRepresentation(keyBlockHash: BlockHash) extends TipRepresentation[F] {

    override def latestMessageHash(validator: Validator): F[Set[BlockHash]] =
      sql"""SELECT block_hash
            FROM validator_latest_messages
            WHERE key_block_hash = $keyBlockHash AND validator = $validator"""
        .query[BlockHash]
        .to[Set]
        .transact(readXa)

    override def latestMessage(validator: Validator): F[Set[Message]] =
      sql"""SELECT m.data
            FROM validator_latest_messages v
            INNER JOIN block_metadata m ON v.block_hash=m.block_hash
            WHERE v.key_block_hash = $keyBlockHash AND v.validator = $validator"""
        .query[BlockSummary]
        .to[List]
        .transact(readXa)
        .flatMap(_.traverse(toMessageSummaryF))
        .map(_.toSet)

    override def latestMessageHashes: F[Map[Validator, Set[BlockHash]]] =
      sql"""SELECT validator, block_hash
            FROM validator_latest_messages
            WHERE key_block_hash = $keyBlockHash"""
        .query[(ByteString, BlockHash)]
        .to[List]
        .transact(readXa)
        .map(_.groupBy(x => PublicKeyHash(x._1)).mapValues(_.map(_._2).toSet))

    override def latestMessages: F[Map[Validator, Set[Message]]] =
      sql"""SELECT v.validator, m.data
            FROM validator_latest_messages v
            INNER JOIN block_metadata m ON m.block_hash = v.block_hash
            WHERE v.key_block_hash = $keyBlockHash"""
        .query[(ByteString, BlockSummary)]
        .to[List]
        .transact(readXa)
        .flatMap(_.traverse { case (v, bs) => toMessageSummaryF(bs).map(v -> _) })
        .map(_.groupBy(x => PublicKeyHash(x._1)).mapValues(_.map(_._2).toSet))
  }

  abstract class SQLiteGlobalTipRepresentation
      extends SQLiteTipRepresentation(ByteString.EMPTY)
      with GlobalTipRepresentation[F] {

    // As a bit of optimisation, I don't want to retrieve all the false
    // equivocations that are stored under empty key_block_hash values
    // in Highway mode. But in NCB mode they are the ones to look for.
    // Passing the flag to the storage would be easy, but updating all
    // the tests is tedious. We'll eventually get rid of NCB, so in the
    // meantime let's just detect which mode we're in based on data.

    private val isHighwayRef = Ref.unsafe[F, Option[Boolean]](None)

    private def isHighway: F[Option[Boolean]] =
      isHighwayRef.get.flatMap {
        case None =>
          // Could look for the presence of eras, but I wanted a query
          // that can have a yes|no|undecided state. If the query was
          // somehow run before the first era is inserted, it could
          // be set to false prematurely.
          sql"""
          SELECT
            EXISTS(SELECT 1 FROM validator_latest_messages WHERE key_block_hash = x'')
              AS has_empty,
            EXISTS(SELECT 1 FROM validator_latest_messages WHERE key_block_hash != x'')
              AS has_defined
          """
            .query[(Boolean, Boolean)]
            .unique
            .map {
              case (_, true) => Some(true)
              case (true, _) => Some(false)
              case _         => None
            }
            .transact(readXa)
            .flatTap(isHighwayRef.set)

        case value =>
          value.pure[F]
      }

    override def getEquivocations: F[Map[Validator, Map[BlockHash, Set[Message]]]] =
      isHighway flatMap {
        case None =>
          Map.empty.pure[F]
        case Some(isHighway) =>
          sql"""SELECT v.validator, v.key_block_hash, m.data
            FROM validator_latest_messages v
            INNER JOIN block_metadata m ON m.block_hash = v.block_hash
            WHERE (v.validator, v.key_block_hash) IN (
              SELECT validator, key_block_hash
              FROM   validator_latest_messages
              WHERE  $isHighway = false AND key_block_hash = x''
                  OR $isHighway = true AND key_block_hash != x''
              GROUP BY validator, key_block_hash
              HAVING COUNT(block_hash) > 1
            )
          """
            .query[(ByteString, BlockHash, BlockSummary)]
            .to[List]
            .transact(readXa)
            .flatMap(_.traverse {
              case (v, k, b) => toMessageSummaryF(b).map((PublicKeyHash(v), k, _))
            })
            .map { ms =>
              ms.groupBy(_._1).mapValues(_.groupBy(_._2).mapValues(_.map(_._3).toSet))
            }
      }
  }

  object SQLiteTipRepresentation {
    def era(keyBlockHash: BlockHash) =
      new SQLiteTipRepresentation(keyBlockHash)
        with EraTipRepresentation[F]
        with MeteredTipRepresentation[F] {
        override implicit val m: Metrics[F] = met
        override implicit val ms: Source    = SQLiteDagStorage.MetricsSource
        override implicit val a: Apply[F]   = Sync[F]
      }
    def global =
      new SQLiteGlobalTipRepresentation with MeteredTipRepresentation[F] {
        override implicit val m: Metrics[F] = met
        override implicit val ms: Source    = SQLiteDagStorage.MetricsSource
        override implicit val a: Apply[F]   = Sync[F]
      }
  }

  override def markAsFinalized(
      lfb: BlockHash,
      finalized: Set[BlockHash],
      orphaned: Set[BlockHash]
  ): F[Unit] = {
    val lfbQuery =
      sql"""UPDATE block_metadata SET is_finalized=TRUE, is_main_chain=TRUE WHERE block_hash=$lfb""".update.run

    val finalizedQuery = NonEmptyList.fromList(finalized.toList).fold(doobie.free.connection.unit) {
      nel =>
        val q = fr"""UPDATE block_metadata SET is_finalized=TRUE WHERE """ ++ Fragments
          .in(fr"block_hash", nel)

        q.update.run.void
    }

    val orphanedQuery =
      NonEmptyList.fromList(orphaned.toList).fold(doobie.free.connection.unit) { nel =>
        val q = fr"""UPDATE block_metadata SET is_orphaned=TRUE WHERE """ ++ Fragments
          .in(fr"block_hash", nel)

        q.update.run.void
      }

    val lfbChainQuery =
      sql"""INSERT OR IGNORE INTO lfb_chain (block_hash, indirectly_finalized, indirectly_orphaned)
             VALUES ($lfb, ${ByteString.copyFrom(finalized.asJava)}, ${ByteString.copyFrom(
        orphaned.asJava
      )})""".update.run.void

    val transaction = for {
      _ <- lfbQuery
      _ <- finalizedQuery
      _ <- orphanedQuery
      _ <- lfbChainQuery
    } yield ()

    transaction.transact(writeXa)
  }

  override def getFinalityStatus(block: BlockHash): F[FinalityStorage.FinalityStatus] =
    sql"""SELECT is_finalized, is_orphaned FROM block_metadata WHERE block_hash=$block"""
      .query[(Boolean, Boolean)]
      .unique
      .map {
        case (isFinalized, isOrphaned) =>
          FinalityStorage.FinalityStatus(isFinalized, isOrphaned)
      }
      .transact(readXa)

  override def getLastFinalizedBlock: F[BlockHash] =
    sql"""SELECT block_hash FROM lfb_chain ORDER BY rowid DESC LIMIT 1"""
      .query[BlockHash]
      .unique
      .transact(readXa)

  private val toMessageSummaryF: BlockSummary => F[Message] = bs =>
    MonadThrowable[F].fromTry(Message.fromBlockSummary(bs))
}

object SQLiteDagStorage {

  private val MetricsSource = Metrics.Source(DagStorageMetricsSource, "sqlite")

  private case class Fs2State(
      buffer: Vector[BlockInfo] = Vector.empty,
      jRank: Long = -1
  )

  private implicit class StreamOps[F[_]: Bracket[*[_], Throwable]](
      val stream: fs2.Stream[F, (Long, BlockInfo)]
  ) {
    private type ErrorOr[B] = Either[Throwable, B]
    private type G[B]       = StateT[ErrorOr, Vector[Vector[BlockInfo]], B]

    /* Returns block summaries grouped by ranks, in ascending order. */
    def groupByRank: fs2.Stream[F, Vector[BlockInfo]] = go(Fs2State(), stream).stream

    /** Check [[https://fs2.io/guide.html#statefully-transforming-streams]]
      * and [[https://blog.leifbattermann.de/2017/10/08/error-and-state-handling-with-monad-transformers-in-scala/]]
      * if it's hard to understand what's going on
      *  */
    private def go(
        state: Fs2State,
        s: fs2.Stream[F, (Long, BlockInfo)]
    ): fs2.Pull[F, Vector[BlockInfo], Unit] =
      s.pull.uncons.flatMap {
        case Some((chunk, streamTail)) =>
          chunk
            .foldLeftM[G, Fs2State](state) {
              case (Fs2State(_, currentRank), (jRank, info)) if currentRank == -1 =>
                Fs2State(Vector(info), jRank).pure[G]
              case (Fs2State(acc, currentRank), (jRank, info)) if jRank == currentRank =>
                Fs2State(acc :+ info, currentRank).pure[G]
              case (Fs2State(acc, currentRank), (jRank, info)) if jRank > currentRank =>
                put(acc) >> Fs2State(Vector(info), jRank).pure[G]
              case (Fs2State(acc, currentRank), (jRank, info)) =>
                error(
                  new IllegalArgumentException(
                    s"Ranks must increase monotonically, got prev rank: $currentRank, prev block: ${msg(
                      acc.last
                    )}, next rank: ${jRank}, next block: ${msg(info)}"
                  )
                )
            }
            .run(Vector.empty)
            .fold(
              ex => fs2.Pull.raiseError[F](ex), {
                case (output, newState) =>
                  fs2.Pull.output(fs2.Chunk.vector(output)) >> go(newState, streamTail)
              }
            )
        case None => fs2.Pull.output(fs2.Chunk(state.buffer)) >> fs2.Pull.done
      }

    private def put(infos: Vector[BlockInfo]) =
      StateT.modify[ErrorOr, Vector[Vector[BlockInfo]]](_ :+ infos)

    private def error(e: Throwable) =
      StateT.liftF[ErrorOr, Vector[Vector[BlockInfo]], Fs2State](e.asLeft[Fs2State])

    private def msg(info: BlockInfo): String =
      Base16.encode(info.getSummary.blockHash.toByteArray).take(10)
  }

  private[storage] def create[F[_]: Sync](
      chunkSize: Int,
      readXa: Transactor[F],
      writeXa: Transactor[F]
  )(
      implicit
      met: Metrics[F]
  ): F[
    DagStorage[F] with DagRepresentation[F] with FinalityStorage[F] with AncestorsStorage[F]
  ] =
    for {
      dagStorage <- Sync[F].delay(
                     new SQLiteDagStorage[F](chunkSize, readXa, writeXa)
                       with MeteredDagStorage[F]
                       with MeteredDagRepresentation[F]
                       with MeteredAncestorsStorage[F]
                       with MeteredFinalityStorage[F] {
                       override implicit val m: Metrics[F] = met
                       override implicit val ms: Source    = MetricsSource
                       override implicit val a: Apply[F]   = Sync[F]
                     }
                   )
    } yield dagStorage: DagStorage[F] with DagRepresentation[F] with FinalityStorage[F] with AncestorsStorage[
      F
    ]

  def ranges(chunkSize: Int)(startBlockNumber: Long, endBlockNumber: Long): Stream[(Long, Long)] =
    (startBlockNumber to endBlockNumber by chunkSize.toLong).toStream.map { from =>
      from -> math.min(from + chunkSize - 1, endBlockNumber)
    }
}
