from casperlabs_client import consts
from casperlabs_client.consts import SUPPORTED_KEY_ALGORITHMS, ED25519_KEY_ALGORITHM
from casperlabs_client.arg_types import algorithm, directory_for_write, positive_integer

ALGORITHM_OPTION = [
    ("-a", "--algorithm"),
    dict(
        required=False,
        type=algorithm,
        choices=SUPPORTED_KEY_ALGORITHMS,
        default=ED25519_KEY_ALGORITHM,
        help=f"Algorithm used for public key generation. Default is {ED25519_KEY_ALGORITHM}.",
    ),
]

FROM_OPTION = [
    ("-f", "--from"),
    dict(
        required=False,
        type=str,
        help="The account hash of the account which is the context of this deployment, base16 encoded.",
    ),
]

DIRECTORY_FOR_WRITE_OPTION = [
    ("directory",),
    dict(type=directory_for_write, help="Output directory. Should already exist."),
]


PAYMENT_OPTIONS = [
    [
        ("-p", "--payment"),
        dict(
            required=False,
            type=str,
            default=None,
            help="Path to the file with payment code",
        ),
    ],
    [
        ("--payment-amount",),
        dict(
            required=False,
            type=int,
            default=None,
            help=(
                "Standard payment amount. Use this with the default payment, or override with --payment-args "
                "if custom payment code is used. By default --payment-amount is set to 10000000"
            ),
        ),
    ],
    [
        ("--payment-hash",),
        dict(
            required=False,
            type=str,
            default=None,
            help="Hash of the stored contract to be called in the payment; base16 encoded",
        ),
    ],
    [
        ("--payment-name",),
        dict(
            required=False,
            type=str,
            default=None,
            help="Name of the stored contract (associated with the executing account) to be called in the payment",
        ),
    ],
    [
        ("--payment-package-hash",),
        dict(
            required=False,
            type=str,
            default=None,
            help="Hash of the stored package to be called in the payment; base16 encoded",
        ),
    ],
    [
        ("--payment-package-name",),
        dict(
            required=False,
            type=str,
            default=None,
            help="Name of the stored package (associated with the executing account) to be called in the payment",
        ),
    ],
    [
        ("--payment-entry-point",),
        dict(
            required=False,
            type=str,
            default=None,
            help="Name of the method that will be used when calling the payment contract.",
        ),
    ],
    [
        ("--payment-version",),
        dict(
            required=False,
            type=positive_integer,
            default=None,
            help="Version of the called payment contract.  Latest will be used by default",
        ),
    ],
    [
        ("--payment-args",),
        dict(
            required=False,
            type=str,
            help="""JSON encoded list of payment args, e.g.: '[{"name": "amount", "value": {"big_int": {"value": "123456", "bit_width": 512}}}]'""",
        ),
    ],
]

SESSION_OPTIONS = [
    [
        ("-s", "--session"),
        dict(
            required=False,
            type=str,
            default=None,
            help="Path to the file with session code",
        ),
    ],
    [
        ("--session-hash",),
        dict(
            required=False,
            type=str,
            default=None,
            help="Hash of the stored contract to be called in the session; base16 encoded",
        ),
    ],
    [
        ("--session-name",),
        dict(
            required=False,
            type=str,
            default=None,
            help="Name of the stored contract (associated with the executing account) to be called in the session",
        ),
    ],
    [
        ("--session-package-hash",),
        dict(
            required=False,
            type=str,
            default=None,
            help="Hash of the stored package to be called in the session; base16 encoded",
        ),
    ],
    [
        ("--session-package-name",),
        dict(
            required=False,
            type=str,
            default=None,
            help="Name of the stored package (associated with the executing account) to be called in the session",
        ),
    ],
    [
        ("--session-entry-point",),
        dict(
            required=False,
            type=str,
            default=None,
            help="Name of the method that will be used when calling the session contract.",
        ),
    ],
    [
        ("--session-version",),
        dict(
            required=False,
            type=positive_integer,
            default=None,
            help="Version of the called session contract. Latest will be used by default.",
        ),
    ],
    [
        ("--session-args",),
        dict(
            required=False,
            type=str,
            help="""JSON encoded list of session args, e.g.: '[{"name": "amount", "value": {"long_value": 123456}}]'""",
        ),
    ],
]

CHAINNAME_OPTION = [
    ("--chain-name",),
    dict(
        required=False,
        type=str,
        help="Name of the chain to optionally restrict the deploy from being accidentally included anywhere else.",
    ),
]

DEPENDENCIES_OPTION = [
    ("--dependencies",),
    dict(
        required=False,
        nargs="+",
        default=None,
        help="List of deploy hashes (base16 encoded) which must be executed before this deploy.",
    ),
]


def private_key_option(required=False):
    return [
        ("--private-key",),
        dict(
            required=required,
            default=None,
            type=str,
            help="Path to the file with account private key.  Assumed to be Ed25519, unless --algorithm is given.",
        ),
    ]


def public_key_option(required=False):
    return [
        ("--public-key",),
        dict(
            required=required,
            default=None,
            type=str,
            help="Path to the file with account public key.  Assumed to be Ed25519, unless --algorithm is given.",
        ),
    ]


TTL_MILLIS_OPTION = [
    ("--ttl-millis",),
    dict(
        required=False,
        type=int,
        help="Time to live. Time (in milliseconds) that the deploy will remain valid for.'",
    ),
]

WAIT_PROCESSED_OPTION = [
    ("-w", "--wait-for-processed"),
    dict(action="store_true", help="Wait for deploy status PROCESSED or DISCARDED"),
]

TIMEOUT_SECONDS_OPTION = [
    ("--timeout-seconds",),
    dict(type=int, default=consts.STATUS_TIMEOUT, help="Timeout in seconds"),
]


DEPLOY_OPTIONS = (
    [
        FROM_OPTION,
        CHAINNAME_OPTION,
        DEPENDENCIES_OPTION,
        TTL_MILLIS_OPTION,
        WAIT_PROCESSED_OPTION,
        TIMEOUT_SECONDS_OPTION,
        ALGORITHM_OPTION,
    ]
    + SESSION_OPTIONS
    + PAYMENT_OPTIONS
)
