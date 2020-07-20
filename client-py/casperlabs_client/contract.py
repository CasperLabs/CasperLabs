import abc
from dataclasses import dataclass
from typing import Dict, Union, Optional

from casperlabs_client.io import read_binary_file
from . import consensus_pb2 as consensus

from .abi import ABI
from .consts import DEFAULT_PAYMENT_AMOUNT


@dataclass
class ContractCode(abc.ABC):
    """ Abstract class representing many ways of sending in contracts to system """

    wasm_file_path: str = None
    contract_hash: bytes = None
    contract_name: str = None
    package_hash: bytes = None
    package_name: str = None
    contract_args: bytes = None
    entry_point: str = None
    version: int = None
    transfer_args: bytes = None

    def __post_init__(self):
        if self.contract_args is None:
            self.contract_args = ABI.args([])

    @staticmethod
    def _maybe_args_from_json(args: Union[str, bytes]) -> Optional[bytes]:
        """ Parses args from JSON format if exists """
        if args:
            # Encode args if still as str
            if isinstance(args, str):
                return ABI.args_from_json(args)
            else:
                return args
        return None

    @staticmethod
    @abc.abstractmethod
    def from_args(args: Dict) -> "ContractCode":
        pass

    @abc.abstractmethod
    def validate(self) -> None:
        """ Raises exception if object is built with invalid data """
        pass

    def is_valid(self) -> bool:
        """ Checks that object is built with valid data """
        try:
            self.validate()
            return True
        except Exception:
            return False

    def to_protobuf(self) -> consensus.Deploy.Code:
        """ Encode contract into consensus.Deploy.Code """
        if self.wasm_file_path:
            wasm_contract = consensus.Deploy.Code.WasmContract(
                wasm=read_binary_file(self.wasm_file_path)
            )
            return consensus.Deploy.Code(
                args=self.contract_args, wasm_contract=wasm_contract
            )
        if self.contract_hash:
            stored_contract = consensus.Deploy.Code.StoredContract(
                contract_hash=self.contract_hash, entry_point=self.entry_point
            )
            return consensus.Deploy.Code(
                args=self.contract_args, stored_contract=stored_contract
            )
        if self.contract_name:
            stored_contract = consensus.Deploy.Code.StoredContract(
                name=self.contract_name, entry_point=self.entry_point
            )
            return consensus.Deploy.Code(
                args=self.contract_args, stored_contract=stored_contract
            )
        elif self.package_hash:
            svc = consensus.Deploy.Code.StoredVersionedContract(
                package_hash=self.package_hash,
                entry_point=self.entry_point,
                version=self.version,
            )
            return consensus.Deploy.Code(
                args=self.contract_args, stored_versioned_contract=svc
            )
        elif self.package_name:
            svc = consensus.Deploy.Code.StoredVersionedContract(
                name=self.package_name,
                entry_point=self.entry_point,
                version=self.version,
            )
            return consensus.Deploy.Code(
                args=self.contract_args, stored_versioned_contract=svc
            )
        elif self.transfer_args:
            transfer_contract = consensus.Deploy.Code.TransferContract()
            return consensus.Deploy.Code(
                args=self.transfer_args, transfer_contract=transfer_contract
            )
        # If we fall through, may error or provide defaults
        return self._only_args_encode()

    @abc.abstractmethod
    def _only_args_encode(self) -> consensus.Deploy.Code:
        pass


@dataclass
class SessionCode(ContractCode):
    """ Representation of Session Code to send into the system. """

    def __post_init__(self):
        """ Use post init for validate to handle any creation method """
        self.validate()

    def validate(self) -> None:
        """ Validates the object and throws exceptions if problems are found"""
        session_options = (
            self.wasm_file_path,
            self.contract_name,
            self.contract_hash,
            self.package_hash,
            self.package_name,
            self.transfer_args,
        )

        options_count = len(list(filter(None, session_options)))
        if options_count != 1:
            raise ValueError(
                "Must have one and only one session, session_hash, session_name, package_hash, or package_name provided"
            )

    @staticmethod
    def from_args(args: Dict) -> "SessionCode":
        """ Creates SessionCode from CLI args """
        wasm_file_path = args.get("session")
        contract_hash = args.get("session_hash")
        contract_name = args.get("session_name")
        package_hash = args.get("session_package_hash")
        package_name = args.get("session_package_name")
        entry_point = args.get("session_entry_point")
        version = args.get("session_version")
        contract_args = SessionCode._maybe_args_from_json(args.get("session_args"))
        transfer_args = args.get("transfer_args")
        session_code = SessionCode(
            wasm_file_path=wasm_file_path,
            contract_args=contract_args,
            contract_hash=contract_hash,
            contract_name=contract_name,
            package_hash=package_hash,
            package_name=package_name,
            entry_point=entry_point,
            version=version,
            transfer_args=transfer_args,
        )
        return session_code

    def _only_args_encode(self) -> consensus.Deploy.Code:
        """ Should be caught in arg parsing for Deploy """
        raise ValueError("No runnable wasm or reference found for session.")


@dataclass
class PaymentCode(ContractCode):
    """ Representation of Payment Code to send into the system. """

    payment_amount: int = None

    @property
    def _payment_options(self) -> tuple:
        return (
            self.wasm_file_path,
            self.contract_name,
            self.contract_hash,
            self.package_name,
            self.package_hash,
            self.payment_amount,
        )

    def __post_init__(self):
        """ Use post init for validate to handle any creation method """
        if not any(self._payment_options):
            self.payment_amount = DEFAULT_PAYMENT_AMOUNT
        self.validate()

    def validate(self) -> None:
        """ Validates the object and throws exceptions if problems are found"""
        options_count = len(list(filter(None, self._payment_options)))
        if options_count > 1:
            raise ValueError(
                "No more than one of payment, payment_hash, payment_name, payment_package_hash, "
                "payment_package_name, or payment_amount can be given."
            )
        elif options_count == 0:
            raise ValueError("No payment options were found.")

    @staticmethod
    def from_args(args: Dict) -> "PaymentCode":
        wasm_file_path = args.get("payment")
        payment_amount = args.get("payment_amount")
        contract_hash = args.get("payment_hash")
        contract_name = args.get("payment_name")
        package_hash = args.get("payment_package_hash")
        package_name = args.get("payment_package_name")
        entry_point = args.get("payment_entry_point")
        version = args.get("payment_version")
        contract_args = SessionCode._maybe_args_from_json(args.get("payment_args"))
        payment_code = PaymentCode(
            wasm_file_path=wasm_file_path,
            contract_args=contract_args,
            contract_hash=contract_hash,
            contract_name=contract_name,
            package_hash=package_hash,
            package_name=package_name,
            entry_point=entry_point,
            version=version,
            payment_amount=payment_amount,
        )
        return payment_code

    def _only_args_encode(self) -> consensus.Deploy.Code:
        """ Payment allows call with only args to standard payment """
        if self.contract_args:
            return consensus.Deploy.Code(args=self.contract_args)
        else:
            payment_args = ABI.args([ABI.big_int("amount", int(self.payment_amount))])
            return consensus.Deploy.Code(args=payment_args)
