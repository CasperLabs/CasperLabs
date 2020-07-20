package io.casperlabs.models.cltype.protobuf

import cats.implicits._
import cats.arrow.FunctionK
import cats.free.{Free, Trampoline}
import com.google.protobuf.ByteString
import eu.timepit.refined._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric._
import io.casperlabs.casper.consensus.state
import io.casperlabs.casper.consensus.Deploy
import io.casperlabs.models.bytesrepr
import io.casperlabs.models.bytesrepr.{FromBytes, ToBytes}
import io.casperlabs.models.cltype._
import scala.util.{Failure, Success, Try}

object Mappings {
  def toProto(v: StoredValue): Either[Error, state.StoredValueInstance] = v match {
    case StoredValue.CLValue(value) =>
      toProto(value).map { instance =>
        state.StoredValueInstance(state.StoredValueInstance.Value.ClValue(instance))
      }

    case StoredValue.Account(account) =>
      Right(state.StoredValueInstance(state.StoredValueInstance.Value.Account(toProto(account))))

    case StoredValue.ContractWasm(contractWasm) =>
      Right(
        state
          .StoredValueInstance(state.StoredValueInstance.Value.ContractWasm(toProto(contractWasm)))
      )

    case StoredValue.Contract(contract) =>
      Right(state.StoredValueInstance(state.StoredValueInstance.Value.Contract(toProto(contract))))

    case StoredValue.ContractPackage(contractPackage) =>
      Right(
        state.StoredValueInstance(
          state.StoredValueInstance.Value.ContractPackage(toProto(contractPackage))
        )
      )
  }

  def toProto(entryPoint: EntryPoint): state.Contract.EntryPoint = state.Contract.EntryPoint(
    name = entryPoint.name,
    args = entryPoint.parameters.map {
      case (k, v) =>
        state.Contract.EntryPoint.Arg(
          name = k,
          clType = Some(toProto(v))
        )
    }.toSeq,
    ret = Some(toProto(entryPoint.ret)),
    access = entryPoint.access match {
      case EntryPointAccess.Public() =>
        state.Contract.EntryPoint.Access.Public(state.Contract.EntryPoint.Public())
      case EntryPointAccess.Groups(groups) =>
        state.Contract.EntryPoint.Access.Groups(
          state.Contract.EntryPoint.Groups(
            groups = groups.map { g =>
              state.Contract.EntryPoint.Group(name = g)
            }.toSeq
          )
        )
    }
  )

  def toProto(c: ContractWasm): state.ContractWasm = state.ContractWasm(
    wasm = ByteString.copyFrom(c.bytes.toArray)
  )

  def toProto(c: Contract): state.Contract = state.Contract(
    contractPackageHash = ByteString.copyFrom(c.contractPackageHash.bytes.toArray),
    contractWasmHash = ByteString.copyFrom(c.contractWasmHash.bytes.toArray),
    namedKeys = toProto(c.namedKeys),
    entryPoints = c.entryPoints.map {
      case (k @ _, v) => toProto(v)
    }.toSeq,
    protocolVersion = Some(toProto(c.protocolVersion))
  )

  def toProto(c: ContractPackage): state.ContractPackage = state.ContractPackage(
    accessKey = Some(toProto(c.accessURef)),
    activeVersions = c.versions.map {
      case (k, v) =>
        state.ContractPackage.Version(
          version = Some(
            state.ContractVersionKey(
              protocolVersionMajor = k._1,
              contractVersion = k._2
            )
          ),
          contractHash = ByteString.copyFrom(v.bytes.toArray)
        )
    }.toSeq,
    disabledVersions = c.disabledVersions.map { v =>
      state.ContractVersionKey(
        protocolVersionMajor = v._1,
        contractVersion = v._2
      )
    }.toSeq,
    groups = c.groups.map {
      case (k, v) =>
        state.ContractPackage.Group(
          group = Some(
            state.Contract.EntryPoint.Group(
              name = k
            )
          ),
          urefs = v.map { uref =>
            toProto(uref)
          }.toSeq
        )
    }.toSeq
  )

