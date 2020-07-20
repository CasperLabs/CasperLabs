package io.casperlabs.storage.dag

import java.io.Serializable

import cats.{Applicative, Monad}
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.Block
import io.casperlabs.casper.consensus.info.BlockInfo
import io.casperlabs.metrics.Metered
import io.casperlabs.models.Message
import io.casperlabs.storage.BlockHash
import io.casperlabs.storage.dag.DagRepresentation.Validator
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.crypto.Keys.PublicKeyHashBS
import simulacrum.typeclass

import scala.util.Try
import scala.util.Success
import scala.util.Failure
import scala.util.control.NoStackTrace
import cats.Functor
import com.github.ghik.silencer.silent

trait DagStorage[F[_]] {

  /** Doesn't guarantee to return immutable representation. */
  def getRepresentation: F[DagRepresentation[F]]

  def checkpoint(): F[Unit]

  def clear(): F[Unit]

  def close(): F[Unit]

  /** Insert a block into the DAG and update the latest messages.
    * In the presence of eras, the block only affects the latest messages
    * of the era which the block is part of. To detect equivocations
    * or the tips, the caller needs to look at multiple eras along the tree.
    */
  private[storage] def insert(block: Block): F[DagRepresentation[F]]
}

object DagStorage {
  def apply[F[_]](implicit B: DagStorage[F]): DagStorage[F] = B

  trait MeteredDagStorage[F[_]] extends DagStorage[F] with Metered[F] {

    abstract override def getRepresentation: F[DagRepresentation[F]] =
      incAndMeasure("representation", super.getRepresentation)

    abstract override def insert(block: Block): F[DagRepresentation[F]] =
      incAndMeasure("insert", super.insert(block))

    abstract override def checkpoint(): F[Unit] =
      incAndMeasure("checkpoint", super.checkpoint())
  }

  trait MeteredDagRepresentation[F[_]] extends DagRepresentation[F] with Metered[F] {

    abstract override def children(blockHash: BlockHash): F[Set[BlockHash]] =
      incAndMeasure("children", super.children(blockHash))

    abstract override def getMainChildren(blockHash: BlockHash): F[Set[BlockHash]] =
      incAndMeasure("getMainChildren", super.getMainChildren(blockHash))

    abstract override def justificationToBlocks(blockHash: BlockHash): F[Set[BlockHash]] =
      incAndMeasure("justificationToBlocks", super.justificationToBlocks(blockHash))

    abstract override def lookup(blockHash: BlockHash): F[Option[Message]] =
      incAndMeasure("lookup", super.lookup(blockHash))

    abstract override def contains(blockHash: BlockHash): F[Boolean] =
      incAndMeasure("contains", super.contains(blockHash))

    abstract override def topoSort(
        startBlockNumber: Long,
        endBlockNumber: Long
    ): fs2.Stream[F, Vector[BlockInfo]] =
      super.incAndMeasure(
        "topoSort",
        super
          .topoSort(startBlockNumber, endBlockNumber)
      )

    abstract override def topoSort(startBlockNumber: Long): fs2.Stream[F, Vector[BlockInfo]] =
      super.incAndMeasure("topoSort", super.topoSort(startBlockNumber))

    abstract override def topoSortTail(tailLength: Int): fs2.Stream[F, Vector[BlockInfo]] =
      super.incAndMeasure("topoSortTail", super.topoSortTail(tailLength))
  }

  trait MeteredTipRepresentation[F[_]] extends TipRepresentation[F] with Metered[F] {
    abstract override def latestMessageHash(validator: Validator): F[Set[BlockHash]] =
      incAndMeasure("latestMessageHash", super.latestMessageHash(validator))

    abstract override def latestMessage(validator: Validator): F[Set[Message]] =
      incAndMeasure("latestMessage", super.latestMessage(validator))

    abstract override def latestMessageHashes: F[Map[Validator, Set[BlockHash]]] =
      incAndMeasure("latestMessageHashes", super.latestMessageHashes)

