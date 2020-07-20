package io.casperlabs.models.cltype

import eu.timepit.refined._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric._
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.models.bytesrepr.{FromBytes, ToBytes}
import org.scalatest.{FlatSpec, Matchers}
import scala.io.Source
import toml.Toml
import toml.Value.Tbl

import StandardCLSerializationTest.TestCase

class StandardCLSerializationTest extends FlatSpec with Matchers {
  val spec = {
    val fileStream = getClass.getResourceAsStream("/CLSerialization.toml")
    val content    = Source.fromInputStream(fileStream).mkString
    Toml.parse(content).right.get
  }

  val tests = TestCase.getArr(spec, "test")

  behavior of "Serialization"
  for (t <- tests) {
    val test = TestCase.from(t)

    it should s"work for ${test.name}" in {
      TestCase.toBytes(test.data) shouldBe test.expected
      TestCase.fromBytes(test.expected, test.data) shouldBe test.data
    }
  }
}

object StandardCLSerializationTest {
  case class TestCase(
      name: String,
      expected: IndexedSeq[Byte],
      data: TestCase.Data
  )

  object TestCase {
    sealed trait Data
    object Data {
      case class SVI(value: StoredValueInstance) extends Data
      case class SV(value: StoredValue)          extends Data
      case class CLT(value: CLType)              extends Data
      case class CLV(value: CLValue)             extends Data
      case class AR(value: AccessRights)         extends Data
    }

    def toBytes(data: Data): IndexedSeq[Byte] = data match {
      case Data.SVI(StoredValueInstance.Account(a))         => ToBytes.toBytes(a)
      case Data.SVI(StoredValueInstance.Contract(c))        => ToBytes.toBytes(c)
      case Data.SVI(StoredValueInstance.ContractWasm(c))    => ToBytes.toBytes(c)
      case Data.SVI(StoredValueInstance.ContractPackage(c)) => ToBytes.toBytes(c)
      case Data.SVI(StoredValueInstance.CLValue(v))         => v.toValue.right.get.value
      case Data.CLT(t)                                      => ToBytes.toBytes(t)
      case Data.CLV(v)                                      => ToBytes.toBytes(v)
      case Data.AR(ar)                                      => ToBytes.toBytes(ar)
      case Data.SV(sv)                                      => ToBytes.toBytes(sv)
    }

    def fromBytes(bytes: IndexedSeq[Byte], target: Data): Data = target match {
      case Data.SVI(StoredValueInstance.Account(_)) =>
        val a = FromBytes.deserialize(Account.deserializer, bytes.toArray).right.get
        Data.SVI(StoredValueInstance.Account(a))

      case Data.SVI(StoredValueInstance.Contract(_)) =>
        val c = FromBytes.deserialize(Contract.deserializer, bytes.toArray).right.get
        Data.SVI(StoredValueInstance.Contract(c))

      case Data.SVI(StoredValueInstance.ContractWasm(_)) =>
        val c = FromBytes.deserialize(ContractWasm.deserializer, bytes.toArray).right.get
        Data.SVI(StoredValueInstance.ContractWasm(c))

      case Data.SVI(StoredValueInstance.ContractPackage(_)) =>
        val c = FromBytes.deserialize(ContractPackage.deserializer, bytes.toArray).right.get
        Data.SVI(StoredValueInstance.ContractPackage(c))

      case Data.SVI(StoredValueInstance.CLValue(v)) =>
        val deserializer = CLValueInstance.deserializer(v.clType)
        val instance     = FromBytes.deserialize(deserializer, bytes.toArray).right.get
        Data.SVI(StoredValueInstance.CLValue(instance))

      case Data.CLT(_) =>
        val t = FromBytes.deserialize(CLType.deserializer, bytes.toArray).right.get
        Data.CLT(t)

      case Data.CLV(_) =>
        val v = FromBytes.deserialize(CLValue.deserializer, bytes.toArray).right.get
        Data.CLV(v)

      case Data.AR(_) =>
        val ar = FromBytes.deserialize(AccessRights.deserializer, bytes.toArray).right.get
        Data.AR(ar)

      case Data.SV(_) =>
        val sv = FromBytes.deserialize(StoredValue.deserializer, bytes.toArray).right.get
        Data.SV(sv)
    }

    def from(table: Tbl): TestCase = {
      val name                       = getString(table, "name")
      val expected: IndexedSeq[Byte] = readHex(getString(table, "expected"))
      val data                       = parseData(table.values("data").asInstanceOf[Tbl])

      TestCase(name, expected, data)
    }

