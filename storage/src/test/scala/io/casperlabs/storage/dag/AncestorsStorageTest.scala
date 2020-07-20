package io.casperlabs.storage.dag

import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.compatible.Assertion
import monix.eval.Task
import monix.execution.Scheduler
import cats.effect.laws.discipline.arbitrary
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.Block
import io.casperlabs.casper.consensus.Signature
import io.casperlabs.storage.{ArbitraryStorageData, SQLiteFixture}
import io.casperlabs.storage.SQLiteStorage
import io.casperlabs.storage.block.BlockStorage
import io.casperlabs.storage.BlockMsgWithTransform

class AncestorsStorageTest
    extends FlatSpec
    with Matchers
    with ArbitraryStorageData
    with SQLiteFixture[BlockStorage[Task] with AncestorsStorage[Task]] {

  override def db: String = "/tmp/message_ancestors_test.db"

  override def createTestResource: Task[BlockStorage[Task] with AncestorsStorage[Task]] =
    SQLiteStorage.create[Task](readXa = xa, writeXa = xa)

  implicit val consensusConfig: ConsensusConfig = ConsensusConfig(
    dagSize = 5,
    dagDepth = 3,
    dagBranchingFactor = 1,
    maxSessionCodeBytes = 1,
    maxPaymentCodeBytes = 1,
    minSessionCodeBytes = 1,
    minPaymentCodeBytes = 1
  )

  def randomMessage: Block =
    sample(arbBlock.arbitrary)

  def createGenesis: Block = {
    val b = randomMessage
    b.withHeader(
        b.getHeader
          .withParentHashes(Seq.empty)
          .withValidatorPublicKeyHash(ByteString.EMPTY)
      )
      .clearSignature
  }

  implicit class BlockOps(b: Block) {
    def withMainParent(block: Block): Block =
      b.withHeader(
        b.getHeader
          .withParentHashes(Seq(block.blockHash))
          .withMainRank(block.getHeader.mainRank + 1)
      )
  }

  behavior of "collectMessageAncestors"

  it should "return an empty list of ancestors for Genesis" in {
    val genesis = createGenesis

    runSQLiteTest {
      case (storage: BlockStorage[Task] with AncestorsStorage[Task]) =>
        for {
          _         <- storage.put(genesis.blockHash, BlockMsgWithTransform().withBlockMessage(genesis))
          ancestors <- storage.collectMessageAncestors(genesis)
        } yield assert(ancestors.isEmpty)
    }
  }

  it should "return correct ancestors at power of 2 heights from the block" in {
    // g - A - B - C - D - E - F - G - H | block
    // 0   1   2   3   4   5   6   7   8 | main rank
    // 3               2       1   0   * | power of 2 ancestors of `H`
    // 2       1   0   *                 | power of 2 ancestors of `D`
    //     1   0   *                     | power of 2 ancestors of `C`
    val genesis = createGenesis
    implicit def `Block => BlockMsgWithTransforms`(b: Block): BlockMsgWithTransform =
      BlockMsgWithTransform().withBlockMessage(b)

    runSQLiteTest {
      case (storage: BlockStorage[Task] with AncestorsStorage[Task]) =>
        for {
          _ <- storage.put(genesis.blockHash, genesis)
          a = randomMessage.withMainParent(genesis)
          b = randomMessage.withMainParent(a)
          c = randomMessage.withMainParent(b)
          d = randomMessage.withMainParent(c)
          e = randomMessage.withMainParent(d)
          f = randomMessage.withMainParent(e)
          g = randomMessage.withMainParent(f)
          h = randomMessage.withMainParent(g)
          _ <- storage.put(a.blockHash, a)
          _ <- storage.put(b.blockHash, b)
          _ <- storage.put(c.blockHash, c)
          _ <- storage.put(d.blockHash, d)
          _ <- storage.put(e.blockHash, e)
          _ <- storage.put(f.blockHash, f)
          _ <- storage.put(g.blockHash, g)
          _ <- storage.put(h.blockHash, h)
          _ <- storage
                .collectMessageAncestors(h)
                .map(
                  _ shouldBe List(
                    1 -> g.blockHash,
                    2 -> f.blockHash,
                    4 -> d.blockHash,
                    8 -> genesis.blockHash
                  ).reverse
                )
          _ <- storage
                .collectMessageAncestors(d)
                .map(
                  _ shouldBe List(
                    1 -> c.blockHash,
                    2 -> b.blockHash,
                    4 -> genesis.blockHash
                  ).reverse
                )
          _ <- storage
                .collectMessageAncestors(c)
                .map(
                  _ shouldBe List(
                    1 -> b.blockHash,
                    2 -> a.blockHash
                  ).reverse
                )
        } yield ()
    }
  }
}
