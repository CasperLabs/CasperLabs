import { computed } from 'mobx';

import ErrorContainer from './ErrorContainer';
import StorageCell from '../lib/StorageCell';
import FaucetService from '../services/FaucetService';
import { DeployInfo } from 'casperlabs-grpc/io/casperlabs/casper/consensus/info_pb';
import { GrpcError, CasperService} from 'casperlabs-sdk';
import { grpc } from '@improbable-eng/grpc-web';
import { getPublicKeyHashBase64 } from './AuthContainer';

export class FaucetContainer {
  private _faucetRequests = new StorageCell<FaucetRequest[]>(
    'faucet-requests',
    []
  );

  // Start polling for status when we add a new faucet request.
  private faucetStatusTimerId = 0;
  private faucetStatusInterval = 10 * 1000;

  constructor(
    private errors: ErrorContainer,
    private faucetService: FaucetService,
    private casperService: CasperService,
    // Callback when the faucet status finished so we can update the balances.
    private onFaucetStatusChange: () => void
  ) {}

  /** Ask the faucet for tokens for a given account. */
  async requestTokens(account: UserAccount) {
    const request = async () => {
      const deployHash = await this.faucetService.requestTokens(
        getPublicKeyHashBase64(account)
      );
      this.monitorFaucetRequest(account, deployHash);
    };
    await this.errors.capture(request());
  }

  /** List faucet requests we sent earlier. */
  @computed get faucetRequests() {
    return this._faucetRequests.get;
  }

  private monitorFaucetRequest(account: UserAccount, deployHash: DeployHash) {
    const request = { timestamp: new Date(), account, deployHash };
    const requests = [request].concat(this._faucetRequests.get);
    this._faucetRequests.set(requests);
    this.startPollingFaucetStatus();
  }

  private startPollingFaucetStatus() {
    if (this.faucetStatusTimerId === 0) {
      this.faucetStatusTimerId = window.setInterval(
        () => this.refreshFaucetRequestStatus(),
        this.faucetStatusInterval
      );
    }
  }

  async refreshFaucetRequestStatus() {
    const requests = this._faucetRequests.get;
    let updated = false;
    let anyNeededUpdate = false;
    for (let req of requests) {
      const needsUpdate =
        typeof req.deployInfo === 'undefined' ||
        req.deployInfo!.processingResultsList.length === 0;

      if (needsUpdate) {
        anyNeededUpdate = true;
        const info = await this.errors.withCapture(
          this.tryGetDeployInfo(req.deployHash)
        );
        if (info != null) {
          req.deployInfo = info.toObject();
          updated = true;
        }
      }
    }
    if (updated) {
      this._faucetRequests.set(requests);
      this.onFaucetStatusChange();
    }
    if (!anyNeededUpdate) {
      window.clearInterval(this.faucetStatusTimerId);
      this.faucetStatusTimerId = 0;
    }
  }

  private async tryGetDeployInfo(
    deployHash: DeployHash
  ): Promise<DeployInfo | null> {
    try {
      return await this.casperService.getDeployInfo(deployHash);
    } catch (err) {
      if (err instanceof GrpcError) {
        if (err.code === grpc.Code.NotFound)
          // We connected to a node that doesn't have it yet.
          return null;
      }
      throw err;
    }
  }
}

// Record of a request we submitted.
export interface FaucetRequest {
  timestamp: Date;
  account: UserAccount;
  deployHash: DeployHash;
  // Assigned later when the data becomes available.
  deployInfo?: DeployInfo.AsObject;
}

export default FaucetContainer;
