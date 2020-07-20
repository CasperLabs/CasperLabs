import React from 'react';
import { RouteComponentProps, withRouter, Link } from 'react-router-dom';
import { observer } from 'mobx-react';
import { DeployContainer } from '../containers/DeployContainer';
import DataTable from './DataTable';
import { DeployInfo } from 'casperlabs-grpc/io/casperlabs/casper/consensus/info_pb';
import Pages from './Pages';
import {
  RefreshableComponent,
  Icon,
  shortHash,
  FailIcon,
  SuccessIcon
} from './Utils';
import ObservableValueMap from '../lib/ObservableValueMap';
import { Balance, FinalityIcon } from './BlockDetails';
import { decodeBase16, encodeBase16 } from 'casperlabs-sdk';

// URL parameter
type Params = {
  deployHashBase16: string;
};

interface Props extends RouteComponentProps<Params> {
  deploy: DeployContainer;
}

@observer
class _DeployDetails extends RefreshableComponent<Props, {}> {
  constructor(props: Props) {
    super(props);
    this.props.deploy.init(decodeBase16(this.deployHashBase16));
  }

  get deployHashBase16() {
    return this.props.match.params.deployHashBase16;
  }

  get container() {
    return this.props.deploy;
  }

  async refresh() {
    await this.container.loadDeploy();
    await this.container.loadBalances();
  }

  componentDidUpdate() {
    // Container and component stays the same during naviagation.
    if (this.deployHashBase16 !== this.container.deployHashBase16) {
      this.container.init(decodeBase16(this.deployHashBase16));
      this.refresh();
    }
  }

  render() {
    return (
      <div>
        <DeployTable
          deployHashBase16={this.deployHashBase16}
          deploy={this.container.deploy}
        />
        <ResultsTable
          deployHashBase16={this.deployHashBase16}
          deploy={this.container.deploy}
          balances={this.container.balances}
          refresh={() => this.refresh()}
        />
      </div>
    );
  }
}

// Inject the router parameters so we can extract the ID from the URL.
export const DeployDetails = withRouter(_DeployDetails);

const DeployTable = observer(
  (props: { deployHashBase16: string; deploy: DeployInfo | null }) => {
    const attrs = props.deploy && deployAttrs(props.deploy);
    return (
      <DataTable
        title={`Deploy ${props.deployHashBase16}`}
        headers={[]}
        rows={attrs}
        renderRow={(attr, i) => (
          <tr key={i}>
            <th>{attr[0]}</th>
            <td>{attr[1]}</td>
          </tr>
        )}
      />
    );
  }
);

const ResultsTable = observer(
  (props: {
    deployHashBase16: string;
    deploy: DeployInfo | null;
    balances: ObservableValueMap<string, number>;
    refresh: () => void;
  }) => {
    return (
      <DataTable
        title={`Results for deploy ${props.deployHashBase16}`}
        headers={[
          'Block Hash',
          'Finality',
          'Cost',
          'Remaining Balance',
          'Result',
          'Message'
        ]}
        rows={props.deploy && props.deploy.getProcessingResultsList()}
        renderRow={(proc, i) => {
          const id = encodeBase16(
            proc
              .getBlockInfo()!
              .getSummary()!
              .getBlockHash_asU8()
          );
          return (
            <tr key={i}>
              <td>
                <Link to={Pages.block(id)}>{shortHash(id)}</Link>
              </td>
              <td>
                <FinalityIcon block={proc.getBlockInfo()!} />
              </td>
              <td className="text-right">{proc.getCost().toLocaleString()}</td>
              <td className="text-right">
                <Balance balance={props.balances.get(id)} />
              </td>
              <td className="text-center">
                {proc.getIsError() ? (
                  <FailIcon/>
                ) : (
                  <SuccessIcon/>
                  )}
              </td>
              <td>{proc.getErrorMessage()}</td>
            </tr>
          );
        }}
        refresh={() => props.refresh()}
      />
    );
  }
);

const deployAttrs: (deploy: DeployInfo) => Array<[string, any]> = (
  deploy: DeployInfo
) => {
  const id = encodeBase16(deploy.getDeploy()!.getDeployHash_asU8());
  const header = deploy.getDeploy()!.getHeader()!;
  return [
    ['Deploy Hash', id],
    [
      'Account Public Key Hash',
      encodeBase16(header.getAccountPublicKeyHash_asU8())
    ],
    ['Timestamp', new Date(header.getTimestamp()).toISOString()],
    ['Gas Price', header.getGasPrice().toLocaleString()]
  ];
};

export default DeployDetails;
