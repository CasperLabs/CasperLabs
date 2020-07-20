import sys
from typing import Dict

from casperlabs_client import consensus_pb2 as consensus, io, CasperLabsClient
from casperlabs_client.commands.common_options import (
    private_key_option,
    ALGORITHM_OPTION,
)

from casperlabs_client.decorators import guarded_command

NAME: str = "sign-deploy"
HELP: str = "Cryptographically signs a deploy. The signature is appended to existing approvals."
OPTIONS = [
    [
        ("-o", "--signed-deploy-path"),
        dict(
            required=False,
            default=None,
            help=(
                "Path to the file where signed deploy will be saved. "
                "Optional, if not provided the deploy will be printed to STDOUT."
            ),
        ),
    ],
    [
        ("-i", "--deploy-path"),
        dict(required=False, default=None, help="Path to the deploy file."),
    ],
    private_key_option(required=True),
    ALGORITHM_OPTION,
]


@guarded_command
def method(casperlabs_client: CasperLabsClient, args: Dict):
    private_key = args.get("private_key")
    algorithm = args.get("algorithm")
    deploy_path = args.get("deploy_path")
    signed_deploy_path = args.get("signed_deploy_path")
    deploy = None
    if not deploy_path:
        deploy = consensus.Deploy()
        deploy.ParseFromString(sys.stdin.read())

    signed_deploy = casperlabs_client.sign_deploy(
        private_key_pem_file=private_key,
        algorithm=algorithm,
        deploy=deploy,
        deploy_file=deploy_path,
    )
    serialized_deploy = signed_deploy.SerializeToString()
    if signed_deploy_path is None:
        sys.stdout.write(serialized_deploy)
    else:
        io.write_binary_file(signed_deploy_path, serialized_deploy)
