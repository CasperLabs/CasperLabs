import pytest
from casperlabs_client import casper_pb2 as casper

from casperlabs_client.query_state import key_variant


@pytest.mark.parametrize(
    "key_type,key_variant_value,exception_type",
    (
        ("hash", casper.StateQuery.KeyVariant.HASH, None),
        ("address", casper.StateQuery.KeyVariant.ADDRESS, None),
        ("uref", casper.StateQuery.KeyVariant.UREF, None),
        ("local", casper.StateQuery.KeyVariant.LOCAL, None),
        ("", None, ValueError),
        ("bad_key", None, ValueError),
    ),
)
def test_key_variant(key_type, key_variant_value, exception_type):
    if key_variant_value:
        assert key_variant(key_type) == key_variant_value
    else:
        with pytest.raises(exception_type):
            key_variant(key_type)
