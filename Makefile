SHELL := bash

DOCKER_USERNAME ?= casperlabs
DOCKER_PUSH_LATEST ?= false
DOCKER_LATEST_TAG ?= latest
# Use the git tag / hash as version. Easy to pinpoint. `git tag` can return more than 1 though. `git rev-parse --short HEAD` would just be the commit.
$(eval TAGS_OR_SHA = $(shell git tag -l --points-at HEAD | grep -e . || git describe --tags --always --long))
# Try to use the semantic version with any leading `v` stripped.
$(eval SEMVER_REGEX = 'v?\K\d+\.\d+(\.\d+)?')
$(eval VER = $(shell echo $(TAGS_OR_SHA) | grep -oE 'v?[0-9]+(\.[0-9]){1,2}$$' | sed 's/^v//' || echo $(TAGS_OR_SHA)))

# All rust related source code. If every Rust module depends on everything then transitive dependencies are not a problem.
# But with comm/build.rs compiling .proto to .rs every time we build the timestamps are updated as well, so filter those and depend on .proto instead.
RUST_SRC := $(shell find . -type f \( -name "Cargo.toml" -o -wholename "*/src/*.rs" -o -name "*.proto" \) \
	| grep -v target \
	| grep -v node_modules \
	| grep -v -E '(ipc|transforms).*\.rs')
SCALA_SRC := $(shell find . -type f \( -wholename "*/src/*.scala" -o -name "*.sbt" -o -wholename "*/resources/*.toml" \))
PROTO_SRC := $(shell find protobuf -type f \( -name "*.proto" \) | grep -v node_modules)
TS_SRC := $(shell find explorer/ui/src explorer/server/src explorer/sdk/src -type f \( -name "*.ts" -o -name "*.tsx" -o -name "*.scss" -o -name "*.json" \))

RUST_TOOLCHAIN := $(shell cat execution-engine/rust-toolchain)

$(eval DOCKER_TEST_TAG = $(shell if [ -z ${DRONE_BUILD_NUMBER} ]; then echo test; else echo test-DRONE-${DRONE_BUILD_NUMBER}; fi))

# Don't delete intermediary files we touch under .make,
# which are markers for things we have done.
# https://stackoverflow.com/questions/5426934/why-this-makefile-removes-my-goal
.SECONDARY:

# Build all artifacts locally.
all: \
	docker-build-all \
	cargo-package-all \
	build-client \
	build-node \
	build-python-client 


# Push the local artifacts to repositories.
publish: docker-push-all
	$(MAKE) -C execution-engine publish

clean:
	$(MAKE) -C execution-engine clean
	sbt clean
	cd integration-testing && rm -rf bundled_contracts system_contracts
	cd explorer/grpc && rm -rf google io node_modules
	cd explorer/sdk && rm -rf node_modules dist
	cd explorer/ui && rm -rf node_modules build
	cd explorer/server && rm -rf node_modules dist
	rm -rf .make


docker-build-all: \
	docker-build/node \
	docker-build/client \
	docker-build/execution-engine \
	docker-build/integration-testing \
	docker-build/key-generator \
	docker-build/explorer \
	docker-build/grpcwebproxy

docker-push-all: \
	docker-push/node \
	docker-push/client \
	docker-push/execution-engine \
	docker-push/key-generator \
	docker-push/explorer

docker-build/node: .make/docker-build/debian/node
docker-build/client: .make/docker-build/debian/client
docker-build/execution-engine: .make/docker-build/execution-engine
docker-build/integration-testing: .make/docker-build/integration-testing
docker-build/key-generator: .make/docker-build/key-generator
docker-build/explorer: .make/docker-build/explorer
docker-build/grpcwebproxy: .make/docker-build/grpcwebproxy

# Tag the `latest` build with the version from git and push it.
# Call it like `DOCKER_PUSH_LATEST=true make docker-push/node`
docker-push/%: docker-build/%
	$(eval PROJECT = $*)
	docker tag $(DOCKER_USERNAME)/$(PROJECT):$(DOCKER_LATEST_TAG) $(DOCKER_USERNAME)/$(PROJECT):$(VER)
	docker push $(DOCKER_USERNAME)/$(PROJECT):$(VER)
	if [ "$(DOCKER_PUSH_LATEST)" = "true" ]; then \
		docker push $(DOCKER_USERNAME)/$(PROJECT):$(DOCKER_LATEST_TAG) ; \
	fi


cargo-package-all: \
	cargo-native-packager/execution-engine/engine-grpc-server \
	package-system-contracts

