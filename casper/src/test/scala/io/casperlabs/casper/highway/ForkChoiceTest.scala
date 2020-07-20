package io.casperlabs.casper.highway

import cats.effect.Timer
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.{state, Bond, Era}
import io.casperlabs.casper.consensus.Block.MessageRole
import io.casperlabs.casper.util.ProtoUtil
import io.casperlabs.casper.{DeploySelection, ValidatorIdentity}
import io.casperlabs.crypto.Keys
import io.casperlabs.crypto.Keys.PublicKey
import io.casperlabs.crypto.signatures.SignatureAlgorithm.Ed25519
import io.casperlabs.mempool.DeployBuffer
import io.casperlabs.storage.dag.DagStorage
import io.casperlabs.storage.{BlockHash, SQLiteStorage}
import monix.eval.Task
import org.scalatest.FlatSpec

import scala.concurrent.duration._
import io.casperlabs.models.Message

class ForkChoiceTest extends FlatSpec with HighwayFixture {

  behavior of "fromKeyBlock"

  it should "return fork choice in the simplest case" in testFixture {
    implicit timer => implicit db =>
      new ForkChoiceTestFixture {

        // key_block - A

        override lazy val bonds: List[Bond] = makeBonds(Map(Alice -> 3))

        override def test: Task[Unit] =
          for {
            _          <- insertGenesis()
            genesisEra <- addGenesisEra()
            a          <- genesisEra.block(alice, genesisEra.keyBlockHash)
            forkChoice <- ForkChoice.create[Task].fromKeyBlock(genesisEra.keyBlockHash)
          } yield {
            assert(forkChoice.block.messageHash == a)
            assert(forkChoice.justifications.map(_.messageHash) == Set(a))
          }
      }
  }

  it should "return fork choice for a blockchain and NOT reduce justifications" in testFixture {
    implicit timer => implicit db =>
      new ForkChoiceTestFixture {

        // key_block - A - B

        override lazy val bonds: List[Bond] = makeBonds(Map(Alice -> 3, Bob -> 2))

        override def test: Task[Unit] =
          for {
            _          <- insertGenesis()
            genesisEra <- addGenesisEra()
            a          <- genesisEra.block(alice, genesisEra.keyBlockHash)
            b          <- genesisEra.block(bob, a)
            forkChoice <- ForkChoice.create[Task].fromKeyBlock(genesisEra.keyBlockHash)
          } yield {
            assert(forkChoice.block.messageHash == b)
            assert(forkChoice.justifications.map(_.messageHash) == Set(a, b))
          }
      }
  }

  it should "return a key block when there's no descendants" in testFixture {
    implicit timer => implicit db =>
      new ForkChoiceTestFixture {

        // key_block
        //
        // key block is the only block.

        override lazy val bonds: List[Bond] = makeBonds(Map(Alice -> 3, Bob -> 4))

        override def test: Task[Unit] =
          for {
            _          <- insertGenesis()
            genesisEra <- addGenesisEra()
            forkChoice <- ForkChoice.create[Task].fromKeyBlock(genesisEra.keyBlockHash)
          } yield {
            assert(forkChoice.block.messageHash == genesisEra.keyBlockHash)
            assert(forkChoice.justifications == Set.empty)
          }
      }
  }

  it should "return the parent-era voting-only ballots as justifications together with child-era blocks" in testFixture {
    implicit timer =>
      implicit db =>
        // Weights: A -> 3, B -> 4, C -> 5
        // three eras (era-1, era-2, era-3)
        // key block in era-1
        // latest messages in era-3
        // no equivocators
        //
        //  Genesis Era      era boundary     Child era
        //             A1-A2(KB)  |    a1 b1
        //            /       \   |   /  /
        //  genesis_KB         B1-|-B2(SB)-B3
        //                        |   \
        //                        |    c1
        //
        // small letters are ballots

        new ForkChoiceTestFixture {

          override def test: Task[Unit] =
            for {
              _          <- insertGenesis()
              genesisEra <- addGenesisEra()
              a1         <- genesisEra.block(alice, genesis.messageHash)
              a2         <- genesisEra.block(alice, a1)
              b1         <- genesisEra.block(bob, a2)
              b2         <- genesisEra.block(bob, b1)
              // Voting period ballots
              ba1        <- genesisEra.ballot(alice, b2)
              bb1        <- genesisEra.ballot(bob, b2)
              bc1        <- genesisEra.ballot(charlie, b2)
              childEra   <- genesisEra.addChildEra(a2)
              b3         <- childEra.block(bob, b2)
              forkChoice <- ForkChoice.create[Task].fromKeyBlock(childEra.keyBlockHash)
            } yield {
              assert(forkChoice.block.messageHash == b3)
              assert(forkChoice.justifications.map(_.messageHash) == Set(b3, ba1, bb1, bc1))
            }
      }
  }

