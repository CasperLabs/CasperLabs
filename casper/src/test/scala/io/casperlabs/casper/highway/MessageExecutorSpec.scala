package io.casperlabs.casper.highway

import java.util.concurrent.atomic.AtomicReference

import cats.implicits._
import cats.effect.{Clock, Timer}
import cats.effect.concurrent.{Ref, Semaphore}
import com.google.protobuf.ByteString
import io.casperlabs.casper.{DeploySelection, ValidatorIdentity}
import io.casperlabs.casper.consensus.{Block, Bond}
import io.casperlabs.casper.consensus.state
import io.casperlabs.casper.mocks.{MockEventEmitter, NoOpValidation}
import io.casperlabs.casper.validation
import io.casperlabs.casper.validation.{HighwayValidationImpl, Validation}
import io.casperlabs.casper.validation.Errors.ValidateErrorWrapper
import io.casperlabs.casper.finality.MultiParentFinalizer
import io.casperlabs.casper.validation.Validation.BlockEffects
import io.casperlabs.casper.scalatestcontrib._
import io.casperlabs.casper.consensus.info.DeployInfo
import io.casperlabs.casper.util.ProtoUtil
import io.casperlabs.casper.{EquivocatedBlock, Valid, ValidatorIdentity}
import io.casperlabs.crypto.Keys, Keys.{PrivateKey, PublicKey}
import io.casperlabs.crypto.signatures.SignatureAlgorithm.Ed25519
import io.casperlabs.models.Message
import io.casperlabs.mempool.DeployBuffer
import io.casperlabs.storage.{BlockHash, SQLiteStorage}
import io.casperlabs.storage.block.BlockStorage
import io.casperlabs.storage.dag.DagStorage
import io.casperlabs.storage.deploy.DeployStorage
import io.casperlabs.shared.Log
import monix.eval.Task
import org.scalatest._

import scala.concurrent.duration._
import io.casperlabs.storage.dag.DagRepresentation
import io.casperlabs.storage.dag.FinalityStorage

class MessageExecutorSpec extends FlatSpec with Matchers with Inspectors with HighwayFixture {

  def executorFixture(f: SQLiteStorage.CombinedStorage[Task] => ExecutorFixture): Unit =
    withCombinedStorage() { db =>
      f(db).test
    }

  implicit val consensusConfig = ConsensusConfig()

  // Pick a key pair we can sign with.
  val thisValidator  = sample(genAccountKeys)
  val otherValidator = sample(genAccountKeys)

  implicit class AccountKeyOps(keys: AccountKeys) {
    // Resign a block after we messed with its contents.
    def signBlock(block: Block): Block = {
      val bodyHash  = protoHash(block.getBody)
      val header    = block.getHeader.withBodyHash(bodyHash)
      val blockHash = protoHash(header)
      block
        .withHeader(header)
        .withBlockHash(blockHash)
        .withSignature(keys.sign(blockHash))
    }
  }

  implicit def `Message => ValidatedMessage`(m: Message): ValidatedMessage = Validated(m)

