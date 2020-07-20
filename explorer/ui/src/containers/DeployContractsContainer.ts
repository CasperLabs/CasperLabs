import { action, observable } from 'mobx';

import ErrorContainer from './ErrorContainer';
import {
  CasperService,
  decodeBase16,
  DeployUtil,
  encodeBase16,
  Signer
} from 'casperlabs-sdk';
import { FieldState, FormState } from 'formstate';
import {
  numberGreaterThan,
  validateBase16,
  validateInt,
  valueRequired
} from '../lib/FormsValidator';
import validator from 'validator';
import $ from 'jquery';
import { Deploy } from 'casperlabs-grpc/io/casperlabs/casper/consensus/consensus_pb';
import {
  CLType,
  CLValueInstance,
  Key
} from 'casperlabs-grpc/io/casperlabs/casper/consensus/state_pb';
import { decodeBase64 } from 'tweetnacl-ts';
import JSBI from 'jsbi';
import { publicKeyHashForEd25519 } from './AuthContainer';

type SupportedType = CLType.SimpleMap[keyof CLType.SimpleMap] | 'Bytes';

export enum KeyType {
  ADDRESS = 'Address',
  HASH = 'Hash',
  UREF = 'URef'
}

export enum BitWidth {
  B_128 = 128,
  B_256 = 256,
  B_512 = 512
}

const powerOf2 = (n: number): JSBI => {
  return JSBI.exponentiate(JSBI.BigInt(2), JSBI.BigInt(n));
};

const numberLimitForUnsigned = (bit: number) => {
  return {
    min: JSBI.BigInt(0),
    max: JSBI.subtract(powerOf2(bit), JSBI.BigInt(1))
  };
};

const numberLimitForSigned = (bit: number) => {
  return {
    min: JSBI.multiply(JSBI.BigInt(-1), powerOf2(bit - 1)),
    max: JSBI.subtract(powerOf2(bit - 1), JSBI.BigInt(1))
  };
};

const NumberLimit = {
  [CLType.Simple.U8]: numberLimitForUnsigned(8),
  [CLType.Simple.U32]: numberLimitForUnsigned(32),
  [CLType.Simple.U64]: numberLimitForUnsigned(64),
  [CLType.Simple.U128]: numberLimitForUnsigned(128),
  [CLType.Simple.U256]: numberLimitForUnsigned(256),
  [CLType.Simple.U512]: numberLimitForUnsigned(512),
  [CLType.Simple.I32]: numberLimitForSigned(32),
  [CLType.Simple.I64]: numberLimitForSigned(64)
};

export type DeployArgument = {
  name: FieldState<string>,
  type: FieldState<SupportedType>,
  // if type == ArgumentType.Key then the type of secondType is KeyType
  // and if type == ArgumentType.BIG_INT, then the type of secondType is BitWidth
  // otherwise second equals to null
  secondType: FieldState<KeyType | BitWidth | null>,
  URefAccessRight: FieldState<Key.URef.AccessRightsMap[keyof Key.URef.AccessRightsMap]>, // null if type != ArgumentType.KEY
  value: FieldState<string>
}

export type FormDeployArgument = FormState<DeployArgument>;
type FormDeployArguments = FormState<FormDeployArgument[]>;

export type DeployConfiguration = {
  contractType: FieldState<DeployUtil.ContractType | null>,
  contractHash: FieldState<string>,
  contractName: FieldState<string>,
  entryPoint: FieldState<string>,
  paymentAmount: FieldState<number>,
  fromAddress: FieldState<string>
}

export type FormDeployConfiguration = FormState<DeployConfiguration>;

interface RawDeployArguments {
  name: string,
  type: SupportedType,
  secondType: KeyType | BitWidth | null,
  URefAccessRight: Key.URef.AccessRightsMap[keyof Key.URef.AccessRightsMap],
  value: string
}

