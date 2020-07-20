package io.casperlabs.casper.highway

import java.time.Instant
import java.util.concurrent.TimeUnit

import cats.effect.concurrent.Ref
import cats.effect.{Resource, Timer}
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.DeploySelection
import io.casperlabs.casper.consensus.state.ProtocolVersion
import io.casperlabs.casper.consensus.{state, Block, Bond, Era}
import io.casperlabs.casper.finality.MultiParentFinalizer
import io.casperlabs.casper.helper.{NoOpsEventEmitter, StorageFixture}
import io.casperlabs.casper.highway.mocks.{MockForkChoice, MockMessageProducer}
import io.casperlabs.casper.mocks.NoOpValidation
import io.casperlabs.casper.util.{ByteStringPrettifier, CasperLabsProtocol}
import io.casperlabs.casper.util.execengine.ExecutionEngineServiceStub
import io.casperlabs.casper.validation.{raiseValidateErrorThroughApplicativeError, Validation}
import io.casperlabs.comm.discovery.Node
import io.casperlabs.comm.gossiping.WaitHandle
import io.casperlabs.comm.gossiping.relaying.BlockRelaying
import io.casperlabs.crypto.Keys
import io.casperlabs.ipc.ChainSpec.DeployConfig
import io.casperlabs.mempool.DeployBuffer
import io.casperlabs.models.{ArbitraryConsensus, Message}
import io.casperlabs.shared.{Log, LogStub}
import io.casperlabs.storage.{BlockHash, BlockMsgWithTransform, SQLiteStorage}
import monix.catnap.SchedulerEffect
import monix.eval.Task
import monix.execution.schedulers.TestScheduler
import org.scalatest.Suite

import scala.concurrent.duration._

