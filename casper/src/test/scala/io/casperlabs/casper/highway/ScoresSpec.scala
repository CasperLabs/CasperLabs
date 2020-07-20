package io.casperlabs.casper.highway

import org.scalatest.{FlatSpec, Matchers}
import io.casperlabs.casper.highway.ForkChoice.Scores
import com.google.protobuf.ByteString
import io.casperlabs.models.{Message, Weight}
import io.casperlabs.crypto.Keys
import io.casperlabs.storage.BlockHash
import io.casperlabs.casper.consensus.{BlockSummary, Signature}
import io.casperlabs.casper.highway.mocks.MockBlockDagStorage
import io.casperlabs.casper.consensus.Block
import monix.eval.Task
import io.casperlabs.storage.dag.DagLookup
import org.scalatest.compatible.Assertion
import io.casperlabs.catscontrib.TaskContrib._
import monix.execution.Scheduler.Implicits.global
import io.casperlabs.casper.PrettyPrinter
import org.scalactic.Prettifier
import io.casperlabs.shared.Sorting._

class ScoresSpec extends FlatSpec with Matchers {

  behavior of "Scores map"

  def newVote(rank: Int, parent: BlockHash): Message.Block =
    Message.Block(
      randomBlockHash,
      randomValidator,
      1L,
      1L,
      randomBlockHash,
      parent,
      Seq.empty,
      Message.asJRank(rank.toLong),
      Message.asMainRank(rank.toLong),
      1,
      Signature(),
      BlockSummary(),
      randomBlockHash,
      Block.MessageRole.PROPOSAL
    )

  def randomBlockHash = ByteString.copyFromUtf8(scala.util.Random.nextString(20))
  def randomValidator =
    Keys.PublicKeyHash(ByteString.copyFromUtf8(scala.util.Random.nextString(20)))

  implicit def `Int => Weight`(intWeight: Int): Weight = BigInt(intWeight)

  it should "insert new vote" in {
    val vote    = newVote(1, randomBlockHash)
    val weight  = 10
    val updated = Scores.init(vote).update(vote, weight)
    assert(updated.votesAtHeight(vote.jRank) == Map(vote.messageHash -> BigInt(weight)))
  }

  it should "update a vote" in {
    val vote    = newVote(1, randomBlockHash)
    val weightA = 10
    val weightB = 20
    val updated = Scores.init(vote).update(vote, weightA).update(vote, weightB)
    assert(updated.votesAtHeight(vote.jRank) == Map(vote.messageHash -> BigInt(weightA + weightB)))
  }

  it should "return weight of all votes" in {
    val scores: List[Weight] = List.fill(20)(scala.util.Random.nextInt(1000))
    val flatScoresMap = Scores(
      Map(
        1L -> scores.map(w => randomBlockHash -> w).toMap
      ),
      0
    )
    assert(flatScoresMap.totalWeight == scores.sum)

    val nestedScoresMap = Scores(
      scores.indices
        .map(level => level.toLong -> Map(randomBlockHash -> scores(level)))
        .toMap,
      0
    )
    assert(nestedScoresMap.totalWeight == scores.sum)
  }

  def createBlock(validator: Keys.PublicKeyHashBS, parentHash: ByteString, mainRank: Int): Block =
    Block()
      .withBlockHash(randomBlockHash)
      .update(
        _.header := Block
          .Header()
          .withValidatorPublicKeyHash(validator)
          .withParentHashes(Seq(parentHash))
          .withMainRank(mainRank.toLong)
      )

  val validatorA = randomValidator
  val validatorB = randomValidator
  val validatorC = randomValidator

  implicit val byteStringPrettifier = Prettifier {
    case bs: ByteString => PrettyPrinter.buildString(bs)
    case other          => Prettifier.default(other)
  }

  class TipFixture(blocks: List[Block]) {
    def assertTip(
        weights: Map[Keys.PublicKeyHashBS, Int],
        startBlock: Block,
        expectedTip: Message.Block
    ): Assertion = {
      val test = for {
        blockStore                      <- MockBlockDagStorage[Task](startBlock +: blocks: _*)
        implicit0(dag: DagLookup[Task]) <- blockStore.getRepresentation
        messageBlocks                   = blocks.map(Message.fromBlock(_).get.asInstanceOf[Message.Block])
        latestVotes                     = messageBlocks.groupBy(_.validatorId).mapValues(_.maxBy(_.mainRank)).values
        scoresMap = latestVotes.foldLeft(Scores.init(startBlock)) {
          case (scores, block) => scores.update(block, weights(block.validatorId))
        }
        tip <- scoresMap.tip[Task]
      } yield assert(tip.messageHash == expectedTip.messageHash)

      test.unsafeRunSync
    }

  }

  implicit def `proto.Block => Message.Block`(in: Block): Message.Block =
    Message.fromBlock(in).get.asInstanceOf[Message.Block]

  // When there's no one with majority of votes we pick one with the highest stake.
  // If we still get a tie then we use block hash as tie breaker.
  import io.casperlabs.casper.dag.DagOperations.bigIntByteStringOrdering
  def tieBreaker(in: List[Message.Block], weights: Map[Keys.PublicKeyHashBS, Int]): Message.Block =
    in.maxBy(b => BigInt(weights(b.validatorId)) -> b.messageHash)(bigIntByteStringOrdering)

