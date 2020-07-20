package io.casperlabs.casper.highway

import cats.{Applicative, Id, Show}
import cats.syntax.show._
import cats.syntax.option._
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.effect.{Clock, Sync}
import cats.effect.concurrent.{Ref, Semaphore}
import com.google.protobuf.ByteString
import java.util.concurrent.TimeUnit

import io.casperlabs.casper.consensus.{Block, BlockSummary, Bond, Era}
import io.casperlabs.casper.consensus.state
import io.casperlabs.crypto.Keys
import io.casperlabs.models.Message
import io.casperlabs.metrics.Metrics
import io.casperlabs.catscontrib.{MakeSemaphore, MonadThrowable}
import io.casperlabs.shared.Log
import io.casperlabs.storage.BlockHash
import io.casperlabs.storage.block.BlockStorageWriter
import io.casperlabs.storage.dag.{DagStorage, FinalityStorage}
import io.casperlabs.storage.era.EraStorage
import io.casperlabs.casper.mocks.MockFinalityStorage
import io.casperlabs.casper.highway.mocks.{
  MockBlockDagStorage,
  MockEraStorage,
  MockForkChoice,
  MockMessageProducer
}
import org.scalatest._
import org.scalactic.source
import org.scalactic.Prettifier
import org.scalatest.prop.GeneratorDrivenPropertyChecks.{forAll => forAllGen}
import org.scalacheck._
import scala.annotation.tailrec
import scala.concurrent.duration._
import java.time.temporal.ChronoUnit

import io.casperlabs.casper.highway.HighwayEvent.HandledLambdaMessage
import io.casperlabs.storage.dag.AncestorsStorage
import io.casperlabs.storage.dag.DagLookup

class EraRuntimeSpec extends WordSpec with Matchers with Inspectors with TickUtils {
  import EraRuntimeSpec._
  import HighwayConf._
  import EraRuntime.Agenda
  import io.casperlabs.catscontrib.effect.implicits.syncId

  type BlockDagStorage[F[_]] = BlockStorageWriter[F] with DagStorage[F]

  implicit def noShrink[T] = Shrink[T](_ => Stream.empty)

  implicit def defaultClock: Clock[Id] = TestClock.frozen[Id](date(2019, 12, 9))

  implicit def `Message => ValidatedMessage`(m: Message): ValidatedMessage = Validated(m)

  implicit val noMetrics = new Metrics.MetricsNOP[Id]
  implicit val noLog     = Log.NOPLog[Id]

  val postEraVotingDuration = days(2)

  val conf = HighwayConf(
    tickUnit = TimeUnit.MILLISECONDS,
    genesisEraStart = date(2019, 12, 9),
    eraDuration = EraDuration.FixedLength(days(7)),
    bookingDuration = days(10),
    entropyDuration = hours(3),
    postEraVotingDuration = VotingDuration.FixedLength(postEraVotingDuration),
    omegaMessageTimeStart = 0.5,
    omegaMessageTimeEnd = 0.75,
    omegaBlocksEnabled = true
  )

  val genesis = Message
    .fromBlockSummary(
      BlockSummary()
        .withBlockHash(ByteString.copyFromUtf8("genesis"))
        .withHeader(
          Block
            .Header()
            .withState(
              Block
                .GlobalState()
                .withBonds(
                  List(
                    Bond("Alice").withStake(state.BigInt("3000")),
                    Bond("Bob").withStake(state.BigInt("4000")),
                    Bond("Charlie").withStake(state.BigInt("5000"))
                  )
                )
            )
        )
    )
    .get
    .asInstanceOf[Message.Block]

  def makeBlock(
      validator: Keys.PublicKeyHashBS,
      era: Era,
      roundId: Ticks,
      mainParent: ByteString = genesis.messageHash,
      justifications: Map[Keys.PublicKeyHashBS, ByteString] = Map.empty,
      messageRole: Block.MessageRole = Block.MessageRole.PROPOSAL
  ) =
    Message
      .fromBlockSummary {
        BlockSummary()
          .withHeader(
            Block
              .Header()
              .withMessageRole(messageRole)
              .withValidatorPublicKeyHash(validator)
              .withKeyBlockHash(era.keyBlockHash)
              .withRoundId(roundId)
              .withParentHashes(List(mainParent).filterNot(_.isEmpty))
              .withMagicBit(scala.util.Random.nextBoolean())
              .withJustifications(
                justifications.toSeq.map {
                  case (v, b) => Block.Justification(v, b)
                }
              )
              .withState(Block.GlobalState().withBonds(era.bonds))
          )
          .withBlockHash(blockHashes.next())
      }
      .get
      .asInstanceOf[Message.Block]

  def makeBallot(
      validator: Keys.PublicKeyHashBS,
      era: Era,
      roundId: Ticks,
      target: ByteString = genesis.messageHash,
      justifications: Map[Keys.PublicKeyHashBS, ByteString] = Map.empty,
      messageRole: Block.MessageRole = Block.MessageRole.WITNESS
  ) =
    Message
      .fromBlockSummary {
        BlockSummary()
          .withHeader(
            Block
              .Header()
              .withMessageType(Block.MessageType.BALLOT)
              .withMessageRole(messageRole)
              .withKeyBlockHash(era.keyBlockHash)
              .withRoundId(roundId)
              .withValidatorPublicKeyHash(validator)
              .withParentHashes(List(target).filterNot(_.isEmpty))
              .withJustifications(
                justifications.toSeq.map {
                  case (v, b) => Block.Justification(v, b)
                }
              )
          )
          .withBlockHash(blockHashes.next())
      }
      .get
      .asInstanceOf[Message.Ballot]

  def defaultBlockDagStorage = MockBlockDagStorage[Id](genesis.toBlock)
  def defaultForkChoice      = MockForkChoice[Id](genesis)
  def defaultFinalityStorage = MockFinalityStorage[Id](genesis.messageHash)

  def defaultMessageProducer(
      validator: String
  )(st: BlockDagStorage[Id]): MockMessageProducer[Id] = {
    implicit val store = st
    new MockMessageProducer[Id](validator)
  }

  implicit def defaultAncestorStorage(implicit ds: DagStorage[Id]): AncestorsStorage[Id] =
    new AncestorsStorage[Id] with DagLookup[Id] {
      override implicit val MT: MonadThrowable[Id] = syncId

      override def findAncestor(block: BlockHash, distance: Long): Id[Option[BlockHash]] =
        lookupUnsafe(block).flatMap(loop(_, distance))

      override def lookup(blockHash: BlockHash): Id[Option[Message]] =
        ds.getRepresentation.flatMap(_.lookup(blockHash))

      override def contains(blockHash: BlockHash): Id[Boolean] =
        ds.getRepresentation.flatMap(_.contains(blockHash))

      @tailrec private def loop(
          msg: Message,
          distance: Long
      ): Option[BlockHash] =
        if (distance == 0) Some(msg.messageHash)
        else {
          lookup(msg.parentBlock) match {
            case Some(parent) =>
              loop(parent, distance - 1)
            case None =>
              None
          }
        }
    }

