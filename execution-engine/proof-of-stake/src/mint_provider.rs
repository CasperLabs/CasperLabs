use types::{account::AccountHash, TransferResult, URef, U512};

pub trait MintProvider {
    fn transfer_purse_to_account(
        &mut self,
        source: URef,
        target: AccountHash,
        amount: U512,
    ) -> TransferResult;

    fn transfer_purse_to_purse(
        &mut self,
        source: URef,
        target: URef,
        amount: U512,
    ) -> Result<(), ()>;

    fn balance(&mut self, purse: URef) -> Option<U512>;
}
