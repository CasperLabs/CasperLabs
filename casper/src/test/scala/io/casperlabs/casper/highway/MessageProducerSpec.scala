package io.casperlabs.casper.highway

import cats._
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.crypto.Keys
import io.casperlabs.crypto.signatures.SignatureAlgorithm.Ed25519
import io.casperlabs.casper.{DeploySelection, ValidatorIdentity}
import io.casperlabs.casper.consensus.{Bond, Era}
import io.casperlabs.casper.consensus.Block.MessageRole
import io.casperlabs.casper.consensus.state
import io.casperlabs.casper.highway.mocks.MockMessageProducer
import io.casperlabs.storage.BlockHash
import io.casperlabs.storage.dag.DagStorage
import io.casperlabs.storage.block.BlockStorage
import io.casperlabs.mempool.DeployBuffer
import io.casperlabs.casper.scalatestcontrib._
import monix.eval.Task
import org.scalatest._
import scala.concurrent.duration._
import com.github.ghik.silencer.silent

@silent("is never used")
class MessageProducerSpec extends FlatSpec with Matchers with Inspectors with HighwayFixture {

  behavior of "collectEquivocators"

  it should "gather equivocators from the grandparent, parent and child eras" in testFixture {
    implicit timer => implicit db =>
      new Fixture(
        length = 5 * eraDuration
      ) {

        override lazy val bonds = List(
          Bond("Alice").withStake(state.BigInt("1000")),
          Bond("Bob").withStake(state.BigInt("2000")),
          Bond("Charlie").withStake(state.BigInt("3000")),
          Bond("Dave").withStake(state.BigInt("4000")),
          Bond("Eve").withStake(state.BigInt("5000")),
          Bond("Fred").withStake(state.BigInt("6000"))
        )

        // Create an equivocation in an era by storing two messages that don't quote each other.
        // Return the block hash we can use as key block for the next era.
        def produce(validator: String, era: Era, withEquivocation: Boolean): Task[BlockHash] = {
          val mp = new MockMessageProducer[Task](validator)
          for {
            dag    <- DagStorage[Task].getRepresentation
            tips   <- dag.latestInEra(era.keyBlockHash)
            latest <- tips.latestMessages
            justifications = latest.map {
              case (v, ms) => Keys.PublicKeyHash(v) -> ms
            }
            b <- mp.block(
                  era.keyBlockHash,
                  roundId = Ticks(era.startTick),
                  mainParent = genesis,
                  justifications = justifications,
                  isBookingBlock = false,
                  messageRole = MessageRole.PROPOSAL
                )
            _ <- mp
                  .ballot(
                    era.keyBlockHash,
                    roundId = Ticks(era.endTick),
                    target = genesis,
                    justifications = justifications,
                    messageRole = MessageRole.WITNESS
                  )
                  .whenA(withEquivocation)
          } yield b.messageHash
        }

        def equivocate(validator: String, era: Era) = produce(validator, era, true)
        def normal(validator: String, era: Era)     = produce(validator, era, false)

        // Make an era tree where we'll put some equivocations into various ones
        // in the hierarchy and check that the right ones are gathered.
        // eA - eB
        //    \ eC - eD
        //         \ eE - eF

        def equivocators(era: Era) =
          MessageProducer.collectEquivocators[Task](era.keyBlockHash).map { vs =>
            // Make it readable again.
            vs.map(v => new String(v.toByteArray))
          }

        override def test =
          for {
            _ <- insertGenesis()

            eA  <- addGenesisEra()
            bAB <- normal("Alice", eA)
            bAC <- normal("Alice", eA)
            bAD <- normal("Alice", eA)
            bAE <- equivocate("Alice", eA)

            eB <- eA.addChildEra(bAB)
            _  <- equivocate("Bob", eB)

            eC  <- eA.addChildEra(bAC)
            bCF <- equivocate("Charlie", eC)
            _   <- normal("Alice", eC)

            eD <- eC.addChildEra(bAD)
            _  <- equivocate("Dave", eD)

            eE <- eC.addChildEra(bAE)
            _  <- equivocate("Eve", eE)

            eF <- eE.addChildEra(bCF)
            _  <- equivocate("Fred", eF)
            _  <- normal("Bob", eF)

            _ <- equivocators(eA) shouldBeF Set("Alice")
            _ <- equivocators(eB) shouldBeF Set("Alice", "Bob")
            _ <- equivocators(eF) shouldBeF Set("Charlie", "Eve", "Fred")
          } yield ()
      }
  }

  behavior of "makeBallot"

