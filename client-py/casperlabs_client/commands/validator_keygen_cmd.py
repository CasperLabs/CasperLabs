from pathlib import Path
from typing import Dict

from casperlabs_client import CasperLabsClient
from casperlabs_client.commands.common_options import DIRECTORY_FOR_WRITE_OPTION
from casperlabs_client.decorators import guarded_command

NAME: str = "validator-keygen"
HELP: str = """Generate validator and node keys.

Usage: casperlabs-client keygen <existing output directory>
Command will override existing files!
Generated files:
   node-id               # node ID as in casperlabs://c0a6c82062461c9b7f9f5c3120f44589393edf31@<NODE ADDRESS>?protocol=40400&discovery=40404
                         # derived from node.key.pem
   node.certificate.pem  # TLS certificate used for node-to-node interaction encryption
                         # derived from node.key.pem
   node.key.pem          # secp256r1 private key
   validator-id          # validator ID in Base64 format; can be used in accounts.csv
                         # derived from validator.public.pem
   validator-id-hex      # validator ID in hex, derived from validator.public.pem
   validator-private.pem # ed25519 private key
   validator-public.pem  # ed25519 public key"""
OPTIONS = (DIRECTORY_FOR_WRITE_OPTION,)


@guarded_command
def method(casperlabs_client: CasperLabsClient, args: Dict):
    directory = Path(args.get("directory")).resolve()
    casperlabs_client.validator_keygen(directory)
    print(f"Keys successfully created in directory: {str(directory.absolute())}")
