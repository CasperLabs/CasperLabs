import tempfile
from pathlib import Path

import pytest

from casperlabs_client import CasperLabsClient, key_holders
from casperlabs_client.consts import SUPPORTED_KEY_ALGORITHMS
from casperlabs_client.key_holders import ED25519Key

from .common import faucet_fund_account, ACCOUNTS_CSV, HACK_DOCKER_DIRECTORY


@pytest.fixture(scope="session")
def casperlabs_client() -> CasperLabsClient:
    return CasperLabsClient()


@pytest.fixture(scope="session")
def account_keys_directory():
    with tempfile.TemporaryDirectory() as directory:
        client = CasperLabsClient()
        for key_algorithm in SUPPORTED_KEY_ALGORITHMS:
            client.keygen(
                directory, algorithm=key_algorithm, filename_prefix=key_algorithm
            )
        yield Path(directory)


@pytest.fixture(scope="session")
def faucet_funded_accounts(casperlabs_client, account_keys_directory) -> dict:
    accounts = {}
    for key_algorithm in SUPPORTED_KEY_ALGORITHMS:
        private_key_pem_path = account_keys_directory / f"{key_algorithm}-private.pem"
        key_holder = key_holders.key_holder_object(
            algorithm=key_algorithm, private_key_pem_path=private_key_pem_path
        )
        faucet_fund_account(casperlabs_client, key_holder.account_hash_hex)
        accounts[key_algorithm] = key_holder, private_key_pem_path
    return accounts


@pytest.fixture(scope="session")
def genesis_account_and_hash():
    """ Returns tuple of data from genesis account using accounts.csv

     :returns: (public_key_hex, public_key_pem_path, account_hash_hex)
     """
    with open(ACCOUNTS_CSV, "r") as f:
        line = f.readline()
        account_public_key_hex = line.split(",")[0]
    key_holder = ED25519Key(public_key=bytes.fromhex(account_public_key_hex))
    with tempfile.TemporaryDirectory("rb") as td:
        key_path = Path(td) / "account-public.pem"
        with open(key_path, "wb") as f:
            f.write(key_holder.public_key_pem)
        return account_public_key_hex, key_path, key_holder.account_hash_hex


@pytest.fixture(scope="session")
def hack_docker_account_hashes():
    accounts = []
    for file_num in range(3):
        file_path = (
            HACK_DOCKER_DIRECTORY / "keys" / f"account-{file_num}" / "account-id-hex"
        )
        with open(file_path, "rb") as f:
            account_hash_bytes = f.read().strip()
            account_hash = account_hash_bytes.decode("UTF_8")
            accounts.append(account_hash)
    return accounts
