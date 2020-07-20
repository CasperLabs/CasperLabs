package io.casperlabs.models

import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus
import io.casperlabs.casper.consensus.Block.MessageType.{BALLOT, BLOCK, Unrecognized}
import io.casperlabs.casper.consensus.Block.MessageRole
import io.casperlabs.casper.consensus.{BlockSummary, Bond}
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.crypto.Keys, Keys.{PublicKey, PublicKeyBS, PublicKeyHash, PublicKeyHashBS}
import io.casperlabs.models.BlockImplicits._
import io.casperlabs.catscontrib.MonadThrowable
import cats.implicits._
import shapeless.tag.@@
import Message.{JRank, MainRank}

import scala.util.{Failure, Success, Try}
import scala.reflect.quasiquotes.Rank
import shapeless.tag

/** A sum type representing message types that can be exchanged between validators.
  *
  * The intent is to map protobuf messages to internal types as soon as it's possible.
  * This way we can do necessary validation of protobuf fields (like existence of required fields)
  * and represent different messages in type-safe way.
  */
sealed trait Message {
  type Id = ByteString
  val messageHash: Id
  val validatorId: PublicKeyHashBS
  val timestamp: Long
  val jRank: JRank
  val mainRank: MainRank
  val parentBlock: Id
  val justifications: Seq[consensus.Block.Justification]
  val validatorMsgSeqNum: Int
  val signature: consensus.Signature
  val roundId: Long

  // Returns the `keyBlockHash`, but it was considered unintuitive after a while.
  val eraId: Id

  val parents: Seq[Id]
  val blockSummary: BlockSummary

  val validatorPrevMessageHash: Id
  val messageRole: consensus.Block.MessageRole

  def isBlock: Boolean
  def isBallot: Boolean = !isBlock

  def isGenesisLike: Boolean =
    this.parents.isEmpty &&
      this.validatorId.isEmpty &&
      this.signature.sig.isEmpty
}

object Message {
  sealed trait JRankTag
  sealed trait MainRankTag

  type JRank = Long @@ JRankTag
  def asJRank(l: Long): JRank = l.asInstanceOf[JRank]

  type MainRank = Long @@ MainRankTag
  def asMainRank(l: Long): MainRank = l.asInstanceOf[MainRank]

  case class Block private (
      messageHash: Message#Id,
      validatorId: PublicKeyHashBS,
      timestamp: Long,
      roundId: Long,
      eraId: Message#Id,
      parentBlock: Message#Id,
      justifications: Seq[consensus.Block.Justification],
      jRank: JRank,
      mainRank: MainRank,
      validatorMsgSeqNum: Int,
      signature: consensus.Signature,
      blockSummary: BlockSummary,
      validatorPrevMessageHash: Message#Id,
      messageRole: MessageRole
  ) extends Message {
    // For Genesis block we expect it to have no parents.
    // We could either encode it as separate ADT variant or keep the assumptions.
    override val parents: Seq[Id] =
      (parentBlock +: secondaryParents).filterNot(_ == ByteString.EMPTY)

    lazy val secondaryParents =
      if (blockSummary.getHeader.parentHashes.isEmpty) Seq.empty
      else blockSummary.getHeader.parentHashes.tail

    lazy val weightMap: Map[PublicKeyHashBS, Weight] = blockSummary.getHeader.getState.bonds.map {
      case Bond(validatorPk, stake) => PublicKeyHash(validatorPk) -> Weight(stake)
    }.toMap

    def isBlock: Boolean = true
  }

  case class Ballot private (
      messageHash: Message#Id,
      validatorId: PublicKeyHashBS,
      timestamp: Long,
      roundId: Long,
      eraId: Message#Id,
      parentBlock: Message#Id,
      justifications: Seq[consensus.Block.Justification],
      jRank: JRank,
      mainRank: MainRank,
      validatorMsgSeqNum: Int,
      signature: consensus.Signature,
      blockSummary: BlockSummary,
      validatorPrevMessageHash: Message#Id,
      messageRole: MessageRole
  ) extends Message {
    override val parents: Seq[Id] = Seq(parentBlock)
    def isBlock: Boolean          = false
  }

  def fromBlockSummary(b: consensus.BlockSummary): Try[Message] =
    try {
      val messageHash        = b.blockHash
      val header             = b.getHeader
      val timestamp          = header.timestamp
      val roundId            = header.roundId
      val keyBlockHash       = header.keyBlockHash
      val parentBlock        = header.parentHashes.headOption.getOrElse(ByteString.EMPTY)
      val validatorId        = PublicKeyHash(header.validatorPublicKeyHash)
      val justifications     = header.justifications
      val jRank              = asJRank(header.jRank)
      val mainRank           = asMainRank(header.mainRank)
      val validatorMsgSeqNum = header.validatorBlockSeqNum
      val messageType        = header.messageType
      val signature          = b.getSignature
      val prevMsgHash        = header.validatorPrevBlockHash
      val messageRole        = header.messageRole

      messageType match {
        case BALLOT =>
          Success(
            Ballot(
              messageHash,
              validatorId,
              timestamp,
              roundId,
              keyBlockHash,
              parentBlock,
              justifications,
              jRank,
              mainRank,
              validatorMsgSeqNum,
              signature,
              b,
              prevMsgHash,
              messageRole
            )
          )
        case BLOCK =>
          Success(
            Block(
              messageHash,
              validatorId,
              timestamp,
              roundId,
              keyBlockHash,
              parentBlock,
              justifications,
              jRank,
              mainRank,
              validatorMsgSeqNum,
              signature,
              b,
              prevMsgHash,
              messageRole
            )
          )
        case Unrecognized(_) =>
          Failure(
            new IllegalArgumentException(
              s"The message ${Base16.encode(messageHash.toByteArray).take(10)} has unrecognized message type."
            )
          )
      }
    } catch {
      case ex: Throwable =>
        val message =
          s"The message ${Base16.encode(b.blockHash.toByteArray).take(10)} could not be parsed to either Block type or a Ballot."
        Failure(new IllegalArgumentException(message, ex))
    }

  def fromBlock(b: consensus.Block): Try[Message] =
    fromBlockSummary(BlockSummary.fromBlock(b))

  def fromOptionalSummary[F[_]: MonadThrowable](
      b: Option[consensus.BlockSummary]
  ): F[Option[Message]] =
    b.fold(none[Message].pure[F])(
      bs => MonadThrowable[F].fromTry(fromBlockSummary(bs)).map(Some(_))
    )
}