  /** A fixture based on the Highway one where we won't be using the TestScheduler,
    * just call methods on the `messageExecutor` and override its dependencies to
    * turn validation on/off and capture the effects it carries out.
    */
  abstract class ExecutorFixture(
      store: SQLiteStorage.CombinedStorage[Task],
      printLevel: Log.Level = Log.Level.Error,
      validate: Boolean = false
  ) extends Fixture(
        length = Duration.Zero,
        printLevel = printLevel
      ) (implicitly[Timer[Task]], store) {

    // Passed `store` to the base class explicitly to avoid a compiler bug,
    // the NullPointerException of the type checker that can happen.
    implicit val db = store

    override lazy val bonds = List(
      // Our validators needs to be bonded in Genesis, so the blocks created by them don't get rejected.
      Bond(thisValidator.publicKeyHash).withStake(state.BigInt("10000")),
      Bond(otherValidator.publicKeyHash).withStake(state.BigInt("5000"))
    )

    // Make a message producer that's supposed to make valid blocks.
    def makeMessageProducer(keys: AccountKeys): MessageProducer[Task] = {
      implicit val deployBuffer    = DeployBuffer.create[Task](chainName, minTtl = Duration.Zero)
      implicit val deploySelection = DeploySelection.create[Task]()

      MessageProducer[Task](
        validatorIdentity = ValidatorIdentity(
          PublicKey(keys.publicKey.toByteArray),
          PrivateKey(keys.privateKey.toByteArray),
          signatureAlgorithm = Ed25519
        ),
        chainName = chainName,
        upgrades = Seq.empty
      )
    }

    // Make blocks in the test validator's name.
    override lazy val messageProducer = makeMessageProducer(thisValidator)

    // Produce the first block for the test validator validator, already inserted, with dependencies.
    def insertFirstBlock(): Task[Block] =
      for {
        _ <- insertGenesis()
        _ <- addGenesisEra()
        message <- messageProducer.block(
                    keyBlockHash = genesis.messageHash,
                    roundId = conf.toTicks(conf.genesisEraStart),
                    mainParent = genesis,
                    justifications = Map.empty,
                    isBookingBlock = false,
                    messageRole = Block.MessageRole.PROPOSAL
                  )
        _     <- forkchoice.set(message)
        block <- BlockStorage[Task].getBlockUnsafe(message.messageHash)
      } yield block

    // Prepare a second block for the test validator, without inserting.
    def prepareSecondBlock() =
      for {
        first <- insertFirstBlock()
        second <- prepareNextBlock(
                   thisValidator,
                   first,
                   first.getHeader.keyBlockHash,
                   first.getHeader.roundId
                 )
      } yield second

    // Make a new block without inserting it.
    def prepareNextBlock(
        keys: AccountKeys,
        parent: Block,
        keyBlockHash: BlockHash,
        roundId: Long
    ): Task[Block] =
      for {
        dag          <- DagStorage[Task].getRepresentation
        parentTips   <- dag.latestInEra(parent.getHeader.keyBlockHash)
        currTips     <- dag.latestInEra(keyBlockHash)
        parentLatest <- parentTips.latestMessages
        currLatest   <- currTips.latestMessages
        allLatest    = parentLatest |+| currLatest
        maybePrev    = currLatest.get(keys.publicKeyHash).map(_.head)
        nextJRank    = ProtoUtil.nextJRank(allLatest.values.flatten.toSeq)
        now          <- Clock[Task].currentTimeMillis
        second = keys.signBlock {
          parent.withHeader(
            parent.getHeader
              .withTimestamp(now)
              .withKeyBlockHash(keyBlockHash)
              .withRoundId(roundId)
              .withJRank(nextJRank)
              .withValidatorPublicKey(keys.publicKey)
              .withValidatorPublicKeyHash(keys.publicKeyHash)
              .withValidatorBlockSeqNum(maybePrev.map(_.validatorMsgSeqNum + 1).getOrElse(1))
              .withValidatorPrevBlockHash(maybePrev.map(_.messageHash).getOrElse(ByteString.EMPTY))
              .withParentHashes(List(parent.blockHash))
              .withJustifications(allLatest.toSeq.map {
                case (v, ms) =>
                  ms.toSeq.map(m => Block.Justification(v, m.messageHash))
              }.flatten)
          )
        }
      } yield second

    override lazy val validation: Validation[Task] =
      if (validate) new HighwayValidationImpl[Task]
      else new NoOpValidation[Task]

    // Collect emitted events.
    override implicit lazy val eventEmitter: MockEventEmitter[Task] =
      MockEventEmitter.unsafe[Task]

    def validateAndAdd(block: Block): Task[Unit] =
      for {
        semaphore <- Semaphore[Task](1)
        _         <- messageExecutor.validateAndAdd(semaphore, block, isBookingBlock = false)
      } yield ()
  }

  behavior of "validateAndAdd"

  it should "validate and save a valid block" in executorFixture { implicit db =>
    new ExecutorFixture(db, validate = true, printLevel = Log.Level.Warn) {
      override def test =
        for {
          block <- prepareSecondBlock()
          _     <- validateAndAdd(block)
          dag   <- DagStorage[Task].getRepresentation
          _     <- dag.contains(block.blockHash) shouldBeF true
          _     <- BlockStorage[Task].contains(block.blockHash) shouldBeF true
        } yield ()
    }
  }

  it should "validate and save a ballot" in executorFixture { implicit db =>
    new ExecutorFixture(db, validate = true, printLevel = Log.Level.Warn) {
      override def test =
        for {
          (ballot: Block) <- prepareSecondBlock().map { block =>
                              thisValidator.signBlock(
                                block.withHeader(
                                  block.getHeader.withMessageType(Block.MessageType.BALLOT)
                                )
                              )
                            }
          _       <- validateAndAdd(ballot)
          dag     <- DagStorage[Task].getRepresentation
          message <- dag.lookupUnsafe(ballot.blockHash)
          _       = message shouldBe a[Message.Ballot]
        } yield ()
    }
  }

