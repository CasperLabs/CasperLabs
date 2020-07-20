package io.casperlabs.casper.dag

import cats.data.NonEmptyList
import cats.implicits._
import cats.{Id, Monad}
import com.github.ghik.silencer.silent
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.Block
import io.casperlabs.casper.helper.BlockGenerator._
import io.casperlabs.casper.helper.BlockUtil.generateValidator
import io.casperlabs.casper.helper.{BlockGenerator, StorageFixture}
import io.casperlabs.casper.scalatestcontrib._
import io.casperlabs.casper.util.BondingUtil.Bond
import io.casperlabs.casper.util.ByteStringPrettifier
import io.casperlabs.models.Message
import io.casperlabs.shared.Sorting.messageSummaryOrdering
import io.casperlabs.storage.dag.DagRepresentation
import monix.eval.Task
import org.scalatest.{FlatSpec, Matchers}
import scala.concurrent.duration._
import scala.collection.immutable.BitSet
import io.casperlabs.casper.consensus.Signature
import org.scalacheck.Arbitrary
import io.casperlabs.storage.ArbitraryStorageData
import io.casperlabs.models.ArbitraryConsensus
import io.casperlabs.storage.BlockMsgWithTransform
import io.casperlabs.storage.dag.AncestorsStorage.Relation