  def toProto(a: Account): state.Account = state.Account(
    publicKey = ByteString.copyFrom(a.publicKey.bytes.toArray),
    mainPurse = toProto(Key.URef(a.mainPurse)).value.uref,
    namedKeys = toProto(a.namedKeys),
    associatedKeys = a.associatedKeys.toSeq.map {
      case (k, w) => state.Account.AssociatedKey(ByteString.copyFrom(k.bytes.toArray), w.toInt)
    },
    actionThresholds = Some(
      state.Account
        .ActionThresholds(
          deploymentThreshold = a.actionThresholds.deployment.toInt,
          keyManagementThreshold = a.actionThresholds.keyManagement.toInt
        )
    )
  )

  def toProto(namedKeys: Map[String, Key]): Seq[state.NamedKey] = namedKeys.toSeq.map {
    case (n, k) => state.NamedKey(name = n, key = Some(toProto(k)))
  }

  def toProto(rights: AccessRights): state.Key.URef.AccessRights = rights match {
    case AccessRights.None         => state.Key.URef.AccessRights.NONE
    case AccessRights.Read         => state.Key.URef.AccessRights.READ
    case AccessRights.Write        => state.Key.URef.AccessRights.WRITE
    case AccessRights.ReadWrite    => state.Key.URef.AccessRights.READ_WRITE
    case AccessRights.Add          => state.Key.URef.AccessRights.ADD
    case AccessRights.ReadAdd      => state.Key.URef.AccessRights.READ_ADD
    case AccessRights.AddWrite     => state.Key.URef.AccessRights.ADD_WRITE
    case AccessRights.ReadAddWrite => state.Key.URef.AccessRights.READ_ADD_WRITE
  }

  def toProto(uref: URef): state.Key.URef = state.Key.URef(
    uref = ByteString.copyFrom(uref.address.bytes.toArray),
    accessRights = toProto(uref.accessRights)
  )

  def toProto(k: Key): state.Key = k match {
    case Key.Account(address) =>
      state.Key(
        state.Key.Value.Address(state.Key.Address(ByteString.copyFrom(address.bytes.toArray)))
      )

    case Key.Hash(address) =>
      state.Key(
        state.Key.Value.Hash(state.Key.Hash(ByteString.copyFrom(address.bytes.toArray)))
      )

    case Key.URef(uref) =>
      state.Key(
        state.Key.Value.Uref(toProto(uref))
      )

  }

  def toProto(version: SemVer): state.ProtocolVersion = state.ProtocolVersion(
    major = version.major,
    minor = version.minor,
    patch = version.patch
  )

  def toProto(v: CLValue): Either[Error, state.CLValueInstance] =
    CLValueInstance.from(v).map(toProto).leftMap(Error.FromBytesError.apply)

  def toProto(t: CLType): state.CLType = toProtoLoop(t).run

