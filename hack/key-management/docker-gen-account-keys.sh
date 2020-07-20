#!/usr/bin/env bash

set -e

$(dirname $0)/docker-gen-keys.sh "$1"
cd "$1"
rm node*
mv validator-pk account-pk
mv validator-pk-hex account-pk-hex
mv validator-id account-id
mv validator-id-hex account-id-hex
mv validator-public.pem account-public.pem
mv validator-private.pem account-private.pem
