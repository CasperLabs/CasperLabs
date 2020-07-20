import base64
import dataclasses
import os
import re
import random
import string
import tempfile
from pathlib import Path

from casperlabs_client.crypto import blake2b_hash

from .errors import (
    UnexpectedProposeOutputFormatError,
    UnexpectedShowBlocksOutputFormatError,
)
from casperlabs_client.abi import ABI


class Contract:
    """ This is generated with util/generate_contract_class.py """

    ADD_ASSOCIATED_KEY = "add_associated_key.wasm"
    ARGS_MULTI = "args_multi.wasm"
    ARGS_U32 = "args_u32.wasm"
    ARGS_U512 = "args_u512.wasm"
    COUNTER_CALL = "counter_call.wasm"
    COUNTER_DEFINE = "counter_define.wasm"
    CREATE_NAMED_PURSE = "create_named_purse.wasm"
    DIRECT_REVERT = "direct_revert.wasm"
    ENDLESS_LOOP = "endless_loop.wasm"
    GET_CALLER_CALL = "get_caller_call.wasm"
    GET_CALLER_DEFINE = "get_caller_define.wasm"
    HELLO_NAME_CALL = "hello_name_call.wasm"
    HELLO_NAME_DEFINE = "hello_name_define.wasm"
    LIST_KNOWN_UREFS_CALL = "list_known_urefs_call.wasm"
    LIST_KNOWN_UREFS_DEFINE = "list_known_urefs_define.wasm"
    MAILING_LIST_CALL = "mailing_list_call.wasm"
    MAILING_LIST_DEFINE = "mailing_list_define.wasm"
    PAYMENT_FROM_NAMED_PURSE = "payment_from_named_purse.wasm"
    REMOVE_ASSOCIATED_KEY = "remove_associated_key.wasm"
    SET_KEY_THRESHOLDS = "set_key_thresholds.wasm"
    STANDARD_PAYMENT = "standard_payment.wasm"
    SUBCALL_REVERT_CALL = "subcall_revert_call.wasm"
    SUBCALL_REVERT_DEFINE = "subcall_revert_define.wasm"
    TRANSFER_TO_ACCOUNT = "transfer_to_account_u512.wasm"
    UPDATE_ASSOCIATED_KEY = "update_associated_key.wasm"


# TEST Account Use Notes
# test_transfer_to_accounts: 300, 299, 298
# test_transfer_with_overdraft: 297, 296
# test_bonding: 295

MAX_PAYMENT_COST = 10000000
DEFAULT_PAYMENT_COST = 100000000
INITIAL_MOTES_AMOUNT = 10 ** 20
MAX_PAYMENT_ABI = ABI.args([ABI.big_int("amount", MAX_PAYMENT_COST)])
DEFAULT_PAYMENT_ABI = ABI.args([ABI.big_int("amount", DEFAULT_PAYMENT_COST)])
CONV_RATE = 10
TEST_ACCOUNT_INITIAL_BALANCE = 1000000000
USER_ERROR_MIN = 65536

BOOTSTRAP_PATH = "/root/.casperlabs/bootstrap"

# Empty /etc/casperlabs means it has no chainspec.
# This is a directory in resources that will be mounted
# as /etc/casperlabs in the node's docker container.
EMPTY_ETC_CASPERLABS = "etc_casperlabs_empty"


@dataclasses.dataclass(eq=True, frozen=True)
class KeyPair:
    private_key: str
    public_key: str


def testing_root_path() -> Path:
    cur_path = Path(os.path.realpath(__file__)).parent
    return cur_path.parent


def resources_path() -> Path:
    return testing_root_path() / "resources"


def random_string(length: int) -> str:
    return "".join(random.choice(string.ascii_letters) for _ in range(length)).lower()


def make_tempfile(prefix: str, content: str) -> str:
    fd, path = tempfile.mkstemp(dir="/tmp", prefix=prefix)

    with os.fdopen(fd, "w") as tmp:
        tmp.write(content)

    return path


def make_tempdir(prefix: str) -> str:
    return tempfile.mkdtemp(dir="/tmp", prefix=prefix)


def extract_block_count_from_show_blocks(show_blocks_output: str) -> int:
    lines = show_blocks_output.splitlines()
    prefix = "count: "
    interesting_lines = [l for l in lines if l.startswith(prefix)]
    if len(interesting_lines) != 1:
        raise UnexpectedShowBlocksOutputFormatError(show_blocks_output)
    line = interesting_lines[0]
    count = line[len(prefix) :]
    try:
        result = int(count)
    except ValueError:
        raise UnexpectedShowBlocksOutputFormatError(show_blocks_output)
    return result


def extract_deploy_hash_from_deploy_output(deploy_output: str) -> str:
    """Success! Deploy [hex_hash] deployed."""
    match = re.match(r"Success! Deploy ([0-9a-f]+) deployed.", deploy_output.strip())
    if match is None:
        raise UnexpectedProposeOutputFormatError(deploy_output)
    return match.group(1)


def public_key_hash_hex(public_key_base64: str) -> str:
    alg = b"ED25519"
    bytes = bytearray(alg)
    bytes.extend([0])
    bytes.extend(base64.b64decode(public_key_base64))
    return blake2b_hash(bytes).hex()