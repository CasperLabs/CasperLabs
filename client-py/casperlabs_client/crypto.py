"""
Cryptography related code used in the CasperLabs client.
"""

import datetime
import ssl

from cryptography.hazmat.backends import default_backend
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography import x509
from cryptography.hazmat.primitives import hashes
from cryptography.x509.oid import NameOID
from Crypto.Hash import keccak
from pyblake2 import blake2b


def extract_common_name(certificate_file: str) -> str:
    cert_dict = ssl._ssl._test_decode_cert(certificate_file)
    return [t[0][1] for t in cert_dict["subject"] if t[0][0] == "commonName"][0]


def generate_secp256r1_key_pair():
    curve = ec.SECP256R1()
    private_key = ec.generate_private_key(curve, default_backend())
    public_key = private_key.public_key()
    return private_key, public_key


def node_public_address(public_key):
    numbers = public_key.public_numbers()
    x, y = numbers.x, numbers.y

    def int_to_32_bytes(x):
        return x.to_bytes(x.bit_length(), byteorder="little")[0:32]

    a = int_to_32_bytes(x) + int_to_32_bytes(y)

    keccak_hash = keccak.new(digest_bits=256)
    keccak_hash.update(a)
    r = keccak_hash.hexdigest()
    return r[12 * 2 :]


def generate_node_certificates(private_key, public_key):
    today = datetime.datetime.today()
    one_day = datetime.timedelta(1, 0, 0)
    address = node_public_address(
        public_key
    )  # .map(Base16.to_protobuf).getOrElse("local")
    owner = f"CN={address}"

    builder = x509.CertificateBuilder()
    builder = builder.not_valid_before(today)

    # TODO: Where's documentation of the decision to make keys valid for 1 year only?
    builder = builder.not_valid_after(today + 365 * one_day)
    builder = builder.subject_name(
        x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, owner)])
    )
    builder = builder.issuer_name(
        x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, owner)])
    )
    builder = builder.public_key(public_key)
    builder = builder.serial_number(x509.random_serial_number())
    certificate = builder.sign(
        private_key=private_key, algorithm=hashes.SHA256(), backend=default_backend()
    )

    cert_pem = certificate.public_bytes(encoding=serialization.Encoding.PEM)
    key_pem = private_key.private_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PrivateFormat.PKCS8,
        encryption_algorithm=serialization.NoEncryption(),
    )
    return cert_pem, key_pem


def blake2b_hash(data: bytes) -> bytes:
    h = blake2b(digest_size=32)
    h.update(data)
    return h.digest()