  it should "not choose an equivocator as the fork choice" in testFixture {
    implicit timer => implicit db =>
      new ForkChoiceTestFixture {

        // key_block - A
        //           \ A'
        // The only validator that created blocks equivocated
        // we should return key_block as fork choice.

        override lazy val bonds: List[Bond] = makeBonds(Map(Alice -> 3, Bob -> 4))

        override def test: Task[Unit] =
          for {
            _           <- insertGenesis()
            genesisEra  <- addGenesisEra()
            (a, aPrime) <- equivocate(alice, genesisEra, genesisEra.keyBlockHash)
            // NOTE: Without the `onlyTakeOwnLatestFromJustifications` flag this test somehow got hung.
            forkChoice <- ForkChoice.create[Task].fromKeyBlock(genesisEra.keyBlockHash)
          } yield {
            assert(forkChoice.block.messageHash == genesisEra.keyBlockHash)
            assert(forkChoice.justifications.map(_.messageHash) == Set(a, aPrime))
          }
      }
  }

  it should "return fork choice within single era" in testFixture { implicit timer => implicit db =>
    // Weights: A -> 3, B -> 4, C -> 5
    //
    //                      era boundary
    //             A1 - A2     |
    //            /       \    |
    //  key_block         B1   |
    //            \            |
    //            C1 - C2      |
    //
    // fork choice - B1
    // latest messages - {C2}, B1 is the fork choice and A2 is its p-dag ancestor

    new ForkChoiceTestFixture {

      override def test: Task[Unit] =
        for {
          _          <- insertGenesis()
          genesisEra <- addGenesisEra()
          a1         <- genesisEra.block(alice, genesis.messageHash)
          c1         <- genesisEra.block(charlie, genesis.messageHash)
          a2         <- genesisEra.block(alice, a1)
          b1         <- genesisEra.block(bob, a2)
          c2         <- genesisEra.block(charlie, c1)
          forkChoice <- ForkChoice.create[Task].fromKeyBlock(genesisEra.keyBlockHash)
        } yield {
          assert(forkChoice.block.messageHash == b1)
          assert(forkChoice.justifications.map(_.messageHash) == Set(b1, a2, c2))
        }
  }
  }

  it should "compute fork choice across multiple eras (no equivocators)" in testFixture {
    implicit timer =>
      implicit db =>
        // Weights: A -> 3, B -> 4, C -> 5
        // three eras (era-1, era-2, era-3)
        // key block in era-1
        // latest messages in era-3
        // no equivocators
        //
        //  Genesis Era      era boundary     Child era
        //             A1-A2(KB)  |    a1 b1 A3     A4
        //            /       \   |   /  / /   \    /
        //  genesis_KB         B1-|-B2(SB)--------B3
        //            \           |   \     \    \  \
        //            C1          |    c1    C2  C3  C4
        //
        // small letters are ballots

        new ForkChoiceTestFixture {

          override def test: Task[Unit] =
            for {
              _          <- insertGenesis()
              genesisEra <- addGenesisEra()
              a1         <- genesisEra.block(alice, genesis.messageHash)
              _          <- genesisEra.block(charlie, genesis.messageHash)
              a2         <- genesisEra.block(alice, a1)
              b1         <- genesisEra.block(bob, a2)
              b2         <- genesisEra.block(bob, b1)
              // Voting period ballots
              ba1        <- genesisEra.ballot(alice, b2)
              bb1        <- genesisEra.ballot(bob, b2)
              bc1        <- genesisEra.ballot(charlie, b2)
              childEra   <- genesisEra.addChildEra(a2)
              a3         <- childEra.block(alice, b2)
              _          <- childEra.block(charlie, b2)
              b3         <- childEra.block(bob, b2)
              _          <- childEra.block(charlie, a3)
              a4         <- childEra.block(alice, b3)
              c4         <- childEra.block(charlie, b3)
              forkChoice <- ForkChoice.create[Task].fromKeyBlock(childEra.keyBlockHash)
            } yield {
              assert(forkChoice.block.messageHash == c4)
              assert(forkChoice.justifications.map(_.messageHash) == Set(ba1, bb1, bc1, a4, b3, c4))
            }
      }
  }

