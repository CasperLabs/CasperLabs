from typing import Dict

from casperlabs_client import consts, CasperLabsClient, reformat
from casperlabs_client.decorators import guarded_command

NAME: str = "show-deploy"
HELP: str = "View properties of a deploy known by Casper on an existing running node."
OPTIONS = [
    [("hash",), dict(type=str, help="Value of the deploy hash, base16 encoded.")],
    [
        ("-w", "--wait-for-processed"),
        dict(action="store_true", help="Wait for deploy status PROCESSED or DISCARDED"),
    ],
    [
        ("--timeout-seconds",),
        dict(type=int, default=consts.STATUS_TIMEOUT, help="Timeout in seconds"),
    ],
]


@guarded_command
def method(casperlabs_client: CasperLabsClient, args: Dict):
    response = casperlabs_client.show_deploy(
        args.get("hash"),
        full_view=False,
        wait_for_processed=args.get("wait_for_processed", False),
        timeout_seconds=args.get("timeout_seconds", consts.STATUS_TIMEOUT),
    )
    print(reformat.hexify(response))