  val messageProducerWithPendingDeploys: String => BlockDagStorage[Id] => MessageProducer[Id] = {
    validator => implicit bds =>
      new MockMessageProducer[Id](validator) {
        override def hasPendingDeploys = true
      }
  }

  def genesisEraRuntime(
      validator: Option[String] = none,
      roundExponent: Int = 0,
      leaderSequencer: LeaderSequencer = LeaderSequencer,
      isSyncedRef: Ref[Id, Boolean] = Ref.of[Id, Boolean](true),
      messageProducer: String => BlockDagStorage[Id] => MessageProducer[Id] = defaultMessageProducer,
      config: HighwayConf = conf
  )(
      implicit
      // Let's say we are right at the beginning of the era by default.
      C: Clock[Id] = TestClock.frozen[Id](date(2019, 12, 9)),
      M: Metrics[Id] = noMetrics,
      L: Log[Id] = noLog,
      DS: BlockDagStorage[Id] = defaultBlockDagStorage,
      ES: EraStorage[Id] = MockEraStorage[Id],
      FS: FinalityStorage[Id] = defaultFinalityStorage,
      FC: ForkChoice[Id] = defaultForkChoice
  ) =
    EraRuntime.fromGenesis[Id](
      config,
      genesis.blockSummary,
      validator.map(v => messageProducer(v)(DS)),
      roundExponent,
      isSyncedRef.get,
      leaderSequencer
    )(syncId, makeSemaphoreId, C, M, L, DS, ES, FS, FC, defaultAncestorStorage)

  /** Create a runtime given an era that's supposedly the child era of another one;
    * otherwise we should be using `genesisEraRuntime` instead. For the same reason
    * this one's not using any default values for the implicits: they should be
    * shared with the parent. Only practical to be used inside `Fixture`.
    */
  def childEraRuntime(
      era: Era,
      validator: Option[String] = none,
      roundExponent: Int = 0,
      leaderSequencer: LeaderSequencer = LeaderSequencer,
      isSyncedRef: Ref[Id, Boolean] = Ref.of[Id, Boolean](true),
      messageProducer: String => BlockDagStorage[Id] => MessageProducer[Id] = defaultMessageProducer,
      config: HighwayConf = conf
  )(
      implicit
      C: Clock[Id],
      M: Metrics[Id],
      L: Log[Id],
      DS: BlockDagStorage[Id],
      ES: EraStorage[Id],
      FS: FinalityStorage[Id],
      FC: ForkChoice[Id]
  ) =
    EraRuntime.fromEra[Id](
      config,
      era,
      validator.map(v => messageProducer(v)(DS)),
      roundExponent,
      isSyncedRef.get,
      leaderSequencer
    )(syncId, makeSemaphoreId, C, M, L, DS, ES, FS, FC, defaultAncestorStorage)

  // Make it easier to share common dependencies.
  // TODO (NODE-1199): Use HighwayFixture instead for all tests.
  trait Fixture {
    implicit val C  = TestClock.adjustable[Id](date(2019, 12, 9))
    implicit val DS = defaultBlockDagStorage
    implicit val ES = MockEraStorage[Id]
    implicit val FS = defaultFinalityStorage
    implicit val FC = defaultForkChoice
  }

  // Prepare a child era.
  abstract class ParentChildFixture(isLeaderInChild: Boolean) extends Fixture {
    val validator = "Alice"
    val leader    = if (isLeaderInChild) validator else "Charlie"

    // Using the validator to lead the parent so it can create its own switch block.
    val parentRuntime =
      genesisEraRuntime(validator.some, leaderSequencer = mockSequencer(validator))

    // Make a switch block.
    private val switchEvents = parentRuntime
      .handleAgenda(Agenda.StartRound(parentRuntime.endTick))
      .written

    private val childEra = switchEvents.collectFirst {
      case HighwayEvent.CreatedEra(era) => era
    }.get

    private val switchBlock = switchEvents.collectFirst {
      case HighwayEvent.CreatedLambdaMessage(msg: Message.Block) => msg
    }.get

    // Make sure the child era key block is finalized so the validator joins.
    FS.markAsFinalized(childEra.keyBlockHash, Set.empty, Set.empty)

    val childRuntime =
      childEraRuntime(childEra, validator.some, leaderSequencer = mockSequencer(leader))

    // Set the fork choice to the switch so we can build on it.
    FC.set(switchBlock)
    // Set the clock forward so the next message is accepted.
    C.set(childRuntime.start)
  }

  /** Fill the DagStorage with blocks at a fixed interval along the era,
    * right up to and including the switch block. */
  def makeFullChain(
      validator: Keys.PublicKeyHashBS,
      runtime: EraRuntime[Id],
      interval: FiniteDuration
  ) =
    Stream
      .iterate(runtime.start)(_ plus interval)
      .takeWhile(t => !t.isAfter(runtime.end))
      .foldLeft(List.empty[Message]) {
        case (msgs, t) =>
          val b = makeBlock(
            validator,
            runtime.era,
            roundId = conf.toTicks(t),
            mainParent = msgs.headOption.map(_.messageHash).getOrElse(ByteString.EMPTY)
          )
          b :: msgs
      }
      .reverse
      .toVector

  def assertEvent(
      events: Vector[HighwayEvent]
  )(test: PartialFunction[HighwayEvent, Unit])(implicit pos: source.Position) =
    events.filter(test.isDefinedAt(_)) match {
      case Vector(event) => test(event)
      case Vector()      => fail(s"Could not find matching event in $events")
      case _             => fail(s"Multiple matching events found in $events")
    }

  def assertAgenda(
      agenda: Agenda
  )(test: PartialFunction[Agenda.DelayedAction, Unit])(implicit pos: source.Position) =
    agenda.filter(test.isDefinedAt(_)) match {
      case Vector(action) => test(action)
      case Vector()       => fail(s"Could not find matching action in $agenda")
      case _              => fail(s"Multiple matching actions found in $agenda")
    }

