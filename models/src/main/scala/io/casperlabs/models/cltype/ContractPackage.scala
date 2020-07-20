package io.casperlabs.models.cltype

import io.casperlabs.models.bytesrepr.{BytesView, FromBytes, ToBytes}

case class ContractPackage(
    accessURef: URef,
    versions: Map[Tuple2[Int, Int], ByteArray32],
    disabledVersions: Set[Tuple2[Int, Int]],
    groups: Map[String, Set[URef]]
)

object ContractPackage {
  implicit val toBytesContract: ToBytes[ContractPackage] = new ToBytes[ContractPackage] {
    override def toBytes(c: ContractPackage): Array[Byte] =
      ToBytes.toBytes(c.accessURef) ++
        ToBytes.toBytes(c.versions) ++
        ToBytes.toBytes(c.disabledVersions) ++
        ToBytes.toBytes(c.groups)
  }

  val deserializer: FromBytes.Deserializer[ContractPackage] =
    for {
      accessURef <- URef.deserializer
      versions <- FromBytes.map(
                   FromBytes.tuple2(FromBytes.int, FromBytes.int),
                   ByteArray32.deserializer
                 )
      disabledVersions <- FromBytes.seq(FromBytes.tuple2(FromBytes.int, FromBytes.int)).map { seq =>
                           seq.toSet
                         }
      groups <- FromBytes.map(FromBytes.string, FromBytes.seq(URef.deserializer).map { seq =>
                 seq.toSet
               })
    } yield ContractPackage(
      accessURef,
      versions,
      disabledVersions,
      groups
    )
}
