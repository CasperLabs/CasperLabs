from typing import Dict

from casperlabs_client.decorators import guarded_command
from casperlabs_client import io, CasperLabsClient

NAME: str = "show-blocks"
HELP: str = "View list of blocks in the current Casper view on an existing running node."
OPTIONS = [
    [
        ("-d", "--depth"),
        dict(required=True, type=int, help="depth in terms of block height"),
    ]
]


@guarded_command
def method(casperlabs_client: CasperLabsClient, args: Dict):
    response = casperlabs_client.show_blocks(args.get("depth"), full_view=False)
    io.print_blocks(response)