# Drone is already running commands in the `builderenv`, no need to delegate.
cargo-native-packager/%:
	if [ -z "${DRONE_BRANCH}" ]; then \
		$(MAKE) .make/cargo-docker-packager/$* ; \
	else \
		$(MAKE) .make/cargo-native-packager/$* ; \
	fi

# Build the `latest` docker image for local testing. Works with Scala.
.make/docker-build/debian/%: \
		%/Dockerfile \
		.make/sbt-deb/%
	$(eval PROJECT = $*)
	docker build -f $(PROJECT)/Dockerfile -t $(DOCKER_USERNAME)/$(PROJECT):$(DOCKER_LATEST_TAG) $(PROJECT)
	mkdir -p $(dir $@) && touch $@

# Dockerize the Integration Tests
.make/docker-build/integration-testing: \
		integration-testing/Dockerfile
	$(eval IT_PATH = integration-testing)
	cp -r protobuf $(IT_PATH)/
	docker build -f $(IT_PATH)/Dockerfile -t $(DOCKER_USERNAME)/integration-testing:$(DOCKER_LATEST_TAG) $(IT_PATH)/
	rm -rf $(IT_PATH)/protobuf
	mkdir -p $(dir $@) && touch $@

# Dockerize the Execution Engine.
.make/docker-build/execution-engine: \
		execution-engine/Dockerfile \
		cargo-native-packager/execution-engine/engine-grpc-server \
		$(RUST_SRC)
	# Just copy the executable to the container.
	$(eval RELEASE = execution-engine/target/debian)
	cp execution-engine/Dockerfile $(RELEASE)/Dockerfile
	docker build -f $(RELEASE)/Dockerfile -t $(DOCKER_USERNAME)/execution-engine:$(DOCKER_LATEST_TAG) $(RELEASE)
	rm -rf $(RELEASE)/Dockerfile
	mkdir -p $(dir $@) && touch $@

# Make an image for keys generation
.make/docker-build/key-generator: \
	hack/key-management/Dockerfile \
	hack/key-management/gen-keys.sh
	docker build -f hack/key-management/Dockerfile -t $(DOCKER_USERNAME)/key-generator:$(DOCKER_LATEST_TAG) hack/key-management
	mkdir -p $(dir $@) && touch $@


# Make an image to host the Casper Explorer UI and the faucet microservice.
.make/docker-build/explorer: \
		explorer/Dockerfile \
		build-explorer
	docker build -f explorer/Dockerfile -t $(DOCKER_USERNAME)/explorer:$(DOCKER_LATEST_TAG) explorer
	mkdir -p $(dir $@) && touch $@


.make/npm/explorer: \
	$(TS_SRC) \
	.make/protoc/explorer \
	explorer/ui/package.json \
	explorer/server/package.json \
	build-explorer-contracts
	# CI=false so on Drone it won't fail on warnings (currently about href).
	./hack/build/docker-buildenv.sh "\
			cd explorer && \
			cd grpc   && npm install && cd - && \
			cd sdk    && npm install && cd - && \
			cd ui     && npm install && cd - && \
			cd server && npm install && cd - && \
			./install-sdk-grpc.sh && \
			cd sdk    && npm run build && cd - && \
			cd ui     && CI=false npm run build && cd - && \
			cd server && npm run clean:dist && npm run build && cd - \
		"
	mkdir -p $(dir $@) && touch $@

# Generate UI client code from Protobuf.
# Installed via `npm install ts-protoc-gen --no-bin-links --save-dev`
.make/protoc/explorer: \
		.make/install/protoc \
		./explorer/grpc/node_modules/ts-protoc-gen/bin/protoc-gen-ts \
		$(PROTO_SRC)
	$(eval DIR_IN = ./protobuf)
	$(eval DIR_OUT = ./explorer/grpc)
	rm -rf $(DIR_OUT)/google
	rm -rf $(DIR_OUT)/io
	# First the pure data packages, so it doesn't create empty _pb_service.d.ts files.
	# Then the service we'll invoke.
	./hack/build/docker-buildenv.sh "\
		protoc \
				-I=$(DIR_IN) \
			--plugin=protoc-gen-ts=./explorer/grpc/node_modules/ts-protoc-gen/bin/protoc-gen-ts \
			--js_out=import_style=commonjs,binary:$(DIR_OUT) \
			--ts_out=service=false:$(DIR_OUT) \
			$(DIR_IN)/google/protobuf/empty.proto \
			$(DIR_IN)/io/casperlabs/casper/consensus/consensus.proto \
			$(DIR_IN)/io/casperlabs/casper/consensus/info.proto \
			$(DIR_IN)/io/casperlabs/casper/consensus/state.proto \
			$(DIR_IN)/io/casperlabs/comm/discovery/node.proto ; \
		protoc \
				-I=$(DIR_IN) \
			--plugin=protoc-gen-ts=./explorer/grpc/node_modules/ts-protoc-gen/bin/protoc-gen-ts \
			--js_out=import_style=commonjs,binary:$(DIR_OUT) \
			--ts_out=service=true:$(DIR_OUT) \
			$(DIR_IN)/io/casperlabs/node/api/casper.proto \
			$(DIR_IN)/io/casperlabs/node/api/diagnostics.proto ; \
		"
	# Annotations were only required for the REST gateway. Remove them from Typescript.
	for f in $(DIR_OUT)/io/casperlabs/node/api/casper_pb* ; do \
		sed -n '/google_api_annotations_pb/!p' $$f > $$f.tmp ; \
		mv $$f.tmp $$f ; \
	done
	mkdir -p $(dir $@) && touch $@

