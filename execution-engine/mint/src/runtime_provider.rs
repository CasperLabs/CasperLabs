use types::{account::AccountHash, Key};

pub trait RuntimeProvider {
    fn get_caller(&self) -> AccountHash;

    fn put_key(&mut self, name: &str, key: Key);
}
