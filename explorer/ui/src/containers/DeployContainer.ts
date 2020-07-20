import { observable, action, computed } from 'mobx';

import ErrorContainer from './ErrorContainer';
import { CasperService, BalanceService, encodeBase16 } from 'casperlabs-sdk';
import { DeployInfo } from 'casperlabs-grpc/io/casperlabs/casper/consensus/info_pb';
import ObservableValueMap from '../lib/ObservableValueMap';

export class DeployContainer {
  @observable deployHash: ByteArray | null = null;
  @observable deploy: DeployInfo | null = null;
  @observable balances: ObservableValueMap<
    string,
    number
  > = new ObservableValueMap();

  constructor(
    private errors: ErrorContainer,
    private casperService: CasperService,
    private balanceService: BalanceService
  ) {}

  /** Call whenever the page switches to a new deploy. */
  @action
  init(deployHash: ByteArray) {
    this.deployHash = deployHash;
    this.deploy = null;
    this.balances.clear();
  }

  @computed get deployHashBase16() {
    return this.deployHash && encodeBase16(this.deployHash);
  }

  async loadDeploy() {
    if (this.deployHash == null) return;
    await this.errors.capture(
      this.casperService.getDeployInfo(this.deployHash).then(deploy => {
        this.deploy = deploy;
      })
    );
  }

  /** Load the balances of the account at each block where the deploy was executed. */
  async loadBalances() {
    if (this.deploy == null) {
      return;
    }
    for (let proc of this.deploy.getProcessingResultsList()) {
      const blockHash = proc
        .getBlockInfo()!
        .getSummary()!
        .getBlockHash_asU8();
      const balance = await this.balanceService.getAccountBalance(
        blockHash,
        this.deploy
          .getDeploy()!
          .getHeader()!
          .getAccountPublicKeyHash_asU8()
      );
      if (balance !== undefined) {
        this.balances.set(encodeBase16(blockHash), balance);
      }
    }
  }
}

export default DeployContainer;
