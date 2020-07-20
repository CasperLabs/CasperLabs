import base64
from typing import Union, Optional

import google.protobuf.json_format
import google.protobuf.text_format


def encode_base64(a: bytes):
    return str(base64.b64encode(a), "utf-8")


def hexify(o):
    """
    Convert protobuf message to text format with cryptographic keys and signatures in base 16.
    """
    return google.protobuf.text_format.MessageToString(o)


def jsonify(o):
    return google.protobuf.json_format.MessageToJson(o)


def optional_base64_base16_to_bytes(
    value: Union[str, bytes], field_name: str, expected_length: int = 32
) -> Optional[bytes]:
    """ Takes bytes or str """
    if value is None:
        return

    if isinstance(value, bytes):
        if len(value) != expected_length:
            raise ValueError(
                f"{field_name} in bytes has len: {len(value)}, expected len: {expected_length}"
            )
        return value

    value_bytes = base64.b64decode(value)
    if len(value_bytes) != expected_length:
        value_bytes = bytes.fromhex(value)
        if len(value_bytes) != 32:
            raise ValueError(
                f"{field_name} in bytes has len: {len(value)}, expected len: {expected_length}"
            )
    return value_bytes
