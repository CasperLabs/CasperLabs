import React from 'react';
import { observer } from 'mobx-react';
import DataTable from './DataTable';
import { BlockInfo } from 'casperlabs-grpc/io/casperlabs/casper/consensus/info_pb';
import { encodeBase16 } from 'casperlabs-sdk';
import { Icon } from './Utils';
import { Bond } from 'casperlabs-grpc/io/casperlabs/casper/consensus/consensus_pb';

export const BondedValidatorsTable = observer(
  (props: { block: BlockInfo; lastFinalizedBlock: BlockInfo | undefined }) => {
    let finalizedBondedValidators = new Set();

    // Since js doesn't support taking tuple as the key of map/set, we need encode it.
    // The char `_` is not used in Base64, so it is safe to use it as separator.
    let key = (b: Bond) =>
      `${b.getValidatorPublicKeyHash_asB64()}_${b.getStake()}`;

    if (props.lastFinalizedBlock) {
      let finalizedBonds = props.lastFinalizedBlock
        .getSummary()!
        .getHeader()!
        .getState()!
        .getBondsList();
      finalizedBondedValidators = new Set(
        finalizedBonds.map(bond => key(bond))
      );
    }
    let bondsList = props.block
      .getSummary()!
      .getHeader()!
      .getState()!
      .getBondsList();
    return (
      <DataTable
        title={`Bonded Validators List (${bondsList.length})`}
        headers={['Validator', 'Stake', 'Finalized']}
        rows={bondsList}
        renderRow={(bond, i) => {
          return (
            <tr key={i}>
              <td className="text-left">
                {encodeBase16(bond.getValidatorPublicKeyHash_asU8())}
              </td>
              <td className="text-right">
                {Number(bond.getStake()!.getValue()).toLocaleString()}
              </td>
              <td className="text-center">
                {finalizedBondedValidators.has(
                  key(bond)
                ) ? (
                  <Icon name="check-circle" color="green" />
                ) : (
                  <Icon name="clock" />
                )}
              </td>
            </tr>
          );
        }}
      />
    );
  }
);
