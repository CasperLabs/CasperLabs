package io.casperlabs.casper

import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.state.Key.URef.AccessRights
import io.casperlabs.crypto.codec._
import io.casperlabs.ipc._
import io.casperlabs.casper.consensus.state._
import io.casperlabs.shared.ByteStringPrettyPrinter

object PrettyPrinter extends ByteStringPrettyPrinter {

  def buildString(k: Key): String = k.value match {
    case Key.Value.Empty                         => "KeyEmpty"
    case Key.Value.Address(Key.Address(address)) => s"Address(${buildString(address)})"
    case Key.Value.Uref(Key.URef(id, accessRights)) =>
      s"URef(${buildString(id)}, ${buildString(accessRights)})"
    case Key.Value.Hash(Key.Hash(hash)) => s"Hash(${buildString(hash)})"
  }

  def buildString(t: Transform): String = t.transformInstance match {
    case Transform.TransformInstance.Empty                        => "TransformEmpty"
    case Transform.TransformInstance.AddI32(TransformAddInt32(i)) => s"Add($i)"
    case Transform.TransformInstance.AddBigInt(TransformAddBigInt(value)) =>
      s"AddBigInt(${value.get.value})"
    case Transform.TransformInstance.AddKeys(TransformAddKeys(ks)) =>
      s"Insert(${ks.map(buildString).mkString(",")})"
    case Transform.TransformInstance.Failure(_)  => "TransformFailure"
    case Transform.TransformInstance.Identity(_) => "Read"
    case Transform.TransformInstance.Write(TransformWrite(mv)) =>
      mv match {
        case None    => "Write(Nothing)"
        case Some(v) => s"Write(${buildString(v)})"
      }
    case Transform.TransformInstance.AddU64(TransformAddUInt64(x)) => s"AddU64($x)"
  }

  def buildString(v: Option[ProtocolVersion]): String = v match {
    case None          => "No protocol version"
    case Some(version) => s"${version}"
  }

  def buildString(nk: NamedKey): String = nk match {
    case NamedKey(_, None)         => "EmptyNamedKey"
    case NamedKey(name, Some(key)) => s"NamedKey($name, ${buildString(key)})"
  }

