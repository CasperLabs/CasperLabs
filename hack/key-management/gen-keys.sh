#!/usr/bin/env sh

#set -o pipefail
set -eu
trap 'handle_exit'  0

VERBOSE=false

handle_exit() {
    LAST_COMMAND_STATUS=$?
    if [ "$LAST_COMMAND_STATUS" -ne 0 ]; then
        if [ "$VERBOSE" = "true" ]; then
            echo 'ERROR: script failed, see the command above' && exit 1
        else
            echo 'ERROR: script failed, you can re-run script with -v option to debug it' && exit "$LAST_COMMAND_STATUS"
        fi
    fi
}

# Generates all necessary key for a node.
# Usage:
# ./gen-keys.sh [-v] <directory where to put keys>
#   -v      Print debug output
# Example:
# ./gen-keys.sh -v test-dir
#
# Will produce:
#
# $ ls test-dir
# node-id               # node ID as in casperlabs://c0a6c82062461c9b7f9f5c3120f44589393edf31@<NODE ADDRESS>?protocol=40400&discovery=40404
#                       # derived from node.key.pem
# node.certificate.pem  # TLS certificate used for node-to-node interaction encryption
#                       # derived from node.key.pem
# node.key.pem          # secp256r1 private key
# validator-id          # validator ID in base64 format, derived from validator.public.pem by hashing it together with the algorithm name
# validator-id-hex      # validator ID in base16 format
# validator-pk          # validator public key in base64 format
# validator-pk-hex      # validator public key in base16 format
# validator-private.pem # ed25519 private key
# validator-public.pem  # ed25519 public key
#
# Use as follows:
# ./node/target/universal/stage/bin/casperlabs-node run \
#   --casper-validator-public-key-path validator-public.pem
#   --casper-validator-private-key-path validator-private.pem
#   --tls-key node.key.pem
#   --tls-certificate node.certificate.pem
if [ "$1" = "-v" ]; then
    VERBOSE=true
    set -v; shift
fi

# Output directory where to put generated keys
OUTPUT_DIR="$1"
if [ ! -d "$OUTPUT_DIR" ]; then
    echo "ERROR: output dir doesn't exist"
    echo "usage: ./gen-keys.sh [-v] <dir>"
    exit 1
fi


# Generate validator private key
openssl genpkey -algorithm Ed25519 -out "$OUTPUT_DIR/validator-private.pem"

# Extract validator public key from private
openssl pkey -in "$OUTPUT_DIR/validator-private.pem" -pubout -out "$OUTPUT_DIR/validator-public.pem"

# Extract raw public key from PEM file to serve as a validator ID
openssl pkey -outform DER -pubout -in "$OUTPUT_DIR/validator-private.pem" | tail -c +13 | openssl base64 | tr -d '\n' > "$OUTPUT_DIR/validator-pk"

# Assert validator-id is 32 bytes long
VALIDATOR_PK_BASE64=$(cat "$OUTPUT_DIR/validator-pk")
VALIDATOR_PK_HEX=$(echo "$VALIDATOR_PK_BASE64" | openssl base64 -d | hexdump -ve '/1 "%02x" ')
echo $VALIDATOR_PK_HEX > "$OUTPUT_DIR/validator-pk-hex"

VALIDATOR_BYTES=$((${#VALIDATOR_PK_HEX} / 2))
if [ $VALIDATOR_BYTES -ne 32 ]
    then
        echo "ERROR: validator-pk must be 32 bytes length, got" "$VALIDATOR_BYTES" "bytes instead"
        exit 1
fi

# Derive validator public key hash.
VALIDATOR_ALGO_HEX=$(echo -n "ED25519" | xxd -p)
VALIDATOR_ID_INPUT_HEX=$(echo ${VALIDATOR_ALGO_HEX}00${VALIDATOR_PK_HEX})
echo $VALIDATOR_ID_INPUT_HEX | xxd -p -r | b2sum -a blake2b -l 256 | tr -d ' -' > "$OUTPUT_DIR/validator-id-hex"
cat "$OUTPUT_DIR/validator-id-hex" | xxd -p -r | openssl base64 > "$OUTPUT_DIR/validator-id"


# Generate private TLS key in PEM format
openssl ecparam -name secp256r1 -genkey -noout -out "$OUTPUT_DIR/secp256r1-private.pem"

# Transform key to PKCS#8 format
openssl pkcs8 -topk8 -nocrypt -in "$OUTPUT_DIR/secp256r1-private.pem" -out "$OUTPUT_DIR/node.key.pem"

# Remove old PEM key
rm "$OUTPUT_DIR/secp256r1-private.pem"

# Extract public key, hash it with Keccak-256 and get last 20 bytes to serve as a node ID
# Borrowed from https://ezcook.de/2017/11/30/Generate-Ethereum-keys-and-wallet-address/
NODE_ID=$(cat "$OUTPUT_DIR/node.key.pem" | \
    openssl ec -text -noout | \
    grep pub -A 5 | \
    tail -n +2 | \
    tr -d '\n[:space:]:' | \
    sed 's/^04//' | \
    keccak-256sum -x -l | \
    tr -d ' -' | \
    tail -c 41 | \
    tr -d '\n')

# Assert node-id is 20 bytes long
NODE_BYTES=$((${#NODE_ID} / 2))
if [ $NODE_BYTES -ne 20 ]
    then
        echo "ERROR: node-id must be 20 bytes length, got" "$NODE_BYTES" "bytes instead"
        exit 1
fi

echo "$NODE_ID"> "$OUTPUT_DIR/node-id"

# Generate X.509 certificate
openssl req \
	-new \
	 -x509 \
	 -key "$OUTPUT_DIR/node.key.pem" \
	 -out "$OUTPUT_DIR/node.certificate.pem" \
	 -days 365 \
	 -subj "/C=US/ST=CA/L=San-Diego/O=CasperLabs, LLC/OU=IT Department/CN=$NODE_ID"

echo "Generate keys: Success"
