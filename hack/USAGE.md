# Usage of the CasperLabs system

This (work in progress) document provides a detailed description of
the APIs and libraries needed to write smart contracts in Rust and run
them on a CasperLabs node.

## Background

A complete description of the theory behind the CasperLabs system, as
well as some implementation details, can be found in the [technical
specification](https://techspec.casperlabs.io/). This section gives an
overview of some of the core ideas from that document in order to
understand the APIs.

Fundamentally, the CasperLabs system is a distributed key-value store
with particular rules around how that store can be updated. We call a
snapshot of the key-value store at a particular point in time the
"global state". The global state contains all the data which is
important to users of the system (e.g. token balances, smart contract
logic, etc.). Keys for the key-value store come in three forms:
`Address`, `Hash`, and `URef`. Each key type serves a different
purpose. `Address`-type keys have accounts as the associated value
(more details on accounts will follow), `Hash`-type keys store "smart
contracts", and `URef`-type keys store any data which can be
represented in the [CasperLabs
ABI](https://techspec.casperlabs.io/en/latest/implementation/appendix.html#b-serialization-format)
(except accounts). An account is a cryptographically-secured
entry-point into the system. All changes to the global state must be
tied to an account. These changes occur via "session code" submitted
by users. A smart contract is a function stored in the global state
which may also modify the state when it is called. Smart contracts can
be called from session code, as well as from other smart contracts.
They accept arguments and return a value (possibly empty in the case
of a function which is entirely side-effects). Smart contracts can
have persisted state by holding `URef`-type keys that correspond to
the state variable values stored in the global state. For the
convenience of users, smart contracts and accounts can associate
global state keys with human-readable names such that they can be
referred to more easily. For example, rather than remembering the
`Hash`-type key pointing to a contract of some particular importance
to a user, that user could associate that `Hash` with a name of their
choosing, say "my-important-contract", in the context of their
account. This would allow the user, in their session code, to find the
key for the contract using the string "my-important-contract".

Changes that are made to the global state are captured in the form of
"blocks". These blocks form a directed acyclic graph (DAG), allowing
some changes to happen concurrently. Each block has a pre-state and a
post-state. The pre-state is the state of the global state before
running any new code which was introduced by the block, while the
post-state is the state of the global state after running the code in
the block. Blocks are identified by a cryptographic hash of their
contents (just block hash for short).

## Example

Consider the [counter contract from our contract examples
repo](https://github.com/CasperLabs/CasperLabs/blob/dev/execution-engine/contracts/examples/counter-define/src/lib.rs).
The contract logic is given in the function `counter_ext()`, while the
`call()` function contains the session code that would be executed by
a user storing the contract in the global state.

```rust
#[no_mangle]
pub extern "C" fn counter_ext() {
    // Look up the key associated with the name "count".
    // This key points to the contract's state variable in the global state (key-value store).
    let i_key: UPointer<i32> = get_uref("count").to_u_ptr().unwrap();

    // The first (zeroth) argument passed to this function is the method name
    // (i.e. action to perform during this call).
    let method_name: String = get_named_arg("method_name");
    match method_name.as_str() {
        "inc" => add(i_key, 1), // increment the state variable by 1
        "get" => {
            let result = read(i_key); // read the value of the state variable
            ret(&result, &Vec::new()); //return the value to the caller
        }
        _ => panic!("Unknown method name!"),
    }
}

#[no_mangle]
pub extern "C" fn call() {
    let counter_local_key = new_uref(0); //initialize counter state variable to 0

    //create map of key to name associations for the stored contract
    let mut counter_urefs: BTreeMap<String, Key> = BTreeMap::new();
    let key_name = String::from("count");
    // Associate "count" to the contract's state variable
    counter_urefs.insert(key_name, counter_local_key.into());

    // Store the function named "counter_ext" as a smart contract in the
    // CasperLabs system. The key it is stored under (which is the `Hash`-type)
    // is returned.
    let hash = store_function("counter_ext", counter_urefs);

    // `call` is the session code run by the account storing the contract.
    // This last line associates the name "counter" with the key for the
    // stored contract, allowing us to easily refer to it in the future
    // (this association exists in this account only).
    add_uref("counter", &hash);
}
```

As we can see on the first line of the `counter_ext` function body,
there is a `URef` associated with the name "count" in the contract.
The "count" state variable is how the counter contract counts; its
value is returned when we call "get" and its value is incremented by
one when we call "inc". The `call` function simply stores the counter
contract logic in the global state and creates an association between
they key under which the contract is stored and the name "counter".

Suppose this code was deployed (in the sense of the section above)
from the account with address
`3030303030303030303030303030303030303030` (indeed, this is the
address of the default account in the current version of the
CasperLabs system). We could use the CasperLabs client to query the
state of the "count" state variable as follows:

```
casperlabs-client --host 127.0.0.1 --port 40401 query-state \
    -t address \
    -k 3030303030303030303030303030303030303030 \
    -p "counter/count" \
    -b <block-hash>
```

Here we see that the type of the key is an `Address` (because we are
starting from an account), the address we are using is the one where
the code was deployed from and the path indicates to first look up the
association with the name "counter" (which returns the stored smart
contract's key), then to look up the association with the name "count"
(this look up now happens in the context of the contract because of
the previous part of the path, and so we find the key associated with
the contract's state variable). The result of this query will be `0`,
the initial value of the counter contract's state variable. If we were
to deploy code calling the "inc" method of this contract ([which is
also given in the examples
repo](https://github.com/CasperLabs/CasperLabs/blob/dev/execution-engine/contracts/examples/counter-call/src/lib.rs))
then query again, the result would be `1`.

To further illustrate the semantics of the "path" in the query,
consider the following equivalent queries:

```
casperlabs-client --host 127.0.0.1 --port 40401 query-state \
    -t hash \
    -k <contract hash> \
    -p "count" \
    -b <block-hash>
```

```
casperlabs-client --host 127.0.0.1 --port 40401 query-state \
    -t uref \
    -k <uref id> \
    -p "" \
    -b <block-hash>
```

In the first equivalent query, we choose to start our search from the
contract directly instead of from the account that deployed it. This
is done by specifying the contract's `Hash`-type key as the starting
point, and shortening the path to include only the association between
"count" and the state variable. This query is less convenient than the
original because, while it is typical to know the account address one
uses, it is not necessarily typical to know the specific key a
contract is stored under.

In the second equivalent query, we start directly at the `URef` which
points to the state variable and so no path is needed. This is the
least convenient of all the queries because it will be very difficult
to discover the particular key a state variable lives under (as they
are randomly generated at runtime with a seed determined by the deploy
specifics).
