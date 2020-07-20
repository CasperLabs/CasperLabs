import base64
from typing import Dict, List

from casperlabs_client import CasperLabsClient, reformat
from casperlabs_client.decorators import guarded_command


NAME: str = "stream-events"
HELP: str = "Stream block and deploy state transition events."
OPTIONS = [
    [("--all",), dict(action="store_true", help="Subscribe to all events")],
    [("--block-added",), dict(action="store_true", help="Block added")],
    [("--block-finalized",), dict(action="store_true", help="Block finalized")],
    [("--deploy-added",), dict(action="store_true", help="Deploy added")],
    [("--deploy-discarded",), dict(action="store_true", help="Deploy discarded")],
    [("--deploy-requeued",), dict(action="store_true", help="Deploy requeued")],
    [("--deploy-processed",), dict(action="store_true", help="Deploy processed")],
    [("--deploy-finalized",), dict(action="store_true", help="Deploy finalized")],
    [("--deploy-orphaned",), dict(action="store_true", help="Deploy orphaned")],
    [
        ("-k", "--account-hash"),
        dict(
            action="append",
            help="Filter by (possibly multiple) account public key hash(es)",
        ),
    ],
    [
        ("-d", "--deploy-hash"),
        dict(action="append", help="Filter by (possibly multiple) deploy hash(es)"),
    ],
    [
        ("-f", "--format"),
        dict(
            required=False,
            default="text",
            choices=("json", "binary", "text"),
            help="Choose output format. Defaults to text representation.",
        ),
    ],
    [
        ("--min-event-id",),
        dict(
            required=False,
            default=0,
            type=int,
            help=(
                "Supports replaying events from a given ID. If the value is 0, it it will subscribe to future events; "
                "if it's non-zero, it will replay all past events from that ID, without subscribing to new. "
                "To catch up with events from the beginning, start from 1."
            ),
        ),
    ],
    [
        ("--max-event-id",),
        dict(
            required=False,
            default=0,
            type=int,
            help="Supports replaying events to a given ID.",
        ),
    ],
]


def _add_to_list(maybe_value) -> List:
    if maybe_value:
        return [maybe_value]
    else:
        return []


@guarded_command
def method(casperlabs_client: CasperLabsClient, args: Dict):
    kwargs = dict(
        account_public_key_hashes=_add_to_list(args.get("account_hash")),
        deploy_hashes=_add_to_list(args.get("deploy_hash")),
    )
    for key in (
        "all",
        "block_added",
        "block_finalized",
        "deploy_added",
        "deploy_discarded",
        "deploy_requeued",
        "deploy_processed",
        "deploy_finalized",
        "deploy_orphaned",
        "min_event_id",
        "max_event_id",
    ):
        kwargs[key] = args.get(key)
    stream = casperlabs_client.stream_events(**kwargs)

    output_format = args.get("format")
    for event in stream:
        if output_format == "binary":
            print(base64.b64encode(event.SerializeToString()).decode())
        elif output_format == "json":
            print(reformat.jsonify(event))
        else:
            print(reformat.hexify(event))