.make/explorer/contracts: $(RUST_SRC) .make/rustup-update
	# Compile the faucet contract that grants tokens.
	cd explorer/contracts && \
	cargo +$(RUST_TOOLCHAIN) build --release --target wasm32-unknown-unknown
	mkdir -p $(dir $@) && touch $@

.make/docker-build/grpcwebproxy: hack/docker/grpcwebproxy/Dockerfile
	cd hack/docker && docker-compose build grpcwebproxy
	docker tag casperlabs/grpcwebproxy:latest $(DOCKER_USERNAME)/grpcwebproxy:$(DOCKER_LATEST_TAG)
	mkdir -p $(dir $@) && touch $@

# Refresh Scala build artifacts if source was changed.
.make/sbt-stage/%: $(SCALA_SRC) build-%-contracts
	$(eval PROJECT = $*)
	sbt -mem 5000 $(PROJECT)/universal:stage
	mkdir -p $(dir $@) && touch $@

.make/sbt-deb/%: $(SCALA_SRC) build-%-contracts
	$(eval PROJECT = $*)
	sbt -mem 5000 $(PROJECT)/debian:packageBin
	mkdir -p $(dir $@) && touch $@

# Create .rpm and .deb packages natively. `cargo rpm build` doesn't work on MacOS.
#
# .rpm will be in execution-engine/target/release/rpmbuild/RPMS/x86_64
# .deb will be in execution-engine/target/debian
.make/cargo-native-packager/execution-engine/engine-grpc-server: $(RUST_SRC) \
		.make/install/protoc \
		.make/install/cargo-native-packager
	$(MAKE) -C execution-engine rpm
	$(MAKE) -C execution-engine deb
	mkdir -p $(dir $@) && touch $@

# Create .rpm and .deb packages with Docker so people using Macs can build
# images locally too.  We may need to have .rpm and .deb specific builder images
# that work with what we want to host it in as we seem to get some missing
# dependencies using the buildenv which didn't happen with Ubuntu.
#
# .rpm will be in execution-engine/target/release/rpmbuild/RPMS/x86_64
# .deb will be in execution-engine/target/debian
#
# Need to use the same user ID as outside if we want to continue working with
# these files, otherwise the user running in docker will own them.
.make/cargo-docker-packager/execution-engine/engine-grpc-server: $(RUST_SRC)
	$(eval USERID = $(shell id -u))
	docker pull $(DOCKER_USERNAME)/buildenv:latest
	docker run --rm --entrypoint sh \
		-v ${PWD}:/CasperLabs \
		$(DOCKER_USERNAME)/buildenv:latest \
		-c "\
		useradd -u $(USERID) -m builder ; \
		cp -r /root/. /home/builder/ ; \
		chown -R builder /home/builder ; \
		su -s /bin/bash -c '\
			export HOME=/home/builder ; \
			cd /CasperLabs/execution-engine ; \
			make setup ; \
			make setup-cargo-packagers ; \
			make rpm ; \
			make deb \
		' builder"
	mkdir -p $(dir $@) && touch $@


# Compile contracts that need to go into the Genesis block.
package-system-contracts: execution-engine/target/system-contracts.tar.gz

execution-engine/target/system-contracts.tar.gz: $(RUST_SRC) .make/rustup-update
	$(MAKE) -C execution-engine package-system-contracts