  private def toProtoLoop(t: CLType): Trampoline[state.CLType] = t match {
    case CLType.Bool   => Trampoline.done(dsl.types.bool)
    case CLType.I32    => Trampoline.done(dsl.types.i32)
    case CLType.I64    => Trampoline.done(dsl.types.i64)
    case CLType.U8     => Trampoline.done(dsl.types.u8)
    case CLType.U32    => Trampoline.done(dsl.types.u32)
    case CLType.U64    => Trampoline.done(dsl.types.u64)
    case CLType.U128   => Trampoline.done(dsl.types.u128)
    case CLType.U256   => Trampoline.done(dsl.types.u256)
    case CLType.U512   => Trampoline.done(dsl.types.u512)
    case CLType.Unit   => Trampoline.done(dsl.types.unit)
    case CLType.String => Trampoline.done(dsl.types.string)
    case CLType.Key    => Trampoline.done(dsl.types.key)
    case CLType.URef   => Trampoline.done(dsl.types.uref)

    case CLType.Option(inner) =>
      Trampoline.defer(toProtoLoop(inner)).map(dsl.types.option)

    case CLType.List(inner) =>
      Trampoline.defer(toProtoLoop(inner)).map(dsl.types.list)

    case CLType.FixedList(inner, length) =>
      Trampoline.defer(toProtoLoop(inner)).map(dsl.types.fixedList(_, length))

    case CLType.Result(ok, err) =>
      for {
        okProto  <- Trampoline.defer(toProtoLoop(ok))
        errProto <- toProtoLoop(err)
      } yield dsl.types.result(okProto, errProto)

    case CLType.Map(key, value) =>
      for {
        keyProto   <- Trampoline.defer(toProtoLoop(key))
        valueProto <- toProtoLoop(value)
      } yield dsl.types.map(keyProto, valueProto)

    case CLType.Tuple1(inner) =>
      Trampoline.defer(toProtoLoop(inner)).map(dsl.types.tuple1)

    case CLType.Tuple2(t1, t2) =>
      for {
        t1Proto <- Trampoline.defer(toProtoLoop(t1))
        t2Proto <- toProtoLoop(t2)
      } yield dsl.types.tuple2(t1Proto, t2Proto)

    case CLType.Tuple3(t1, t2, t3) =>
      for {
        t1Proto <- Trampoline.defer(toProtoLoop(t1))
        t2Proto <- toProtoLoop(t2)
        t3Proto <- toProtoLoop(t3)
      } yield dsl.types.tuple3(t1Proto, t2Proto, t3Proto)

    case CLType.Any => Trampoline.done(dsl.types.any)
  }

  def toProto(v: CLValueInstance): state.CLValueInstance =
    state.CLValueInstance(
      clType = toProto(v.clType).some,
      value = toProtoValue(v).some
    )

  private def toProtoValue(v: CLValueInstance): state.CLValueInstance.Value =
    toProtoValueLoop(v).run

  private def toProtoValueLoop(v: CLValueInstance): Trampoline[state.CLValueInstance.Value] =
    v match {
      case CLValueInstance.Bool(b)   => Trampoline.done(dsl.values.bool(b))
      case CLValueInstance.I32(i)    => Trampoline.done(dsl.values.i32(i))
      case CLValueInstance.I64(i)    => Trampoline.done(dsl.values.i64(i))
      case CLValueInstance.U8(i)     => Trampoline.done(dsl.values.u8(i))
      case CLValueInstance.U32(i)    => Trampoline.done(dsl.values.u32(i))
      case CLValueInstance.U64(i)    => Trampoline.done(dsl.values.u64(i))
      case CLValueInstance.U128(i)   => Trampoline.done(dsl.values.u128(i.value))
      case CLValueInstance.U256(i)   => Trampoline.done(dsl.values.u256(i.value))
      case CLValueInstance.U512(i)   => Trampoline.done(dsl.values.u512(i.value))
      case CLValueInstance.Unit      => Trampoline.done(dsl.values.unit)
      case CLValueInstance.String(s) => Trampoline.done(dsl.values.string(s))
      case CLValueInstance.Key(k)    => Trampoline.done(dsl.values.key(toProto(k)))
      case CLValueInstance.URef(u)   => Trampoline.done(dsl.values.uref(toProto(u)))

      case CLValueInstance.Option(value, _) =>
        value.traverse(v => Trampoline.defer(toProtoValueLoop(v))).map(dsl.values.option)

      case CLValueInstance.List(values, CLType.U8) =>
        val bytes: Seq[Byte] = values.map {
          case CLValueInstance.U8(b) => b
          case _                     => 0.toByte
        }
        Trampoline.done(dsl.values.bytes(bytes))

      case CLValueInstance.List(values, _) =>
        values.toList.traverse(v => Trampoline.defer(toProtoValueLoop(v))).map(dsl.values.list)

      case CLValueInstance.FixedList(values, CLType.U8, _) =>
        val bytes: Seq[Byte] = values.map {
          case CLValueInstance.U8(b) => b
          case _                     => 0.toByte
        }
        Trampoline.done(dsl.values.bytes(bytes))

      case CLValueInstance.FixedList(values, _, _) =>
        values.toList.traverse(v => Trampoline.defer(toProtoValueLoop(v))).map(dsl.values.fixedList)

      case CLValueInstance.Result(value, _, _) =>
        value
          .bitraverse(
            v => Trampoline.defer(toProtoValueLoop(v)),
            v => Trampoline.defer(toProtoValueLoop(v))
          )
          .map(dsl.values.result)

      case CLValueInstance.Map(values, _, _) =>
        values.toList
          .traverse {
            case (key, value) =>
              for {
                keyProto   <- Trampoline.defer(toProtoValueLoop(key))
                valueProto <- toProtoValueLoop(value)
              } yield state.CLValueInstance.MapEntry(key = keyProto.some, value = valueProto.some)
          }
          .map(dsl.values.map)

      case CLValueInstance.Tuple1(value) =>
        Trampoline.defer(toProtoValueLoop(value)).map(dsl.values.tuple1)

      case CLValueInstance.Tuple2(value1, value2) =>
        for {
          v1 <- Trampoline.defer(toProtoValueLoop(value1))
          v2 <- toProtoValueLoop(value2)
        } yield dsl.values.tuple2(v1, v2)

      case CLValueInstance.Tuple3(value1, value2, value3) =>
        for {
          v1 <- Trampoline.defer(toProtoValueLoop(value1))
          v2 <- toProtoValueLoop(value2)
          v3 <- toProtoValueLoop(value3)
        } yield dsl.values.tuple3(v1, v2, v3)
    }

