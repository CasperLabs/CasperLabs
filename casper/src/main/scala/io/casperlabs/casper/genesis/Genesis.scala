package io.casperlabs.casper.genesis

import java.io.File
import java.nio.file.Path
import java.util.Base64

import cats.data.EitherT
import cats.implicits._
import cats.{Applicative, MonadError}
import com.github.ghik.silencer.silent
import com.google.protobuf.ByteString
import io.casperlabs.storage.block.BlockStorage
import io.casperlabs.casper.{CasperConf, PrettyPrinter, ValidatorIdentity}
import io.casperlabs.casper.consensus._
import io.casperlabs.casper.consensus.state.ProtocolVersion
import io.casperlabs.casper.util.ProtoUtil.{blockHeader, deployDataToEEDeploy, unsignedBlockProto}
import io.casperlabs.casper.util.{CasperLabsProtocol, ProtoUtil}
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.crypto.Keys
import io.casperlabs.crypto.Keys.PublicKeyHash
import io.casperlabs.ipc
import io.casperlabs.ipc.RunGenesisRequest
import io.casperlabs.shared.Sorting._
import io.casperlabs.shared.{Log, Sorting}
import io.casperlabs.smartcontracts.ExecutionEngineService
import io.casperlabs.storage.BlockMsgWithTransform
import io.casperlabs.storage.dag.FinalityStorage

import scala.util._
import scala.util.control.NoStackTrace
import io.casperlabs.models.Message
import io.casperlabs.crypto.hash.Blake2b256

object Genesis {
  import Sorting.byteArrayOrdering

  // https://casperlabs.atlassian.net/wiki/spaces/EN/pages/135528449/Genesis+Process+Specification
  def fromChainSpec[F[_]: MonadThrowable: Log: ExecutionEngineService: BlockStorage: FinalityStorage](
      genesisConfig: ipc.ChainSpec.GenesisConfig
  ): F[BlockMsgWithTransform] =
    for {
      // Execute the EE genesis setup based on the chain spec.
      // The results are already going to be committed.
      genesisResult <- ExecutionEngineService[F]
                        .runGenesis(
                          RunGenesisRequest()
                            .withGenesisConfigHash(ProtoUtil.protoHash(genesisConfig))
                            .withProtocolVersion(genesisConfig.getProtocolVersion)
                            .withEeConfig(genesisConfig.getEeConfig)
                        )
                        .rethrow
      transforms    = genesisResult.getEffect.transformMap
      postStateHash = genesisResult.poststateHash

      // Sorted list of bonded validators.
      bonds = genesisConfig.getEeConfig.accounts
        .sortBy { x =>
          x.publicKeyHash -> x.getBondedAmount.value
        }
        .collect {
          case account if account.bondedAmount.isDefined && account.getBondedAmount.value != "0" =>
            Bond(account.publicKeyHash, account.bondedAmount)
        }
        .toSeq

      state = Block
        .GlobalState()
        .withBonds(bonds)
        .withPreStateHash(ExecutionEngineService[F].emptyStateHash)
        .withPostStateHash(postStateHash)

      // Chain spec based Genesis will have an empty body,
      // no deploys, so everyone has to calculate it themselves.
      body = Block.Body()

      header = blockHeader(
        body,
        creator = ValidatorIdentity.empty, // Genesis has no creator
        parentHashes = Nil,
        justifications = Nil,
        state = state,
        jRank = Message.asJRank(0),
        mainRank = Message.asMainRank(0),
        validatorSeqNum = 0,
        validatorPrevBlockHash = ByteString.EMPTY,
        protocolVersion = genesisConfig.getProtocolVersion,
        timestamp = genesisConfig.timestamp,
        chainName = genesisConfig.name
      )

      unsignedBlock = unsignedBlockProto(body, header)

      genesis = BlockMsgWithTransform(
        Some(unsignedBlock),
        BlockStorage.blockEffectsMapToProto(Map(0 -> transforms))
      )

      // And store the block as well since we won't have any other means of retrieving its effects.
      _ <- BlockStorage[F].put(genesis)
      _ <- FinalityStorage[F].markAsFinalized(
            genesis.getBlockMessage.blockHash,
            Set.empty,
            Set.empty
          )
    } yield genesis
}
