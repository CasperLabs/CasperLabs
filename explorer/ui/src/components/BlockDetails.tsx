import React from 'react';
import { RouteComponentProps, withRouter, Link } from 'react-router-dom';
import { observer } from 'mobx-react';
import { BlockContainer } from '../containers/BlockContainer';
import DataTable from './DataTable';
import { BlockInfo } from 'casperlabs-grpc/io/casperlabs/casper/consensus/info_pb';
import Pages from './Pages';
import { RefreshableComponent, Icon, SuccessIcon, FailIcon } from './Utils';
import { BlockDAG } from './BlockDAG';
import { Block } from 'casperlabs-grpc/io/casperlabs/casper/consensus/consensus_pb';
import { shortHash } from './Utils';
import ObservableValueMap, { ObservableValue } from '../lib/ObservableValueMap';
import { decodeBase16, encodeBase16 } from 'casperlabs-sdk';

// https://www.pluralsight.com/guides/react-router-typescript

// URL parameter
type Params = {
  blockHashBase16: string;
};

interface Props extends RouteComponentProps<Params> {
  block: BlockContainer;
}

@observer
class _BlockDetails extends RefreshableComponent<Props, {}> {
  constructor(props: Props) {
    super(props);
    this.props.block.init(decodeBase16(this.blockHashBase16));
  }

  get blockHashBase16() {
    return this.props.match.params.blockHashBase16;
  }

  get container() {
    return this.props.block;
  }

  async refresh() {
    await this.container.loadBlock();
    await this.container.loadNeighborhood();
    await this.container.loadDeploys();
    await this.container.loadBalances();
  }

  componentDidUpdate() {
    // Container and component stays the same during navigation.
    if (this.blockHashBase16 !== this.container.blockHashBase16) {
      this.container.init(decodeBase16(this.blockHashBase16));
      this.refresh();
    }
  }

  render() {
    return (
      <div>
        <BlockTable
          blockHashBase16={this.blockHashBase16}
          block={this.container.block}
          refresh={() => this.container.loadBlock()}
        />
        <DeploysTable
          blockHashBase16={this.blockHashBase16}
          deploys={this.container.deploys}
          balances={this.container.balances}
        />
        <BlockDAG
          title={`Neighborhood of block ${this.blockHashBase16}`}
          refresh={() => this.container.loadNeighborhood()}
          blocks={this.container.neighborhood}
          hideBallotsToggleStore={this.container.hideBallotsToggleStore}
          width="100%"
          height="400px"
          selected={this.container.block!}
          depth={this.container.depth}
          onSelected={block => {
            let target = encodeBase16(block.getSummary()!.getBlockHash_asU8());
            if (target !== this.blockHashBase16) {
              this.props.history.push(Pages.block(target));
            }
          }}
          onDepthChange={depth => {
            this.container.depth = depth;
            this.container.loadNeighborhood();
          }}
        />
      </div>
    );
  }
}

// Inject the router parameters so we can extract the ID from the URL.
export const BlockDetails = withRouter(_BlockDetails);

const BlockTable = observer(
  (props: {
    blockHashBase16: string;
    block: BlockInfo | null;
    refresh: () => void;
  }) => {
    const attrs = props.block && blockAttrs(props.block);
    return (
      <DataTable
        title={`Block ${props.blockHashBase16}`}
        headers={[]}
        rows={attrs}
        renderRow={(attr, i) => (
          <tr key={i}>
            <th>{attr[0]}</th>
            <td>{attr[1]}</td>
          </tr>
        )}
        refresh={() => props.refresh()}
      />
    );
  }
);

const DeploysTable = observer(
  (props: {
    blockHashBase16: string;
    deploys: Block.ProcessedDeploy[] | null;
    balances: ObservableValueMap<string, number>;
  }) => {
    return (
      <DataTable
        title={`Deploys in block ${props.blockHashBase16}`}
        headers={[
          'Deploy Hash',
          'Account',
          'Stage',
          'Cost',
          'Remaining Balance',
          'Result',
          'Message'
        ]}
        rows={props.deploys}
        renderRow={(deploy, i) => {
          const id = encodeBase16(deploy.getDeploy()!.getDeployHash_asU8());
          const accountId = encodeBase16(
            deploy
              .getDeploy()!
              .getHeader()!
              .getAccountPublicKeyHash_asU8()
          );
          return (
            <tr key={i}>
              <td>
                <Link to={Pages.deploy(id)}>{shortHash(id)}</Link>
              </td>
              <td>{shortHash(accountId)}</td>
              <td>{deploy.getStage()}</td>
              <td className="text-right">
                {deploy.getCost().toLocaleString()}
              </td>
              <td className="text-right">
                <Balance balance={props.balances.get(accountId)} />
              </td>
              <td className="text-center">
                {deploy.getIsError() ? (
                  <FailIcon/>
                ) : (
                    <SuccessIcon/>
                  )}
              </td>
              <td>{deploy.getErrorMessage()}</td>
            </tr>
          );
        }}
      />
    );
  }
);

