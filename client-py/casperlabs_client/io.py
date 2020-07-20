import base64
from os.path import dirname, realpath
from typing import Union
from pathlib import Path

from casperlabs_client import consensus_pb2 as consensus
from casperlabs_client.reformat import hexify


def read_file(file_path: Union[Path, str]) -> str:
    with open(file_path, "r") as f:
        return f.read()


def read_binary_file(file_path: Union[Path, str]) -> bytes:
    with open(file_path, "rb") as f:
        return f.read()


def read_deploy_file(file_path: Union[Path, str]) -> consensus.Deploy:
    deploy = consensus.Deploy()
    file_contents = read_binary_file(file_path)
    deploy.ParseFromString(file_contents)
    return deploy


def write_file(file_path: Union[Path, str], text: str) -> None:
    with open(file_path, "w") as f:
        f.write(text)


def write_binary_file(file_path: Union[Path, str], data: bytes) -> None:
    with open(file_path, "wb") as f:
        f.write(data)


def read_pem_key(file_path: str) -> bytes:
    """ Read pem file and parse key

    :param file_path: Path to pem file
    """

    def is_key_line(line) -> bool:
        return line and not line.startswith("----")

    with open(file_path) as f:
        key_line = [line for line in f.readlines() if is_key_line(line)][0].strip()
        key_bytes = base64.b64decode(key_line)
        if len(key_bytes) % 32 == 0:
            return key_bytes[:32]
        else:
            return key_bytes[-32:]


def read_version() -> str:
    version_path = Path(dirname(realpath(__file__))) / "VERSION"
    return read_file(version_path).strip()


def print_blocks(response, element_name="block"):
    count = 0
    for block in response:
        print(f"------------- {element_name} {count} ---------------")
        print_block(block)
        print("-----------------------------------------------------\n")
        count += 1
    print("count:", count)


def print_block(block):
    print(hexify(block))
