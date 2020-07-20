package io.casperlabs.casper.finality.votingmatrix

import cats.data.StateT
import cats.effect.Sync
import cats.effect.concurrent.Ref
import cats.implicits._
import cats.mtl.FunctorRaise
import com.github.ghik.silencer.silent
import com.google.protobuf.ByteString
import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.casper.consensus.Block
import io.casperlabs.casper.consensus.Block.{MessageRole, MessageType}
import io.casperlabs.casper.consensus.info.Event.Value.{BlockAdded, NewFinalizedBlock}
import io.casperlabs.casper.equivocations.EquivocationDetector
import io.casperlabs.casper.finality.CommitteeWithConsensusValue
import io.casperlabs.casper.helper.BlockGenerator._
import io.casperlabs.casper.helper.BlockUtil.generateValidator
import io.casperlabs.casper.helper.{BlockGenerator, StorageFixture}
import io.casperlabs.casper.util.BondingUtil.Bond
import io.casperlabs.casper.util.EventStreamParser
import io.casperlabs.casper.{validation, InvalidBlock}
import io.casperlabs.crypto.Keys
import io.casperlabs.models.Message
import io.casperlabs.shared.{FilesAPI, Log, LogStub, Time}
import io.casperlabs.storage.block.BlockStorage
import io.casperlabs.storage.dag.{AncestorsStorage, DagRepresentation, DagStorage}
import monix.eval.Task
import org.scalatest.{FlatSpec, Matchers}
import io.casperlabs.shared.ByteStringPrettyPrinter._
import io.casperlabs.storage.SQLiteStorage.CombinedStorage
import monix.tail.Iterant

import scala.collection.immutable.HashMap