  "EraRuntime" when {
    "started with the genesis block" should {
      val runtime = genesisEraRuntime()

      "use the genesis ticks for the era" in {
        conf.toInstant(Ticks(runtime.era.startTick)) shouldBe conf.genesisEraStart
        conf.toInstant(Ticks(runtime.era.endTick)) shouldBe conf.genesisEraEnd
      }

      "use the genesis block as key and booking block" in {
        runtime.era.keyBlockHash shouldBe genesis.messageHash
        runtime.era.bookingBlockHash shouldBe genesis.messageHash
        runtime.era.leaderSeed shouldBe ByteString.EMPTY
      }

      "not assign a parent era" in {
        runtime.era.parentKeyBlockHash shouldBe ByteString.EMPTY
      }

      "recognize booking block boundaries" in {
        def check(parentDay: Int, blockDay: Int) =
          runtime.isBookingBoundary(
            date(2019, 12, parentDay),
            date(2019, 12, blockDay)
          )
        runtime.bookingBoundaries should contain theSameElementsInOrderAs List(
          date(2019, 12, 13),
          date(2019, 12, 20)
        )
        check(4, 7) shouldBe false   // before the era
        check(11, 13) shouldBe true  // block falls on the first 10 day boundary
        check(13, 13) shouldBe false // parent and child on the same exact round, but parent was first
        check(13, 14) shouldBe false // parent block falls on the first 10 day boundary but the child does not
        check(19, 20) shouldBe true  // block falls on the second 10 day boundary
        check(25, 28) shouldBe false // after the era
      }

      "recognize key block boundaries" in {
        def check(parentDay: Int, blockDay: Int) =
          runtime.isKeyBoundary(
            date(2019, 12, parentDay),
            date(2019, 12, blockDay)
          )
        runtime.keyBoundaries should contain theSameElementsInOrderAs List(
          date(2019, 12, 13) plus hours(3),
          date(2019, 12, 20) plus hours(3)
        )
        check(11, 13) shouldBe false
        check(13, 14) shouldBe true
        check(14, 14) shouldBe false
        check(19, 20) shouldBe false
        check(20, 21) shouldBe true
        check(25, 28) shouldBe false
      }

      "recognize the switch block boundary" in {
        val end  = conf.genesisEraEnd
        val `1h` = hours(1)
        runtime.isSwitchBoundary(end minus `1h`, end plus `1h`) shouldBe true
        runtime.isSwitchBoundary(end minus `1h`, end) shouldBe true
        runtime.isSwitchBoundary(end, end) shouldBe false
        runtime.isSwitchBoundary(end, end plus `1h`) shouldBe false
      }
    }
  }

  "validate" should {
    "reject a message from an unbonded validator" in {
      val runtime = genesisEraRuntime()
      val message =
        makeBlock("Anonymous", runtime.era, roundId = runtime.startTick)
      runtime.validate(message).value shouldBe Left(
        "The validator is not bonded in the era."
      )
    }

    "reject a message before the era starts" in {
      val runtime = genesisEraRuntime()
      val message =
        makeBlock("Alice", runtime.era, roundId = Ticks(runtime.startTick - 1))
      runtime.validate(message).value shouldBe Left(
        "The round ID is before the start of the era."
      )
    }

    "reject a message after the era ends" in {
      val runtime = genesisEraRuntime()
      val message =
        makeBlock(
          "Alice",
          runtime.era,
          roundId = conf.toTicks(conf.genesisEraEnd plus postEraVotingDuration plus 1.hour)
        )
      runtime.validate(message).value shouldBe Left(
        "The round ID is after the end of the voting period."
      )
    }

    "reject a block received from a doppelganger" in {
      val runtime = genesisEraRuntime("Alice".some)
      val message =
        makeBlock("Alice", runtime.era, roundId = runtime.startTick)
      runtime.validate(message).value shouldBe Left(
        "The block is coming from a doppelganger."
      )
    }

    "reject a block received from a non-leader" in {
      val runtime = genesisEraRuntime(
        "Alice".some,
        leaderSequencer = mockSequencer("Bob")
      )
      val message = makeBlock("Charlie", runtime.era, runtime.startTick)
      runtime.validate(message).value shouldBe Left(
        "The block is not coming from the leader of the round."
      )
    }

    def testRejectSecondLambdaBlock(firstMessage: EraRuntime[Id] => Message) = {
      implicit val ds = defaultBlockDagStorage
      val runtime     = genesisEraRuntime("Alice".some, leaderSequencer = mockSequencer("Bob"))
      val message0    = insert(firstMessage(runtime))
      val message1 = makeBlock(
        "Bob",
        runtime.era,
        roundId = Ticks(message0.roundId),
        justifications = toJustifications(message0)
      )
      runtime.validate(message1).value shouldBe Left(
        "The leader has already sent a lambda message in this round."
      )
    }

    "reject a second lambda block received from the leader in the same round" in {
      testRejectSecondLambdaBlock { runtime =>
        makeBlock("Bob", runtime.era, roundId = runtime.startTick)
      }
    }

    "reject a second lambda block received after a lambda ballot in the same round in the voting only period" in {
      testRejectSecondLambdaBlock { runtime =>
        makeBallot("Bob", runtime.era, roundId = runtime.endTick)
      }
    }

    "reject a block built on a switch block" in {
      implicit val ds = defaultBlockDagStorage
      val leader      = "Alice"
      val runtime     = genesisEraRuntime(none, leaderSequencer = mockSequencer(leader))
      val build: (Ticks, BlockHash) => Message =
        makeBlock(leader, runtime.era, _, _)
      val msg1 = insert(build(runtime.startTick, genesis.messageHash))
      val msg2 = insert(build(runtime.endTick, msg1.messageHash))
      val msg3 = build(Ticks(runtime.endTick + 1), msg2.messageHash)
      runtime.validate(msg3).value shouldBe Left(
        "Only ballots should be built on top of a switch block in the current era."
      )
    }

    "accept a ballot from a leader not built on a switch block but a normal one during the voting period" in {
      implicit val ds = defaultBlockDagStorage
      val leader      = "Alice"
      val runtime     = genesisEraRuntime(none, leaderSequencer = mockSequencer(leader))
      val msg1        = insert(makeBlock(leader, runtime.era, runtime.startTick))
      val msg2        = makeBallot(leader, runtime.era, runtime.endTick, target = msg1.messageHash)
      runtime.validate(msg2).value shouldBe Right(())
    }

    "reject a ballot from a leader built on an invalid block that itself builds on a switch block" in {
      implicit val ds = defaultBlockDagStorage
      val leader      = "Alice"
      val runtime     = genesisEraRuntime(none, leaderSequencer = mockSequencer(leader))
      val msg1        = insert(makeBlock(leader, runtime.era, runtime.endTick))
      val msg2 =
        insert(makeBlock(leader, runtime.era, runtime.endTick, mainParent = msg1.messageHash))
      val msg3 = makeBallot(leader, runtime.era, runtime.endTick, target = msg2.messageHash)
      runtime.validate(msg3).value shouldBe Left(
        "A ballot during the voting-only period can only be built on pre-era-end blocks and switch blocks."
      )
    }

    "accept a second ballot received from the leader in the same round during the voting-only period" in {
      implicit val ds = defaultBlockDagStorage
      val leader      = "Bob"
      val runtime     = genesisEraRuntime("Alice".some, leaderSequencer = mockSequencer(leader))

      // These are just here to check the helper methods during the active period.
      val ballot0 = makeBallot(leader, runtime.era, runtime.startTick)

      val ballot1 = makeBallot(
        leader,
        runtime.era,
        runtime.startTick,
        justifications = toJustifications(ballot0)
      )

      // This is the voting period.
      val switch =
        makeBlock(leader, runtime.era, runtime.endTick, justifications = toJustifications(ballot1))

      val ballot2 = makeBallot(
        leader,
        runtime.era,
        Ticks(runtime.endTick + 1),
        target = switch.messageHash,
        justifications = toJustifications(switch)
      )

      val ballot3 = makeBallot(
        leader,
        runtime.era,
        Ticks(runtime.endTick + 1),
        target = switch.messageHash,
        justifications = toJustifications(ballot2)
      )

      val ballots =
        insert(List(ballot0, ballot1, switch, ballot2, ballot3)).collect {
          case b: Message.Ballot => b
        }

      ballots map {
        EraRuntime.isLambdaLikeBallot[Id](
          ds.getRepresentation,
          _,
          runtime.endTick
        )
      } shouldBe List(false, false, true, false)

      ballots map {
        EraRuntime.citesOwnMessageInSameRound[Id](
          ds.getRepresentation,
          _
        )
      } shouldBe List(false, true, false, true)

      ballots map {
        EraRuntime.hasOtherLambdaMessageInSameRound[Id](
          ds.getRepresentation,
          _,
          runtime.endTick
        )
      } shouldBe List(false, false, false, true)

      runtime.validate(ballot3).value shouldBe Right(())
    }
  }

