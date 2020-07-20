"""
Command line interface for CasperLabsClient.
"""

import argparse
import sys

from casperlabs_client.io import read_version

from casperlabs_client import CasperLabsClient, consts
from casperlabs_client.commands import (
    account_hash_cmd,
    balance_cmd,
    deploy_cmd,
    keygen_cmd,
    make_deploy_cmd,
    propose_cmd,
    stream_events_cmd,
    transfer_cmd,
    sign_deploy_cmd,
    send_deploy_cmd,
    query_state_cmd,
    show_block_cmd,
    show_deploy_cmd,
    show_blocks_cmd,
    show_deploys_cmd,
    show_peers_cmd,
    validator_keygen_cmd,
    visualize_dag_cmd,
)


def cli(*arguments) -> int:
    """
    Parse list of method line arguments and call appropriate method.
    """

    class Parser:
        def __init__(self):
            # The --help option added by default has a short version -h, which conflicts
            # with short version of --host, so we need to disable it.
            self.parser = argparse.ArgumentParser(
                prog="casperlabs_client", add_help=False
            )
            self.parser.add_argument(
                "--help",
                action="help",
                default=argparse.SUPPRESS,
                help="show this help message and exit",
            )
            self.parser.add_argument(
                "-h",
                "--host",
                required=False,
                default=consts.DEFAULT_HOST,
                type=str,
                help="Hostname or IP of node on which gRPC service is running.",
            )
            self.parser.add_argument(
                "-p",
                "--port",
                required=False,
                default=consts.DEFAULT_PORT,
                type=int,
                help="Port used for external gRPC API.",
            )
            self.parser.add_argument(
                "--port-internal",
                required=False,
                default=consts.DEFAULT_INTERNAL_PORT,
                type=int,
                help="Port used for internal gRPC API.",
            )
            self.parser.add_argument(
                "--node-id",
                required=False,
                type=str,
                help="node_id parameter for TLS connection",
            )
            self.parser.add_argument(
                "--certificate-file",
                required=False,
                type=str,
                help="Certificate file for TLS connection",
            )
            self.parser.add_argument(
                "--version", action="version", version=read_version()
            )
            self.sp = self.parser.add_subparsers(help="Choose a request")

            def no_command(casperlabs_client, args):
                print(
                    "You must provide a method. --help for documentation of commands."
                )
                self.parser.print_usage()
                return 1

            self.parser.set_defaults(function=no_command)

        def add_command(
            self, command_name: str, function, help_message: str, argument_list: list
        ):
            """ Add main command to parser """
            command_parser = self.sp.add_parser(command_name, help=help_message)
            command_parser.set_defaults(function=function)
            for (args, options) in argument_list:
                command_parser.add_argument(*args, **options)

        def run(self, argv):
            # Using dict rather than namespace to allow dual interface with library
            args = vars(self.parser.parse_args(argv))
            return args["function"](
                CasperLabsClient(
                    args.get("host"),
                    args.get("port"),
                    args.get("port_internal"),
                    args.get("node_id"),
                    args.get("certificate_file"),
                ),
                args,
            )

    parser = Parser()

    for command in (
        account_hash_cmd,
        balance_cmd,
        deploy_cmd,
        keygen_cmd,
        make_deploy_cmd,
        propose_cmd,
        query_state_cmd,
        send_deploy_cmd,
        show_block_cmd,
        show_blocks_cmd,
        show_deploy_cmd,
        show_deploys_cmd,
        show_peers_cmd,
        sign_deploy_cmd,
        stream_events_cmd,
        transfer_cmd,
        validator_keygen_cmd,
        visualize_dag_cmd,
    ):
        parser.add_command(command.NAME, command.method, command.HELP, command.OPTIONS)

    return parser.run([str(a) for a in arguments])


def main():
    return cli(*sys.argv[1:])


if __name__ == "__main__":
    sys.exit(main())
