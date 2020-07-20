#!/usr/bin/env python3
"""
CasperLabs Client API library and command line tool.
"""

from pathlib import Path

# Hack to fix the relative imports problems #
import sys

sys.path.append(str(Path(__file__).resolve().parents[1]))
# end of hack #
import os
import time
import grpc
from grpc._channel import _Rendezvous
import functools
import logging
import tempfile

# Monkey patching of google.protobuf.text_encoding.CEscape
# to get keys and signatures in hex when printed
import google.protobuf.text_format

CEscape = google.protobuf.text_format.text_encoding.CEscape


def _hex(text, as_utf8):
    try:
        return (len(text) in (32, 64, 20)) and text.hex() or CEscape(text, as_utf8)
    except TypeError:
        return CEscape(text, as_utf8)


google.protobuf.text_format.text_encoding.CEscape = _hex

# ~/CasperLabs/protobuf/io/casperlabs/node/api/control.proto
from . import control_pb2_grpc
from . import control_pb2 as control

# ~/CasperLabs/protobuf/io/casperlabs/node/api/casper.proto
from . import casper_pb2 as casper
from . import casper_pb2_grpc

# ~/CasperLabs/protobuf/io/casperlabs/casper/consensus/info.proto
from . import info_pb2 as info

# ~/CasperLabs/protobuf/io/casperlabs/node/api/diagnostics.proto
from . import diagnostics_pb2_grpc
from . import empty_pb2

from . import vdag
from casperlabs_client.utils import (
    extract_common_name,
    key_variant,
    make_deploy,
    sign_deploy,
    get_public_key,
)


DEFAULT_HOST = "localhost"
DEFAULT_PORT = 40401
DEFAULT_INTERNAL_PORT = 40402


class InternalError(Exception):
    """
    The only exception that API calls can throw.
    Internal errors like gRPC exceptions will be caught
    and this exception thrown instead, so the user does
    not have to worry about handling any other exceptions.
    """

    def __init__(self, status="", details=""):
        super(InternalError, self).__init__()
        self.status = status
        self.details = details

    def __str__(self):
        return f"{self.status}: {self.details}"


def api(function):
    """
    Decorator of API functions that protects user code from
    unknown exceptions raised by gRPC or internal API errors.
    It will catch all exceptions and throw InternalError.

    :param function: function to be decorated
    :return:
    """

    @functools.wraps(function)
    def wrapper(*args, **kwargs):
        try:
            return function(*args, **kwargs)
        except (SyntaxError, TypeError, InternalError):
            raise
        except _Rendezvous as e:
            raise InternalError(str(e.code()), e.details())
        except Exception as e:
            raise InternalError(details=str(e)) from e

    return wrapper


NUMBER_OF_RETRIES = 5

# Initial delay in seconds before an attempt to retry
INITIAL_DELAY = 0.3


def retry_wrapper(function, *args):
    delay = INITIAL_DELAY
    for i in range(NUMBER_OF_RETRIES):
        try:
            return function(*args)
        except _Rendezvous as e:
            if e.code() == grpc.StatusCode.UNAVAILABLE and i < NUMBER_OF_RETRIES - 1:
                delay += delay
                logging.warning(f"Retrying after {e} in {delay} seconds")
                time.sleep(delay)
            else:
                raise


def retry_unary(function):
    @functools.wraps(function)
    def wrapper(*args):
        return retry_wrapper(function, *args)

    return wrapper


def retry_stream(function):
    @functools.wraps(function)
    def wrapper(*args):
        yield from retry_wrapper(function, *args)

    return wrapper


class InsecureGRPCService:
    def __init__(self, host, port, serviceStub):
        self.address = f"{host}:{port}"
        self.serviceStub = serviceStub

    def __getattr__(self, name):

        logging.debug(
            f"Creating insecure connection to {self.address} ({self.serviceStub})"
        )

        @retry_unary
        def unary_unary(*args):
            logging.debug(
                f"Insecure {self.address} ({self.serviceStub}): {name} {list(args)}"
            )
            with grpc.insecure_channel(self.address) as channel:
                return getattr(self.serviceStub(channel), name)(*args)

        @retry_stream
        def unary_stream(*args):
            logging.debug(
                f"Insecure {self.address} ({self.serviceStub}): {name} {list(args)}"
            )
            with grpc.insecure_channel(self.address) as channel:
                yield from getattr(self.serviceStub(channel), name[: -len("_stream")])(
                    *args
                )

        return name.endswith("_stream") and unary_stream or unary_unary


