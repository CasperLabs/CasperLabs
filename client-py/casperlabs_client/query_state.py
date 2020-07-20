from . import casper_pb2 as casper


# Note, there is also casper.StateQuery.KeyVariant.KEY_VARIANT_UNSPECIFIED,
# but it doesn't seem to have an official string representation
# ("key_variant_unspecified"? "unspecified"?) and is not used by the client.
STATE_QUERY_KEY_VARIANT = {
    "hash": casper.StateQuery.KeyVariant.HASH,
    "uref": casper.StateQuery.KeyVariant.UREF,
    "address": casper.StateQuery.KeyVariant.ADDRESS,
    "local": casper.StateQuery.KeyVariant.LOCAL,
}


def key_variant(key_type):
    """
    Returns query-state key variant.
    """
    variant = STATE_QUERY_KEY_VARIANT.get(key_type.lower(), None)
    if variant is None:
        raise ValueError(f"{key_type} is not a known query-state key type")
    return variant
