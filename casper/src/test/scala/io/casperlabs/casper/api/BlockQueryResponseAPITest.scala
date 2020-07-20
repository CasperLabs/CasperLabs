package io.casperlabs.casper.api

import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper.consensus.Block.Justification
import io.casperlabs.casper.consensus._
import io.casperlabs.casper.consensus.info.BlockInfo
import io.casperlabs.casper.consensus.state.ProtocolVersion
import io.casperlabs.casper.helper.StorageFixture
import io.casperlabs.casper.util.BondingUtil.Bond
import io.casperlabs.casper.util.ProtoUtil
import io.casperlabs.catscontrib.TaskContrib._
import io.casperlabs.crypto.Keys
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.crypto.signatures.SignatureAlgorithm.Ed25519
import io.casperlabs.p2p.EffectsTestInstances.LogicalTime
import io.casperlabs.storage.block.BlockStorage
import monix.eval.Task
import org.scalatest.{FlatSpec, Matchers}
import io.casperlabs.models.Message
import io.casperlabs.casper.ValidatorIdentity

class BlockQueryResponseAPITest extends FlatSpec with Matchers with StorageFixture {
  implicit val timeEff = new LogicalTime[Task]
  val badTestHashQuery = "No such a hash"

  val version = ProtocolVersion(1)

  def genesisBlock(version: ProtocolVersion): Block = {
    val ps = Block
      .GlobalState()
      .withBonds(Seq(Bond(ByteString.copyFromUtf8("random"), 1)))
    val body = Block.Body()
    val header = ProtoUtil.blockHeader(
      body,
      parentHashes = Nil,
      justifications = Nil,
      state = ps,
      jRank = Message.asJRank(1),
      mainRank = Message.asMainRank(1),
      protocolVersion = version,
      timestamp = 1527191663,
      chainName = "casperlabs",
      creator = ValidatorIdentity.empty,
      validatorSeqNum = 0,
      validatorPrevBlockHash = ByteString.EMPTY
    )
    ProtoUtil.unsignedBlockProto(body, header)
  }
  val genesisBlock: Block = genesisBlock(version)
  val genesisHash         = genesisBlock.blockHash
  val genesisHashString   = Base16.encode(genesisBlock.blockHash.toByteArray)

  val blockNumber = 1L
  val timestamp   = 1527191665L
  val ps          = Block.GlobalState()
  val deployCount = 10L
  val deployCostAndPrice = (0L until deployCount).toList.map { i =>
    (100 * i + i, i + i)
  }
  val randomDeploys =
    deployCostAndPrice
      .traverse {
        case (cost, price) =>
          ProtoUtil.basicProcessedDeploy[Task]().map { pd =>
            pd.withCost(cost)
              .withDeploy(pd.getDeploy.withHeader(pd.getDeploy.getHeader.withGasPrice(price)))
          }
      }
      .unsafeRunSync(scheduler)
  val body              = Block.Body().withDeploys(randomDeploys)
  val parentsString     = List(genesisHashString)
  val parentsHashList   = List(genesisHash)
  val justifications    = Seq(Justification().withLatestBlockHash(genesisBlock.blockHash))
  val chainName: String = "abcdefgh"
  val secondBlockSenderString: String =
    "3456789101112131415161718192345678910111213141516171819261718192"
  val secondBlockSender: ByteString = ProtoUtil.stringToByteString(secondBlockSenderString)
  val secondValidator = ValidatorIdentity(
    Keys.PublicKey(secondBlockSender.toByteArray),
    Keys.PrivateKey(secondBlockSender.toByteArray),
    Ed25519
  )
  val secondBlock = ProtoUtil.block(
    justifications,
    genesisBlock.getHeader.getState.postStateHash,
    ByteString.EMPTY,
    Seq.empty,
    randomDeploys,
    ProtocolVersion(1),
    Seq(genesisBlock.blockHash),
    1,
    ByteString.EMPTY,
    chainName,
    timestamp,
    Message.asJRank(1),
    Message.asMainRank(1),
    secondValidator,
    ByteString.EMPTY,
    0,
    false
  )
  val secondHashString     = Base16.encode(secondBlock.blockHash.toByteArray)
  val blockHash: BlockHash = secondBlock.blockHash
  val secondBlockQuery     = secondHashString.take(5)

  // TODO: Test tsCheckpoint:
  // we should be able to stub in a tuplespace dump but there is currently no way to do that.
  "showBlock" should "return successful block info response" in withCombinedStorage() {
    implicit storage =>
      for {
        _         <- initData(storage)
        blockInfo <- BlockAPI.getBlockInfo[Task](secondBlockQuery, BlockInfo.View.BASIC)
        _         = blockInfo.getSummary.blockHash should be(blockHash)
        _         = blockInfo.getStatus.getStats.blockSizeBytes should be(secondBlock.serializedSize)
        _ = blockInfo.getStatus.getStats.deployCostTotal should be(
          deployCostAndPrice.map(_._1).sum
        )
        _ = blockInfo.getStatus.getStats.deployGasPriceAvg should be(
          deployCostAndPrice.map(x => x._1 * x._2).sum / deployCostAndPrice.map(_._1).sum
        )
        _ = blockInfo.getSummary.getHeader.jRank should be(blockNumber)
        _ = blockInfo.getSummary.getHeader.getProtocolVersion should be(version)
        _ = blockInfo.getSummary.getHeader.deployCount should be(deployCount)
        _ = blockInfo.getSummary.getHeader.parentHashes.head should be(genesisHash)
        _ = blockInfo.getSummary.getHeader.parentHashes should be(parentsHashList)
        _ = blockInfo.getSummary.getHeader.validatorPublicKeyHash should be(
          secondValidator.publicKeyHashBS
        )
        _ = blockInfo.getSummary.getHeader.chainName should be(chainName)
      } yield ()
  }

  it should "return children in FULL view" in withCombinedStorage() { implicit storage =>
    for {
      _         <- initData(storage)
      basicInfo <- BlockAPI.getBlockInfo[Task](genesisHashString, BlockInfo.View.BASIC)
      fullInfo  <- BlockAPI.getBlockInfo[Task](genesisHashString, BlockInfo.View.FULL)
      _         = basicInfo.getStatus.childHashes shouldBe empty
      _         = fullInfo.getStatus.childHashes should not be empty
    } yield ()
  }

  it should "return error when no block exists" in withCombinedStorage() { implicit storage =>
    for {
      blockQueryResponse <- BlockAPI
                             .getBlockInfo[Task](badTestHashQuery, BlockInfo.View.BASIC)
                             .attempt
    } yield {
      blockQueryResponse.isLeft shouldBe true
      blockQueryResponse.left.get.getMessage should include("NOT_FOUND")
    }
  }

  private def initData(blockStorage: BlockStorage[Task]): Task[Unit] =
    for {
      _ <- blockStorage.put(genesisBlock.blockHash, genesisBlock, Map.empty)
      _ <- blockStorage.put(secondBlock.blockHash, secondBlock, Map.empty)
    } yield ()
}
