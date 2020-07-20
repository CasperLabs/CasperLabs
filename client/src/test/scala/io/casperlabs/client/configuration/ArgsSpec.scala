package io.casperlabs.client.configuration

import cats.syntax.option._
import cats.syntax.either._
import org.scalatest._
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.casper.consensus.Deploy.Arg
import io.casperlabs.casper.consensus.state
import io.casperlabs.models.cltype.CLType
import io.casperlabs.models.cltype.protobuf.dsl
import com.google.protobuf.ByteString

class ArgsSpec extends FlatSpec with Matchers {
  behavior of "Args"

  it should "parse JSON arguments" in {
    val bool       = true
    val int        = 314
    val long       = 2342L
    val byte: Byte = 7
    val bigInt     = BigInt("123456789101112131415161718")
    val string     = "Hello, world!"
    val address    = Array.range(0, 32).map(_.toByte)
    val addressHex = Base16.encode(address)

    val json = s"""
    [
      {"name" : "bool", "value" : {"cl_type" : { "simple_type" : "BOOL" }, "value" : { "bool_value" : $bool }}},

      {"name" : "i32", "value" : {"cl_type" : { "simple_type" : "I32" }, "value" : { "i32" : $int }}},

      {"name" : "i64", "value" : {"cl_type" : { "simple_type" : "I64" }, "value" : { "i64" : $long }}},

      {"name" : "u8", "value" : {"cl_type" : { "simple_type" : "U8" }, "value" : { "u8" : $byte }}},

      {"name" : "u32", "value" : {"cl_type" : { "simple_type" : "U32" }, "value" : { "u32" : $int }}},

      {"name" : "u64", "value" : {"cl_type" : { "simple_type" : "U64" }, "value" : { "u64" : $long }}},

      {"name" : "u128", "value" : {"cl_type" : { "simple_type" : "U128" }, "value" : { "u128" :  {"value" : "${bigInt.toString}"}}}},

      {"name" : "u256", "value" : {"cl_type" : { "simple_type" : "U256" }, "value" : { "u256" :  {"value" : "${bigInt.toString}"}}}},

      {"name" : "u512", "value" : {"cl_type" : { "simple_type" : "U512" }, "value" : { "u512" :  {"value" : "${bigInt.toString}"}}}},

      {"name" : "unit", "value" : {"cl_type" : { "simple_type" : "UNIT" }, "value" : { "unit" : {} }}},

      {"name" : "string", "value" : {"cl_type" : { "simple_type" : "STRING" }, "value" : { "str_value" : "$string" }}},

      {"name" : "accountKey", "value" : {"cl_type" : { "simple_type" : "KEY" }, "value" : {"key": {"address": {"account": "$addressHex"}}}}},

      {"name" : "hashKey", "value" : {"cl_type" : { "simple_type" : "KEY" }, "value" : {"key": {"hash": {"hash": "$addressHex"}}}}},

      {"name" : "urefKey", "value" : {"cl_type" : { "simple_type" : "KEY" }, "value" : {"key": {"uref": {"uref": "$addressHex", "access_rights": 5}}}}},

      {"name" : "uref", "value" : {"cl_type" : { "simple_type" : "UREF" }, "value" : {"uref": {"uref": "$addressHex", "access_rights": 5}}}},

      {
        "name" : "maybe_u64",
        "value" : {
          "cl_type" : {"option_type" : {"inner" : {"simple_type" : "U64"}}},
          "value" : {"option_value" : {}}
        }
      },

      {
        "name" : "maybe_u64",
        "value" : {
          "cl_type" : {"option_type" : {"inner" : {"simple_type" : "U64"}}},
          "value" : {"option_value" : {"value" : {"u64" : $long}}}
        }
      },

      {
        "name" : "list_i32",
        "value" : {
          "cl_type" : {"list_type" : {"inner" : {"simple_type" : "I32"}}},
          "value" : {"list_value" : {"values" : [{"i32" : 0}, {"i32" : 1}, {"i32" : 2}, {"i32" : 3}]}}
        }
      },

      {
        "name" : "fixed_list_str",
        "value" : {
          "cl_type" : {"fixed_list_type" : {"inner" : {"simple_type" : "STRING"}, "len" : 3}},
          "value" : {"fixed_list_value" : {"length" : 3, "values" : [{"str_value" : "A"}, {"str_value" : "B"}, {"str_value" : "C"}]}}
        }
      },

      {
        "name" : "err_string",
        "value" : {
          "cl_type" : {"result_type" : {"ok" : {"simple_type" : "BOOL"}, "err" : {"simple_type" : "STRING"}}},
          "value" : {"result_value" : {"err" : {"str_value" : "$string"}}}
        }
      },

      {
        "name" : "ok_bool",
        "value" : {
          "cl_type" : {"result_type" : {"ok" : {"simple_type" : "BOOL"}, "err" : {"simple_type" : "STRING"}}},
          "value" : {"result_value" : {"ok" : {"bool_value" : $bool}}}
        }
      },

      {
        "name" : "map_string_i32",
        "value" : {
          "cl_type" : {"map_type" : {"key" : {"simple_type" : "STRING"}, "value" : {"simple_type" : "I32"}}},
          "value" : {
            "map_value" : {
              "values" : [
                {"key" : {"str_value" : "A"}, "value" : {"i32" : 0}},
                {"key" : {"str_value" : "B"}, "value" : {"i32" : 1}},
                {"key" : {"str_value" : "C"}, "value" : {"i32" : 2}}
              ]
            }
          }
        }
      },

      {
        "name" : "tuple1",
        "value" : {
          "cl_type" : {"tuple1_type" : {"type0" : {"simple_type" : "U8"}}},
          "value" : {"tuple1_value" : {"value_1" : {"u8" : $byte}}}
        }
      },

      {
        "name" : "tuple2",
        "value" : {
          "cl_type" : {"tuple2_type" : {"type0" : {"simple_type" : "U8"}, "type1" : {"simple_type" : "U32"}}},
          "value" : {"tuple2_value" : {"value_1" : {"u8" : $byte}, "value_2" : {"u32" : $int}}}
        }
      },

      {
        "name" : "tuple3",
        "value" : {
          "cl_type" : {"tuple3_type" : {
            "type0" : {"simple_type" : "U8"},
            "type1" : {"simple_type" : "U32"},
            "type2" : {"simple_type" : "U64"}
          }},
          "value" : {"tuple3_value" : {
            "value_1" : {"u8" : $byte},
            "value_2" : {"u32" : $int},
            "value_3" : {"u64" : $long}
          }}
        }
      },

      {
        "name" : "raw_bytes",
        "value" : {
          "cl_type" : {"list_type" : {"inner" : {"simple_type" : "U8"}}},
          "value" : {"bytes_value" : "$addressHex"}
        }
      },

      {
        "name" : "raw_bytes_fixed",
        "value" : {
          "cl_type" : {"fixed_list_type" : {"inner" : {"simple_type" : "U8"}, "len" : 32}},
          "value" : {"bytes_value" : "$addressHex"}
        }
      }
    ]
    """

    val args = Args.fromJson(json).fold(e => fail(e), identity)

    args should have size 27
    args(0) shouldBe Arg("bool").withValue(
      dsl.instances.bool(bool)
    )
    args(1) shouldBe Arg("i32").withValue(
      dsl.instances.i32(int)
    )
    args(2) shouldBe Arg("i64").withValue(
      dsl.instances.i64(long)
    )
    args(3) shouldBe Arg("u8").withValue(
      dsl.instances.u8(byte)
    )
    args(4) shouldBe Arg("u32").withValue(
      dsl.instances.u32(int)
    )
    args(5) shouldBe Arg("u64").withValue(
      dsl.instances.u64(long)
    )
    args(6) shouldBe Arg("u128").withValue(
      dsl.instances.u128(bigInt)
    )
    args(7) shouldBe Arg("u256").withValue(
      dsl.instances.u256(bigInt)
    )
    args(8) shouldBe Arg("u512").withValue(
      dsl.instances.u512(bigInt)
    )
    args(9) shouldBe Arg("unit").withValue(
      dsl.instances.unit
    )
    args(10) shouldBe Arg("string").withValue(
      dsl.instances.string(string)
    )
    args(11) shouldBe Arg("accountKey").withValue(
      state.CLValueInstance(
        clType = dsl.types.key.some,
        value = dsl.values
          .key(state.Key().withAddress(state.Key.Address(ByteString.copyFrom(address))))
          .some
      )
    )
    args(12) shouldBe Arg("hashKey").withValue(
      state.CLValueInstance(
        clType = dsl.types.key.some,
        value = dsl.values
          .key(state.Key().withHash(state.Key.Hash(ByteString.copyFrom(address))))
          .some
      )
    )
    args(13) shouldBe Arg("urefKey").withValue(
      state.CLValueInstance(
        clType = dsl.types.key.some,
        value = dsl.values
          .key(
            state
              .Key()
              .withUref(
                state.Key
                  .URef(ByteString.copyFrom(address), state.Key.URef.AccessRights.READ_ADD)
              )
          )
          .some
      )
    )
    args(14) shouldBe Arg("uref").withValue(
      state.CLValueInstance(
        clType = dsl.types.uref.some,
        value = dsl.values
          .uref(
            state.Key
              .URef(ByteString.copyFrom(address), state.Key.URef.AccessRights.READ_ADD)
          )
          .some
      )
    )
    args(15) shouldBe Arg("maybe_u64").withValue(
      dsl.instances.option.none(dsl.types.u64)
    )
    args(16) shouldBe Arg("maybe_u64").withValue(
      dsl.instances.option.some(dsl.instances.u64(long))
    )
    args(17) shouldBe Arg("list_i32").withValue(
      dsl.instances.list(
        List(0, 1, 2, 3).map(dsl.instances.i32)
      )
    )
    args(18) shouldBe Arg("fixed_list_str").withValue(
      dsl.instances.fixedList(
        List("A", "B", "C").map(dsl.instances.string)
      )
    )
    args(19) shouldBe Arg("err_string").withValue(
      dsl.instances.result.err(dsl.instances.string(string), dsl.types.bool)
    )
    args(20) shouldBe Arg("ok_bool").withValue(
      dsl.instances.result.ok(dsl.instances.bool(bool), dsl.types.string)
    )
    args(21) shouldBe Arg("map_string_i32").withValue(
      dsl.instances.map(
        List("A", "B", "C")
          .map(dsl.instances.string)
          .zip(
            List(0, 1, 2).map(dsl.instances.i32)
          )
      )
    )
    args(22) shouldBe Arg("tuple1").withValue(
      dsl.instances.tuple1(dsl.instances.u8(byte))
    )
    args(23) shouldBe Arg("tuple2").withValue(
      dsl.instances.tuple2(dsl.instances.u8(byte), dsl.instances.u32(int))
    )
    args(24) shouldBe Arg("tuple3").withValue(
      dsl.instances.tuple3(dsl.instances.u8(byte), dsl.instances.u32(int), dsl.instances.u64(long))
    )
    args(25) shouldBe Arg("raw_bytes").withValue(
      dsl.instances.bytes(address)
    )
    args(26) shouldBe Arg("raw_bytes_fixed").withValue(
      dsl.instances.bytesFixedLength(address)
    )
  }

