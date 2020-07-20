package io.casperlabs.models.cltype

import io.casperlabs.models.bytesrepr.{BytesView, FromBytes, ToBytes}

case class EntryPoint(
    name: String,
    parameters: Map[String, CLType],
    ret: CLType,
    access: EntryPointAccess,
    entryPointType: EntryPointType
)

object EntryPoint {
  implicit val toBytesEntryPoint: ToBytes[EntryPoint] = new ToBytes[EntryPoint] {
    override def toBytes(c: EntryPoint): Array[Byte] =
      ToBytes.toBytes(c.name) ++
        ToBytes.toBytes(c.parameters) ++
        ToBytes.toBytes(c.ret) ++
        ToBytes.toBytes(c.access) ++
        ToBytes.toBytes(c.entryPointType)
  }

  val deserializer: FromBytes.Deserializer[EntryPoint] =
    for {
      name           <- FromBytes.string
      parameters     <- FromBytes.map(FromBytes.string, CLType.deserializer)
      ret            <- CLType.deserializer
      access         <- EntryPointAccess.deserializer
      entryPointType <- EntryPointType.deserializer
    } yield EntryPoint(name, parameters, ret, access, entryPointType)
}