interface UserInputPersistent {
  deployConfiguration: {
    contractType: DeployUtil.ContractType | null,
    paymentAmount: number,
    fromAddress: string
  },
  deployArguments: RawDeployArguments[],
  editingDeployArguments: RawDeployArguments[],
  editing: boolean
}

export class DeployContractsContainer {
  @observable deployConfiguration: FormDeployConfiguration = new FormState<DeployConfiguration>({
    contractType: new FieldState<DeployUtil.ContractType | null>(null).validators(valueRequired),
    contractHash: new FieldState('').disableAutoValidation(),
    paymentAmount: new FieldState<number>(10000000).validators(
      numberGreaterThan(0),
      validateInt
    ),
    fromAddress: new FieldState<string>(''),
    contractName: new FieldState<string>('') ,
    entryPoint: new FieldState<string>('call'),
  }).compose().validators(deployConfiguration => {
    if (deployConfiguration.contractType.$ === DeployUtil.ContractType.Hash) {
      let value = deployConfiguration.contractHash.value;
      let v = validateBase16(value) || valueRequired(value) || valueRequired(deployConfiguration.entryPoint.value);
      if (v !== false) {
        deployConfiguration.contractHash.setError(v);
      }
      return v;
    } else if (deployConfiguration.contractType.$ === DeployUtil.ContractType.WASM) {
      // WASM
      if (!this.selectedFile) {
        const msg = 'Upload WASM file firstly';
        alert(msg);
        return msg;
      }
    } else if (deployConfiguration.contractType.$ === DeployUtil.ContractType.Name){
      let v = valueRequired(deployConfiguration.contractName.value) || valueRequired(deployConfiguration.entryPoint.value);
      if (v !== false) {
        deployConfiguration.contractHash.setError(v);
      }
      return v;
    }
    return false;
  });
  @observable deployArguments: FormDeployArguments = new FormState<FormDeployArgument[]>([]);
  @observable editingDeployArguments: FormDeployArguments = new FormState<FormDeployArgument[]>([]);
  @observable selectedFile: File | null = null;
  @observable editing: boolean = false;
  @observable signDeployModal: boolean = false;
  // indicate whether we are waiting for signing from plugin
  @observable signing: boolean = false;
  // hash of deploy result
  @observable deployedHash: string | null = null;
  private selectedFileContent: null | ByteArray = null;
  private static PersistentKey = 'deploy-configuration';

  // id for accordion
  accordionId = 'deploy-table-accordion';

  constructor(
    private errors: ErrorContainer,
    private casperService: CasperService
  ) {
    this.tryRestore();
  }

  @action.bound
  removeDeployArgument(deployArgument: FormDeployArgument) {
    let i = this.deployArguments.$.findIndex((f) => f === deployArgument);
    this.deployArguments.$.splice(i, 1);
    this.saveToSessionStore();
  }

  @action.bound
  addNewEditingDeployArgument() {
    this.editing = true;
    this.editingDeployArguments.$.push(this.newDeployArgument());
  }

  private newDeployArgument(
    name: string = '',
    type: SupportedType = CLType.Simple.BOOL,
    secondType: KeyType | BitWidth | null = null,
    accessRight: Key.URef.AccessRightsMap[keyof Key.URef.AccessRightsMap] = Key.URef.AccessRights.NONE,
    value: string = ''
  ) {
    return new FormState({
      name: new FieldState<string>(name).disableAutoValidation().validators(valueRequired),
      type: new FieldState<SupportedType>(type),
      secondType: new FieldState<KeyType | BitWidth | null>(secondType),
      URefAccessRight: new FieldState<Key.URef.AccessRightsMap[keyof Key.URef.AccessRightsMap]>(accessRight),
      value: new FieldState<string>(value).disableAutoValidation().validators(valueRequired)
    }).compose().validators(this.validateDeployArgument);
  }

  @action.bound
  handleFileSelect(e: React.ChangeEvent<HTMLInputElement>) {
    if (e.target.files) {
      this.selectedFileContent = null;
      this.selectedFile = e.target.files[0];
      const reader = new FileReader();
      reader.readAsArrayBuffer(this.selectedFile);
      reader.onload = _ => {
        this.selectedFileContent = new Uint8Array(reader.result as ArrayBuffer);
      };
    }
  }