  it should "raise an error for unattributable errors" in executorFixture { implicit db =>
    new ExecutorFixture(db, validate = true, printLevel = Log.Level.Crit) {
      override def test =
        for {
          // A block without signature cannot be a slashed
          block  <- prepareSecondBlock().map(_.clearSignature)
          result <- validateAndAdd(block).attempt
          _ = result match {
            case Left(ex)  => ex shouldBe a[ValidateErrorWrapper]
            case Right(()) => fail("Should have raised.")
          }
          _ <- BlockStorage[Task].contains(block.blockHash) shouldBeF false
        } yield ()
    }
  }

  // TODO (CON-623): In the future we want these not to raise, but for that the fork
  // choice would also have to know not to build on them because they are invalid blocks.
  it should "raise an error for attributable errors" in executorFixture { implicit db =>
    new ExecutorFixture(db, validate = true, printLevel = Log.Level.Crit) {
      override def test =
        for {
          // Make a block that signed by the validator that has invalid content.
          block <- prepareSecondBlock() map { block =>
                    thisValidator.signBlock(block.withHeader(block.getHeader.withJRank(100)))
                  }
          result <- validateAndAdd(block).attempt
          _ = result match {
            case Left(ex)  => ex shouldBe a[ValidateErrorWrapper]
            case Right(()) => fail("Should have raised.")
          }
          _ <- BlockStorage[Task].contains(block.blockHash) shouldBeF false
        } yield ()
    }
  }

  it should "save an equivocation" in executorFixture { implicit db =>
    new ExecutorFixture(db, validate = true) {
      override def test =
        for {
          first <- insertFirstBlock()

          // Make two blocks by the other validator that form an equivocation.
          blockA <- prepareNextBlock(
                     otherValidator,
                     first,
                     first.getHeader.keyBlockHash,
                     first.getHeader.roundId + 1
                   )
          blockB <- prepareNextBlock(
                     otherValidator,
                     first,
                     first.getHeader.keyBlockHash,
                     first.getHeader.roundId + 2
                   )
          _   <- validateAndAdd(blockA)
          eff <- messageExecutor.computeEffects(blockB, false)
          _   = eff._1 shouldBe EquivocatedBlock
          _   = eff._2.effects shouldBe empty
          _   <- validateAndAdd(blockB)
          _   <- BlockStorage[Task].contains(blockB.blockHash) shouldBeF true

          dag  <- DagStorage[Task].getRepresentation
          tips <- dag.latestInEra(first.getHeader.keyBlockHash)
          _    <- tips.getEquivocators shouldBeF Set(otherValidator.publicKeyHash)
        } yield ()
    }
  }

  it should "not consider blocks that don't cite each other across eras as equivocations" in executorFixture {
    implicit db =>
      new ExecutorFixture(db, validate = true) {
        override def test =
          for {
            era0  <- addGenesisEra()
            first <- insertFirstBlock()
            era1  <- era0.addChildEra(keyBlockHash = first.blockHash)

            // Make one block in the genesis era and one in the child era;
            // they don't cite each other.
            block0 <- prepareNextBlock(
                       thisValidator,
                       first,
                       era0.keyBlockHash,
                       era0.endTick
                     )

            block1 <- prepareNextBlock(
                       thisValidator,
                       first,
                       era1.keyBlockHash,
                       era1.startTick
                     )

            _      <- validateAndAdd(block0)
            status <- messageExecutor.computeEffects(block1, false).map(_._1)
            _      <- validateAndAdd(block1)
            _      <- BlockStorage[Task].contains(block1.blockHash) shouldBeF true
          } yield {
            // TODO (CON-643): Update the EquivocationDetector to only detect in a given era,
            // or only detect in up to the keyblock, but not globally. For now it will detect
            // them and therefore not save the effects they have.
            if (status != Valid) cancel("CON-643")
            status shouldBe Valid
          }
      }
  }

  it should "not emit events" in executorFixture { implicit db =>
    new ExecutorFixture(db) {
      override def test =
        for {
          block  <- prepareSecondBlock()
          _      <- validateAndAdd(block)
          events <- eventEmitter.events
        } yield {
          events shouldBe empty
        }
    }
  }

  behavior of "effectsAfterAdded"