  def fromProto(rights: state.Key.URef.AccessRights): Either[Error, AccessRights] =
    rights match {
      case state.Key.URef.AccessRights.NONE            => Right(AccessRights.None)
      case state.Key.URef.AccessRights.READ            => Right(AccessRights.Read)
      case state.Key.URef.AccessRights.WRITE           => Right(AccessRights.Write)
      case state.Key.URef.AccessRights.READ_WRITE      => Right(AccessRights.ReadWrite)
      case state.Key.URef.AccessRights.ADD             => Right(AccessRights.Add)
      case state.Key.URef.AccessRights.READ_ADD        => Right(AccessRights.ReadAdd)
      case state.Key.URef.AccessRights.ADD_WRITE       => Right(AccessRights.AddWrite)
      case state.Key.URef.AccessRights.READ_ADD_WRITE  => Right(AccessRights.ReadAddWrite)
      case state.Key.URef.AccessRights.Unrecognized(i) => Left(Error.UnrecognizedAccessRights(i))
    }

  def fromProto(uref: state.Key.URef): Either[Error, URef] =
    for {
      address <- toByteArray32(uref.uref)
      rights  <- fromProto(uref.accessRights)
    } yield URef(address, rights)

  def fromProto(k: state.Key): Either[Error, Key] = k.value match {
    case state.Key.Value.Empty => Left(Error.EmptyKeyVariant)

    case state.Key.Value.Address(state.Key.Address(address)) =>
      toByteArray32(address).map(Key.Account.apply)

    case state.Key.Value.Hash(state.Key.Hash(address)) =>
      toByteArray32(address).map(Key.Hash.apply)

    case state.Key.Value.Uref(uref) => fromProto(uref).map(Key.URef.apply)
  }

  def fromProto(proto: state.CLType): Either[Error, CLType] =
    fromProtoLoop(proto).foldMap(interpreter)

