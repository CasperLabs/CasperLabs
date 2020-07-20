import {
  Approval,
  Deploy,
  Signature
} from 'casperlabs-grpc/io/casperlabs/casper/consensus/consensus_pb';
import JSBI from 'jsbi';
import * as nacl from 'tweetnacl-ts';
import { ByteArray } from '../index';
import { Args, BigIntValue } from './Args';
import { protoHash } from './Contracts';

export enum ContractType {
  WASM = 'WASM',
  Hash = 'Hash',
  Name = 'Name'
}

// The following two methods definition guarantee that session is a string iff its contract type is ContractType.Name
// See https://stackoverflow.com/questions/39700093/variable-return-types-based-on-string-literal-type-argument for detail
// for ContractType.WASM, the type of session is ByteArray, and entryPoint is not required
export function makeDeploy(
  args: Deploy.Arg[],
  type: ContractType.WASM,
  session: ByteArray,
  paymentWasm: ByteArray | null,
  paymentAmount: bigint | JSBI,
  accountPublicKeyHash: ByteArray,
  dependencies?: Uint8Array[]
): Deploy;

// for ContractType.Hash, the type of session is ByteArray, and entryPoint is required
export function makeDeploy(
  args: Deploy.Arg[],
  type: ContractType.Hash,
  session: ByteArray,
  paymentWasm: ByteArray | null,
  paymentAmount: bigint | JSBI,
  accountPublicKey: ByteArray,
  dependencies: Uint8Array[],
  entryPoint: string
): Deploy;

// for ContractType.Name, the type of sessionName is string, and entryPoint is required
export function makeDeploy(
  args: Deploy.Arg[],
  type: ContractType.Name,
  sessionName: string,
  paymentWasm: ByteArray | null,
  paymentAmount: bigint | JSBI,
  accountPublicKeyHash: ByteArray,
  dependencies: Uint8Array[],
  entryPoint: string
): Deploy;

// If EE receives a deploy with no payment bytes,
// then it will use host-side functionality equivalent to running the standard payment contract
export function makeDeploy(
  args: Deploy.Arg[],
  type: ContractType,
  session: ByteArray | string,
  paymentWasm: ByteArray | null,
  paymentAmount: bigint | JSBI,
  accountPublicKeyHash: ByteArray,
  dependencies?: Uint8Array[],
  entryPoint?: string,
): Deploy {
  const sessionCode = new Deploy.Code();
  if (type === ContractType.WASM) {
    const wasmContract = new Deploy.Code.WasmContract();
    wasmContract.setWasm(session);
    sessionCode.setWasmContract(wasmContract);
  } else if (type === ContractType.Hash) {
    const storedContract = new Deploy.Code.StoredContract();
    storedContract.setContractHash(session);
    storedContract.setEntryPoint(entryPoint || "");
    sessionCode.setStoredContract(storedContract);
  } else {
    const storedContract = new Deploy.Code.StoredContract();
    storedContract.setName(session as string);
    storedContract.setEntryPoint(entryPoint || "")
    sessionCode.setStoredContract(storedContract);
  }
  sessionCode.setArgsList(args);

  if (paymentWasm === null) {
    paymentWasm = Buffer.from('');
  }
  const paymentContract = new Deploy.Code.WasmContract();
  paymentContract.setWasm(paymentWasm);
  const payment = new Deploy.Code();
  payment.setWasmContract(paymentContract);
  payment.setArgsList(Args(['amount', BigIntValue(paymentAmount)]));

  const body = new Deploy.Body();
  body.setSession(sessionCode);
  body.setPayment(payment);

  const header = new Deploy.Header();
  header.setAccountPublicKeyHash(accountPublicKeyHash);
  header.setTimestamp(new Date().getTime());
  header.setBodyHash(protoHash(body));
  // we will remove gasPrice eventually
  header.setGasPrice(1);
  header.setDependenciesList(dependencies ?? []);

  const deploy = new Deploy();
  deploy.setBody(body);
  deploy.setHeader(header);
  deploy.setDeployHash(protoHash(header));
  return deploy;
}

export const signDeploy = (
  deploy: Deploy,
  signingKeyPair: nacl.SignKeyPair
): Deploy => {
  const signature = new Signature();
  signature.setSigAlgorithm('ed25519');
  signature.setSig(
    nacl.sign_detached(deploy.getDeployHash_asU8(), signingKeyPair.secretKey)
  );

  const approval = new Approval();
  approval.setApproverPublicKey(signingKeyPair.publicKey);
  approval.setSignature(signature);

  deploy.setApprovalsList([approval]);

  return deploy;
};

export const setSignature = (
  deploy: Deploy,
  sig: ByteArray,
  publicKey: ByteArray
): Deploy => {
  const signature = new Signature();
  signature.setSigAlgorithm('ed25519');
  signature.setSig(sig);

  const approval = new Approval();
  approval.setApproverPublicKey(publicKey);
  approval.setSignature(signature);

  deploy.setApprovalsList([approval]);

  return deploy;
};