    abstract override def latestMessages: F[Map[Validator, Set[Message]]] =
      incAndMeasure("latestMessages", super.latestMessages)
  }
}

trait TipRepresentation[F[_]] {
  def latestMessageHash(validator: Validator): F[Set[BlockHash]]
  def latestMessage(validator: Validator): F[Set[Message]]
  def latestMessageHashes: F[Map[Validator, Set[BlockHash]]]
  def latestMessages: F[Map[Validator, Set[Message]]]

  // Returns latest messages from honest validators
  def latestMessagesHonestValidators(implicit A: Applicative[F]): F[Map[Validator, Message]] =
    latestMessages.map { latestMessages =>
      latestMessages.collect {
        case (v, messages) if messages.size == 1 =>
          (v, messages.head)
      }
    }
}

trait EraTipRepresentation[F[_]] extends TipRepresentation[F] {
  // TODO: These methods should move here from DagRepresentationRich,
  // to make sure we never try to detect equivocators on the global
  // representation, however for that we need lots of updates in
  // the casper codebase.

  // Equivocation will be defined as the set of validators who
  // equivocated in the eras between the key block and the current era,
  // but it will be up to the caller to aggregate this information,
  // i.e. to decide how far to look back, before forgiveness kicks in.

  // Since latest messages are restricted to this era, when looking
  // to build a new block, one has to reduce the tips of multiple eras
  // into the final set of childless blocks.

  // The justifications of a new block can be chosen as the reduced set of:
  // * the latest messages in the era,
  // * the parent block candidate hashes, and
  // * the justifications of the parent block candidates.

  /** Get the equivocators in *this* era. */
  def getEquivocators(implicit A: Applicative[F]): F[Set[Validator]] =
    getEquivocations.map(_.keySet)

  /** Get the equivocations in *this* era. */
  def getEquivocations(implicit A: Applicative[F]): F[Map[Validator, Set[Message]]] =
    latestMessages.map(_.filter(_._2.size > 1))
}

trait GlobalTipRepresentation[F[_]] extends TipRepresentation[F] {

  /** Get the equivocations across all eras. */
  def getEquivocations: F[Map[Validator, Map[BlockHash, Set[Message]]]]

  /** Get the equivocators across all eras. */
  def getEquivocators(implicit A: Applicative[F]): F[Set[Validator]] =
    getEquivocations.map(_.keySet)
}

@typeclass trait DagLookup[F[_]] {
  def lookup(blockHash: BlockHash): F[Option[Message]]
  def contains(blockHash: BlockHash): F[Boolean]

  def lookupBlockUnsafe(blockHash: BlockHash)(implicit MT: MonadThrowable[F]): F[Message.Block] =
    lookupUnsafe(blockHash).flatMap(
      msg =>
        Try(msg.asInstanceOf[Message.Block]) match {
          case Success(value) => MT.pure(value)
          case Failure(_)     =>
            // PrettyPrinter is not visible here.
            val hashEncoded = Base16.encode(blockHash.toByteArray).take(10)
            MT.raiseError(
              new Exception(
                s"$hashEncoded was expected to be a Block but was a Ballot."
              ) with NoStackTrace
            )
        }
    )

  def lookupUnsafe(blockHash: BlockHash)(implicit MT: MonadThrowable[F]): F[Message] =
    lookup(blockHash) flatMap {
      MonadThrowable[F].fromOption(
        _,
        new NoSuchElementException(
          s"DAG store was missing ${Base16.encode(blockHash.toByteArray)}."
        )
      )
    }
}

trait DagRepresentation[F[_]] extends DagLookup[F] {
  def getMainChildren(blockHash: BlockHash): F[Set[BlockHash]]

  def children(blockHash: BlockHash): F[Set[BlockHash]]

  /** Return blocks which have a specific block in their justifications. */
  def justificationToBlocks(blockHash: BlockHash): F[Set[BlockHash]]