    def readHex(hex: String): IndexedSeq[Byte] = Base16.decode(hex.drop(2))

    def parseData(data: Tbl): Data = data.values.keys.head match {
      case key if key == "cl_type" =>
        Data.CLT(parseCLType(getTable(data, key)))

      case key if key == "cl_value" =>
        val subData = getTable(data, key)
        Data.CLV(parseCLValue(subData))

      case key if key == "account" =>
        val subData = getTable(data, key)
        Data.SVI(StoredValueInstance.Account(parseAccount(subData)))

      case key if key == "contract" =>
        val subData = getTable(data, key)
        Data.SVI(StoredValueInstance.Contract(parseContract(subData)))

      case key if key == "stored_value" =>
        val subData = getTable(data, key)
        Data.SV(parseStoredValue(subData))

      case key if key == "access_rights" =>
        val ar = parseAccessRights(getString(data, key))
        Data.AR(ar)

      case _ =>
        val instance = parseCLValueInstance(data)
        Data.SVI(StoredValueInstance.CLValue(instance))
    }

    def parseStoredValue(data: Tbl): StoredValue = data.values.keys.head match {
      case key if key == "account" =>
        val a = parseAccount(getTable(data, "account"))
        StoredValue.Account(a)

      case key if key == "contract" =>
        val c = parseContract(getTable(data, "contract"))
        StoredValue.Contract(c)

      case key if key == "cl_value" =>
        val v = parseCLValue(getTable(data, "cl_value"))
        StoredValue.CLValue(v)
    }

    def parseCLValueInstance(data: Tbl): CLValueInstance = data.values.keys.head match {
      case key if key == "boolean_value" =>
        CLValueInstance.Bool(getBool(data, key))

      case key if key == "i32_value" =>
        CLValueInstance.I32(getNumber(data, key).toInt)

      case key if key == "i64_value" =>
        CLValueInstance.I64(getNumber(data, key))

      case key if key == "u8_value" =>
        CLValueInstance.U8(getNumber(data, key).toByte)

      case key if key == "u32_value" =>
        CLValueInstance.U32(getNumber(data, key).toInt)

      case key if key == "u64_value" =>
        CLValueInstance.U64(getNumber(data, key))

      case key if key == "u128_value" =>
        CLValueInstance.U128(getBigInt(data, key))

      case key if key == "u256_value" =>
        CLValueInstance.U256(getBigInt(data, key))

      case key if key == "u512_value" =>
        CLValueInstance.U512(getBigInt(data, key))

      case key if key == "unit_value" =>
        CLValueInstance.Unit

      case key if key == "string_value" =>
        CLValueInstance.String(getString(data, key))

      case key if key == "uref_value" =>
        val subData = getTable(data, key)
        val uref    = parseURef(subData)
        CLValueInstance.URef(uref)

      case key if key == "key_value" =>
        val subData = getTable(data, key)
        val k       = parseKey(subData)
        CLValueInstance.Key(k)

      case key if key == "option_value" =>
        val subData = getTable(data, key)
        if (subData.values.isEmpty) {
          CLValueInstance.Option(None, CLType.Any).right.get
        } else {
          val inner = parseCLValueInstance(subData)
          CLValueInstance.Option(Some(inner), inner.clType).right.get
        }

      case key if key == "list_value" =>
        val list = getArr(data, key).map(parseCLValueInstance)
        CLValueInstance.List(list, list.headOption.map(_.clType).getOrElse(CLType.Any)).right.get

      case key if key == "fixed_list_value" =>
        val list = getArr(data, key).map(parseCLValueInstance)
        CLValueInstance.FixedList(list, list.head.clType, list.size).right.get

      case key if key == "result_value" =>
        val subData = getTable(data, key)
        subData.values.keys.head match {
          case "ok" =>
            val inner = parseCLValueInstance(getTable(subData, "ok"))
            CLValueInstance.Result(Right(inner), inner.clType, CLType.Any).right.get

          case "err" =>
            val inner = parseCLValueInstance(getTable(subData, "err"))
            CLValueInstance.Result(Left(inner), CLType.Any, inner.clType).right.get
        }

      case key if key == "map_value" =>
        val elements = getArr(data, key).map { t =>
          val k = parseCLValueInstance(getTable(t, "key"))
          val v = parseCLValueInstance(getTable(t, "value"))
          (k, v)
        }.toMap
        val kType = elements.keys.headOption.map(_.clType).getOrElse(CLType.Any)
        val vType = elements.values.headOption.map(_.clType).getOrElse(CLType.Any)
        CLValueInstance.Map(elements, kType, vType).right.get

      case key if key == "tuple1_value" =>
        val subData = getTable(data, key)
        val v0      = parseCLValueInstance(getTable(subData, "_0"))
        CLValueInstance.Tuple1(v0)

      case key if key == "tuple2_value" =>
        val subData = getTable(data, key)
        val v0      = parseCLValueInstance(getTable(subData, "_0"))
        val v1      = parseCLValueInstance(getTable(subData, "_1"))
        CLValueInstance.Tuple2(v0, v1)

      case key if key == "tuple3_value" =>
        val subData = getTable(data, key)
        val v0      = parseCLValueInstance(getTable(subData, "_0"))
        val v1      = parseCLValueInstance(getTable(subData, "_1"))
        val v2      = parseCLValueInstance(getTable(subData, "_2"))
        CLValueInstance.Tuple3(v0, v1, v2)
    }

