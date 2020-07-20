import blake from 'blakejs';
import { Deploy } from 'casperlabs-grpc/io/casperlabs/casper/consensus/consensus_pb';
import * as fs from 'fs';
import { Message } from 'google-protobuf';
import * as nacl from 'tweetnacl-ts';
import { ByteArray } from '../index';
import { Args, BigIntValue, BytesValue } from './Args';
import { ContractType, makeDeploy, signDeploy } from './DeployUtil';
import { Ed25519 } from './Keys';

// https://www.npmjs.com/package/tweetnacl-ts
// https://github.com/dcposch/blakejs

export function byteHash(x: ByteArray): ByteArray {
  return blake.blake2b(x, null, 32);
}

export function protoHash<T extends Message>(x: T): ByteArray {
  return byteHash(x.serializeBinary());
}

export class Contract {
  private sessionWasm: ByteArray;
  private paymentWasm: ByteArray;

  constructor(sessionPath: string, paymentPath?: string) {
    this.sessionWasm = fs.readFileSync(sessionPath);
    if (!paymentPath) {
      this.paymentWasm = Buffer.from('');
    } else {
      this.paymentWasm = fs.readFileSync(paymentPath);
    }
  }

  public deploy(
    args: Deploy.Arg[],
    paymentAmount: bigint,
    accountPublicKeyHash: ByteArray,
    signingKeyPair: nacl.SignKeyPair
  ): Deploy {
    const deploy = makeDeploy(
      args,
      ContractType.WASM,
      this.sessionWasm,
      this.paymentWasm,
      paymentAmount,
      accountPublicKeyHash
    );
    return signDeploy(deploy, signingKeyPair);
  }
}

/** Always use the same account for deploying and signing. */
export class BoundContract {
  constructor(
    private contract: Contract,
    private contractKeyPair: nacl.SignKeyPair
  ) {}

  public deploy(args: Deploy.Arg[], paymentAmount: bigint): Deploy {
    return this.contract.deploy(
      args,
      paymentAmount,
      Ed25519.publicKeyHash(this.contractKeyPair.publicKey),
      this.contractKeyPair
    );
  }
}

export class Faucet {
  public static args(accountPublicKeyHash: ByteArray): Deploy.Arg[] {
    return Args(['account', BytesValue(accountPublicKeyHash)]);
  }
}

export class Transfer {
  public static args(
    accountPublicKeyHash: ByteArray,
    amount: bigint
  ): Deploy.Arg[] {
    return Args(
      ['account', BytesValue(accountPublicKeyHash)],
      ['amount', BigIntValue(amount)]
    );
  }
}