  "initAgenda" when {
    "the validator is bonded in the era" should {
      "schedule the first round" in {
        val runtime = genesisEraRuntime(validator = "Alice".some)

        val agenda = runtime.initAgenda
        agenda should have size 1
        agenda.head.tick shouldBe runtime.startTick
        agenda.head.action shouldBe Agenda.StartRound(runtime.startTick)
      }

      "schedule the first round at the next available round exponent" in {
        // Say this validator is starting a bit late.
        val now = conf.genesisEraStart plus 5.hours
        val exp = 10

        implicit val clock = TestClock.frozen[Id](now)
        val runtime        = genesisEraRuntime(validator = "Alice".some, roundExponent = exp)

        val millisNext = Ticks.nextRound(runtime.startTick, exp)(Ticks(now.toEpochMilli))

        val agenda = runtime.initAgenda
        agenda should have size 1
        agenda.head.tick shouldBe millisNext
        agenda.head.action shouldBe Agenda.StartRound(Ticks(millisNext))
      }
    }

    "the validator is not bonded in the era" should {
      "not schedule anything" in {
        genesisEraRuntime("Anonymous".some).initAgenda shouldBe empty
        genesisEraRuntime(none).initAgenda shouldBe empty
      }
    }

    "the era is already over" should {
      "not schedule anything" in {
        implicit val clock = TestClock.frozen[Id](conf.genesisEraStart plus 700.days)
        genesisEraRuntime(validator = "Alice".some).initAgenda shouldBe empty
      }
    }

    "the era hasn't started yet" should {
      "schedule the first round for the beginning" in {
        implicit val clock = TestClock.frozen[Id](conf.genesisEraStart minus 8.hours)
        val runtime        = genesisEraRuntime(validator = "Alice".some)
        val agenda         = runtime.initAgenda
        agenda should have size 1
        agenda.head.tick shouldBe runtime.startTick
        agenda.head.action shouldBe Agenda.StartRound(runtime.startTick)
      }
    }
  }

