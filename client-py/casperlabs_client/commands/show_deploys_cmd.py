from typing import Dict

from casperlabs_client.decorators import guarded_command
from casperlabs_client import io, CasperLabsClient

NAME: str = "show-deploys"
HELP: str = "View deploys included in a block."
OPTIONS = [[("hash",), dict(type=str, help="Value of the block hash, base16 encoded.")]]


@guarded_command
def method(casperlabs_client: CasperLabsClient, args: Dict):
    response = casperlabs_client.show_deploys(args.get("hash"), full_view=False)
    io.print_blocks(response, element_name="deploy")