@silent("is never used")
class FinalityDetectorByVotingMatrixTest
    extends FlatSpec
    with Matchers
    with BlockGenerator
    with StorageFixture {

  behavior of "Finality Detector of Voting Matrix"

  implicit val logEff = LogStub[Task]()
  implicit val raiseValidateErr: FunctorRaise[Task, InvalidBlock] =
    validation.raiseValidateErrorThroughApplicativeError[Task]

  def mkVotingMatrix(
      dag: DagRepresentation[Task],
      genesisHash: BlockHash,
      isHighway: Boolean = false
  )(
      implicit AS: AncestorsStorage[Task]
  ) =
    FinalityDetectorVotingMatrix
      .of[Task](
        dag,
        genesisHash,
        rFTT = 0.1,
        isHighway
      )

  it should "detect ballots finalizing a block" in withCombinedStorage() { implicit storage =>
    /* The DAG looks like:
     *
     *
     *
     *
     *     Ballot
     *       \
     *       b1
     *      /
     *    a1
     *      \
     *      genesis
     */

    val v1     = generateValidator("V1")
    val v2     = generateValidator("V2")
    val v1Bond = Bond(v1, 10)
    val v2Bond = Bond(v2, 10)
    val bonds  = Seq(v1Bond, v2Bond)

    for {
      genesis <- createAndStoreMessage[Task](Seq(), EmptyValidator, bonds)
      dag     <- storage.getRepresentation
      implicit0(detector: FinalityDetectorVotingMatrix[Task]) <- mkVotingMatrix(
                                                                  dag,
                                                                  genesis.blockHash
                                                                )
      (a1, c1) <- createBlockAndUpdateFinalityDetector[Task](
                   Seq(genesis.blockHash),
                   genesis.blockHash,
                   v1,
                   bonds,
                   HashMap(v1 -> genesis.blockHash),
                   messageType = Block.MessageType.BLOCK,
                   lfb = genesis
                 )
      _ = c1 shouldBe empty
      (b1, c2) <- createBlockAndUpdateFinalityDetector[Task](
                   Seq(a1.blockHash),
                   genesis.blockHash,
                   v2,
                   bonds,
                   HashMap(v1 -> a1.blockHash),
                   messageType = Block.MessageType.BLOCK,
                   lfb = genesis
                 )
      _ = c2 shouldBe empty
      (ballot, c3) <- createBlockAndUpdateFinalityDetector[Task](
                       Seq(b1.blockHash),
                       genesis.blockHash,
                       v1,
                       bonds,
                       HashMap(v1 -> b1.blockHash),
                       messageType = Block.MessageType.BALLOT,
                       lfb = genesis
                     )
      _ = c3 shouldBe Seq(CommitteeWithConsensusValue(Set(v1, v2), 20, a1.blockHash))
    } yield ()
  }

  it should "finalize as many blocks as possible in a round even with omega blocks (TNET-36)" in withCombinedStorage() {
    implicit storage =>
      val vA    = generateValidator("A")
      val vB    = generateValidator("B")
      val vC    = generateValidator("C")
      val vD    = generateValidator("D")
      val bonds = List(vA, vB, vC, vD).map(Bond(_, 10))

      def makeCreator(genesis: Block, lfbRef: Ref[Task, Block])(
          implicit m: FinalityDetectorVotingMatrix[Task]
      ) =
        (
            validator: Keys.PublicKeyHashBS,
            messageType: MessageType,
            messageRole: MessageRole,
            parent: Block,
            justifications: Map[Validator, Block]
        ) =>
          for {
            lfb <- lfbRef.get
            (block, _) <- createBlockAndUpdateFinalityDetector[Task](
                           parentsHashList = Seq(parent.blockHash),
                           keyBlockHash = genesis.blockHash,
                           validator,
                           bonds,
                           lfb = lfb,
                           messageType = messageType,
                           messageRole = messageRole,
                           justifications = justifications.mapValues(_.blockHash),
                           check = false
                         )
          } yield block

      val BLOC = MessageType.BLOCK
      val BALL = MessageType.BALLOT
      val CONF = MessageRole.CONFIRMATION
      val PROP = MessageRole.PROPOSAL
      val WITN = MessageRole.WITNESS

      /* The DAG looks like:
       *
       * A5 ---------
       *             \
       *               d4
       *             /
       *          c4
       *        /
       *     b4
       *   /
       * a4 ----------
       * |  \    \    \
       * |   b3   c3   d3
       * |  /    /    /
       * A3 ---------
       *   \------
       *    \     \
       *     |     \
       *     |      \
       *     b2      \
       *   /    \     \
       * a2       C2   d2
       * |  \   /  |   |
       * |   b1   c1   d1
       * |  /    /    /
       * A1 ---------
       *  \
       *   G
       */

      for {
        genesis <- createAndStoreMessage[Task](Seq(), EmptyValidator, bonds)
        dag     <- storage.getRepresentation
        implicit0(detector: FinalityDetectorVotingMatrix[Task]) <- mkVotingMatrix(
                                                                    dag,
                                                                    genesis.blockHash
                                                                  )
        lfbRef <- Ref.of[Task, Block](genesis)
        create = makeCreator(genesis, lfbRef)

        a1 <- create(vA, BLOC, PROP, genesis, Map.empty)
        b1 <- create(vB, BALL, CONF, a1, Map(vA -> a1))
        c1 <- create(vC, BALL, CONF, a1, Map(vA -> a1))
        d1 <- create(vD, BALL, CONF, a1, Map(vA -> a1))
        a2 <- create(vA, BALL, WITN, a1, Map(vA -> a1, vB -> b1))
        c2 <- create(vC, BLOC, WITN, a1, Map(vA -> a1, vB -> b1, vC -> c1))
        d2 <- create(vD, BALL, WITN, a1, Map(vA -> a1, vD -> d1))
        b2 <- create(vB, BALL, WITN, c2, Map(vA -> a2, vB -> b1, vC -> c2))
        a3 <- create(vA, BLOC, PROP, c2, Map(vA -> a2, vB -> b2, vC -> c2, vD -> d2))

        f1 <- detector.checkFinality(dag)
        _  = f1 should have size 1
        _  = f1.head.consensusValue shouldBe a1.blockHash
        _  = f1.head.validator shouldBe Set(vA, vB, vC)
        _  <- lfbRef.set(a1)

        b3 <- create(vB, BALL, CONF, a3, Map(vA -> a3, vB -> b2, vC -> c2, vD -> d2))
        c3 <- create(vC, BALL, CONF, a3, Map(vA -> a3, vB -> b2, vC -> c2, vD -> d2))
        d3 <- create(vD, BALL, CONF, a3, Map(vA -> a3, vB -> b2, vC -> c2, vD -> d2))
        a4 <- create(vA, BALL, WITN, a3, Map(vA -> a3, vB -> b3, vC -> c3, vD -> d3))
        b4 <- create(vB, BALL, WITN, a3, Map(vA -> a4, vB -> b3, vC -> c3, vD -> d3))
        c4 <- create(vC, BALL, WITN, a3, Map(vA -> a4, vB -> b4, vC -> c3, vD -> d3))
        d4 <- create(vD, BALL, WITN, a3, Map(vA -> a4, vB -> b4, vC -> c4, vD -> d3))
        a5 <- create(vA, BLOC, PROP, a3, Map(vA -> a4, vB -> b4, vC -> c4, vD -> d4))

        f2 <- detector.checkFinality(dag)
        _  = f2.map(_.consensusValue) shouldBe List(c2.blockHash, a3.blockHash)
        _  = f2.last.validator shouldBe Set(vA, vB, vC, vD)
        _  <- lfbRef.set(a3)

      } yield ()
  }

  it should "detect finality as appropriate" in withCombinedStorage() { implicit storage =>
    /* The DAG looks like:
     *
     *
     *
     *
     *   b4
     *   |  \
     *   b3  b2
     *    \ /
     *    b1
     *      \
     *      genesis
     */
    val v1     = generateValidator("V1")
    val v2     = generateValidator("V2")
    val v1Bond = Bond(v1, 20)
    val v2Bond = Bond(v2, 10)
    val bonds  = Seq(v1Bond, v2Bond)

    for {
      genesis <- createAndStoreMessage[Task](Seq(), EmptyValidator, bonds)
      dag     <- storage.getRepresentation
      implicit0(detector: FinalityDetectorVotingMatrix[Task]) <- mkVotingMatrix(
                                                                  dag,
                                                                  genesis.blockHash
                                                                )
      (b1, c1) <- createBlockAndUpdateFinalityDetector[Task](
                   Seq(genesis.blockHash),
                   genesis.blockHash,
                   v1,
                   bonds,
                   HashMap(EmptyValidator -> genesis.blockHash),
                   lfb = genesis
                 )
      _ = c1 shouldBe Seq(CommitteeWithConsensusValue(Set(v1), 20, b1.blockHash))
      (b2, c2) <- createBlockAndUpdateFinalityDetector[Task](
                   Seq(b1.blockHash),
                   b1.blockHash,
                   v2,
                   bonds,
                   HashMap(v1 -> b1.blockHash),
                   lfb = b1
                 )
      _ = c2 shouldBe empty
      (b3, c3) <- createBlockAndUpdateFinalityDetector[Task](
                   Seq(b1.blockHash),
                   b1.blockHash,
                   v1,
                   bonds,
                   HashMap(v1 -> b1.blockHash),
                   lfb = b1
                 )
      _ = c3 shouldBe Seq(CommitteeWithConsensusValue(Set(v1), 20, b3.blockHash))
      (b4, c4) <- createBlockAndUpdateFinalityDetector[Task](
                   Seq(b3.blockHash),
                   b3.blockHash,
                   v1,
                   bonds,
                   HashMap(v1 -> b3.blockHash, v2 -> b2.blockHash),
                   lfb = b3
                 )
      result = c4 shouldBe Seq(CommitteeWithConsensusValue(Set(v1), 20, b4.blockHash))
    } yield result
  }

  it should "finalize blocks properly with only one validator" in withCombinedStorage() {
    implicit storage =>
      /* The DAG looks like:
       *
       *    b4
       *    |
       *    b3
       *    |
       *    b2
       *    |
       *    b1
       *      \
       *      genesis
       */
      val v1     = generateValidator("V1")
      val v1Bond = Bond(v1, 10)
      val bonds  = Seq(v1Bond)
      for {
        genesis <- createAndStoreMessage[Task](Seq(), EmptyValidator, bonds)
        dag     <- storage.getRepresentation
        implicit0(detector: FinalityDetectorVotingMatrix[Task]) <- mkVotingMatrix(
                                                                    dag,
                                                                    genesis.blockHash
                                                                  )
        (b1, c1) <- createBlockAndUpdateFinalityDetector[Task](
                     Seq(genesis.blockHash),
                     genesis.blockHash,
                     v1,
                     bonds,
                     lfb = genesis
                   )

        _ = c1 shouldBe Seq(CommitteeWithConsensusValue(Set(v1), 10, b1.blockHash))
        (b2, c2) <- createBlockAndUpdateFinalityDetector[Task](
                     Seq(b1.blockHash),
                     b1.blockHash,
                     v1,
                     bonds,
                     HashMap(v1 -> b1.blockHash),
                     lfb = b1
                   )
        _ = c2 shouldBe Seq(CommitteeWithConsensusValue(Set(v1), 10, b2.blockHash))
        (b3, c3) <- createBlockAndUpdateFinalityDetector[Task](
                     Seq(b2.blockHash),
                     b2.blockHash,
                     v1,
                     bonds,
                     HashMap(v1 -> b2.blockHash),
                     lfb = b2
                   )
        _ = c3 shouldBe Seq(CommitteeWithConsensusValue(Set(v1), 10, b3.blockHash))
        (b4, c4) <- createBlockAndUpdateFinalityDetector[Task](
                     Seq(b3.blockHash),
                     b3.blockHash,
                     v1,
                     bonds,
                     HashMap(v1 -> b3.blockHash),
                     lfb = b3
                   )
        result = c4 shouldBe Seq(CommitteeWithConsensusValue(Set(v1), 10, b4.blockHash))
      } yield result
  }

  it should "increment last finalized block as appropriate in round robin" in withCombinedStorage() {
    implicit storage =>
      /* The DAG looks like:
       *
       *
       *    b7 ----
       *           \
       *            b6
       *           /
       *        b5
       *      /
       *    b4 ----
       *           \
       *            b3
       *          /
       *        b2
       *      /
       *    b1
       *      \
       *      genesis
       */
      val v1     = generateValidator("V1")
      val v2     = generateValidator("V2")
      val v3     = generateValidator("V3")
      val v1Bond = Bond(v1, 10)
      val v2Bond = Bond(v2, 10)
      val v3Bond = Bond(v3, 10)
      val bonds  = Seq(v1Bond, v2Bond, v3Bond)
      for {
        genesis <- createAndStoreMessage[Task](Seq(), EmptyValidator, bonds)
        dag     <- storage.getRepresentation
        implicit0(detector: FinalityDetectorVotingMatrix[Task]) <- mkVotingMatrix(
                                                                    dag,
                                                                    genesis.blockHash
                                                                  )
        (b1, c1) <- createBlockAndUpdateFinalityDetector[Task](
                     Seq(genesis.blockHash),
                     genesis.blockHash,
                     v1,
                     bonds,
                     lfb = genesis
                   )
        _ = c1 shouldBe empty
        (b2, c2) <- createBlockAndUpdateFinalityDetector[Task](
                     Seq(b1.blockHash),
                     genesis.blockHash,
                     v2,
                     bonds,
                     HashMap(v1 -> b1.blockHash),
                     lfb = genesis
                   )
        _ = c2 shouldBe empty
        (b3, c3) <- createBlockAndUpdateFinalityDetector[Task](
                     Seq(b2.blockHash),
                     genesis.blockHash,
                     v3,
                     bonds,
                     HashMap(v1 -> b1.blockHash, v2 -> b2.blockHash),
                     lfb = genesis
                   )
        _ = c3 shouldBe empty
        (b4, c4) <- createBlockAndUpdateFinalityDetector[Task](
                     Seq(b3.blockHash),
                     genesis.blockHash,
                     v1,
                     bonds,
                     HashMap(v1 -> b1.blockHash, v2 -> b2.blockHash, v3 -> b3.blockHash),
                     lfb = genesis
                   )
        _ = c4 shouldBe Seq(CommitteeWithConsensusValue(Set(v1, v2, v3), 30, b1.blockHash))
        (b5, c5) <- createBlockAndUpdateFinalityDetector[Task](
                     Seq(b4.blockHash),
                     genesis.blockHash,
                     v2,
                     bonds,
                     HashMap(v1 -> b4.blockHash, v2 -> b2.blockHash, v3 -> b3.blockHash),
                     lfb = b1
                   )
        _ = c5 shouldBe Seq(CommitteeWithConsensusValue(Set(v1, v2, v3), 30, b2.blockHash))
        (b6, c6) <- createBlockAndUpdateFinalityDetector[Task](
                     Seq(b5.blockHash),
                     genesis.blockHash,
                     v3,
                     bonds,
                     HashMap(v1 -> b4.blockHash, v2 -> b5.blockHash, v3 -> b3.blockHash),
                     lfb = b2
                   )
        _ = c6 shouldBe Seq(CommitteeWithConsensusValue(Set(v1, v2, v3), 30, b3.blockHash))
        (b7, c7) <- createBlockAndUpdateFinalityDetector[Task](
                     Seq(b6.blockHash),
                     genesis.blockHash,
                     v1,
                     bonds,
                     HashMap(v1 -> b4.blockHash, v2 -> b5.blockHash, v3 -> b6.blockHash),
                     lfb = b3
                   )
        result = c7 shouldBe Seq(CommitteeWithConsensusValue(Set(v1, v2, v3), 30, b4.blockHash))
      } yield result
  }

  // See [[casper/src/test/resources/casper/finalityDetectorWithEquivocations.png]]
  it should "exclude the weight of validator who have been detected equivocating when searching for the committee" in withCombinedStorage() {
    implicit storage =>
      val v1     = generateValidator("V1")
      val v2     = generateValidator("V2")
      val v3     = generateValidator("V3")
      val v1Bond = Bond(v1, 10)
      val v2Bond = Bond(v2, 10)
      val v3Bond = Bond(v3, 10)
      val bonds  = Seq(v1Bond, v2Bond, v3Bond)

      for {
        genesis <- createAndStoreMessage[Task](Seq(), EmptyValidator, bonds)
        dag     <- storage.getRepresentation
        implicit0(detector: FinalityDetectorVotingMatrix[Task]) <- mkVotingMatrix(
                                                                    dag,
                                                                    genesis.blockHash
                                                                  )
        (b1, c1) <- createBlockAndUpdateFinalityDetector[Task](
                     Seq(genesis.blockHash),
                     genesis.blockHash,
                     v1,
                     bonds,
                     lfb = genesis
                   )
        _ = c1 shouldBe empty
        (b2, c2) <- createBlockAndUpdateFinalityDetector[Task](
                     Seq(b1.blockHash),
                     genesis.blockHash,
                     v2,
                     bonds,
                     HashMap(v1 -> b1.blockHash),
                     lfb = genesis
                   )
        _ = c2 shouldBe empty
        // b4 and b2 are both created by v2 but don't cite each other
        (b4, c4) <- createBlockAndUpdateFinalityDetector[Task](
                     Seq(b1.blockHash, genesis.blockHash),
                     genesis.blockHash,
                     v2,
                     bonds,
                     HashMap(v1 -> b1.blockHash),
                     ByteString.copyFromUtf8(scala.util.Random.nextString(64)),
                     lfb = genesis
                   )
        _ = c4 shouldBe empty
        // so v2 can be detected equivocating
        _ <- dag.getEquivocators.map(_ shouldBe Set(v2))
        (b3, c3) <- createBlockAndUpdateFinalityDetector[Task](
                     Seq(b2.blockHash),
                     genesis.blockHash,
                     v3,
                     bonds,
                     HashMap(v2 -> b2.blockHash),
                     lfb = genesis
                   )
        _ = c3 shouldBe empty
        (b5, c5) <- createBlockAndUpdateFinalityDetector[Task](
                     Seq(b3.blockHash),
                     genesis.blockHash,
                     v1,
                     bonds,
                     HashMap(v1 -> b1.blockHash, v3 -> b3.blockHash),
                     lfb = genesis
                   )
        // Though v2 also votes for b1, it has been detected equivocating, so the committee doesn't include v2 or count its weight
        result = c5 shouldBe Seq(CommitteeWithConsensusValue(Set(v1, v3), 20, b1.blockHash))
      } yield result
  }

  // See [[casper/src/test/resources/casper/equivocatingBlockGetFinalized.png]]
  it should "finalize equivocator's block when enough honest validators votes for it" in withCombinedStorage() {
    implicit storage =>
      val v1     = generateValidator("V1")
      val v2     = generateValidator("V2")
      val v3     = generateValidator("V3")
      val v1Bond = Bond(v1, 10)
      val v2Bond = Bond(v2, 10)
      val v3Bond = Bond(v3, 10)
      val bonds  = Seq(v1Bond, v2Bond, v3Bond)

      for {
        genesis <- createAndStoreMessage[Task](Seq(), EmptyValidator, bonds)
        dag     <- storage.getRepresentation
        implicit0(detector: FinalityDetectorVotingMatrix[Task]) <- mkVotingMatrix(
                                                                    dag,
                                                                    genesis.blockHash
                                                                  )
        (b1, c1) <- createBlockAndUpdateFinalityDetector[Task](
                     Seq(genesis.blockHash),
                     genesis.blockHash,
                     v1,
                     bonds,
                     lfb = genesis
                   )
        _ = c1 shouldBe empty
        (b2, c2) <- createBlockAndUpdateFinalityDetector[Task](
                     Seq(b1.blockHash),
                     genesis.blockHash,
                     v2,
                     bonds,
                     HashMap(v1 -> b1.blockHash),
                     lfb = genesis
                   )
        _ = c2 shouldBe empty
        // b1 and b3 are both created by v1 but don't cite each other
        (b3, c3) <- createBlockAndUpdateFinalityDetector[Task](
                     Seq(genesis.blockHash),
                     genesis.blockHash,
                     v1,
                     bonds,
                     lfb = genesis
                   )
        _ = c3 shouldBe empty
        // so v1 can be detected equivocating
        _ <- dag.getEquivocators.map(_ shouldBe Set(v1))
        (b4, c4) <- createBlockAndUpdateFinalityDetector[Task](
                     Seq(b2.blockHash),
                     genesis.blockHash,
                     v3,
                     bonds,
                     HashMap(v2 -> b2.blockHash),
                     lfb = genesis
                   )
        _ = c4 shouldBe empty
        (b5, c5) <- createBlockAndUpdateFinalityDetector[Task](
                     Seq(b4.blockHash),
                     genesis.blockHash,
                     v2,
                     bonds,
                     HashMap(v2 -> b2.blockHash, v3 -> b4.blockHash),
                     lfb = genesis
                   )
        // After creating b5, v2 knows v3 and himself vote for b1, and v3 knows v2 and
        // himself vote for b1, so v2 and v3 construct a committee.
        // So even b1 was created by v1 who equivocated, it gets finalized as having enough honest supporters
        result = c5 shouldBe Seq(
          CommitteeWithConsensusValue(Set(v2, v3), 20, b1.blockHash),
          CommitteeWithConsensusValue(Set(v2, v3), 20, b2.blockHash)
        )
      } yield result
  }

  // See [[casper/src/test/resources/casper/equivocatingBlockCantGetFinalized.png]]
  it should "not finalize equivocator's blocks, no matter how many votes equivocating validators cast" in withCombinedStorage() {
    implicit storage =>
      val v1     = generateValidator("V1")
      val v2     = generateValidator("V2")
      val v3     = generateValidator("V3")
      val v1Bond = Bond(v1, 10)
      val v2Bond = Bond(v2, 10)
      val v3Bond = Bond(v3, 10)
      val bonds  = Seq(v1Bond, v2Bond, v3Bond)
      for {
        genesis <- createAndStoreMessage[Task](Seq(), EmptyValidator, bonds)
        dag     <- storage.getRepresentation
        implicit0(detector: FinalityDetectorVotingMatrix[Task]) <- mkVotingMatrix(
                                                                    dag,
                                                                    genesis.blockHash
                                                                  )
        (b1, c1) <- createBlockAndUpdateFinalityDetector[Task](
                     Seq(genesis.blockHash),
                     genesis.blockHash,
                     v1,
                     bonds,
                     lfb = genesis
                   )
        _ = c1 shouldBe empty
        (b2, c2) <- createBlockAndUpdateFinalityDetector[Task](
                     Seq(b1.blockHash),
                     genesis.blockHash,
                     v2,
                     bonds,
                     HashMap(v1 -> b1.blockHash),
                     lfb = genesis
                   )
        _ = c2 shouldBe empty
        // b1 and b3 are both created by v1 but don't cite each other
        (b3, c3) <- createBlockAndUpdateFinalityDetector[Task](
                     Seq(genesis.blockHash),
                     genesis.blockHash,
                     v1,
                     bonds,
                     lfb = genesis
                   )
        _ = c3 shouldBe empty
        // so v1 can be detected equivocating
        _ <- dag.getEquivocators.map(_ shouldBe Set(v1))
        (b4, c4) <- createBlockAndUpdateFinalityDetector[Task](
                     Seq(genesis.blockHash),
                     genesis.blockHash,
                     v3,
                     bonds,
                     HashMap(v3 -> genesis.blockHash),
                     lfb = genesis
                   )
        _ = c4 shouldBe empty
        // b4 and b5 are both created by v3 but don't cite each other
        (b5, c5) <- createBlockAndUpdateFinalityDetector[Task](
                     Seq(b2.blockHash),
                     genesis.blockHash,
                     v3,
                     bonds,
                     HashMap(v2 -> b2.blockHash),
                     lfb = genesis
                   )
        _ = c5 shouldBe empty
        // so v3 can be detected equivocating
        _ <- dag.getEquivocators.map(_ shouldBe Set(v1, v3))
        (b6, c6) <- createBlockAndUpdateFinalityDetector[Task](
                     Seq(b5.blockHash),
                     genesis.blockHash,
                     v2,
                     bonds,
                     HashMap(v3 -> b5.blockHash),
                     lfb = genesis
                   )
        _ = c6 shouldBe empty
        (b7, c7) <- createBlockAndUpdateFinalityDetector[Task](
                     Seq(b5.blockHash),
                     genesis.blockHash,
                     v1,
                     bonds,
                     HashMap(v2 -> b6.blockHash),
                     lfb = genesis
                   )
        // After creating b7, all validators know they all vote for v1, but b1 still can not get finalized, because v1 and v3 equivocated
        result = c7 shouldBe empty
      } yield result
  }

  it should "not count child era messages towards finality of the parent bocks" in withCombinedStorage() {
    implicit storage =>
      /* The DAG looks like:
       * era-1:
       *    a1 ----
       *           \
       *            c1
       *          /
       *        b1
       *      /
       * era-0:
       *    a0
       *      \
       *      genesis
       */
      val v1     = generateValidator("V1")
      val v2     = generateValidator("V2")
      val v3     = generateValidator("V3")
      val v1Bond = Bond(v1, 10)
      val v2Bond = Bond(v2, 10)
      val v3Bond = Bond(v3, 10)
      val bonds  = Seq(v1Bond, v2Bond, v3Bond)
      for {
        genesis <- createAndStoreMessage[Task](Seq(), EmptyValidator, bonds)
        dag     <- storage.getRepresentation
        implicit0(detector: FinalityDetectorVotingMatrix[Task]) <- mkVotingMatrix(
                                                                    dag,
                                                                    genesis.blockHash,
                                                                    isHighway = true
                                                                  )
        (a0, c1) <- createBlockAndUpdateFinalityDetector[Task](
                     Seq(genesis.blockHash),
                     genesis.blockHash,
                     v1,
                     bonds,
                     lfb = genesis
                   )
        _ = c1 shouldBe empty
        (b1, c2) <- createBlockAndUpdateFinalityDetector[Task](
                     Seq(a0.blockHash),
                     a0.blockHash,
                     v2,
                     bonds,
                     HashMap(v1 -> a0.blockHash),
                     lfb = genesis
                   )
        _ = c2 shouldBe empty
        (c1, c3) <- createBlockAndUpdateFinalityDetector[Task](
                     Seq(b1.blockHash),
                     a0.blockHash,
                     v3,
                     bonds,
                     HashMap(v1 -> a0.blockHash, v2 -> b1.blockHash),
                     lfb = genesis
                   )
        _ = c3 shouldBe empty
        (a1, c4) <- createBlockAndUpdateFinalityDetector[Task](
                     Seq(c1.blockHash),
                     a0.blockHash,
                     v1,
                     bonds,
                     HashMap(v1 -> a0.blockHash, v2 -> b1.blockHash, v3 -> c1.blockHash),
                     lfb = genesis
                   )
        _ = c4 shouldBe empty
      } yield ()
  }

  // see [casper/src/test/resources/casper/CON-654_testnet_finalizer_bug.jpg]
  it should "not detect finality when there's none (a test case from the CON-654 bug)" in withCombinedStorage() {
    implicit storage =>
      val v1     = generateValidator("V1")
      val v2     = generateValidator("V2")
      val v3     = generateValidator("V3")
      val v4     = generateValidator("V4")
      val v5     = generateValidator("V5")
      val v1Bond = Bond(v1, 52)
      val v2Bond = Bond(v2, 51)
      val v3Bond = Bond(v3, 50)
      val v4Bond = Bond(v4, 54)
      val v5Bond = Bond(v5, 53)
      val bonds  = Seq(v1Bond, v2Bond, v3Bond, v4Bond, v5Bond)
      import monix.execution.Scheduler.Implicits.global
      val genesis = createAndStoreMessage[Task](Seq(), EmptyValidator, bonds).runSyncUnsafe()

      type LFB = Block

      def createAndAdvanceTheLFB(v: Validator, parent: Block, justifications: List[Block])(
          implicit detector: FinalityDetectorVotingMatrix[Task]
      ): StateT[Task, LFB, Block] =
        StateT { lfb =>
          createBlockAndUpdateFinalityDetector[Task](
            Seq(parent.blockHash),
            genesis.blockHash,
            v,
            bonds,
            justifications
              .map(b => Keys.PublicKeyHash(b.getHeader.validatorPublicKeyHash) -> b.blockHash)
              .toMap,
            lfb = lfb
          ).map {
            case (block, finalizedBlocks) =>
              val finalizedBlocksHashes =
                finalizedBlocks.map(_.consensusValue.show).mkString("[", ", ", "]")
              assert(
                finalizedBlocks.isEmpty,
                s"No blocks should be finalized. ${block.blockHash} finalized $finalizedBlocksHashes."
              )
              (lfb, block) //We know that in this specific test LFB should not change
          }
        }

      for {

        dag <- storage.getRepresentation
        implicit0(detector: FinalityDetectorVotingMatrix[Task]) <- mkVotingMatrix(
                                                                    dag,
                                                                    genesis.blockHash,
                                                                    isHighway = true
                                                                  )
        test = for {
          b994 <- createAndAdvanceTheLFB(v3, genesis, List.empty)
          b707 <- createAndAdvanceTheLFB(v5, genesis, List.empty)
          b296 <- createAndAdvanceTheLFB(v1, b994, List(b994))
          bo8a <- createAndAdvanceTheLFB(v1, b994, List(b296, b994))
          b206 <- createAndAdvanceTheLFB(v3, b994, List(b994))
          b8a1 <- createAndAdvanceTheLFB(v5, b994, List(b994, b296, b206, b707))
          bc7e <- createAndAdvanceTheLFB(v1, b994, List(bo8a, b206))
          bc38 <- createAndAdvanceTheLFB(v3, b994, List(b206))
          b66a <- createAndAdvanceTheLFB(v1, b994, List(bc7e, b707))
          bb9b <- createAndAdvanceTheLFB(v4, b707, List(b707))
          b6e2 <- createAndAdvanceTheLFB(v2, b994, List(bo8a, b206, bb9b))
          b1ee <- createAndAdvanceTheLFB(v4, b707, List(bb9b, bo8a))
          b5d6 <- createAndAdvanceTheLFB(v2, b994, List(b6e2, b1ee, b8a1))
          b7f0 <- createAndAdvanceTheLFB(v5, b994, List(b8a1, bb9b, bc38, bo8a))
          be2f <- createAndAdvanceTheLFB(v2, b994, List(b5d6, b7f0))
          b5e4 <- createAndAdvanceTheLFB(v4, b707, List(bc7e, b206, b1ee, b707))
        } yield ()
        (lfb, _) <- test.run(genesis)
        _        = assert(lfb == genesis)
      } yield ()
  }

  case class ReplayedDagFixture(
      genesis: Message,
      messagesAdded: List[Message],
      lfbChain: List[BlockHash]
  )

  implicit def `Message => Block`(m: Message): Block =
    Block()
      .withBlockHash(m.messageHash)
      .withHeader(
        Block
          .Header()
          .withTimestamp(m.timestamp)
          .withRoundId(m.roundId)
          .withParentHashes(m.parents)
          .withKeyBlockHash(m.eraId)
          .withJRank(m.jRank)
          .withMainRank(m.mainRank)
          .withJustifications(m.justifications)
          .withMessageType {
            if (m.isBallot) MessageType.BALLOT
            else MessageType.BLOCK
          }
          .withMessageRole(m.messageRole)
          .withValidatorBlockSeqNum(m.validatorMsgSeqNum)
          .withValidatorPrevBlockHash(m.validatorPrevMessageHash)
          .withValidatorPublicKeyHash(m.validatorId)
          .withState(
            m.blockSummary.getHeader.getState
          )
      )

  def replayedDag(
      fileName: String
  )(f: CombinedStorage[Task] => ReplayedDagFixture => Task[Unit]) =
    withCombinedStorage() { implicit storage =>
      implicit val filesAPI = FilesAPI.create[Task]

      for {
        events <- EventStreamParser.fromFile[Task](fileName)
        (messagesInv, lfbInv) = events
          .map(_.value)
          .foldLeft((List.empty[Message], List.empty[BlockHash])) {
            case ((messages, lfb), m) =>
              m match {
                case ba: BlockAdded =>
                  (Message.fromBlockSummary(ba.value.getBlock.getSummary).get :: messages, lfb)
                case nf: NewFinalizedBlock => (messages, nf.value.blockHash :: lfb)
                case _                     => (messages, lfb)
              }
          }
        messages = messagesInv.reverse
        lfb      = lfbInv.reverse
        genesis  = messages.head
        _        <- messages.traverse(m => storage.put(m.messageHash, m, Map.empty))
        _        <- f(storage)(ReplayedDagFixture(genesis, messages.tail, lfb))
      } yield ()
    }

  // see [casper/src/test/resources/casper/CON-654_finalizer_bug_2.jpg]
  it should "replay history from the file with events" in replayedDag(
    "/con-654_event_stream.txt"
  ) { implicit storage => fixture =>
    val genesis = fixture.genesis

    for {
      dag <- storage.getRepresentation
      implicit0(detector: FinalityDetectorVotingMatrix[Task]) <- mkVotingMatrix(
                                                                  dag,
                                                                  genesis.messageHash,
                                                                  isHighway = true
                                                                )

      // We now that the last block shouldn't be finalized.
      incorrectLFB = fixture.lfbChain.last

      (lastLfb, _) <- fixture.messagesAdded.foldLeftM((genesis.messageHash, 0)) {
                       case ((lfbHash, idx), m) =>
                         for {
                           _         <- detector.addMessage(dag, m, lfbHash)
                           finalized <- detector.checkFinality(dag)
                         } yield finalized match {
                           case Nil => (lfbHash, idx)
                           case lfb =>
                             lfb
                               .map(_.consensusValue)
                               .zipWithIndex
                               .map(tuple => (tuple._1, tuple._2 + idx))
                               .foreach {
                                 case (hash, idx) =>
                                   assert(
                                     hash == fixture.lfbChain(idx + 1),
                                     "Finalized different hash than expected."
                                   )
                               }
                             (lfb.last.consensusValue, idx + lfb.size)
                         }
                     }
      _ <- Task(
            assert(
              lastLfb != incorrectLFB,
              s"${incorrectLFB.show} should never be finalized."
            )
          )
    } yield ()
  }

  def createBlockAndUpdateFinalityDetector[F[_]: Sync: Time: Log: BlockStorage: DagStorage: FinalityDetectorVotingMatrix: FunctorRaise[
    *[_],
    InvalidBlock
  ]](
      parentsHashList: Seq[BlockHash],
      keyBlockHash: BlockHash,
      creator: Validator,
      bonds: Seq[Bond] = Seq.empty[Bond],
      justifications: collection.Map[Validator, BlockHash] = HashMap.empty[Validator, BlockHash],
      postStateHash: ByteString = ByteString.copyFromUtf8(scala.util.Random.nextString(64)),
      messageType: Block.MessageType = Block.MessageType.BLOCK,
      messageRole: Block.MessageRole = Block.MessageRole.UNDEFINED,
      lfb: Block,
      check: Boolean = true
  ): F[(Block, Seq[CommitteeWithConsensusValue])] =
    for {
      block <- createMessage[F](
                parentsHashList,
                keyBlockHash,
                creator,
                bonds,
                justifications,
                postStateHash = postStateHash,
                messageType = messageType
              )
      dag     <- DagStorage[F].getRepresentation
      message <- Sync[F].fromTry(Message.fromBlock(block))
      // EquivocationDetector works before adding block to DAG
      _ <- EquivocationDetector.checkEquivocation(dag, message, isHighway = false).attempt
      _ <- BlockStorage[F].put(block.blockHash, block, Map.empty)
      _ <- FinalityDetectorVotingMatrix[F].addMessage(dag, message, lfb.blockHash)
      finalizedBlockOpt <- if (check) FinalityDetectorVotingMatrix[F].checkFinality(dag)
                          else Nil.pure[F]
    } yield block -> finalizedBlockOpt
}
