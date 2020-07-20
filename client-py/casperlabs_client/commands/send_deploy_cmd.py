from typing import Dict

from casperlabs_client import CasperLabsClient
from casperlabs_client.decorators import guarded_command


NAME: str = "send-deploy"
HELP: str = (
    "Deploy a smart contract source file to Casper on an existing running node. "
    "The deploy will be packaged and sent as a block to the network depending on "
    "the configuration of the Casper instance."
)
OPTIONS = [
    [
        ("-i", "--deploy-path"),
        dict(required=False, default=None, help="Path to the file with signed deploy."),
    ]
]


@guarded_command
def method(casperlabs_client: CasperLabsClient, args: Dict):
    deploy_hash = casperlabs_client.send_deploy(deploy_file=args.get("deploy_path"))
    print(f"Success! Deploy {deploy_hash} deployed")
