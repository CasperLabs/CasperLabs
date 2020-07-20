package io.casperlabs.casper.finality

import java.util.concurrent.atomic.AtomicReference

import cats.{Functor, Monad}
import cats.data.NonEmptyList
import cats.effect.Concurrent
import cats.effect.concurrent.Ref
import io.casperlabs.catscontrib.{Fs2Compiler, MonadThrowable}
import com.google.protobuf.ByteString
import io.casperlabs.crypto.Keys
import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper.PrettyPrinter
import io.casperlabs.casper.consensus.Block
import io.casperlabs.casper.finality.MultiParentFinalizer.FinalizedBlocks
import io.casperlabs.casper.finality.votingmatrix.FinalityDetectorVotingMatrix
import io.casperlabs.casper.helper.BlockUtil.generateValidator
import io.casperlabs.casper.helper.{BlockGenerator, StorageFixture}
import io.casperlabs.casper.util.BondingUtil.Bond
import io.casperlabs.casper.util.{ByteStringPrettifier, ProtoUtil}
import io.casperlabs.models.Message
import io.casperlabs.shared.{LogStub, Time}
import io.casperlabs.storage.block.BlockStorage
import io.casperlabs.storage.dag.{DagRepresentation, DagStorage}
import monix.eval.Task
import org.scalatest.FlatSpec
import io.casperlabs.casper.mocks.MockFinalityStorage

class MultiParentFinalizerTest
    extends FlatSpec
    with BlockGenerator
    with ByteStringPrettifier
    with StorageFixture {

  behavior of "MultiParentFinalizer"

  import MultiParentFinalizerTest._

  // Who creates what doesn't matter in these tests.
  val v1     = generateValidator("A")
  val v2     = generateValidator("Z")
  val v1Bond = Bond(v1, 3)
  val v2Bond = Bond(v2, 3)
  val bonds  = Seq(v1Bond, v2Bond)

  it should "cache block finalization so it doesn't revisit already finalized blocks." in withCombinedStorage() {
    implicit storage =>
      for {
        genesis                                  <- createAndStoreMessage[Task](Seq(), EmptyValidator, bonds)
        implicit0(fs: MockFinalityStorage[Task]) <- MockFinalityStorage[Task](genesis.blockHash)
        dag                                      <- storage.getRepresentation
        implicit0(multiParentFinalizer: MultiParentFinalizer[Task]) <- MultiParentFinalizer
                                                                        .create[Task](
                                                                          dag,
                                                                          genesis.blockHash,
                                                                          MultiParentFinalizerTest.immediateFinalityStub
                                                                        )(
                                                                          Concurrent[Task],
                                                                          fs,
                                                                          metrics
                                                                        )
        a0                         <- createAndStoreBlockFull[Task](v1, Seq(genesis), Seq.empty, bonds)
        a1                         <- createAndStoreBlockFull[Task](v1, Seq(a0), Seq.empty, bonds)
        b0                         <- createAndStoreBlockFull[Task](v2, Seq(genesis, a0), Seq(a1), bonds)
        b0Msg                      <- Task.fromTry(Message.fromBlock(b0))
        Seq(newlyFinalizedBlocks0) <- onNewMessageAdded[Task](b0Msg)
        // `b0` is in main chain, `a0` is secondary parent.
        _ = assert(newlyFinalizedBlocks0.newLFB == b0.blockHash)
        _ = assert(
          newlyFinalizedBlocks0.indirectlyFinalized.map(_.messageHash) == Set(
            a0.blockHash
          )
        )
        _ = assert(
          newlyFinalizedBlocks0.indirectlyOrphaned
            .map(_.messageHash) == Set(a1.blockHash)
        )
        _ <- fs.markAsFinalized(
              newlyFinalizedBlocks0.newLFB,
              newlyFinalizedBlocks0.indirectlyFinalized.map(_.messageHash),
              newlyFinalizedBlocks0.indirectlyOrphaned.map(_.messageHash)
            )
        a2    <- createAndStoreBlockFull[Task](v1, Seq(b0, a0), Seq(a1), bonds)
        a2Msg <- Task.fromTry(Message.fromBlock(a2))
        // `a2`'s main parent is `b0`, secondary is `a0`.
        // Since `a0` was already finalized through `b0` it should not be returned now.
        // Similarly `a1` was already orphaned by `b0`.
        Seq(newlyFinalizedBlocks1) <- onNewMessageAdded[Task](a2Msg)

        _ = assert(newlyFinalizedBlocks1.newLFB == a2.blockHash)
        _ = assert(newlyFinalizedBlocks1.indirectlyFinalized.isEmpty)
        _ = assert(newlyFinalizedBlocks1.indirectlyOrphaned.isEmpty)
      } yield ()
  }

  it should "cache LFB from the main chain; not return LFB when new block doesn't vote on LFB's child" in withCombinedStorage() {
    implicit storage =>
      implicit val noopLog = LogStub[Task]()

      /** `B` is LFB but `C` doesn't vote for any of `B`'s children (empty vote).
        * `MultiParentFinalizer` should cache LFB (`B`) and kick off new LFB calculation from it.
        * Adding `C` should not return any LFB.
        *
        *  G = A = B*
        *      \\= C
        */
      for {
        genesis                                  <- createAndStoreMessage[Task](Seq(), EmptyValidator, bonds)
        implicit0(fs: MockFinalityStorage[Task]) <- MockFinalityStorage[Task](genesis.blockHash)
        dag                                      <- storage.getRepresentation
        fft                                      = 0.1
        finalizer <- FinalityDetectorVotingMatrix
                      .of[Task](dag, genesis.blockHash, fft, isHighway = false)
        implicit0(multiParentFinalizer: MultiParentFinalizer[Task]) <- MultiParentFinalizer
                                                                        .create[Task](
                                                                          dag,
                                                                          genesis.blockHash,
                                                                          finalizer
                                                                        )(
                                                                          Concurrent[Task],
                                                                          fs,
                                                                          metrics
                                                                        )
        a        <- createAndStoreBlockFull[Task](v1, Seq(genesis), Seq.empty, bonds)
        nelBonds = NonEmptyList.fromListUnsafe(bonds.toList)
        b <- MultiParentFinalizerTest
              .finalizeBlock[Task](a.blockHash, nelBonds)
              .flatMap(ProtoUtil.unsafeGetBlock[Task](_))
        bMsg                        <- Task.fromTry(Message.fromBlock(b))
        Seq(finalizedA, finalizedB) <- onNewMessageAdded[Task](bMsg)
        _                           = assert(finalizedA.newLFB == a.blockHash && finalizedA.indirectlyFinalized.isEmpty)

        // `aPrime` is sibiling of `a`, another child of Genesis.
        // Since `a` has already been finalized `aPrime` should never become chosen as new LFB.
        // We are testing whether `MultiParentFinalizer` caches new LFB properly.
        aPrime <- createAndStoreBlockFull[Task](v2, Seq(genesis), Seq.empty, bonds)
        bPrime <- MultiParentFinalizerTest
                   .finalizeBlock[Task](aPrime.blockHash, nelBonds)
                   .flatMap(ProtoUtil.unsafeGetBlock[Task](_))
        bPrimeMsg  <- Task.fromTry(Message.fromBlock(bPrime))
        finalizedB <- onNewMessageAdded[Task](bPrimeMsg)
        _          = assert(finalizedB.isEmpty)
      } yield ()
  }
}

