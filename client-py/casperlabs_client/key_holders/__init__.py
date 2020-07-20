from pathlib import Path
from typing import Union

from .key_holder import KeyHolder  # noqa:F401
from .ed25519 import ED25519Key
from .secp256k1 import SECP256K1Key

from ..consts import ED25519_KEY_ALGORITHM, SECP256K1_KEY_ALGORITHM


def class_from_algorithm(algorithm: str):
    """ Get proper KeyHolder class using algorithm string. """
    class_map = {
        ED25519_KEY_ALGORITHM: ED25519Key,
        SECP256K1_KEY_ALGORITHM: SECP256K1Key,
    }
    try:
        return class_map[algorithm]
    except KeyError:
        ValueError(f"algorithm should be in ({class_map.keys()})")


def key_holder_object(
    algorithm: str = ED25519_KEY_ALGORITHM,
    private_key_pem_path: Union[str, Path] = None,
    private_key: bytes = None,
    public_key_pem_path: Union[str, Path] = None,
    public_key: bytes = None,
):
    """
    Will create proper KeyHolder class based on algorithm given.
    Will populate first based on private key options. If not given will try public key options.

    Note: KeyHolder objects require private key for some functionality.

    :param algorithm:            Key algorithm, see consts.SUPPORTED_KEY_ALGORITHMS
    :param private_key: private  key in bytes (used as first option)
    :param private_key_pem_path: Path to pem file containing private key (used as second option)
    :param public_key:           public key in bytes (used as third option)
    :param public_key_pem_path:  Path to pem file containing public key (used as fourth option)
    :return:
    """
    class_object = class_from_algorithm(algorithm)
    if private_key is not None:
        return class_object(private_key=private_key)
    if private_key_pem_path is not None:
        return class_object.from_private_key_path(private_key_pem_path)
    if public_key is not None:
        return class_object(public_key=public_key)
    if public_key_pem_path is not None:
        return class_object.from_public_key_path(public_key_pem_path)
    raise ValueError("No key information provided to create key holder")