  @action.bound
  async saveEditingDeployArguments() {
    const res = await this.editingDeployArguments.validate();
    if (!res.hasError) {
      while (this.editingDeployArguments.$.length) {
        this.deployArguments.$.push(this.editingDeployArguments.$.shift()!);
      }
      this.editing = false;
    }
    // persist manually
    this.saveToSessionStore();
  }

  @action.bound
  cancelEditing() {
    this.editingDeployArguments.$.splice(0, this.editingDeployArguments.$.length);
    this.editing = false;
    this.saveToSessionStore();
  }

  @action.bound
  clearForm() {
    let msg = 'Do you want to clear the form?';
    if (window.confirm(msg)) {
      this.deployConfiguration.reset();
      this.editing = false;
      this.editingDeployArguments.reset();
      this.deployArguments.reset();
    }
  }

  @action.bound
  async openSignModal() {
    let v1 = await this.deployConfiguration.validate();
    let v2 = await this.deployArguments.validate();
    if (v1.hasError || v2.hasError) {
      return;
    } else {
      this.signDeployModal = true;
    }
  }

  @action.bound
  async onSubmit() {
    return this.errors.withCapture(this._onSubmit()).catch(() => {
      return false;
    });
  }

  @action.bound
  async _onSubmit() {
    this.deployedHash = null;
    if (!Signer.isConnected()) {
      throw new Error('Please install the CasperLabs Sign Helper Plugin first!');
    }

    const publicKeyBase64 = await Signer.getSelectedPublicKeyBase64();
    if (!publicKeyBase64) {
      throw new Error('Please create an account in the Plugin first!');
    }
    // Todo: (ECO-441) make Signer return publicKeyHash directly
    const publicKeyHash = publicKeyHashForEd25519(publicKeyBase64);
    let deploy = await this.makeDeploy(publicKeyHash);
    if (!deploy) {
      return false;
    }

    this.signing = true;
    let sigBase64;
    try {
      sigBase64 = await Signer.sign(encodeBase16(deploy!.getDeployHash_asU8()));
      this.signing = false;
      let signedDeploy = DeployUtil.setSignature(
        deploy,
        decodeBase64(sigBase64),
        decodeBase64(publicKeyBase64)
      );
      await this.casperService.deploy(signedDeploy);
      ($(`#${this.accordionId}`) as any).collapse('hide');
      this.deployedHash = encodeBase16(signedDeploy.getDeployHash_asU8());
      return true;
    } catch (e) {
      this.signing = false;
      throw e;
    }
  }

  private async makeDeploy(publicKeyHash: Uint8Array): Promise<Deploy | null> {
    let deployConfigurationForm = await this.deployConfiguration.validate();
    let deployArguments = await this.deployArguments.validate();
    if (deployConfigurationForm.hasError || deployArguments.hasError) {
      return null;
    } else {
      const config = deployConfigurationForm.value;
      const args = deployArguments.value;
      let type: DeployUtil.ContractType;
      let session: ByteArray | string;

      let argsProto = args.map((arg: FormState<DeployArgument>) => {
        return this.buildArgument(arg);
      });
      const paymentAmount = JSBI.BigInt(config.paymentAmount.value);

      if (config.contractType.value === DeployUtil.ContractType.WASM) {
        session = this.selectedFileContent!;
        return DeployUtil.makeDeploy(argsProto, DeployUtil.ContractType.WASM, session, null, paymentAmount, publicKeyHash);
      } else if (config.contractType.value === DeployUtil.ContractType.Hash){
        session = decodeBase16(config.contractHash.value);
        const entryPoint = config.entryPoint.value;
        return DeployUtil.makeDeploy(argsProto, DeployUtil.ContractType.Hash, session, null, paymentAmount, publicKeyHash, [], entryPoint);
      } else if (config.contractType.value === DeployUtil.ContractType.Name){
        session = config.contractName.value;
        const entryPoint = config.entryPoint.value;
        return DeployUtil.makeDeploy(argsProto, DeployUtil.ContractType.Name, session, null, paymentAmount, publicKeyHash, [], entryPoint);
      }
      return Promise.resolve(null);
    }
  }

