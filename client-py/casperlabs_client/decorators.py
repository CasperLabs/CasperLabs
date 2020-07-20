import functools
import logging
import sys
import time

import grpc
from grpc._channel import _Rendezvous

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
                # TODO: Look at using backoff PyPi package.
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


def guarded_command(function):
    """
    Decorator of functions that implement CLI commands.

    Occasionally the node can throw some exceptions instead of properly sending us a response,
    those will be deserialized on our end and rethrown by the gRPC layer.
    In this case we want to catch the exception and return a non-zero return code to the shell.

    :param function:  function to be decorated
    :return:
    """

    import traceback

    @functools.wraps(function)
    def wrapper(*args, **kwargs):
        try:
            rc = function(*args, **kwargs)
            # Generally the CLI commands are assumed to succeed if they don't throw,
            # but they can also return a positive error code if they need to.
            if rc is not None:
                return rc
            return 0
        except Exception as e:
            traceback.print_exc(file=sys.stderr)
            print(str(e), file=sys.stderr)
            return 1

    return wrapper
