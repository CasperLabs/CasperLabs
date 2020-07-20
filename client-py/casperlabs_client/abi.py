import json
import base64
from . import consensus_pb2 as consensus, state_pb2 as state


from google.protobuf import json_format

Arg = consensus.Deploy.Arg
Instance = state.CLValueInstance
Value = Instance.Value
Type = state.CLType

# TODO: This is in no way an ABI.  Need to rename.


class ABI:
    """
    Serialize deploy arguments.
    """

    @staticmethod
    def optional_value(name, a):
        if a is None:
            return Arg(
                name=name,
                value=Instance(
                    cl_type=Type(
                        option_type=Type.Option(inner=Type(any_type=Type.Any()))
                    ),
                    value=Value(option_value=Instance.Option(value=None)),
                ),
            )
        return Arg(
            name=ABI.string_value(name),
            value=Instance(
                cl_type=Type(option_type=Type.Option(inner=a.value.cl_type)),
                value=Value(option_value=Instance.Option(value=a.value.value)),
            ),
        )

    @staticmethod
    def fixed_list(name: str, data: bytes):
        data_len = len(data)
        byte_values = [Value(u8=byte) for byte in data]
        return Arg(
            name=name,
            value=Instance(
                cl_type=Type(
                    fixed_list_type=Type.FixedList(
                        inner=Type(simple_type=Type.Simple.U8), len=data_len
                    )
                ),
                value=Value(
                    fixed_list_value=Instance.FixedList(
                        length=data_len, values=byte_values
                    )
                ),
            ),
        )

    @staticmethod
    def bytes_value(name, a: bytes):
        return ABI.fixed_list(name, a)

    @staticmethod
    def account(name, a):
        if type(a) == bytes and len(a) == 32:
            return ABI.fixed_list(name, a)
        if type(a) == str and len(a) == 64:
            return ABI.fixed_list(name, bytes.fromhex(a))
        raise Exception("account must be 32 bytes or 64 characters long string")

    @staticmethod
    def int_value(name, i: int):
        return Arg(
            name=name,
            value=Instance(
                cl_type=Type(simple_type=Type.Simple.I32), value=Value(i32=i)
            ),
        )

    @staticmethod
    def long_value(name, n: int):
        return Arg(
            name=name,
            value=Instance(
                cl_type=Type(simple_type=Type.Simple.I64), value=Value(i64=n)
            ),
        )

    @staticmethod
    def big_int(name, n):
        return Arg(
            name=name,
            value=Instance(
                cl_type=Type(simple_type=Type.Simple.U512),
                value=Value(u512=Instance.U512(value=str(n))),
            ),
        )

    @staticmethod
    def string_value(name, s):
        return Arg(
            name=name,
            value=Instance(
                cl_type=Type(simple_type=Type.Simple.STRING), value=Value(str_value=s)
            ),
        )

    @staticmethod
    def key_hash(name, h):
        return Arg(
            name=name,
            value=Instance(
                cl_type=Type(simple_type=Type.Simple.KEY),
                value=Value(key=state.Key(hash=state.Key.Hash(hash=h))),
            ),
        )

    @staticmethod
    def key_address(name, a):
        return Arg(
            name=name,
            value=Instance(
                cl_type=Type(simple_type=Type.Simple.KEY),
                value=Value(key=state.Key(address=state.Key.Address(account=a))),
            ),
        )

    @staticmethod
    def key_uref(name, uref, access_rights):
        return Arg(
            name=name,
            value=Instance(
                cl_type=Type(simple_type=Type.Simple.KEY),
                value=Value(
                    key=state.Key(
                        uref=state.Key.URef(uref=uref, access_rights=access_rights)
                    )
                ),
            ),
        )

    @staticmethod
    def key_local(name, h):
        return Arg(
            name=name,
            value=Instance(
                cl_type=Type(simple_type=Type.Simple.KEY),
                value=Value(key=state.Key(local=state.Key.Local(hash=h))),
            ),
        )

    @staticmethod
    def args(args_list: list):
        c = consensus.Deploy.Code(args=args_list)
        return c.args

    @staticmethod
    def args_from_json(s):
        parsed_json = ABI.hex2base64(json.loads(s))
        args = [ABI.single_arg_from_json(d) for d in parsed_json]
        c = consensus.Deploy.Code(args=args)
        return c.args

    @staticmethod
    def args_to_json(args, **kwargs):
        lst = [
            json_format.MessageToDict(arg, preserving_proto_field_name=True)
            for arg in args
        ]
        return json.dumps(ABI.base64_2hex(lst), **kwargs)

    # TODO: remove when Legacy args no longer used
    @staticmethod
    def single_arg_from_json(d):
        try:
            return json_format.ParseDict(d, consensus.Deploy.Arg())
        except json_format.ParseError:
            legacy = json_format.ParseDict(d, consensus.Deploy.LegacyArg())
            return ABI.legacy_arg_to_arg(legacy)

    @staticmethod
    def legacy_arg_to_arg(legacy):
        if legacy.value.HasField("bytes_value"):
            return ABI.bytes_value(legacy.name, legacy.value.bytes_value)
        elif legacy.value.HasField("int_value"):
            ABI.int_value(legacy.name, legacy.value.int_value)
        elif legacy.value.HasField("string_value"):
            return ABI.string_value(legacy.name, legacy.value.string_value)
        elif legacy.value.HasField("long_value"):
            return ABI.long_value(legacy.name, legacy.value.long_value)
        elif legacy.value.HasField("big_int"):
            if legacy.value.big_int.bit_width == 128:
                return ABI.u128(legacy.name, legacy.value.big_int.value)
            elif legacy.value.big_int.bit_width == 256:
                return ABI.u256(legacy.name, legacy.value.big_int.value)
            elif legacy.value.big_int.bit_width == 512:
                return ABI.u512(legacy.name, legacy.value.big_int.value)
            else:
                return None
        elif legacy.value.HasField("key"):
            return Arg(
                name=legacy.name,
                value=Instance(
                    cl_type=Type(simple_type=Type.Simple.KEY),
                    value=Value(key=legacy.value.key),
                ),
            )
        elif legacy.value.HasField("int_list"):
            inner_values = [ABI.int_value("", i) for i in legacy.value.int_list.values]
            return ABI.list_value(legacy.name, inner_values)
        elif legacy.value.HasField("string_list"):
            inner_values = [
                ABI.string_value("", s) for s in legacy.value.string_list.values
            ]
            return ABI.list_value(legacy.name, inner_values)
        elif legacy.value.HasField("optional_value"):
            # TODO: stack safety
            inner_legacy = consensus.Deploy.LegacyArg(
                name="", value=legacy.value.optional_value
            )
            inner_value = ABI.legacy_arg_to_arg(inner_legacy)
            return ABI.optional_value(legacy.name, inner_value)
        else:
            return None

    @staticmethod
    def list_value(name, xs):
        inner_type = Type(any_type=Type.Any()) if not xs else xs[0].value.cl_type
        inner_value = [x.value.value for x in xs]
        return Arg(
            name=name,
            value=Instance(
                cl_type=Type(list_type=Type.List(inner=inner_type)),
                value=Value(list_value=Instance.List(values=inner_value)),
            ),
        )

    @staticmethod
    def u32(name, n: int):
        return Arg(
            name=name,
            value=Instance(
                cl_type=Type(simple_type=Type.Simple.U32), value=Value(u32=n)
            ),
        )

    @staticmethod
    def u64(name, n: int):
        return Arg(
            name=name,
            value=Instance(
                cl_type=Type(simple_type=Type.Simple.U64), value=Value(u64=n)
            ),
        )

    @staticmethod
    def u512(name, n: int):
        return ABI.big_int(name, n)

    @staticmethod
    def u256(name, n):
        return Arg(
            name=name,
            value=Instance(
                cl_type=Type(simple_type=Type.Simple.U256),
                value=Value(u512=Instance.U256(value=str(n))),
            ),
        )

    @staticmethod
    def u128(name, n):
        return Arg(
            name=name,
            value=Instance(
                cl_type=Type(simple_type=Type.Simple.U128),
                value=Value(u512=Instance.U128(value=str(n))),
            ),
        )

    @staticmethod
    def byte_array(name, a):
        return ABI.bytes_value(name, a)

    # These are kinds of Arg that are of type bytes.
    # We want to represent them in JSON in hex format, rather than base64.
    HEX_FIELDS = ("account", "bytes_value", "hash", "uref")

    # Standard protobuf JSON representation encodes fields of type bytes
    # in base64. We like base16 (hex) more. Below helper functions
    # help in converting output of protobuf JSON args serialization (base64 -> base16)
    # and in preparing user suplied JSON for parsing with protobuf (base16 -> base64).

    @staticmethod
    def h2b64(name, x):
        """
        Convert value of a field of a given name from hex to base64,
        if the field is known to be of the bytes type.
        """
        if type(x) == str and name in ABI.HEX_FIELDS:
            return base64.b64encode(bytes.fromhex(x)).decode("utf-8")
        return ABI.hex2base64(x)

    @staticmethod
    def hex2base64(d):
        """
        Convert decoded JSON list of args so fields of type bytes
        are in base64, in order to make it compatible with the format
        required by protobuf JSON parser.
        """
        if type(d) == list:
            return [ABI.hex2base64(x) for x in d]
        if type(d) == dict:
            return {k: ABI.h2b64(k, d[k]) for k in d}
        return d

    @staticmethod
    def b64_2h(name, x):
        """
        Helper function of base64_2hex.
        """
        if type(x) == str and name in ABI.HEX_FIELDS:
            return base64.b64decode(x).hex()
        return ABI.base64_2hex(x)

    @staticmethod
    def base64_2hex(d):
        """
        Convert JSON serialized args so fields of type bytes are shown in base16 (hex).
        """
        if type(d) == list:
            return [ABI.base64_2hex(x) for x in d]
        if type(d) == dict:
            return {k: ABI.b64_2h(k, d[k]) for k in d}
        return d