  private def fromProtoLoop(proto: state.CLType): FE[CLType] = proto match {
    case state.CLType(state.CLType.Variants.Empty) => raise(Error.EmptyTypeVariant)
    case state.CLType(state.CLType.Variants.SimpleType(state.CLType.Simple.Unrecognized(i))) =>
      raise(Error.UnrecognizedSimpleType(i))

    case state.CLType(state.CLType.Variants.SimpleType(state.CLType.Simple.BOOL)) =>
      pure(CLType.Bool)
    case state.CLType(state.CLType.Variants.SimpleType(state.CLType.Simple.I32)) =>
      pure(CLType.I32)
    case state.CLType(state.CLType.Variants.SimpleType(state.CLType.Simple.I64)) =>
      pure(CLType.I64)
    case state.CLType(state.CLType.Variants.SimpleType(state.CLType.Simple.U8)) => pure(CLType.U8)
    case state.CLType(state.CLType.Variants.SimpleType(state.CLType.Simple.U32)) =>
      pure(CLType.U32)
    case state.CLType(state.CLType.Variants.SimpleType(state.CLType.Simple.U64)) =>
      pure(CLType.U64)
    case state.CLType(state.CLType.Variants.SimpleType(state.CLType.Simple.U128)) =>
      pure(CLType.U128)
    case state.CLType(state.CLType.Variants.SimpleType(state.CLType.Simple.U256)) =>
      pure(CLType.U256)
    case state.CLType(state.CLType.Variants.SimpleType(state.CLType.Simple.U512)) =>
      pure(CLType.U512)
    case state.CLType(state.CLType.Variants.SimpleType(state.CLType.Simple.UNIT)) =>
      pure(CLType.Unit)
    case state.CLType(state.CLType.Variants.SimpleType(state.CLType.Simple.STRING)) =>
      pure(CLType.String)
    case state.CLType(state.CLType.Variants.SimpleType(state.CLType.Simple.KEY)) =>
      pure(CLType.Key)
    case state.CLType(state.CLType.Variants.SimpleType(state.CLType.Simple.UREF)) =>
      pure(CLType.URef)

    case state.CLType(state.CLType.Variants.OptionType(state.CLType.OptionProto(innerProto))) =>
      innerProto match {
        case None    => raise(Error.MissingType)
        case Some(t) => defer(fromProtoLoop(t)).map(CLType.Option.apply)
      }

    case state.CLType(state.CLType.Variants.ListType(state.CLType.List(innerProto))) =>
      innerProto match {
        case None    => raise(Error.MissingType)
        case Some(t) => defer(fromProtoLoop(t)).map(CLType.List.apply)
      }

    case state.CLType(
        state.CLType.Variants.FixedListType(state.CLType.FixedList(innerProto, length))
        ) =>
      innerProto match {
        case None    => raise(Error.MissingType)
        case Some(t) => defer(fromProtoLoop(t)).map(CLType.FixedList(_, length))
      }

    case state.CLType(state.CLType.Variants.ResultType(state.CLType.Result(okProto, errProto))) =>
      for {
        ok  <- okProto.map(t => defer(fromProtoLoop(t))).getOrElse(raise(Error.MissingType))
        err <- errProto.map(t => defer(fromProtoLoop(t))).getOrElse(raise(Error.MissingType))
      } yield CLType.Result(ok, err)

    case state.CLType(state.CLType.Variants.MapType(state.CLType.Map(keyProto, valueProto))) =>
      for {
        key   <- keyProto.map(t => defer(fromProtoLoop(t))).getOrElse(raise(Error.MissingType))
        value <- valueProto.map(t => defer(fromProtoLoop(t))).getOrElse(raise(Error.MissingType))
      } yield CLType.Map(key, value)

    case state.CLType(state.CLType.Variants.Tuple1Type(state.CLType.Tuple1(innerProto))) =>
      innerProto match {
        case None    => raise(Error.MissingType)
        case Some(t) => defer(fromProtoLoop(t)).map(CLType.Tuple1.apply)
      }

    case state.CLType(state.CLType.Variants.Tuple2Type(state.CLType.Tuple2(t1Proto, t2Proto))) =>
      for {
        t1 <- t1Proto.map(t => defer(fromProtoLoop(t))).getOrElse(raise(Error.MissingType))
        t2 <- t2Proto.map(t => defer(fromProtoLoop(t))).getOrElse(raise(Error.MissingType))
      } yield CLType.Tuple2(t1, t2)

    case state.CLType(
        state.CLType.Variants.Tuple3Type(state.CLType.Tuple3(t1Proto, t2Proto, t3Proto))
        ) =>
      for {
        t1 <- t1Proto.map(t => defer(fromProtoLoop(t))).getOrElse(raise(Error.MissingType))
        t2 <- t2Proto.map(t => defer(fromProtoLoop(t))).getOrElse(raise(Error.MissingType))
        t3 <- t3Proto.map(t => defer(fromProtoLoop(t))).getOrElse(raise(Error.MissingType))
      } yield CLType.Tuple3(t1, t2, t3)

    case state.CLType(state.CLType.Variants.AnyType(state.CLType.Any())) => pure(CLType.Any)
  }

