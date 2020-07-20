package io.casperlabs.casper.finality

import cats.Monad
import cats.data.StateT
import com.google.protobuf.ByteString
import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper.consensus.Era
import io.casperlabs.casper.consensus.info.BlockInfo
import io.casperlabs.casper.helper.BlockUtil.generateValidator
import io.casperlabs.casper.helper.{BlockGenerator, StorageFixture}
import io.casperlabs.casper.util.BondingUtil.Bond
import monix.eval.Task
import org.scalatest.FlatSpec
import io.casperlabs.casper.scalatestcontrib._
import io.casperlabs.models.Message
import io.casperlabs.storage.dag.{
  DagRepresentation,
  EraTipRepresentation,
  FinalityStorage,
  GlobalTipRepresentation
}
import io.casperlabs.storage.dag.DagRepresentation.Validator
import io.casperlabs.casper.mocks.MockFinalityStorage
import io.casperlabs.casper.util.ByteStringPrettifier
import cats.data.IndexedStateT
import cats.effect.Sync

class FinalityDetectorUtilTest
    extends FlatSpec
    with BlockGenerator
    with ByteStringPrettifier
    with StorageFixture {

  behavior of "FinalityDetectorUtilTest"

  // Who creates what doesn't matter in these tests.
  val v1     = generateValidator("V1")
  val v2     = generateValidator("V2")
  val v1Bond = Bond(v1, 2)
  val v2Bond = Bond(v2, 3)
  val bonds  = Seq(v1Bond, v2Bond)

  "finalizedIndirectly" should "finalize blocks in the p-past-cone of the block from the main chain" in withCombinedStorage() {
    implicit storage =>
      //
      // G=A= = =B
      //   \\A1/
      // When B gets finalized A1 gets finalized indirectly.

      for {
        genesis <- createAndStoreMessage[Task](Seq(), EmptyValidator, bonds)
        a       <- createAndStoreBlockFull[Task](v1, Seq(genesis), Seq.empty, bonds)
        a1      <- createAndStoreBlockFull[Task](v2, Seq(a), Seq.empty, bonds)
        b       <- createAndStoreBlockFull[Task](v1, Seq(a, a1), Seq.empty, bonds)
        dag     <- storage.getRepresentation
        implicit0(finalityStorage: FinalityStorage[Task]) <- MockFinalityStorage[Task](
                                                              genesis.blockHash,
                                                              a.blockHash
                                                            )
        finalizedIndirectly <- FinalityDetectorUtil.finalizedIndirectly[Task](
                                dag,
                                b.blockHash
                              )(Sync[Task], finalityStorage)
        finalizedIndirectlyHash = finalizedIndirectly.map(_.messageHash)
      } yield assert(finalizedIndirectlyHash == Set(a1.blockHash))
  }

  it should "not consider previously finalized blocks" in withCombinedStorage() {
    implicit storage =>
      import FinalityDetectorUtilTest._
      // Record how many times (if any) each node was visited.
      type G[A] = StateT[Task, Map[BlockHash, Int], A]

      /**
        *           B = E
        *          //   |
        *         A = D |
        *       // \   \|
        *      G = C = F*
        *
        *  A main chain is G <- C <- F.
        *  When F is finalized it's indirectly finalizing its p-past-cone:
        *  {D, E, B, A}. There are three paths to reach A:
        *  1. F -> D -> A
        *  2. F -> E -> B -> A
        *  3. F -> C -> A
        *  `finalizedIndirectly` should not visit the same node twice.
        *  It should also not traverse subgraphs of already finalized nodes,
        *  i.e. since A is finalized with C, `finalizedIndirectly(F)` should not visit that node.
        */
      for {
        genesis <- createAndStoreMessage[Task](Seq(), EmptyValidator, bonds)
        a       <- createAndStoreBlockFull[Task](v1, Seq(genesis), Seq.empty, bonds)
        b       <- createAndStoreBlockFull[Task](v1, Seq(a), Seq.empty, bonds)
        c       <- createAndStoreBlockFull[Task](v1, Seq(genesis, a), Seq.empty, bonds)
        dag     <- storage.getRepresentation

        // Create DAG that counts the visits.
        stateTDag = stateTDagRepresentation(Task.catsAsync, dag) // For some reason scalac cannot infer this.

        // First finalizing C.
        expectedNodesVisitedA = Map(
          c.blockHash       -> 1,
          a.blockHash       -> 1,
          genesis.blockHash -> 2
        )
        implicit0(finalityStorage: FinalityStorage[G]) <- MockFinalityStorage[G](
                                                           Seq(genesis.blockHash): _*
                                                         ).runA(Map.empty)
        (nodesVisited, finalizedIndirectly) <- FinalityDetectorUtil
                                                .finalizedIndirectly[G](
                                                  stateTDag,
                                                  c.blockHash
                                                )(Sync[G], finalityStorage)
                                                .run(Map.empty)
        _ = nodesVisited shouldBe expectedNodesVisitedA
        _ = finalizedIndirectly.map(_.messageHash) should contain theSameElementsAs Set(a.blockHash)

        d <- createAndStoreBlockFull[Task](v1, Seq(a), Seq.empty, bonds)
        e <- createAndStoreBlockFull[Task](v1, Seq(b), Seq.empty, bonds)
        f <- createAndStoreBlockFull[Task](v1, Seq(c, d, e), Seq.empty, bonds)
        // Notice how neither C nor A are being visited.
        expectedNodesVisitedB = Map(
          f -> 1,
          d -> 1,
          e -> 1,
          b -> 1,
          c -> 1,
          a -> 2
        ).map(p => (p._1.blockHash, p._2))

        _ <- finalityStorage
              .markAsFinalized(c.blockHash, Set(a.blockHash), Set.empty)
              .run(Map.empty)

        (nodeVisitedB, finalizedIndirectlyB) <- FinalityDetectorUtil
                                                 .finalizedIndirectly[G](
                                                   stateTDag,
                                                   f.blockHash
                                                 )
                                                 .run(Map.empty)
        _ = nodeVisitedB shouldBe expectedNodesVisitedB
        _ = finalizedIndirectlyB.map(_.messageHash) should contain theSameElementsAs Set(
          d.blockHash,
          e.blockHash,
          b.blockHash
        )
      } yield ()
  }

  "orphanedIndirectly" should "orphan blocks in the j-past-cone that aren't already finalized" in withCombinedStorage() {
    implicit storage =>
      //    B - C
      //  //
      // G = A === E* = F
      //     \\  /
      //       D
      for {
        g   <- createAndStoreMessage[Task](Seq(), EmptyValidator, bonds)
        a   <- createAndStoreBlockFull[Task](v1, Seq(g), Seq.empty, bonds)
        b   <- createAndStoreBlockFull[Task](v2, Seq(g), Seq.empty, bonds)
        c   <- createAndStoreBlockFull[Task](v2, Seq(b), Seq(b), bonds)
        d   <- createAndStoreBlockFull[Task](v2, Seq(a), Seq(c), bonds)
        e   <- createAndStoreBlockFull[Task](v1, Seq(a, d), Seq(), bonds)
        _   <- createAndStoreBlockFull[Task](v2, Seq(e), Seq(), bonds)
        dag <- storage.getRepresentation
        implicit0(finalityStorage: FinalityStorage[Task]) <- MockFinalityStorage[Task](
                                                              g.blockHash,
                                                              a.blockHash
                                                            )
        orphanedIndirectly <- FinalityDetectorUtil.orphanedIndirectly[Task](
                               dag,
                               e.blockHash,
                               finalizedIndirectly = Set(d.blockHash)
                             )(Sync[Task], finalityStorage)
        orphanedHashes = orphanedIndirectly.map(_.messageHash)
      } yield {
        assert(orphanedHashes == Set(b.blockHash, c.blockHash))
      }
  }

  it should "find orphans across eras" in withCombinedStorage() { implicit storage =>
    // The switch block doesn't have B in its justification, but later
    // when another block is finalized in the child era, B should be
    // marked as an orphan.
    //
    // G = A = | S
    //  \\     |  \\
    //    B    |    C*
    for {
      g   <- createAndStoreMessage[Task](Seq(), EmptyValidator, bonds)
      a   <- createAndStoreBlockFull[Task](v1, Seq(g), Seq.empty, bonds, keyBlockHash = g.blockHash)
      b   <- createAndStoreBlockFull[Task](v2, Seq(g), Seq.empty, bonds, keyBlockHash = g.blockHash)
      s   <- createAndStoreBlockFull[Task](v1, Seq(a), Seq(a), bonds, keyBlockHash = a.blockHash)
      c   <- createAndStoreBlockFull[Task](v2, Seq(s), Seq(s, b), bonds, keyBlockHash = a.blockHash)
      dag <- storage.getRepresentation
      implicit0(finalityStorage: FinalityStorage[Task]) <- MockFinalityStorage[Task](
                                                            g.blockHash,
                                                            a.blockHash,
                                                            s.blockHash,
                                                            c.blockHash
                                                          )
      orphanedIndirectly <- FinalityDetectorUtil.orphanedIndirectly[Task](
                             dag,
                             c.blockHash,
                             finalizedIndirectly = Set.empty
                           )(Sync[Task], finalityStorage)
      orphanedHashes = orphanedIndirectly.map(_.messageHash)
    } yield {
      assert(orphanedHashes == Set(b.blockHash))
    }
  }

  "levelZeroMsgsOfValidator" should "expect multiple messages from the validator in justifications (TNET-36)" in withCombinedStorage() {
    implicit storage =>
      // When we have multiple eras the validator appears multiple times in justifications,
      // but that mustn't prevent us from recognising the earliest vote that goes for a candidate.

      // G - A - B -|- C* - D - E

      for {
        g  <- createAndStoreMessage[Task](Seq(), EmptyValidator, bonds)
        e0 = Era(keyBlockHash = g.blockHash)
        _  <- storage.addEra(e0)
        a <- createAndStoreBlockFull[Task](
              v1,
              Seq(g),
              Seq.empty,
              bonds,
              keyBlockHash = e0.keyBlockHash,
              maybeValidatorPrevBlockHash = None
            )
        e1 = Era(keyBlockHash = a.blockHash, parentKeyBlockHash = e0.keyBlockHash)
        _  <- storage.addEra(e1)
        b <- createAndStoreBlockFull[Task](
              v1,
              Seq(a),
              Seq(a),
              bonds,
              keyBlockHash = e0.keyBlockHash,
              maybeValidatorPrevBlockHash = Some(a.blockHash)
            )
        c <- createAndStoreBlockFull[Task](
              v1,
              Seq(b),
              Seq(b),
              bonds,
              keyBlockHash = e1.keyBlockHash,
              maybeValidatorPrevBlockHash = Some(b.blockHash)
            )
        d <- createAndStoreBlockFull[Task](
              v1,
              Seq(c),
              Seq(c, b),
              bonds,
              keyBlockHash = e1.keyBlockHash,
              maybeValidatorPrevBlockHash = Some(c.blockHash)
            )
        _ <- createAndStoreBlockFull[Task](
              v1,
              Seq(d),
              Seq(d, b),
              bonds,
              keyBlockHash = e1.keyBlockHash,
              maybeValidatorPrevBlockHash = Some(d.blockHash)
            )
        dag <- storage.getRepresentation

        // Say C is the candidate and we want to know what is the first message that votes on C.
        candidate = Message.fromBlock(c).get
        messages <- FinalityDetectorUtil.levelZeroMsgsOfValidator(
                     dag,
                     v1,
                     candidate,
                     isHighway = true
                   )
      } yield {
        messages should not be empty
        messages.last.messageHash shouldBe candidate.messageHash
      }
  }
}

