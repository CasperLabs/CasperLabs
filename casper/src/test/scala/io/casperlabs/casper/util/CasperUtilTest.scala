package io.casperlabs.casper.util

import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.Block
import io.casperlabs.casper.finality.FinalityDetectorUtil
import io.casperlabs.casper.helper.BlockGenerator._
import io.casperlabs.casper.helper.BlockUtil.generateValidator
import io.casperlabs.casper.helper.{BlockGenerator, StorageFixture}
import io.casperlabs.casper.scalatestcontrib._
import io.casperlabs.casper.util.BondingUtil.Bond
import io.casperlabs.casper.util.ProtoUtil._
import io.casperlabs.casper.util.execengine.ExecutionEngineServiceStub
import io.casperlabs.models.Message
import io.casperlabs.shared.LogStub
import io.casperlabs.storage.dag._
import monix.eval.Task
import org.scalatest.{Assertion, FlatSpec, Matchers}

class CasperUtilTest extends FlatSpec with Matchers with BlockGenerator with StorageFixture {

  implicit val logEff                  = LogStub[Task]()
  implicit val casperSmartContractsApi = ExecutionEngineServiceStub.noOpApi[Task]()

  /**
    * when a==b, this method doesn't work,
    * because `isInMainChain` return `true`, but votedBranch return `None`
    */
  def testVoteBranchAndIsInMainChain(
      a: Block,
      b: Block,
      result: Option[Block]
  )(implicit dag: DagRepresentation[Task], as: AncestorsStorage[Task]): Task[Unit] = {
    require(a.blockHash != b.blockHash)
    for {
      _        <- isInMainChain(dag, a.blockHash, b.blockHash) shouldBeF result.isDefined
      bMessage <- Task.fromTry(Message.fromBlock(b))
      _ <- io.casperlabs.casper.finality
            .votedBranch(dag, a.blockHash, bMessage)
            .map(_.map(_.messageHash)) shouldBeF result
            .map(_.blockHash)
    } yield ()
  }

  "isInMainChain and votedBranch" should "classify appropriately when using the same block" in withCombinedStorage() {
    implicit storage =>
      for {
        b        <- createAndStoreMessage[Task](Seq())
        dag      <- storage.getRepresentation
        _        <- isInMainChain(dag, b.blockHash, b.blockHash) shouldBeF true
        bMessage <- Task.fromTry(Message.fromBlock(b))
        result <- io.casperlabs.casper.finality
                   .votedBranch(dag, b.blockHash, bMessage) shouldBeF None
      } yield result
  }

  it should "classify appropriately" in withCombinedStorage() { implicit storage =>
    /**
      * The DAG looks like:
      *
      *     b3
      *     |
      *     b2
      *     |
      *     b1
      *
      */
    for {
      genesis <- createAndStoreMessage[Task](Seq())
      b2      <- createAndStoreMessage[Task](Seq(genesis.blockHash))
      b3      <- createAndStoreMessage[Task](Seq(b2.blockHash))

      _ <- testVoteBranchAndIsInMainChain(genesis, b2, Some(b2))
      _ <- testVoteBranchAndIsInMainChain(b2, genesis, None)
      _ <- testVoteBranchAndIsInMainChain(genesis, b3, Some(b2))
      _ <- testVoteBranchAndIsInMainChain(b3, genesis, None)
      _ <- testVoteBranchAndIsInMainChain(b2, b3, Some(b3))
      _ <- testVoteBranchAndIsInMainChain(b3, b2, None)
    } yield ()
  }

  it should "classify diamond DAGs appropriately" in withCombinedStorage() { implicit storage =>
    /**
      * The dag looks like:
      *
      *            b4
      *           // \
      *          b2  b3
      *           \  /
      *          genesis
      */
    for {
      genesis <- createAndStoreMessage[Task](Seq())
      b2      <- createAndStoreMessage[Task](Seq(genesis.blockHash))
      b3      <- createAndStoreMessage[Task](Seq(genesis.blockHash))
      b4      <- createAndStoreMessage[Task](Seq(b2.blockHash, b3.blockHash))

      implicit0(dag: DagRepresentation[Task]) <- storage.getRepresentation

      _ <- testVoteBranchAndIsInMainChain(genesis, b2, Some(b2))
      _ <- testVoteBranchAndIsInMainChain(genesis, b3, Some(b3))
      _ <- testVoteBranchAndIsInMainChain(genesis, b4, Some(b2))
      _ <- testVoteBranchAndIsInMainChain(b2, b4, Some(b4))
      _ <- testVoteBranchAndIsInMainChain(b3, b4, None)
    } yield ()
  }