    def parseEntryPointAccess(data: Tbl): EntryPointAccess =
      getString(data, "variant") match {
        case "PUBLIC" => EntryPointAccess.Public()
        case "GROUPS" => {
          val groups = getArr(data, "groups").map { t =>
            getString(t, "label")
          }
          EntryPointAccess.Groups(groups)
        }
      }

    def parseEntryPoint(data: Tbl): EntryPoint = {
      val name = getString(data, "name");
      val args = getArr(data, "args").map { t =>
        val name   = getString(t, "name")
        val clType = parseCLType(getTable(t, "type"))
        (name, clType)
      }.toMap
      val ret    = parseCLType(getTable(data, "ret"))
      val access = parseEntryPointAccess(getTable(data, "access"))
      val entryPointType = getString(data, "entry_point_type") match {
        case "SESSION"  => EntryPointType.Session
        case "CONTRACT" => EntryPointType.Contract
      }
      EntryPoint(name, args, ret, access, entryPointType)
    }

    def parseContract(data: Tbl): Contract = {
      val contractPackageHash = readHex(getString(data, "contract_package_hash"))
      val contractWasmHash    = readHex(getString(data, "contract_hash"))
      val namedKeys = getArr(data, "named_keys").map { t =>
        val k = getString(t, "key")
        val v = parseKey(getTable(t, "value"))
        (k, v)
      }.toMap
      val entryPoints = getArr(data, "entry_points").map { t =>
        // val k = getString(t, "key");
        val v = parseEntryPoint(t);
        (getString(t, "name"), v)
      }.toMap
      val protocolVersionData = getTable(data, "protocol_version")
      val protocolVersion = SemVer(
        getNumber(protocolVersionData, "major").toInt,
        getNumber(protocolVersionData, "minor").toInt,
        getNumber(protocolVersionData, "patch").toInt
      )
      Contract(
        ByteArray32(contractPackageHash).get,
        ByteArray32(contractWasmHash).get,
        namedKeys,
        entryPoints,
        protocolVersion
      )
    }

    def parseAccount(data: Tbl): Account = {
      val pk = ByteArray32(readHex(getString(data, "public_key"))).get
      val namedKeys = getArr(data, "named_keys").map { t =>
        val k = getString(t, "key")
        val v = parseKey(getTable(t, "value"))
        (k, v)
      }.toMap
      val mainPurse = parseURef(getTable(data, "main_purse"))
      val associatedKeys = getArr(data, "associated_keys").map { t =>
        val k = ByteArray32(readHex(getString(t, "key"))).get
        val v = getNumber(t, "value").toByte
        (k, v)
      }.toMap
      val actionThresholdsData = getTable(data, "action_thresholds")
      val actionThresholds = Account.ActionThresholds(
        getNumber(actionThresholdsData, "deployment").toByte,
        getNumber(actionThresholdsData, "key_management").toByte
      )
      Account(pk, namedKeys, mainPurse, associatedKeys, actionThresholds)
    }

    def parseCLValue(data: Tbl): CLValue = {
      val t     = parseCLType(getTable(data, "cl_type"))
      val bytes = readHex(getString(data, "bytes"))

      CLValue(t, bytes)
    }

