from pathlib import Path

from casperlabs_local_net.common import public_key_hash_hex
from casperlabs_local_net.docker_base import LoggingDockerBase
from casperlabs_local_net.docker_config import DockerConfig


class DockerClarity(LoggingDockerBase):
    def __init__(self, config: DockerConfig, grpc_proxy_name: str):
        self.grpc_proxy_name = grpc_proxy_name
        super().__init__(config)

    @property
    def container_type(self) -> str:
        return "explorer"

    @property
    def faucet_account_path(self) -> Path:
        return Path(self.host_mount_dir) / "faucet-account"

    @property
    def faucet_account_public_key_hash(self) -> str:
        public_key_path = self.faucet_account_path / "account-id"
        with public_key_path.open() as f:
            public_key_base64 = f.readline()
            return public_key_hash_hex(public_key_base64)

    @property
    def volumes(self) -> dict:
        account_public_key = self.faucet_account_path / "account-public.pem"
        account_private_key = self.faucet_account_path / "account-private.pem"

        return {
            str(account_public_key.absolute()): {
                "bind": "/app/keys/public.key",
                "mode": "ro",
            },
            str(account_private_key.absolute()): {
                "bind": "/app/keys/private.key",
                "mode": "ro",
            },
        }

    @property
    def environment(self) -> dict:
        return {
            "FAUCET_ACCOUNT_PUBLIC_KEY_PATH": "/app/keys/public.key",
            "FAUCET_ACCOUNT_PRIVATE_KEY_PATH": "/app/keys/private.key",
            "CASPER_SERVICE_URL": f"http://{self.grpc_proxy_name}:8401",
            "SERVER_PORT": "8080",
            "SERVER_USE_TLS": "false",
            "UI_GRPC_URL": f"http://{self.grpc_proxy_name}:8401",
            "AUTH_MOCK_ENABLED": "true",  # Enable Auth0 mock service
        }

    def _get_container(self):
        container = self.config.docker_client.containers.run(
            self.image_name,
            name=self.container_name,
            user="root",
            detach=True,
            network=self.config.network,
            volumes=self.volumes,
            hostname=self.container_name,
            environment=self.environment,
            ports={"8080/tcp": 8080},
        )
        return container


class DockerGrpcWebProxy(LoggingDockerBase):
    """
    Allow browsers call grpc service directly
    This container exposes port 8401.

    browser(or headless selenium)  -> grpcwebproxy  -> node
                                  8401            40401(via docker hostname)
    """

    def __init__(self, config: DockerConfig, docker_node_name: str):
        self.docker_node_name = docker_node_name
        super().__init__(config)

    @property
    def container_type(self) -> str:
        return "grpcwebproxy"

    @property
    def grpc_web_proxy_path(self) -> Path:
        return Path(self.host_mount_dir) / "grpcwebproxy"

    @property
    def volumes(self) -> dict:
        tls_certificate = self.grpc_web_proxy_path / "certificate.pem"
        tls_key = self.grpc_web_proxy_path / "key.pem"
        return {
            str(tls_certificate.absolute()): {
                "bind": "/etc/tls/certificate.pem",
                "mode": "ro",
            },
            str(tls_key.absolute()): {"bind": "/etc/tls/key.pem", "mode": "ro"},
        }

    @property
    def command(self) -> str:
        return (
            "/grpcwebproxy"
            f" --backend_addr={self.docker_node_name}:40401"
            f" --backend_tls={self.config.grpc_encryption}"
            " --backend_tls_noverify"
            " --backend_max_call_recv_msg_size=16777216"
            " --allow_all_origins"
            " --server_tls_cert_file=/etc/tls/certificate.pem"
            " --server_tls_key_file=/etc/tls/key.pem"
            " --server_http_debug_port 8401"
        )

    def _get_container(self):
        container = self.config.docker_client.containers.run(
            self.image_name,
            name=self.container_name,
            user="root",
            detach=True,
            command=self.command,
            network=self.config.network,
            volumes=self.volumes,
            hostname=self.container_name,
            ports={"8401/tcp": 8401},
        )

        return container


class DockerSelenium(LoggingDockerBase):
    """
    see https://github.com/SeleniumHQ/docker-selenium
    """

    @property
    def container_type(self) -> str:
        return "selenium"

    @property
    def image_name(self) -> str:
        return "selenium/standalone-chrome:3.141.59-xenon"

    @property
    def volumes(self) -> dict:
        return {"/dev/shm": {"bind": "/dev/shm", "mode": "rw"}}

    def _get_container(self):
        container = self.config.docker_client.containers.run(
            self.image_name,
            name=self.container_name,
            user="root",
            detach=True,
            network=self.config.network,
            volumes=self.volumes,
            hostname=self.container_name,
            ports={"4444/tcp": 4444},
        )
        return container
