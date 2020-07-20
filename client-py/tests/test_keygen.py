from casperlabs_client import consts


def test_account_key_files_are_created(account_keys_directory):
    for key_algorithm in consts.SUPPORTED_KEY_ALGORITHMS:
        for file_suffix in (
            consts.ACCOUNT_PUBLIC_KEY_FILENAME_SUFFIX,
            consts.ACCOUNT_PRIVATE_KEY_FILENAME_SUFFIX,
            consts.ACCOUNT_HASH_FILENAME_SUFFIX,
        ):
            file_path = account_keys_directory / f"{key_algorithm}{file_suffix}"
            assert file_path.exists(), f"File {file_path} not found."


def test_validator_key_files_are_created(validator_keys_directory):
    for filename in (
        consts.VALIDATOR_PRIVATE_KEY_FILENAME,
        consts.VALIDATOR_PUBLIC_KEY_FILENAME,
        consts.VALIDATOR_ID_HEX_FILENAME,
        consts.NODE_PRIVATE_KEY_FILENAME,
        consts.NODE_CERTIFICATE_FILENAME,
        consts.NODE_ID_FILENAME,
    ):
        file_path = validator_keys_directory / filename
        assert file_path.exists(), f"File {file_path} not found"
