import pytest
from casperlabs_client.deploy import DeployData
from collections import defaultdict


@pytest.mark.parametrize("args,exception", ())
def test_create_deploy_data_from_args(args, exception):
    pass


@pytest.mark.parametrize(
    "args,exception",
    (
        (
            defaultdict(lambda: None, dict(session="nothing.wasm", session_name="bob")),
            ValueError,
        ),
        (defaultdict(lambda: None, dict(nothing=None)), ValueError),
    ),
)
def test_create_deploy_data_from_args_raises_exception(args, exception):
    with pytest.raises(exception):
        _ = DeployData.from_args(args)
