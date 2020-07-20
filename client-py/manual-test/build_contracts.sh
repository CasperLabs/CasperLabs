#!/usr/bin/env bash

# build contracts to use
pushd ../../execution-engine || exit
make build-contracts-rs
popd