  it should "return the tip for flat DAG" in {
    //    B1
    //   /
    // A1-A2
    //   \
    //    C1
    // All votes are on the same level in the scores map

    val a1 = createBlock(validatorA, randomBlockHash, 1)
    val a2 = createBlock(validatorA, a1.blockHash, 2)
    val b1 = createBlock(validatorB, a1.blockHash, 2)
    val c1 = createBlock(validatorC, a1.blockHash, 2)

    val blocks = List(a2, b1, c1)

    val weights = Map(
      validatorA -> 7,
      validatorB -> 5,
      validatorC -> 3
    )

    val fixture = new TipFixture(blocks)
    fixture.assertTip(weights, a1, a2)
  }

  it should "return tip for the blockchain" in {
    // a1 <- a2 <- b1 <- c1
    val a1     = createBlock(validatorA, randomBlockHash, 1)
    val a2     = createBlock(validatorA, a1.blockHash, 2)
    val b1     = createBlock(validatorB, a2.blockHash, 3)
    val c1     = createBlock(validatorC, b1.blockHash, 4)
    val blocks = List(a2, b1, c1)

    val weightsA = Map(
      validatorA -> 7,
      validatorB -> 3,
      validatorC -> 3
    )

    val fixtureA = new TipFixture(blocks)
    fixtureA.assertTip(weightsA, a1, a2)

    // This weight map will have majority of votes at block b1
    val weightsB = Map(
      validatorA -> 10,
      validatorB -> 20,
      validatorC -> 10
    )

    val fixtureB = new TipFixture(blocks)
    fixtureB.assertTip(weightsB, a1, b1)

    // This weight map will have majority of votes at block c1
    val weightsC = Map(
      validatorA -> 10,
      validatorB -> 10,
      validatorC -> 21
    )

    val fixtureC = new TipFixture(blocks)
    fixtureC.assertTip(weightsC, a1, c1)
  }

  it should "return tip when a tie between uneven branches of the tree" in {
    //    b1
    //   /
    // a1-a2
    //   \
    //    c1-c2-c3
    // Here, weight on C's branch has to be propagated to the bottom
    // and, weights have to be accumulated across all branches.

    val a1 = createBlock(validatorA, randomBlockHash, 1)
    val a2 = createBlock(validatorA, a1.blockHash, 2)
    val b1 = createBlock(validatorB, a1.blockHash, 2)
    val c1 = createBlock(validatorC, a1.blockHash, 2)
    val c2 = createBlock(validatorC, c1.blockHash, 3)
    val c3 = createBlock(validatorC, c2.blockHash, 4)

    val blocks = List(a1, a2, b1, c1, c2, c3)

    // No validator on its own has majority of votes.
    val weights = Map(
      validatorA -> 10,
      validatorB -> 10,
      validatorC -> 10
    )

    val expectedTip = tieBreaker(List(b1, a2, c1), weights)

    val fixture = new TipFixture(blocks)
    fixture.assertTip(weights, a1, expectedTip)
  }

  it should "return correct tip as soon as it has majority of the votes" in {
    //    b1
    //   /
    // a1-a2      a3
    //   \        /
    //    c1-c2-c3 (has 2/3 of the votes)

    val weights = Map(
      validatorA -> 10,
      validatorB -> 10,
      validatorC -> 10
    )

    val a1 = createBlock(validatorA, randomBlockHash, 1)
    val a2 = createBlock(validatorA, a1.blockHash, 2)
    val b1 = createBlock(validatorB, a1.blockHash, 2)
    val c1 = createBlock(validatorC, a1.blockHash, 2)
    val c2 = createBlock(validatorC, c1.blockHash, 3)
    val c3 = createBlock(validatorC, c2.blockHash, 4)
    val a3 = createBlock(validatorA, c3.blockHash, 5)

    val blocks = List(a2, b1, c1, c2, c3, a3)

    val fixture = new TipFixture(blocks)
    fixture.assertTip(weights, a1, c3)
  }

  it should "return correct tip when there's a tie" in {
    //    a2-a3
    //   /
    // a1
    //   \
    //    b1-b2

    val a1 = createBlock(validatorA, randomBlockHash, 1)
    val a2 = createBlock(validatorA, a1.blockHash, 2)
    val a3 = createBlock(validatorA, a2.blockHash, 3)
    val b1 = createBlock(validatorB, a1.blockHash, 2)
    val b2 = createBlock(validatorB, b1.blockHash, 3)

    val blocks = List(a1, a2, a3, b1, b2)

    // No validator on its own has majority of votes.
    val weights = Map(
      validatorA -> 10,
      validatorB -> 10
    )

    val expectedTip = tieBreaker(List(a3, b2), weights)

    val fixture = new TipFixture(blocks)
    fixture.assertTip(weights, a1, expectedTip)
  }

  it should "return correct tip when there's a score tie between uneven branches of the tree" in {
    //   a1
    //  /
    // g
    //  \
    //   b1 - b2
    //
    // Even though there's a tie between a1 and b1
    // it doesn't matter since we start assigning weights "from the top"
    // where b2 gets 50% of all weights and is "alone" at that level.

    val g  = createBlock(validatorA, randomBlockHash, 0)
    val a1 = createBlock(validatorA, g.blockHash, 1)
    val b1 = createBlock(validatorB, g.blockHash, 1)
    val b2 = createBlock(validatorB, b1.blockHash, 2)

    val blocks = List(g, a1, b1, b2)

    // No validator on its own has majority of votes.
    val weights = Map(
      validatorA -> 10,
      validatorB -> 10
    )

    val fixture = new TipFixture(blocks)
    fixture.assertTip(weights, g, b2)
  }
}