  def fromProto(
      value: state.CLValueInstance.Value,
      clType: CLType
  ): Either[Error, CLValueInstance] = fromProtoLoop(value, clType).foldMap(interpreter)

  private def fromProtoLoop(
      value: state.CLValueInstance.Value,
      clType: CLType
  ): FE[CLValueInstance] =
    value.value match {
      case state.CLValueInstance.Value.Value.Empty        => raise(Error.EmptyInstanceVariant)
      case state.CLValueInstance.Value.Value.BoolValue(b) => pure(CLValueInstance.Bool(b))
      case state.CLValueInstance.Value.Value.I32(i)       => pure(CLValueInstance.I32(i))
      case state.CLValueInstance.Value.Value.I64(i)       => pure(CLValueInstance.I64(i))
      case state.CLValueInstance.Value.Value.U8(i)        => pure(CLValueInstance.U8(i.toByte))
      case state.CLValueInstance.Value.Value.U32(i)       => pure(CLValueInstance.U32(i))
      case state.CLValueInstance.Value.Value.U64(i)       => pure(CLValueInstance.U64(i))

      case state.CLValueInstance.Value.Value.U128(i) =>
        lift(validateBigInt(i.value).map(CLValueInstance.U128.apply))
      case state.CLValueInstance.Value.Value.U256(i) =>
        lift(validateBigInt(i.value).map(CLValueInstance.U256.apply))
      case state.CLValueInstance.Value.Value.U512(i) =>
        lift(validateBigInt(i.value).map(CLValueInstance.U512.apply))

      case state.CLValueInstance.Value.Value.Unit(_)     => pure(CLValueInstance.Unit)
      case state.CLValueInstance.Value.Value.StrValue(s) => pure(CLValueInstance.String(s))
      case state.CLValueInstance.Value.Value.Key(k) =>
        lift(fromProto(k).map(CLValueInstance.Key.apply))
      case state.CLValueInstance.Value.Value.Uref(u) =>
        lift(fromProto(u).map(CLValueInstance.URef.apply))

      case state.CLValueInstance.Value.Value.BytesValue(bytes) =>
        val u8Instances = bytes.toByteArray.map(CLValueInstance.U8.apply)
        clType match {
          case CLType.List(CLType.U8) =>
            lift(CLValueInstance.List(u8Instances, CLType.U8).leftMap(Error.InstanceError.apply))

          case CLType.FixedList(CLType.U8, length) =>
            lift(
              CLValueInstance
                .FixedList(u8Instances, CLType.U8, length)
                .leftMap(Error.InstanceError.apply)
            )

          case other => raise(Error.TypeMismatch(other, "List(U8) or FixedList(U8)"))
        }

      case state.CLValueInstance.Value.Value
            .OptionValue(state.CLValueInstance.OptionProto(innerProto)) =>
        clType match {
          case CLType.Option(innerType) =>
            innerProto.traverse(v => defer(fromProtoLoop(v, innerType))).flatMap { innerValue =>
              lift(CLValueInstance.Option(innerValue, innerType).leftMap(Error.InstanceError.apply))
            }

          case other => raise(Error.TypeMismatch(other, "Option"))
        }

      case state.CLValueInstance.Value.Value.ListValue(state.CLValueInstance.List(innerProto)) =>
        clType match {
          case CLType.List(innerType) =>
            innerProto.toList.traverse(v => defer(fromProtoLoop(v, innerType))).flatMap {
              innerValue =>
                lift(CLValueInstance.List(innerValue, innerType).leftMap(Error.InstanceError.apply))
            }

          case other => raise(Error.TypeMismatch(other, "List"))
        }

      case state.CLValueInstance.Value.Value
            .FixedListValue(state.CLValueInstance.FixedList(length, innerProto)) =>
        clType match {
          case CLType.FixedList(innerType, l) if l == length =>
            innerProto.toList.traverse(v => defer(fromProtoLoop(v, innerType))).flatMap {
              innerValue =>
                lift(
                  CLValueInstance
                    .FixedList(innerValue, innerType, length)
                    .leftMap(Error.InstanceError.apply)
                )
            }

          case other => raise(Error.TypeMismatch(other, s"FixedList(length == $length)"))
        }

      case state.CLValueInstance.Value.Value
            .ResultValue(state.CLValueInstance.Result(innerProto)) =>
        clType match {
          case CLType.Result(okType, errType) =>
            innerProto match {
              case state.CLValueInstance.Result.Value.Empty => raise(Error.EmptyResultVariant)

              case state.CLValueInstance.Result.Value.Err(e) =>
                defer(fromProtoLoop(e, errType)).flatMap { innerValue =>
                  lift(
                    CLValueInstance
                      .Result(Left(innerValue), okType, errType)
                      .leftMap(Error.InstanceError.apply)
                  )
                }

              case state.CLValueInstance.Result.Value.Ok(k) =>
                defer(fromProtoLoop(k, okType)).flatMap { innerValue =>
                  lift(
                    CLValueInstance
                      .Result(Right(innerValue), okType, errType)
                      .leftMap(Error.InstanceError.apply)
                  )
                }
            }

          case other => raise(Error.TypeMismatch(other, "Result"))
        }

      case state.CLValueInstance.Value.Value.MapValue(state.CLValueInstance.Map(innerProto)) =>
        clType match {
          case CLType.Map(keyType, valueType) =>
            val values = innerProto.toList.traverse {
              case state.CLValueInstance.MapEntry(keyProto, valueProto) =>
                for {
                  key <- keyProto
                          .map(k => defer(fromProtoLoop(k, keyType)))
                          .getOrElse(raise(Error.MissingInstance))
                  value <- valueProto
                            .map(v => defer(fromProtoLoop(v, valueType)))
                            .getOrElse(raise(Error.MissingInstance))
                } yield (key, value)
            }
            values.flatMap { pairs =>
              lift(
                CLValueInstance
                  .Map(pairs.toMap, keyType, valueType)
                  .leftMap(Error.InstanceError.apply)
              )
            }

          case other => raise(Error.TypeMismatch(other, "Map"))
        }

      case state.CLValueInstance.Value.Value
            .Tuple1Value(state.CLValueInstance.Tuple1(innerProto)) =>
        clType match {
          case CLType.Tuple1(innerType) =>
            innerProto
              .map(v => defer(fromProtoLoop(v, innerType)))
              .getOrElse(raise(Error.MissingInstance))
              .map { innerValue =>
                CLValueInstance.Tuple1(innerValue)
              }

          case other => raise(Error.TypeMismatch(other, "Tuple1"))
        }

      case state.CLValueInstance.Value.Value
            .Tuple2Value(state.CLValueInstance.Tuple2(t1Proto, t2Proto)) =>
        clType match {
          case CLType.Tuple2(type1, type2) =>
            for {
              v1 <- t1Proto
                     .map(v => defer(fromProtoLoop(v, type1)))
                     .getOrElse(raise(Error.MissingInstance))
              v2 <- t2Proto
                     .map(v => defer(fromProtoLoop(v, type2)))
                     .getOrElse(raise(Error.MissingInstance))
            } yield CLValueInstance.Tuple2(v1, v2)

          case other => raise(Error.TypeMismatch(other, "Tuple2"))
        }

      case state.CLValueInstance.Value.Value
            .Tuple3Value(state.CLValueInstance.Tuple3(t1Proto, t2Proto, t3Proto)) =>
        clType match {
          case CLType.Tuple3(type1, type2, type3) =>
            for {
              v1 <- t1Proto
                     .map(v => defer(fromProtoLoop(v, type1)))
                     .getOrElse(raise(Error.MissingInstance))
              v2 <- t2Proto
                     .map(v => defer(fromProtoLoop(v, type2)))
                     .getOrElse(raise(Error.MissingInstance))
              v3 <- t3Proto
                     .map(v => defer(fromProtoLoop(v, type3)))
                     .getOrElse(raise(Error.MissingInstance))
            } yield CLValueInstance.Tuple3(v1, v2, v3)

          case other => raise(Error.TypeMismatch(other, "Tuple3"))
        }
    }

