package io.casperlabs.casper.api

import cats.effect.Sync
import com.github.ghik.silencer.silent
import com.google.protobuf.ByteString
import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper.MultiParentCasperRef.MultiParentCasperRef
import io.casperlabs.casper._
import io.casperlabs.casper.consensus._
import io.casperlabs.casper.consensus.info.BlockInfo
import io.casperlabs.casper.helper.BlockGenerator._
import io.casperlabs.casper.helper.BlockUtil.generateValidator
import io.casperlabs.casper.helper._
import io.casperlabs.casper.util.BondingUtil.Bond
import io.casperlabs.catscontrib.{Fs2Compiler, MonadThrowable}
import io.casperlabs.models.cltype.protobuf.dsl.instances.result
import io.casperlabs.storage.BlockMsgWithTransform
import monix.eval.Task
import org.scalatest.{FlatSpec, Matchers}

import scala.collection.immutable.HashMap

// See [[/docs/casper/images/no_finalizable_block_mistake_with_no_disagreement_check.png]]
@silent("(deprecated)|(never used)")
class BlocksResponseAPITest extends FlatSpec with Matchers with BlockGenerator with StorageFixture {

  val v1     = generateValidator("V1")
  val v2     = generateValidator("V2")
  val v3     = generateValidator("V3")
  val v1Bond = Bond(v1, 25)
  val v2Bond = Bond(v2, 20)
  val v3Bond = Bond(v3, 15)
  val bonds  = Seq(v1Bond, v2Bond, v3Bond)

  "showBlocks" should "return all blocks" in withCombinedStorage() { implicit storage =>
    for {
      genesis <- createAndStoreMessage[Task](Seq(), EmptyValidator, bonds)
      b2 <- createAndStoreMessage[Task](
             Seq(genesis.blockHash),
             v2,
             bonds,
             HashMap(v1 -> genesis.blockHash, v2 -> genesis.blockHash, v3 -> genesis.blockHash)
           )
      b3 <- createAndStoreMessage[Task](
             Seq(genesis.blockHash),
             v1,
             bonds,
             HashMap(v1 -> genesis.blockHash, v2 -> genesis.blockHash, v3 -> genesis.blockHash)
           )
      b4 <- createAndStoreMessage[Task](
             Seq(b2.blockHash),
             v3,
             bonds,
             HashMap(v1 -> genesis.blockHash, v2 -> b2.blockHash, v3 -> b2.blockHash)
           )
      b5 <- createAndStoreMessage[Task](
             Seq(b3.blockHash),
             v2,
             bonds,
             HashMap(v1 -> b3.blockHash, v2 -> b2.blockHash, v3 -> genesis.blockHash)
           )
      b6 <- createAndStoreMessage[Task](
             Seq(b4.blockHash),
             v1,
             bonds,
             HashMap(v1 -> b3.blockHash, v2 -> b2.blockHash, v3 -> b4.blockHash)
           )
      _ <- createAndStoreMessage[Task](
            Seq(b5.blockHash),
            v3,
            bonds,
            HashMap(v1 -> b3.blockHash, v2 -> b5.blockHash, v3 -> b4.blockHash)
          )
      _ <- createAndStoreMessage[Task](
            Seq(b6.blockHash),
            v2,
            bonds,
            HashMap(v1 -> b6.blockHash, v2 -> b5.blockHash, v3 -> b4.blockHash)
          )
      dag                 <- storage.getRepresentation
      latestMessageHashes <- dag.latestMessageHashes
      equivocators        <- dag.getEquivocators
      tips <- Estimator.tips[Task](
               dag,
               genesis.blockHash,
               latestMessageHashes,
               equivocators
             )
      casperEffect <- NoOpsCasperEffect[Task](
                       HashMap.empty[BlockHash, BlockMsgWithTransform],
                       tips
                     )(Sync[Task], storage, storage)
      implicit0(casperRef: MultiParentCasperRef[Task]) <- MultiParentCasperRef.of[Task]
      _                                                <- casperRef.set(casperEffect)
      blocksResponse <- BlockAPI.getBlockInfos[Task](Int.MaxValue)(
                         MonadThrowable[Task],
                         log,
                         storage,
                         storage,
                         implicitly[Fs2Compiler[Task]]
                       )
    } yield blocksResponse.length should be(8) // TODO: Switch to 4 when we implement block height correctly
  }

