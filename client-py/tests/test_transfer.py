import pytest

from casperlabs_client import CasperLabsClient, InternalError
from casperlabs_client.consts import (
    ACCOUNT_PRIVATE_KEY_FILENAME_SUFFIX,
    SUPPORTED_KEY_ALGORITHMS,
)


@pytest.mark.parametrize("algorithm", SUPPORTED_KEY_ALGORITHMS)
def test_basic_transfer_to_node_comm_failure(account_keys_directory, algorithm):
    """ Tests full building up to sending and assures we don't get failure that isn't sending based """
    client = CasperLabsClient()
    with pytest.raises(InternalError) as excinfo:
        _ = client.transfer(
            from_addr="1212121212121212121212121212121212121212121212121212121212121212",
            target_account="0000000000000000000000000000000000000000000000000000000000000000",
            amount=10000,
            private_key=account_keys_directory
            / f"{algorithm}{ACCOUNT_PRIVATE_KEY_FILENAME_SUFFIX}",
        )
    assert "failed to connect" in str(excinfo.value)


@pytest.mark.parametrize("target_account,target_purse", ((None, None), ("abc", "abc")))
def test_not_only_one_target_arguments(target_account, target_purse):
    with pytest.raises(InternalError):
        CasperLabsClient().transfer(
            amount=100, target_purse=target_purse, target_account=target_account
        )
