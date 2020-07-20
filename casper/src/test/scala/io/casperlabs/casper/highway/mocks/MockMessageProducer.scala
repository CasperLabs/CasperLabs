package io.casperlabs.casper.highway.mocks

import cats._
import cats.implicits._
import cats.syntax.show
import cats.effect.Sync
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.{Block, BlockSummary}
import io.casperlabs.casper.highway.{MessageProducer, Ticks}
import io.casperlabs.casper.util.ProtoUtil
import io.casperlabs.crypto.Keys
import io.casperlabs.storage.BlockHash
import io.casperlabs.storage.BlockMsgWithTransform
import io.casperlabs.storage.block.BlockStorageWriter
import io.casperlabs.storage.dag.DagStorage
import io.casperlabs.models.Message
import scala.util.control.NonFatal
import io.casperlabs.shared.ByteStringPrettyPrinter._
import io.casperlabs.shared.Log

class MockMessageProducer[F[_]: Sync: BlockStorageWriter: DagStorage](
    val validatorId: Keys.PublicKeyHashBS
) extends MessageProducer[F] {

  override def hasPendingDeploys = false.pure[F]

  private def insert(message: Message): F[Unit] = {
    val summary = message.blockSummary
    for {
      _ <- BlockStorageWriter[F].put(
            BlockMsgWithTransform().withBlockMessage(
              Block(
                blockHash = summary.blockHash,
                header = summary.header,
                signature = summary.signature
              )
            )
          )
      // Check that we got a version of the storage that maintains the DAG store as well.
      dag <- DagStorage[F].getRepresentation
      msg <- dag.lookup(message.messageHash)
      _ = assert(
        msg.isDefined,
        s"Storing block ${message.messageHash.show} did not update the DAG storage!"
      )
    } yield ()
  }

  private def withParent[A <: Message](
      parentBlockHash: Message
  )(f: Message => F[A]): F[A] =
    for {
      dag <- DagStorage[F].getRepresentation
      parent <- dag.lookupUnsafe(parentBlockHash.messageHash).recoverWith {
                 case NonFatal(ex) =>
                   Sync[F].raiseError(
                     new IllegalStateException(
                       s"Couldn't look up parent in MockMessageProducer: $ex"
                     )
                   )
               }
      child <- f(parent)
      _     <- insert(child)
    } yield child

  override def ballot(
      keyBlockHash: BlockHash,
      roundId: Ticks,
      target: Message.Block,
      justifications: Map[Keys.PublicKeyHashBS, Set[Message]],
      messageRole: Block.MessageRole
  ): F[Message.Ballot] = withParent(target) { _ =>
    val unsigned = BlockSummary()
      .withHeader(
        Block
          .Header()
          .withMessageType(Block.MessageType.BALLOT)
          .withMessageRole(messageRole)
          .withValidatorPublicKeyHash(validatorId)
          .withMainRank(target.mainRank + 1)
          .withJRank(
            (target.jRank +: justifications.values.flatten.map(_.jRank).toList)
              .map(_.asInstanceOf[Long])
              .max + 1
          )
          .withParentHashes(List(target.messageHash))
          .withJustifications(
            for {
              kv <- justifications.toList
              h  <- kv._2.toList.map(_.messageHash)
            } yield Block.Justification(kv._1, h)
          )
          .withRoundId(roundId)
          .withKeyBlockHash(keyBlockHash)
          .withState(
            Block.GlobalState().withBonds(target.blockSummary.getHeader.getState.bonds)
          )
      )
    val hash   = ProtoUtil.protoHash(unsigned)
    val signed = unsigned.withBlockHash(hash)

    Sync[F]
      .fromTry(Message.fromBlockSummary(signed))
      .map(_.asInstanceOf[Message.Ballot])
  }

  override def block(
      keyBlockHash: ByteString,
      roundId: Ticks,
      mainParent: Message.Block,
      justifications: Map[Keys.PublicKeyHashBS, Set[Message]],
      isBookingBlock: Boolean,
      messageRole: Block.MessageRole
  ): F[Message.Block] =
    withParent(mainParent) { _ =>
      val unsigned = BlockSummary()
        .withHeader(
          Block
            .Header()
            .withMessageRole(messageRole)
            .withValidatorPublicKeyHash(validatorId)
            .withMainRank(mainParent.mainRank + 1)
            .withJRank(
              (mainParent.jRank +: justifications.values.flatten.map(_.jRank).toList)
                .map(_.asInstanceOf[Long])
                .max + 1
            )
            .withParentHashes(List(mainParent.messageHash))
            .withJustifications(
              for {
                kv <- justifications.toList
                h  <- kv._2.toList.map(_.messageHash)
              } yield Block.Justification(kv._1, h)
            )
            .withRoundId(roundId)
            .withKeyBlockHash(keyBlockHash)
            .withState(
              Block.GlobalState().withBonds(mainParent.blockSummary.getHeader.getState.bonds)
            )
        )
      val hash   = ProtoUtil.protoHash(unsigned)
      val signed = unsigned.withBlockHash(hash)

      Sync[F]
        .fromTry(Message.fromBlockSummary(signed))
        .map(_.asInstanceOf[Message.Block])
    }
}