  it should "classify complicated chains appropriately" in withCombinedStorage() {
    implicit storage =>
      val v1 = generateValidator("V1")
      val v2 = generateValidator("V2")

      /**
        * The dag looks like:
        *
        *           b8
        *           |
        *           b7   b6
        *             \  |
        *               \|
        *           b5   b4
        *             \  |
        *              \ |
        *           b3   b2
        *             \  /
        *            genesis
        *
        *
        */
      for {
        genesis <- createAndStoreMessage[Task](Seq(), EmptyValidator)
        b2      <- createAndStoreMessage[Task](Seq(genesis.blockHash), v2)
        b3      <- createAndStoreMessage[Task](Seq(genesis.blockHash), v1)
        b4      <- createAndStoreMessage[Task](Seq(b2.blockHash), v2)
        b5      <- createAndStoreMessage[Task](Seq(b2.blockHash), v1)
        b6      <- createAndStoreMessage[Task](Seq(b4.blockHash), v2)
        b7      <- createAndStoreMessage[Task](Seq(b4.blockHash), v1)
        b8      <- createAndStoreMessage[Task](Seq(b7.blockHash), v1)

        implicit0(dag: DagRepresentation[Task]) <- storage.getRepresentation

        _ <- testVoteBranchAndIsInMainChain(genesis, b2, Some(b2))
        _ <- testVoteBranchAndIsInMainChain(genesis, b3, Some(b3))
        _ <- testVoteBranchAndIsInMainChain(genesis, b4, Some(b2))
        _ <- testVoteBranchAndIsInMainChain(genesis, b5, Some(b2))
        _ <- testVoteBranchAndIsInMainChain(genesis, b8, Some(b2))
        _ <- testVoteBranchAndIsInMainChain(b2, b3, None)
        _ <- testVoteBranchAndIsInMainChain(b3, b4, None)
        _ <- testVoteBranchAndIsInMainChain(b4, b5, None)
        _ <- testVoteBranchAndIsInMainChain(b5, b6, None)
        _ <- testVoteBranchAndIsInMainChain(b6, b7, None)
        _ <- testVoteBranchAndIsInMainChain(b7, b8, Some(b8))
        _ <- testVoteBranchAndIsInMainChain(b2, b6, Some(b4))
        _ <- testVoteBranchAndIsInMainChain(b2, b8, Some(b4))
        _ <- testVoteBranchAndIsInMainChain(b4, b8, Some(b7))
        _ <- testVoteBranchAndIsInMainChain(b5, b8, None)
        _ <- testVoteBranchAndIsInMainChain(b4, b2, None)
      } yield ()
  }

  // See [[casper/src/test/resources/casper/panoramaForEquivocatorSwimlaneIsEmpty.png]]
  "panoramaOfBlockByValidators" should "properly return the panorama of message B" in withCombinedStorage() {
    implicit storage =>
      val v0         = generateValidator("V0")
      val v1         = generateValidator("V1")
      val v2         = generateValidator("V2")
      val v3         = generateValidator("V3")
      val v4         = generateValidator("V4")
      val validators = List(v0, v1, v2, v3, v4)

      val bonds = validators.map(v => Bond(v, 1))

      for {
        genesis        <- createAndStoreMessage[Task](Seq(), EmptyValidator)
        dag            <- storage.getRepresentation
        genesisMessage = Message.fromBlock(genesis).get

        messagePanorama = (b: Block) => {
          FinalityDetectorUtil
            .panoramaOfBlockByValidators(
              dag,
              Message.fromBlock(b).get,
              genesisMessage,
              validators.toSet
            )
            .map(_.mapValues(_.messageHash))
        }

        b1 <- createAndStoreMessage[Task](
               Seq(genesis.blockHash),
               v0,
               bonds,
               Map(v0 -> genesis.blockHash)
             )
        b2 <- createAndStoreMessage[Task](
               Seq(genesis.blockHash),
               v3,
               bonds,
               Map(v3 -> genesis.blockHash)
             )
        b3 <- createAndStoreMessage[Task](
               Seq(b1.blockHash),
               v1,
               bonds,
               Map(v0 -> b1.blockHash, v1 -> genesis.blockHash)
             )
        b4 <- createAndStoreMessage[Task](
               Seq(b1.blockHash),
               v0,
               bonds,
               Map(v0 -> b1.blockHash)
             )
        // b5 votes for b2 instead of b1
        b5 <- createAndStoreMessage[Task](
               Seq(b2.blockHash),
               v3,
               bonds,
               Map(v1 -> b3.blockHash, v3 -> b2.blockHash)
             )
        b6 <- createAndStoreMessage[Task](
               Seq(b4.blockHash),
               v2,
               bonds,
               Map(v0 -> b4.blockHash, v2 -> genesis.blockHash, v3 -> b5.blockHash)
             )
        b7 <- createAndStoreMessage[Task](
               Seq(b6.blockHash),
               v2,
               bonds,
               Map(v2 -> b6.blockHash)
             )

        panoramaDagLevel <- messagePanorama(genesis)

        _ = panoramaDagLevel shouldEqual Map()

        panoramaDagLevel1 <- messagePanorama(b1)

        _ = panoramaDagLevel1 shouldEqual Map(
          v0 -> b1.blockHash
        )

        panoramaDagLevel2 <- messagePanorama(b3)

        _ = panoramaDagLevel2 shouldEqual Map(
          v0 -> b1.blockHash,
          v1 -> b3.blockHash
        )

        panoramaDagLevel3 <- messagePanorama(b5)

        _ = panoramaDagLevel3 shouldEqual Map(
          v0 -> b1.blockHash,
          v1 -> b3.blockHash,
          v3 -> b5.blockHash
        )

        panoramaDagLevel4 <- messagePanorama(b6)

        _ = panoramaDagLevel4 shouldEqual Map(
          v0 -> b4.blockHash,
          v1 -> b3.blockHash,
          v2 -> b6.blockHash,
          v3 -> b5.blockHash
        )

        panoramaDagLevel5 <- messagePanorama(b7)

        _ = panoramaDagLevel5 shouldEqual Map(
          v0 -> b4.blockHash,
          v1 -> b3.blockHash,
          v2 -> b7.blockHash,
          v3 -> b5.blockHash
        )
      } yield ()
  }

