package io.casperlabs.models.cltype

import io.casperlabs.models.cltype
import io.casperlabs.models.bytesrepr._

sealed trait Key {
  protected val tag: Byte
  protected def innerToBytes: Array[Byte]
}

object Key {
  case class Account(address: ByteArray32) extends Key {
    override protected val tag: Byte        = Account.tag
    protected def innerToBytes: Array[Byte] = ToBytes[ByteArray32].toBytes(address)
  }

  object Account {
    val tag: Byte = 0
  }

  case class Hash(address: ByteArray32) extends Key {
    override protected val tag: Byte        = Hash.tag
    protected def innerToBytes: Array[Byte] = ToBytes[ByteArray32].toBytes(address)
  }

  object Hash {
    val tag: Byte = 1
  }

  case class URef(uref: cltype.URef) extends Key {
    override protected val tag: Byte        = URef.tag
    protected def innerToBytes: Array[Byte] = ToBytes[cltype.URef].toBytes(uref)
  }

  object URef {
    val tag: Byte = 2
  }

  implicit val toBytesKey: ToBytes[Key] = new ToBytes[Key] {
    override def toBytes(k: Key): Array[Byte] =
      k.tag +: k.innerToBytes
  }

  val deserializer: FromBytes.Deserializer[Key] =
    FromBytes.byte.flatMap {
      case tag if tag == Account.tag =>
        ByteArray32.deserializer.map[Key](address => Account(address))
      case tag if tag == Hash.tag => ByteArray32.deserializer.map[Key](address => Hash(address))
      case tag if tag == URef.tag => cltype.URef.deserializer.map[Key](uref => URef(uref))
      case other                  => FromBytes.raise(FromBytes.Error.InvalidVariantTag(other, "Key"))
    }
}
