#![allow(clippy::wildcard_imports)]

use lazy_static::lazy_static;
use regex::Regex;

use crate::dependent_file::DependentFile;

lazy_static! {
    pub static ref MANIFEST_NAME_REGEX: Regex = Regex::new(r#"(?m)(^name = )"([^"]+)"#).unwrap();
    pub static ref MANIFEST_VERSION_REGEX: Regex =
        Regex::new(r#"(?m)(^version = )"([^"]+)"#).unwrap();
    pub static ref PACKAGE_JSON_NAME_REGEX: Regex =
        Regex::new(r#"(?m)(^  "name": )"([^"]+)"#).unwrap();
    pub static ref PACKAGE_JSON_VERSION_REGEX: Regex =
        Regex::new(r#"(?m)(^  "version": )"([^"]+)"#).unwrap();
}

fn replacement(updated_version: &str) -> String {
    format!(r#"$1"{}"#, updated_version)
}

fn replacement_with_slash(updated_version: &str) -> String {
    format!(r#"$1/{}"#, updated_version)
}

pub mod types {
    use super::*;

    lazy_static! {
        pub static ref DEPENDENT_FILES: Vec<DependentFile> = {
            vec![
                DependentFile::new(
                    "types/Cargo.toml",
                    MANIFEST_VERSION_REGEX.clone(),
                    replacement,
                ),

                DependentFile::new(
                    "cargo-casperlabs/src/common.rs",
                    Regex::new(r#"(?m)("casperlabs-types",\s*)"(?:[^"]+)"#).unwrap(),
                    replacement,
                ),

                DependentFile::new(
                    "contract/Cargo.toml",
                    Regex::new(r#"(?m)(^casperlabs-types = \{[^\}]*version = )"(?:[^"]+)"#).unwrap(),
                    replacement,
                ),

                DependentFile::new(
                    "contracts/system/standard-payment-install/Cargo.toml",
                    Regex::new(r#"(?m)(^types = \{[^\}]*version = )"(?:[^"]+)"#).unwrap(),
                    replacement,
                ),

                DependentFile::new(
                    "engine-core/Cargo.toml",
                    Regex::new(r#"(?m)(^types = \{[^\}]*version = )"(?:[^"]+)"#).unwrap(),
                    replacement,
                ),

                DependentFile::new(
                    "engine-grpc-server/Cargo.toml",
                    Regex::new(r#"(?m)(^types = \{[^\}]*version = )"(?:[^"]+)"#).unwrap(),
                    replacement,
                ),

                DependentFile::new(
                    "engine-shared/Cargo.toml",
                    Regex::new(r#"(?m)(^types = \{[^\}]*version = )"(?:[^"]+)"#).unwrap(),
                    replacement,
                ),

                DependentFile::new(
                    "engine-storage/Cargo.toml",
                    Regex::new(r#"(?m)(^types = \{[^\}]*version = )"(?:[^"]+)"#).unwrap(),
                    replacement,
                ),

                DependentFile::new(
                    "engine-test-support/Cargo.toml",
                    Regex::new(r#"(?m)(^types = \{[^\}]*version = )"(?:[^"]+)"#).unwrap(),
                    replacement,
                ),

                DependentFile::new(
                    "engine-wasm-prep/Cargo.toml",
                    Regex::new(r#"(?m)(^types = \{[^\}]*version = )"(?:[^"]+)"#).unwrap(),
                    replacement,
                ),

                DependentFile::new(
                    "mint/Cargo.toml",
                    Regex::new(r#"(?m)(^types = \{[^\}]*version = )"(?:[^"]+)"#).unwrap(),
                    replacement,
                ),

                DependentFile::new(
                    "proof-of-stake/Cargo.toml",
                    Regex::new(r#"(?m)(^types = \{[^\}]*version = )"(?:[^"]+)"#).unwrap(),
                    replacement,
                ),

                DependentFile::new(
                    "standard-payment/Cargo.toml",
                    Regex::new(r#"(?m)(^types = \{[^\}]*version = )"(?:[^"]+)"#).unwrap(),
                    replacement,
                ),

                DependentFile::new(
                    "types/src/lib.rs",
                    Regex::new(r#"(?m)(#!\[doc\(html_root_url = "https://docs.rs/casperlabs-types)/(?:[^"]+)"#).unwrap(),
                    replacement_with_slash,
                ),
            ]
        };
    }
}

pub mod contract {
    use super::*;

    lazy_static! {
        pub static ref DEPENDENT_FILES: Vec<DependentFile> = {
            vec![
                DependentFile::new(
                    "contract/Cargo.toml",
                    MANIFEST_VERSION_REGEX.clone(),
                    replacement,
                ),

                DependentFile::new(
                    "cargo-casperlabs/src/common.rs",
                    Regex::new(r#"(?m)("casperlabs-contract",\s*)"(?:[^"]+)"#).unwrap(),
                    replacement,
                ),

                DependentFile::new(
                    "engine-core/Cargo.toml",
                    Regex::new(r#"(?m)(^contract = \{[^\}]*version = )"(?:[^"]+)"#).unwrap(),
                    replacement,
                ),

                DependentFile::new(
                    "engine-test-support/Cargo.toml",
                    Regex::new(r#"(?m)(^contract = \{[^\}]*version = )"(?:[^"]+)"#).unwrap(),
                    replacement,
                ),

                DependentFile::new(
                    "contract/src/lib.rs",
                    Regex::new(r#"(?m)(#!\[doc\(html_root_url = "https://docs.rs/casperlabs-contract)/(?:[^"]+)"#).unwrap(),
                    replacement_with_slash,
                ),
            ]
        };
    }
}

pub mod engine_wasm_prep {
    use super::*;

    lazy_static! {
        pub static ref DEPENDENT_FILES: Vec<DependentFile> = {
            vec![
                DependentFile::new(
                    "engine-wasm-prep/Cargo.toml",
                    MANIFEST_VERSION_REGEX.clone(),
                    replacement,
                ),
                DependentFile::new(
                    "engine-core/Cargo.toml",
                    Regex::new(r#"(?m)(^engine-wasm-prep = \{[^\}]*version = )"(?:[^"]+)"#)
                        .unwrap(),
                    replacement,
                ),
                DependentFile::new(
                    "engine-grpc-server/Cargo.toml",
                    Regex::new(r#"(?m)(^engine-wasm-prep = \{[^\}]*version = )"(?:[^"]+)"#)
                        .unwrap(),
                    replacement,
                ),
                DependentFile::new(
                    "engine-shared/Cargo.toml",
                    Regex::new(r#"(?m)(^engine-wasm-prep = \{[^\}]*version = )"(?:[^"]+)"#)
                        .unwrap(),
                    replacement,
                ),
                DependentFile::new(
                    "engine-storage/Cargo.toml",
                    Regex::new(r#"(?m)(^engine-wasm-prep = \{[^\}]*version = )"(?:[^"]+)"#)
                        .unwrap(),
                    replacement,
                ),
                DependentFile::new(
                    "engine-test-support/Cargo.toml",
                    Regex::new(r#"(?m)(^engine-wasm-prep = \{[^\}]*version = )"(?:[^"]+)"#)
                        .unwrap(),
                    replacement,
                ),
            ]
        };
    }
}

pub mod mint {
    use super::*;

    lazy_static! {
        pub static ref DEPENDENT_FILES: Vec<DependentFile> = {
            vec![
                DependentFile::new(
                    "mint/Cargo.toml",
                    MANIFEST_VERSION_REGEX.clone(),
                    replacement,
                ),
                DependentFile::new(
                    "engine-core/Cargo.toml",
                    Regex::new(r#"(?m)(^mint = \{[^\}]*version = )"(?:[^"]+)"#).unwrap(),
                    replacement,
                ),
            ]
        };
    }
}

pub mod proof_of_stake {
    use super::*;

    lazy_static! {
        pub static ref DEPENDENT_FILES: Vec<DependentFile> = {
            vec![
                DependentFile::new(
                    "proof-of-stake/Cargo.toml",
                    MANIFEST_VERSION_REGEX.clone(),
                    replacement,
                ),
                DependentFile::new(
                    "engine-core/Cargo.toml",
                    Regex::new(r#"(?m)(^proof-of-stake = \{[^\}]*version = )"(?:[^"]+)"#).unwrap(),
                    replacement,
                ),
            ]
        };
    }
}

pub mod standard_payment {
    use super::*;

    lazy_static! {
        pub static ref DEPENDENT_FILES: Vec<DependentFile> = {
            vec![
                DependentFile::new(
                    "standard-payment/Cargo.toml",
                    MANIFEST_VERSION_REGEX.clone(),
                    replacement,
                ),
                DependentFile::new(
                    "engine-core/Cargo.toml",
                    Regex::new(r#"(?m)(^standard-payment = \{[^\}]*version = )"(?:[^"]+)"#)
                        .unwrap(),
                    replacement,
                ),
            ]
        };
    }
}

pub mod engine_shared {
    use super::*;

    lazy_static! {
        pub static ref DEPENDENT_FILES: Vec<DependentFile> = {
            vec![
                DependentFile::new(
                    "engine-shared/Cargo.toml",
                    MANIFEST_VERSION_REGEX.clone(),
                    replacement,
                ),
                DependentFile::new(
                    "engine-core/Cargo.toml",
                    Regex::new(r#"(?m)(^engine-shared = \{[^\}]*version = )"(?:[^"]+)"#).unwrap(),
                    replacement,
                ),
                DependentFile::new(
                    "engine-grpc-server/Cargo.toml",
                    Regex::new(r#"(?m)(^engine-shared = \{[^\}]*version = )"(?:[^"]+)"#).unwrap(),
                    replacement,
                ),
                DependentFile::new(
                    "engine-storage/Cargo.toml",
                    Regex::new(r#"(?m)(^engine-shared = \{[^\}]*version = )"(?:[^"]+)"#).unwrap(),
                    replacement,
                ),
                DependentFile::new(
                    "engine-test-support/Cargo.toml",
                    Regex::new(r#"(?m)(^engine-shared = \{[^\}]*version = )"(?:[^"]+)"#).unwrap(),
                    replacement,
                ),
            ]
        };
    }
}

pub mod engine_storage {
    use super::*;

    lazy_static! {
        pub static ref DEPENDENT_FILES: Vec<DependentFile> = {
            vec![
                DependentFile::new(
                    "engine-storage/Cargo.toml",
                    MANIFEST_VERSION_REGEX.clone(),
                    replacement,
                ),
                DependentFile::new(
                    "engine-core/Cargo.toml",
                    Regex::new(r#"(?m)(^engine-storage = \{[^\}]*version = )"(?:[^"]+)"#).unwrap(),
                    replacement,
                ),
                DependentFile::new(
                    "engine-grpc-server/Cargo.toml",
                    Regex::new(r#"(?m)(^engine-storage = \{[^\}]*version = )"(?:[^"]+)"#).unwrap(),
                    replacement,
                ),
                DependentFile::new(
                    "engine-test-support/Cargo.toml",
                    Regex::new(r#"(?m)(^engine-storage = \{[^\}]*version = )"(?:[^"]+)"#).unwrap(),
                    replacement,
                ),
            ]
        };
    }
}

pub mod engine_core {
    use super::*;

    lazy_static! {
        pub static ref DEPENDENT_FILES: Vec<DependentFile> = {
            vec![
                DependentFile::new(
                    "engine-core/Cargo.toml",
                    MANIFEST_VERSION_REGEX.clone(),
                    replacement,
                ),
                DependentFile::new(
                    "engine-grpc-server/Cargo.toml",
                    Regex::new(r#"(?m)(^engine-core = \{[^\}]*version = )"(?:[^"]+)"#).unwrap(),
                    replacement,
                ),
                DependentFile::new(
                    "engine-test-support/Cargo.toml",
                    Regex::new(r#"(?m)(^engine-core = \{[^\}]*version = )"(?:[^"]+)"#).unwrap(),
                    replacement,
                ),
            ]
        };
    }
}

pub mod engine_grpc_server {
    use super::*;

    lazy_static! {
        pub static ref DEPENDENT_FILES: Vec<DependentFile> = {
            vec![
                DependentFile::new(
                    "engine-grpc-server/Cargo.toml",
                    MANIFEST_VERSION_REGEX.clone(),
                    replacement,
                ),
                DependentFile::new(
                    "engine-test-support/Cargo.toml",
                    Regex::new(r#"(?m)(^engine-grpc-server = \{[^\}]*version = )"(?:[^"]+)"#)
                        .unwrap(),
                    replacement,
                ),
            ]
        };
    }
}

pub mod engine_test_support {
    use super::*;

    lazy_static! {
        pub static ref DEPENDENT_FILES: Vec<DependentFile> = {
            vec![
                DependentFile::new(
                    "engine-test-support/Cargo.toml",
                    MANIFEST_VERSION_REGEX.clone(),
                    replacement,
                ),

                DependentFile::new(
                    "cargo-casperlabs/src/tests_package.rs",
                    Regex::new(r#"(?m)("casperlabs-engine-test-support",\s*)"(?:[^"]+)"#).unwrap(),
                    cargo_casperlabs_src_test_package_rs_replacement,
                ),

                DependentFile::new(
                    "engine-test-support/src/lib.rs",
                    Regex::new(r#"(?m)(#!\[doc\(html_root_url = "https://docs.rs/casperlabs-engine-test-support)/(?:[^"]+)"#).unwrap(),
                    replacement_with_slash,
                ),
            ]
        };
    }

    fn cargo_casperlabs_src_test_package_rs_replacement(updated_version: &str) -> String {
        format!(r#"$1"{}"#, updated_version)
    }
}

pub mod cargo_casperlabs {
    use super::*;

    lazy_static! {
        pub static ref DEPENDENT_FILES: Vec<DependentFile> = {
            vec![DependentFile::new(
                "cargo-casperlabs/Cargo.toml",
                MANIFEST_VERSION_REGEX.clone(),
                replacement,
            )]
        };
    }
}

pub mod contract_as {
    use super::*;

    lazy_static! {
        pub static ref DEPENDENT_FILES: Vec<DependentFile> = {
            vec![
                DependentFile::new(
                    "contract-as/package.json",
                    PACKAGE_JSON_VERSION_REGEX.clone(),
                    replacement,
                ),
                DependentFile::new(
                    "contract-as/package-lock.json",
                    PACKAGE_JSON_VERSION_REGEX.clone(),
                    replacement,
                ),
            ]
        };
    }
}
