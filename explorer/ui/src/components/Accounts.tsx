import React from 'react';
import { observer } from 'mobx-react';

import AuthContainer, {
  publicKeyHashForEd25519,
  ImportAccountFormData,
  NewAccountFormData,
  getPublicKeyHash
} from '../containers/AuthContainer';
import { Button, IconButton, ListInline, RefreshableComponent } from './Utils';
import Modal from './Modal';
import { FileSelect, Form, SelectField, TextField } from './Forms';
import {
  base64to16, decodeBase64,
  DeployUtil,
  encodeBase16,
  encodeBase64
} from 'casperlabs-sdk';
import { ObservableValue } from '../lib/ObservableValueMap';
import DataTable from './DataTable';

interface Props {
  auth: AuthContainer;
}

@observer
export default class Accounts extends RefreshableComponent<Props, {}> {
  async refresh(force?: boolean) {
    await this.props.auth.refreshAccounts();
    await this.props.auth.refreshBalances(force);
  }

  render() {
    const accountForm = this.props.auth.accountForm;

    let modalAccountForm;
    if (accountForm instanceof NewAccountFormData) {
      // Help IDE infer that the type of accountForm is NewAccountFormData
      let newAccountForm = accountForm;
      modalAccountForm = (
        <Modal
          id="new-account"
          title="Create Account Key"
          submitLabel="Save"
          onSubmit={() => this.props.auth.createAccount()}
          onClose={() => {
            this.props.auth.accountForm = null;
          }}
          error={newAccountForm.error}
        >
          <Form>
            <TextField
              id="id-account-name"
              label="Name"
              fieldState={newAccountForm.name}
              placeholder="Human readable alias"
            />
            <TextField
              id="id-public-key-hash-base16"
              label="Public Key Hash (Base16)"
              fieldState={encodeBase16(
                publicKeyHashForEd25519(newAccountForm.publicKeyBase64.value)
              )}
              readonly={true}
            />
            <TextField
              id="id-public-key-base16"
              label="Public Key (Base16)"
              fieldState={encodeBase16(decodeBase64(newAccountForm.publicKeyBase64.value))}
              readonly={true}
            />
            <TextField
              id="id-public-key-base64"
              label="Public Key (Base64)"
              fieldState={newAccountForm.publicKeyBase64.value}
              readonly={true}
            />
            <TextField
              id="id-private-key-base64"
              label="Private Key (Base64)"
              fieldState={newAccountForm.privateKeyBase64}
              readonly={true}
            />
          </Form>
        </Modal>
      );
    } else if (accountForm instanceof ImportAccountFormData) {
      // Help IDE infer that the type of accountForm is ImportAccountFormData
      let importAccountForm = accountForm;
      modalAccountForm = (
        <Modal
          id="import-account"
          title="Import Account Public Key"
          submitLabel="Save"
          onSubmit={() => this.props.auth.importAccount()}
          onClose={() => {
            this.props.auth.accountForm = null;
          }}
          error={importAccountForm.error}
        >
          <Form>
            <FileSelect
              id="id-file-select"
              label={importAccountForm.fileName || 'Choose Public Key File'}
              handleFileSelect={e => {
                importAccountForm.handleFileSelect(e);
              }}
            />
            <SelectField
              id="id-signature-algorithm"
              label="Signature Algorithm"
              value={'Ed25519'}
              options={[{ label: 'Ed25519', value: 'Ed25519' }]}
            />
            <TextField
              id="id-account-name"
              label="Name"
              fieldState={importAccountForm.name}
              placeholder="Human readable alias"
            />
            <TextField
              id="id-public-key-hash-base16"
              label="Public Key Hash (Base16)"
              fieldState={encodeBase16(
                publicKeyHashForEd25519(importAccountForm.publicKeyBase64.value)
              )}
              readonly={true}
            />
            <TextField
              id="id-public-key-base64"
              label="Public Key (Base64)"
              fieldState={importAccountForm.publicKeyBase64}
              readonly={true}
            />
          </Form>
        </Modal>
      );
    }
    return (
      <div>
        <DataTable
          title="Accounts"
          refresh={() => this.refresh(true)}
          rows={this.props.auth.accounts}
          headers={['Name', 'Public Key Hash (Base16)', 'Balance', '']}
          renderRow={(account: UserAccount) => {
            let publicKeyHash = getPublicKeyHash(account);
            const balance = this.props.auth.balances.get(
              encodeBase64(publicKeyHash)
            );
            return (
              <tr key={account.name}>
                <td>{account.name}</td>
                <td>{encodeBase16(publicKeyHash)}</td>
                <td>
                  <Balance balance={balance} />
                </td>
                <td className="text-center">
                  <IconButton
                    onClick={() => this.props.auth.deleteAccount(account.name)}
                    title="Delete"
                    icon="trash-alt"
                  />
                </td>
              </tr>
            );
          }}
          footerMessage={
            <span>
              You can create a new account here, which is basically an Ed25519
              public key. Don't worry, the private key will never leave the
              browser, we'll save it straight to disk on your machine.
            </span>
          }
        />
        {modalAccountForm}
        <ListInline>
          <Button
            title="Create Account Key"
            onClick={() => this.props.auth.configureNewAccount()}
          />
          <Button
            onClick={() => this.props.auth.configureImportAccount()}
            title="Import Account Key"
          />
        </ListInline>
      </div>
    );
  }
}

// Need an observer component to subscribe just to this account balance.
const Balance = observer(
  (props: { balance: ObservableValue<AccountBalance> }) => {
    const value = props.balance.value;
    if (value == null) return null;

    const hash = encodeBase16(value.blockHash);
    const balance =
      value.balance === undefined ? 'n/a' : value.balance.toLocaleString();
    return (
      <div className="text-right" title={`As of block ${hash}`}>
        {balance}
      </div>
    );
  }
);
