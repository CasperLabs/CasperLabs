CL_CASPER_NUM_VALIDATORS ?= 3
CL_SERVER_HTTP_PORT ?= 40403
CL_VERSION ?= latest

REFRESH_TARGETS   := ./monitoring/prometheus/refresh-targets.sh
HIGHWAY_ENV       := .casperlabs/chainspec/genesis/highway.env
RESET_HIGHWAY_ENV := rm -f $(HIGHWAY_ENV); ./scripts/highway-env.sh > $(HIGHWAY_ENV)

# Don't remove directories created as dependencies.
.SECONDARY:

# Remove all node-N environments.
clean: down $(shell find . -type d -name "node-*" | grep -v .casperlabs | grep -v keys | awk -F '/' '{print $$2"/down"}')
	docker network rm casperlabs || exit 0
	rm -rf .casperlabs
	rm -rf .make
	rm -rf keys

# Create a new node environment by linking to the template docker-complse.yml file.
node-%: .casperlabs
	$(eval N = $*)
	$(eval ENV = node-$(N)/.env)

	@# Collect existing nodes before making the newest one. We'll bootstrap from them.
	$(eval NODES = $(shell find . -maxdepth 1 -type d -name "node-*" | sed 's/.\///'))

	mkdir node-$(N)

	@# Create an .env file to hold template variables for docker-compose.
	echo NODE_NUMBER=$(N) >> $(ENV)
	echo BOOTSTRAP_HOSTNAMES=$(NODES) >> $(ENV)

	# Set default env values. Create overrides with `export CL_<name>=<value>` before running `make node-X/up` commands,
	# or override them for individual nodes e.g. `CL_VERSION=0.1.2 make node-0/up`
	# Capture all CL_ env vars into the .env file so later they don't revert if we run things in another console.
	# Take overrides from template/.env file.
	bash -c '\
	export CL_VERSION=$(CL_VERSION) ; \
	while read kv; do \
		k=$$(echo $${kv} | cut -d'=' -f1) ; \
		v=$$(echo $${kv} | cut -d'=' -f2) ; \
		if [ -z "$${!k}" ]; then \
			export $${k}=$${v} ; \
		fi ; \
	done < ${PWD}/template/.env ; \
	for var in $$(compgen -v CL_); do \
		echo $${var}=$${!var} >> $(ENV) ; \
	done'

	@# Link Dockerfile so we can change it and reapply.
	@#ln -s ${PWD}/template/docker-compose.yml node-$(N)/docker-compose.yml
	@#Alternatively just make a copy so you can edit it independently.
	cp ${PWD}/template/docker-compose.yml node-$(N)/docker-compose.yml


# Start node.
# Only make `highway.env` when the first node is started, so the era epoch doesn't become stale.
node-%/up: node-% .make/docker/network maybe-reset-highway-env
	@# Go into the directory to pick up values from .env
	if [ "$(CL_VERSION)" != "latest" ]; then \
		cd node-$* && docker-compose pull; \
	fi
	cd node-$* && docker-compose up -d
	$(REFRESH_TARGETS)

# Tear down node. Using docker to delete logs owned by root for now.
node-%/down: $(HIGHWAY_ENV)
	if [ -d node-$* ]; then \
		cd node-$* && docker-compose down && cd - && \
		docker run -it --rm \
			-v $(PWD)/node-$*/logs:/var/logs/casperlabs \
			--entrypoint sh casperlabs/node:$(CL_VERSION) \
			-c 'rm -rf /var/logs/casperlabs/*' && \
		rm -rf node-$* ; \
	fi
	$(REFRESH_TARGETS)

# Just a quick way to check whether the server published any Prometheus metrics yet.
node-%/metrics:
	docker run --rm --network casperlabs appropriate/curl -s http://node-$*:$(CL_SERVER_HTTP_PORT)/metrics

node-%/status:
	docker run --rm --network casperlabs appropriate/curl -s http://node-$*:$(CL_SERVER_HTTP_PORT)/status | jq


# Start common components.
up: \
		.make/docker/network \
		.make/stests/build \
		.casperlabs
	$(REFRESH_TARGETS)
	if [ "$(CL_VERSION)" != "latest" ]; then \
		docker-compose pull; \
	fi
	docker-compose -p casperlabs up -d --remove-orphans

# Stop common components.
down:
	docker-compose -p casperlabs down

# Start all nodes and supporting services, for convenience.
up-all: up
	bash -c 'i=0 ; while [[ $$i -lt $(CL_CASPER_NUM_VALIDATORS) ]] ; do \
		$(MAKE) node-$$i/up ; \
		((i = i + 1)) ; \
	done'

# Slow down traffic between nodes to simulate network effects and large blocks.
# If we slow it down too much then the client won't be able to connect though.
# https://alexei-led.github.io/post/pumba_docker_netem/
delay:
	docker run -it --rm -v /var/run/docker.sock:/var/run/docker.sock gaiaadm/pumba \
	    netem \
	      --duration 5m \
	      --interface eth0 \
	      --tc-image gaiadocker/iproute2 \
	      delay \
	        --time 500 \
	        --jitter 100 \
	        --distribution normal \
	      re2:^node

# Use the `rate` function to limit bandwidth.
slow:
	docker run -it --rm -v /var/run/docker.sock:/var/run/docker.sock gaiaadm/pumba \
	    netem \
	      --duration 5m \
	      --interface eth0 \
	      --tc-image gaiadocker/iproute2 \
	      rate \
	        --rate 100kbit \
	      re2:^node


.make/docker/network:
	docker network create casperlabs
	mkdir -p $(dir $@) && touch $@