    def parseKey(data: Tbl): Key = data.values.keys.head match {
      case key if key == "account" =>
        val address = readHex(getString(data, key))
        Key.Account(ByteArray32(address).get)

      case key if key == "hash" =>
        val address = readHex(getString(data, key))
        Key.Hash(ByteArray32(address).get)

      case key if key == "uref_value" =>
        val urefData = getTable(data, key)
        val uref     = parseURef(urefData)
        Key.URef(uref)
    }

    def parseCLType(data: Tbl): CLType = data.values.keys.head match {
      case key if key == "simple_type" =>
        getString(data, key) match {
          case "BOOL"   => CLType.Bool
          case "I32"    => CLType.I32
          case "I64"    => CLType.I64
          case "U8"     => CLType.U8
          case "U32"    => CLType.U32
          case "U64"    => CLType.U64
          case "U128"   => CLType.U128
          case "U256"   => CLType.U256
          case "U512"   => CLType.U512
          case "UNIT"   => CLType.Unit
          case "STRING" => CLType.String
          case "KEY"    => CLType.Key
          case "UREF"   => CLType.URef
          case "ANY"    => CLType.Any
        }

      case key if key == "option_type" =>
        val subData = getTable(data, key)
        val inner   = parseCLType(subData)
        CLType.Option(inner)

      case key if key == "list_type" =>
        val subData = getTable(data, key)
        val inner   = parseCLType(subData)
        CLType.List(inner)

      case key if key == "fixed_list_type" =>
        val subData = getTable(data, key)
        val inner   = parseCLType(getTable(subData, "inner_type"))
        val length  = getNumber(subData, "length").toInt
        CLType.FixedList(inner, length)

      case key if key == "result_type" =>
        val subData = getTable(data, key)
        val ok      = parseCLType(getTable(subData, "ok_type"))
        val err     = parseCLType(getTable(subData, "err_type"))
        CLType.Result(ok, err)

      case key if key == "map_type" =>
        val subData = getTable(data, key)
        val k       = parseCLType(getTable(subData, "key_type"))
        val v       = parseCLType(getTable(subData, "value_type"))
        CLType.Map(k, v)

      case key if key == "tuple1_type" =>
        val subData = getTable(data, key)
        val t0      = parseCLType(getTable(subData, "_0"))
        CLType.Tuple1(t0)

      case key if key == "tuple2_type" =>
        val subData = getTable(data, key)
        val t0      = parseCLType(getTable(subData, "_0"))
        val t1      = parseCLType(getTable(subData, "_1"))
        CLType.Tuple2(t0, t1)

      case key if key == "tuple3_type" =>
        val subData = getTable(data, key)
        val t0      = parseCLType(getTable(subData, "_0"))
        val t1      = parseCLType(getTable(subData, "_1"))
        val t2      = parseCLType(getTable(subData, "_2"))
        CLType.Tuple3(t0, t1, t2)
    }

    def parseURef(data: Tbl): URef = {
      val address = readHex(getString(data, "address"))
      val access  = parseAccessRights(getString(data, "access_rights"))
      URef(ByteArray32(address).get, access)
    }

    def parseAccessRights(s: String): AccessRights = s match {
      case "READ"           => AccessRights.Read
      case "WRITE"          => AccessRights.Write
      case "READ_WRITE"     => AccessRights.ReadWrite
      case "ADD"            => AccessRights.Add
      case "READ_ADD"       => AccessRights.ReadAdd
      case "ADD_WRITE"      => AccessRights.AddWrite
      case "READ_ADD_WRITE" => AccessRights.ReadAddWrite
      case "NONE"           => AccessRights.None
      case other            => throw new IllegalArgumentException(s"Invalid AccessRights string: $other")
    }

    def getBigInt(table: Tbl, key: String): BigInt Refined NonNegative = {
      val bigInt = BigInt(getString(table, key))
      refineV[NonNegative](bigInt).right.get
    }

    def getNumber(table: Tbl, key: String): Long =
      table.values(key).asInstanceOf[toml.Value.Num].value

    def getTable(table: Tbl, key: String): Tbl =
      table.values(key).asInstanceOf[Tbl]

    def getString(table: Tbl, key: String): String =
      table.values(key).asInstanceOf[toml.Value.Str].value

    def getBool(table: Tbl, key: String): Boolean =
      table.values(key).asInstanceOf[toml.Value.Bool].value

    def getArr(table: Tbl, key: String): List[Tbl] =
      table.values(key).asInstanceOf[toml.Value.Arr].values.map(_.asInstanceOf[Tbl])
  }
}
