package io.casperlabs.models.cltype

import io.casperlabs.models.bytesrepr.{BytesView, FromBytes, ToBytes}

case class ContractWasm(bytes: IndexedSeq[Byte])

object ContractWasm {
  implicit val toBytesContract: ToBytes[ContractWasm] = new ToBytes[ContractWasm] {
    override def toBytes(c: ContractWasm): Array[Byte] =
      ToBytes.toBytes(c.bytes)
  }

  val deserializer: FromBytes.Deserializer[ContractWasm] =
    for {
      contractBytes <- FromBytes.bytes
    } yield ContractWasm(contractBytes.toIndexedSeq)
}
