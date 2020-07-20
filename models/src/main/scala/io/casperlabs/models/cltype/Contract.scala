package io.casperlabs.models.cltype

import io.casperlabs.models.bytesrepr.{BytesView, FromBytes, ToBytes}

case class Contract(
    contractPackageHash: ByteArray32,
    contractWasmHash: ByteArray32,
    namedKeys: Map[String, Key],
    entryPoints: Map[String, EntryPoint],
    protocolVersion: SemVer
)

object Contract {
  implicit val toBytesContract: ToBytes[Contract] = new ToBytes[Contract] {
    override def toBytes(c: Contract): Array[Byte] =
      ToBytes.toBytes(c.contractPackageHash) ++
        ToBytes.toBytes(c.contractWasmHash) ++
        ToBytes.toBytes(c.namedKeys) ++
        ToBytes.toBytes(c.entryPoints) ++
        ToBytes.toBytes(c.protocolVersion)
  }

  val deserializer: FromBytes.Deserializer[Contract] =
    for {
      contractPackageHash <- ByteArray32.deserializer
      contractHash        <- ByteArray32.deserializer
      namedKeys           <- FromBytes.map(FromBytes.string, Key.deserializer)
      entryPoints         <- FromBytes.map(FromBytes.string, EntryPoint.deserializer)
      protocolVersion     <- SemVer.deserializer

    } yield Contract(contractPackageHash, contractHash, namedKeys, entryPoints, protocolVersion)
}
