# Running the CasperLabs Node
---
The CasperLabs node consists of two components:
* `casperlabs-engine-grpc-server`, which executes smart contracts and persists the effects of these executions.
* `casperlabs-node`, which handles peer-to-peer communication, consensus, and block storage.

## Prerequisites

### Hardware
Base requirements: 4 cores, 16 GB RAM  and 200 GB of disk space. *Note: Validators need to monitor their disk usage and expand as necessary.*

Cloud Instances:

| Provider  | Machine Type  |
|---|---|
| AWS  | m5.xlarge  |
| Azure  | D4 v3  |
| GCP  | n1-standard-4  |


### Operating System
* The officially supported OS is Ubuntu 20.04 LTS or greater. The software should work with other distributions, but may experience some hiccups.  Feel free to reach out for help if you want to use another Linux distribution and hit issues. The technical team is available on [Discord](https://discord.gg/xeU7szA)


#### Using binaries (recommended):
* [Install](INSTALL.md) the `casperlabs` package, which contains `casperlabs-node` and `casperlabs-engine-grpc-server`.

#### Ports used by the Node:

The default configuration of the node uses following ports:
* 40400 - Intra node communication port for consensus.
* 40401 - External GRPC for deployments
* 40403 - For monitoring (GraphQL, Grafana etc)
* 40404 - Intra node communication port for node discovery.

It is possible to override these and other settings when starting the node.  Run `casperlabs-node --help` to see a list of options.

---
## The Casper Testnet
CasperLabs is building a permissionless public blockchain.  However, in the current phase of the Testnet bonded validator slots are open to node operators that have agreed to the Terms and Conditions and signed up to participate in the program. To sign up for the Testnet, complete this [process](https://docs.google.com/forms/d/e/1FAIpQLSdaxtS015aiH89Pn0zt09v95FqNDoBk3hOH7Jq0IeuUWFVcTA/viewform?usp=sf_link)


### Running a Node on the Casper Testnet
It's possible to run a Read only node on the testnet.  The configuration of a read only node is exactly the same as a bonded node at this time.  Read only nodes process all state updates and receive all blocks just as bonded nodes.  Read only nodes are not permitted to propose blocks or participate in consensus.

#### Step 1: Create an account at [clarity.casperlabs.io](https://clarity.casperlabs.io)

From within the interface, create an account, which automatically creates a new keypair.  ***Download the files from Clarity. You will need these files for the node***   This keypair should be placed on the machine running the node software. Please keep your private keys secure at all times.

#### Step 2: Get the ChainSpec

The first block in the blockchain is the Genesis block.  The nodes must have the same Genesis block in order to be on the same chain. This information is known as the Chain specification (Chainspec). The Chainspec is comprised of a list of Genesis validators, stored in the `accounts.csv` file and a `manifest.toml`, which contains protocol parameters.  These files need to be placed in the `chainspec/genesis` directory on the node.

###### Testnet ChainSpec

These files are available from:(https://github.com/CasperLabs/CasperLabs/tree/dev/testnet). It is recommended that the files be downloaded via curl or equivalent mechanism, to avoid any hidden characters from appearing in the files.  The Genesis block must have the same hash, or the node will not connect.

```
mkdir -p ~/.casperlabs/chainspec/genesis

cd ~/.casperlabs/chainspec/genesis

curl -O https://raw.githubusercontent.com/CasperLabs/CasperLabs/dev/testnet/accounts.csv
curl -O https://raw.githubusercontent.com/CasperLabs/CasperLabs/dev/testnet/manifest.toml
```
##### These files need to be placed in the `/.casperlabs/chainspec/genesis` directory on the node.

##### The md5 checksums for these files for the testnet are here.  Please verify that your files match.

``` 
3add70e406639ac74e85a64de2f4dddb  accounts.csv
a89412ed5f1218ec821ee1f0c4a521eb  manifest.toml

```

#### Step 3: Create Node keys and TLS Certificate

The node uses a separate set of keys for its' TLS certificate. These keys are separate from validator keys- which happen to be the same as account keys (created via Clarity).  In this step, create the Node keys and TLS Certificate as described [here](KEYS.md#generating-node-keys-and-validator-keys).

The following instructions apply only for Linux OS.  Running the system using Docker requires adapting the commands for Docker. 


#### Step 4: Start the Node 
The system can run in either Read Only mode or Validator mode.  The difference is whether the public validator key that are specified at node startup is in the accounts.csv file at Genesis.  The matching of the public key & private key is what bonds the node to the network at this time.

The node requires a bootstrap server in order to connect to the network & peer up.  The command below lists the 3 bootstrap servers provided by CasperLabs.
 
**Note: the paths to the keys should be adjusted to reflect the location of keys on the system.

##### Read Only Mode 
If you wish to run a read only node, this start command is sufficient. No validator keys are required.

This starts the engine and node with nohup. You may choose to start it another way if you want (systemd, scripts, etc.).

```
nohup casperlabs-engine-grpc-server ~/.casperlabs/.casper-node.sock &

nohup casperlabs-node run --metrics-prometheus --log-json-path <path to where you want to log> --server-data-dir ~/.casperlabs -b "casperlabs://7dae5a7981bc9694616b5aac8fb7786797ce98ed@13.57.226.23?protocol=40400&discovery=40404 casperlabs://f2a46587e227428f38fa6f1e8f3c4749e8543783@52.53.252.92?protocol=40400&discovery=40404 casperlabs://4bd89b7dfa3eceea71f928ee895fbb2bf77481a9@13.52.217.79?protocol=40400&discovery=40404" --tls-certificate <PATH_TO_TLS_CERT> --tls-key <PATH_TO_TLS_KEY> &
```

##### Validator Mode with Highway Parameters for Testnet
If the validator keys have been added to Testnet genesis block, use this command line to start the node.  These options are part of the `run` command for the node. **Note: the paths to the keys should be adjusted to reflect the location of keys on the system.

This starts the engine and node with nohup. You may choose to start it another way if you want (systemd, scripts, etc.).

```
nohup casperlabs-engine-grpc-server ~/.casperlabs/.casper-node.sock &

nohup casperlabs-node run --metrics-prometheus --log-json-path <path to where you want to log> --server-data-dir ~/.casperlabs -b "casperlabs://7dae5a7981bc9694616b5aac8fb7786797ce98ed@13.57.226.23?protocol=40400&discovery=40404 casperlabs://f2a46587e227428f38fa6f1e8f3c4749e8543783@52.53.252.92?protocol=40400&discovery=40404 casperlabs://4bd89b7dfa3eceea71f928ee895fbb2bf77481a9@13.52.217.79?protocol=40400&discovery=40404" --tls-certificate <PATH_TO_TLS_CERT> --tls-key <PATH_TO_TLS_KEY> --casper-validator-private-key-path <PATH_TO_PRIVATE_KEY> --highway-init-round-exponent 19 --server-relay-factor 5 --server-init-sync-min-successful 5 --highway-omega-message-time-start 0.1 --highway-omega-message-time-end 0.9 --highway-omega-blocks-enabled --server-deploy-gossip-enabled &
```

#### Checking Status

* When joining at Genesis, the genesis block is viewable on [Clarity](https://clarity.casperlabs.io/#/explorer) along with connected peer nodes from other validators.
* There is a status endpoint that provides information on block height and synchronization. The endpoint outputs JSON.  Install JQuery for readable output, then run:

```
curl https://localhost:40403/status | jq
```
Check the 'Last Finalized Block' received from this query against Clarity to confirm that the node is in sync with the network.

#### Monitoring

The SRE team monitors validating nodes on the network.  Please open port 40403 and communicate your node's public IP address via Discord.

* You can also monitor the network and your node on the [Grafana](https://grafana.casperlabs.io/d/tlZ4zTrZk/testnet-block-processing?orgId=1&from=now-7d&to=now) dashboard.
* It's possible to configure Prometheus metrics and Grafana from the metrics emitted from port 40403 at the /metrics endpoint.

* If you wish to set up alerts, look at the deploy buffer size.  If the deploy buffers start to increase, your node may be having issues, or experiencing unusually high load.

#### Stopping the Node

```
pkill casperlabs-node
pkill casperlabs-engine-grpc-server
```
To clear the previous state from the node run the following command:

```
cd ~/.casperlabs
rm sqlite.db
rm -r global_state
```

#### Troubleshooting
Common errors to be looked at when having an issue:
* Check to ensure that the execution engine is running as the same user as node. There is no need to run under sudo.
* Check the paths to the keys.
* Before rejoining the network, check that the data directory (global_state and sqlite.db from ~/.casperlabs) has been cleared out before starting the engine & node. 

#### Getting Help
* Join [Discord/validators channel](https://discordapp.com/invite/Q38s3Vh) and send queries for help with any issues. 
* Capture the logs (dafault location /var/logs/casperlabs which can be changed by setting CL_LOG_JSON_PATH to any other directory), zip and share with us. jq can be used to parse the json logs. 

---

##### Connecting elsewhere

To connect elsewhere, obtain the ChainSpec, unzip it, and start the node with the `--casper-chain-spec-path`
option pointed to the directory.

The ChainSpec contains the information to create the Genesis block.

---

### Running a Standalone Node

You can run a single Node in standalone mode for testing purposes.

##### Step 1: Create an `accounts.csv` file

Add your validator key as the single bonded validator to the accounts in the ChainSpec.
You can override the default accounts that come with the node by shadowing the file
under your `--server-data-dir` directory, by default `~/.casperlabs`. For example the
following code would cause your validator to have an initial balance of 50 million and
a 1 million in bonds.

```
mkdir -p ~/.casperlabs/chainspec/genesis
(cat keys/validator-id; echo ",50000000,1000000") > ~/.casperlabs/chainspec/genesis/accounts.csv
```

##### Step 2: Start the Execution Engine

In a separate terminal, run:
```
casperlabs-engine-grpc-server ~/.casperlabs/.casper-node.sock
```

##### Step 3: Start the Node

This command adds the initial token supply to the node. This will enable the system to have some tokens at Genesis.

```
casperlabs-node run \
    --casper-standalone \
    --tls-key ./keys/node.key.pem \
    --tls-certificate ./keys/node.certificate.pem \
    --casper-validator-private-key-path ./keys/validator-private.pem \
    --casper-validator-public-key-path ./keys/validator-public.pem \
    --casper-initial-motes 1234567890 \
```

##### Step 4: Deploy Some Code

See instructions [here](https://docs.casperlabs.io/en/latest/dapp-dev-guide/deploying-contracts.html).

---

### Running a Simulated Network

See instructions [here](../hack/docker/README.md).
