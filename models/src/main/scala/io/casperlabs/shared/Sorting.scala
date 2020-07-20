package io.casperlabs.shared

import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.BlockSummary
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.crypto.Keys
import io.casperlabs.models.BlockImplicits._
import io.casperlabs.models.Message
import io.casperlabs.models.Message.{JRank, MainRank}

object Sorting {

  implicit val byteArrayOrdering: Ordering[Array[Byte]] = Ordering.by(Base16.encode)

  implicit val byteStringOrdering: Ordering[ByteString]              = Ordering.by(_.toByteArray)
  implicit val publicKeyHashOrdering: Ordering[Keys.PublicKeyHashBS] = Ordering.by(_.toByteArray)

  implicit val blockSummaryOrdering: Ordering[BlockSummary] = (x: BlockSummary, y: BlockSummary) =>
    x.jRank.compare(y.jRank) match {
      case 0 => Ordering[ByteString].compare(x.blockHash, y.blockHash)
      case n => n
    }

  implicit def messageSummaryOrdering[A <: Message]: Ordering[A] =
    (x: A, y: A) =>
      x.jRank.compare(y.jRank) match {
        case 0 => Ordering[ByteString].compare(x.messageHash, y.messageHash)
        case n => n
      }

  implicit val jRankOrdering: Ordering[JRank]       = Ordering.by[JRank, Long](identity)
  implicit val mainRankOrdering: Ordering[MainRank] = Ordering.by[MainRank, Long](identity)

  implicit def catsOrder[T: Ordering]: cats.Order[T] = cats.Order.fromOrdering[T]

  // For some reason, these are not derived automatically :/
  implicit val jRankOrder    = catsOrder[JRank]
  implicit val mainRankOrder = catsOrder[MainRank]
}
