package io.casperlabs.casper.genesis

import java.io.PrintWriter
import java.nio.file.{Files, Path, Paths}
import java.util.Base64

import cats.effect.Sync
import cats.implicits._
import io.casperlabs.casper.consensus.state
import io.casperlabs.casper.helper.{HashSetCasperTestNode, StorageFixture}
import com.google.protobuf.ByteString
import io.casperlabs.casper.util.{CasperLabsProtocol, ProtoUtil}
import io.casperlabs.casper.util.execengine.ExecutionEngineServiceStub
import io.casperlabs.crypto.Keys
import io.casperlabs.ipc
import io.casperlabs.models.{Message, Weight}
import io.casperlabs.shared.LogStub
import io.casperlabs.shared.PathOps.RichPath
import io.casperlabs.shared.Log
import io.casperlabs.smartcontracts.ExecutionEngineService
import io.casperlabs.storage.BlockMsgWithTransform
import io.casperlabs.storage.block.BlockStorage
import monix.eval.Task
import org.scalatest.{FlatSpec, Matchers}

class GenesisTest extends FlatSpec with Matchers with StorageFixture {

  it should "create a valid genesis block" in withCombinedStorage() { implicit storage =>
    val accounts = Seq(
      ("KZZwxShJ8aqC6N/lvocsFrYAvwnMiYPgS5A0ETWPLeY=", 0, 100),
      ("V3dfs7swdXYE68RTvQObGZ6PCadHZKwWkPc25zS33hg=", 0, 200),
      ("a/GydTUB0C04Z4lQam2TaB0imcbt/URV9Za5e8VyWWg=", 100, 0)
    )

    val maxBlockSizeBytes = 10 * 1024 * 1024
    val maxTtlMillis      = 1000
    val maxDependencies   = 10

    val spec = ipc.ChainSpec
      .GenesisConfig()
      .withName("casperlabs")
      .withTimestamp(1234567890L)
      .withProtocolVersion(state.ProtocolVersion(1))
      .withEeConfig(
        ipc.ChainSpec.GenesisConfig
          .ExecConfig()
          .withAccounts(accounts map {
            case (key, balance, bond) =>
              ipc.ChainSpec.GenesisConfig.ExecConfig
                .GenesisAccount()
                .withPublicKeyHash(ByteString.copyFrom(java.util.Base64.getDecoder.decode(key)))
                .withBalance(state.BigInt(balance.toString, bitWidth = 512))
                .withBondedAmount(state.BigInt(bond.toString, bitWidth = 512))
          })
      )
      .withDeployConfig(
        ipc.ChainSpec
          .DeployConfig()
          .withMaxBlockSizeBytes(maxBlockSizeBytes)
          .withMaxDependencies(maxDependencies)
          .withMaxTtlMillis(maxTtlMillis)
      )

    val validatorsMap: Map[Keys.PublicKeyHashBS, Weight] =
      accounts.collect {
        case (key, _, bond) if bond > 0 =>
          (
            Keys.PublicKeyHash(ByteString.copyFrom(java.util.Base64.getDecoder.decode(key))),
            Weight(bond)
          )
      }.toMap

    implicit val casperSmartContractsApi = HashSetCasperTestNode.simpleEEApi[Task](validatorsMap)
    implicit val logEff                  = LogStub[Task]()

    for {
      genesisWithTransform <- Genesis.fromChainSpec[Task](spec)
      implicit0(versions: CasperLabsProtocol[Task]) <- CasperLabsProtocol
                                                        .fromChainSpec[Task](
                                                          ipc
                                                            .ChainSpec()
                                                            .withGenesis(spec)
                                                        )

      deployConfig <- versions.configAt(Message.asMainRank(0L)).map(_.deployConfig)
      _            = assert(deployConfig.maxTtlMillis == maxTtlMillis)
      _            = assert(deployConfig.maxDependencies == maxDependencies)
      _            = assert(deployConfig.maxBlockSizeBytes == maxBlockSizeBytes)

      BlockMsgWithTransform(Some(genesis), _) = genesisWithTransform

      _ = genesis.getHeader.chainName shouldBe "casperlabs"
      _ = genesis.getHeader.timestamp shouldBe 1234567890L
      _ = genesis.getHeader.getProtocolVersion shouldBe state.ProtocolVersion(1)
      _ = genesis.getHeader.getState.bonds should have size 2

      stored <- storage.get(genesis.blockHash)
      _      = stored should not be (empty)

      dag <- storage.getRepresentation
      maybePostGenesisStateHash <- ExecutionEngineServiceStub
                                    .validateBlockCheckpoint[Task](
                                      genesis,
                                      dag
                                    )
    } yield maybePostGenesisStateHash shouldBe 'right
  }
}
