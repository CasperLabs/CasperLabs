import sys
from typing import Dict

from casperlabs_client import io, CasperLabsClient
from casperlabs_client.commands.common_options import public_key_option, DEPLOY_OPTIONS
from casperlabs_client.decorators import guarded_command


NAME: str = "make-deploy"
HELP: str = "Constructs a deploy that can be signed and sent to a node."
OPTIONS = [
    [
        ("-o", "--deploy-path"),
        dict(
            required=False,
            help=(
                "Path to the file where deploy will be saved. "
                "Optional, if not provided the deploy will be printed to STDOUT."
            ),
        ),
    ],
    public_key_option(required=False),
] + DEPLOY_OPTIONS


@guarded_command
def method(casperlabs_client: CasperLabsClient, args: Dict):
    deploy = casperlabs_client.make_deploy(
        from_addr=args.get("from"),
        payment=args.get("payment"),
        session=args.get("session"),
        public_key=args.get("public_key"),
        session_args=args.get("session_args"),
        payment_args=args.get("payment_args"),
        payment_amount=args.get("payment_amount"),
        payment_hash=args.get("payment_hash"),
        payment_name=args.get("payment_name"),
        payment_package_hash=args.get("payment_package_hash"),
        payment_package_name=args.get("payment_package_name"),
        session_hash=args.get("session_hash"),
        session_name=args.get("session_name"),
        session_package_hash=args.get("session_package_hash"),
        session_package_name=args.get("session_package_name"),
        ttl_millis=args.get("ttl_millis"),
        dependencies=args.get("dependencies"),
        chain_name=args.get("chain_name"),
        algorithm=args.get("algorithm"),
    )
    data = deploy.SerializeToString()
    if not args.get("deploy_path"):
        sys.stdout.buffer.write(data)
    else:
        io.write_binary_file(args.get("deploy_path"), data)
