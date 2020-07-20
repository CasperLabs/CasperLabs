package io.casperlabs.models.cltype

import io.casperlabs.models.bytesrepr.{BytesView, FromBytes, ToBytes}
import io.casperlabs.models.cltype

sealed trait StoredValue

object StoredValue {
  case class CLValue(value: cltype.CLValue)                           extends StoredValue
  case class Account(account: cltype.Account)                         extends StoredValue
  case class ContractWasm(contractWasm: cltype.ContractWasm)          extends StoredValue
  case class Contract(contract: cltype.Contract)                      extends StoredValue
  case class ContractPackage(contractPackage: cltype.ContractPackage) extends StoredValue

  implicit val toBytesStoredValue: ToBytes[StoredValue] = new ToBytes[StoredValue] {
    override def toBytes(v: StoredValue): Array[Byte] = v match {
      case CLValue(value)             => CLVALUE_TAG +: ToBytes.toBytes(value)
      case Account(account)           => ACCOUNT_TAG +: ToBytes.toBytes(account)
      case ContractWasm(contractWasm) => CONTRACT_WASM_TAG +: ToBytes.toBytes(contractWasm)
      case Contract(contract)         => CONTRACT_TAG +: ToBytes.toBytes(contract)
      case ContractPackage(contractPackage) =>
        CONTRACT_PACKAGE_TAG +: ToBytes.toBytes(contractPackage)
    }
  }

  val deserializer: FromBytes.Deserializer[StoredValue] =
    FromBytes.byte.flatMap {
      case tag if tag == CLVALUE_TAG  => cltype.CLValue.deserializer.map(v => CLValue(v))
      case tag if tag == ACCOUNT_TAG  => cltype.Account.deserializer.map(v => Account(v))
      case tag if tag == CONTRACT_TAG => cltype.Contract.deserializer.map(v => Contract(v))
      case tag if tag == CONTRACT_WASM_TAG =>
        cltype.ContractWasm.deserializer.map(v => ContractWasm(v))
      case tag if tag == CONTRACT_PACKAGE_TAG =>
        cltype.ContractPackage.deserializer.map(v => ContractPackage(v))
      case other => FromBytes.raise(FromBytes.Error.InvalidVariantTag(other, "StoredValue"))
    }

  val CLVALUE_TAG: Byte          = 0
  val ACCOUNT_TAG: Byte          = 1
  val CONTRACT_WASM_TAG: Byte    = 2
  val CONTRACT_TAG: Byte         = 3
  val CONTRACT_PACKAGE_TAG: Byte = 4
}