  it should "compute fork choice across multiple eras (with equivocators)" in testFixture {
    implicit timer =>
      implicit db =>
        // Using the same DAG as in the previous test, but this time
        // we will make C equivocate in the Genesis era. This means its
        // blocks get 0 score - the fork choice should pick A4
        //
        // Weights: A -> 3, B -> 4, C -> 5
        //
        //  Genesis Era      era boundary     Child era
        //             A1-A2(KB)  |    a1 b1 A3     A4
        //            /       \   |   /  / /   \    /
        //  genesis_KB \       B1-|-B2(SB)--------B3
        //            \ \         |   \     \    \  \
        //            C1 C1*      |    c1    C2  C3  C4
        //
        // small letters are ballots

        new ForkChoiceTestFixture {

          override def test: Task[Unit] =
            for {
              _             <- insertGenesis()
              genesisEra    <- addGenesisEra()
              (c1, c1Prime) <- equivocate(charlie, genesisEra, genesis.messageHash)
              a1            <- genesisEra.block(alice, genesis.messageHash)
              a2            <- genesisEra.block(alice, a1)
              b1            <- genesisEra.block(bob, a2)
              b2            <- genesisEra.block(bob, b1)
              // Voting period ballots
              ba1          <- genesisEra.ballot(alice, b2)
              bb1          <- genesisEra.ballot(bob, b2)
              bc1          <- genesisEra.ballot(charlie, b2)
              childEra     <- genesisEra.addChildEra(a2)
              a3           <- childEra.block(alice, b2)
              _            <- childEra.block(charlie, b2)
              b3           <- childEra.block(bob, b2)
              _            <- childEra.block(charlie, a3)
              a4           <- childEra.block(alice, b3)
              c4           <- childEra.block(charlie, b3)
              forkChoice   <- ForkChoice.create[Task].fromKeyBlock(childEra.keyBlockHash)
              equivocators <- MessageProducer.collectEquivocators[Task](childEra.keyBlockHash)
            } yield {
              assert(equivocators == Set(charlie.validatorId))
              assert(forkChoice.block.messageHash == c4)
              val justifications = forkChoice.justifications.map(_.messageHash)
              // This is non-det b/c, when creating a new message in MessageProducer,
              // we will pick non-det validator previous message to put in `message.validatorPrevMsg`
              // field. The new message will override previous equivocation as "latest message" in the DAG.
              val verC1      = Set(a4, c1, ba1, bb1, bc1, b3, c4)
              val verC1Prime = Set(a4, c1Prime, ba1, bb1, bc1, b3, c4)
              assert(justifications == verC1 || justifications == verC1Prime)
            }
      }
  }

  it should "not return parent-era voting-only ballots as justifications once they are included in the child-era blocks" in (pending)

  abstract class ForkChoiceTestFixture(
      implicit
      timer: Timer[Task],
      db: SQLiteStorage.CombinedStorage[Task]
  ) extends Fixture(eraDuration) {

    lazy val Alice   = Ed25519.newKeyPair
    lazy val Bob     = Ed25519.newKeyPair
    lazy val Charlie = Ed25519.newKeyPair

    def makeBonds(keys: Map[(Keys.PrivateKey, PublicKey), Int]): List[Bond] =
      keys
        .map { case (keys, stake) => keys._2 -> stake }
        .map {
          case (pk, stake) =>
            val hash = Ed25519.publicKeyHash(pk)
            Bond(ByteString.copyFrom(hash)).withStake(state.BigInt(stake.toString))
        }
        .toList

    override lazy val bonds: List[Bond] = makeBonds(Map(Alice -> 3, Bob -> 4, Charlie -> 5))

    val alice: MessageProducer[Task]   = createMessageProducer(Alice)
    val bob: MessageProducer[Task]     = createMessageProducer(Bob)
    val charlie: MessageProducer[Task] = createMessageProducer(Charlie)

    private def createMessageProducer(keys: (Keys.PrivateKey, PublicKey)): MessageProducer[Task] =
      MessageProducer[Task](
        validatorIdentity = ValidatorIdentity(keys._2, keys._1, signatureAlgorithm = Ed25519),
        chainName = chainName,
        upgrades = Seq.empty,
        // Without this, tests that want to trigger equivocations without creating another
        // message first will not work as the code will look up the latest from the DB.
        onlyTakeOwnLatestFromJustifications = true
      )

    private def produceG(
        mp: MessageProducer[Task],
        parent: BlockHash,
        era: Era,
        withEquivocation: Boolean
    ): Task[List[BlockHash]] =
      for {
        dag            <- DagStorage[Task].getRepresentation
        tips           <- dag.latestInEra(era.keyBlockHash)
        parentBlock    <- dag.lookupBlockUnsafe(parent)
        latest         <- tips.latestMessages
        justifications = latest
        b <- mp.block(
              era.keyBlockHash,
              roundId = Ticks(era.startTick),
              mainParent = parentBlock,
              justifications = justifications,
              isBookingBlock = false,
              messageRole = MessageRole.PROPOSAL
            )
        bPrime <- if (withEquivocation)
                   mp.ballot(
                       era.keyBlockHash,
                       roundId = Ticks(era.endTick),
                       target = parentBlock,
                       justifications = justifications,
                       messageRole = MessageRole.WITNESS
                     )
                     .map(_.messageHash.some)
                 else none[BlockHash].pure[Task]
      } yield List(b.messageHash) ::: bPrime.toList

    def normal(
        mp: MessageProducer[Task],
        era: Era,
        parent: BlockHash
    ): Task[BlockHash] =
      produceG(mp, parent, era, false).map(_.head)

    def equivocate(
        mp: MessageProducer[Task],
        era: Era,
        parent: BlockHash
    ): Task[(BlockHash, BlockHash)] =
      produceG(mp, parent, era, true).map {
        case List(b, bPrime) => (b, bPrime)
      }

  }
}
