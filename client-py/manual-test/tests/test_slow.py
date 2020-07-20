import pytest

from casperlabs_client.consts import SUPPORTED_KEY_ALGORITHMS
from .common import WASM_DIRECTORY


@pytest.mark.slow
@pytest.mark.parametrize("algorithm", SUPPORTED_KEY_ALGORITHMS)
def test_call_do_nothing_with_all_supported_key_type_accounts(
    casperlabs_client, faucet_funded_accounts, account_keys_directory, algorithm
):
    """
    faucet_funded_accounts creates accounts with balance for each type of supported key algorithm.
    This test executes the `do_nothing.wasm` contract for each. The tests signing deploy and full
    execution in the system for each key algorithm.

    This can take up to 2 minutes per key type to execute.
    """
    key_holder, private_key_pem_path = faucet_funded_accounts[algorithm]
    do_nothing_wasm_path = WASM_DIRECTORY / "do_nothing.wasm"
    deploy_hash = casperlabs_client.deploy(
        session=do_nothing_wasm_path,
        private_key=private_key_pem_path,
        algorithm=algorithm,
    )
    result = casperlabs_client.show_deploy(deploy_hash, wait_for_processed=True)
    for block_info in result.processing_results:
        assert not block_info.is_error
    assert len(result.processing_results) > 0, "No block_info returned"