trait HighwayFixture
    extends StorageFixture
    with TickUtils
    with ByteStringPrettifier
    with ArbitraryConsensus { self: Suite =>

  /** Create multiple databases, one for each validator. */
  def testFixtures(validators: List[String], timeout: FiniteDuration = 10.seconds)(
      f: Timer[Task] => List[(String, SQLiteStorage.CombinedStorage[Task])] => FixtureLike
  ): Unit = {
    val ctx   = TestScheduler()
    val timer = SchedulerEffect.timer[Task](ctx)
    withCombinedStorages(numStorages = validators.size, timeout = timeout) { dbs =>
      Task.async[Unit] { cb =>
        val fix = f(timer)(validators zip dbs)
        // TestScheduler allows us to manually forward time.
        // To get meaningful round IDs, we must start from the genesis.
        ctx.forwardTo(fix.start)
        // Without an extra delay the TestScheduler executes tasks immediately.
        fix.test.delayExecution(0.seconds).runAsync(cb)(ctx)
        // Now allow the tests to run forward until the end.
        ctx.forwardTo(fix.start plus fix.length)
        // There shouldn't be any uncaught exceptions.
        assert(ctx.state.lastReportedError == null)
      }
    }
  }

  /** Create one database to test with. */
  def testFixture(f: Timer[Task] => SQLiteStorage.CombinedStorage[Task] => FixtureLike): Unit =
    testFixtures(validators = List("")) { timer => dbs =>
      f(timer)(dbs.head._2)
    }

  // Allow using strings for validator names where a ByteString key is required.
  implicit def `String => PublicKeyHashBS`(s: String): Keys.PublicKeyHashBS =
    Keys.PublicKeyHash(ByteString.copyFromUtf8(s))

  import HighwayConf.{EraDuration, VotingDuration}

  val genesisEraStart       = date(2019, 12, 30)
  val eraDuration           = days(7)
  val bookingDuration       = days(10)
  val postEraVotingDuration = days(2)

  val defaultConf = HighwayConf(
    tickUnit = TimeUnit.SECONDS,
    genesisEraStart = genesisEraStart,
    eraDuration = EraDuration.FixedLength(eraDuration),
    bookingDuration = bookingDuration,
    entropyDuration = hours(3),
    postEraVotingDuration = VotingDuration.FixedLength(postEraVotingDuration),
    omegaMessageTimeStart = 0.5,
    omegaMessageTimeEnd = 0.75,
    omegaBlocksEnabled = false
  )

  trait FixtureLike {
    // Where to set the TestScheduler to go from.
    def start: Instant
    // How long a time to simulate in the TestScheduler.
    def length: FiniteDuration
    // Override this value to make sure the assertions are executed.
    def test: Task[Unit]
  }

  abstract class Fixture(
      override val length: FiniteDuration,
      val conf: HighwayConf = defaultConf,
      val validator: String = "Alice",
      val initRoundExponent: Int = 0,
      val isSyncedRef: Ref[Task, Boolean] = Ref.unsafe(true),
      printLevel: Log.Level = Log.Level.Error
  )(
      implicit
      timer: Timer[Task],
      db: SQLiteStorage.CombinedStorage[Task]
  ) extends FixtureLike {

    // Genesis validators.
    def bonds: List[Bond] = List(
      Bond("Alice").withStake(state.BigInt("3000")),
      Bond("Bob").withStake(state.BigInt("4000")),
      Bond("Charlie").withStake(state.BigInt("5000"))
    )

    override val start = conf.genesisEraStart

    val chainName = "highway-test-chain"

    val genesisBlock = {
      val emptyStateHash = ByteString.copyFromUtf8("empty-state")
      Block()
        .withBlockHash(ByteString.copyFromUtf8("genesis"))
        .withHeader(
          Block
            .Header()
            .withChainName(chainName)
            .withState(
              Block
                .GlobalState()
                .withBonds(bonds)
                .withPreStateHash(emptyStateHash)
                .withPostStateHash(emptyStateHash)
            )
        )
        .withBody(Block.Body())
    }

    val genesis = Message.fromBlock(genesisBlock).get.asInstanceOf[Message.Block]

    implicit val log: Log[Task] with LogStub =
      LogStub[Task](
        prefix = validator.padTo(10, " ").mkString(""),
        printEnabled = true,
        printLevel = printLevel
      )

    implicit lazy val eventEmitter = NoOpsEventEmitter.create[Task]

    implicit lazy val forkchoice = MockForkChoice.unsafe[Task](genesis)

    implicit lazy val finalizer = new MultiParentFinalizer[Task] {

      override def addMessage(message: Message): Task[Unit] = Task.unit

      override def checkFinality(): Task[Seq[MultiParentFinalizer.FinalizedBlocks]] =
        Task(Seq.empty)
    }

    implicit lazy val deployBuffer = DeployBuffer.create[Task](chainName, minTtl = Duration.Zero)

    implicit lazy val execEngineService =
      ExecutionEngineServiceStub.noOpApi[Task](
        bonds = genesisBlock.getHeader.getState.bonds
      )

    implicit lazy val deploySelection = DeploySelection.create[Task]()

    implicit val validationRaise = raiseValidateErrorThroughApplicativeError[Task]
    implicit val protocol = CasperLabsProtocol.unsafe[Task](
      (
        0L,
        ProtocolVersion(0, 0, 0),
        Some(
          DeployConfig()
            .withMaxTtlMillis(24 * 60 * 60 * 1000) // 1 day
            .withMaxDependencies(10)
            .withMaxBlockSizeBytes(10 * 1024 * 1024)
            .withMaxBlockCost(0)
        )
      )
    )
    // While we're using the MockMessageProducer we can't fully validate blocks.
    implicit lazy val validation: Validation[Task] = new NoOpValidation[Task]

    lazy val messageProducer: MessageProducer[Task] = new MockMessageProducer[Task](validator)

    lazy val messageExecutor: MessageExecutor[Task] = new MessageExecutor[Task](
      chainName = chainName,
      genesis = genesisBlock,
      upgrades = Seq.empty,
      maybeValidatorId = Some(validator: Keys.PublicKeyHashBS)
    )

    implicit lazy val blockRelaying = new BlockRelaying[Task] {
      override def relay(hashes: List[BlockHash], sources: Set[Node]): Task[WaitHandle[Task]] =
        ().pure[Task].pure[Task]
    }

    def addGenesisEra(): Task[Era] = {
      val era = EraRuntime.genesisEra(conf, genesis.blockSummary)
      db.addEra(era).as(era)
    }

    implicit class EraOps(era: Era) {
      def addChildEra(keyBlockHash: BlockHash = ByteString.EMPTY): Task[Era] = {
        val nextEndTick = conf.toTicks(conf.eraEnd(conf.toInstant(Ticks(era.endTick))))
        val randomEra   = sample[Era]

        for {
          keyBlock <- if (keyBlockHash.isEmpty) {
                       era.block(messageProducer, era.keyBlockHash).flatMap(db.lookupUnsafe)
                     } else {
                       db.lookupUnsafe(keyBlockHash)
                     }

          childEra = randomEra
            .withKeyBlockHash(keyBlock.messageHash)
            .withParentKeyBlockHash(era.keyBlockHash)
            .withStartTick(era.endTick)
            .withEndTick(nextEndTick)
            .withBonds(era.bonds)

          _ <- db.addEra(childEra)
        } yield childEra
      }

      // It's the same as in `ForkChoiceTest` :( or vice versa
      def ballot(mp: MessageProducer[Task], parent: BlockHash): Task[BlockHash] =
        for {
          dag         <- db.getRepresentation
          tips        <- dag.latestInEra(era.keyBlockHash)
          latest      <- tips.latestMessages
          parentBlock <- dag.lookupBlockUnsafe(parent)
          justifications = latest.map {
            case (v, ms) => Keys.PublicKeyHash(v) -> ms
          }
          b <- mp.ballot(
                era.keyBlockHash,
                roundId = Ticks(era.startTick),
                target = parentBlock,
                justifications = justifications,
                messageRole = Block.MessageRole.WITNESS
              )
        } yield b.messageHash

      def block(mp: MessageProducer[Task], parent: BlockHash): Task[BlockHash] =
        for {
          dag         <- db.getRepresentation
          tips        <- dag.latestInEra(era.keyBlockHash)
          parentBlock <- dag.lookupBlockUnsafe(parent)
          latest      <- tips.latestMessages
          justifications = latest.map {
            case (v, ms) => Keys.PublicKeyHash(v) -> ms
          }
          b <- mp.block(
                era.keyBlockHash,
                roundId = Ticks(era.startTick),
                mainParent = parentBlock,
                justifications = justifications,
                isBookingBlock = false,
                messageRole = Block.MessageRole.PROPOSAL
              )
        } yield b.messageHash
    }

    def makeRuntime(era: Era): Task[EraRuntime[Task]] =
      EraRuntime.fromEra[Task](
        conf,
        era,
        messageProducer.some,
        initRoundExponent,
        isSyncedRef.get
      )

    def insertGenesis(): Task[Unit] =
      db.put(BlockMsgWithTransform().withBlockMessage(genesisBlock)) *>
        db.markAsFinalized(
          genesis.messageHash,
          Set.empty,
          Set.empty
        )

    def makeSupervisor(): Resource[Task, EraSupervisor[Task]] =
      for {
        _ <- Resource.liftF(insertGenesis())
        supervisor <- EraSupervisor[Task](
                       conf,
                       genesis.blockSummary,
                       messageProducer.some,
                       messageExecutor,
                       initRoundExponent,
                       isSyncedRef.get
                     )
      } yield supervisor

    /** Use this to wait for future conditions in the test, don't try to use `.tick()`
      * because that can only be called outside the test's for comprehension and it's
      * done at the top. But sleeps will schedule the tasks that tell the TestScheduler
      * when the next job is due.
      */
    def sleepUntil(t: Instant): Task[Unit] =
      for {
        now   <- Timer[Task].clock.realTime(conf.tickUnit)
        later = conf.toTicks(t)
        delay = math.max(later - now, 0L)
        _     <- Timer[Task].sleep(FiniteDuration(delay, conf.tickUnit))
      } yield ()
  }
}
