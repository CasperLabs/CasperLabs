import pytest

from casperlabs_client import CasperLabsClient, InternalError
from casperlabs_client.consts import SUPPORTED_KEY_ALGORITHMS
from tests.conftest import key_paths


@pytest.mark.parametrize("algorithm", (SUPPORTED_KEY_ALGORITHMS))
def test_simple_deploy_build_to_node_comm_failure(account_keys_directory, algorithm):
    private_key_pem_path, _ = key_paths(algorithm, account_keys_directory)
    client = CasperLabsClient()
    with pytest.raises(InternalError) as excinfo:
        _ = client.deploy(
            from_addr=b"12121212121212121212121212121212",
            session_name="contract_name",
            private_key=private_key_pem_path,
        )
    assert "failed to connect" in str(excinfo.value)
