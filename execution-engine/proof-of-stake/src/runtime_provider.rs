use types::{account::AccountHash, BlockTime, Key, Phase};

pub trait RuntimeProvider {
    fn get_key(&self, name: &str) -> Option<Key>;

    fn put_key(&mut self, name: &str, key: Key);

    fn remove_key(&mut self, name: &str);

    fn get_phase(&self) -> Phase;

    fn get_block_time(&self) -> BlockTime;

    fn get_caller(&self) -> AccountHash;
}
