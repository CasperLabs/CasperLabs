from typing import Dict

from casperlabs_client import consts, reformat, CasperLabsClient
from casperlabs_client.commands.common_options import private_key_option, DEPLOY_OPTIONS
from casperlabs_client.decorators import guarded_command

NAME: str = "deploy"
HELP: str = (
    "Deploy a smart contract source file to Casper on an existing running node. "
    "The deploy will be packaged and sent as a block to the network depending "
    "on the configuration of the Casper instance."
)

OPTIONS = DEPLOY_OPTIONS + [private_key_option(required=True)]


@guarded_command
def method(casperlabs_client: CasperLabsClient, args: Dict):
    deploy_hash = casperlabs_client.deploy(
        from_addr=args.get("from"),
        payment=args.get("payment"),
        session=args.get("session"),
        private_key=args.get("private_key"),
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
    print(f"Success! Deploy {deploy_hash} deployed")
    if args.get("wait_for_processed", False):
        deploy_info = casperlabs_client.show_deploy(
            deploy_hash,
            full_view=False,
            wait_for_processed=True,
            timeout_seconds=args.get("timeout_seconds", consts.STATUS_TIMEOUT),
        )
        print(reformat.hexify(deploy_info))