  it should "assign fields correctly and restart the validator seq.no and prev message hash in new eras" in testFixture {
    implicit timer => implicit db =>
      new Fixture(length = 3 * eraDuration) { self =>
        val (privateKey, publicKey) = Ed25519.newKeyPair
        val validatorId             = Keys.PublicKeyHash(ByteString.copyFrom(Ed25519.publicKeyHash(publicKey)))

        override lazy val messageProducer: MessageProducer[Task] = {
          implicit val deployBuffer    = DeployBuffer.create[Task](chainName, minTtl = Duration.Zero)
          implicit val deploySelection = DeploySelection.create[Task]()

          MessageProducer[Task](
            validatorIdentity =
              ValidatorIdentity(publicKey, privateKey, signatureAlgorithm = Ed25519),
            chainName = chainName,
            upgrades = Seq.empty
          )
        }

        override def test =
          for {
            _  <- insertGenesis()
            e0 <- addGenesisEra()
            b1 <- messageProducer.ballot(
                   e0.keyBlockHash,
                   roundId = Ticks(e0.startTick),
                   target = genesis,
                   justifications = Map.empty,
                   messageRole = MessageRole.WITNESS
                 )

            _ = b1.validatorId shouldBe validatorId
            _ = b1.eraId shouldBe e0.keyBlockHash
            _ = b1.roundId shouldBe e0.startTick
            _ = b1.blockSummary.getHeader.chainName shouldBe chainName
            _ = b1.jRank shouldBe 1
            _ = b1.validatorMsgSeqNum shouldBe 1

            b2 <- messageProducer.ballot(
                   e0.keyBlockHash,
                   roundId = Ticks(e0.endTick),
                   target = genesis,
                   justifications = Map(validatorId -> Set(b1)),
                   messageRole = MessageRole.WITNESS
                 )

            _ = b2.jRank shouldBe 2
            _ = b2.mainRank shouldBe 1
            _ = b2.validatorMsgSeqNum shouldBe 2
            _ = b2.validatorPrevMessageHash shouldBe b1.messageHash

            e1 <- e0.addChildEra()
            b3 <- messageProducer.ballot(
                   e1.keyBlockHash,
                   roundId = Ticks(e1.startTick),
                   target = genesis,
                   justifications = Map(validatorId -> Set(b2)),
                   messageRole = MessageRole.WITNESS
                 )

            _ = b3.jRank shouldBe 3
            _ = b3.mainRank shouldBe 1
            _ = b3.validatorMsgSeqNum shouldBe 1
            _ = b3.validatorPrevMessageHash shouldBe ByteString.EMPTY

            b4 <- messageProducer.ballot(
                   e1.keyBlockHash,
                   roundId = Ticks(e1.endTick),
                   target = genesis,
                   justifications = Map(validatorId -> Set(b2, b3)),
                   messageRole = MessageRole.WITNESS
                 )

            _ = b4.jRank shouldBe 4
            _ = b4.mainRank shouldBe 1
            _ = b4.validatorMsgSeqNum shouldBe 2
            _ = b4.validatorPrevMessageHash shouldBe b3.messageHash
            _ = b4.justifications should have size 2

            // Check that messages are persisted.
            ballots = List(b1, b2, b3, b4)
            dag     <- DagStorage[Task].getRepresentation

            _ <- ballots.traverse { x =>
                  dag.lookupUnsafe(x.messageHash)
                } shouldBeF ballots

            _ <- ballots.traverse { x =>
                  BlockStorage[Task].getBlockSummaryUnsafe(x.messageHash)
                } shouldBeF ballots.map(_.blockSummary)
          } yield ()
      }
  }

  behavior of "makeBlock"

