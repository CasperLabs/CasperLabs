from casperlabs_client.key_holders import ED25519Key
from .common import (
    get_valid_block_hash,
    HACK_DOCKER_DIRECTORY,
    get_valid_block_hash_hex,
)

from casperlabs_client.commands import (
    show_peers_cmd,
    show_block_cmd,
    show_blocks_cmd,
    visualize_dag_cmd,
)


def test_show_peers(casperlabs_client):
    response = casperlabs_client.show_peers()
    assert 'host: "node-2"' in str(response)


def test_show_peers_cli(casperlabs_client):
    show_peers_cmd.method(casperlabs_client, dict())


def test_show_block(casperlabs_client):
    block_hash = get_valid_block_hash(casperlabs_client)
    assert len(block_hash) == 32
    block_hash_hex = block_hash.hex()
    block = casperlabs_client.show_block(block_hash_base16=block_hash_hex)
    assert block.summary.block_hash == block_hash


def test_show_block_cli(casperlabs_client):
    block_hash = get_valid_block_hash(casperlabs_client)
    assert len(block_hash) == 32
    block_hash_hex = block_hash.hex()
    args = {"hash": block_hash_hex}
    show_block_cmd.method(casperlabs_client, args)


def test_account_hash(casperlabs_client):
    """ Use account generated key and hash to test python hash """
    account_path = HACK_DOCKER_DIRECTORY / "keys" / "account-0"
    key_holder = ED25519Key.from_public_key_path(account_path / "account-public.pem")
    with open(account_path / "account-id-hex", "rb") as f:
        account_hash_hex = f.read().strip().decode("UTF_8")
    expected_hash = key_holder.account_hash.hex()
    assert account_hash_hex == expected_hash


def test_balance(casperlabs_client, genesis_account_and_hash):
    _, _, genesis_account_hash_hex = genesis_account_and_hash
    block_hash = get_valid_block_hash_hex(casperlabs_client)
    result = casperlabs_client.balance(genesis_account_hash_hex, block_hash)
    assert result > 0


def test_show_blocks(casperlabs_client):
    block_generator = casperlabs_client.show_blocks(depth=3)
    for block in block_generator:
        assert "validator_public_key_hash: " in str(block)


def test_show_blocks_cli(casperlabs_client):
    args = {"depth": 5}
    show_blocks_cmd.method(casperlabs_client, args)


def test_vdag(casperlabs_client):
    result = casperlabs_client.visualize_dag(12)
    # Exercise generator to check for errors
    for value in result:
        assert value is not None


def test_vdag_cli(casperlabs_client):
    args = {"depth": 10}
    visualize_dag_cmd.method(casperlabs_client, args)