  "handleMessage" when {
    "the validator is bonded" when {

      val leader = "Charlie"
      val runtime = genesisEraRuntime(
        "Alice".some,
        leaderSequencer = mockSequencer(leader),
        roundExponent = 1 // Every 2nd second.
      )

      "given a lambda message" when {

        "it is from the leader" should {
          "create a lambda response" in {
            val msg    = makeBlock(leader, runtime.era, runtime.startTick)
            val events = runtime.handleMessage(msg).written
            events should have size 2
            assertEvent(events) {
              case HighwayEvent.CreatedLambdaResponse(_) =>
            }
            assertEvent(events) {
              case HighwayEvent.HandledLambdaMessage =>
            }
          }

          "only cite the lambda message and validators own latest message" in {
            var forkChoiceFun: MockForkChoice.ForkChoiceFun =
              (_, _) => ForkChoice.Result(genesis, Set.empty)

            implicit val ds = defaultBlockDagStorage
            implicit val fc = MockForkChoice[Id](genesis, Some(forkChoiceFun(_, _)))

            val runtime =
              genesisEraRuntime("Alice".some, leaderSequencer = mockSequencer("Charlie"))

            val msgA = insert(makeBallot("Alice", runtime.era, runtime.startTick))
            val msgC = insert(makeBlock("Charlie", runtime.era, runtime.startTick))
            insert(makeBallot("Bob", runtime.era, runtime.startTick))
            insert(makeBallot("Charlie", runtime.era, runtime.startTick))

            forkChoiceFun = (_, js) => {
              js shouldBe Set(msgA.messageHash, msgC.messageHash)
              ForkChoice.Result(msgC, Set(msgC))
            }

            val events = runtime.handleMessage(msgC).written

            assertEvent(events) {
              case HighwayEvent.CreatedLambdaResponse(response) =>
                // It should return what the `forkChoiceFun` gave as result.
                response.justifications.map(_.latestBlockHash) shouldBe Seq(msgC.messageHash)
            }
          }

          "target the fork choice, not necessarily the lambda message" in {
            implicit val ds = defaultBlockDagStorage
            implicit val fc = defaultForkChoice

            val runtime =
              genesisEraRuntime("Alice".some, leaderSequencer = mockSequencer("Charlie"))

            // Bob makes a block, on top of which Alice sends a ballot.
            // Then Alice gets Charlie's lambda block, and responds with a ballot,
            // but the fork choice indicates Bob's block is still the one to target.

            val msgB = insert(makeBlock("Bob", runtime.era, runtime.startTick))
            insert(makeBallot("Alice", runtime.era, runtime.startTick, target = msgB.messageHash))
            val msgC = insert(makeBlock("Charlie", runtime.era, runtime.startTick))

            fc.set(msgB)

            val events = runtime.handleMessage(msgC).written

            assertEvent(events) {
              case HighwayEvent.CreatedLambdaResponse(response) =>
                response.parentBlock shouldBe msgB.messageHash
            }
          }

          "remember that a lambda message was received in this round" in {
            // This will be relevant when for round exponent adjustments.
            pending
          }
        }

        "the fork choice points to a previous era" should {
          "not respond" in {
            new ParentChildFixture(isLeaderInChild = false) {
              val msg = insert(makeBlock(leader, childRuntime.era, childRuntime.startTick))

              // By default it should respond.
              childRuntime.handleMessage(msg).written should not be empty

              FC.set(insert(makeBlock(leader, parentRuntime.era, parentRuntime.startTick)))

              childRuntime.handleMessage(msg).written shouldBe Vector(HandledLambdaMessage)
            }
          }
        }

        "it is not from the leader" should {
          "ignore the block" in {
            val msg = makeBlock("Bob", runtime.era, runtime.startTick)
            // These will fail validation but in case they didn't, don't respond.
            runtime.handleMessage(msg).written shouldBe empty
          }
        }

        "it is a switch block" should {
          implicit val ds = defaultBlockDagStorage

          // Let the leader make one block every hour. At the end of the genesis era,
          // the right key block should be picked for the child era.
          val blocks = insert(makeFullChain(leader, runtime, 1.hour))

          "create an era" in {
            implicit val es = MockEraStorage[Id]

            // The genesis era is going to be 2 weeks long
            val runtime = genesisEraRuntime("Alice".some, leaderSequencer = mockSequencer(leader))

            // The last block should be the switch block.
            val events = runtime.handleMessage(blocks.last).written

            assertEvent(events) {
              case HighwayEvent.CreatedEra(era) =>
                era.parentKeyBlockHash shouldBe runtime.era.keyBlockHash
                era.startTick shouldBe runtime.era.endTick

                // Block frequency in the test chain is 1 hour, so see how many we have should have in the era.
                val genesisHours = conf.genesisEraStart.until(conf.genesisEraEnd, ChronoUnit.HOURS)
                // Booking block is 10 days before the end of the era,
                val bookingIdx = (genesisHours - conf.bookingDuration.toHours).toInt
                // The key block is 3 hours later than the booking block.
                val keyIdx = (bookingIdx + conf.entropyDuration.toHours).toInt

                era.bookingBlockHash shouldBe blocks(bookingIdx).messageHash
                era.keyBlockHash shouldBe blocks(keyIdx).messageHash

                val expectedSeed = LeaderSequencer.seed(
                  // NOTE: The white paper says to use key block, the math paper booking block hash.
                  blocks(bookingIdx).messageHash.toByteArray,
                  blocks.slice(bookingIdx, keyIdx + 1).map(_.blockSummary.getHeader.magicBit)
                )

                era.leaderSeed.toByteArray shouldBe expectedSeed
            }
          }

          "not create an era if it already exists" in {
            implicit val es = MockEraStorage[Id]
            // The genesis era is going to be 2 weeks long
            val runtime = genesisEraRuntime("Alice".some, leaderSequencer = mockSequencer(leader))
            // The first time it's executed the switch block should create an era.
            assertEvent(runtime.handleMessage(blocks.last).written) {
              case HighwayEvent.CreatedEra(_) =>
            }
            // The second time, it should pass.
            runtime.handleMessage(blocks.last).written.collect {
              case HighwayEvent.CreatedEra(_) => fail("Should not have created an era again!")
            }
          }
        }

        "in a round which is not corresponding to the validator's current round ID" should {
          "not respond but trigger an updated of the finalizer state" in {
            val msg =
              makeBlock(leader, runtime.era, roundId = Ticks(runtime.startTick + 1))
            runtime.handleMessage(msg).written shouldBe Vector(HandledLambdaMessage)
          }
        }

        "it is from the validator itself" should {
          "throw IllegalStateException" in {
            an[IllegalStateException] shouldBe thrownBy {
              val msg = makeBlock(validator = "Alice", runtime.era, runtime.startTick)
              runtime.handleMessage(msg)
            }
          }
        }
      }

      "given a ballot" when {
        "in the normal era period" should {
          "not respond" in {
            val msg = makeBallot(leader, runtime.era, runtime.startTick)
            runtime.handleMessage(msg).written shouldBe empty
          }
        }
        "in the post-era voting period" when {
          implicit val clock = TestClock.frozen[Id](conf.genesisEraEnd)
          implicit val ds    = defaultBlockDagStorage
          implicit val fc    = defaultForkChoice
          val runtime        = genesisEraRuntime("Alice".some, leaderSequencer = mockSequencer("Bob"))

          val switch = fc.set(insert(makeBlock("Bob", runtime.era, runtime.endTick)))

          "coming from the leader" should {
            "create a lambda response" in {
              val msg    = makeBallot("Bob", runtime.era, runtime.endTick)
              val events = runtime.handleMessage(msg).written
              assertEvent(events) {
                case HighwayEvent.CreatedLambdaResponse(ballot: Message.Ballot) =>
                  ballot.roundId shouldBe msg.roundId
                  ballot.parentBlock shouldBe switch.messageHash
              }
              assertEvent(events) { case HighwayEvent.HandledLambdaMessage => }
            }
          }
          "coming from a non-leader" should {
            "not respond" in {
              val msg = makeBallot("Charlie", runtime.era, runtime.endTick)
              runtime.handleMessage(msg).written shouldBe empty
            }
          }
        }
      }
    }

    "the validator is not bonded" when {
      val leader      = "Alice"
      implicit val ds = defaultBlockDagStorage
      val runtime = genesisEraRuntime(
        "Anonymous".some,
        leaderSequencer = mockSequencer(leader)
      )
      val blocks = insert(makeFullChain(leader, runtime, 24.hours))

      "given a lambda message" should {
        "not respond" in {
          runtime.handleMessage(blocks.head).written shouldBe Vector(HandledLambdaMessage)
        }
      }

      "given a switch block" should {
        "create an era" in {
          assertEvent(runtime.handleMessage(blocks.last).written) {
            case _: HighwayEvent.CreatedEra =>
          }
        }
      }
    }

    "the message is played back during initial sync" should {
      "not respond" in {
        val leader      = "Alice"
        val isSyncedRef = Ref.of[Id, Boolean](false)
        val runtime = genesisEraRuntime(
          "Bob".some,
          isSyncedRef = isSyncedRef,
          leaderSequencer = mockSequencer(leader)
        )
        val msg = makeBlock(leader, runtime.era, runtime.startTick)
        runtime.handleMessage(msg).written shouldBe Vector(HandledLambdaMessage)
        isSyncedRef.set(true) // Works with `Id` only because of the `=> F[Boolean]`
        runtime.handleMessage(msg).written should not be empty
      }
    }
  }