@silent("deprecated")
@silent("is never used")
class DagOperationsTest
    extends FlatSpec
    with Matchers
    with BlockGenerator
    with StorageFixture
    with ArbitraryConsensus
    with ArbitraryStorageData {

  "bfTraverseF" should "lazily breadth-first traverse a DAG with effectful neighbours" in {
    implicit val intKey = DagOperations.Key.identity[Int]
    val stream          = DagOperations.bfTraverseF[Id, Int](List(1))(i => List(i * 2, i * 3))
    stream.take(10).toList shouldBe List(1, 2, 3, 4, 6, 9, 8, 12, 18, 27)
  }

  "bfToposortTraverseF" should "lazily breadth-first and order by rank when traverse a DAG with effectful neighbours" in withCombinedStorage() {
    implicit storage =>
      /*
       * DAG Looks like this:
       *
       *       b6      b7
       *       |  \ /  |
       *       |  b4   b5
       *       |     \ |
       *       b2      b3
       *         \  /
       *          b1
       *           |
       *         genesis
       */
      val v1 = generateValidator("v1")
      val v2 = generateValidator("v2")
      val v3 = generateValidator("v3")

      for {
        genesis <- createAndStoreMessage[Task](Seq.empty)
        b1      <- createAndStoreMessage[Task](Seq(genesis.blockHash), v2)
        b2      <- createAndStoreMessage[Task](Seq(b1.blockHash), v1)
        b3      <- createAndStoreMessage[Task](Seq(b1.blockHash), v3)
        b4      <- createAndStoreMessage[Task](Seq(b3.blockHash), v2)
        b5      <- createAndStoreMessage[Task](Seq(b3.blockHash), v3)
        b6      <- createAndStoreMessage[Task](Seq(b2.blockHash, b4.blockHash), v1)
        b7      <- createAndStoreMessage[Task](Seq(b4.blockHash, b5.blockHash), v3)

        dag                <- storage.getRepresentation
        dagTopoOrderingAsc = DagOperations.blockTopoOrderingAsc
        stream = DagOperations.bfToposortTraverseF[Task](Message.fromBlock(genesis).toList) { b =>
          dag
            .children(b.messageHash)
            .flatMap(_.toList.traverse(l => dag.lookup(l).map(_.get)))
        }(Monad[Task], dagTopoOrderingAsc)
        _                   <- stream.toList.map(_.map(_.jRank) shouldBe List(0, 1, 2, 2, 3, 3, 4, 4))
        dagTopoOrderingDesc = DagOperations.blockTopoOrderingDesc
        stream2 = DagOperations
          .bfToposortTraverseF[Task](
            Message.fromBlock(b6).toList ++ Message.fromBlock(b7).toList
          ) { b =>
            b.parents.toList.traverse(l => dag.lookup(l).map(_.get))
          }(Monad[Task], dagTopoOrderingDesc)
        _ <- stream2.toList.map(_.map(_.jRank) shouldBe List(4, 4, 3, 3, 2, 2, 1, 0))
      } yield ()
  }

  "Greatest common ancestor" should "be computed properly" in withCombinedStorage() {
    implicit storage =>
      /*
       * DAG Looks like this:
       *
       *        b6   b7
       *       |  \ /  \
       *       |   b4  b5
       *       |    \ /
       *       b2    b3
       *         \  /
       *          b1
       *           |
       *         genesis
       */
      for {
        genesis <- createAndStoreMessage[Task](Seq.empty)
        b1      <- createAndStoreMessage[Task](Seq(genesis.blockHash))
        b2      <- createAndStoreMessage[Task](Seq(b1.blockHash))
        b3      <- createAndStoreMessage[Task](Seq(b1.blockHash))
        b4      <- createAndStoreMessage[Task](Seq(b3.blockHash))
        b5      <- createAndStoreMessage[Task](Seq(b3.blockHash))
        b6      <- createAndStoreMessage[Task](Seq(b2.blockHash, b4.blockHash))
        b7      <- createAndStoreMessage[Task](Seq(b4.blockHash, b5.blockHash))

        dag <- storage.getRepresentation

        _      <- DagOperations.greatestCommonAncestorF[Task](b1, b5, genesis, dag) shouldBeF b1
        _      <- DagOperations.greatestCommonAncestorF[Task](b3, b2, genesis, dag) shouldBeF b1
        _      <- DagOperations.greatestCommonAncestorF[Task](b6, b7, genesis, dag) shouldBeF b1
        _      <- DagOperations.greatestCommonAncestorF[Task](b2, b2, genesis, dag) shouldBeF b2
        result <- DagOperations.greatestCommonAncestorF[Task](b3, b7, genesis, dag) shouldBeF b3
      } yield result
  }

  behavior of "Latest Common Ancestor"

  val v1 = generateValidator("One")
  val v2 = generateValidator("Two")
  val v3 = generateValidator("Three")

  it should "be computed properly for various j-DAGs (1)" in withCombinedStorage() {
    implicit storage =>
      /* 1) DAG looks like this:
       *
       * b1  b2  b3
       *  \  |  /
       *  genesis
       *
       */
      for {
        genesis        <- createAndStoreMessage[Task](Seq.empty)
        b1             <- createAndStoreMessage[Task](Seq(genesis.blockHash), v1)
        b2             <- createAndStoreMessage[Task](Seq(genesis.blockHash), v2)
        b3             <- createAndStoreMessage[Task](Seq(genesis.blockHash), v3)
        dag            <- storage.getRepresentation
        latestMessages <- dag.latestMessageHashes
        lca <- DagOperations.latestCommonAncestorsMainParent(
                dag,
                NonEmptyList.fromListUnsafe(latestMessages.values.flatten.toList)
              )
      } yield assert(lca.messageHash == genesis.blockHash)
  }

  it should "be computed properly for various j-DAGs (2)" in withCombinedStorage() {
    implicit storage =>
      /* 2) DAG looks like this:
       *         b2
       *      /  |
       *     b1  |
       *     |  /
       *  genesis
       *
       */
      for {
        genesis        <- createAndStoreMessage[Task](Seq.empty)
        _              <- createAndStoreMessage[Task](Seq(genesis.blockHash), v1)
        _              <- createAndStoreMessage[Task](Seq(genesis.blockHash), v2)
        dag            <- storage.getRepresentation
        latestMessages <- dag.latestMessageHashes
        lca <- DagOperations.latestCommonAncestorsMainParent(
                dag,
                NonEmptyList.fromListUnsafe(latestMessages.values.flatten.toList)
              )
      } yield assert(lca.messageHash == genesis.blockHash)
  }

  it should "be computed properly for various j-DAGs (3)" in withCombinedStorage() {
    implicit storage =>
      /* 3) DAG looks like this:
       * v1  v2  v3
       *
       *         b6
       *         |
       *     b4  b5
       *       \ |
       * b1  b2  b3
       *  \  |  /
       *  genesis
       *
       */

      for {
        genesis        <- createAndStoreMessage[Task](Seq.empty)
        _              <- createAndStoreMessage[Task](Seq(genesis.blockHash), v1)
        _              <- createAndStoreMessage[Task](Seq(genesis.blockHash), v2)
        b3             <- createAndStoreMessage[Task](Seq(genesis.blockHash), v3)
        _              <- createAndStoreMessage[Task](Seq(b3.blockHash), v2)
        b5             <- createAndStoreMessage[Task](Seq(b3.blockHash), v3)
        _              <- createAndStoreMessage[Task](Seq(b5.blockHash), v3)
        dag            <- storage.getRepresentation
        latestMessages <- dag.latestMessageHashes
        lca <- DagOperations.latestCommonAncestorsMainParent(
                dag,
                NonEmptyList.fromListUnsafe(latestMessages.values.flatten.toList)
              )
      } yield assert(lca.messageHash == genesis.blockHash)
  }

  it should "be computed properly for various j-DAGs (4)" in withCombinedStorage() {
    implicit storage =>
      /* 4) DAG looks like this:
       * v1  v2  v3
       *
       *         b6
       *         |
       *     b4  b5
       *   /     |
       * b1  b2  b3
       *  \  |  /
       *  genesis
       *
       */

      for {
        genesis        <- createAndStoreMessage[Task](Seq.empty)
        b1             <- createAndStoreMessage[Task](Seq(genesis.blockHash), v1)
        _              <- createAndStoreMessage[Task](Seq(genesis.blockHash), v2)
        b3             <- createAndStoreMessage[Task](Seq(genesis.blockHash), v3)
        _              <- createAndStoreMessage[Task](Seq(b1.blockHash), v2)
        b5             <- createAndStoreMessage[Task](Seq(b3.blockHash), v3)
        _              <- createAndStoreMessage[Task](Seq(b5.blockHash), v3)
        dag            <- storage.getRepresentation
        latestMessages <- dag.latestMessageHashes
        lca <- DagOperations.latestCommonAncestorsMainParent(
                dag,
                NonEmptyList.fromListUnsafe(latestMessages.values.flatten.toList)
              )
      } yield assert(lca.messageHash == genesis.blockHash)
  }

  it should "be computed properly for various j-DAGs (5)" in withCombinedStorage() {
    implicit storage =>
      /* 5) DAG looks like this:
       *  b6     b7
       *    \  /
       *     b4  b5
       *    |    |
       * b1  b2  b3
       *  \  |  /
       *  genesis
       *
       */

      for {
        genesis        <- createAndStoreMessage[Task](Seq.empty)
        _              <- createAndStoreMessage[Task](Seq(genesis.blockHash), v1)
        b2             <- createAndStoreMessage[Task](Seq(genesis.blockHash), v2)
        b3             <- createAndStoreMessage[Task](Seq(genesis.blockHash), v3)
        b4             <- createAndStoreMessage[Task](Seq(b2.blockHash), v2)
        _              <- createAndStoreMessage[Task](Seq(b3.blockHash), v3)
        _              <- createAndStoreMessage[Task](Seq(b4.blockHash), v1)
        _              <- createAndStoreMessage[Task](Seq(b4.blockHash), v3)
        dag            <- storage.getRepresentation
        latestMessages <- storage.latestMessageHashes
        lca <- DagOperations.latestCommonAncestorsMainParent(
                dag,
                NonEmptyList.fromListUnsafe(latestMessages.values.flatten.toList)
              )
      } yield assert(lca.messageHash == genesis.blockHash)
  }

  it should "be computed properly for various j-DAGs (6)" in withCombinedStorage() {
    implicit storage =>
      /* 6) DAG looks like:
       *
       *          m
       *            \
       *       j  k  l
       *      /  /   |
       *     g  h   i
       *      \ |  /
       *        f
       *      /
       *     d  e
       *    |    \
       *    a   b  c
       *     \  | /
       *     genesis
       *
       */

      for {
        genesis      <- createAndStoreMessage[Task](Seq(), EmptyValidator)
        a            <- createAndStoreMessage[Task](Seq(genesis.blockHash), v1)
        b            <- createAndStoreMessage[Task](Seq(genesis.blockHash), v2)
        c            <- createAndStoreMessage[Task](Seq(genesis.blockHash), v3)
        d            <- createAndStoreMessage[Task](Seq(a.blockHash), v1, Seq.empty)
        e            <- createAndStoreMessage[Task](Seq(c.blockHash), v2, Seq.empty, Map(v2 -> b.blockHash))
        f            <- createAndStoreMessage[Task](Seq(d.blockHash), v2, Seq.empty, Map(v2 -> e.blockHash))
        g            <- createAndStoreMessage[Task](Seq(f.blockHash), v1, Seq.empty, Map(v1 -> d.blockHash))
        h            <- createAndStoreMessage[Task](Seq(f.blockHash), v2, Seq.empty)
        i            <- createAndStoreMessage[Task](Seq(f.blockHash), v3, Seq.empty, Map(v3 -> c.blockHash))
        j            <- createAndStoreMessage[Task](Seq(g.blockHash), v1, Seq.empty)
        k            <- createAndStoreMessage[Task](Seq(h.blockHash), v2, Seq.empty)
        l            <- createAndStoreMessage[Task](Seq(i.blockHash), v3, Seq.empty)
        m            <- createAndStoreMessage[Task](Seq(l.blockHash), v2, Seq.empty, Map(v2 -> k.blockHash))
        dag          <- storage.getRepresentation
        latestBlocks <- dag.latestMessageHashes
        lca <- DagOperations.latestCommonAncestorsMainParent(
                dag,
                NonEmptyList.fromListUnsafe(latestBlocks.values.flatten.toList)
              )
      } yield assert(lca.messageHash == f.blockHash)
  }

  "uncommon ancestors" should "be computed properly" in withCombinedStorage() { implicit storage =>
    /*
     *  DAG Looks like this:
     *
     * rank
     *  4        b6   b7
     *          |  \ / |
     *  3       b4  b5 |
     *            \ |  |
     *  2           b3 |
     *              |  |
     *  1          b1  b2
     *              |  /
     *  0         genesis
     */
    implicit def toMessageSummary: Block => Message = Message.fromBlock(_).get
    for {
      genesis <- createAndStoreMessage[Task](Seq.empty)
      b1      <- createAndStoreMessage[Task](Seq(genesis.blockHash))
      b2      <- createAndStoreMessage[Task](Seq(genesis.blockHash))
      b3      <- createAndStoreMessage[Task](Seq(b1.blockHash))
      b4      <- createAndStoreMessage[Task](Seq(b3.blockHash))
      b5      <- createAndStoreMessage[Task](Seq(b3.blockHash))
      b6      <- createAndStoreMessage[Task](Seq(b4.blockHash, b5.blockHash))
      b7      <- createAndStoreMessage[Task](Seq(b2.blockHash, b5.blockHash))

      dag <- storage.getRepresentation

      _ <- DagOperations.uncommonAncestors[Task](Vector(b6, b7), dag) shouldBeF Map(
            toMessageSummary(b6) -> BitSet(0),
            toMessageSummary(b4) -> BitSet(0),
            toMessageSummary(b7) -> BitSet(1),
            toMessageSummary(b2) -> BitSet(1)
          )

      _ <- DagOperations.uncommonAncestors[Task](Vector(b6, b3), dag) shouldBeF Map(
            toMessageSummary(b6) -> BitSet(0),
            toMessageSummary(b4) -> BitSet(0),
            toMessageSummary(b5) -> BitSet(0)
          )

      _ <- DagOperations.uncommonAncestors[Task](Vector(b2, b4, b5), dag) shouldBeF Map(
            toMessageSummary(b2) -> BitSet(0),
            toMessageSummary(b4) -> BitSet(1),
            toMessageSummary(b5) -> BitSet(2),
            toMessageSummary(b3) -> BitSet(1, 2),
            toMessageSummary(b1) -> BitSet(1, 2)
          )

      result <- DagOperations.uncommonAncestors[Task](Vector(b1), dag) shouldBeF Map
                 .empty[Message, BitSet]
    } yield result
  }

  "anyDescendantPathExists" should
    "return whether there is a path from any of the possible ancestor blocks to any of the potential descendants" in withCombinedStorage() {
    implicit storage =>
      def anyDescendantPathExists(
          dag: DagRepresentation[Task],
          start: Set[Block],
          targets: Set[Block]
      ) =
        DagOperations.anyDescendantPathExists[Task](
          dag,
          start.map(_.blockHash),
          targets.map(_.blockHash)
        )
      /*
       * DAG Looks like this:
       *
       *        b6   b7
       *       |  \ /  \
       *       |   b4  b5
       *       |    \ /
       *       b2    b3
       *         \  /
       *          b1
       *           |
       *         genesis
       */
      for {
        genesis <- createAndStoreMessage[Task](Seq.empty)
        b1      <- createAndStoreMessage[Task](Seq(genesis.blockHash))
        b2      <- createAndStoreMessage[Task](Seq(b1.blockHash))
        b3      <- createAndStoreMessage[Task](Seq(b1.blockHash))
        b4      <- createAndStoreMessage[Task](Seq(b3.blockHash))
        b5      <- createAndStoreMessage[Task](Seq(b3.blockHash))
        b6      <- createAndStoreMessage[Task](Seq(b2.blockHash, b4.blockHash))
        b7      <- createAndStoreMessage[Task](Seq(b4.blockHash, b5.blockHash))
        dag     <- storage.getRepresentation
        // self
        _ <- anyDescendantPathExists(dag, Set(genesis), Set(genesis)) shouldBeF true
        // any descendant
        _ <- anyDescendantPathExists(dag, Set(b3), Set(b2, b7)) shouldBeF true
        // any ancestor
        _ <- anyDescendantPathExists(dag, Set(b2, b3), Set(b5)) shouldBeF true
        // main parent
        _ <- anyDescendantPathExists(dag, Set(b4), Set(b7)) shouldBeF true
        // secondary parent
        _ <- anyDescendantPathExists(dag, Set(b5), Set(b7)) shouldBeF true
        // not to ancestor
        _ <- anyDescendantPathExists(dag, Set(b2, b4), Set(b1)) shouldBeF false
        // not to sibling
        _ <- anyDescendantPathExists(dag, Set(b2), Set(b3)) shouldBeF false
      } yield ()
  }

  "collectWhereDescendantPathExists" should
    "return from the possible ancestor blocks the ones which have a path to any of the potential descendants" in withCombinedStorage() {
    implicit storage =>
      def collect(
          dag: DagRepresentation[Task],
          start: Set[Block],
          targets: Set[Block]
      ) =
        DagOperations.collectWhereDescendantPathExists[Task](
          dag,
          start.map(_.blockHash),
          targets.map(_.blockHash)
        )
      /*
       * DAG Looks like this:
       *
       *        b6   b7
       *       |  \ /  \
       *       |   b4  b5
       *       |    \ /
       *       b2    b3
       *         \  /
       *          b1
       *           |
       *         genesis
       */
      for {
        genesis <- createAndStoreMessage[Task](Seq.empty)
        b1      <- createAndStoreMessage[Task](Seq(genesis.blockHash))
        b2      <- createAndStoreMessage[Task](Seq(b1.blockHash))
        b3      <- createAndStoreMessage[Task](Seq(b1.blockHash))
        b4      <- createAndStoreMessage[Task](Seq(b3.blockHash))
        b5      <- createAndStoreMessage[Task](Seq(b3.blockHash))
        b6      <- createAndStoreMessage[Task](Seq(b2.blockHash, b4.blockHash))
        b7      <- createAndStoreMessage[Task](Seq(b4.blockHash, b5.blockHash))
        dag     <- storage.getRepresentation
        // self
        _ <- collect(dag, Set(genesis), Set(genesis)) shouldBeF Set(genesis.blockHash)
        // any descendant
        _ <- collect(dag, Set(b3), Set(b2, b7)) shouldBeF Set(b3.blockHash)
        // any ancestor
        _ <- collect(dag, Set(b2, b3), Set(b5)) shouldBeF Set(b3.blockHash)
        _ <- collect(dag, Set(b1, b3), Set(b5)) shouldBeF Set(b1.blockHash, b3.blockHash)
        // main parent
        _ <- collect(dag, Set(b4), Set(b7)) shouldBeF Set(b4.blockHash)
        // secondary parent
        _ <- collect(dag, Set(b5), Set(b7)) shouldBeF Set(b5.blockHash)
        // not to ancestor
        _ <- collect(dag, Set(b2, b4), Set(b1)) shouldBeF Set.empty
        // not to sibling
        _ <- collect(dag, Set(b2), Set(b3)) shouldBeF Set.empty
      } yield ()
  }

  "swimlaneV" should "return correct stream of blocks even if they are referenced indirectly" in withCombinedStorage() {
    implicit storage =>
      val v1    = generateValidator("v1")
      val v2    = generateValidator("v2")
      val bonds = Seq(Bond(v1, 10), Bond(v2, 10))

      for {
        genesis <- createAndStoreMessage[Task](Seq.empty)
        b1      <- createAndStoreMessage[Task](Seq(genesis.blockHash), v1, bonds)
        b2      <- createAndStoreMessage[Task](Seq(b1.blockHash), v2, bonds, Map(v1 -> b1.blockHash))
        b3      <- createAndStoreMessage[Task](Seq(b2.blockHash), v1, bonds, Map(v2 -> b2.blockHash))
        dag     <- storage.getRepresentation
        message <- Task.fromTry(Message.fromBlock(b3))
        _ <- DagOperations
              .swimlaneV[Task](v1, message, dag)
              .map(_.messageHash)
              .toList shouldBeF List(b3.blockHash, b1.blockHash)
        _ <- DagOperations
              .swimlaneVFromJustifications[Task](v1, List(Message.fromBlock(b3).get), dag)
              .map(_.messageHash)
              .toList shouldBeF List(b3.blockHash, b1.blockHash)
      } yield ()
  }

  import ByteStringPrettifier._
  val v1Bond     = Bond(v1, 2)
  val v2Bond     = Bond(v2, 3)
  val v3Bond     = Bond(v2, 3)
  val bondsThree = List(v1Bond, v2Bond, v3Bond)

  val genesisValidator = EmptyValidator
  val genesisEra       = ByteString.EMPTY

  "panoramaOfBlockByValidators" should "return latest message per validator within single era" in withCombinedStorage() {
    implicit storage =>
      // Validators A, B and C
      // All messages within single era.
      //
      //    a1
      //   / \(justification)
      // G - b1   b2
      //        \/
      //        c1 (sees a1)
      //
      // Starting point: b2
      // Expected result: Map(A -> a1, B -> b2, C -> c1)

      for {
        genesis <- createAndStoreMessage[Task](Seq(), EmptyValidator, bondsThree)
        a1 <- createAndStoreBlockFull[Task](
               v1,
               Seq(genesis),
               Seq.empty,
               bondsThree,
               keyBlockHash = genesis.blockHash
             )
        b1 <- createAndStoreBlockFull[Task](
               v2,
               Seq(genesis),
               Seq(a1),
               bondsThree,
               keyBlockHash = genesis.blockHash
             )
        c1 <- createAndStoreBlockFull[Task](
               v3,
               Seq(b1),
               Seq.empty,
               bondsThree,
               keyBlockHash = genesis.blockHash
             )
        b2 <- createAndStoreBlockFull[Task](
               v2,
               Seq(c1),
               Seq.empty,
               bondsThree,
               keyBlockHash = genesis.blockHash
             )
        implicit0(dag: DagRepresentation[Task]) <- storage.getRepresentation
        b2Message                               = Message.fromBlock(b2).get
        genesisMessage                          = Message.fromBlock(genesis).get
        localDagView = EraObservedBehavior.local(
          Map(
            genesisEra -> Map(
              genesisValidator -> Set(genesisMessage)
            ),
            genesis.blockHash -> Map(
              v1 -> Set(Message.fromBlock(a1).get),
              v2 -> Set(Message.fromBlock(b1).get),
              v3 -> Set(Message.fromBlock(c1).get)
            )
          )
        )
        latestMessages <- DagOperations.panoramaOfMessage[Task](
                           dag,
                           b2Message,
                           localDagView,
                           usingIndirectJustifications = true
                         )
        latestGenesisMessageHashes = latestMessages
          .latestMessagesInEra(genesis.blockHash)
          .mapValues(_.map(_.messageHash))
        expected = Map(
          v1 -> Set(a1.blockHash),
          v2 -> Set(b1.blockHash),
          v3 -> Set(c1.blockHash)
        )
      } yield assert(latestGenesisMessageHashes == expected)
  }

  it should "return latest message per validator across multiple eras" in withCombinedStorage() {
    implicit storage =>
      // Validators A, B and C
      //                      |
      //    a1                |      a2
      //   / \(justification) |     / \
      // G - b1   b2 -------- |--S_b   \
      //        \/            |     \   \
      //        c1 (sees a1)  |      c2-c3
      //
      // Starting point: c3
      // Expected result: Map(A -> a2, B -> S_b, C -> c2)

      for {
        genesis <- createAndStoreMessage[Task](Seq(), EmptyValidator, bondsThree)
        a1 <- createAndStoreBlockFull[Task](
               v1,
               Seq(genesis),
               Seq.empty,
               bondsThree,
               keyBlockHash = genesis.blockHash
             )
        b1 <- createAndStoreBlockFull[Task](
               v2,
               Seq(genesis),
               Seq(a1),
               bondsThree,
               keyBlockHash = genesis.blockHash
             )
        c1 <- createAndStoreBlockFull[Task](
               v3,
               Seq(b1),
               Seq.empty,
               bondsThree,
               keyBlockHash = genesis.blockHash
             )
        b2 <- createAndStoreBlockFull[Task](
               v2,
               Seq(c1),
               Seq.empty,
               bondsThree,
               keyBlockHash = genesis.blockHash
             )
        sb <- createAndStoreBlockFull[Task](
               v2,
               Seq(b2),
               Seq.empty,
               bondsThree,
               keyBlockHash = genesis.blockHash
             )
        a2 <- createAndStoreBlockFull[Task](
               v1,
               Seq(sb),
               Seq.empty,
               bondsThree,
               keyBlockHash = c1.blockHash
             )
        c2 <- createAndStoreBlockFull[Task](
               v3,
               Seq(sb),
               Seq.empty,
               bondsThree,
               keyBlockHash = c1.blockHash
             )
        c3 <- createAndStoreBlockFull[Task](
               v3,
               Seq(c2),
               Seq(a2, sb),
               bondsThree,
               keyBlockHash = c1.blockHash
             )
        implicit0(dag: DagRepresentation[Task]) <- storage.getRepresentation
        c3Message                               = Message.fromBlock(c3).get
        genesisMessage                          = Message.fromBlock(genesis).get
        localDagView = EraObservedBehavior.local(
          Map(
            genesisEra -> Map(
              genesisValidator -> Set(genesisMessage)
            ),
            genesis.blockHash -> Map(
              v1 -> Set(Message.fromBlock(a1).get),
              v2 -> Set(Message.fromBlock(sb).get),
              v3 -> Set(Message.fromBlock(c1).get)
            ),
            c1.blockHash -> Map(
              v1 -> Set(Message.fromBlock(a2).get),
              v3 -> Set(Message.fromBlock(c2).get)
            )
          )
        )
        latestMessages <- DagOperations.panoramaOfMessage[Task](
                           dag,
                           c3Message,
                           localDagView,
                           usingIndirectJustifications = true
                         )
        latestGenesisMessageHashes = latestMessages
          .latestMessagesInEra(genesis.blockHash)
          .mapValues(_.map(_.messageHash))

        expectedGenesis = Map(
          v1 -> Set(a1.blockHash),
          v2 -> Set(sb.blockHash),
          v3 -> Set(c1.blockHash)
        )
        latestChildMessageHashes = latestMessages
          .latestMessagesInEra(c1.blockHash)
          .mapValues(_.map(_.messageHash))

        expectedChild = Map(
          v1 -> Set(a2.blockHash),
          v3 -> Set(c2.blockHash)
        )
      } yield {
        assert(latestGenesisMessageHashes == expectedGenesis)
        assert(latestChildMessageHashes == expectedChild)
      }
  }

  it should "detect equivocators" in withCombinedStorage() { implicit storage =>
    //    a1
    //   /   \
    // G -a1'-b2
    //   \   /
    //    b1
    //
    // Note there's no message for C validator
    for {
      genesis <- createAndStoreMessage[Task](Seq(), EmptyValidator, bondsThree)
      a1 <- createAndStoreBlockFull[Task](
             v1,
             Seq(genesis),
             Seq.empty,
             bondsThree,
             keyBlockHash = genesis.blockHash
           )
      a1Prime <- createAndStoreBlockFull[Task](
                  v1,
                  Seq(genesis),
                  Seq.empty,
                  bondsThree,
                  keyBlockHash = genesis.blockHash
                )
      b1 <- createAndStoreBlockFull[Task](
             v2,
             Seq(genesis),
             Seq(a1),
             bondsThree,
             keyBlockHash = genesis.blockHash
           )
      b2 <- createAndStoreBlockFull[Task](
             v2,
             Seq(b1),
             Seq(a1, a1Prime),
             bondsThree,
             keyBlockHash = genesis.blockHash
           )
      genesisMessage = Message.fromBlock(genesis).get
      localDagView = EraObservedBehavior.local(
        Map(
          genesisEra -> Map(
            genesisValidator -> Set(genesisMessage)
          ),
          genesis.blockHash -> Map(
            v1 -> Set(Message.fromBlock(a1).get, Message.fromBlock(a1Prime).get),
            v2 -> Set(Message.fromBlock(b1).get)
          )
        )
      )
      implicit0(dag: DagRepresentation[Task]) <- storage.getRepresentation
      latestMessages <- DagOperations.panoramaOfMessage[Task](
                         dag,
                         Message.fromBlock(b2).get,
                         localDagView
                       )

      latestGenesisMessageHashes = latestMessages
        .latestMessagesInEra(genesis.blockHash)
        .mapValues(_.map(_.messageHash))

      expectedGenesis = Map(
        v1 -> Set(a1.blockHash, a1Prime.blockHash),
        v2 -> Set(b1.blockHash)
      )

      detectedEquivocators = latestMessages.equivocatorsVisibleInEras(Set(genesis.blockHash))
    } yield {
      assert(latestGenesisMessageHashes == expectedGenesis)
      assert(detectedEquivocators == Set(v1))
    }
  }

  it should "detect equivocators across multiple eras" in withCombinedStorage() {
    implicit storage =>
      //    a1 --- a2
      //  //   \  //  \
      // G = a1'-b2 == b3
      //  \\   //
      //    b1
      //
      // Note there's no message for C validator and A equivocated before stop block.
      for {
        genesis <- createAndStoreMessage[Task](Seq(), EmptyValidator, bondsThree)
        a1 <- createAndStoreBlockFull[Task](
               v1,
               Seq(genesis),
               Seq.empty,
               bondsThree,
               keyBlockHash = genesis.blockHash
             )
        a1Prime <- createAndStoreBlockFull[Task](
                    v1,
                    Seq(genesis),
                    Seq.empty,
                    bondsThree,
                    keyBlockHash = genesis.blockHash
                  )
        b1 <- createAndStoreBlockFull[Task](
               v2,
               Seq(genesis),
               Seq.empty,
               bondsThree,
               keyBlockHash = genesis.blockHash
             )
        b2 <- createAndStoreBlockFull[Task](
               v2,
               Seq(b1),
               Seq(a1, a1Prime),
               bondsThree,
               keyBlockHash = genesis.blockHash,
               maybeValidatorPrevBlockHash = Some(b1.blockHash)
             )
        a2 <- createAndStoreBlockFull[Task](
               v1,
               Seq(b2),
               Seq(a1),
               bondsThree,
               keyBlockHash = genesis.blockHash,
               maybeValidatorPrevBlockHash = Some(a1.blockHash)
             )
        b3 <- createAndStoreBlockFull[Task](
               v2,
               Seq(b2),
               Seq(a2),
               bondsThree,
               keyBlockHash = genesis.blockHash,
               maybeValidatorPrevBlockHash = Some(b2.blockHash)
             )
        genesisMessage = Message.fromBlock(genesis).get
        localDagView = EraObservedBehavior.local(
          Map(
            genesisEra -> Map(
              genesisValidator -> Set(genesisMessage)
            ),
            genesis.blockHash -> Map(
              v1 -> Set(Message.fromBlock(a2).get, Message.fromBlock(a1Prime).get),
              v2 -> Set(Message.fromBlock(b3).get)
            )
          )
        )
        implicit0(dag: DagRepresentation[Task]) <- storage.getRepresentation
        latestMessages <- DagOperations.panoramaOfMessage[Task](
                           dag,
                           Message.fromBlock(b3).get,
                           localDagView
                         )

        latestGenesisMessageHashes = latestMessages
          .latestMessagesInEra(genesis.blockHash)
          .mapValues(_.map(_.messageHash))

        expectedGenesis = Map(
          v1 -> Set(a1Prime.blockHash, a2.blockHash),
          v2 -> Set(b2.blockHash)
        )

        detectedEquivocators = latestMessages.equivocatorsVisibleInEras(Set(genesis.blockHash))
      } yield {
        assert(detectedEquivocators == Set(v1))
        assert(latestGenesisMessageHashes == expectedGenesis)
      }
  }

  it should "use only messages visible in the justifications (not local DAG)" in withCombinedStorage() {
    implicit storage =>
      // Validators A, B, C and D
      //                      |
      //    a1                |      a2
      //   / \(justification) |     / \
      // G - b1 - b2 -------- |--S_b   \
      //        \/            |     \   \
      //        c1 (sees a1)  |      c2-c3
      //
      // We're handling an incoming message D, that has the following view:
      //
      //   a1  d1
      //  / \ /
      // G - b1
      //
      // For some reason D was slow to send his messages (low round exponent).
      //
      //
      // Starting point: d1
      // Expected result: Map(B -> b1, D -> d1)
      val v4     = generateValidator("V4")
      val v4Bond = Bond(v4, 3)
      val bonds  = v4Bond +: bondsThree

      for {
        genesis <- createAndStoreMessage[Task](Seq(), EmptyValidator, bonds)
        a1 <- createAndStoreBlockFull[Task](
               v1,
               Seq(genesis),
               Seq.empty,
               bonds,
               keyBlockHash = genesis.blockHash
             )
        b1 <- createAndStoreBlockFull[Task](
               v2,
               Seq(genesis),
               Seq(a1),
               bonds,
               keyBlockHash = genesis.blockHash
             )
        c1 <- createAndStoreBlockFull[Task](
               v3,
               Seq(b1),
               Seq.empty,
               bonds,
               keyBlockHash = genesis.blockHash
             )
        b2 <- createAndStoreBlockFull[Task](
               v2,
               Seq(c1),
               Seq.empty,
               bonds,
               keyBlockHash = genesis.blockHash
             )
        sb <- createAndStoreBlockFull[Task](
               v2,
               Seq(b2),
               Seq.empty,
               bonds,
               keyBlockHash = genesis.blockHash
             )
        a2 <- createAndStoreBlockFull[Task](
               v1,
               Seq(sb),
               Seq.empty,
               bonds,
               keyBlockHash = c1.blockHash
             )
        c2 <- createAndStoreBlockFull[Task](
               v3,
               Seq(sb),
               Seq.empty,
               bonds,
               keyBlockHash = c1.blockHash
             )
        c3 <- createAndStoreBlockFull[Task](
               v3,
               Seq(c2),
               Seq(a2),
               bonds,
               keyBlockHash = c1.blockHash
             )
        d1 <- createAndStoreBlockFull[Task](
               v4,
               Seq(b1),
               Seq.empty,
               bonds,
               keyBlockHash = genesis.blockHash
             )
        implicit0(dag: DagRepresentation[Task]) <- storage.getRepresentation
        d1Message                               = Message.fromBlock(d1).get
        genesisMessage                          = Message.fromBlock(genesis).get
        localDagView = EraObservedBehavior.local(
          Map(
            genesisEra -> Map(
              genesisValidator -> Set(genesisMessage)
            ),
            genesis.blockHash -> Map(
              v1 -> Set(Message.fromBlock(a1).get),
              v2 -> Set(Message.fromBlock(sb).get),
              v3 -> Set(Message.fromBlock(c1).get)
            ),
            c1.blockHash -> Map(
              v1 -> Set(Message.fromBlock(a2).get),
              v3 -> Set(Message.fromBlock(c2).get)
            )
          )
        )

        jpastCone <- DagOperations.panoramaOfMessage[Task](
                      dag,
                      d1Message,
                      localDagView,
                      usingIndirectJustifications = true
                    )
        latestGenesisMessageHashes = jpastCone
          .latestMessagesInEra(genesis.blockHash)
          .mapValues(_.map(_.messageHash))

        expectedGenesis = Map(
          v2 -> Set(b1.blockHash),
          v1 -> Set(a1.blockHash)
        )

        latestChildMessageHashes = jpastCone
          .latestMessagesInEra(c1.blockHash)
          .mapValues(_.map(_.messageHash))

        expectedChild = Map.empty
      } yield {
        assert(latestGenesisMessageHashes == expectedGenesis)
        assert(latestChildMessageHashes == expectedChild)
      }
  }

  it should "properly detect equivocations when the equivocating pair is further in the j-past-cone" in withCombinedStorage() {
    implicit storage =>
      // Validators A, B, C and D.
      //
      //
      // Local DAG before receiving `d1`.
      //    a1
      //   / \(justification)
      // G - b1 -a2- b2
      //  \ /  \    /
      //   a1'   c1 (sees a1)
      //
      // We're handling an incoming message D, that has the following view:
      //
      //   a1        d1
      //  / \       /
      // G - b1 - a2
      //  \ /
      //   a1'
      //
      //
      // Starting point: d1
      // Expected result: Map( A -> {a2, a1'}, B -> b1, D -> d1)
      val v4     = generateValidator("V4")
      val v4Bond = Bond(v4, 3)
      val bonds  = v4Bond +: bondsThree

      for {
        genesis <- createAndStoreMessage[Task](Seq(), EmptyValidator, bonds)
        a1 <- createAndStoreBlockFull[Task](
               v1,
               Seq(genesis),
               Seq.empty,
               bonds,
               keyBlockHash = genesis.blockHash
             )
        a1Prime <- createAndStoreBlockFull[Task](
                    v1,
                    Seq(genesis),
                    Seq.empty,
                    bonds,
                    keyBlockHash = genesis.blockHash
                  )
        b1 <- createAndStoreBlockFull[Task](
               v2,
               Seq(genesis),
               Seq(a1, a1Prime),
               bonds,
               keyBlockHash = genesis.blockHash
             )
        c1 <- createAndStoreBlockFull[Task](
               v3,
               Seq(b1),
               Seq.empty,
               bonds,
               keyBlockHash = genesis.blockHash
             )
        a2 <- createAndStoreBlockFull[Task](
               v1,
               Seq(b1),
               Seq.empty,
               bonds,
               keyBlockHash = genesis.blockHash,
               maybeValidatorPrevBlockHash = Some(a1.blockHash)
             )
        b2 <- createAndStoreBlockFull[Task](
               v2,
               Seq(a2),
               Seq(c1),
               bonds,
               keyBlockHash = genesis.blockHash,
               maybeValidatorPrevBlockHash = Some(b1.blockHash)
             )
        d1 <- createAndStoreBlockFull[Task](
               v4,
               Seq(a2),
               Seq.empty,
               bonds,
               keyBlockHash = genesis.blockHash
             )
        implicit0(dag: DagRepresentation[Task]) <- storage.getRepresentation
        d1Message                               = Message.fromBlock(d1).get
        genesisMessage                          = Message.fromBlock(genesis).get
        localDagView = EraObservedBehavior.local(
          Map(
            genesisEra -> Map(
              genesisValidator -> Set(genesisMessage)
            ),
            genesis.blockHash -> Map(
              v1 -> Set(Message.fromBlock(a2).get, Message.fromBlock(a1).get),
              v2 -> Set(Message.fromBlock(b2).get),
              v3 -> Set(Message.fromBlock(c1).get)
            )
          )
        )

        jpastCone <- DagOperations.panoramaOfMessage[Task](
                      dag,
                      d1Message,
                      localDagView,
                      usingIndirectJustifications = true
                    )

        latestGenesisMessageHashes = jpastCone
          .latestMessagesInEra(genesis.blockHash)
          .mapValues(_.map(_.messageHash))

        expectedGenesis = Map(
          v1 -> Set(a2.blockHash, a1Prime.blockHash),
          v2 -> Set(b1.blockHash)
        )

        detectedEquivocators = jpastCone.equivocatorsVisibleInEras(Set(genesis.blockHash))
      } yield {
        assert(latestGenesisMessageHashes == expectedGenesis)
        assert(detectedEquivocators == Set(v1))
      }
  }

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
    b.update(
        _.header := b.getHeader
          .withParentHashes(Seq.empty)
          .withValidatorPublicKeyHash(EmptyValidator)
          .withMainRank(0)
      )
      .withSignature(Signature())
  }

  implicit class BlockOps(b: Block) {
    def withMainParent(block: Block): Block =
      b.withHeader(
        b.getHeader
          .withParentHashes(Seq(block.blockHash))
          .withMainRank(block.getHeader.mainRank + 1)
      )
  }

  "relation" should "return correct relation between blocks" in withCombinedStorage() {
    implicit storage =>
      implicit def `Block => BlockMsgWithTransforms`(b: Block): BlockMsgWithTransform =
        BlockMsgWithTransform().withBlockMessage(b)

      implicit def `Block => Message`(b: Block): Message =
        Message.fromBlock(b).get

      // A is an ancestor of B
      // A - C - D - B
      val a1 = createGenesis
      val c1 = randomMessage.withMainParent(a1)
      val d1 = randomMessage.withMainParent(c1)
      val b1 = randomMessage.withMainParent(d1)

      for {
        _ <- storage.put(a1.blockHash, a1)
        _ <- storage.put(c1.blockHash, c1)
        _ <- storage.put(d1.blockHash, d1)
        _ <- storage.put(b1.blockHash, b1)
        _ <- DagOperations.relation[Task](a1, b1) shouldBeF Some(Relation.Ancestor)
        _ <- DagOperations.relation[Task](b1, a1) shouldBeF Some(Relation.Descendant)

        // A comes before B but is not related
        // C - D - B
        //  \_A
        c3 = createGenesis
        a3 = randomMessage.withMainParent(c3)
        d3 = randomMessage.withMainParent(c3)
        b3 = randomMessage.withMainParent(d3)
        _  <- storage.put(c3.blockHash, c3)
        _  <- storage.put(a3.blockHash, a3)
        _  <- storage.put(d3.blockHash, d3)
        _  <- storage.put(b3.blockHash, b3)
        _  <- DagOperations.relation[Task](a3, b3) shouldBeF None
        _  <- DagOperations.relation[Task](b3, a3) shouldBeF None
      } yield ()
  }

}
