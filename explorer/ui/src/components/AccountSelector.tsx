import React from 'react';
import { observer } from 'mobx-react';
import { Button, Card } from './Utils';
import { RouteComponentProps, withRouter } from 'react-router';
import Downshift from 'downshift';
import AuthContainer from '../containers/AuthContainer';
import { FaucetContainer } from '../containers/FaucetContainer';
import AccountSelectorContainer from '../containers/AccountSelectorContainer';

interface Props extends RouteComponentProps {
  auth: AuthContainer;
  faucet: FaucetContainer;
  accountSelectorContainer: AccountSelectorContainer;
}

function ArrowIcon(props: { isOpen: boolean }) {
  if (props.isOpen) {
    return <i className="fas fa-angle-up" />;
  } else {
    return <i className="fas fa-angle-down" />;
  }
}

@observer
class _AccountSelector extends React.Component<Props, {}> {
  constructor(props: Props) {
    super(props);
    this.props.accountSelectorContainer.init(
      this.props.auth.accounts || [],
      this.props.history
    );
  }

  render() {
    let accountSelectorContainer = this.props.accountSelectorContainer;
    return (
      <div>
        <Card title="Search">
          <Downshift
            selectedItem={accountSelectorContainer.inputValue}
            onStateChange={accountSelectorContainer.handleStateChange}
          >
            {({
              getInputProps,
              getToggleButtonProps,
              getItemProps,
              isOpen,
              selectedItem,
              inputValue,
              highlightedIndex
            }) => (
              <div>
                <div style={{ position: 'relative' }}>
                  <input
                    {...getInputProps({
                      placeholder: 'Enter Account Public Key Hash',
                      className: 'form-control'
                    })}
                  />
                  {selectedItem ? (
                    <button
                      onClick={accountSelectorContainer.clearSelection}
                      className="controller-button"
                      aria-label="clear selection"
                    >
                      <i className="fas fa-times"></i>
                    </button>
                  ) : (
                    <button
                      className="controller-button"
                      {...getToggleButtonProps()}
                    >
                      <ArrowIcon isOpen={isOpen} />
                    </button>
                  )}
                </div>

                <div style={{ position: 'relative' }}>
                  <ul className={`pop-list ${isOpen ? 'is-open' : ''}`}>
                    {isOpen
                      ? accountSelectorContainer
                          .getStringItems(inputValue)
                          .map((item, index) => (
                            <li
                              key={index}
                              className={`option ${
                                highlightedIndex === index ? 'is-active' : ''
                              }`}
                              {...getItemProps({
                                item,
                                index
                              })}
                            >
                              {item}
                            </li>
                          ))
                      : null}
                  </ul>
                </div>
              </div>
            )}
          </Downshift>
          {accountSelectorContainer.checkError && (
            <div className="invalid-feedback">
              {accountSelectorContainer.checkError}
            </div>
          )}
          <div style={{ marginTop: '2em' }}>
            <Button
              title="Submit"
              onClick={() => accountSelectorContainer.submit()}
            />
          </div>
        </Card>
      </div>
    );
  }
}

export const AccountSelector = withRouter(_AccountSelector);
export default AccountSelector;