  // See [[casper/src/test/resources/casper/panoramaForEquivocatorSwimlaneIsEmpty.png]]
  "panoramaM" should "properly return the panorama of message B, and when V(j)-swimlane is empty or V(j) happens to be an equivocator, puts 0 in the corresponding cell." in withCombinedStorage() {
    implicit storage =>
      val v0                = generateValidator("V0")
      val v1                = generateValidator("V1")
      val v2                = generateValidator("V2")
      val v3                = generateValidator("V3")
      val v4                = generateValidator("V4")
      val validators        = List(v0, v1, v2, v3, v4)
      val validatorsToIndex = validators.zipWithIndex.toMap

      val bonds = validators.map(v => Bond(v, 1))

      for {
        genesis <- createAndStoreMessage[Task](Seq(), EmptyValidator)
        b1 <- createAndStoreMessage[Task](
               Seq(genesis.blockHash),
               v0,
               bonds
             )
        b2 <- createAndStoreMessage[Task](
               Seq(genesis.blockHash),
               v3,
               bonds
             )
        _ <- createAndStoreMessage[Task](
              Seq(b1.blockHash),
              v1,
              bonds,
              Map(v0 -> b1.blockHash)
            )
        b4 <- createAndStoreMessage[Task](
               Seq(b1.blockHash),
               v0,
               bonds,
               Map(v0 -> b1.blockHash)
             )
        // b5 votes for b2 instead of b1
        b5 <- createAndStoreMessage[Task](
               Seq(b2.blockHash),
               v3,
               bonds,
               Map(v3 -> b2.blockHash)
             )
        b6 <- createAndStoreMessage[Task](
               Seq(b4.blockHash),
               v2,
               bonds,
               Map(v0 -> b4.blockHash, v3 -> b5.blockHash)
             )
        b7 <- createAndStoreMessage[Task](
               Seq(b6.blockHash),
               v2,
               bonds,
               Map(v2 -> b6.blockHash)
             )
        _ <- createAndStoreMessage[Task](
              Seq(b7.blockHash),
              v1,
              bonds,
              Map(v2 -> b4.blockHash) // skip v1 last message in justifications
            )
        b7Msg      = Message.fromBlock(b7).get
        genesisMsg = Message.fromBlock(genesis).get
        dag        <- storage.getRepresentation
        b7Panorama <- FinalityDetectorUtil
                       .panoramaOfBlockByValidators[Task](dag, b7Msg, genesisMsg, validators.toSet)
        panoramaM <- FinalityDetectorUtil.panoramaM(
                      dag,
                      validatorsToIndex,
                      b7Msg,
                      b7Panorama,
                      isHighway = false
                    )
        _ = panoramaM.size shouldBe (validatorsToIndex.size)
        _ = panoramaM shouldBe IndexedSeq(
          b4.getHeader.jRank,
          0, // V(1) happens to be an equivocator
          b7.getHeader.jRank,
          b5.getHeader.jRank,
          0 // V(4)-swimlane is empty
        )
      } yield ()
  }
}