  def fromProto(proto: state.CLValueInstance): Either[Error, CLValueInstance] = proto.value match {
    case None => Left(Error.MissingValue)
    case Some(value) =>
      proto.clType
        .map(fromProto)
        .getOrElse(Left(Error.MissingType))
        .flatMap(clType => fromProto(value, clType))
  }

  def fromArg(arg: Deploy.Arg): Either[Error, (String, CLValue)] = arg.value match {
    case None => Left(Error.MissingArg)
    case Some(value) =>
      for {
        instance <- fromProto(value)
        clValue  <- instance.toValue.leftMap(Error.InstanceError.apply)
      } yield (arg.name, clValue)
  }

  private def toByteArray32(bytes: ByteString): Either[Error, ByteArray32] =
    ByteArray32(bytes.toByteArray) match {
      case None          => Left(Error.Expected32Bytes(foundLength = bytes.size))
      case Some(bytes32) => Right(bytes32)
    }

  private def validateBigInt(s: String): Either[Error, BigInt Refined NonNegative] =
    Try(BigInt(s)) match {
      case Failure(_) => Left(Error.InvalidBigIntValue(s))
      case Success(i) => refineV[NonNegative](i).leftMap(_ => Error.InvalidBigIntValue(s))
    }

  sealed trait Error
  object Error {
    case class NoRepresentation(source: String, target: String) extends Error {
      override def toString: String = s"No representation for $source as $target"
    }

