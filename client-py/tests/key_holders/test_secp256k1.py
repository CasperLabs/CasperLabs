import ecdsa
import pytest
from ecdsa.keys import BadSignatureError

from casperlabs_client.key_holders import SECP256K1Key


def test_secp256k1_generate():
    PRIVATE_KEY_LENGTH = 32
    PUBLIC_KEY_LENGTH = 65

    key_holder = SECP256K1Key.generate()
    assert len(key_holder.private_key) == PRIVATE_KEY_LENGTH, "private key length"
    assert len(key_holder.public_key) == PUBLIC_KEY_LENGTH, "public key length"

    private_parts = key_holder.private_key_pem.split(b"-----")
    assert private_parts[1] == b"BEGIN EC PRIVATE KEY", "private pem header"
    assert private_parts[3] == b"END EC PRIVATE KEY", "private pen footer"

    public_parts = key_holder.public_key_pem.split(b"-----")
    assert public_parts[1] == b"BEGIN PUBLIC KEY", "public pem header"
    assert public_parts[3] == b"END PUBLIC KEY", "public pem footer"


def test_secp256k1_sign():
    key_holder = SECP256K1Key.generate()
    data = b"0123456789"
    signature = key_holder.sign(data)
    public_key = ecdsa.VerifyingKey.from_string(
        key_holder.public_key, curve=SECP256K1Key.CURVE
    )
    assert (
        public_key.verify_digest(signature, data, sigdecode=ecdsa.util.sigdecode_der)
        is True
    ), "good signature verification"
    with pytest.raises(BadSignatureError):
        public_key.verify_digest(
            signature, data + b"1", sigdecode=ecdsa.util.sigdecode_der
        )


def test_secp256k1_round_trip_private_key():
    private_key = b"\xc4|+\x8b\x98\xeb`\xa9\xc8\x91\xea6W\xa6\x7f\xc6\xd9\xdb\xd6\xc4\xef\xfd9\xa8Y\xbe \x0b\x88wr9"
    first_key = SECP256K1Key(private_key=private_key)
    private_pem = first_key.private_key_pem  # generated from private_key given
    other_key = SECP256K1Key(private_key_pem=private_pem)
    result_private_key = other_key.private_key  # generated from private_key_pem given
    assert (
        result_private_key == private_key
    ), "private key after round trip doesn't match"


def test_secp256k1_round_trip_private_key_pem():
    private_key_pem = (
        b"-----BEGIN EC PRIVATE KEY-----\n"
        b"MHQCAQEEIMR8K4uY62CpyJHqNlemf8bZ29bE7/05qFm+IAuId3I5oAcGBSuBBAAK\n"
        b"oUQDQgAEYboDvTrgr1jzJ2CU0ZbEnU9ps8or0/CEkC2jxYTSUI/h8HFijN+8nMiS\n"
        b"qRDPvK1hdyBjfQr4DKPRlRME+A1Fbg=="
        b"\n-----END EC PRIVATE KEY-----\n"
    )
    first_key = SECP256K1Key(private_key_pem=private_key_pem)
    private_key = first_key.private_key
    other_key = SECP256K1Key(private_key=private_key)
    result_private_key_pem = other_key.private_key_pem
    assert (
        result_private_key_pem == private_key_pem
    ), "private key pem after roundtrip doesn't match"


def test_secp256k1_account_hash():
    public_key = (
        b"a\xba\x03\xbd:\xe0\xafX\xf3'`\x94\xd1\x96\xc4\x9dOi\xb3\xca+\xd3\xf0\x84\x90-\xa3\xc5\x84\xd2P\x8f"
        b"\xe1\xf0qb\x8c\xdf\xbc\x9c\xc8\x92\xa9\x10\xcf\xbc\xadaw c}\n\xf8\x0c\xa3\xd1\x95\x13\x04\xf8\rEn"
    )
    key_holder = SECP256K1Key(public_key=public_key)
    expected_account_hash = b"M\xdb0c\r.\xf4\xad\x1f1WJ\xb7\x0bw\x87\xd7\xc1\xf1\xb0\xe2\x18\xc2\xa4\xe7\x04\xe7\rM\xbc\xaeN"
    account_hash = key_holder.account_hash
    assert account_hash == expected_account_hash, "account_hash does not equal expected"


def test_secp256k1_round_trip_public_key_pem():
    public_key_pem = (
        b"-----BEGIN PUBLIC KEY-----\n"
        b"MFYwEAYHKoZIzj0CAQYFK4EEAAoDQgAEKB8hO0DCsJ0v/GAV9EyDxKTHvwWQt3N4\n"
        b"IIwPuq5tcrYUo1JMb6uvx193+yNwBCNeApRJhLo3sTSdM2kcsyB90w==\n"
        b"-----END PUBLIC KEY-----\n"
    )
    first_key = SECP256K1Key(public_key_pem=public_key_pem)
    public_key = first_key.public_key
    other_key = SECP256K1Key(public_key=public_key)
    result_public_key_pem = other_key.public_key_pem
    assert (
        result_public_key_pem == public_key_pem
    ), "public key pem after roundtrip doesn't match"