  it should "emit events" in executorFixture { implicit db =>
    new ExecutorFixture(db) {
      override def test =
        for {
          block   <- insertFirstBlock()
          message = Message.fromBlock(block).get
          wait    <- messageExecutor.effectsAfterAdded(message)
          _       <- wait
          events  <- eventEmitter.events
        } yield {
          forExactly(1, events) { event =>
            event.value.isBlockAdded shouldBe true
          }
        }
    }
  }

  it should "update the finalizer state" in executorFixture { implicit db =>
    new ExecutorFixture(db) {

      val messageAddedRef = Ref.unsafe[Task, Option[Message]](none)

      override lazy val finalizer = new MultiParentFinalizer[Task] {

        override def addMessage(message: Message): Task[Unit] =
          messageAddedRef.set(Some(message))

        override def checkFinality(): Task[Seq[MultiParentFinalizer.FinalizedBlocks]] =
          Task(Seq.empty)
      }

      override def test =
        for {
          block   <- insertFirstBlock()
          message = Message.fromBlock(block).get
          wait    <- messageExecutor.effectsAfterAdded(message)
          _       <- wait
          _       <- messageAddedRef.get shouldBeF Some(message)
        } yield ()
    }
  }

  it should "mark deploys as processed" in executorFixture { implicit db =>
    new ExecutorFixture(db) {
      override def test =
        for {
          block   <- sample(arbBlock.arbitrary.filter(_.getBody.deploys.size > 0)).pure[Task]
          deploys = block.getBody.deploys.map(_.getDeploy).toList
          _       <- DeployStorage[Task].writer.addAsPending(deploys)
          _       <- BlockStorage[Task].put(block, BlockEffects.empty.effects)
          message = Message.fromBlock(block).get
          wait    <- messageExecutor.effectsAfterAdded(message)
          _       <- wait
          statuses <- deploys.traverse { d =>
                       DeployStorage[Task].reader.getBufferedStatus(d.deployHash)
                     }
        } yield {
          forAll(statuses) { maybeStatus =>
            maybeStatus should not be empty
            maybeStatus.get.state shouldBe DeployInfo.State.PROCESSED
          }
        }
    }
  }

  it should "requeue orphaned deploys in blocks" in executorFixture { implicit db =>
    new ExecutorFixture(db) {
      // Mock finalizer that marks anything as orphaned.
      override lazy val finalizer = new MultiParentFinalizer[Task] {
        private val messageRef = new AtomicReference[Message]()

        override def addMessage(message: Message): Task[Unit] = Task(messageRef.set(message))

        override def checkFinality(): Task[Seq[MultiParentFinalizer.FinalizedBlocks]] =
          for {
            _ <- FinalityStorage[Task].markAsFinalized(
                  ByteString.EMPTY,
                  Set.empty,
                  Set(messageRef.get.messageHash)
                )
          } yield List(
            MultiParentFinalizer
              .FinalizedBlocks(
                ByteString.EMPTY,
                BigInt(0),
                Set.empty,
                indirectlyOrphaned = Set(messageRef.get)
              )
          )
      }

      def makeOrphan(own: Boolean) = {
        val block = {
          val randomBlock = sample(arbBlock.arbitrary.filter(_.getBody.deploys.size > 0))
          if (!own)
            randomBlock
          else
            randomBlock.withHeader(
              randomBlock.getHeader.withValidatorPublicKeyHash(validator)
            )
        }
        val deploys = block.getBody.deploys.map(_.getDeploy).toList
        val message = Message.fromBlock(block).get
        for {
          _    <- DeployStorage[Task].writer.addAsProcessed(deploys)
          _    <- BlockStorage[Task].put(block, BlockEffects.empty.effects)
          wait <- messageExecutor.effectsAfterAdded(message)
          _    <- wait
          _    <- messageExecutor.checkFinality().flatMap(identity)
          statuses <- deploys.traverse { d =>
                       DeployStorage[Task].reader.getBufferedStatus(d.deployHash)
                     }
        } yield statuses
      }

      override def test =
        for {
          ownStatuses   <- makeOrphan(own = true)
          otherStatuses <- makeOrphan(own = false)
        } yield {
          forAll(ownStatuses) { maybeStatus =>
            maybeStatus should not be empty
            maybeStatus.get.state shouldBe DeployInfo.State.PENDING
          }
          // Requeue deploys processed by others if we have them in the buffer too,
          // so that it's consistent across all nodes and all of them can re-propose.
          forAll(otherStatuses) { maybeStatus =>
            maybeStatus should not be empty
            maybeStatus.get.state shouldBe DeployInfo.State.PENDING
          }
        }
    }
  }