  private buildArgument(arg: FormState<DeployArgument>) {
    const argValueStr: string = arg.$.value.value;
    const type = arg.$.type.$;
    let clValueInstance: CLValueInstance;
    if (type === 'Bytes') {
      clValueInstance = this.buildBytesTypeArg(argValueStr);
    } else {
      const simpleType = type as CLType.SimpleMap[keyof CLType.SimpleMap];
      clValueInstance = this.buildSimpleTypeArg(simpleType, argValueStr, arg);
    }

    const deployArg = new Deploy.Arg();
    deployArg.setName(arg.$.name.value);
    deployArg.setValue(clValueInstance);
    return deployArg;
  }

  private buildBytesTypeArg(argValueStr: string): CLValueInstance {
    const innerType = new CLType();
    innerType.setSimpleType(CLType.Simple.U8);
    const fixedListType = new CLType.FixedList();
    fixedListType.setInner(innerType);
    const bytes = decodeBase16(argValueStr);
    fixedListType.setLen(bytes.length);

    const clType = new CLType();
    clType.setFixedListType(fixedListType);

    const value = new CLValueInstance.Value();
    value.setBytesValue(bytes);

    const clValueInstance = new CLValueInstance();
    clValueInstance.setClType(clType);
    clValueInstance.setValue(value);
    return clValueInstance;
  }

  private buildSimpleTypeArg(simpleType: CLType.SimpleMap[keyof CLType.SimpleMap], argValueStr: string, arg: FormState<DeployArgument>): CLValueInstance {
    const value = new CLValueInstance.Value();
    const clType = new CLType();
    switch (simpleType) {
      case CLType.Simple.U8:
        value.setU8(parseInt(argValueStr));
        break;
      case CLType.Simple.U32:
        value.setU32(parseInt(argValueStr));
        break;
      case CLType.Simple.U64:
        value.setU64(parseInt(argValueStr));
        break;
      case CLType.Simple.I32:
        value.setI32(parseInt(argValueStr));
        break;
      case CLType.Simple.I64:
        value.setI64(parseInt(argValueStr));
        break;
      case CLType.Simple.U128:
        const u128 = new CLValueInstance.U128();
        u128.setValue(argValueStr);
        value.setU128(u128);
        break;
      case CLType.Simple.U256:
        const u256 = new CLValueInstance.U256();
        u256.setValue(argValueStr);
        value.setU256(u256);
        break;
      case CLType.Simple.U512:
        const u512 = new CLValueInstance.U512();
        u512.setValue(argValueStr);
        value.setU256(u512);
        break;
      case CLType.Simple.STRING:
        value.setStrValue(argValueStr);
        break;
      case CLType.Simple.BOOL:
        value.setBoolValue(validator.toBoolean(argValueStr));
        break;
      case CLType.Simple.KEY:
        const key = new Key();
        let keyType = arg.$.secondType.value as KeyType;
        let valueInByteArray = decodeBase16(argValueStr);
        switch (keyType) {
          case KeyType.ADDRESS:
            const address = new Key.Address();
            address.setAccount(valueInByteArray);
            key.setAddress(address);
            break;
          case KeyType.HASH:
            const hash = new Key.Hash();
            hash.setHash(valueInByteArray);
            key.setHash(hash);
            break;
          case KeyType.UREF:
            const uRef = new Key.URef();
            uRef.setUref(valueInByteArray);
            uRef.setAccessRights(arg.$.URefAccessRight.value!);
            key.setUref(uRef);
            break;
        }
        value.setKey(key);
        break;
      case CLType.Simple.UREF:
        const URef = new Key.URef();
        URef.setAccessRights(arg.$.URefAccessRight.value!);
        URef.setUref(decodeBase16(argValueStr));
        value.setUref(URef);
        break;
    }
    clType.setSimpleType(simpleType);

    const clValueInstance = new CLValueInstance();
    clValueInstance.setClType(clType);
    clValueInstance.setValue(value);
    return clValueInstance;
  }

