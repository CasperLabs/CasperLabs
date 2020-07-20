from typing import Dict

from casperlabs_client import CasperLabsClient, io
from casperlabs_client.decorators import guarded_command

NAME: str = "show-block"
HELP: str = (
    "View properties of a block known by Casper on an existing running node. "
    "Output includes: parent hashes, storage contents of the tuplespace."
)
OPTIONS = [[("hash",), dict(type=str, help="the hash value of the block")]]


@guarded_command
def method(casperlabs_client: CasperLabsClient, args: Dict):
    response = casperlabs_client.show_block(args.get("hash"), full_view=True)
    return io.print_block(response)
