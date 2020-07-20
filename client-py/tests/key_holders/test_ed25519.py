import pytest
from cryptography.hazmat.primitives.asymmetric import ed25519
from cryptography.exceptions import InvalidSignature

from casperlabs_client.key_holders import ED25519Key


def test_ed25519_generate():
    PRIVATE_KEY_BYTES_LENGTH = 32
    PUBLIC_KEY_BYTES_LENGTH = 32
    PRIVATE_KEY_BASE64_LENGTH = 64
    PUBLIC_KEY_BASE64_LENGTH = 60

    key_holder = ED25519Key.generate()

    assert len(key_holder.private_key) == PRIVATE_KEY_BYTES_LENGTH
    assert len(key_holder.public_key) == PUBLIC_KEY_BYTES_LENGTH

    private_parts = key_holder.private_key_pem.split(b"-----")
    assert private_parts[1] == b"BEGIN PRIVATE KEY"
    assert (
        len(private_parts[2]) == PRIVATE_KEY_BASE64_LENGTH + 2
    )  # Two line feeds at begin and end
    assert private_parts[3] == b"END PRIVATE KEY"

    public_parts = key_holder.public_key_pem.split(b"-----")
    assert public_parts[1] == b"BEGIN PUBLIC KEY"
    assert (
        len(public_parts[2]) == PUBLIC_KEY_BASE64_LENGTH + 2
    )  # Two line feeds at begin and end
    assert public_parts[3] == b"END PUBLIC KEY"


def test_ed25519_sign():
    key_holder = ED25519Key.generate()
    data = b"0123456789"
    signature = key_holder.sign(data)
    public_key = ed25519.Ed25519PublicKey.from_public_bytes(key_holder.public_key)

    # verify raised exception when bad.
    with pytest.raises(InvalidSignature):
        public_key.verify(signature, data + b"1")
    # return of None with no exception when good.
    assert public_key.verify(signature, data) is None


def test_parse_pem_data_line_ed25519_private_key():
    pem_data = (
        b"-----BEGIN PRIVATE KEY-----\n"
        b"MC4CAQAwBQYDK2VwBCIEIGXB6fvNKdlQh53I7bSlGg9bFmqST/0tpwJqbjtW6Drg\n"
        b"-----END PRIVATE KEY-----\n"
    )
    expected = b"e\xc1\xe9\xfb\xcd)\xd9P\x87\x9d\xc8\xed\xb4\xa5\x1a\x0f[\x16j\x92O\xfd-\xa7\x02jn;V\xe8:\xe0"
    result = ED25519Key._parse_pem_data(pem_data)
    assert result == expected


def test_parse_pem_data_line_ed25519_public_key():
    pem_data = (
        b"-----BEGIN PUBLIC KEY-----\n"
        b"MCowBQYDK2VwAyEAwaURJT / kvOOr42Y3 / ScQQt3+DpgVPW0nbsv8GC70G9g=\n"
        b"-----END PUBLIC KEY-----\n"
    )
    expected = b"\xc1\xa5\x11%?\xe4\xbc\xe3\xab\xe3f7\xfd'\x10B\xdd\xfe\x0e\x98\x15=m'n\xcb\xfc\x18.\xf4\x1b\xd8"
    result = ED25519Key._parse_pem_data(pem_data)
    assert result == expected


def test_ed25519_round_trip_private_key():
    private_key = b"e\xc1\xe9\xfb\xcd)\xd9P\x87\x9d\xc8\xed\xb4\xa5\x1a\x0f[\x16j\x92O\xfd-\xa7\x02jn;V\xe8:\xe0"
    first_key = ED25519Key(private_key=private_key)
    private_pem = first_key.private_key_pem  # generated from private_key given
    other_key = ED25519Key(private_key_pem=private_pem)
    result_private_key = other_key.private_key  # generated from private_key_pem given
    assert (
        result_private_key == private_key
    ), "private key after round trip doesn't match"


def test_ed25519_round_trip_private_key_pem():
    private_key_pem = (
        b"-----BEGIN PRIVATE KEY-----\n"
        b"MC4CAQAwBQYDK2VwBCIEIGXB6fvNKdlQh53I7bSlGg9bFmqST/0tpwJqbjtW6Drg\n"
        b"-----END PRIVATE KEY-----\n"
    )
    first_key = ED25519Key(private_key_pem=private_key_pem)
    private_key = first_key.private_key  # generated from private_key_pem given
    other_key = ED25519Key(private_key=private_key)
    result_private_key_pem = (
        other_key.private_key_pem
    )  # generated from private_key given
    assert (
        result_private_key_pem == private_key_pem
    ), "private key pem after roundtrip doesn't match"


def test_ed25519_account_hash():
    public_key = b"\xc1\xa5\x11%?\xe4\xbc\xe3\xab\xe3f7\xfd'\x10B\xdd\xfe\x0e\x98\x15=m'n\xcb\xfc\x18.\xf4\x1b\xd8"
    key_holder = ED25519Key(public_key=public_key)
    expected_account_hash = b"\t%\xa8\x12\x83\xd3\x8b\x00\x19\xd3\x8dN\x8b\x16U<\xab\xe2MQ\xd8yR\x02\xebBn\x15\xd5F`8"
    account_hash = key_holder.account_hash
    assert account_hash == expected_account_hash, "account_hash does not equal expected"
