interface UserAccount {
  // Human readable name.
  name: string;
  // Public key in PEM format.
  publicKeyBase64: string;
  // Now we only support ed25519 in Clarity.
  sigAlgorithm?: 'ed25519';
}

interface NamedHash {
  // Human readable name.
  name: string;
  // Hash of the vesting contract
  hashBase16: string;
}

interface UserMetadata {
  accounts?: UserAccount[];
  contracts?: Contracts;
}

interface Contracts {
  vestingContracts?: NamedHash[];
}

interface User {
  // The User ID in Auth0.
  sub: string;
  name: string;
  email?: string;
}

type ByteArray = Uint8Array;
type DeployHash = ByteArray;
type BlockHash = ByteArray;

interface AccountBalance {
  checkedAt: Date;
  blockHash: BlockHash;
  // undefine means the account didn't exist.
  balance: number | undefined;
}
