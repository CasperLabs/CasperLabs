package io.casperlabs.models.cltype

import io.casperlabs.models.bytesrepr.{BytesView, FromBytes, ToBytes}

sealed trait EntryPointType {
  val tag: Byte
}

object EntryPointType {
  implicit val toBytesEntryPointType: ToBytes[EntryPointType] = new ToBytes[EntryPointType] {
    override def toBytes(entryPointType: EntryPointType): Array[Byte] =
      ToBytes.toBytes(entryPointType.tag)
  }

  val deserializer: FromBytes.Deserializer[EntryPointType] =
    FromBytes.byte.flatMap {
      case tag if tag == 0 => FromBytes.pure(EntryPointType.Session)
      case tag if tag == 1 => FromBytes.pure(EntryPointType.Contract)
      case other           => FromBytes.raise(FromBytes.Error.InvalidVariantTag(other, "EntryPointType"))
    }

  case object Session extends EntryPointType {
    val tag                         = 0;
    override def toString(): String = "Session";
  }
  case object Contract extends EntryPointType {
    val tag                         = 1;
    override def toString(): String = "Contract";
  }
}
