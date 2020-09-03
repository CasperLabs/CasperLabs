# Keys

The CasperLabs platform uses three different sets of keys for different functions.

1. A node operator must provide a`secp256r1` private key encoded in unencrypted `PKCS#8` format and an `X.509` certificate.  These are used to encrypt communication with other nodes.
2. A validator must provide an `ed25519` keypair for use as their identity.  If these keys are not provided when the node is started, a node will default to read-only mode.
3. A DApp developer must provide an `ed25519` or `secp256k1` keypair for their account identity and deploying code.

Note: There is no difference between an ed25519 validator key pair or an ed25519 account key pair.

## Multiple Key Algorithms

When keys are generated in PEM format, they include DER encoding which records the algorithm used. The hex version of 
the public key does not have encoding to identify algorithm. We are using a leading byte to record algorithm used.

## Generating Node Keys and Validator Keys

In order to run a node or validate, you will need the following files:

|File                   |Contents                                                                                            |
|-----------------------|----------------------------------------------------------------------------------------------------|
|`node.key.pem`         |A `secp256r1` private key                                                                           |
|`node.certificate.pem` |The `X.509` certificate containing the `secp256r1` public key paired with `node.key.pem`            |
|`node-id`              |A value that is used to uniquely identify a node on the network, derived from `node.key.pem`        |
|`secret_key.pem`       |An `ed25519` private key                                                                            |
|`public_key.pem`       |The `ed25519` public key                                                                            |
|`public_key_hex`       |The base-16 representation of public key with algorithm leading byte                                |

Note also that the `accounts.csv` has changed and will use both the public key and algorithm type.

The recommended methods for generating keys are to use the [Python Client](https://pypi.org/project/casperlabs-client/).

More advanced users may prefer to generate keys directly on their host OS.

The `public_key_hex` file contains a leading byte (2 hex characters) to indicate the algorithm as follows:
   - `01` ed25519
   - `02` secp256k1

PEM files generated store the algorithm as part of the DER encoding of data.   

### Using casperlabs_client

#### Prerequisites
* [casperlabs_client](https://github.com/CasperLabs/client-py/blob/dev/README.md)

#### Instructions

```
mkdir /tmp/keys
casperlabs_client validator-keygen /tmp/keys
```

You should see the following output:

```
Keys successfully created in directory: /tmp/keys
```

## Generating Account Keys

### Using CLarity (for DApp Developers)

Currently, the recommended method for generating account keys is to use [CLarity](https://clarity.casperlabs.io).
When generating a key pair in Clarity, you will be prompted to save three files from your browser with default filename shown below:

|File                         |Contents                                                           |
|-----------------------------|-------------------------------------------------------------------|
|[key_name]_secret_key.pem    | An 'ed25519' private key in PEM format.                           |
|[key_name]_public_key.pem    | An 'ed25519' public key in PEM format.                            |
|[key_name]_public_key_hex    | An 'ed25519' public key in hex format with leading algorithm byte |

### Generating Keys on Local Machine

These instructions are provided for reference and advanced use-cases.

In order to deploy a contract on the network, you will need the following files:

|File                 |Contents                                                                                          |
|---------------------|--------------------------------------------------------------------------------------------------|
|`secret_key.pem`       |An `ed25519` private key                                                                            |
|`public_key.pem`       |The `ed25519` public key                                                                            |
|`public_key_hex`       |The base-16 representation of public key with algorithm leading byte                                |

### Using casperlabs_client

#### Prerequisites
* [casperlabs_client](https://github.com/CasperLabs/client-py/blob/dev/README.md)

#### Instructions

```
mkdir /tmp/keys
casperlabs_client validator-keygen /tmp/keys
```

You should see the following output:

```
Keys successfully created in directory: /tmp/keys
```

### Using OpenSSL

#### Prerequisites
* [OpenSSL](https://www.openssl.org): v1.1.1 or higher

#### Instructions

```
openssl genpkey -algorithm Ed25519 -out secret_key.pem
openssl pkey -in secret_key.pem -pubout -out public_key.pem
openssl pkey -outform DER -pubout -in secret_key.pem | tail -c +13 | openssl base64 | hexdump -ve '/1 "%02x"' | awk '{print "01" $0}' > public_key_hex
```