  it should "remove finalized deploys" in executorFixture { implicit db =>
    new ExecutorFixture(db) {
      @volatile var messagesMade = Set.empty[Message]

      // Mock finalizer that marks anything as final.
      override lazy val finalizer = new MultiParentFinalizer[Task] {
        private val messageRef                                = new AtomicReference[Message]()
        override def addMessage(message: Message): Task[Unit] = Task(messageRef.set(message))

        override def checkFinality(): Task[Seq[MultiParentFinalizer.FinalizedBlocks]] =
          for {
            _ <- FinalityStorage[Task].markAsFinalized(
                  messageRef.get.messageHash,
                  (messagesMade - messageRef.get).map(_.messageHash),
                  Set.empty
                )
          } yield List(
            MultiParentFinalizer
              .FinalizedBlocks(
                messageRef.get.messageHash,
                BigInt(0),
                indirectlyFinalized = messagesMade - messageRef.get,
                indirectlyOrphaned = Set.empty
              )
          )
      }

      def makeBlock(): Task[Message] = {
        val block   = sample(arbBlock.arbitrary.filter(_.getBody.deploys.size > 0))
        val deploys = block.getBody.deploys.map(_.getDeploy).toList
        val message = Message.fromBlock(block).get
        messagesMade = messagesMade + message
        for {
          _ <- DeployStorage[Task].writer.addAsProcessed(deploys)
          _ <- BlockStorage[Task].put(block, BlockEffects.empty.effects)
        } yield message
      }

      def getStatuses(message: Message) =
        for {
          block   <- BlockStorage[Task].getBlockUnsafe(message.messageHash)
          deploys = block.getBody.deploys.map(_.getDeploy).toList
          statuses <- deploys.traverse { d =>
                       DeployStorage[Task].reader.getBufferedStatus(d.deployHash)
                     }
        } yield statuses

      override def test =
        for {
          indirectMsg       <- makeBlock()
          finalizedMsg      <- makeBlock()
          wait              <- messageExecutor.effectsAfterAdded(finalizedMsg)
          _                 <- wait
          _                 <- messageExecutor.checkFinality().flatMap(identity)
          indirectStatuses  <- getStatuses(indirectMsg)
          finalizedStatuses <- getStatuses(finalizedMsg)
        } yield {
          forAll(indirectStatuses ++ finalizedStatuses) { maybeStatus =>
            maybeStatus shouldBe empty
          }
        }
    }
  }

  behavior of "computeEffects"

  trait ExecEngineSerivceWithFakeEffects { self: ExecutorFixture =>
    import io.casperlabs.ipc._
    import io.casperlabs.smartcontracts.ExecutionEngineService
    import io.casperlabs.casper.helper.HashSetCasperTestNode.simpleEEApi
    import state.Key

    def sampleBlockWithDeploys =
      sample(arbBlock.arbitrary.filter(_.getBody.deploys.nonEmpty))

    override lazy val execEngineService =
      simpleEEApi[Task](initialBonds = Map.empty, generateConflict = false)
  }

  it should "return the effects of a valid block" in executorFixture { implicit db =>
    new ExecutorFixture(db) with ExecEngineSerivceWithFakeEffects {
      override def test =
        messageExecutor.computeEffects(sampleBlockWithDeploys, false) map {
          case (status, effects) =>
            status shouldBe Valid
            effects.effects should not be (empty)
        }
    }
  }

  it should "not return the effects of an invalid block" in executorFixture { implicit db =>
    val functorRaiseInvalidBlock =
      validation.raiseValidateErrorThroughApplicativeError[Task]

    new ExecutorFixture(db) with ExecEngineSerivceWithFakeEffects {
      // Fake validation which lets everything through but it raises the one
      // invalid status which should result in a block being saved.
      override lazy val validation = new NoOpValidation[Task] {
        override def checkEquivocation(dag: DagRepresentation[Task], block: Block): Task[Unit] =
          functorRaiseInvalidBlock.raise(EquivocatedBlock)
      }

      override def test =
        messageExecutor.computeEffects(sample[Block], false) map {
          case (status, effects) =>
            status shouldBe EquivocatedBlock
            effects.effects shouldBe (empty)
        }
    }
  }

}
