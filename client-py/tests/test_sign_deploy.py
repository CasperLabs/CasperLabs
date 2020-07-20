import pytest

from casperlabs_client import CasperLabsClient, key_holders
from casperlabs_client.consts import SUPPORTED_KEY_ALGORITHMS
from tests.conftest import key_paths


@pytest.mark.parametrize("algorithm", SUPPORTED_KEY_ALGORITHMS)
def test_sign_deploy(account_keys_directory, algorithm):
    private_key_pem_path, public_key_pem_path = key_paths(
        algorithm, account_keys_directory
    )
    key_holder = key_holders.key_holder_object(
        algorithm, private_key_pem_path=private_key_pem_path
    )
    client = CasperLabsClient()
    deploy = client.make_deploy(
        public_key=public_key_pem_path,
        session_name="contract_name",
        algorithm=algorithm,
    )
    signed_by_pem = client.sign_deploy(
        private_key_pem_file=private_key_pem_path, algorithm=algorithm, deploy=deploy
    )
    signed_by_key_holder = client.sign_deploy(
        key_holder=key_holder, algorithm=algorithm, deploy=deploy
    )
    assert signed_by_pem == signed_by_key_holder
