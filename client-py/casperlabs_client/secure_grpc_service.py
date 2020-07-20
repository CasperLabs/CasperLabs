import logging

import grpc

from casperlabs_client.crypto import extract_common_name
from casperlabs_client.io import read_binary_file
from casperlabs_client.decorators import retry_unary, retry_stream


class SecureGRPCService:
    def __init__(self, host, port, serviceStub, node_id, certificate_file):
        self.address = f"{host}:{port}"
        self.serviceStub = serviceStub
        self.node_id = node_id or extract_common_name(certificate_file)
        self.certificate_file = certificate_file
        file_contents = read_binary_file(self.certificate_file)
        self.credentials = grpc.ssl_channel_credentials(file_contents)
        self.secure_channel_options = (
            (
                ("grpc.ssl_target_name_override", self.node_id),
                ("grpc.default_authority", self.node_id),
            )
            if self.node_id
            else None
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

        if name.endswith("_stream"):
            return unary_stream
        else:
            return unary_unary