  // TODO: remove this when there are no more usages of the old format
  it should "parse JSON legacy arguments" in {
    val int_value   = 12345
    val amount      = 123456L
    val account     = Array.range(0, 32).map(_.toByte)
    val hex_account = Base16.encode(account)
    val json        = s"""
    [
      {"name": "my_long_value", "value": {"long_value": ${amount}}},
      {"name": "my_account", "value": {"bytes_value": "${hex_account}"}},
      {"name": "maybe_long", "value": {"optional_value": {}}},
      {"name": "maybe_long", "value": {"optional_value": {"long_value": ${amount}}}},
      {"name": "surname", "value": {"string_value": "Nakamoto"}},
      {"name": "big_number", "value": {"big_int": {"value": "2", "bit_width": 512}}},
      {"name": "my_hash", "value": {"key": {"hash": {"hash": "${hex_account}"}}}},
      {"name": "my_address", "value": {"key": {"address": {"account": "${hex_account}"}}}},
      {"name": "my_uref", "value": {"key": {"uref": {"uref": "${hex_account}", "access_rights": 5}}}},
      {"name": "my_int_value", "value": {"int_value": ${int_value}}},
      {"name": "my_int_list", "value": {"int_list": {"values": [0, 1, 2]}}},
      {"name": "my_string_list", "value": {"string_list": {"values": ["A", "B", "C"]}}}
    ]
    """

    Args.fromJson(json) match {
      case Left(error) =>
        fail(error)
      case Right(args) =>
        args should have size 12
        args(0) shouldBe Arg("my_long_value").withValue(
          dsl.instances.i64(amount)
        )
        args(1) shouldBe Arg("my_account").withValue(
          dsl.instances.fixedList(account.map(dsl.instances.u8))
        )
        args(2) shouldBe Arg("maybe_long").withValue(
          dsl.instances.option.none(dsl.types.any)
        )
        args(3) shouldBe Arg("maybe_long").withValue(
          dsl.instances.option.some(dsl.instances.i64(amount))
        )
        args(4) shouldBe Arg("surname").withValue(
          dsl.instances.string("Nakamoto")
        )
        args(5) shouldBe Arg("big_number").withValue(
          dsl.instances.u512(2)
        )
        args(6) shouldBe Arg("my_hash").withValue(
          state.CLValueInstance(
            clType = dsl.types.key.some,
            value = dsl.values
              .key(state.Key().withHash(state.Key.Hash(ByteString.copyFrom(account))))
              .some
          )
        )
        args(7) shouldBe Arg("my_address").withValue(
          state.CLValueInstance(
            clType = dsl.types.key.some,
            value = dsl.values
              .key(state.Key().withAddress(state.Key.Address(ByteString.copyFrom(account))))
              .some
          )
        )
        args(8) shouldBe Arg("my_uref").withValue(
          state.CLValueInstance(
            clType = dsl.types.key.some,
            value = dsl.values
              .key(
                state
                  .Key()
                  .withUref(
                    state.Key
                      .URef(ByteString.copyFrom(account), state.Key.URef.AccessRights.READ_ADD)
                  )
              )
              .some
          )
        )
        args(9) shouldBe Arg("my_int_value").withValue(
          dsl.instances.i32(int_value)
        )
        args(10) shouldBe Arg("my_int_list").withValue(
          dsl.instances.list(List(0, 1, 2).map(dsl.instances.i32))
        )
        args(11) shouldBe Arg("my_string_list").withValue(
          dsl.instances.list(List("A", "B", "C").map(dsl.instances.string))
        )
    }
  }
}
