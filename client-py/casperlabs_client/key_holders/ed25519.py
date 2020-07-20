import base64
from typing import Union
from pathlib import Path

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ed25519

from casperlabs_client.consts import ED25519_KEY_ALGORITHM
from casperlabs_client.io import read_binary_file
from .key_holder import KeyHolder


class ED25519Key(KeyHolder):
    """ Class for loading, generating and handling public/private key pairs using ed25519 algorithm """

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
            ED25519_KEY_ALGORITHM,
        )

    @staticmethod
    def _parse_pem_data(pem_file_data: bytes):
        raw_data = KeyHolder._parse_pem_data_line(pem_file_data)
        data = base64.b64decode(raw_data)
        # TODO: Where does this magic come from?
        if len(data) % 32 == 0:
            return data[:32]
        else:
            return data[-32:]

    def _private_key_pem_from_private_key(self) -> bytes:
        private_key = ed25519.Ed25519PrivateKey.from_private_bytes(self.private_key)
        return private_key.private_bytes(
            encoding=serialization.Encoding.PEM,
            format=serialization.PrivateFormat.PKCS8,
            encryption_algorithm=serialization.NoEncryption(),
        )

    def _private_key_from_private_key_pem(self) -> bytes:
        return self._parse_pem_data(self.private_key_pem)

    def _public_key_pem_from_public_key(self) -> bytes:
        public_key = ed25519.Ed25519PublicKey.from_public_bytes(self.public_key)
        return public_key.public_bytes(
            encoding=serialization.Encoding.PEM,
            format=serialization.PublicFormat.SubjectPublicKeyInfo,
        )

    def _public_key_from_public_key_pem(self):
        return self._parse_pem_data(self.public_key_pem)

    def _public_key_from_private_key(self):
        private_key = ed25519.Ed25519PrivateKey.from_private_bytes(self.private_key)
        return private_key.public_key().public_bytes(
            encoding=serialization.Encoding.Raw, format=serialization.PublicFormat.Raw
        )

    def sign(self, data: bytes) -> bytes:
        return ed25519.Ed25519PrivateKey.from_private_bytes(self.private_key).sign(data)

    @staticmethod
    def from_private_key_path(private_key_pem_path: Union[str, Path]) -> "KeyHolder":
        """ Returns a ED25519Key object loaded from a private_key_pem file"""
        private_key_pem = read_binary_file(private_key_pem_path)
        return ED25519Key(private_key_pem=private_key_pem)

    @staticmethod
    def from_public_key_path(public_key_pem_path: Union[str, Path]) -> "KeyHolder":
        """ Returns a ED25519Key object loaded from a private_key_pem file"""
        public_key_pem = read_binary_file(public_key_pem_path)
        return ED25519Key(public_key_pem=public_key_pem)

    @staticmethod
    def generate():
        """
        Generates a new key pair and returns as ED25519 object.

        :returns Ed25519Key object
        """
        private_key = ed25519.Ed25519PrivateKey.generate()

        private_pem = private_key.private_bytes(
            encoding=serialization.Encoding.PEM,
            format=serialization.PrivateFormat.PKCS8,
            encryption_algorithm=serialization.NoEncryption(),
        )

        private_bytes = private_key.private_bytes(
            encoding=serialization.Encoding.Raw,
            format=serialization.PrivateFormat.Raw,
            encryption_algorithm=serialization.NoEncryption(),
        )

        return ED25519Key(private_key_pem=private_pem, private_key=private_bytes)
