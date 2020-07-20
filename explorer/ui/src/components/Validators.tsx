import React from 'react';
import { observer } from 'mobx-react';
import { RefreshableComponent, shortHash } from './Utils';
import DataTable from './DataTable';
import ValidatorsContainer, {
  ValidatorInfo
} from '../containers/ValidatorsContainer';
import { base64to16, encodeBase16 } from 'casperlabs-sdk';
import { Link } from 'react-router-dom';
import Pages from './Pages';
import Timestamp from './TimeStamp';

interface Props {
  validatorsContainer: ValidatorsContainer;
}

@observer
class Validators extends RefreshableComponent<Props, {}> {

  componentWillUnmount(): void {
    this.props.validatorsContainer.toggleableSubscriber.unsubscribeAndFree();
  }

  refresh(): void {
    this.props.validatorsContainer.refresh();
    this.props.validatorsContainer.toggleableSubscriber.setUpSubscriber();
  }

  render() {
    return (
      <DataTable title="Validators" headers={['Validator ID', 'Latest Block Hash', 'JRank', 'Timestamp']}
                 rows={this.props.validatorsContainer.validatorInfos}
                 subscribeToggleStore={this.props.validatorsContainer.toggleableSubscriber.subscribeToggleStore}
                 refresh={() => this.refresh()}
                 renderRow={(validatorInfo: ValidatorInfo, idx) => {
                   const blockHashBase16 = encodeBase16(validatorInfo.latestBlockHash);
                   return (
                     <tr key={validatorInfo.id}>
                       <td>{base64to16(validatorInfo.id)}</td>
                       <td>
                         <Link to={Pages.block(blockHashBase16)}>{shortHash(blockHashBase16)}</Link>
                       </td>
                       <td>{validatorInfo.rank}</td>
                       <td>
                         <Timestamp timestamp={validatorInfo.timestamp}/>
                       </td>
                     </tr>
                   );
                 }}/>
    );
  }
};

export default Validators;