  it should "assign fields correctly and restart the validator seq.no and prev message hash in new eras" in testFixture {
    implicit timer => implicit db =>
      new Fixture(length = 3 * eraDuration) { self =>
        val (privateKey, publicKey) = Ed25519.newKeyPair
        val validatorId             = Keys.PublicKeyHash(ByteString.copyFrom(Ed25519.publicKeyHash(publicKey)))

        override lazy val messageProducer: MessageProducer[Task] = {
          implicit val deployBuffer    = DeployBuffer.create[Task](chainName, minTtl = Duration.Zero)
          implicit val deploySelection = DeploySelection.create[Task]()

          MessageProducer[Task](
            validatorIdentity =
              ValidatorIdentity(publicKey, privateKey, signatureAlgorithm = Ed25519),
            chainName = chainName,
            upgrades = Seq.empty
          )
        }

        override def test =
          for {
            _  <- insertGenesis()
            e0 <- addGenesisEra()
            b1 <- messageProducer.block(
                   e0.keyBlockHash,
                   roundId = Ticks(e0.startTick),
                   mainParent = genesis,
                   justifications = Map.empty,
                   isBookingBlock = false,
                   messageRole = MessageRole.PROPOSAL
                 )

            _ = b1.validatorId shouldBe validatorId
            _ = b1.eraId shouldBe e0.keyBlockHash
            _ = b1.roundId shouldBe e0.startTick
            _ = b1.blockSummary.getHeader.chainName shouldBe chainName
            _ = b1.jRank shouldBe 1
            _ = b1.mainRank shouldBe 1
            _ = b1.validatorMsgSeqNum shouldBe 1

            b2 <- messageProducer.block(
                   e0.keyBlockHash,
                   roundId = Ticks(e0.endTick),
                   mainParent = b1,
                   justifications = Map(validatorId -> Set(b1)),
                   isBookingBlock = false,
                   messageRole = MessageRole.PROPOSAL
                 )

            _ = b2.jRank shouldBe 2
            _ = b2.mainRank shouldBe 2
            _ = b2.validatorMsgSeqNum shouldBe 2
            _ = b2.validatorPrevMessageHash shouldBe b1.messageHash

            e1 <- e0.addChildEra()
            b3 <- messageProducer.block(
                   e1.keyBlockHash,
                   roundId = Ticks(e1.startTick),
                   mainParent = b2,
                   justifications = Map(validatorId -> Set(b2)),
                   isBookingBlock = false,
                   messageRole = MessageRole.PROPOSAL
                 )

            _ = b3.jRank shouldBe 3
            _ = b3.mainRank shouldBe 3
            _ = b3.validatorMsgSeqNum shouldBe 1
            _ = b3.validatorPrevMessageHash shouldBe ByteString.EMPTY

            b4 <- messageProducer.block(
                   e1.keyBlockHash,
                   roundId = Ticks(e1.endTick),
                   mainParent = b3,
                   justifications = Map(validatorId -> Set(b2, b3)),
                   isBookingBlock = false,
                   messageRole = MessageRole.PROPOSAL
                 )

            _ = b4.jRank shouldBe 4
            _ = b4.mainRank shouldBe 4
            _ = b4.validatorMsgSeqNum shouldBe 2
            _ = b4.validatorPrevMessageHash shouldBe b3.messageHash
            _ = b4.justifications should have size 2

            // Check that messages are persisted.
            block = List(b1, b2, b3, b4)
            dag   <- DagStorage[Task].getRepresentation

            _ <- block.traverse { x =>
                  dag.lookupUnsafe(x.messageHash)
                } shouldBeF block

            _ <- block.traverse { x =>
                  BlockStorage[Task].getBlockSummaryUnsafe(x.messageHash)
                } shouldBeF block.map(_.blockSummary)
          } yield ()
      }
  }

  behavior of "collectEras"

  it should "return eras up until the one the key block was built in" in testFixture {
    implicit timer => implicit db =>
      new Fixture(eraDuration) {
        // An era tree:
        // eA - eB
        //    \ eC - eD
        //         \ eE - eF

        val alice = new MockMessageProducer[Task]("Alice")
        val bob   = new MockMessageProducer[Task]("Bob")

        def erasUntil(era: Era): Task[List[ByteString]] =
          MessageProducer.collectEras[Task](era.keyBlockHash).map(_.map(_.keyBlockHash))

        override def test =
          for {
            _ <- insertGenesis()

            eA   <- addGenesisEra()
            eAa1 <- eA.block(alice, genesis.messageHash)
            eAb1 <- eA.block(bob, eAa1)

            eB   <- eA.addChildEra(eAb1)
            eBa1 <- eB.block(alice, eAb1)
            eBb1 <- eB.block(bob, eBa1)

            eC   <- eA.addChildEra(eAb1)
            eCa1 <- eC.block(alice, eAb1)
            eCb1 <- eC.block(bob, eCa1)

            eD   <- eC.addChildEra(eAa1)
            eDa1 <- eD.block(alice, eCb1)
            eDb1 <- eD.block(bob, eDa1)

            eE   <- eC.addChildEra(eAa1)
            eEa1 <- eE.block(alice, eCb1)
            eEb1 <- eE.block(bob, eEa1)

            eF   <- eE.addChildEra(eCb1)
            eFa1 <- eF.block(alice, eEb1)
            eFb1 <- eF.block(bob, eFa1)

            _ <- erasUntil(eA) shouldBeF List(eA.keyBlockHash)
            _ <- erasUntil(eB) shouldBeF List(eA.keyBlockHash, eB.keyBlockHash)
            _ <- erasUntil(eC) shouldBeF List(eA.keyBlockHash, eC.keyBlockHash)
            _ <- erasUntil(eD) shouldBeF List(eA.keyBlockHash, eC.keyBlockHash, eD.keyBlockHash)
            _ <- erasUntil(eE) shouldBeF List(eA.keyBlockHash, eC.keyBlockHash, eE.keyBlockHash)
            _ <- erasUntil(eF) shouldBeF List(eC.keyBlockHash, eE.keyBlockHash, eF.keyBlockHash)
          } yield ()
      }
  }

  behavior of "lowerLimit"

  it should "return the limit if the default is 0" in {
    MessageProducer.lowerLimit(0, 10) shouldBe 10
  }
  it should "return the default if the limit is 0" in {
    MessageProducer.lowerLimit(10, 0) shouldBe 10
  }
  it should "return the default if it's lower" in {
    MessageProducer.lowerLimit(5, 10) shouldBe 5
  }
  it should "return the limit if it's lower" in {
    MessageProducer.lowerLimit(10, 5) shouldBe 5
  }
}