object MultiParentFinalizerTest extends BlockGenerator {
  import cats.implicits._

  /** Assembles a String that represents a DAG structure.
    * One block per line in the form of: {rank} {main parent hash} {block hash} {validator id}.
    */
  def printDagTopoSort[F[_]: Fs2Compiler: Functor](dag: DagRepresentation[F]): F[String] =
    dag
      .topoSort(startBlockNumber = 0) // Start from Genesis.
      .compile
      .toVector
      .map(_.flatten)
      .map(
        _.map(
          blockInfo =>
            s"${blockInfo.getSummary.getHeader.jRank}:\t${PrettyPrinter.buildString(
              blockInfo.getSummary.getHeader.parentHashes.headOption.getOrElse(ByteString.EMPTY)
            )} <- ${PrettyPrinter
              .buildString(blockInfo.getSummary.blockHash)} : ${PrettyPrinter
              .buildString(blockInfo.getSummary.getHeader.validatorPublicKeyHash)}"
        ).mkString("\n")
      )

  /** Creates a chain of blocks that build on top of each other.
    * Starts with `start` block hash.
    *
    * Returns last block hash in chain.
    */
  def createChainOfBlocks[F[_]: MonadThrowable: Time: BlockStorage: DagStorage](
      start: BlockHash,
      bonds: NonEmptyList[Bond]
  ): F[List[BlockHash]] =
    bonds.map(_.validatorPublicKeyHash).toList.foldLeftM(start :: Nil) {
      case (chain, validatorId) =>
        for {
          block <- createAndStoreMessage[F](
                    Seq(chain.head),
                    Keys.PublicKeyHash(validatorId),
                    bonds.toList
                  )
        } yield block.blockHash :: chain
    }

  def onNewMessageAdded[F[_]: Monad: MultiParentFinalizer](
      m: Message
  ): F[Seq[FinalizedBlocks]] =
    MultiParentFinalizer[F].addMessage(m) *> MultiParentFinalizer[F].checkFinality()

  /** Finalizes a `start` block.
    *
    * To finalize a block, we need level-1 summit (i.e. enough validators has to see enough validators voting for a block).
    * The easiest way to achieve this is to add a layer of messages that build on top of block we want to finalize.
    *
    * Returns last block hash in chain.
    */
  def finalizeBlock[F[_]: MonadThrowable: Time: BlockStorage: DagStorage: MultiParentFinalizer](
      start: BlockHash,
      bonds: NonEmptyList[Bond]
  ): F[BlockHash] =
    for {
      chain <- createChainOfBlocks[F](start, bonds) // Create level-0 summit
      // Update Finalizer to know about how DAG advanced.
      _ <- chain.traverse_(
            hash =>
              for {
                block <- ProtoUtil.unsafeGetBlock[F](hash)
                msg   <- MonadThrowable[F].fromTry(Message.fromBlock(block))
                res   <- onNewMessageAdded[F](msg)
              } yield res
          )
      b <- createAndStoreMessage[F](
            Seq(chain.head),
            Keys.PublicKeyHash(bonds.head.validatorPublicKeyHash)
          ) // Create level-1 summit
    } yield b.blockHash

  // Finalizes block it receives as argument.
  val immediateFinalityStub = new FinalityDetector[Task] {

    private val prevMsg: AtomicReference[Message] = new AtomicReference()

    override def addMessage(
        dag: DagRepresentation[Task],
        message: Message,
        latestFinalizedBlock: BlockHash
    ): Task[Unit] =
      Task(prevMsg.set(message))

    override def checkFinality(
        dag: DagRepresentation[Task]
    ): Task[Seq[CommitteeWithConsensusValue]] =
      Task(Seq(CommitteeWithConsensusValue(Set.empty, 1L, prevMsg.get().messageHash)))
  }
}
