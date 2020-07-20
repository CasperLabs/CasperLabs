FROM node:12.5.0-stretch-slim

COPY contracts/transfer_to_account_u512.wasm /app/contracts/transfer.wasm
COPY contracts/faucet_stored.wasm /app/contracts/faucet_stored.wasm

COPY server/node_modules /app/server/node_modules
# Copying the local projects because after a build the server node_modules contains symlinks.
COPY grpc /app/grpc
COPY sdk /app/sdk
COPY server/dist /app/server
COPY ui/build /app/ui

WORKDIR /app/server
ENV STATIC_ROOT=/app/ui
ENV PAYMENT_AMOUNT=10000000
ENV TRANSFER_AMOUNT=1000000000
ENV GAS_PRICE=10
# The link to graphql playground, used in Home page of UI.
ENV UI_GRAPHQL_URL=http://devnet-graphql.casperlabs.io:40403/graphql
ENV FAUCET_CONTRACT_PATH=/app/contracts/faucet_stored.wasm
ENTRYPOINT node server.js