class SecureGRPCService:
    def __init__(self, host, port, serviceStub, node_id, certificate_file):
        self.address = f"{host}:{port}"
        self.serviceStub = serviceStub
        self.node_id = node_id or extract_common_name(certificate_file)
        self.certificate_file = certificate_file
        with open(self.certificate_file, "rb") as f:
            self.credentials = grpc.ssl_channel_credentials(f.read())
        self.secure_channel_options = self.node_id and (
            ("grpc.ssl_target_name_override", self.node_id),
            ("grpc.default_authority", self.node_id),
        )

    def __getattr__(self, name):
        logging.debug(
            f"Creating secure connection to {self.address} ({self.serviceStub})"
        )

        @retry_unary
        def unary_unary(*args):
            with grpc.secure_channel(
                self.address, self.credentials, options=self.secure_channel_options
            ) as channel:
                return getattr(self.serviceStub(channel), name)(*args)

        @retry_stream
        def unary_stream(*args):
            with grpc.secure_channel(
                self.address, self.credentials, options=self.secure_channel_options
            ) as channel:
                yield from getattr(self.serviceStub(channel), name[: -len("_stream")])(
                    *args
                )

        return name.endswith("_stream") and unary_stream or unary_unary


class CasperLabsClient:
    """
    gRPC CasperLabs client.
    """

    DEPLOY_STATUS_CHECK_DELAY = 0.5
    DEPLOY_STATUS_TIMEOUT = 180  # 3 minutes

    def __init__(
        self,
        host: str = DEFAULT_HOST,
        port: int = DEFAULT_PORT,
        port_internal: int = DEFAULT_INTERNAL_PORT,
        node_id: str = None,
        certificate_file: str = None,
    ):
        """
        CasperLabs client's constructor.

        :param host:            Hostname or IP of node on which gRPC service is running
        :param port:            Port used for external gRPC API
        :param port_internal:   Port used for internal gRPC API
        :param certificate_file:      Certificate file for TLS
        :param node_id:         node_id of the node, for gRPC encryption
        """
        self.host = host
        self.port = port
        self.port_internal = port_internal
        self.node_id = node_id
        self.certificate_file = certificate_file

        if node_id:
            self.casperService = SecureGRPCService(
                host, port, casper_pb2_grpc.CasperServiceStub, node_id, certificate_file
            )
            self.controlService = SecureGRPCService(
                # We currently assume that if node_id is given then
                # we get certificate_file too. This is unlike in the Scala client
                # where node_id is all that's needed for configuring secure connection.
                # The reason for this is that currently it doesn't seem to be possible
                # to open a secure grpc connection in Python without supplying any
                # certificate on the client side.
                host,
                port_internal,
                control_pb2_grpc.ControlServiceStub,
                node_id,
                certificate_file,
            )
            self.diagnosticsService = SecureGRPCService(
                host,
                port,
                diagnostics_pb2_grpc.DiagnosticsStub,
                node_id,
                certificate_file,
            )

        else:
            self.casperService = InsecureGRPCService(
                host, port, casper_pb2_grpc.CasperServiceStub
            )
            self.controlService = InsecureGRPCService(
                host, port_internal, control_pb2_grpc.ControlServiceStub
            )
            self.diagnosticsService = InsecureGRPCService(
                host, port, diagnostics_pb2_grpc.DiagnosticsStub
            )

    @api
    def make_deploy(
        self,
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
        return make_deploy(
            from_addr=from_addr,
            gas_price=gas_price,
            payment=payment,
            session=session,
            public_key=public_key,
            session_args=session_args,
            payment_args=payment_args,
            payment_amount=payment_amount,
            payment_hash=payment_hash,
            payment_name=payment_name,
            payment_uref=payment_uref,
            session_hash=session_hash,
            session_name=session_name,
            session_uref=session_uref,
            ttl_millis=ttl_millis,
            dependencies=dependencies,
            chain_name=chain_name,
        )

    @api
    def sign_deploy(self, deploy, public_key, private_key_file):
        return sign_deploy(deploy, public_key, private_key_file)

    @api
    def deploy(
        self,
        from_addr: bytes = None,
        gas_price: int = 10,
        payment: str = None,
        session: str = None,
        public_key: str = None,
        private_key: str = None,
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
        dependencies=None,
        chain_name: str = None,
    ):
        """
        Deploy a smart contract source file to Casper on an existing running node.
        The deploy will be packaged and sent as a block to the network depending
        on the configuration of the Casper instance.

        :param from_addr:     Purse address that will be used to pay for the deployment.
        :param gas_price:     The price of gas for this transaction in units dust/gas.
                              Must be positive integer.
        :param payment:       Path to the file with payment code.
        :param session:       Path to the file with session code.
        :param public_key:    Path to a file with public key (Ed25519)
        :param private_key:   Path to a file with private key (Ed25519)
        :param session_args:  List of ABI encoded arguments of session contract
        :param payment_args:  List of ABI encoded arguments of payment contract
        :param session-hash:  Hash of the stored contract to be called in the
                              session; base16 encoded.
        :param session-name:  Name of the stored contract (associated with the
                              executing account) to be called in the session.
        :param session-uref:  URef of the stored contract to be called in the
                              session; base16 encoded.
        :param payment-hash:  Hash of the stored contract to be called in the
                              payment; base16 encoded.
        :param payment-name:  Name of the stored contract (associated with the
                              executing account) to be called in the payment.
        :param payment-uref:  URef of the stored contract to be called in the
                              payment; base16 encoded.
        :ttl_millis:          Time to live. Time (in milliseconds) that the
                              deploy will remain valid for.
        :dependencies:        List of deploy hashes (base16 encoded) which
                              must be executed before this deploy.
        :chain_name:          Name of the chain to optionally restrict the
                              deploy from being accidentally included
                              anywhere else.
        :return:              deploy hash in base16 format
        """

        deploy = self.make_deploy(
            from_addr=from_addr,
            gas_price=gas_price,
            payment=payment,
            session=session,
            public_key=public_key,
            session_args=session_args,
            payment_args=payment_args,
            payment_amount=payment_amount,
            payment_hash=payment_hash,
            payment_name=payment_name,
            payment_uref=payment_uref,
            session_hash=session_hash,
            session_name=session_name,
            session_uref=session_uref,
            ttl_millis=ttl_millis,
            dependencies=dependencies,
            chain_name=chain_name,
        )

        deploy = self.sign_deploy(
            deploy, get_public_key(public_key, from_addr, private_key), private_key
        )
        self.send_deploy(deploy)
        return deploy.deploy_hash.hex()

    @api
    def send_deploy(self, deploy):
        self.casperService.Deploy(casper.DeployRequest(deploy=deploy))

    @api
    def showBlocks(self, depth: int = 1, max_rank=0, full_view=True):
        """
        Get slices of the DAG, going backwards, rank by rank.

        :param depth:     How many of the top ranks of the DAG to show.
        :param max_rank:  Maximum rank to go back from.
                          0 means go from the current tip of the DAG.
        :param full_view: Full view if True, otherwise basic.
        :return:          Generator of block info objects.
        """
        yield from self.casperService.StreamBlockInfos_stream(
            casper.StreamBlockInfosRequest(
                depth=depth,
                max_rank=max_rank,
                view=(
                    full_view and info.BlockInfo.View.FULL or info.BlockInfo.View.BASIC
                ),
            )
        )

    @api
    def showBlock(self, block_hash_base16: str, full_view=True):
        """
        Returns object describing a block known by Casper on an existing running node.

        :param block_hash_base16: hash of the block to be retrieved
        :param full_view:         full view if True, otherwise basic
        :return:                  object representing the retrieved block
        """
        return self.casperService.GetBlockInfo(
            casper.GetBlockInfoRequest(
                block_hash_base16=block_hash_base16,
                view=(
                    full_view and info.BlockInfo.View.FULL or info.BlockInfo.View.BASIC
                ),
            )
        )

    @api
    def propose(self):
        """"
        THIS METHOD IS DEPRECATED! It will be removed soon.

        Propose a block using deploys in the pool.

        :return:    response object with block_hash
        """
        return self.controlService.Propose(control.ProposeRequest())

    @api
    def visualizeDag(
        self,
        depth: int,
        out: str = None,
        show_justification_lines: bool = False,
        stream: str = None,
        delay_in_seconds=5,
    ):
        """
        Generate DAG in DOT format.

        :param depth:                     depth in terms of block height
        :param out:                       output image filename, outputs to stdout if
                                          not specified, must end with one of the png,
                                          svg, svg_standalone, xdot, plain, plain_ext,
                                          ps, ps2, json, json0
        :param show_justification_lines:  if justification lines should be shown
        :param stream:                    subscribe to changes, 'out' has to specified,
                                          valid values are 'single-output', 'multiple-outputs'
        :param delay_in_seconds:          delay in seconds when polling for updates (streaming)
        :return:                          Yields generated DOT source or file name when out provided.
                                          Generates endless stream of file names if stream is not None.
        """
        block_infos = list(self.showBlocks(depth, full_view=False))
        dot_dag_description = vdag.generate_dot(block_infos, show_justification_lines)
        if not out:
            yield dot_dag_description
            return

        parts = out.split(".")
        file_format = parts[-1]
        file_name_base = ".".join(parts[:-1])

        iteration = -1

        def file_name():
            nonlocal iteration, file_format
            if not stream or stream == "single-output":
                return out
            else:
                iteration += 1
                return f"{file_name_base}_{iteration}.{file_format}"

        yield self._call_dot(dot_dag_description, file_name(), file_format)
        previous_block_hashes = set(b.summary.block_hash for b in block_infos)
        while stream:
            time.sleep(delay_in_seconds)
            block_infos = list(self.showBlocks(depth, full_view=False))
            block_hashes = set(b.summary.block_hash for b in block_infos)
            if block_hashes != previous_block_hashes:
                dot_dag_description = vdag.generate_dot(
                    block_infos, show_justification_lines
                )
                yield self._call_dot(dot_dag_description, file_name(), file_format)
                previous_block_hashes = block_hashes

    def _call_dot(self, dot_dag_description, file_name, file_format):
        with tempfile.NamedTemporaryFile(mode="w") as f:
            f.write(dot_dag_description)
            f.flush()
            cmd = f"dot -T{file_format} -o {file_name} {f.name}"
            rc = os.system(cmd)
            if rc:
                raise Exception(f"Call to dot ({cmd}) failed with error code {rc}")
        print(f"Wrote {file_name}")
        return file_name

    @api
    def queryState(self, blockHash: str, key: str, path: str, keyType: str):
        """
        Query a value in the global state.

        :param blockHash:         Hash of the block to query the state of
        :param key:               Base16 encoding of the base key
        :param path:              Path to the value to query. Must be of the form
                                  'key1/key2/.../keyn'
        :param keyType:           Type of base key. Must be one of 'hash', 'uref', 'address' or 'local'.
                                  For 'local' key type, 'key' value format is {seed}:{rest},
                                  where both parts are hex encoded."
        :return:                  QueryStateResponse object
        """
        q = casper.StateQuery(key_variant=key_variant(keyType), key_base16=key)
        q.path_segments.extend([name for name in path.split("/") if name])
        return self.casperService.GetBlockState(
            casper.GetBlockStateRequest(block_hash_base16=blockHash, query=q)
        )

    @api
    def balance(self, address: str, block_hash: str):
        value = self.queryState(block_hash, address, "", "address")
        account = None
        try:
            account = value.account
        except AttributeError:
            return InternalError(
                "balance", f"Expected Account type value under {address}."
            )

        urefs = [u for u in account.named_keys if u.name == "mint"]
        if len(urefs) == 0:
            raise InternalError(
                "balance",
                "Account's named_keys map did not contain Mint contract address.",
            )

        mintPublic = urefs[0]

        mintPublicHex = mintPublic.key.uref.uref.hex()
        purseAddrHex = account.main_purse.uref.hex()
        localKeyValue = f"{mintPublicHex}:{purseAddrHex}"

        balanceURef = self.queryState(block_hash, localKeyValue, "", "local")
        balanceURefHex = balanceURef.cl_value.value.key.uref.uref.hex()
        balance = self.queryState(block_hash, balanceURefHex, "", "uref")
        balanceStrValue = balance.cl_value.value.u512.value
        return int(balanceStrValue)

    @api
    def showDeploy(
        self,
        deploy_hash_base16: str,
        full_view: bool = False,
        wait_for_processed: bool = False,
        delay: int = DEPLOY_STATUS_CHECK_DELAY,
        timeout_seconds: int = DEPLOY_STATUS_TIMEOUT,
    ):
        """
        Retrieve information about a single deploy by hash.
        """
        start_time = time.time()
        while True:
            deploy_info = self.casperService.GetDeployInfo(
                casper.GetDeployInfoRequest(
                    deploy_hash_base16=deploy_hash_base16,
                    view=(
                        full_view
                        and info.DeployInfo.View.FULL
                        or info.DeployInfo.View.BASIC
                    ),
                )
            )
            if (
                wait_for_processed
                and deploy_info.status.state == info.DeployInfo.State.PENDING
            ):
                if time.time() - start_time > timeout_seconds:
                    raise Exception(
                        f"Timed out waiting for deploy {deploy_hash_base16} to be processed"
                    )
                time.sleep(delay)
                continue
            return deploy_info

    @api
    def showDeploys(self, block_hash_base16: str, full_view=True):
        """
        Get the processed deploys within a block.
        """
        yield from self.casperService.StreamBlockDeploys_stream(
            casper.StreamBlockDeploysRequest(
                block_hash_base16=block_hash_base16,
                view=(
                    full_view
                    and info.DeployInfo.View.FULL
                    or info.DeployInfo.View.BASIC
                ),
            )
        )

    @api
    def stream_events(
        self,
        all: bool = False,
        block_added: bool = False,
        block_finalized: bool = False,
        deploy_added: bool = False,
        deploy_discarded: bool = False,
        deploy_requeued: bool = False,
        deploy_processed: bool = False,
        deploy_finalized: bool = False,
        deploy_orphaned: bool = False,
        account_public_keys=None,
        deploy_hashes=None,
        min_event_id: int = 0,
        max_event_id: int = 0,
    ):
        """
        See StreamEventsRequest in
            ~/CasperLabs/protobuf/io/casperlabs/node/api/casper.proto
        for description of types of events.

        Note, you must subscribe to some events (pass True to some keywords other than account_public_keys or deploy_hashes)
        otherwise this generator will block forever.
        """
        if all:
            block_added = True
            block_finalized = True
            deploy_added = True
            deploy_discarded = True
            deploy_requeued = True
            deploy_processed = True
            deploy_finalized = True
            deploy_orphaned = True

        yield from self.casperService.StreamEvents_stream(
            casper.StreamEventsRequest(
                block_added=block_added,
                block_finalized=block_finalized,
                deploy_added=deploy_added,
                deploy_discarded=deploy_discarded,
                deploy_requeued=deploy_requeued,
                deploy_processed=deploy_processed,
                deploy_finalized=deploy_finalized,
                deploy_orphaned=deploy_orphaned,
                deploy_filter=casper.StreamEventsRequest.DeployFilter(
                    account_public_keys=[
                        bytes.fromhex(pk) for pk in account_public_keys or []
                    ],
                    deploy_hashes=[bytes.fromhex(h) for h in deploy_hashes or []],
                ),
                min_event_id=min_event_id,
                max_event_id=max_event_id,
            )
        )

    @api
    def wait_for_deploy_processed(
        self,
        deploy_hash,
        on_error_raise=True,
        delay=DEPLOY_STATUS_CHECK_DELAY,
        timeout_seconds=DEPLOY_STATUS_TIMEOUT,
    ):
        start_time = time.time()
        result = None
        while True:
            if time.time() - start_time > timeout_seconds:
                raise Exception(
                    f"Timed out waiting for deploy {deploy_hash} to be processed"
                )

            result = self.showDeploy(deploy_hash, full_view=False)
            if result.status.state != info.DeployInfo.State.PENDING:
                break
            time.sleep(delay)

        if on_error_raise:
            if len(result.processing_results) == 0:
                raise Exception(f"Deploy {deploy_hash} status: {result.status}")

            last_processing_result = result.processing_results[0]
            if last_processing_result.is_error:
                raise Exception(
                    f"Deploy {deploy_hash} execution error: {last_processing_result.error_message}"
                )
        return result

    @api
    def show_peers(self):
        return list(self.diagnosticsService.ListPeers(empty_pb2.Empty()).peers)

    def cli(self, *arguments) -> int:
        from . import cli

        return cli(
            "--host",
            self.host,
            "--port",
            self.port,
            "--port-internal",
            self.port_internal,
            *arguments,
        )