  def buildString(clType: CLType): String = clType match {
    case CLType(CLType.Variants.Empty) => "Empty"
    case CLType(CLType.Variants.SimpleType(CLType.Simple.Unrecognized(i))) =>
      s"Unrecognized(${i})"

    case CLType(CLType.Variants.SimpleType(CLType.Simple.BOOL)) =>
      "Bool"
    case CLType(CLType.Variants.SimpleType(CLType.Simple.I32)) =>
      "I32"
    case CLType(CLType.Variants.SimpleType(CLType.Simple.I64)) =>
      "I64"
    case CLType(CLType.Variants.SimpleType(CLType.Simple.U8)) => "U8"
    case CLType(CLType.Variants.SimpleType(CLType.Simple.U32)) =>
      "U32"
    case CLType(CLType.Variants.SimpleType(CLType.Simple.U64)) =>
      "U64"
    case CLType(CLType.Variants.SimpleType(CLType.Simple.U128)) =>
      "U128"
    case CLType(CLType.Variants.SimpleType(CLType.Simple.U256)) =>
      "U256"
    case CLType(CLType.Variants.SimpleType(CLType.Simple.U512)) =>
      "U512"
    case CLType(CLType.Variants.SimpleType(CLType.Simple.UNIT)) =>
      "Unit"
    case CLType(CLType.Variants.SimpleType(CLType.Simple.STRING)) =>
      "String"
    case CLType(CLType.Variants.SimpleType(CLType.Simple.KEY)) =>
      "Key"
    case CLType(CLType.Variants.SimpleType(CLType.Simple.UREF)) =>
      "URef"

    case CLType(CLType.Variants.OptionType(CLType.OptionProto(innerProto))) =>
      innerProto match {
        case None    => "Unspecified type"
        case Some(t) => buildString(t)
      }

    case CLType(CLType.Variants.ListType(CLType.List(innerProto))) =>
      innerProto match {
        case None    => "Unspecified type"
        case Some(t) => s"List(${buildString(t)})"
      }

    case CLType(
        CLType.Variants.FixedListType(CLType.FixedList(innerProto, length))
        ) =>
      innerProto match {
        case None    => "Unspecified type"
        case Some(t) => s"FixedList(${buildString(t)}, ${length})"
      }

    case CLType(CLType.Variants.ResultType(CLType.Result(okProto, errProto))) =>
      s"Result{ok: ${okProto.map(buildString).getOrElse("Unspecified type")}, err: ${errProto.map(buildString).getOrElse("Unspecified type")}"

    case CLType(CLType.Variants.MapType(CLType.Map(keyProto, valueProto))) =>
      s"Map{key: ${keyProto.map(buildString).getOrElse("Unspecified type")}, value: ${valueProto.map(buildString).getOrElse("Unspecified type")}"

    case CLType(CLType.Variants.Tuple1Type(CLType.Tuple1(innerProto))) =>
      innerProto match {
        case None    => "Unspecified type"
        case Some(t) => s"Tuple(${buildString(t)})"
      }

    case CLType(CLType.Variants.Tuple2Type(CLType.Tuple2(t1Proto, t2Proto))) =>
      s"Tuple(${t1Proto.map(buildString).getOrElse("Unspecified type")}, ${t2Proto.map(buildString).getOrElse("Unspecified type")})"

    case CLType(
        CLType.Variants.Tuple3Type(CLType.Tuple3(t1Proto, t2Proto, t3Proto))
        ) =>
      s"Tuple(${t1Proto.map(buildString).getOrElse("Unspecified type")}, ${t2Proto
        .map(buildString)
        .getOrElse("Unspecified type")}, ${t3Proto.map(buildString).getOrElse("Unspecified type")})"

    case CLType(CLType.Variants.AnyType(CLType.Any())) => "Any"
  }

  def buildString(access: Contract.EntryPoint.Access): String = access match {
    case Contract.EntryPoint.Access.Public(Contract.EntryPoint.Public()) => "Public"
    case Contract.EntryPoint.Access.Groups(Contract.EntryPoint.Groups(groups)) =>
      s"Groups(${groups
        .map { x =>
          x.name
        }
        .mkString(",")})"

    case Contract.EntryPoint.Access.Empty => "Empty"
  }

  def buildString(entryPointType: Contract.EntryPoint.EntryPointType): String =
    entryPointType match {
      case Contract.EntryPoint.EntryPointType.Session(Contract.EntryPoint.SessionType()) =>
        "Session"
      case Contract.EntryPoint.EntryPointType.Contract(Contract.EntryPoint.ContractType()) =>
        "Contract"
      case Contract.EntryPoint.EntryPointType.Empty => "Empty"
    }

  def buildString(arg: Contract.EntryPoint.Arg): String =
    s"Arg(${arg.name}, ${arg.clType.map(buildString).getOrElse("Unspecified type")}"

  def buildString(entryPoint: Contract.EntryPoint): String =
    s"EntryPoint(${entryPoint.name}, ${entryPoint.args
      .map(buildString)
      .mkString(", ")}, ${entryPoint.ret.map(buildString).getOrElse("Unspecified type")}, ${buildString(
      entryPoint.access
    )}, ${buildString(entryPoint.entryPointType)})"

  def buildString(v: StoredValue): String = v.variants match {
    case StoredValue.Variants.Account(
        Account(
          pk,
          urefs,
          mainPurse,
          associatedKeys,
          actionThresholds
        )
        ) =>
      s"Account(${buildString(pk)}, {${urefs.map(buildString).mkString(",")}}, ${mainPurse
        .map(buildString)}, {${associatedKeys
        .map(buildString)
        .mkString(",")}, {${actionThresholds.map(buildString)}})"
    case StoredValue.Variants.Contract(
        Contract(
          contractPackageHash,
          contractWasmHash,
          namedKeys,
          entryPoints @ _,
          protocolVersion @ _
        )
        ) =>
      s"Contract(${buildString(contractPackageHash)}, ${buildString(contractWasmHash)}, {${namedKeys
        .map(buildString)
        .mkString(", ")}}, {${entryPoints.map(buildString).mkString(", ")}}, ${buildString(protocolVersion)})"
    case StoredValue.Variants.ClValue(_) => "ClValue"
    case StoredValue.Variants.ContractPackage(
        ContractPackage(accessKey, activeVersions, disabledVersions, groups)
        ) =>
      s"ContractPackage(${accessKey.map(buildString).getOrElse("No access key")}, {${activeVersions
        .map(buildString)
        .mkString(", ")}}, {${disabledVersions.map(buildString).mkString(", ")}}, {${groups.map(buildString).mkString(", ")}})"
    case StoredValue.Variants.ContractWasm(_) => "ContractWasm"
    case StoredValue.Variants.Empty           => "Empty"
  }

  def buildString(v: ContractVersionKey): String =
    s"ContractVersionKey(${v.protocolVersionMajor}, ${v.contractVersion})"

  def buildString(v: Contract.EntryPoint.Group): String = s"${v.name}"

  def buildString(v: ContractPackage.Group): String =
    s"Group(${v.group.map(buildString).getOrElse("No name")}, ${v.urefs.map(buildString).mkString(", ")})"

  def buildString(v: ContractPackage.Version): String =
    s"Version(${v.version.map(buildString).getOrElse(", ")}, ${buildString(v.contractHash)})"

  def buildString(v: Value): String = v.value match {
    case Value.Value.Empty => "ValueEmpty"
    case Value.Value.Account(
        Account(
          pk,
          urefs,
          mainPurse,
          associatedKeys,
          actionThresholds
        )
        ) =>
      s"Account(${buildString(pk)}, {${urefs.map(buildString).mkString(",")}}, ${mainPurse
        .map(buildString)}, {${associatedKeys
        .map(buildString)
        .mkString(",")}, {${actionThresholds.map(buildString)}})"
    case Value.Value.BytesValue(bytes) => s"ByteArray(${buildString(bytes)})"
    case Value.Value.Contract(_)       => "Contract";

    case Value.Value.IntList(IntList(list))       => s"List(${list.mkString(",")})"
    case Value.Value.IntValue(i)                  => s"Int32($i)"
    case Value.Value.NamedKey(nk)                 => buildString(nk)
    case Value.Value.StringList(StringList(list)) => s"List(${list.mkString(",")})"
    case Value.Value.StringValue(s)               => s"String($s)"
    case Value.Value.BigInt(v)                    => s"BigInt(${v.value})"
    case Value.Value.Key(key)                     => buildString(key)
    case Value.Value.LongValue(l)                 => s"Long($l)"
    case Value.Value.Unit(_)                      => "Unit"
  }

  def buildString(b: consensus.Block): String = {
    val blockString = for {
      header     <- b.header
      mainParent <- header.parentHashes.headOption
      postState  <- header.state
    } yield s"Block j-rank #${header.jRank} main-rank #${header.mainRank} (${buildString(b.blockHash)}) " +
      s"-- Sender ID ${buildString(header.validatorPublicKeyHash)} " +
      s"-- M Parent Hash ${buildString(mainParent)} " +
      s"-- Contents ${buildString(postState.postStateHash)}" +
      s"-- Chain Name ${limit(header.chainName, 10)}"
    blockString match {
      case Some(str) => str
      case None      => s"Block with missing elements (${buildString(b.blockHash)})"
    }
  }

  private def buildString(a: Key.URef.AccessRights): String =
    a match {
      case AccessRights.NONE           => "None"
      case AccessRights.READ           => "Read"
      case AccessRights.ADD            => "Add"
      case AccessRights.WRITE          => "Write"
      case AccessRights.ADD_WRITE      => "AddWrite"
      case AccessRights.READ_ADD       => "ReadAdd"
      case AccessRights.READ_WRITE     => "ReadWrite"
      case AccessRights.READ_ADD_WRITE => "ReadAddWrite"
      case AccessRights.Unrecognized(value) =>
        s"Unrecognized AccessRights variant: $value"
    }

  private def buildString(uref: Key.URef): String =
    s"URef(${buildString(uref.uref)}, ${buildString(uref.accessRights)})"

  private def buildString(ak: Account.AssociatedKey): String = {
    val pk     = buildString(ak.publicKey)
    val weight = ak.weight
    s"$pk:$weight"
  }

  private def buildString(at: Account.ActionThresholds): String =
    s"Deployment threshold ${at.deploymentThreshold}, Key management threshold: ${at.keyManagementThreshold}"

  def buildString(d: consensus.Deploy): String =
    s"Deploy ${buildStringNoLimit(d.deployHash)} (account ${buildStringNoLimit(d.getHeader.accountPublicKeyHash)})"
}
