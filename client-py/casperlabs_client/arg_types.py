import argparse
import os
from pathlib import Path

from casperlabs_client.consts import SUPPORTED_KEY_ALGORITHMS


def algorithm(algorithm_name):
    """ Check the algorithm string is supported """
    if algorithm_name in SUPPORTED_KEY_ALGORITHMS:
        return algorithm_name
    raise argparse.ArgumentTypeError(
        f"{algorithm_name} is not valid. Must be in {SUPPORTED_KEY_ALGORITHMS}"
    )


def positive_integer(number):
    """Check number is an integer greater than 0"""
    n = int(number)
    if n < 1:
        raise argparse.ArgumentTypeError(f"{number} is not a positive int value")
    return n


def directory_for_write(path):
    """ Check directory exists and is writable. """
    if not os.path.exists(path):
        raise argparse.ArgumentTypeError(f"Directory '{path}' does not exist")
    if not os.path.isdir(path):
        raise argparse.ArgumentTypeError(f"'{path}' is not a directory")
    if not os.access(path, os.W_OK):
        raise argparse.ArgumentTypeError(f"'{path}' does not have writing permissions")
    return Path(path)
