FROM python:3.7.7-slim

LABEL MAINTAINER="CasperLabs, LLC. <info@casperlabs.io>"

RUN apt-get clean && rm -rf /var/lib/apt/lists/* && apt-get update && apt-get install -y gcc unzip curl sudo
RUN curl -OL https://github.com/google/protobuf/releases/download/v3.2.0/protoc-3.2.0-linux-x86_64.zip
RUN unzip protoc-3.2.0-linux-x86_64.zip -d protoc3 && sudo mv protoc3/bin/* /usr/local/bin/
RUN sudo mv protoc3/include/* /usr/local/include/ && ln -s /protoc3/bin/protoc /usr/bin/protoc
RUN python3 -m pip install pipenv
ENTRYPOINT ["/root/integration-testing/run_tests.sh"]

ENV IN_DOCKER=true

WORKDIR /root/integration-testing
RUN mkdir -p /root/integration-testing
COPY ./ /root/integration-testing/
RUN python3 -m pipenv sync
RUN mkdir -p /root/protobuf
COPY ./protobuf/ /root/protobuf/
