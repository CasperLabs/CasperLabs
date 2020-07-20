package io.casperlabs.casper.equivocations

import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.casper._
import io.casperlabs.casper.consensus.Block
import io.casperlabs.casper.helper.BlockUtil.generateValidator
import io.casperlabs.casper.helper.{BlockGenerator, StorageFixture}
import io.casperlabs.casper.scalatestcontrib._
import io.casperlabs.casper.util.ProtoUtil
import io.casperlabs.casper.validation.Errors.ValidateErrorWrapper
import io.casperlabs.models.Message
import io.casperlabs.shared.LogStub
import io.casperlabs.storage.block.BlockStorage
import io.casperlabs.storage.dag.DagStorage
import monix.eval.Task
import org.scalatest.{FlatSpec, Matchers}
import logstage.LogIO

import scala.collection.immutable.HashMap

class EquivocationDetectorTest
    extends FlatSpec
    with Matchers
    with BlockGenerator
    with StorageFixture {

  implicit val raiseValidateErr = validation.raiseValidateErrorThroughApplicativeError[Task]
  // Necessary because errors are returned via Sync which has an error type fixed to _ <: Throwable.
  // When raise errors we wrap them with Throwable so we need to do the same here.
  implicit def wrapWithThrowable[A <: InvalidBlock](err: A): Throwable =
    ValidateErrorWrapper(err)

  def createMessageAndTestEquivocateDetector(
      parentsHashList: Seq[BlockHash],
      lfb: Block,
      creator: Validator = EmptyValidator,
      justifications: collection.Map[Validator, BlockHash] = HashMap.empty[Validator, BlockHash],
      rankOfLowestBaseBlockExpect: Option[Long],
      messageType: Block.MessageType = Block.MessageType.BLOCK,
      isHighway: Boolean = false
  )(
      implicit dagStorage: DagStorage[Task],
      blockStorage: BlockStorage[Task],
      log: LogStub with LogIO[Task] = LogStub[Task]()
  ): Task[Block] =
    for {
      dag <- dagStorage.getRepresentation
      b <- createMessage[Task](
            parentsHashList,
            keyBlockHash = lfb.blockHash,
            creator,
            justifications = justifications,
            messageType = messageType
          )
      message <- Task.fromTry(Message.fromBlock(b))
      blockStatus <- EquivocationDetector
                      .checkEquivocation(dag, message, isHighway)
                      .attempt

      _ = rankOfLowestBaseBlockExpect match {
        case None =>
          blockStatus shouldBe Right(())
        case Some(_) =>
          blockStatus shouldBe Left(ValidateErrorWrapper(EquivocatedBlock))
      }
      _ <- blockStorage.put(b.blockHash, b, Map.empty)
      latestMessages <- if (isHighway)
                         dag.latestInEra(b.getHeader.keyBlockHash).flatMap(_.latestMessage(creator))
                       else
                         dag.latestMessage(creator)
      rankOfLowestBaseBlock = EquivocationDetector.findMinBaseRank(Map(creator -> latestMessages))
      _                     = rankOfLowestBaseBlock shouldBe rankOfLowestBaseBlockExpect
    } yield b

  def createBlockAndCheckEquivocatorsFromViewOfBlock(
      parentsHashList: Seq[BlockHash],
      lfb: Block,
      creator: Validator = EmptyValidator,
      justifications: collection.Map[Validator, BlockHash] = HashMap.empty[Validator, BlockHash],
      rankOfLowestBaseBlockExpect: Option[Long],
      visibleEquivocatorExpected: Set[Validator]
  )(
      implicit dagStorage: DagStorage[Task],
      blockStorage: BlockStorage[Task],
      log: LogStub with LogIO[Task]
  ): Task[Block] =
    for {
      block <- createMessageAndTestEquivocateDetector(
                parentsHashList,
                lfb,
                creator,
                justifications,
                rankOfLowestBaseBlockExpect
              )
      dag            <- dagStorage.getRepresentation
      latestMessages <- ProtoUtil.getJustificationMsgs[Task](dag, block.getHeader.justifications)
      _ <- EquivocationDetector.detectVisibleFromJustifications(
            dag,
            latestMessages.mapValues(_.map(_.messageHash))
          ) shouldBeF visibleEquivocatorExpected
    } yield block

  def simpleEquivocation(leftMessageType: Block.MessageType, rightMessageType: Block.MessageType) =
    withCombinedStorage() { implicit storage =>
      /*
       * The Dag looks like
       *
       *     |      v0     |
       *     |             |
       *     |             |
       *     |    b2   b3  |
       *     |     \  /    |
       *     |      b1     |
       *             \
       *               genesis
       *
       */
      implicit val logEff = LogStub[Task]()
      val v0              = generateValidator("V0")

      for {
        genesis <- createAndStoreMessage[Task](Seq(), EmptyValidator)
        b1 <- createMessageAndTestEquivocateDetector(
               Seq(genesis.blockHash),
               genesis,
               v0,
               rankOfLowestBaseBlockExpect = None
             )
        _ <- createMessageAndTestEquivocateDetector(
              Seq(b1.blockHash),
              genesis,
              v0,
              justifications = HashMap(v0 -> b1.blockHash),
              rankOfLowestBaseBlockExpect = None,
              messageType = leftMessageType
            )
        _ <- createMessageAndTestEquivocateDetector(
              Seq(b1.blockHash),
              genesis,
              v0,
              justifications = HashMap(v0 -> b1.blockHash),
              rankOfLowestBaseBlockExpect = b1.getHeader.jRank.some,
              messageType = rightMessageType
            )
      } yield ()
    }

  behavior of "EquivocationDetector"

  it should "detect simple equivocation with blocks" in simpleEquivocation(
    Block.MessageType.BLOCK,
    Block.MessageType.BLOCK
  )

  it should "detect simple equivocation with ballots" in simpleEquivocation(
    Block.MessageType.BALLOT,
    Block.MessageType.BALLOT
  )

  it should "detect simple equivocation with block and ballot" in simpleEquivocation(
    Block.MessageType.BLOCK,
    Block.MessageType.BALLOT
  )

  it should "detect equivocation in the current era" in withCombinedStorage() { implicit db =>
    val v1 = generateValidator("v1")
    // era-0: G - B0
    //          \
    //            B1
    for {
      g <- createAndStoreMessage[Task](Nil)
      _ <- createAndStoreEra[Task](g.blockHash)
      _ <- createMessageAndTestEquivocateDetector(
            List(g.blockHash),
            g,
            v1,
            rankOfLowestBaseBlockExpect = None,
            isHighway = true
          )
      _ <- createMessageAndTestEquivocateDetector(
            List(g.blockHash),
            g,
            v1,
            rankOfLowestBaseBlockExpect = g.getHeader.jRank.some,
            isHighway = true
          )
    } yield ()
  }

  it should "not detect parent era ballots as equivocations" in withCombinedStorage() {
    implicit db =>
      val v1 = generateValidator("v1")
      // era-0: G - B0 - b2
      //               \
      // era-1:         B1
      for {
        g <- createAndStoreMessage[Task](Nil)
        _ <- createAndStoreEra[Task](g.blockHash)
        b0 <- createMessageAndTestEquivocateDetector(
               List(g.blockHash),
               g,
               v1,
               rankOfLowestBaseBlockExpect = None,
               isHighway = true
             )
        _ <- createMessageAndTestEquivocateDetector(
              List(b0.blockHash),
              g,
              v1,
              justifications = Map(v1 -> b0.blockHash),
              rankOfLowestBaseBlockExpect = None,
              isHighway = true
            )
        _ <- createMessageAndTestEquivocateDetector(
              List(b0.blockHash),
              b0,
              v1,
              justifications = Map.empty,
              rankOfLowestBaseBlockExpect = None,
              isHighway = true
            )
      } yield ()
  }

  it should "not detect parent era equivocations in the child era" in withCombinedStorage() {
    implicit db =>
      val v1 = generateValidator("v1")
      // era-0: G - B0 - B1
      //               \
      //                 B2
      //                   \
      // era-1:             B3
      for {
        g <- createAndStoreMessage[Task](Nil)
        _ <- createAndStoreEra[Task](g.blockHash)
        b0 <- createMessageAndTestEquivocateDetector(
               List(g.blockHash),
               g,
               v1,
               rankOfLowestBaseBlockExpect = None,
               isHighway = true
             )
        _ <- createMessageAndTestEquivocateDetector(
              List(b0.blockHash),
              g,
              v1,
              justifications = Map(v1 -> b0.blockHash),
              rankOfLowestBaseBlockExpect = None,
              isHighway = true
            )
        b2 <- createMessageAndTestEquivocateDetector(
               List(b0.blockHash),
               g,
               v1,
               justifications = Map(v1 -> b0.blockHash),
               rankOfLowestBaseBlockExpect = Some(b0.getHeader.jRank),
               isHighway = true
             )
        _ <- createMessageAndTestEquivocateDetector(
              List(b2.blockHash),
              b0,
              v1,
              justifications = Map.empty,
              rankOfLowestBaseBlockExpect = None,
              isHighway = true
            )
      } yield ()
  }

  it should "not report equivocation when references a message creating an equivocation that was created by other validator" in withCombinedStorage() {
    implicit storage =>
      /*
       * The Dag looks like
       *
       *    v0    |    v1     |
       *          |           |
       *          |    b3     |
       *          |  /        |
       *         /|           |
       *       /  |           |
       * b1   b2  |           |
       *    \  \  |           |
       *       genesis
       *
       */
      implicit val logEff = LogStub[Task]()
      val v0              = generateValidator("V0")
      val v1              = generateValidator("V1")
      for {
        genesis <- createAndStoreMessage[Task](Seq(), EmptyValidator)
        _ <- createMessageAndTestEquivocateDetector(
              Seq(genesis.blockHash),
              genesis,
              v0,
              rankOfLowestBaseBlockExpect = None
            )
        b2 <- createMessageAndTestEquivocateDetector(
               Seq(genesis.blockHash),
               genesis,
               v0,
               rankOfLowestBaseBlockExpect = Some(0)
             )
        _ <- createMessageAndTestEquivocateDetector(
              Seq(b2.blockHash),
              genesis,
              v1,
              justifications = HashMap(v0 -> b2.blockHash),
              rankOfLowestBaseBlockExpect = None
            )
      } yield ()
  }

  it should "not report equivocation when block indirectly references previous creator's block" in withCombinedStorage() {
    implicit storage =>
      /*
       * The Dag looks like
       *
       *    v0    |      v1     |
       *          |             |
       *          |      b5     |
       *          |  /     \    |
       *         /|         |   |
       *       /  |         |   |
       *    b4    |         |   |
       *     |    |         |   |
       *    b3    |         |   |
       *       \  |         |   |
       *         \|----b2   |   |
       *          |     \   /   |
       *          |      b1     |
       *                  \
       *                    genesis
       *
       */
      implicit val logEff = LogStub[Task]()
      val v0              = generateValidator("V0")
      val v1              = generateValidator("V1")
      for {
        genesis <- createAndStoreMessage[Task](Seq(), EmptyValidator)
        b1 <- createMessageAndTestEquivocateDetector(
               Seq(genesis.blockHash),
               genesis,
               v1,
               rankOfLowestBaseBlockExpect = None
             )
        b2 <- createMessageAndTestEquivocateDetector(
               Seq(b1.blockHash),
               genesis,
               v1,
               justifications = HashMap(v1 -> b1.blockHash),
               rankOfLowestBaseBlockExpect = None
             )
        b3 <- createMessageAndTestEquivocateDetector(
               Seq(b2.blockHash),
               genesis,
               v0,
               justifications = HashMap(v1 -> b2.blockHash),
               rankOfLowestBaseBlockExpect = None
             )
        b4 <- createMessageAndTestEquivocateDetector(
               Seq(b3.blockHash),
               genesis,
               v0,
               justifications = HashMap(v0 -> b3.blockHash),
               rankOfLowestBaseBlockExpect = None
             )
        _ <- createMessageAndTestEquivocateDetector(
              Seq(b4.blockHash),
              genesis,
              v1,
              justifications = HashMap(v0 -> b4.blockHash, v1 -> b1.blockHash),
              rankOfLowestBaseBlockExpect = None
            )
      } yield ()
  }

  it should "should detect equivocation when receiving a block created by a validator who has been detected equivocating" in withCombinedStorage() {
    implicit storage =>
      /*
       * The Dag looks like
       *
       *          |      v0     |
       *          |             |
       *          |             |
       *          |         b4  |
       *          |         |   |
       *          |    b2   b3  |
       *          |     \   /   |
       *          |      b1     |
       *                  \
       *                    genesis
       *
       */

      implicit val logEff = LogStub[Task]()
      val v0              = generateValidator("V0")

      for {
        genesis <- createAndStoreMessage[Task](Seq(), EmptyValidator)
        b1 <- createMessageAndTestEquivocateDetector(
               Seq(genesis.blockHash),
               genesis,
               v0,
               justifications = HashMap(v0 -> genesis.blockHash),
               rankOfLowestBaseBlockExpect = None
             )
        _ <- createMessageAndTestEquivocateDetector(
              Seq(b1.blockHash),
              genesis,
              v0,
              justifications = HashMap(v0 -> b1.blockHash),
              rankOfLowestBaseBlockExpect = None
            )
        b3 <- createMessageAndTestEquivocateDetector(
               Seq(b1.blockHash),
               genesis,
               v0,
               justifications = HashMap(v0 -> b1.blockHash),
               rankOfLowestBaseBlockExpect = b1.getHeader.jRank.some
             )
        _ <- createMessageAndTestEquivocateDetector(
              Seq(b3.blockHash),
              genesis,
              v0,
              justifications = HashMap(v0 -> b3.blockHash),
              rankOfLowestBaseBlockExpect = b1.getHeader.jRank.some
            )
      } yield ()
  }

  it should "detect equivocation and update the rank of lowest base block correctly when receiving a block created by a validator who has been detected equivocating" in withCombinedStorage() {
    implicit storage =>
      /*
       * The Dag looks like
       *
       *          |      v0     |
       *          |             |
       *          |             |
       *          |             |
       *          |             |
       *          |    b3   b4  |
       *          |     \   /   |
       *          |      b2  b6 |
       *          |      |  /   |
       *          |      b1     |
       *                  \
       *                    genesis
       *
       */

      implicit val logEff = LogStub[Task]()
      val v0              = generateValidator("V0")

      for {
        genesis <- createAndStoreMessage[Task](Seq(), EmptyValidator)
        b1 <- createMessageAndTestEquivocateDetector(
               Seq(genesis.blockHash),
               genesis,
               v0,
               justifications = HashMap(v0 -> genesis.blockHash),
               rankOfLowestBaseBlockExpect = None
             )
        b2 <- createMessageAndTestEquivocateDetector(
               Seq(b1.blockHash),
               genesis,
               v0,
               justifications = HashMap(v0 -> b1.blockHash),
               rankOfLowestBaseBlockExpect = None
             )
        _ <- createMessageAndTestEquivocateDetector(
              Seq(b2.blockHash),
              genesis,
              v0,
              justifications = HashMap(v0 -> b2.blockHash),
              rankOfLowestBaseBlockExpect = None
            )
        // When v0 creates first equivocation, then the rank of lowest base block is the rank of b2
        _ <- createMessageAndTestEquivocateDetector(
              Seq(b2.blockHash),
              genesis,
              v0,
              justifications = HashMap(v0 -> b2.blockHash),
              rankOfLowestBaseBlockExpect = b2.getHeader.jRank.some
            )
        // When v0 creates another equivocation, and the base block(i.e. block b1) of the
        // equivocation is smaller, then update the rank of lowest base block to be the rank of b1
        _ <- createMessageAndTestEquivocateDetector(
              Seq(b1.blockHash),
              genesis,
              v0,
              justifications = HashMap(v0 -> b1.blockHash),
              rankOfLowestBaseBlockExpect = b1.getHeader.jRank.some
            )
      } yield ()
  }

  // See [[casper/src/test/resources/casper/tipsHavingEquivocations.png]]
  "detectVisibleFromJustificationMsgHashes" should "find validators who has equivocated from the j-past-cone of block's justifications" in withCombinedStorage() {
    implicit storage =>
      implicit val logEff = LogStub[Task]()
      val v1              = generateValidator("V1")
      val v2              = generateValidator("V2")

      for {
        genesis <- createAndStoreMessage[Task](Seq(), EmptyValidator)
        a1 <- createBlockAndCheckEquivocatorsFromViewOfBlock(
               Seq(genesis.blockHash),
               genesis,
               v1,
               justifications = HashMap(v1 -> genesis.blockHash),
               rankOfLowestBaseBlockExpect = None,
               visibleEquivocatorExpected = Set.empty
             )
        a2 <- createBlockAndCheckEquivocatorsFromViewOfBlock(
               Seq(genesis.blockHash),
               genesis,
               v1,
               justifications = HashMap(v1 -> genesis.blockHash),
               rankOfLowestBaseBlockExpect = 0L.some,
               visibleEquivocatorExpected = Set.empty
             )
        b <- createBlockAndCheckEquivocatorsFromViewOfBlock(
              Seq(a2.blockHash),
              genesis,
              v2,
              justifications = HashMap(v1 -> a2.blockHash, v2 -> genesis.blockHash),
              rankOfLowestBaseBlockExpect = None,
              visibleEquivocatorExpected = Set.empty
            )

        c <- createBlockAndCheckEquivocatorsFromViewOfBlock(
              Seq(b.blockHash),
              genesis,
              v1,
              justifications = HashMap(v1 -> a1.blockHash, v2 -> b.blockHash),
              rankOfLowestBaseBlockExpect = 0L.some,
              visibleEquivocatorExpected = Set(v1)
            )

        // this block isn't shown in the diagram
        _ <- createBlockAndCheckEquivocatorsFromViewOfBlock(
              Seq(c.blockHash),
              genesis,
              v1,
              justifications = HashMap(v1 -> c.blockHash, v2 -> b.blockHash),
              rankOfLowestBaseBlockExpect = 0L.some,
              visibleEquivocatorExpected = Set(v1)
            )
      } yield ()
  }
}
