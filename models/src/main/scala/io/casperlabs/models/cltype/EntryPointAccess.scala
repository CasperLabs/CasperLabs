package io.casperlabs.models.cltype

import io.casperlabs.models.bytesrepr.{BytesView, FromBytes, ToBytes}

sealed trait EntryPointAccess {
  val tag: Byte

}

object EntryPointAccess {

  implicit val toBytesEntryPointAccess: ToBytes[EntryPointAccess] = new ToBytes[EntryPointAccess] {
    override def toBytes(access: EntryPointAccess): Array[Byte] =
      access match {
        case Public()       => ToBytes.toBytes(access.tag)
        case Groups(groups) => ToBytes.toBytes(access.tag) ++ ToBytes.toBytes(groups)
      }
  }

  val deserializer: FromBytes.Deserializer[EntryPointAccess] =
    FromBytes.byte.flatMap {
      case tag if tag == 1 => FromBytes.pure(EntryPointAccess.Public())
      case tag if tag == 2 =>
        for {
          groups <- FromBytes.seq(FromBytes.string)
        } yield EntryPointAccess.Groups(groups)
      case other => FromBytes.raise(FromBytes.Error.InvalidVariantTag(other, "EntryPointAccess"))
    }

  case class Public() extends EntryPointAccess {
    val tag: Byte                   = 1
    override def toString(): String = "Public";
  }

  case class Groups(labels: Seq[String]) extends EntryPointAccess {
    val tag: Byte                   = 2
    override def toString(): String = "Groups(" + labels.mkString(", ") + ")";
  }
}
