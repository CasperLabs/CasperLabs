package io.casperlabs.storage.util

import com.google.protobuf.ByteString
import doobie._
import io.casperlabs.casper.consensus.Block.ProcessedDeploy
import io.casperlabs.casper.consensus.{BlockSummary, Deploy, DeploySummary, Era}
import io.casperlabs.crypto.Keys.{PublicKey, PublicKeyBS, PublicKeyHash, PublicKeyHashBS}
import io.casperlabs.casper.consensus.info.{BlockInfo, Event}
import io.casperlabs.casper.consensus.info.DeployInfo.ProcessingResult
import io.casperlabs.models.DeployImplicits.DeployOps
import io.casperlabs.storage.dag.FinalityStorage.FinalityStatus
import io.casperlabs.ipc.TransformEntry

trait DoobieCodecs {
  protected implicit val metaByteString: Meta[ByteString] =
    Meta[Array[Byte]].imap(ByteString.copyFrom)(_.toByteArray)

  protected implicit val metaPublicKeyHashBS: Meta[PublicKeyHashBS] =
    Meta[Array[Byte]].imap(d => PublicKeyHash(ByteString.copyFrom(d)))(_.toByteArray)

  protected implicit val readDeploy: Read[Deploy] =
    Read[(Array[Byte], Option[Array[Byte]])].map {
      case (deploySummary, maybeDeployBody) =>
        val deploy = Deploy.parseFrom(deploySummary)
        val maybeBody = maybeDeployBody
          .filterNot(_.isEmpty)
          .map(Deploy.Body.parseFrom) orElse deploy.body
        maybeBody.map(deploy.withBody).getOrElse(deploy)
    }

  protected implicit val readDeploySummary: Read[DeploySummary] = Read[Array[Byte]].map {
    case deploySummaryBytes => Deploy.parseFrom(deploySummaryBytes).getSummary
  }

  protected implicit val readDeployHeader: Read[Deploy.Header] =
    Read[Array[Byte]].map(Deploy.Header.parseFrom)

  protected implicit val readProcessingResult: Read[(ByteString, ProcessedDeploy)] = {
    Read[(Array[Byte], Long, Option[String], Int)].map {
      case (blockHash, cost, maybeError, stage) =>
        (
          ByteString.copyFrom(blockHash),
          ProcessedDeploy(
            deploy = None,
            cost = cost,
            isError = maybeError.nonEmpty,
            errorMessage = maybeError.getOrElse(""),
            stage = stage
          )
        )
    }
  }

  protected implicit val readProcessedDeploy: Read[ProcessedDeploy] = {
    Read[(Deploy, Long, Option[String], Int)].map {
      case (deploy, cost, maybeError, stage) =>
        ProcessedDeploy(
          deploy = Option(deploy),
          cost = cost,
          isError = maybeError.nonEmpty,
          errorMessage = maybeError.getOrElse(""),
          stage = stage
        )
    }
  }

  protected implicit val readBlockInfo: Read[BlockInfo] = {
    Read[(Array[Byte], Int, Int, Long, Long, Boolean, Boolean)].map {
      case (
          blockSummaryData,
          blockSize,
          deployErrorCount,
          deployCostTotal,
          deployGasPriceAvg,
          isFinalized,
          isOrphaned
          ) =>
        val blockSummary = BlockSummary.parseFrom(blockSummaryData)
        val blockStatus = BlockInfo
          .Status()
          .withStats(
            BlockInfo.Status
              .Stats()
              .withBlockSizeBytes(blockSize)
              .withDeployErrorCount(deployErrorCount)
              .withDeployCostTotal(deployCostTotal)
              .withDeployGasPriceAvg(deployGasPriceAvg)
          )
          .withFinality(FinalityStatus(isFinalized, isOrphaned))
        BlockInfo()
          .withSummary(blockSummary)
          .withStatus(blockStatus)
    }
  }

  protected implicit val readDeployAndProcessingResult: Read[ProcessingResult] = {
    Read[(Long, Option[String], Int, BlockInfo)].map {
      case (cost, maybeError, stage, blockInfo) =>
        ProcessingResult(
          cost = cost,
          isError = maybeError.nonEmpty,
          errorMessage = maybeError.getOrElse(""),
          stage = stage
        ).withBlockInfo(blockInfo)
    }
  }

  protected implicit val metaBlockSummary: Meta[BlockSummary] =
    Meta[Array[Byte]].imap(BlockSummary.parseFrom)(_.toByteArray)

  protected implicit val metaTransformEntry: Meta[TransformEntry] =
    Meta[Array[Byte]].imap(TransformEntry.parseFrom)(_.toByteArray)

  protected implicit val metaEra: Meta[Era] =
    Meta[Array[Byte]].imap(Era.parseFrom)(_.toByteString.toByteArray)

  protected implicit val metaEventValue: Meta[Event.Value] =
    // Event.Value is not directly parseable. We want SQLite to generate the ID,
    // so store it as an Event without ID.
    Meta[Array[Byte]].imap(Event.parseFrom(_).value)(v => Event(value = v).toByteArray)
}