# Generate keys and bonds.
.casperlabs:
	mkdir -p .casperlabs/nodes
	mkdir -p .casperlabs/chainspec/genesis

	@# Create a `faucet-account` to hold some initial tokens to distribute.
	mkdir -p keys/faucet-account
	../key-management/docker-gen-account-keys.sh keys/faucet-account
	FAUCET_PK="$$(cat keys/faucet-account/account-pk-hex)" ; \
	echo "$$FAUCET_PK,ed25519,1000000000000000000,0" > .casperlabs/chainspec/genesis/accounts.csv

	@# Create bonded validators with 0 balance.
	bash -c 'i=0 ; while [[ $$i -lt $(CL_CASPER_NUM_VALIDATORS) ]] ; do \
		echo Generating validator $$i / $(CL_CASPER_NUM_VALIDATORS) ; \
		mkdir -p .casperlabs/nodes/node-$$i ; \
		mkdir -p keys/account-$$i ; \
		../key-management/docker-gen-keys.sh .casperlabs/nodes/node-$$i ; \
		../key-management/docker-gen-account-keys.sh keys/account-$$i ; \
		BOND=$$(( $(CL_CASPER_NUM_VALIDATORS)*10+$$i )) ; \
		VALIDATOR_PK="$$(cat .casperlabs/nodes/node-$$i/validator-pk-hex)" ; \
		echo "$$VALIDATOR_PK,ed25519,0,$$BOND" >> .casperlabs/chainspec/genesis/accounts.csv ; \
		((i = i + 1)) ; \
	done'

	@# Check that the files we wanted exist and aren't empty.
	[ -s .casperlabs/chainspec/genesis/accounts.csv ]


# Create common Highway environment overrides for the chainspec defaults,
# common to all, so a node started later won't choose a different era epoch.
$(HIGHWAY_ENV): reset-highway-env

# If we need to re-start the nodes after a long time, we might have to recreate the era epoch, if it's short, like 5 minutes.
reset-highway-env: .casperlabs
	$(RESET_HIGHWAY_ENV)

# Reset highway env if there are no nodes running at the moment or there's only one node directory created (we are restarting the 1 node we have)
maybe-reset-highway-env:
	DEFINED=$$(find . -maxdepth 1 -type d | grep -e 'node-[0-9]' | wc -l); \
	RUNNING=$$(docker ps --format '{{.Names}}' | grep -e 'node-[0-9]' | wc -l); \
	if [ "$${RUNNING}" -eq "0" ] || [ "$${DEFINED}" -eq "1" ]; then \
		$(RESET_HIGHWAY_ENV); \
	fi


# https://github.com/CasperLabs/stests
stests/build:
	# Build contracts stests will want to use.
	$(eval CONTRACTS_FROM = ../../execution-engine/target/wasm32-unknown-unknown/release)
	$(eval CONTRACTS_TO = ../../client-py/casperlabs_client/)
	cd ../../execution-engine && \
	  make build-contract-rs/transfer-to-account-u512-stored \
		     build-contract-rs/counter-define
	# Copy them over under the python client; it's where stests will look for them at the moment.
	cp $(CONTRACTS_FROM)/transfer_to_account_u512_stored.wasm $(CONTRACTS_TO)
	cp $(CONTRACTS_FROM)/counter_define.wasm $(CONTRACTS_TO)

	# Package the latest Python client; the one on pypi might be older than the latest and greatest.
	# This might cause issues as python client breaks with TestNet
	# Since at least 0.17, PyPi version has the .wasm, one of the main reasons we manually built and included.
	# If problems come up, we should most likely pull from PyPi version 0.18.1.
	cd ../.. &&	make build-python-client

	# Make the client available to the docker deamon here (smaller context.).
	rm -rf stests/.build
	mkdir -p stests/.build
	cp ../../client-py/dist/casperlabs_client*.tar.gz stests/.build/

	docker build \
	  --build-arg CACHEBUST=$$(date +%s) \
		-f stests/Dockerfile -t casperlabs/stests:latest stests

	rm -rf stests/.build

# stests is part of the docker-compose that runs on `up`, but it has to be built, it's not published yet.
.make/stests/build:
	BUILT=$$(docker images | grep casperlabs/stests | wc -l); \
	if [ "$${BUILT}" -eq "0" ]; then \
		$(MAKE) stests/build; \
	fi

# After nodes have been started, and `redis` with `make up`,
# and an stest image built with `make stests/build`,
# this command can run stests in interactive mode.
stests/console: .casperlabs
	@# Collect existing nodes, so we can register them automatically.
	@# To not assume nodes will be online/offline, the registration is based on declared directories,
	@# but if that doesn't work we could just list the running docker containers.
	@# Unfortunately the stests workers will only subscribe to events in the beginning,
	@# but not when nodes are registered later, so we have to do a `docker restart stests`
	@# after this console has registered all the required containers.
	$(eval NODES = $(shell find . -maxdepth 1 -type d -name "node-*" | sort | sed 's/.\///'))

	@# Make sure stests workers pick up newly registered nodes.
	@# This will be happening automatically at some point in stests.
	sh -c 'sleep 30s && docker restart stests' > /dev/null 2>&1 &

	docker run -it --rm \
	  --network casperlabs \
		--name stests-console \
		-v ${PWD}/keys:/root/casperlabs/keys \
		-v ${PWD}/stests/start-console.sh:/root/casperlabs/start-console.sh \
		-e KEYS=/root/casperlabs/keys \
		-e STESTS_BROKER_REDIS_HOST=redis \
		-e STESTS_CACHE_REDIS_HOST=redis \
		-e STESTS_MWARE_REDIS_HOST=redis \
		-e NETWORK=poc1 \
		-e NODES='$(NODES)' \
		casperlabs/stests:latest bash -c 'chmod +x start-console.sh && ./start-console.sh'