  "handleAgenda" when {
    "given a StartRound action" when {
      "in the active period of the era" when {
        "the validator is the leader" should {
          val exponent    = 15
          val roundLength = Ticks.roundLength(exponent).millis
          val now         = conf.genesisEraStart plus (roundLength * 20)
          val roundId     = conf.toTicks(now)
          val nextRoundId =
            Ticks.nextRound(Ticks(conf.genesisEraStart.toEpochMilli), exponent)(roundId)

          implicit val clock = TestClock.frozen[Id](now)

          val runtime = genesisEraRuntime(
            "Alice".some,
            leaderSequencer = mockSequencer("Alice"),
            roundExponent = exponent
          )

          val (events, agenda) = runtime.handleAgenda(Agenda.StartRound(roundId)).run

          "create a lambda message" in {
            events should have size 2
            assertEvent(events) {
              case event: HighwayEvent.CreatedLambdaMessage =>
                event.message.roundId shouldBe roundId
            }
            assertEvent(events) {
              case HighwayEvent.HandledLambdaMessage =>
            }
          }

          "not schedule more than 1 round" in {
            agenda should have size (2)
          }

          "schedule another round" in {
            assertAgenda(agenda) {
              case Agenda.DelayedAction(tick, start: Agenda.StartRound) =>
                tick shouldBe nextRoundId
                start.roundId shouldBe nextRoundId
            }
          }

          "schedule an omega message" in {
            assertAgenda(agenda) {
              case Agenda.DelayedAction(_, omega: Agenda.CreateOmegaMessage) =>
                omega.roundId shouldBe roundId
            }
          }

          "randomize the omega delay" in {
            implicit val clock = TestClock.adjustable[Id](now)

            val runtime = genesisEraRuntime(
              "Alice".some,
              leaderSequencer = mockSequencer("Alice"),
              roundExponent = exponent
            )

            val omegaDelays: List[Long] = List
              .range(0L, 10L)
              .map { i =>
                val instant = conf.genesisEraStart plus (roundLength * i)
                val roundId = conf.toTicks(instant)

                // Omega is based on time, not just the round.
                clock.set(conf.toInstant(roundId))

                val omegaTick =
                  runtime
                    .handleAgenda(Agenda.StartRound(roundId))
                    .value
                    .collectFirst {
                      case Agenda.DelayedAction(tick, _: Agenda.CreateOmegaMessage) =>
                        tick
                    }
                    .get

                omegaTick - roundId
              }

            omegaDelays.toSet.size should be > 1

            val ticksPerRound = Ticks.roundLength(exponent)

            forAll(omegaDelays) { ticks =>
              ticks shouldBe >=((ticksPerRound * conf.omegaMessageTimeStart).toLong)
              ticks shouldBe <=((ticksPerRound * conf.omegaMessageTimeEnd).toLong)
            }
          }
        }

        "the validator is not leading" should {
          val runtime = genesisEraRuntime(
            "Alice".some,
            leaderSequencer = mockSequencer("Bob")
          )
          val (events, agenda) = runtime.handleAgenda(Agenda.StartRound(runtime.startTick)).run

          "not create a lambda message" in {
            events shouldBe empty
          }
          "schedule another round" in {
            forExactly(1, agenda) { a =>
              a.action shouldBe an[Agenda.StartRound]
            }
          }
          "schedule an omega message" in {
            forExactly(1, agenda) { a =>
              a.action shouldBe an[Agenda.CreateOmegaMessage]
            }
          }
        }

        "the fork choice points at a previous era" should {
          "skip the round" in {
            new ParentChildFixture(isLeaderInChild = true) {
              // By default we should be able to create a lambda.
              childRuntime
                .handleAgenda(Agenda.StartRound(childRuntime.startTick))
                .written should not be empty

              FC.set(insert(makeBlock(leader, parentRuntime.era, parentRuntime.startTick)))

              val (events, agenda) =
                childRuntime.handleAgenda(Agenda.StartRound(Ticks(childRuntime.startTick + 1))).run

              events shouldBe empty
              agenda should not be empty
            }
          }
        }
      }

      "right after the era-end" when {
        "no previous switch block has been seen" should {
          "create a switch block and an era" in {
            val runtime = genesisEraRuntime("Alice".some, leaderSequencer = mockSequencer("Alice"))
            val roundId = runtime.endTick // anything at or after the end tick.
            val events  = runtime.handleAgenda(Agenda.StartRound(roundId)).written
            assertEvent(events) {
              case _: HighwayEvent.CreatedEra =>
            }
            assertEvent(events) {
              case HighwayEvent.CreatedLambdaMessage(b: Message.Block) =>
                b.roundId shouldBe roundId
                b.eraId shouldBe runtime.era.keyBlockHash
            }
          }
        }
        "already found a switch block" should {
          "only create ballots" in {
            implicit val fc = defaultForkChoice
            val runtime     = genesisEraRuntime("Alice".some, leaderSequencer = mockSequencer("Alice"))
            val switch =
              makeBlock("Alice", runtime.era, runtime.endTick, mainParent = genesis.messageHash)
            fc.set(switch)
            // TODO (NODE-1116): return ballots in voting period
            //val events = runtime.handleAgenda(Agenda.StartRound(Ticks(runtime.endTick + 1))).written
            // assertEvent(events) {
            //   case HighwayEvent.CreatedLambdaMessage(_: Message.Ballot) =>
            // }
          }
        }
      }

      "in the post-era voting period" when {
        class PostEraFixture(
            isLeader: Boolean = false,
            postEraElapsed: FiniteDuration = 20.seconds
        ) {
          val exponent       = 14 // ~15 seconds
          val now            = conf.genesisEraEnd plus postEraElapsed
          implicit val clock = TestClock.frozen(now)
          implicit val ds    = defaultBlockDagStorage
          implicit val fc    = defaultForkChoice
          val leader         = "Alice"
          val validator      = if (isLeader) leader else "Bob"
          val runtime = genesisEraRuntime(
            validator.some,
            roundExponent = exponent,
            leaderSequencer = mockSequencer(leader)
          )
          // Pick the beginning of the round where `now` is included
          val roundId = Ticks.roundBoundaries(runtime.startTick, exponent)(conf.toTicks(now))._1

          def handle = runtime.handleAgenda(Agenda.StartRound(roundId))
        }
        "the validator is the leader" should {
          "create a switch block if it builds on a non-switch block" in {
            new PostEraFixture(isLeader = true) {
              val nonSwitch =
                fc.set(insert(makeBlock(validator, runtime.era, runtime.startTick)))

              assertEvent(handle.written) {
                case HighwayEvent.CreatedLambdaMessage(switch: Message.Block) =>
                  switch.roundId shouldBe roundId
                  switch.parentBlock shouldBe nonSwitch.messageHash
              }
            }
          }
          "create a ballot instead of a block when already building on a switch block" in {
            new PostEraFixture(isLeader = true) {
              val switch =
                fc.set(insert(makeBlock(validator, runtime.era, runtime.endTick)))

              assertEvent(handle.written) {
                case HighwayEvent.CreatedLambdaMessage(ballot: Message.Ballot) =>
                  ballot.roundId shouldBe roundId
                  ballot.parentBlock shouldBe switch.messageHash
              }
            }
          }
          "create a ballot even if the switch block was an omega-block" in {
            new PostEraFixture(isLeader = true) {
              val switch =
                fc.set(
                  insert(
                    makeBlock(
                      validator,
                      runtime.era,
                      runtime.endTick,
                      messageRole = Block.MessageRole.WITNESS
                    )
                  )
                )

              val events = handle.written

              assertEvent(events) {
                case HighwayEvent.CreatedLambdaMessage(ballot: Message.Ballot) =>
                  ballot.roundId shouldBe roundId
                  ballot.parentBlock shouldBe switch.messageHash
                  ballot.messageRole shouldBe Block.MessageRole.PROPOSAL
              }

              assertEvent(events) {
                case HighwayEvent.HandledLambdaMessage =>
              }
            }
          }
        }
        "the voting is still going" should {
          "schedule an omega message" in {
            new PostEraFixture {
              assertAgenda(handle.value) {
                case Agenda.DelayedAction(_, a: Agenda.CreateOmegaMessage) =>
                  a.roundId shouldBe roundId
              }
            }
          }
          "schedule another round" in {
            new PostEraFixture {
              assertAgenda(handle.value) {
                case Agenda.DelayedAction(_, a: Agenda.StartRound) =>
                  a.roundId should be > roundId.toLong
              }
            }
          }
        }
        "the fixed voting period is almost over" should {
          "not schedule a next round after the end" in {
            new PostEraFixture(postEraElapsed = postEraVotingDuration minus 1.second) {
              handle.value.map(_.action).collect {
                case Agenda.StartRound(_) =>
                  fail("Should not schedule more rounds after the voting ends.")
              }
            }
          }
          "schedule an omega message for the last round" in {
            new PostEraFixture(postEraElapsed = postEraVotingDuration minus 1.second) {
              assertAgenda(handle.value) {
                case Agenda.DelayedAction(_, Agenda.CreateOmegaMessage(_)) =>
              }
            }
          }
        }
        "the summit based voting is finalized" should {
          "not schedule any more rounds" in {
            val summitConf = conf.copy(
              postEraVotingDuration = VotingDuration.SummitLevel(1)
            )
            implicit val clock = TestClock.frozen(summitConf.genesisEraEnd)
            implicit val fs    = defaultFinalityStorage
            implicit val ds    = defaultBlockDagStorage
            implicit val fc    = defaultForkChoice

            val runtime = genesisEraRuntime(validator = "Alice".some, config = summitConf)

            val switch = insert(makeBlock("Alice", runtime.era, runtime.endTick))
            fc.set(switch)
            fs.markAsFinalized(switch.messageHash, finalized = Set.empty, orphaned = Set.empty)

            val agenda = runtime.handleAgenda(Agenda.StartRound(Ticks(runtime.endTick))).value

            agenda.map(_.action) collect {
              case Agenda.StartRound(_) =>
                fail("Should not schedule more rounds after the switch block is finalized.")
            }
          }
        }
      }

      "during initial sync" should {
        val runtime = genesisEraRuntime(
          "Alice".some,
          leaderSequencer = mockSequencer("Alice"),
          isSyncedRef = Ref.of[Id, Boolean](false)
        )

        val (events, agenda) = runtime.handleAgenda(Agenda.StartRound(runtime.startTick)).run

        "not create a lambda message" in {
          events shouldBe empty
        }
        "schedule another round" in {
          forExactly(1, agenda) { a =>
            a.action shouldBe an[Agenda.StartRound]
          }
        }
        "schedule an omega message" in {
          forExactly(1, agenda) { a =>
            a.action shouldBe an[Agenda.CreateOmegaMessage]
          }
        }
      }

      "creating the lambda message takes longer than a round" should {
        "skip to the lambda in the next active round but schedule an omega for it" in {
          val exponent       = 15 // ~30s
          val roundLength    = Ticks.roundLength(exponent).millis
          val roundStart     = conf.genesisEraStart plus 60 * roundLength
          val now            = roundStart plus 3 * roundLength
          val currentTick    = conf.toTicks(now)
          implicit val clock = TestClock.frozen[Id](now)

          val runtime = genesisEraRuntime("Alice".some, roundExponent = exponent)

          // Executing this round that was supposed to have been done a while ago.
          val roundId = conf.toTicks(roundStart)
          val agenda  = runtime.handleAgenda(Agenda.StartRound(roundId)).value

          // Next round should be in the future.
          assertAgenda(agenda) {
            case Agenda.DelayedAction(tick, Agenda.StartRound(nextRoundId)) =>
              tick should be > currentTick.toLong
              nextRoundId should be > currentTick.toLong
              nextRoundId should be > roundId + roundLength.toMillis
          }

          // Omega should be for this round.
          assertAgenda(agenda) {
            case Agenda.DelayedAction(tick, Agenda.CreateOmegaMessage(currentRoundId)) =>
              tick should be > currentRoundId.toLong
              currentRoundId should be <= currentTick.toLong
              conf.toInstant(currentRoundId) should be >= (now minus roundLength)
          }
        }

        "not schedule an omega if it's after the end of the voting period" in {
          val now            = conf.genesisEraEnd plus postEraVotingDuration plus 1.hour
          implicit val clock = TestClock.frozen[Id](now)
          val roundId        = conf.toTicks(now)
          val runtime        = genesisEraRuntime("Alice".some)
          val agenda         = runtime.handleAgenda(Agenda.StartRound(roundId)).value

          agenda shouldBe empty
        }
      }

      "crossing the booking block boundary" should {
        "pass the flag to the message producer" in {
          implicit val ds = defaultBlockDagStorage
          implicit val fc = defaultForkChoice

          val messageProducer = new MockMessageProducer[Id]("Alice") {
            override def block(
                eraId: ByteString,
                roundId: Ticks,
                mainParent: Message.Block,
                justifications: Map[Keys.PublicKeyHashBS, Set[Message]],
                isBookingBlock: Boolean,
                messageRole: Block.MessageRole
            ): Id[Message.Block] = {
              isBookingBlock shouldBe true
              mainParent.messageHash shouldBe fc.fromKeyBlock(eraId).block.messageHash
              justifications shouldBe fc.fromKeyBlock(eraId).justificationsMap

              super.block(eraId, roundId, mainParent, justifications, isBookingBlock, messageRole)
            }
          }

          val runtime = genesisEraRuntime(
            "Alice".some,
            leaderSequencer = mockSequencer("Alice"),
            messageProducer = _ => _ => messageProducer
          )

          val prev = insert(makeBlock("Alice", runtime.era, runtime.startTick))
          fc.set(ForkChoice.Result(prev, Set(prev)))

          // Do a round which is surely after the booking time.
          val events = runtime.handleAgenda(Agenda.StartRound(runtime.endTick)).written

          assertEvent(events) {
            case _: HighwayEvent.CreatedLambdaMessage =>
          }
        }
      }
    }

    "given a CreateOmegaMessage action" when {
      "during initial sync" should {
        "not create an omega message" in {
          val runtime = genesisEraRuntime(
            "Alice".some,
            isSyncedRef = Ref.of[Id, Boolean](false)
          )
          val (events, agenda) =
            runtime.handleAgenda(Agenda.CreateOmegaMessage(runtime.startTick)).run

          events shouldBe empty
          agenda shouldBe empty
        }
      }
      "the era is active" should {
        "create an omega message" in {
          val runtime = genesisEraRuntime("Alice".some)
          val events =
            runtime.handleAgenda(Agenda.CreateOmegaMessage(runtime.startTick)).written
          assertEvent(events) {
            case HighwayEvent.CreatedOmegaMessage(msg) =>
              msg.isBallot shouldBe true
          }
        }
        "create a block if there are pending deploys in the buffer" in {
          val runtime =
            genesisEraRuntime("Alice".some, messageProducer = messageProducerWithPendingDeploys)
          val events =
            runtime.handleAgenda(Agenda.CreateOmegaMessage(runtime.startTick)).written

          assertEvent(events) {
            case HighwayEvent.CreatedOmegaMessage(msg) =>
              msg.isBlock shouldBe true
              msg.messageRole shouldBe Block.MessageRole.WITNESS
          }
        }
      }
      "in the post-era voting period" should {
        implicit val clock = TestClock.frozen(conf.genesisEraEnd)
        "create an omega message" in {
          val runtime = genesisEraRuntime("Alice".some)
          val events =
            runtime.handleAgenda(Agenda.CreateOmegaMessage(runtime.endTick)).written
          assertEvent(events) {
            case HighwayEvent.CreatedOmegaMessage(_) =>
          }
        }
        "create a switch block if there are pending deploys in the buffer" in {
          val runtime =
            genesisEraRuntime("Alice".some, messageProducer = messageProducerWithPendingDeploys)
          val events =
            runtime.handleAgenda(Agenda.CreateOmegaMessage(runtime.endTick)).written

          assertEvent(events) {
            case HighwayEvent.CreatedOmegaMessage(msg) =>
              msg.isBlock shouldBe true
              msg.messageRole shouldBe Block.MessageRole.WITNESS
          }
        }
        "create a ballot if there are deploys but a switch block has been created before" in {
          implicit val ds = defaultBlockDagStorage
          implicit val fc = defaultForkChoice

          val runtime =
            genesisEraRuntime("Alice".some, messageProducer = messageProducerWithPendingDeploys)

          fc.set(insert(makeBlock("Alice", runtime.era, runtime.endTick)))

          val events =
            runtime.handleAgenda(Agenda.CreateOmegaMessage(runtime.endTick)).written

          assertEvent(events) {
            case HighwayEvent.CreatedOmegaMessage(_: Message.Ballot) =>
          }
        }
      }
      "the fork choice points at a previous era" should {
        "skip the round" in {
          new ParentChildFixture(isLeaderInChild = false) {
            // By default it should create an omega.
            childRuntime
              .handleAgenda(Agenda.CreateOmegaMessage(childRuntime.startTick))
              .written should not be empty

            FC.set(insert(makeBlock(leader, parentRuntime.era, parentRuntime.startTick)))

            childRuntime
              .handleAgenda(Agenda.CreateOmegaMessage(Ticks(childRuntime.startTick + 1)))
              .written shouldBe empty
          }
        }
      }
    }
  }

