import logging

import grpc

from casperlabs_client.decorators import retry_unary, retry_stream


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