  /**
   * Implement validator for DeployArgument which can be used in formstate library,
   * If a truthy string is returned it represents a validation error.
   * @param deployArgument
   */
  private validateDeployArgument(deployArgument: DeployArgument): string | false {
    const value = deployArgument.value.$;
    switch (deployArgument.type.$) {
      case CLType.Simple.U8:
      case CLType.Simple.U32:
      case CLType.Simple.U64:
      case CLType.Simple.I32:
      case CLType.Simple.I64:
      case CLType.Simple.U128:
      case CLType.Simple.U256:
      case CLType.Simple.U512:
        let limit: { min: JSBI, max: JSBI } = (NumberLimit as any)[deployArgument.type.value];
        if (!validator.isNumeric(value)) {
          return `Value should be a number`;
        }
        const v = JSBI.BigInt(value);
        if (v < limit.min || v > limit.max) {
          return `Value should be in [${limit.min.toString(10)}, ${limit.max.toString(10)}]`;
        }
        return false;
      case CLType.Simple.STRING:
        return false;
      case CLType.Simple.BOOL:
        if (!validator.isBoolean(value)) {
          return `Value should be true or false`;
        }
        return false;
      case CLType.Simple.KEY:
      case CLType.Simple.UREF:
        if (!validator.isHexadecimal(value)) {
          return `Value should be base16`;
        }
        return false;
      case 'Bytes':
        if (!validator.isHexadecimal(value)) {
          return `Value should be base16`;
        }
        return false;
    }
    return false;
  }

  @action
  private tryRestore() {
    const preState = localStorage.getItem(DeployContractsContainer.PersistentKey);
    if (preState !== null) {
      const value = JSON.parse(preState) as UserInputPersistent;

      this.editing = value.editing;

      this.deployConfiguration.$.paymentAmount.onChange(value.deployConfiguration.paymentAmount);
      this.deployConfiguration.$.contractType.onChange(value.deployConfiguration.contractType);
      this.deployConfiguration.$.fromAddress.onChange(value.deployConfiguration.fromAddress);

      this.editingDeployArguments.reset();
      this.deployArguments.reset();

      value.editingDeployArguments?.forEach(arg => {
        const deployArgument = this.newDeployArgument(arg.name, arg.type, arg.secondType, arg.URefAccessRight, arg.value);
        this.editingDeployArguments.$.push(deployArgument);
      });

      value.deployArguments?.forEach(arg => {
        const deployArgument = this.newDeployArgument(arg.name, arg.type, arg.secondType, arg.URefAccessRight, arg.value);
        this.deployArguments.$.push(deployArgument);
      });
    }
  }

  public saveToSessionStore() {
    let deployConfiguration = this.deployConfiguration.$;
    let state: UserInputPersistent = {
      deployArguments: this.deployArguments.$.map(arg => {
        const value = arg.$;
        return {
          name: value.name.value,
          type: value.type.value,
          secondType: value.secondType.value,
          URefAccessRight: value.URefAccessRight.value,
          value: value.value.value
        };
      }),
      deployConfiguration: {
        contractType: deployConfiguration.contractType.value,
        paymentAmount: deployConfiguration.paymentAmount.value,
        fromAddress: deployConfiguration.fromAddress.value
      },
      editing: this.editing,
      editingDeployArguments: this.editingDeployArguments.$.map(arg => {
        const value = arg.$;
        return {
          name: value.name.value,
          type: value.type.value,
          secondType: value.secondType.value,
          URefAccessRight: value.URefAccessRight.value,
          value: value.value.value
        };
      })
    };

    localStorage.setItem(DeployContractsContainer.PersistentKey, JSON.stringify(state));
  }
}