  "collectMagicBits" should {
    "collect bits from the booking to the key block" in {
      implicit val ds = defaultBlockDagStorage
      val runtime     = genesisEraRuntime()
      // Any chain will do.
      val blocks = insert(makeFullChain("Bob", runtime, 8.hours))

      val bounds = for {
        a <- Gen.choose(0, blocks.size - 1)
        b <- Gen.choose(a, blocks.size - 1)
      } yield (a, b)

      forAllGen(bounds) {
        case (bookingIdx, keyIdx) =>
          val exp =
            blocks
              .drop(bookingIdx)
              .take(keyIdx - bookingIdx + 1)
              .map(_.blockSummary.getHeader.magicBit)

          val bits = EraRuntime
            .collectMagicBits[Id](ds.getRepresentation, blocks(bookingIdx), blocks(keyIdx))
            .toArray

          bits should contain theSameElementsInOrderAs exp
      }
    }
  }

  "isSameRoundAs" should {
    "take the era into account" in {
      val runtime = genesisEraRuntime()
      val a       = makeBlock("Alice", runtime.era, runtime.startTick)
      val b       = makeBlock("Bob", runtime.era, runtime.startTick)
      val c       = makeBlock("Charlie", runtime.era, runtime.endTick)
      val d       = makeBlock("Alice", runtime.era.copy(keyBlockHash = c.messageHash), runtime.startTick)
      EraRuntime.isSameRoundAs(a)(b) shouldBe true
      EraRuntime.isSameRoundAs(a)(c) shouldBe false
      EraRuntime.isSameRoundAs(a)(d) shouldBe false
    }
  }

