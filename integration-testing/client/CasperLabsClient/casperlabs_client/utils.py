import time
import ssl
import google.protobuf.text_format
import google.protobuf.json_format
from . import abi
from . import casper_pb2 as casper
from . import consensus_pb2 as consensus
from . import crypto


def _read_binary(file_name: str):
    with open(file_name, "rb") as f:
        return f.read()


def hexify(o):
    """
    Convert protobuf message to text format with cryptographic keys and signatures in base 16.
    """
    return google.protobuf.text_format.MessageToString(o)


def jsonify(o):
    return google.protobuf.json_format.MessageToJson(o)


def extract_common_name(certificate_file: str) -> str:
    cert_dict = ssl._ssl._test_decode_cert(certificate_file)
    return [t[0][1] for t in cert_dict["subject"] if t[0][0] == "commonName"][0]


# Note, there is also casper.StateQuery.KeyVariant.KEY_VARIANT_UNSPECIFIED,
# but it doesn't seem to have an official string representation
# ("key_variant_unspecified"? "unspecified"?) and is not used by the client.
STATE_QUERY_KEY_VARIANT = {
    "hash": casper.StateQuery.KeyVariant.HASH,
    "uref": casper.StateQuery.KeyVariant.UREF,
    "address": casper.StateQuery.KeyVariant.ADDRESS,
    "local": casper.StateQuery.KeyVariant.LOCAL,
}


def key_variant(key_type):
    """
    Returns query-state key variant.
    """
    variant = STATE_QUERY_KEY_VARIANT.get(key_type.lower(), None)
    if variant is None:
        raise Exception(f"{key_type} is not a known query-state key type")
    return variant


def _encode_contract(contract_options, contract_args):
    file_name, hash, name, uref = contract_options
    Code = consensus.Deploy.Code
    if file_name:
        return Code(wasm=_read_binary(file_name), args=contract_args)
    if hash:
        return Code(hash=hash, args=contract_args)
    if name:
        return Code(name=name, args=contract_args)
    if uref:
        return Code(uref=uref, args=contract_args)
    return Code(args=contract_args)


def _serialize(o) -> bytes:
    return o.SerializeToString()


def make_deploy(
    from_addr: bytes = None,
    gas_price: int = 10,
    payment: str = None,
    session: str = None,
    public_key: str = None,
    session_args: bytes = None,
    payment_args: bytes = None,
    payment_amount: int = None,
    payment_hash: bytes = None,
    payment_name: str = None,
    payment_uref: bytes = None,
    session_hash: bytes = None,
    session_name: str = None,
    session_uref: bytes = None,
    ttl_millis: int = 0,
    dependencies: list = None,
    chain_name: str = None,
):
    """
    Create a protobuf deploy object. See deploy for description of parameters.
    """
    # Convert from hex to binary.
    if from_addr and len(from_addr) == 64:
        from_addr = bytes.fromhex(from_addr)

    if from_addr and len(from_addr) != 32:
        raise Exception("from_addr must be 32 bytes")

    if payment_amount:
        payment_args = abi.ABI.args([abi.ABI.big_int("amount", int(payment_amount))])

    session_options = (session, session_hash, session_name, session_uref)
    payment_options = (payment, payment_hash, payment_name, payment_uref)

    if len(list(filter(None, session_options))) != 1:
        raise TypeError(
            "deploy: exactly one of session, session_hash, session_name or session_uref must be provided"
        )

    if len(list(filter(None, payment_options))) > 1:
        raise TypeError(
            "deploy: only one of payment, payment_hash, payment_name or payment_uref can be provided"
        )

    # session_args must go to payment as well for now cause otherwise we'll get GASLIMIT error,
    # if payment is same as session:
    # https://github.com/CasperLabs/CasperLabs/blob/dev/casper/src/main/scala/io/casperlabs/casper/util/ProtoUtil.scala#L463
    body = consensus.Deploy.Body(
        session=_encode_contract(session_options, session_args),
        payment=_encode_contract(payment_options, payment_args),
    )

    header = consensus.Deploy.Header(
        account_public_key=from_addr
        or (public_key and crypto.read_pem_key(public_key)),
        timestamp=int(1000 * time.time()),
        gas_price=gas_price,
        body_hash=crypto.blake2b_hash(_serialize(body)),
        ttl_millis=ttl_millis,
        dependencies=dependencies and [bytes.fromhex(d) for d in dependencies] or [],
        chain_name=chain_name or "",
    )

    deploy_hash = crypto.blake2b_hash(_serialize(header))

    return consensus.Deploy(deploy_hash=deploy_hash, header=header, body=body)


def sign_deploy(deploy, public_key, private_key_file):
    # See if this is hex encoded
    try:
        public_key = bytes.fromhex(public_key)
    except TypeError:
        pass

    deploy.approvals.extend(
        [
            consensus.Approval(
                approver_public_key=public_key,
                signature=crypto.signature(private_key_file, deploy.deploy_hash),
            )
        ]
    )
    return deploy


def get_public_key(public_key, from_addr, private_key):
    return (
        (public_key and crypto.read_pem_key(public_key))
        or from_addr
        or crypto.private_to_public_key(private_key)
    )
