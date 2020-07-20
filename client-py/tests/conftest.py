from pathlib import Path

import pytest
import tempfile
from casperlabs_client import CasperLabsClient
from casperlabs_client.consts import (
    SUPPORTED_KEY_ALGORITHMS,
    ACCOUNT_PUBLIC_KEY_FILENAME_SUFFIX,
    ACCOUNT_PRIVATE_KEY_FILENAME_SUFFIX,
)


@pytest.fixture(scope="session")
def account_keys_directory():
    with tempfile.TemporaryDirectory() as directory:
        client = CasperLabsClient()
        for key_algorithm in SUPPORTED_KEY_ALGORITHMS:
            client.keygen(
                directory, algorithm=key_algorithm, filename_prefix=key_algorithm
            )
        yield Path(directory)


def key_paths(algorithm, directory):
    return (
        directory / f"{algorithm}{ACCOUNT_PRIVATE_KEY_FILENAME_SUFFIX}",
        directory / f"{algorithm}{ACCOUNT_PUBLIC_KEY_FILENAME_SUFFIX}",
    )


@pytest.fixture(scope="session")
def validator_keys_directory():
    with tempfile.TemporaryDirectory() as directory:
        CasperLabsClient.validator_keygen(directory)
        yield Path(directory)
