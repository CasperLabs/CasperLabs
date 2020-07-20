from dataclasses import dataclass
from typing import Dict, Union

from . import consensus_pb2 as consensus, consts, key_holders
import time

from casperlabs_client import crypto
from .contract import PaymentCode, SessionCode
from .key_holders import ED25519Key, SECP256K1Key


def sign_deploy(deploy, key_holder):
    signature_bytes = key_holder.sign(deploy.deploy_hash)
    signature = consensus.Signature(
        sig_algorithm=key_holder.algorithm.lower(), sig=signature_bytes
    )
    deploy.approvals.extend(
        [
            consensus.Approval(
                approver_public_key=key_holder.public_key, signature=signature
            )
        ]
    )
    return deploy


@dataclass
class DeployData:
    from_addr: bytes
    payment_code: PaymentCode
    session_code: SessionCode
    ttl_millis: int = 0
    dependencies: list = None
    chain_name: str = None
    key_holder: Union[ED25519Key, SECP256K1Key] = None

    @staticmethod
    def from_args(args: Dict) -> "DeployData":
        """
        Build Deploy data class from command line arguments, this includes testing for combinations of args
        that are allowed or required.
        """

        # `from` isn't good for dict creation, but used from CLI, so handle both `from` and `from_addr`
        from_addr = args.get("from", args.get("from_addr"))
        if from_addr and not isinstance(from_addr, bytes):
            from_addr = bytes.fromhex(from_addr)

        payment_code = PaymentCode.from_args(args)
        session_code = SessionCode.from_args(args)

        ttl_millis = args.get("ttl_millis")
        dependencies_hex = args.get("dependencies") or []
        dependencies = [bytes.fromhex(d) for d in dependencies_hex]
        chain_name = args.get("chain_name", "")
        algorithm = args.get("algorithm")
        private_key_pem_path = args.get("private_key")
        public_key_pem_path = args.get("public_key")
        if private_key_pem_path or public_key_pem_path:
            key_holder = key_holders.key_holder_object(
                algorithm=algorithm,
                private_key_pem_path=private_key_pem_path,
                public_key_pem_path=public_key_pem_path,
            )
        else:
            key_holder = None

        if not from_addr:
            if not key_holder:
                raise ValueError(
                    "Must provide `from` or a key to calculate account hash."
                )
            from_addr = key_holder.account_hash

        deploy = DeployData(
            from_addr=from_addr,
            payment_code=payment_code,
            session_code=session_code,
            ttl_millis=ttl_millis,
            dependencies=dependencies,
            chain_name=chain_name,
            key_holder=key_holder,
        )

        if len(deploy.from_addr) != consts.ACCOUNT_HASH_LENGTH:
            raise Exception(
                "--from must be 32 bytes encoded as 64 characters long hexadecimal"
            )

        return deploy

    def make_protobuf(self) -> consensus.Deploy:
        """
        Create a protobuf deploy object. See deploy for description of parameters.
        """
        if len(self.from_addr) != 32:
            raise Exception("from_addr must be 32 bytes")

        self.payment_code.validate()
        self.session_code.validate()

        body = consensus.Deploy.Body(
            session=self.session_code.to_protobuf(),
            payment=self.payment_code.to_protobuf(),
        )
        body_hash = crypto.blake2b_hash((body.SerializeToString()))

        header = consensus.Deploy.Header(
            account_public_key_hash=self.from_addr,
            timestamp=int(1000 * time.time()),
            body_hash=body_hash,
            ttl_millis=self.ttl_millis,
            dependencies=self.dependencies,
            chain_name=self.chain_name,
        )

        deploy_hash = crypto.blake2b_hash(header.SerializeToString())

        return consensus.Deploy(deploy_hash=deploy_hash, header=header, body=body)