object FinalityDetectorUtilTest {
  import cats.implicits._

  def stateTDagRepresentation[A, F[_]: Monad](
      implicit D: DagRepresentation[F]
  ): DagRepresentation[StateT[F, Map[BlockHash, Int], *]] =
    new DagRepresentation[StateT[F, Map[BlockHash, Int], *]] {
      // Record how many times (if any) each node was visited.
      override def lookup(blockHash: BlockHash): StateT[F, Map[BlockHash, Int], Option[Message]] =
        StateT
          .modify[F, Map[BlockHash, Int]](_ |+| Map(blockHash -> 1))
          .flatMapF(_ => D.lookup(blockHash))

      // We're not interested in these
      override def children(blockHash: BlockHash): StateT[F, Map[BlockHash, Int], Set[BlockHash]] =
        ???

      override def getMainChildren(
          blockHash: io.casperlabs.storage.BlockHash
      ): StateT[F, Map[BlockHash, Int], Set[BlockHash]] =
        ???

      /** Return blocks that having a specify justification */
      override def justificationToBlocks(
          blockHash: BlockHash
      ): StateT[F, Map[BlockHash, Int], Set[BlockHash]] = ???

      override def contains(blockHash: BlockHash): StateT[F, Map[BlockHash, Int], Boolean] = ???

      /** Return block summaries with ranks in the DAG between start and end, inclusive. */
      override def topoSort(
          startBlockNumber: Long,
          endBlockNumber: Long
      ): fs2.Stream[StateT[F, Map[BlockHash, Int], *], Vector[BlockInfo]] = ???

      /** Return block summaries with ranks of blocks in the DAG from a start index to the end. */
      override def topoSort(
          startBlockNumber: Long
      ): fs2.Stream[StateT[F, Map[BlockHash, Int], *], Vector[BlockInfo]] = ???

      override def topoSortTail(
          tailLength: Int
      ): fs2.Stream[StateT[F, Map[BlockHash, Int], *], Vector[BlockInfo]] = ???

      override def getBlockInfosByValidator(
          validator: Validator,
          limit: Int,
          lastTimeStamp: Long,
          lastBlockHash: BlockHash
      ) = ???

      override def latestGlobal: StateT[F, Map[BlockHash, Int], GlobalTipRepresentation[
        StateT[F, Map[BlockHash, Int], *]
      ]] =
        ???
      override def latestInEra(
          keyBlockHash: BlockHash
      ): StateT[F, Map[BlockHash, Int], EraTipRepresentation[StateT[F, Map[BlockHash, Int], *]]] =
        ???

    }
}