  /** Return block summaries with ranks in the DAG between start and end, inclusive. */
  def topoSort(
      startBlockNumber: Long,
      endBlockNumber: Long
  ): fs2.Stream[F, Vector[BlockInfo]]

  /** Return block summaries with ranks of blocks in the DAG from a start index to the end. */
  def topoSort(startBlockNumber: Long): fs2.Stream[F, Vector[BlockInfo]]

  def topoSortTail(tailLength: Int): fs2.Stream[F, Vector[BlockInfo]]

  def getBlockInfosByValidator(
      validator: Validator,
      limit: Int,
      lastTimeStamp: Long,
      lastBlockHash: BlockHash
  ): F[List[BlockInfo]]

  /** Get a global representation, which can be used in:
    * 1) naive casper mode, without eras
    * 2) in the gossiping, when nodes ask each other for their latest blocks
    *
    * Latest messages would be that of any era which is considered active,
    * so that pull based gossiping can pull ballots of eras still being finalized
    * as well as the child era which is already started, but we stop returning
    * records for eras that have already finished (their last ballots are no longer
    * relevant tips).
    *
    * This will not reflect equivocations in the presence of parallel eras.
    *
    * Doesn't guarantee to return immutable representation.
    */
  def latestGlobal: F[GlobalTipRepresentation[F]]

  /** Get a representation restricted to a given era, which mean anyone
    * with more than 1 entry in their latest messages must have equivocated
    * in *this* era. If they equivocated in an ancestor era, that has to be
    * detected separately in the application layer by walking backward on
    * the era tree, according to the forgiveness settings.
    *
    * Messages in sibling eras are invisible to each other.
    *
    * The DAG itself, i.e. the parent child relationships are unaffected.
    */
  def latestInEra(keyBlockHash: BlockHash): F[EraTipRepresentation[F]]

}

object DagRepresentation {
  type Validator = PublicKeyHashBS

  implicit class DagRepresentationRich[F[_]: Monad](
      dagRepresentation: DagRepresentation[F]
  ) {

    // Returns a set of validators that this node has seen equivocating.
    def getEquivocators: F[Set[Validator]] =
      getEquivocations.map(_.keySet)

    // Returns a mapping between equivocators and their messages.
    def getEquivocations: F[Map[Validator, Set[Message]]] =
      latestMessages.map(_.filter(_._2.size > 1))

    def latestMessages: F[Map[Validator, Set[Message]]] =
      dagRepresentation.latestGlobal.flatMap(_.latestMessages)

    // NOTE: These extension methods are here so the Naive-Casper codebase doesn't have to do another
    // step (i.e. `.latestGlobal.flatMap { tip => ... }`)  but in Highway we should first specify the era.

    def latestMessagesInEra(keyBlockHash: ByteString): F[Map[Validator, Set[Message]]] =
      dagRepresentation.latestInEra(keyBlockHash).flatMap(_.latestMessages)

    def getEquivocatorsInEra(keyBlockHash: ByteString): F[Set[Validator]] =
      dagRepresentation.latestInEra(keyBlockHash).flatMap(_.getEquivocators)

    def latestMessageHash(validator: Validator): F[Set[BlockHash]] =
      dagRepresentation.latestGlobal.flatMap(_.latestMessageHash(validator))

    def latestMessage(validator: Validator): F[Set[Message]] =
      dagRepresentation.latestGlobal.flatMap(_.latestMessage(validator))

    def latestMessageHashes: F[Map[Validator, Set[BlockHash]]] =
      dagRepresentation.latestGlobal.flatMap(_.latestMessageHashes)

    // Returns latest messages from honest validators
    def latestMessagesHonestValidators: F[Map[Validator, Message]] =
      latestMessages.map { latestMessages =>
        latestMessages.collect {
          case (v, messages) if messages.size == 1 =>
            (v, messages.head)
        }
      }
  }

  def apply[F[_]](implicit ev: DagRepresentation[F]): DagRepresentation[F] = ev
}
