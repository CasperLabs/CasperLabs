import React from 'react';
import { Link } from 'react-router-dom';
import { observer } from 'mobx-react';

import Pages from './Pages';
import AuthContainer from '../containers/AuthContainer';

interface Props {
  auth: AuthContainer;
}

const Home = observer((props: Props) => {
  return (
    <div>
      <div className="jumbotron shadow">
        <div>
          <h1>CasperLabs Clarity</h1>
          <p>
            This is a self service portal for dApp developers to interact with
            the blockchain. On devnet you can use this portal to create accounts
            for yourself, fund them with some free tokens to play with, and
            explore the blockchain. If you're having an issue then don't
            hesitate to let us know on{' '}
            <a href="https://discordapp.com/invite/Q38s3Vh">Discord</a>,{' '}
            <a href="https://t.me/casperlabs">Telegram</a> or{' '}
            <a href="https://github.com/CasperLabs/CasperLabs/issues">Github</a>
          </p>

          <div className="row">
            <AccountsCard accounts={props.auth.accounts} />
            {props.auth.accounts &&
              props.auth.accounts.length > 0 && [
                <FaucetCard key="faucet" />,
                <ExploreCard key="explore" />,
                <GraphQLCard key="graphql" />
              ]}
          </div>
        </div>
      </div>

      <div className="card-body">
        <ul className="list-inline" id="go-to-buttons">
          <li className="list-inline-item">
            <h4>
              <a href="https://docs.casperlabs.io/" role="button">
                Read our Tech Spec &raquo;
              </a>
            </h4>
          </li>
        </ul>

        <h3>Looking for help?</h3>
        <p>
          To write contracts have a look at the{' '}
          <a href="https://docs.casperlabs.io/en/latest/dapp-dev-guide/index.html">
            dApp Developer Guide
          </a>
          , the{' '}
          <a href="https://github.com/CasperLabs/CasperLabs/blob/dev/README.md">
            main docs
          </a>{' '}
          and the{' '}
          <a href="https://github.com/CasperLabs/CasperLabs/tree/dev/hack/docker">
            local docker network setup
          </a>
          .
        </p>
      </div>

      <br />
    </div>
  );
});

export default Home;

interface CardProps {
  background: string;
  icon: string;
  to?: string;
  children: any;
}

// Card displays coloured summaries for main areas
const Card = (props: CardProps) => {
  const linkClass = 'card-footer text-white clearfix small z-1';
  const view = [
    <span key="left" className="float-left">
      View Details
    </span>,
    <span key="right" className="float-right">
      <i className="fa fa-angle-right"></i>
    </span>
  ];
  return (
    <div className="col-xl-3 col-sm-6 mb-3">
      <div className={`card text-white bg-${props.background} o-hidden h-100`}>
        <div className="card-body">
          <div className="card-body-icon">
            <i className={`fa fa-fw fa-${props.icon}`}></i>
          </div>
          {props.children}
        </div>
        {props.to ? (
          props.to.startsWith('http') ? (
            <a href={props.to} className={linkClass}>
              {view}
            </a>
          ) : (
            <Link to={props.to} className={linkClass}>
              {view}
            </Link>
          )
        ) : null}
      </div>
    </div>
  );
};

const CardMessage = (props: { message: string }) => {
  return <div className="mr-5">{props.message}</div>;
};

const AccountsCard = (props: { accounts: UserAccount[] | null }) => {
  const background =
    props.accounts && props.accounts.length > 0 ? 'success' : 'success';
  return (
    <Card background={background} icon="address-book" to={Pages.Accounts}>
      {props.accounts == null || props.accounts.length === 0 ? (
        <CardMessage message="Create an account" />
      ) : (
        <CardMessage
          message={`You have ${props.accounts.length} account key(s)`}
        />
      )}
    </Card>
  );
};

const FaucetCard = (_: {}) => {
  // TODO: Display available funds.
  return (
    <Card background="info" icon="coins" to={Pages.Faucet}>
      <CardMessage message="Request tokens" />
    </Card>
  );
};

const ExploreCard = (_: {}) => {
  // TODO: Display latest block timestamp.
  return (
    <Card background="info" icon="project-diagram" to={Pages.Explorer}>
      <CardMessage message="Explore the blockchain" />
    </Card>
  );
};

const GraphQLCard = (_: {}) => {
  return (
    <Card
      background="info"
      icon="flask"
      to={window.config.graphql.url}
    >
      <CardMessage message="Try the GraphQL console!" />
    </Card>
  );
};

// TODO: DeployCard with the cached deploys to send another one?
// TODO: BlocksCard with the last finalized block, or the tips?
