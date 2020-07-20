from typing import Union
from pathlib import Path

import ecdsa
from casperlabs_client.consts import SECP256K1_KEY_ALGORITHM
from casperlabs_client.io import read_binary_file
from .key_holder import KeyHolder


class NoChangeHasher:
    def __init__(self, data=None):
        if data is None:
            self.data = b""
        else:
            self.data = data

    def update(self, data):
        self.data += data
        return self

    def copy(self):
        return NoChangeHasher(self.data)

    def name(self):
        return "bob"

    def __call__(self, data=None):
        if data:
            self.data = data
        return self

    def digest(self):
        return self.data

    @property
    def digest_size(self) -> int:
        return len(self.data)


class SECP256K1Key(KeyHolder):
    """
    Class for loading, generating and handling public/private keys using secp256k1 algorithm

    Note: Many ecdsa methods are from/to_string. This is hold over from Python2 and work as bytes in Python3
    """

    CURVE = ecdsa.SECP256k1
    UNCOMPRESSED = "uncompressed"

    def __init__(
        self,
        private_key_pem: bytes = None,
        private_key=None,
        public_key_pem: bytes = None,
        public_key=None,
    ):
        super().__init__(
            private_key_pem,
            private_key,
            public_key_pem,
            public_key,
            SECP256K1_KEY_ALGORITHM,
        )

    def _private_key_pem_from_private_key(self) -> bytes:
        return ecdsa.SigningKey.from_string(self.private_key, curve=self.CURVE).to_pem()

    def _private_key_from_private_key_pem(self) -> bytes:
        return ecdsa.SigningKey.from_pem(
            self.private_key_pem.decode("UTF-8")
        ).to_string()

    def _public_key_pem_from_public_key(self) -> bytes:
        public_key = ecdsa.VerifyingKey.from_string(self.public_key, curve=self.CURVE)
        return public_key.to_pem()

    def _public_key_from_public_key_pem(self) -> bytes:
        return ecdsa.VerifyingKey.from_pem(
            self.public_key_pem.decode("UTF-8")
        ).to_string(self.UNCOMPRESSED)

    def _public_key_from_private_key(self) -> bytes:
        private_key = ecdsa.SigningKey.from_string(self.private_key, curve=self.CURVE)
        return private_key.verifying_key.to_string(self.UNCOMPRESSED)

    def sign(self, hashed_data: bytes) -> bytes:
        """ Return signature of data given """
        private_key = ecdsa.SigningKey.from_string(self.private_key, curve=self.CURVE)
        return private_key.sign_digest(
            hashed_data, sigencode=ecdsa.util.sigencode_der_canonize
        )

    @staticmethod
    def generate():
        """
        Generates a new key pair and returns as SEPC256K1Key object.

        :returns SECP256K1Key object
        """
        private_key_object = ecdsa.SigningKey.generate(curve=SECP256K1Key.CURVE)
        private_key = private_key_object.to_string()
        return SECP256K1Key(private_key=private_key)

    @staticmethod
    def from_private_key_path(private_key_pem_path: Union[str, Path]) -> "KeyHolder":
        """ Creates SECP256K1Key object from private key file in pem format """
        private_key_pem = read_binary_file(private_key_pem_path)
        return SECP256K1Key(private_key_pem=private_key_pem)

    @staticmethod
    def from_public_key_path(public_key_pem_path: Union[str, Path]) -> "KeyHolder":
        """
        Creates SECP256K1Key object from public key file in pem format.

        Note: Functionality requiring Private Key will not be possible.  Use only if no private key pem is available.
        """
        public_key_pem = read_binary_file(public_key_pem_path)
        return SECP256K1Key(public_key_pem=public_key_pem)

    @staticmethod
    def from_private_key(private_key: bytes) -> "KeyHolder":
        """ Creates SECP256K1Key object from private key in bytes """
        return SECP256K1Key(private_key=private_key)
