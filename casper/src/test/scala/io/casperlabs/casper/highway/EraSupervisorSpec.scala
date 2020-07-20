package io.casperlabs.casper.highway

import cats.effect.Resource
import cats.effect.concurrent.Ref
import cats.implicits._
import io.casperlabs.casper.consensus.Block.MessageRole
import io.casperlabs.comm.gossiping.WaitHandle
import io.casperlabs.comm.gossiping.relaying.BlockRelaying
import io.casperlabs.crypto.Keys
import io.casperlabs.models.Message
import io.casperlabs.storage.{BlockHash, SQLiteStorage}
import monix.eval.Task
import org.scalatest._
import scala.concurrent.duration._
import io.casperlabs.casper.highway.mocks.MockMessageProducer
import io.casperlabs.crypto.Keys
import io.casperlabs.shared.Log

class EraSupervisorSpec
    extends FlatSpec
    with Matchers
    with Inspectors
    with HighwayFixture
    with HighwayNetworkFixture {

  behavior of "collectActiveEras"

  it should "collect voting and active eras" in testFixture { implicit timer => implicit db =>
    new Fixture(length = 6 * eraDuration) {
      // 2 weeks for genesis, then 1 week for each descendant.
      // e0 - e1
      //    \ e2 - e3
      //         \ e4 - e5
      override def test =
        for {
          _  <- insertGenesis()
          e0 <- addGenesisEra()
          b1 <- e0.block(messageProducer, genesis.blockSummary.blockHash)
          b2 <- e0.block(messageProducer, genesis.blockSummary.blockHash)
          _  <- e0.addChildEra(b1)
          e2 <- e0.addChildEra(b2)
          b3 <- e2.block(messageProducer, b2)
          b4 <- e2.block(messageProducer, b2)
          e3 <- e2.addChildEra(b3)
          e4 <- e2.addChildEra(b4)
          b5 <- e4.block(messageProducer, b4)
          e5 <- e4.addChildEra(b5)

          // Make sure all key blocks were finalized. This will set the LFB to b5
          _ <- List(b1, b2, b3, b4, b5).traverse(db.markAsFinalized(_, Set.empty, Set.empty))

          // Wait until where e3 and e4 are voting.
          // Their parent eras will have their voting over, and
          // their children should be active.
          _ <- sleepUntil(conf.toInstant(Ticks(e3.endTick)) plus postEraVotingDuration / 2)

          active <- EraSupervisor.collectActiveEras[Task](makeRuntime)
        } yield {
          // b3 is not in the main chain if the LFB is b5, so it won't be considered active.
          val expected = List(e4, e5).map(_.keyBlockHash)
          active.map(_._1.era.keyBlockHash) should contain theSameElementsAs expected
        }
    }
  }

  behavior of "EraSupervisor"

  it should "start running the genesis era and create descendant eras when the time comes" in testFixture {
    implicit timer => implicit db =>
      new Fixture(
        length = eraDuration * 2 + postEraVotingDuration,
        initRoundExponent = 15 // ~ 8 hours; so we don't get that many blocks,
      ) {
        override def test =
          makeSupervisor().use { supervisor =>
            for {
              _    <- Task.sleep(eraDuration * 2 + postEraVotingDuration / 2)
              eras <- supervisor.eras
            } yield {
              eras should have size 2
            }
          }
      }
  }

  it should "not show active eras after errors" in testFixture { implicit timer => implicit db =>
    new Fixture(
      length = 1.hour,
      initRoundExponent = 10, // ~20 minutes,
      printLevel = Log.Level.Crit
    ) {
      // Create a message producer that will raise errors so the agenda will fail.
      override lazy val messageProducer: MessageProducer[Task] =
        new MockMessageProducer[Task](validator) {
          override def block(
              keyBlockHash: BlockHash,
              roundId: Ticks,
              mainParent: Message.Block,
              justifications: Map[Keys.PublicKeyHashBS, Set[Message]],
              isBookingBlock: Boolean,
              messageRole: MessageRole
          ) = Task.raiseError(new RuntimeException("Stop the agenda!"))
        }

      override def test =
        makeSupervisor().use { supervisor =>
          for {
            active0 <- supervisor.activeEras
            _       = active0 should not be empty
            _       <- Task.sleep(30.minutes)
            active1 <- supervisor.activeEras
            _       = active1 shouldBe empty
          } yield ()
        }
    }
  }

  it should "relay created messages to other nodes" in testFixtures(
    validators = List("Alice", "Bob", "Charlie")
  ) { timer => validatorDatabases =>
    new NetworkFixture(validatorDatabases) {

      override val start  = genesisEraStart
      override val length = days(5)

      override def makeRelayFixture(
          validator: String,
          db: SQLiteStorage.CombinedStorage[Task],
          supervisorsRef: Ref[Task, Map[String, EraSupervisor[Task]]],
          isSyncedRef: Ref[Task, Boolean]
      ) =
        new RelayFixture(
          length,
          validator = validator,
          initRoundExponent = 15,
          supervisorsRef,
          isSyncedRef
        ) (timer, db) {

          val validatorId: Keys.PublicKeyHashBS = validator

          override val test = for {
            _        <- sleepUntil(start plus length)
            relayed  <- relayedRef.get
            dag      <- db.getRepresentation
            messages <- relayed.toList.traverse(dag.lookupUnsafe)
            parents  <- messages.traverse(m => dag.lookupUnsafe(m.parentBlock))
          } yield {
            messages should not be empty
            atLeast(1, messages) shouldBe a[Message.Ballot]
            atLeast(1, messages) shouldBe a[Message.Block]

            // Validators should only try to relay their own messages.
            forAll(messages) { m =>
              m.validatorId shouldBe validatorId
            }
            // There should be some responses to other validators' messages.
            forAtLeast(1, parents) { p =>
              p.validatorId should not be empty
              p.validatorId should not be validatorId
            }
          }
        }
    }
  }
}
