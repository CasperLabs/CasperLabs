FROM ubuntu:18.04 as build

RUN apt update && \
    apt install -y git make g++; \
    cd /tmp; \
    git clone https://github.com/maandree/libkeccak.git; \
    git clone https://github.com/maandree/sha3sum.git; \
    cd /tmp/libkeccak; git checkout 1.2; make install; ldconfig;  \
    cd /tmp/sha3sum; make;

RUN cd /tmp; \
    git clone https://github.com/BLAKE2/BLAKE2.git; \
    cd /tmp/BLAKE2/b2sum; make;



FROM ubuntu:18.04

RUN apt update && apt install -y openssl libgomp1 xxd bsdmainutils && mkdir -p -- "/usr/local/include"

# https://github.com/openssl/openssl/issues/7754
RUN sed -i s/RANDFILE/#RANDFILE/ /etc/ssl/openssl.cnf

# Based on libkeccak `make install`
COPY --from=build /tmp/libkeccak/libkeccak.so /usr/local/lib/libkeccak.so.1.2
RUN ln -sf -- "/usr/local/lib/libkeccak.so.1.2" "/usr/local/lib/libkeccak.so.1" && ln -sf -- "/usr/local/lib/libkeccak.so.1.2" "/usr/local/lib/libkeccak.so"
COPY --from=build /tmp/libkeccak/libkeccak.a /usr/local/lib/libkeccak.a
COPY --from=build /tmp/libkeccak/libkeccak.h /usr/local/include
COPY --from=build /tmp/libkeccak/libkeccak-legacy.h /usr/local/include
RUN ldconfig

COPY --from=build /tmp/sha3sum/keccak-256sum /usr/local/bin

COPY --from=build /tmp/BLAKE2/b2sum/b2sum /usr/local/bin

COPY gen-keys.sh /
ENTRYPOINT [ "/gen-keys.sh" ]
