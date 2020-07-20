#!/usr/bin/env python3
import os
import sys
import re
import errno
import urllib.request
import shutil
from glob import glob
from pathlib import Path
from shutil import copyfile
from setuptools import setup, find_packages
from os import path
from os.path import basename, dirname, join

from setuptools.command.develop import develop as DevelopCommand
from setuptools.command.install import install as InstallCommand
from distutils.spawn import find_executable

THIS_DIRECTORY = os.path.dirname(os.path.realpath(__file__))

# Directory with Scala client's bundled contracts

PROTOBUF_DIR = f"{THIS_DIRECTORY}/../../../protobuf"
PROTO_DIR = f"{THIS_DIRECTORY}/casperlabs_client/proto"
PACKAGE_DIR = f"{THIS_DIRECTORY}/casperlabs_client"
NAME = "casperlabs_client"


def proto_compiler_check():
    proto_c = find_executable("protoc")
    if proto_c is None:
        sys.stderr.write(
            "protoc is not installed. "
            "Please install Protocol Buffers binary package for your Operating System.\n"
        )
        sys.exit(-1)


def python_compiler_check():
    if sys.version < "3.6":
        sys.stderr.write(f"{NAME} is only supported on Python versions 3.6+.\n")
        sys.exit(-1)


def make_dirs(path):
    try:
        os.makedirs(path)
    except OSError as e:
        if e.errno != errno.EEXIST:
            raise


def download(url, directory):
    make_dirs(directory)
    urllib.request.urlretrieve(url, join(directory, basename(url)))


def replace_in_place(pairs, file_name):
    import in_place

    with in_place.InPlace(file_name) as f:
        for line in f:
            for r, s in pairs:
                line = re.sub(r, s, line)
            f.write(line)


def modify_files(description, pairs, files):
    print(description, ":", files)
    for file_name in files:
        replace_in_place(pairs, file_name)


def run_protoc(file_names, PROTO_DIR=PROTO_DIR):
    import grpc_tools
    from grpc_tools import protoc

    print(f"Run protoc...: {file_names}")
    google_proto = join(dirname(grpc_tools.__file__), "_proto")
    for file_name in file_names:
        protoc.main(
            (
                "",
                f"-I{PROTO_DIR}",
                "-I" + google_proto,
                f"--python_out={PACKAGE_DIR}",
                f"--python_grpc_out={PACKAGE_DIR}",
                f"--grpc_python_out={PACKAGE_DIR}",
                file_name,
            )
        )


def collect_proto_files():
    import grpc_tools

    print("Collect files...")

    download(
        "https://raw.githubusercontent.com/scalapb/ScalaPB/master/protobuf/scalapb/scalapb.proto",
        f"{PROTO_DIR}",
    )

    copyfile(
        join(dirname(grpc_tools.__file__), "_proto/google/protobuf/empty.proto"),
        f"{PROTO_DIR}/empty.proto",
    )
    copyfile(
        join(dirname(grpc_tools.__file__), "_proto/google/protobuf/descriptor.proto"),
        f"{PROTO_DIR}/descriptor.proto",
    )
    copyfile(
        join(dirname(grpc_tools.__file__), "_proto/google/protobuf/wrappers.proto"),
        f"{PROTO_DIR}/wrappers.proto",
    )

    download(
        "https://raw.githubusercontent.com/CasperLabs/CasperLabs/dev/protobuf/google/api/annotations.proto",
        f"{PROTO_DIR}",
    )
    download(
        "https://raw.githubusercontent.com/CasperLabs/CasperLabs/dev/protobuf/google/api/http.proto",
        f"{PROTO_DIR}",
    )

    for file_name in Path(f"{PROTOBUF_DIR}/io/").glob("**/*.proto"):
        copyfile(file_name, f"{PROTO_DIR}/{basename(file_name)}")
    print("Finished collecting files...")


def clean_up():
    try:
        shutil.rmtree(f"{PROTO_DIR}")
    except FileNotFoundError:
        pass
    for file_name in glob(f"{PACKAGE_DIR}/*pb2*py"):
        os.remove(file_name)


def run_codegen():
    python_compiler_check()
    proto_compiler_check()
    clean_up()
    make_dirs(f"{PROTO_DIR}")
    collect_proto_files()
    modify_files(
        "Patch proto files' imports", [(r'".+/', '"')], glob(f"{PROTO_DIR}/*.proto")
    )
    run_protoc(glob(f"{PROTO_DIR}/*.proto"))
    modify_files(
        "Patch generated Python gRPC modules",
        [(r"(import .*_pb2)", r"from . \1")],
        glob(f"{PACKAGE_DIR}/*pb2*py"),
    )
    modify_files(
        "Patch generated Python gRPC modules (for asyncio)",
        [(r"(import .*_pb2)", r"from . \1")],
        [fn for fn in glob(f"{PACKAGE_DIR}/*_grpc[.]py") if "_pb2_" not in fn],
    )


def prepare_sdist():
    run_codegen()


if len(sys.argv) > 1 and sys.argv[1] == "sdist":
    prepare_sdist()


with open(path.join(THIS_DIRECTORY, "README.md"), encoding="utf-8") as fh:
    long_description = fh.read()


class CInstall(InstallCommand):
    def run(self):
        super().run()


class CDevelop(DevelopCommand):
    def run(self):
        run_codegen()
        super().run()


setup(
    name=NAME,
    version="0.18.2",
    packages=find_packages(exclude=["tests"]),
    setup_requires=[
        "protobuf==3.9.1",
        "grpcio-tools>=1.20",
        "in-place==0.4.0",
        "grpcio>=1.20",
    ],
    install_requires=[
        "protobuf==3.9.1",
        "grpcio>=1.20",
        "pyblake2==1.1.2",
        "ed25519==1.4",
        "cryptography==2.8",
        "pycryptodome==3.9.4",
    ],
    cmdclass={"install": CInstall, "develop": CDevelop},
    description="Python Client for interacting with a CasperLabs Node",
    long_description=long_description,
    long_description_content_type="text/markdown",
    include_package_data=True,
    package_data={},
    keywords="casperlabs blockchain ethereum smart-contracts",
    author="CasperLabs LLC",
    author_email="testing@casperlabs.io",
    license="CasperLabs Open Source License (COSL)",
    zip_safe=False,
    classifiers=[
        "Development Status :: 3 - Alpha",
        "Programming Language :: Python :: 3.6",
        "Programming Language :: Python :: 3.7",
        "Programming Language :: Python :: 3 :: Only",
        "Operating System :: OS Independent",
        "Intended Audience :: Developers",
    ],
    python_requires=">=3.6.0",
    url="https://casperlabs.io/",
    project_urls={
        "Source": "https://github.com/CasperLabs/CasperLabs/tree/dev/integration-testing/client/CasperLabsClient",
        "Readme": "https://github.com/CasperLabs/CasperLabs/blob/dev/integration-testing/client/CasperLabsClient/README.md",
    },
    entry_points={
        "console_scripts": ["casperlabs_client = casperlabs_client.cli:main"]
    },
)
