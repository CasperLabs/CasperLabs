# Hack to fix the relative imports problems with grpc #
import sys
from pathlib import Path

sys.path.append(str(Path(__file__).resolve().parents[1]))
# end of hack #

# ~/CasperLabs/protobuf/io/casperlabs/casper/consensus/info.proto
from . import info_pb2 as info


def deploy_info_view(full_view):
    if full_view:
        return info.DeployInfo.View.FULL
    else:
        return info.DeployInfo.View.BASIC


def block_info_view(full_view):
    if full_view:
        return info.BlockInfo.View.FULL
    else:
        return info.BlockInfo.View.BASIC