# Compile a contract under execution-engine; it will be written for example to execution-engine/target/wasm32-unknown-unknown/release/mint_token.wasm
# The target works .make/contracts/standard_payment for example and compiles the standard-payment project in EE;
# in the EE project all the contracts appear individually in the cargo workspace.
.make/contracts/%: $(RUST_SRC) .make/rustup-update
	$(eval CONTRACT=$(subst _,-,$*))
	$(MAKE) -C execution-engine build-contract-rs/$(CONTRACT)
	mkdir -p $(dir $@) && touch $@

# Compile a contract and put it in the CLI client resources so they get packaged with the JAR.
client/src/main/resources/%.wasm: .make/contracts/%
	mkdir -p $(dir $@)
	cp execution-engine/target/wasm32-unknown-unknown/release/$*.wasm $@

# Compile a contract and put it in the node resources so they get packaged with the JAR.
node/src/main/resources/chainspec/genesis/%.wasm: .make/contracts/%
	cp execution-engine/target/wasm32-unknown-unknown/release/$*.wasm $@

# Copy a client or explorer contract to the explorer.
explorer/contracts/%.wasm: .make/contracts/%
	mkdir -p $(dir $@)
	cp execution-engine/target/wasm32-unknown-unknown/release/$*.wasm $@

build-client: \
	.make/sbt-stage/client

build-python-client: \
	$(PROTO_SRC) \
	$(shell find ./client-py/ -name "*.py"|grep -v _grpc.py)
	client-py/build.sh

build-client-contracts: \
	client/src/main/resources/bonding.wasm \
	client/src/main/resources/unbonding.wasm \
	client/src/main/resources/transfer_to_account_u512.wasm

build-node: \
	.make/sbt-stage/node

build-node-contracts: \
	node/src/main/resources/chainspec/genesis/mint_install.wasm \
	node/src/main/resources/chainspec/genesis/pos_install.wasm \
	node/src/main/resources/chainspec/genesis/standard_payment_install.wasm

build-explorer: \
	.make/npm/explorer

build-explorer-contracts: \
	explorer/contracts/transfer_to_account_u512.wasm \
	explorer/contracts/faucet_stored.wasm

# Get the .proto files for REST annotations for Github. This is here for reference about what to get from where, the files are checked in.
# There were alternatives, like adding a reference to a Maven project called `googleapis-commons-protos` but it had version conflicts.
.PHONY: protobuf/google/api
protobuf/google/api:
	$(eval DIR = protobuf/google/api)
	$(eval SRC = https://raw.githubusercontent.com/googleapis/googleapis/master/google/api)
	mkdir -p $(DIR)
	cd $(DIR) && \
	curl -s -O $(SRC)/annotations.proto && \
	curl -s -O $(SRC)/http.proto

.PHONY: protobuf/google
protobuf/google:
	$(eval DIR = protobuf/google/protobuf)
	$(eval SRC = https://raw.githubusercontent.com/protocolbuffers/protobuf/master/src/google/protobuf)
	mkdir -p $(DIR)
	cd $(DIR) && \
	curl -s -O $(SRC)/empty.proto && \
	curl -s -O $(SRC)/descriptor.proto

# Miscellaneous tools to install once.

.make/install/protoc:
	if [ -z "$$(which protoc)" ]; then \
		curl -OL https://github.com/protocolbuffers/protobuf/releases/download/v3.6.1/protoc-3.6.1-linux-x86_64.zip ; \
		unzip protoc-3.6.1-linux-x86n_64.zip -d protoc ; \
		mv protoc/bin/* /usr/local/bin/ ; \
		mv protoc/include/* /usr/local/include/ ; \
		chmod +x /usr/local/bin/protoc ; \
		rm -rf protoc* ; \
	fi
	mkdir -p $(dir $@) && touch $@

# Install the protoc plugin to generate TypeScript. Use docker so people don't have to install npm.
./explorer/grpc/node_modules/ts-protoc-gen/bin/protoc-gen-ts:
	./hack/build/docker-buildenv.sh "\
		cd explorer/grpc && npm install && npm install ts-protoc-gen --no-bin-links --save-dev \
	"

.make/install/rpm:
	if [ -z "$$(which rpmbuild)" ]; then
		# You'll need to isntall `rpmbuild` for `cargo rpm` to work. I'm putting this here for reference:
		sudo apt-get install rpm
	fi
	mkdir -p $(dir $@) && touch $@

.make/install/cargo-native-packager:
	$(MAKE) -C execution-engine setup-cargo-packagers
	mkdir -p $(dir $@) && touch $@

.make/rustup-update: execution-engine/rust-toolchain
	$(MAKE) -C execution-engine setup
	mkdir -p $(dir $@) && touch $@
