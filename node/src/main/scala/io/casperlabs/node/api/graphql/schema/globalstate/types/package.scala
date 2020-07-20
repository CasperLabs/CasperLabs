package io.casperlabs.node.api.graphql.schema.globalstate

import io.casperlabs.crypto.codec.Base16
import io.casperlabs.models.bytesrepr._
import io.casperlabs.models.cltype
import io.casperlabs.models.cltype.{CLType, CLValueInstance}
import io.casperlabs.node.api.graphql.schema.utils.ProtocolVersionType
import sangria.schema._

package object types {
  // Everything defined as ObjectTypes because
  // sangria doesn't support UnionTypes created from with ScalarTypes.

  lazy val AccessRightsEnum: EnumType[Option[cltype.AccessRights]] =
    EnumType[Option[cltype.AccessRights]](
      "AccessRightsType",
      values = List(
        EnumValue(
          "UNKNOWN",
          value = None
        ),
        EnumValue(
          "READ",
          value = Some(cltype.AccessRights.Read)
        ),
        EnumValue(
          "WRITE",
          value = Some(cltype.AccessRights.Write)
        ),
        EnumValue(
          "ADD",
          value = Some(cltype.AccessRights.Add)
        ),
        EnumValue(
          "READ_ADD",
          value = Some(cltype.AccessRights.ReadAdd)
        ),
        EnumValue(
          "READ_WRITE",
          value = Some(cltype.AccessRights.ReadWrite)
        ),
        EnumValue(
          "ADD_WRITE",
          value = Some(cltype.AccessRights.AddWrite)
        ),
        EnumValue(
          "READ_ADD_WRITE",
          value = Some(cltype.AccessRights.ReadAddWrite)
        )
      )
    )

  lazy val KeyAddress =
    ObjectType(
      "KeyAddress",
      fields[Unit, cltype.Key.Account](
        Field(
          "value",
          StringType,
          resolve = c => Base16.encode(c.value.address.bytes.toArray)
        )
      )
    )

  lazy val KeyHash = ObjectType(
    "KeyHash",
    fields[Unit, cltype.Key.Hash](
      Field(
        "value",
        StringType,
        resolve = c => Base16.encode(c.value.address.bytes.toArray)
      )
    )
  )

  lazy val URef = ObjectType(
    "URef",
    fields[Unit, CLValueInstance.URef](
      Field(
        "address",
        StringType,
        resolve = c => Base16.encode(c.value.value.address.bytes.toArray)
      ),
      Field(
        "accessRights",
        AccessRightsEnum,
        resolve = _.value.value.accessRights
      )
    )
  )

  lazy val KeyURef = ObjectType(
    "KeyURef",
    fields[Unit, cltype.Key.URef](
      Field("uref", URef, resolve = c => CLValueInstance.URef(c.value.uref))
    )
  )

  lazy val KeyUnion = UnionType(
    "KeyUnion",
    types = List(
      KeyAddress,
      KeyHash,
      KeyURef
    )
  )

  lazy val KeyType = ObjectType(
    "Key",
    fields[Unit, CLValueInstance.Key](
      Field(
        "value",
        KeyUnion,
        resolve = _.value.value match {
          case value: cltype.Key.Hash    => value
          case value: cltype.Key.Account => value
          case value: cltype.Key.URef    => value
        }
      )
    )
  )

  lazy val NamedKey = ObjectType(
    "NamedKey",
    fields[Unit, (String, cltype.Key)](
      Field("name", StringType, resolve = _.value._1),
      Field("key", KeyType, resolve = c => CLValueInstance.Key(c.value._2))
    )
  )

  lazy val Parameter = ObjectType(
    "Parameter",
    fields[Unit, (String, cltype.CLType)](
      Field("name", StringType, resolve = _.value._1),
      Field("cl_type", StringType, resolve = _.value._2.toString())
    )
  )

  lazy val AccessPublic = ObjectType(
    "AccessPublic",
    fields[Unit, cltype.EntryPointAccess.Public](
      Field("value", StringType, resolve = _ => "Public")
    )
  )

  lazy val AccessGroups = ObjectType(
    "AccessGroups",
    fields[Unit, cltype.EntryPointAccess.Groups](
      Field("labels", ListType(StringType), resolve = _.value.labels.toList)
    )
  )

  lazy val AccessUnion: UnionType[Unit] = UnionType(
    "EntryPointAccessUnion",
    types = List(
      AccessPublic,
      AccessGroups
    )
  )
  lazy val EntryPoint = ObjectType(
    "EntryPoint",
    fields[Unit, cltype.EntryPoint](
      Field("name", StringType, resolve = _.value.name),
      Field("parameters", ListType(Parameter), resolve = _.value.parameters.toList),
      Field("ret", StringType, resolve = _.value.ret.toString()),
      Field("access", AccessUnion, resolve = _.value.access),
      Field("entryPointType", StringType, resolve = _.value.entryPointType.toString())
    )
  )

  lazy val Contract = ObjectType(
    "Contract",
    fields[Unit, cltype.Contract](
      Field(
        "contractPackageHash",
        StringType,
        resolve = c => Base16.encode(c.value.contractPackageHash.bytes.toArray)
      ),
      Field(
        "contractWasmHash",
        StringType,
        resolve = c => Base16.encode(c.value.contractWasmHash.bytes.toArray)
      ),
      Field("namedKeys", ListType(NamedKey), resolve = _.value.namedKeys.toList),
      Field("entryPoints", ListType(EntryPoint), resolve = _.value.entryPoints.map {
        case (k @ _, v) => v
      }.toList),
      Field(
        "protocolVersion",
        ProtocolVersionType,
        resolve = c => cltype.protobuf.Mappings.toProto(c.value.protocolVersion)
      )
    )
  )

  lazy val ContractVersionKey = ObjectType(
    "ContractVersionKey",
    fields[Unit, Tuple2[Int, Int]](
      Field("protocolMajorVersion", IntType, resolve = _.value._1),
      Field("contractVersion", IntType, resolve = _.value._2)
    )
  )

  lazy val ActiveVersion = ObjectType(
    "ActiveVersion",
    fields[Unit, (Tuple2[Int, Int], cltype.ByteArray32)](
      Field("key", ContractVersionKey, resolve = _.value._1),
      Field("value", StringType, resolve = c => Base16.encode(c.value._2.bytes.toArray))
    )
  )

  lazy val Group = ObjectType(
    "Group",
    fields[Unit, (String, Set[cltype.URef])](
      Field("key", StringType, resolve = _.value._1),
      Field(
        "value",
        ListType(KeyURef),
        resolve = c =>
          c.value._2.map { x =>
            cltype.Key.URef(x)
          }.toList
      )
    )
  )

  lazy val ContractPackage = ObjectType(
    "ContractPackage",
    fields[Unit, cltype.ContractPackage](
      Field("mainPurse", KeyURef, resolve = c => cltype.Key.URef(c.value.accessURef)),
      Field(
        "versions",
        ListType(ActiveVersion),
        resolve = _.value.versions.toList
      ),
      Field(
        "disabledVersions",
        ListType(ContractVersionKey),
        resolve = _.value.disabledVersions.toList
      ),
      Field("groups", ListType(Group), resolve = _.value.groups.toList)
    )
  )

  lazy val ContractWasm = ObjectType(
    "ContractWasm",
    fields[Unit, cltype.ContractWasm](
      Field("body", StringType, resolve = c => Base16.encode(c.value.bytes.toArray))
    )
  )

  lazy val AccountAssociatedKey = ObjectType(
    "AccountAssociatedKey",
    fields[Unit, (cltype.Account.PublicKey, cltype.Account.Weight)](
      Field(
        "pubKey",
        StringType,
        resolve = c => Base16.encode(c.value._1.bytes.toArray)
      ),
      Field("weight", IntType, resolve = _.value._2.toInt)
    )
  )

  lazy val AccountActionThresholds = ObjectType(
    "AccountActionThresholds",
    fields[Unit, cltype.Account.ActionThresholds](
      Field("deploymentThreshold", IntType, resolve = _.value.deployment.toInt),
      Field("keyManagementThreshold", IntType, resolve = _.value.keyManagement.toInt)
    )
  )

  lazy val Account = ObjectType(
    "Account",
    fields[Unit, cltype.Account](
      Field("pubKey", StringType, resolve = c => Base16.encode(c.value.publicKey.bytes.toArray)),
      Field("mainPurse", KeyURef, resolve = c => cltype.Key.URef(c.value.mainPurse)),
      Field("namedKeys", ListType(NamedKey), resolve = _.value.namedKeys.toList),
      Field(
        "associatedKeys",
        ListType(AccountAssociatedKey),
        resolve = _.value.associatedKeys.toList
      ),
      Field("actionThreshold", AccountActionThresholds, resolve = _.value.actionThresholds)
    )
  )

  lazy val Bool = ObjectType(
    "Bool",
    fields[Unit, CLValueInstance.Bool](
      Field("value", BooleanType, resolve = _.value.value)
    )
  )

  lazy val I32 = ObjectType(
    "I32",
    fields[Unit, CLValueInstance.I32](
      Field("value", IntType, resolve = _.value.value)
    )
  )

  lazy val I64 = ObjectType(
    "I64",
    fields[Unit, CLValueInstance.I64](
      Field("value", LongType, resolve = _.value.value)
    )
  )

  lazy val U8 = ObjectType(
    "U8",
    fields[Unit, CLValueInstance.U8](
      Field("value", IntType, resolve = _.value.value.toInt)
    )
  )

  lazy val U32 = ObjectType(
    "U32",
    fields[Unit, CLValueInstance.U32](
      Field("value", IntType, resolve = _.value.value)
    )
  )

  lazy val U64 = ObjectType(
    "U64",
    fields[Unit, CLValueInstance.U64](
      Field("value", LongType, resolve = _.value.value)
    )
  )

  lazy val U128 = ObjectType(
    "U128",
    fields[Unit, CLValueInstance.U128](
      Field("value", BigIntType, resolve = _.value.value.value)
    )
  )

  lazy val U256 = ObjectType(
    "U256",
    fields[Unit, CLValueInstance.U256](
      Field("value", BigIntType, resolve = _.value.value.value)
    )
  )

  lazy val U512 = ObjectType(
    "U512",
    fields[Unit, CLValueInstance.U512](
      Field("value", BigIntType, resolve = _.value.value.value)
    )
  )

  lazy val UnitType = ObjectType(
    "Unit",
    fields[Unit, CLValueInstance.Unit.type](
      Field("value", StringType, resolve = _ => "Unit")
    )
  )

  lazy val CLString = ObjectType(
    "CLString",
    fields[Unit, CLValueInstance.String](
      Field("value", StringType, resolve = _.value.value)
    )
  )

  lazy val CLOption: ObjectType[Unit, CLValueInstance.Option] = ObjectType(
    "Option",
    () =>
      fields[Unit, CLValueInstance.Option](
        Field("value", OptionType(CLValueUnion), resolve = _.value.value)
      )
  )

  lazy val CLList: ObjectType[Unit, CLValueInstance.List] = ObjectType(
    "List",
    () =>
      fields[Unit, CLValueInstance.List](
        Field("value", ListType(CLValueUnion), resolve = _.value.value)
      )
  )

  lazy val FixedList = ObjectType(
    "FixedList",
    () =>
      fields[Unit, CLValueInstance.FixedList](
        Field("value", ListType(CLValueUnion), resolve = _.value.value),
        Field("length", IntType, resolve = _.value.length)
      )
  )

  lazy val ResultUnion = UnionType(
    "Either",
    types = List(
      ObjectType(
        "ResultError",
        () =>
          fields[Unit, Left[CLValueInstance, CLValueInstance]](
            Field("value", CLValueUnion, resolve = _.value.value)
          )
      ),
      ObjectType(
        "ResultOk",
        () =>
          fields[Unit, Right[CLValueInstance, CLValueInstance]](
            Field("value", CLValueUnion, resolve = _.value.value)
          )
      )
    )
  )

  lazy val Result = ObjectType(
    "Result",
    () =>
      fields[Unit, CLValueInstance.Result](
        Field("value", ResultUnion, resolve = _.value.value match {
          case err: Left[CLValueInstance, CLValueInstance] => err
          case ok: Right[CLValueInstance, CLValueInstance] => ok
        })
      )
  )

  lazy val CLMap = ObjectType(
    "Map",
    () =>
      fields[Unit, CLValueInstance.Map](
        Field("value", ListType(Tuple2), resolve = _.value.value.toSeq.map {
          case (key, value) => CLValueInstance.Tuple2(key, value)
        })
      )
  )

  lazy val Tuple1 = ObjectType(
    "Tuple1",
    () =>
      fields[Unit, CLValueInstance.Tuple1](
        Field("_1", CLValueUnion, resolve = _.value._1)
      )
  )

  lazy val Tuple2 = ObjectType(
    "Tuple2",
    () =>
      fields[Unit, CLValueInstance.Tuple2](
        Field("_1", CLValueUnion, resolve = _.value._1),
        Field("_2", CLValueUnion, resolve = _.value._2)
      )
  )

  lazy val Tuple3 = ObjectType(
    "Tuple3",
    () =>
      fields[Unit, CLValueInstance.Tuple3](
        Field("_1", CLValueUnion, resolve = _.value._1),
        Field("_2", CLValueUnion, resolve = _.value._2),
        Field("_3", CLValueUnion, resolve = _.value._3)
      )
  )

  lazy val CLValueUnion: UnionType[Unit] = UnionType(
    "CLValueUnion",
    types = List(
      Bool,
      I32,
      I64,
      U8,
      U32,
      U64,
      U128,
      U256,
      U512,
      UnitType,
      CLString,
      KeyType,
      URef,
      CLOption,
      CLList,
      FixedList,
      Result,
      CLMap,
      Tuple1,
      Tuple2,
      Tuple3
    )
  )

  lazy val StoredValueUnion: UnionType[Unit] = UnionType(
    "StoredValueUnion",
    types = List(
      Account,
      Contract,
      ContractPackage,
      ContractWasm,
      Bool,
      I32,
      I64,
      U8,
      U32,
      U64,
      U128,
      U256,
      U512,
      UnitType,
      CLString,
      KeyType,
      URef,
      CLOption,
      CLList,
      FixedList,
      Result,
      CLMap,
      Tuple1,
      Tuple2,
      Tuple3
    )
  )

  lazy val StoredValue = ObjectType(
    "StoredValue",
    fields[Unit, cltype.StoredValueInstance](
      Field(
        "value",
        StoredValueUnion,
        resolve = _.value match {
          case cltype.StoredValueInstance.Contract(value)        => value
          case cltype.StoredValueInstance.ContractWasm(value)    => value
          case cltype.StoredValueInstance.ContractPackage(value) => value
          case cltype.StoredValueInstance.Account(value)         => value
          case cltype.StoredValueInstance.CLValue(value) =>
            value match {
              case v: CLValueInstance.Bool      => v
              case v: CLValueInstance.I32       => v
              case v: CLValueInstance.I64       => v
              case v: CLValueInstance.U8        => v
              case v: CLValueInstance.U32       => v
              case v: CLValueInstance.U64       => v
              case v: CLValueInstance.U128      => v
              case v: CLValueInstance.U256      => v
              case v: CLValueInstance.U512      => v
              case CLValueInstance.Unit         => CLValueInstance.Unit
              case v: CLValueInstance.String    => v
              case v: CLValueInstance.Key       => v
              case v: CLValueInstance.URef      => v
              case v: CLValueInstance.Option    => v
              case v: CLValueInstance.List      => v
              case v: CLValueInstance.FixedList => v
              case v: CLValueInstance.Result    => v
              case v: CLValueInstance.Map       => v
              case v: CLValueInstance.Tuple1    => v
              case v: CLValueInstance.Tuple2    => v
              case v: CLValueInstance.Tuple3    => v
            }
        }
      )
    )
  )
}
