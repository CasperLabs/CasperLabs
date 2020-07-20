package io.casperlabs.models.cltype

import io.casperlabs.models.bytesrepr.FromBytes
import io.casperlabs.models.cltype

sealed trait StoredValueInstance

object StoredValueInstance {
  def from(sv: StoredValue): Either[FromBytes.Error, StoredValueInstance] = sv match {
    case StoredValue.CLValue(v) =>
      cltype.CLValueInstance.from(v).map(instance => CLValue(instance))
    case StoredValue.Account(a)         => Right(Account(a))
    case StoredValue.ContractWasm(c)    => Right(ContractWasm(c))
    case StoredValue.Contract(c)        => Right(Contract(c))
    case StoredValue.ContractPackage(c) => Right(ContractPackage(c))
  }

  case class CLValue(value: cltype.CLValueInstance)                   extends StoredValueInstance
  case class Account(account: cltype.Account)                         extends StoredValueInstance
  case class Contract(contract: cltype.Contract)                      extends StoredValueInstance
  case class ContractWasm(contract: cltype.ContractWasm)              extends StoredValueInstance
  case class ContractPackage(contractPackage: cltype.ContractPackage) extends StoredValueInstance
}