  it should "return until depth" in withCombinedStorage() { implicit storage =>
    /**
      * The Dag looks like
      *
      *
      *  4          b8
      *           /
      *  3     b6 ----   b7
      *                x
      *  2          b5  b4
      *           /    /
      *  1     b3   b2
      *         \   |
      *  0       genesis
      *
      */
    for {
      genesis <- createAndStoreMessage[Task](Seq(), EmptyValidator, bonds)
      b2 <- createAndStoreMessage[Task](
             Seq(genesis.blockHash),
             v2,
             bonds,
             HashMap(v1 -> genesis.blockHash, v2 -> genesis.blockHash, v3 -> genesis.blockHash)
           )
      b3 <- createAndStoreMessage[Task](
             Seq(genesis.blockHash),
             v1,
             bonds,
             HashMap(v1 -> genesis.blockHash, v2 -> genesis.blockHash, v3 -> genesis.blockHash)
           )
      b4 <- createAndStoreMessage[Task](
             Seq(b2.blockHash),
             v3,
             bonds,
             HashMap(v1 -> genesis.blockHash, v2 -> b2.blockHash, v3 -> b2.blockHash)
           )
      b5 <- createAndStoreMessage[Task](
             Seq(b3.blockHash),
             v2,
             bonds,
             HashMap(v1 -> b3.blockHash, v2 -> b2.blockHash, v3 -> genesis.blockHash)
           )
      b6 <- createAndStoreMessage[Task](
             Seq(b4.blockHash),
             v1,
             bonds,
             HashMap(v1 -> b3.blockHash, v2 -> b2.blockHash, v3 -> b4.blockHash)
           )
      b7 <- createAndStoreMessage[Task](
             Seq(b5.blockHash),
             v3,
             bonds,
             HashMap(v1 -> b3.blockHash, v2 -> b5.blockHash, v3 -> b4.blockHash)
           )
      b8 <- createAndStoreMessage[Task](
             Seq(b6.blockHash),
             v2,
             bonds,
             HashMap(v1 -> b6.blockHash, v2 -> b5.blockHash, v3 -> b4.blockHash)
           )
      dag                  <- storage.getRepresentation
      latestMessagesHashes <- dag.latestMessageHashes
      equivocators         <- dag.getEquivocators
      tips <- Estimator
               .tips[Task](
                 dag,
                 genesis.blockHash,
                 latestMessagesHashes,
                 equivocators
               )
      casperEffect <- NoOpsCasperEffect[Task](
                       HashMap.empty[BlockHash, BlockMsgWithTransform],
                       tips
                     )(Sync[Task], storage, storage)
      implicit0(casperRef: MultiParentCasperRef[Task]) <- MultiParentCasperRef.of[Task]
      _                                                <- casperRef.set(casperEffect)

      // TODO: Remove once we get rid of IndexedDagStorage
      localGetBlockInfos = (depth: Int) =>
        BlockAPI.getBlockInfos[Task](depth)(
          MonadThrowable[Task],
          log,
          storage,
          storage,
          implicitly[Fs2Compiler[Task]]
        )
      blocksWithRankBelow1 <- localGetBlockInfos(1)
      _                    = blocksWithRankBelow1.length shouldBe 1
      blocksWithRankBelow2 <- localGetBlockInfos(2)
      _                    = blocksWithRankBelow2.length shouldBe 3
      blocksWithRankBelow3 <- localGetBlockInfos(3)
      _                    = blocksWithRankBelow3.length shouldBe 5
      blocksWithRankBelow4 <- localGetBlockInfos(4)
      _                    = blocksWithRankBelow4.length shouldBe 7
      blocksWithRankBelow5 <- localGetBlockInfos(5)
      result               = blocksWithRankBelow5.length shouldBe 8
    } yield result
  }
}
