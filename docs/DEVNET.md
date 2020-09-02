# DEVNET
##  Quick Start Instructions

##### Step 1: Creating an account
Go to [clarity.casperlabs.io Sign-In](https://clarity.casperlabs.io/#/) and complete the new user process:
* Navigate to [Accounts](https://clarity.casperlabs.io/#/accounts) and click [Create Account](https://clarity.casperlabs.io/#/accounts)
* Provide a name for your account and click [Save](https://clarity.casperlabs.io/#/accounts); multiple key files will be written to disk. You will need these key files to interact with the network, so give some consideration to where you save them.
* Navigate to [Faucet](https://clarity.casperlabs.io/#/faucet), select your new account, and click [Request Tokens](https://clarity.casperlabs.io/#/faucet)

  The request will appear in the "Recent Faucet Requests" table; wait for the green check mark to appear in the "Status" column.

  Note: a "token" obtained on devnet has no monetary value whatsoever.

##### Step 2: Checking the balance of an account

 [Install the CasperLabs client](INSTALL.md), use the `balance` sub-command (see example below);

* `--address` is the hex-encoded public key of the account to query
* `--block-hash` the hex-encoded hash of the block where the balance should be queried.

For example:
```shell
casperlabs-client \
        --host deploy.casperlabs.io \
        balance \
        --address b9ae114d6093646ed4be6f7fe1f7e5533a5d52a3351f3f18030ea82b3d915d6b \
        --block-hash ef6d4c66a29d833de462fbb7fd35227cbc3849b36872940c852727f668d6993f
```
##### Step 3: Deploying code

Once the CasperLabs client is installed, compile a contract, see [CONTRACTS.md](CONTRACTS.md) for details.

Use the CasperLabs client `deploy` sub-command (see example below):

  - `--session` is the path to the compiled contract
  - `--private-key` is the path to the secret_key.pem file downloaded from [clarity.casperlabs.io](https://clarity.casperlabs.io/) during account creation.

For example:
```shell
casperlabs_client \
        --host deploy.casperlabs.io \
        deploy \
        --session <path-to-wasm> \
        --private-key secret_key.pem
```

You can query the outcome of deploys using `casperlabs-client`:

```shell
casperlabs_client\
        --host deploy.casperlabs.io \
        --port 40401 show-deploy <deploy-hash>
```

For more details on deploys, see the available CLI arguments with `casperlabs_client deploy --help`, and further documentation in [CONTRACTS.md](CONTRACTS.md).

##### Step 4: Bonding

Follow the instructions [here](NODE.md#running-a-node-on-the-casper-testnet) for connecting to the CasperLabs network. Once bonded, you can use the CasperLabs client with your local node to deploy code and propose blocks on the devnet.

For example:

```shell
casperlabs_client \
        --host localhost \
        deploy \
        --session <path-to-wasm> \
        --private-key secret_key.pem

casperlabs_client \
        --host localhost \
        propose
```

##### Step 5: Unbonding

Follow instructions [here](NODE.md#stopping-the-node) for stopping a bonded validator.

## Notes
This quick start gives the simplest set of instructions for getting started on the CasperLabs devnet. More advanced users may wish to take other approaches to some of the steps listed above.
