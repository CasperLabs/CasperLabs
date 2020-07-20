import os
from pathlib import Path

from casperlabs_client import CasperLabsClient
from casperlabs_client.abi import ABI

THIS_DIRECTORY = Path(os.path.dirname(os.path.realpath(__file__)))
CASPERLABS_ROOT_DIRECTORY = THIS_DIRECTORY.parent.parent.parent

WASM_DIRECTORY = (
    CASPERLABS_ROOT_DIRECTORY
    / "execution-engine"
    / "target"
    / "wasm32-unknown-unknown"
    / "release"
)
HACK_DOCKER_DIRECTORY = CASPERLABS_ROOT_DIRECTORY / "hack" / "docker"
ACCOUNTS_CSV = (
    HACK_DOCKER_DIRECTORY / ".casperlabs" / "chainspec" / "genesis" / "accounts.csv"
)
NODE_0_PRIVATE_PEM_PATH = (
    HACK_DOCKER_DIRECTORY
    / ".casperlabs"
    / "nodes"
    / "nodes-0"
    / "validator-private.pem"
)
FAUCET_PRIVATE_KEY_PEM_PATH = (
    HACK_DOCKER_DIRECTORY / "keys" / "faucet-account" / "account-private.pem"
)


def get_valid_block_hash(casperlabs_client: CasperLabsClient):
    """ Get a valid block hash from current hack/docker network in bytes """
    block_generator = casperlabs_client.show_blocks(depth=8)
    block = list(block_generator)[-1]
    block_hash = block.summary.block_hash
    return block_hash


def get_valid_block_hash_hex(casperlabs_client: CasperLabsClient):
    """ Get a valid block hash from current hack/docker network in hex"""
    return get_valid_block_hash(casperlabs_client).hex()


def faucet_fund_account(casperlabs_client, account_hash_hex, amount=1000000000):
    faucet_wasm_path = WASM_DIRECTORY / "faucet.wasm"
    session_args = ABI.args(
        [ABI.account("target", account_hash_hex), ABI.big_int("amount", amount)]
    )
    # TODO: validate faucet key path to make sure key gen worked during standup.
    deploy_hash = casperlabs_client.deploy(
        private_key=FAUCET_PRIVATE_KEY_PEM_PATH,
        session=faucet_wasm_path,
        session_args=session_args,
    )
    result = casperlabs_client.show_deploy(deploy_hash, wait_for_processed=True)
    block_hash = result.processing_results[0].block_info.summary.block_hash
    result = casperlabs_client.balance(account_hash_hex, block_hash.hex())
    assert result > 0
