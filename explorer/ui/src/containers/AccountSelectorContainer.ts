import { action, computed, observable } from 'mobx';
import { decodeBase16, decodeBase64, encodeBase16, Keys } from 'casperlabs-sdk';
import { StateChangeOptions } from 'downshift';
import matchSorter from 'match-sorter';
import Pages from '../components/Pages';
import * as H from 'history';
import { getPublicKeyHashBase16 } from './AuthContainer';

interface AccountKeyWithAlias {
  key: string;
  alias: string;
}

export default class AccountSelectorContainer {
  @observable inputValue?: string | null = null;
  @observable checkError?: string | null = null;
  @observable accountNameToKeys: Map<string, string> = new Map();
  history: any;

  @action
  init(accounts: UserAccount[], history: H.History) {
    (accounts || []).forEach(x => {
      let publicKeyHashBase16 = getPublicKeyHashBase16(x);
      this.accountNameToKeys.set(
        `${x.name} (${publicKeyHashBase16})`,
        publicKeyHashBase16
      );
    });
    this.history = history;
  }

  @computed
  get items(): Array<AccountKeyWithAlias> {
    return Array.from(this.accountNameToKeys).map(([alias, account]) => {
      return {
        key: account,
        alias: alias
      };
    });
  }

  @action
  handleStateChange = (changes: StateChangeOptions<string>) => {
    this.checkError = null;
    if (changes.hasOwnProperty('selectedItem')) {
      this.inputValue = this.accountNameToKeys.get(changes.selectedItem || '');
    } else if (changes.hasOwnProperty('inputValue')) {
      this.inputValue = changes.inputValue;
    }
  };

  @computed
  get check() {
    if (!this.inputValue) {
      return 'Account Public Key Hash cannot be empty.';
    }

    if (this.inputValue.length < 64) {
      return 'Account Public Key Hash has to be 64 characters long.';
    }

    try {
      decodeBase16(this.inputValue);
    } catch (e) {
      return 'Could not decode as Base16 hash.';
    }

    return null;
  }

  getItems(filter: string | null) {
    return filter
      ? matchSorter(this.items, filter, {
          keys: ['key'],
          threshold: matchSorter.rankings.STARTS_WITH
        })
      : this.items;
  }

  getStringItems(filter: string | null) {
    return this.getItems(filter).map(item => item.alias);
  }

  @action.bound
  submit() {
    this.checkError = this.check;
    if (!this.checkError && this.inputValue) {
      this.history.push(Pages.deploysOfAccount(this.inputValue));
    }
  }

  @action.bound
  clearSelection() {
    this.inputValue = null;
  }
}
