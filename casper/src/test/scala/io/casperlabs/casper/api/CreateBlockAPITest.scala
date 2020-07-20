package io.casperlabs.casper.api

import cats.Monad
import cats.data.{EitherT, NonEmptyList}
import cats.effect.concurrent.Semaphore
import cats.implicits._
import com.github.ghik.silencer.silent
import com.google.protobuf.ByteString
import io.casperlabs.casper
import io.casperlabs.casper._
import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.casper.MultiParentCasperImpl.Broadcaster
import io.casperlabs.casper.MultiParentCasperRef.MultiParentCasperRef
import io.casperlabs.casper.consensus._
import io.casperlabs.casper.consensus.info.{BlockInfo, DeployInfo}
import io.casperlabs.casper.consensus.info.BlockInfo.Status.Stats
import io.casperlabs.casper.consensus.info.DeployInfo.ProcessingResult
import io.casperlabs.casper.helper.{
  GossipServiceCasperTestNodeFactory,
  HashSetCasperTestNode,
  NoOpsEventEmitter
}
import io.casperlabs.casper.helper.BlockUtil.generateValidator
import io.casperlabs.casper.util._
import io.casperlabs.casper.validation.{NCBValidationImpl, Validation}
import io.casperlabs.catscontrib.TaskContrib._
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.crypto.signatures.SignatureAlgorithm.{Ed25519, Secp256k1}
import io.casperlabs.crypto.Keys
import io.casperlabs.metrics.Metrics
import io.casperlabs.models.Weight
import io.casperlabs.p2p.EffectsTestInstances._
import io.casperlabs.shared.Time
import io.casperlabs.storage.BlockMsgWithTransform
import io.casperlabs.storage.dag.DagRepresentation
import io.casperlabs.storage.deploy.DeployStorageReader
import io.casperlabs.casper.scalatestcontrib._
import monix.eval.Task
import monix.execution.Scheduler
import org.scalatest.{FlatSpec, Inspectors, Matchers}
import io.casperlabs.casper.helper.DeployOps._
import io.casperlabs.comm.ServiceError.OutOfRange
import io.casperlabs.comm.gossiping.relaying.NoOpsDeployRelaying
import io.casperlabs.shared.LogStub
import io.casperlabs.models.DeployImplicits._

import scala.concurrent.duration._