  "omegaOffset" should {
    "go from 0.0 to 1.0" in {
      val expected = List(
        "Alice"   -> 0.0,
        "Bob"     -> 0.33,
        "Charlie" -> 0.66,
        "Dave"    -> 1.0
      )
      val names = expected.unzip._1

      Inspectors.forAll(expected) {
        case (name, offset) =>
          EraRuntime.omegaOffset(names, name) shouldBe offset +- 0.01
      }
    }

    "return 0.5 for single item" in {
      EraRuntime.omegaOffset(List("Alice"), "Alice") shouldBe 0.5
    }
  }
}

object EraRuntimeSpec {
  import cats.Monad
  import cats.implicits._

  implicit def validatorKey(name: String): Keys.PublicKeyHashBS =
    Keys.PublicKeyHash(ByteString.copyFromUtf8(name))

  implicit class MessageOps(msg: Message) {
    def toBlock: Block = {
      val s = msg.blockSummary
      Block(s.blockHash, s.header, None, s.signature)
    }
  }

  private val blockHashes =
    Stream.iterate(0)(_ + 1).map(i => ByteString.copyFromUtf8(i.toString)).iterator

  implicit val prettifier = Prettifier {
    case x: ByteString => new String(x.toByteArray)
    case other         => Prettifier.default(other)
  }

  def toJustifications(messages: Message*): Map[Keys.PublicKeyHashBS, ByteString] =
    messages.map(m => m.validatorId -> m.messageHash).toMap

  def mockSequencer(validator: String) = new LeaderSequencer {
    def leaderFunction[F[_]: MonadThrowable](era: Era): F[LeaderFunction] =
      ((_: Ticks) => validatorKey(validator)).pure[F]

    def omegaFunction[F[_]: MonadThrowable](era: Era): F[OmegaFunction] =
      // It was completely random before, so we can use the real randomizer.
      LeaderSequencer.omegaFunction[F](era)
  }

  def insert[F[_]: Monad, A <: Message](
      messages: Seq[A]
  )(implicit ds: MockBlockDagStorage[F]): F[Seq[A]] =
    messages.toList.traverse(insert[F, A]).map(_.toSeq)

  def insert[F[_]: Monad, A <: Message](message: A)(implicit ds: MockBlockDagStorage[F]): F[A] =
    ds.insert(message.toBlock).as(message)

  // XXX: This is an unsafe, incorrect implementation of a Semaphore,
  // but these tests don't do concurrency at the moment.
  // The other option is to rewrite all of them to use Task.
  implicit val makeSemaphoreId: MakeSemaphore[Id] =
    new MakeSemaphore[Id] {
      override def apply(n: Long): cats.Id[Semaphore[cats.Id]] = new Semaphore[Id] {
        val s = new java.util.concurrent.Semaphore(n.toInt)

        override def available =
          s.availablePermits().toLong
        override def count =
          // Not exactly, if threads were requiring more than 1 permit.
          available - s.getQueueLength()
        override def acquireN(n: Long)    = s.acquire(n.toInt)
        override def tryAcquireN(n: Long) = s.tryAcquire(n.toInt)
        override def releaseN(n: Long)    = s.release(n.toInt)
        // XXX: t is already evaluated when passed to `withPermit` so there's nothing we can do.
        override def withPermit[A](t: cats.Id[A]) = t
      }
    }
}
