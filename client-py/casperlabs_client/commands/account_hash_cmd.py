import sys
from typing import Dict

from casperlabs_client import CasperLabsClient, io
from casperlabs_client.commands.common_options import ALGORITHM_OPTION
from casperlabs_client.decorators import guarded_command

NAME: str = "account-hash"
HELP: str = (
    "Calculate and return the account hash from public key and algorithm pair."
    "Saves in file if path given, otherwise uses stdout."
)
OPTIONS = (
    ALGORITHM_OPTION,
    (
        ("-k", "--public-key"),
        dict(required=True, type=str, help="Path to the file with account public key"),
    ),
    (
        ("-o", "--file-path"),
        dict(
            required=False,
            help=(
                "Path to the file where account hash will be saved. "
                "Optional, if not provided the account hash will be printed to STDOUT."
            ),
        ),
    ),
)


@guarded_command
def method(casperlabs_client: CasperLabsClient, args: Dict):
    algorithm = args.get("algorithm")
    public_key_path = args.get("public_key")
    file_path = args.get("file_path")

    account_hash = casperlabs_client.account_hash(
        algorithm=algorithm, public_key_pem_path=public_key_path
    )
    account_hash_hex = account_hash.hex().encode("UTF-8")

    if file_path:
        io.write_binary_file(file_path, account_hash_hex)
    else:
        sys.stdout.buffer.write(account_hash_hex)