@silent("deprecated")
class CreateBlockAPITest
    extends FlatSpec
    with Matchers
    with Inspectors
    with GossipServiceCasperTestNodeFactory {
  import HashSetCasperTest._

  implicit val scheduler: Scheduler = Scheduler.fixedPool("create-block-api-test", 4)
  implicit val metrics              = new Metrics.MetricsNOP[Task]
  implicit val raiseValidateErr =
    casper.validation.raiseValidateErrorThroughApplicativeError[Task]
  implicit val logEff         = LogStub[Task]()
  implicit val broadcaster    = Broadcaster.noop[Task]
  implicit val eventEmitter   = NoOpsEventEmitter.create[Task]
  implicit val validationEff  = new NCBValidationImpl[Task]
  implicit val deployRelaying = new NoOpsDeployRelaying[Task]

  private val (validatorKeys, validators) = {
    val validatorIds = (1 to 4) map { _ =>
      val (sk, pk) = Ed25519.newKeyPair
      ValidatorIdentity(pk, sk, Ed25519)
    }
    validatorIds -> validatorIds.map(_.publicKeyHashBS)
  }
  private val bonds                                   = createBonds(validators)
  private val BlockMsgWithTransform(Some(genesis), _) = createGenesis(bonds)

  "createBlock" should "not allow simultaneous calls" in {
    implicit val scheduler = Scheduler.fixedPool("three-threads", 3)
    implicit val time = new Time[Task] {
      private val timer                               = Task.timer
      def currentMillis: Task[Long]                   = timer.clock.realTime(MILLISECONDS)
      def nanoTime: Task[Long]                        = timer.clock.monotonic(NANOSECONDS)
      def sleep(duration: FiniteDuration): Task[Unit] = timer.sleep(duration)
    }
    val node   = standaloneEff(genesis, validatorKeys.head)
    val casper = new SleepingMultiParentCasperImpl[Task](node.casperEff)
    val deploys = List.fill(2)(
      ProtoUtil.deploy(
        0,
        ByteString.copyFromUtf8(System.currentTimeMillis().toString)
      )
    )

    implicit val logEff = LogStub[Task]()
    implicit val db     = node.deployBuffer

    def testProgram(blockApiLock: Semaphore[Task])(
        implicit casperRef: MultiParentCasperRef[Task]
    ): Task[(Either[Throwable, ByteString], Either[Throwable, ByteString])] =
      for {
        t1 <- (BlockAPI.deploy[Task](deploys.head) *> BlockAPI
               .propose[Task](blockApiLock, canCreateBallot = false)).start
        _ <- Time[Task].sleep(2.second)
        t2 <- (BlockAPI.deploy[Task](deploys.last) *> BlockAPI
               .propose[Task](blockApiLock, canCreateBallot = false)).start //should fail because other not done
        r1 <- t1.join.attempt
        r2 <- t2.join.attempt
      } yield (r1, r2)

    try {
      val (response1, response2) = (for {
        casperRef    <- MultiParentCasperRef.of[Task]
        _            <- casperRef.set(casper)
        blockApiLock <- Semaphore[Task](1)
        result       <- testProgram(blockApiLock)(casperRef)
      } yield result).unsafeRunSync

      response1.isRight shouldBe true
      response2.isRight shouldBe false
      response2.left.get.getMessage should include("ABORTED")
    } finally {
      node.tearDown().unsafeRunSync
    }
  }

  "propose" should "handle concurrent calls and not create equivocations" in {
    val threadCount        = 3
    implicit val scheduler = Scheduler.fixedPool("propose-threads", threadCount)
    // Similarly to integration tests, start multiple concurrent processes that
    // try to deploy and immediately propose. The proposals should be rejected
    // when another one is already running, but eventually all deploys should be
    // taken and put into blocks. The blocks we created should form a simple
    // chain, there shouldn't be any forks in it, which we should see from the
    // fact that each rank is occupied by a single block.
    val node = standaloneEff(genesis, validatorKeys.head)

    implicit val db = node.deployBuffer

    def deployAndPropose(
        blockApiLock: Semaphore[Task]
    )(implicit casperRef: MultiParentCasperRef[Task]) =
      for {
        d <- ProtoUtil.basicDeploy[Task]()
        _ <- BlockAPI.deploy[Task](d)
        _ <- BlockAPI.propose[Task](blockApiLock, canCreateBallot = false)
      } yield ()

    val test: Task[Unit] = for {
      casperRef    <- MultiParentCasperRef.of[Task]
      _            <- casperRef.set(node.casperEff)
      blockApiLock <- Semaphore[Task](1)
      // Create blocks in waves.
      results <- Task.sequence {
                  List.fill(5) {
                    Task.gatherUnordered {
                      List.fill(threadCount) {
                        deployAndPropose(blockApiLock)(casperRef).attempt
                      }
                    }
                  }
                }
      // See how many blocks were created.
      dag    <- node.dagStorage.getRepresentation
      blocks <- dag.topoSortTail(Int.MaxValue).compile.toList.map(_.flatten)
    } yield {
      // Genesis plus as many as we created (most likely the number of waves we have)
      val successCount = results.flatten.count(_.isRight)
      blocks should have size (1L + successCount.toLong)

      // Check that we have no forks.
      val blocksPerRank = blocks.groupBy(_.getSummary.getHeader.jRank)
      blocksPerRank should have size (blocks.size.toLong)
      forAll(blocksPerRank.values)(_ should have size 1)

      // Check that adding the block hasn't failed for an unexpected reason.
      results.flatten.foreach {
        case Right(_) =>
        case Left(ex) =>
          // Either the lock couldn't be acquired or all deploys were stolen.
          ex.getMessage should (include("ABORTED") or include("OUT_OF_RANGE"))
      }
    }

    try {
      test.unsafeRunSync
    } finally {
      node.tearDown().unsafeRunSync
    }
  }

  "deploy" should "reject replayed deploys" in {
    // Create the node with low fault tolerance threshold so it finalizes the blocks as soon as they are made.
    val node =
      standaloneEff(genesis, validatorKeys.head)

    implicit val logEff = LogStub[Task]()
    implicit val db     = node.deployBuffer

    def testProgram(blockApiLock: Semaphore[Task])(
        implicit casperRef: MultiParentCasperRef[Task]
    ): Task[Unit] =
      for {
        d <- ProtoUtil.basicDeploy[Task]()
        _ <- BlockAPI.deploy[Task](d)
        _ <- BlockAPI.propose[Task](blockApiLock, canCreateBallot = false)
        _ <- BlockAPI.deploy[Task](d)
        _ <- BlockAPI.propose[Task](blockApiLock, canCreateBallot = false)
      } yield ()

    try {
      (for {
        casperRef    <- MultiParentCasperRef.of[Task]
        _            <- casperRef.set(node.casperEff)
        blockApiLock <- Semaphore[Task](1)
        result       <- testProgram(blockApiLock)(casperRef).attempt
        Left(ex)     = result
        _            = ex.getMessage should include("No new deploys")
      } yield result).unsafeRunSync
    } finally {
      node.tearDown().unsafeRunSync
    }
  }

  it should "accept deploys signed by Secp256k1" in {
    val node = standaloneEff(genesis, validatorKeys.head)

    implicit val db = node.deployBuffer

    try {
      val test = for {
        deploy0  <- ProtoUtil.basicDeploy[Task]()
        (sk, pk) = Secp256k1.newKeyPair
        deploy1  = deploy0.approve(Secp256k1, sk, pk)
        result   <- BlockAPI.deploy[Task](deploy1).attempt
      } yield {
        result shouldBe Right(())
      }

      test.unsafeRunSync
    } finally {
      node.tearDown().unsafeRunSync
    }
  }

  it should "reject incorrect signature" in {
    val node = standaloneEff(genesis, validatorKeys.head)

    implicit val db = node.deployBuffer

    try {
      val test = for {
        deploy0  <- ProtoUtil.basicDeploy[Task]()
        (sk, pk) = Secp256k1.newKeyPair
        deploy1  = deploy0.approve(Secp256k1, sk, Keys.PublicKey(Array[Byte](1, 2, 3)))
        result   <- BlockAPI.deploy[Task](deploy1).attempt
      } yield {
        result match {
          case Left(ex) => ex.getMessage should include("Invalid deploy signature.")
          case other    => fail(s"Expected failed signature: $other")
        }
      }

      test.unsafeRunSync
    } finally {
      node.tearDown().unsafeRunSync
    }
  }

  "getDeployInfo" should "return DeployInfo for specified deployHash" in {
    // Create the node with low fault tolerance threshold so it finalizes the blocks as soon as they are made.
    val node =
      standaloneEff(genesis, validatorKeys.head)

    implicit val logEff        = LogStub[Task]()
    implicit val blockStorage  = node.blockStorage
    implicit val deployStorage = node.deployStorage
    implicit val db            = node.deployBuffer

    val deploy = ProtoUtil.deploy(0)

    def testProgram(blockApiLock: Semaphore[Task])(
        implicit casperRef: MultiParentCasperRef[Task]
    ): Task[Unit] =
      for {
        _         <- BlockAPI.deploy[Task](deploy)
        blockHash <- BlockAPI.propose[Task](blockApiLock, canCreateBallot = false)
        deployInfo <- BlockAPI.getDeployInfo[Task](
                       Base16.encode(deploy.deployHash.toByteArray),
                       DeployInfo.View.FULL
                     )
        block <- blockStorage
                  .get(blockHash)
                  .map(_.get.blockMessage.get)
        summary         = BlockSummary(block.blockHash, block.header, block.signature)
        processedDeploy = block.getBody.deploys.head
        expectBlockInfo = BlockInfo()
          .withSummary(summary)
          .withStatus(
            BlockInfo
              .Status()
              .withStats(
                Stats(
                  block.serializedSize,
                  block.getBody.deploys.count(_.isError),
                  block.getBody.deploys.map(_.cost).sum
                )
              )
          )
        expectProcessingResult = ProcessingResult(
          blockInfo = expectBlockInfo.some,
          cost = processedDeploy.cost,
          isError = processedDeploy.isError,
          errorMessage = processedDeploy.errorMessage
        )
        _ = deployInfo.processingResults.head shouldBe expectProcessingResult
        _ = deployInfo.deploy shouldBe deploy.some
        result <- BlockAPI
                   .getDeployInfo[Task](
                     Base16.encode(ByteString.copyFromUtf8("NOT_EXIST").toByteArray),
                     DeployInfo.View.BASIC
                   )
                   .attempt
        _ = result.left.get.getMessage should include("NOT_FOUND: Deploy")
      } yield ()

    try {
      (for {
        casperRef    <- MultiParentCasperRef.of[Task]
        _            <- casperRef.set(node.casperEff)
        blockApiLock <- Semaphore[Task](1)
        result       <- testProgram(blockApiLock)(casperRef)
      } yield result).unsafeRunSync
    } finally {
      node.tearDown().unsafeRunSync
    }
  }

  "getBlockDeploys" should "return return all ProcessedDeploys in a block" in {
    val node =
      standaloneEff(genesis, validatorKeys.head)

    implicit val logEff        = LogStub[Task]()
    implicit val blockStorage  = node.blockStorage
    implicit val deployStorage = node.deployStorage
    implicit val db            = node.deployBuffer

    def mkDeploy(code: String) =
      ProtoUtil.deploy(0, ByteString.copyFromUtf8(code))

    def testProgram(blockApiLock: Semaphore[Task])(
        implicit casperRef: MultiParentCasperRef[Task]
    ): Task[Unit] =
      for {
        _         <- BlockAPI.deploy[Task](mkDeploy("a"))
        _         <- BlockAPI.deploy[Task](mkDeploy("b"))
        blockHash <- BlockAPI.propose[Task](blockApiLock, canCreateBallot = false)
        deploys <- BlockAPI.getBlockDeploys[Task](
                    Base16.encode(blockHash.toByteArray),
                    DeployInfo.View.FULL
                  )
        block <- blockStorage
                  .get(blockHash)
                  .map(_.get.blockMessage.get)
        _ = deploys shouldBe block.getBody.deploys
      } yield ()

    try {
      (for {
        casperRef    <- MultiParentCasperRef.of[Task]
        _            <- casperRef.set(node.casperEff)
        blockApiLock <- Semaphore[Task](1)
        result       <- testProgram(blockApiLock)(casperRef)
      } yield result).unsafeRunSync
    } finally {
      node.tearDown().unsafeRunSync
    }
  }

  "getDeployInfos" should "return a list of DeployInfo for the list of deploys" in {
    // Create the node with low fault tolerance threshold so it finalizes the blocks as soon as they are made.
    val node =
      standaloneEff(genesis, validatorKeys.head)

    implicit val logEff        = LogStub[Task]()
    implicit val deployStorage = node.deployStorage
    implicit val db            = node.deployBuffer

    val deploys = (1L to 10L).map(ProtoUtil.deploy(_)).toList

    def testProgram(blockApiLock: Semaphore[Task])(
        implicit casperRef: MultiParentCasperRef[Task]
    ): Task[Unit] =
      for {
        _ <- deploys.traverse(d => {
              BlockAPI.deploy[Task](d) *> BlockAPI
                .propose[Task](blockApiLock, canCreateBallot = false)
            })
        deployInfos <- DeployStorageReader[Task].getDeployInfos(deploys)
        _ <- deployInfos.traverse(
              deployInfo =>
                DeployStorageReader[Task]
                  .getDeployInfo(deployInfo.getDeploy.deployHash) shouldBeF deployInfo.some
            )
        result <- DeployStorageReader[Task]
                   .getDeployInfos(
                     List.empty[Deploy]
                   )
        _ = result shouldBe List.empty[DeployInfo]
      } yield ()

    try {
      (for {
        casperRef    <- MultiParentCasperRef.of[Task]
        _            <- casperRef.set(node.casperEff)
        blockApiLock <- Semaphore[Task](1)
        result       <- testProgram(blockApiLock)(casperRef)
      } yield result).unsafeRunSync
    } finally {
      node.tearDown().unsafeRunSync
    }
  }
}

private class SleepingMultiParentCasperImpl[F[_]: Monad: Time](underlying: MultiParentCasper[F])
    extends MultiParentCasper[F] {
  def addBlock(b: Block): F[BlockStatus] = underlying.addBlock(b)
  def contains(b: Block): F[Boolean]     = underlying.contains(b)
  def estimator(
      dag: DagRepresentation[F],
      lfbHash: ByteString,
      latestMessagesHashes: Map[Validator, Set[ByteString]],
      equivocators: Set[Validator]
  ): F[NonEmptyList[BlockHash]] =
    underlying.estimator(dag, lfbHash, latestMessagesHashes, equivocators)
  def dag: F[DagRepresentation[F]] = underlying.dag
  def lastFinalizedBlock: F[Block] = underlying.lastFinalizedBlock

  override def createMessage(canCreateBallot: Boolean): F[CreateBlockStatus] =
    for {
      result <- underlying.createMessage(canCreateBallot)
      _      <- implicitly[Time[F]].sleep(5.seconds)
    } yield result

}