    case class TypeMismatch(tagType: CLType, valueType: String) extends Error

    case class InstanceError(err: CLValueInstance.Error) extends Error

    case class FromBytesError(err: FromBytes.Error) extends Error

    case class Expected32Bytes(foundLength: Int) extends Error
    case class Expected64Bytes(foundLength: Int) extends Error

    case object MissingType          extends Error
    case object MissingInstance      extends Error
    case object MissingArg           extends Error
    case object MissingValue         extends Error
    case object EmptyKeyVariant      extends Error
    case object EmptyArgValueVariant extends Error
    case object EmptyInstanceVariant extends Error
    case object EmptyResultVariant   extends Error
    case object EmptyTypeVariant     extends Error

    case class InvalidBigIntValue(value: String) extends Error
    case class InvalidBitWidth(bitWidth: Int)    extends Error

    case class UnrecognizedAccessRights(enumValue: Int) extends Error
    case class UnrecognizedSimpleType(enumValue: Int)   extends Error
  }

  private case class Raise[A](error: Error)
  private type FE[A] = Free[Raise, A]

  private def raise[A](e: Error): FE[A]     = Free.liftF(Raise[A](e))
  private def pure[A](a: A): FE[A]          = Free.pure(a)
  private def defer[A](fa: => FE[A]): FE[A] = Free.defer(fa)
  private def lift[A](either: Either[Error, A]): FE[A] = either match {
    case Left(err) => raise(err)
    case Right(a)  => pure(a)
  }

  private val interpreter: FunctionK[Raise, Either[Error, *]] =
    new FunctionK[Raise, Either[Error, *]] {
      def apply[A](raise: Raise[A]): Either[Error, A] = Left(raise.error)
    }
}