const blockAttrs: (block: BlockInfo) => Array<[string, any]> = (
  block: BlockInfo
) => {
  const id = encodeBase16(block.getSummary()!.getBlockHash_asU8());
  const header = block.getSummary()!.getHeader()!;
  const validatorId = encodeBase16(header.getValidatorPublicKeyHash_asU8());
  const stats = block.getStatus()!.getStats()!;
  return [
    ['Block Hash', id],
    ['Key Block Hash',
      <Link to={Pages.block(encodeBase16(header.getKeyBlockHash_asU8()))}>
        {shortHash(header.getKeyBlockHash_asU8())}
      </Link>],
    ['j-Rank', header.getJRank()],
    ['m-Rank', header.getMainRank()],
    ['Round ID', header.getRoundId()],
    ['Timestamp', new Date(header.getTimestamp()).toISOString()],
    ['Type', <BlockType header={header} />],
    ['Role', <BlockRole header={header} />],
    ['Validator', validatorId],
    ['Validator Block Number', header.getValidatorBlockSeqNum()],
    [
      'Validator Stake',
      (() => {
        let validatorBond = header
          .getState()!
          .getBondsList()
          .find(
            x =>
              encodeBase16(x.getValidatorPublicKeyHash_asU8()) === validatorId
          );
        // Genesis doesn't have a validator.
        return (
          (validatorBond &&
            validatorBond.getStake() &&
            Number(validatorBond.getStake()!.getValue()).toLocaleString()) ||
          null
        );
      })()
    ],
    ['Deploy Count', header.getDeployCount()],
    ['Deploy Error Count', stats.getDeployErrorCount()],
    ['Deploy Cost Total', stats.getDeployCostTotal().toLocaleString()],
    ['Deploy Gas Price Average', stats.getDeployGasPriceAvg().toLocaleString()],
    ['Block Size (bytes)', stats.getBlockSizeBytes().toLocaleString()],
    [
      'Finality',
      <FinalityIcon block={block} />
    ],
    [
      'Parents',
      <ul>
        {header.getParentHashesList_asU8().map((x, idx) => (
          <li key={idx}>
            <BlockLink blockHash={x} />
          </li>
        ))}
      </ul>
    ],
    [
      'Children',
      <ul>
        {block
          .getStatus()!
          .getChildHashesList_asU8()
          .map((x, idx) => (
            <li key={idx}>
              <BlockLink blockHash={x} />
            </li>
          ))}
      </ul>
    ]
  ];
};

export const BlockLink = (props: { blockHash: ByteArray }) => {
  let id = encodeBase16(props.blockHash);
  return <Link to={Pages.block(id)}>{id}</Link>;
};

// Need to observe the balance to react to when it's available.
export const Balance = observer(
  (props: { balance: ObservableValue<number> }) => {
    const value = props.balance.value;
    if (value == null) return null;
    return <span>{value.toLocaleString()}</span>;
  }
);

export const BlockType = (props: { header: Block.Header }) => {
  let typ = props.header.getMessageType();
  let lbl = typ === Block.MessageType.BLOCK ? "Block" : typ === Block.MessageType.BALLOT ? "Ballot" : "n/a"
  return <span>{lbl}</span>;
}

export const BlockRole = (props: { header: Block.Header }) => {
  let role = props.header.getMessageRole()
  let lbl =
    role === Block.MessageRole.PROPOSAL ? "Proposal" :
      role === Block.MessageRole.CONFIRMATION ? "Confirmation" :
        role === Block.MessageRole.WITNESS ? "Witness" :
          "n/a";
  return <span>{lbl}</span>;
}

export const FinalityIcon = (props: { block: BlockInfo }) => {
  if (props.block.getSummary()?.getHeader()!.getMessageType() === Block.MessageType.BALLOT)
    return null;

  let finality = props.block.getStatus()!.getFinality();
  if (finality === BlockInfo.Status.Finality.FINALIZED) {
    return <SuccessIcon/>
  } else if (finality === BlockInfo.Status.Finality.ORPHANED)
    return <FailIcon/>
  else {
    return <Icon name="clock" />
  }
}

export default BlockDetails;
