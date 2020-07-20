from dataclasses import dataclass
from tempfile import NamedTemporaryFile
from contextlib import contextmanager
from typing import Union, List
from pathlib import Path
import base64
from pyblake2 import blake2b

from casperlabs_local_net.common import resources_path
from casperlabs_client.crypto import read_pem_key, blake2b_hash


def is_valid_account(account_id: Union[int, str]) -> bool:
    try:
        if account_id == "genesis":
            return True
        return 1 <= account_id <= 300
    except TypeError:
        return False


@dataclass
class Account:
    file_id: Union[int, str]

    @property
    def account_path(self) -> Path:
        return resources_path() / "accounts"

    @property
    def public_key_path(self) -> Path:
        return self.account_path / self.public_key_filename

    @property
    def private_key_path(self) -> Path:
        return self.account_path / self.private_key_filename

    @property
    def public_key_docker_path(self) -> Path:
        return f"/data/accounts/{self.public_key_filename}"

    @property
    def private_key_docker_path(self) -> Path:
        return f"/data/accounts/{self.private_key_filename}"

    @property
    def private_key_filename(self) -> str:
        return f"account-private-{self.file_id}.pem"

    @property
    def public_key_filename(self) -> str:
        return f"account-public-{self.file_id}.pem"

    @property
    def public_key(self) -> str:
        with open(self.account_path / f"account-id-{self.file_id}") as f:
            return f.read().strip()

    @property
    def private_key(self) -> str:
        return base64.b64encode(read_pem_key(self.private_key_path)).decode("utf-8")

    @property
    def public_key_hex(self) -> str:
        return base64.b64decode(self.public_key).hex()

    @property
    def public_key_int_list(self) -> List[int]:
        pkh = self.public_key_hex
        return [int(pkh[i : i + 2], 16) for i in range(0, len(pkh), 2)]

    @property
    def public_key_binary(self) -> bytes:
        return base64.b64decode(self.public_key + "===")

    @contextmanager
    def public_key_binary_file(self):
        """
        Creates a temp file containing the binary version of public key.

        :return: path to binary file of public key for signing with Python Client
        """
        with NamedTemporaryFile("wb") as temp_file:
            with open(temp_file.name, "wb") as f:
                f.write(self.public_key_binary)
            yield temp_file.name


GENESIS_ACCOUNT = Account("genesis")


if __name__ == "__main__":

    print(f"Rust Code for it_common lib.rs:")
    for key in ["genesis"] + list(range(1, 21)):
        acct = Account(key)
        print(
            f"const ACCOUNT_{str(key).upper()}_ADDR: [u8;32] = {acct.public_key_int_list};"
        )
