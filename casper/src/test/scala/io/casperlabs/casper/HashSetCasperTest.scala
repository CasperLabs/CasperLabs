package io.casperlabs.casper

import cats.effect.Sync
import cats.implicits._
import com.github.ghik.silencer.silent
import com.google.protobuf.ByteString
import cats.Show
import cats.syntax.show
import io.casperlabs.shared.ByteStringPrettyPrinter._
import io.casperlabs.casper.MultiParentCasperImpl.Broadcaster
import io.casperlabs.casper.consensus.Block.{Justification, ProcessedDeploy}
import io.casperlabs.casper.consensus._
import io.casperlabs.casper.genesis.Genesis
import io.casperlabs.casper.helper.DeployOps.ChangeDeployOps
import io.casperlabs.casper.helper._
import io.casperlabs.casper.scalatestcontrib._
import io.casperlabs.casper.util.{BondingUtil, ProtoUtil}
import io.casperlabs.catscontrib.TaskContrib.TaskOps
import io.casperlabs.crypto.Keys
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.crypto.signatures.SignatureAlgorithm
import io.casperlabs.crypto.signatures.SignatureAlgorithm.{Ed25519, Secp256k1, Secp256r1}
import io.casperlabs.ipc
import io.casperlabs.mempool.DeployBuffer
import io.casperlabs.metrics.Metrics
import io.casperlabs.models.Message
import io.casperlabs.p2p.EffectsTestInstances.LogicalTime
import io.casperlabs.shared.LogStub
import io.casperlabs.storage.BlockMsgWithTransform
import io.casperlabs.storage.block.BlockStorage
import io.casperlabs.storage.dag.FinalityStorage
import monix.eval.Task
import monix.execution.Scheduler
import monix.execution.Scheduler.Implicits.global
import org.scalatest.{Assertion, FlatSpec, Inspectors, Matchers}

import scala.concurrent.duration._
import scala.collection.immutable

/** Run tests using the GossipService and co. */
class GossipServiceCasperTest extends HashSetCasperTest with GossipServiceCasperTestNodeFactory

/** Run tests against whatever kind of test node the inheriting sets up. */
@silent("match may not be exhaustive")
abstract class HashSetCasperTest
    extends FlatSpec
    with Matchers
    with Inspectors
    with HashSetCasperTestNodeFactory {

  import HashSetCasperTest._

  implicit val emitter = NoOpsEventEmitter.create[Task]

  implicit class TestNodeOps(node: TestNode[Task]) {
    def addAndBroadcast(b: Block): Task[BlockStatus] =
      node.casperEff.addBlock(b).flatTap(node.broadcaster.networkEffects(b, _))

    def propose(): Task[Block] =
      node.casperEff.createBlock.flatMap {
        case Created(block) => {
          addAndBroadcast(block)
            .map { addBlockStatus =>
              assert(addBlockStatus == Valid)
              block
            }
        }
        case noBlock: NoBlock =>
          Task.raiseError(new IllegalStateException(s"No block created ${noBlock.toString}"))
      }

    def deployAndPropose(d: Deploy): Task[Block] =
      node.deployBuffer.addDeploy(d) >>= {
        case Left(ex) => Task.raiseError[Block](ex)
        case Right(_) => propose()
      }

    def randomDeployAndPropose(): Task[Block] =
      for {
        deploy <- ProtoUtil.basicDeploy[Task]()
        block  <- node.deployAndPropose(deploy)
      } yield block
  }

  implicit val timeEff = new LogicalTime[Task]

  private def makeValidatorIdentity(alg: SignatureAlgorithm) = {
    val (sk, pk) = alg.newKeyPair
    ValidatorIdentity(pk, sk, alg)
  }
  private val otherValidatorKey =
    makeValidatorIdentity(Ed25519)

  private val (validatorKeys, validators) = {
    val validatorIds = (1 to 4) map (_ % 3) map {
      case 0 => Ed25519
      case 1 => Secp256k1
      case 2 => Secp256r1
    } map (makeValidatorIdentity)

    validatorIds -> validatorIds.map(_.publicKeyHashBS)
  }
  private val wallets = validators.map(key => (key, 10001L)).toMap
  private val bonds   = createBonds(validators)
  private val BlockMsgWithTransform(Some(genesis), _) =
    buildGenesis(wallets, bonds, 0L)

  //put a new casper instance at the start of each
  //test since we cannot reset it
  behavior of "HashSetCasper"

  it should "accept deploys" in effectTest {
    val node = standaloneEff(genesis, validatorKeys.head)

    for {
      deploy <- ProtoUtil.basicDeploy[Task]()
      _      <- node.deployBuffer.addDeploy(deploy)
      result = node.logEff.infos(0).contains("Received Deploy") should be(true)
      _      <- node.deployStorage.reader.getByHash(deploy.deployHash).map(_.get) shouldBeF (deploy)
      _      <- node.tearDown()
    } yield result
  }

  // Leaving this test around. It won't happen in real life because the download manager
  // won't attempt the same block on two different fibers, however it's protected by the
  // mechanism we put in place against equivocating blocks going in at the same time.
  it should "not allow multiple threads to process the same block at the same time" in {
    val scheduler = Scheduler.fixedPool("three-threads", 3)
    val node =
      standaloneEff(genesis, validatorKeys.head)(scheduler)
    val casper = node.casperEff

    val testProgram = for {
      deploy <- ProtoUtil.basicDeploy[Task]()
      _      <- node.deployBuffer.addDeploy(deploy)
      block  <- casper.createBlock.map { case Created(block) => block }
      result <- Task
                 .racePair(
                   casper.addBlock(block),
                   casper.addBlock(block)
                 )
                 .flatMap {
                   case Left((statusA, running)) =>
                     running.join.map((statusA, _))

                   case Right((running, statusB)) =>
                     running.join.map((_, statusB))
                 }
    } yield result

    val threadStatuses: (BlockStatus, BlockStatus) =
      testProgram.unsafeRunSync(scheduler)

    threadStatuses should matchPattern {
      case (Processed, Valid) | (Valid, Processed) =>
    }
    node.tearDown().unsafeRunSync
  }

  it should "create blocks based on deploys" in effectTest {
    val node = standaloneEff(genesis, validatorKeys.head)

    for {
      deploy  <- ProtoUtil.basicDeploy[Task]()
      block   <- node.deployAndPropose(deploy)
      deploys = block.body.get.deploys.flatMap(_.deploy)
      parents = ProtoUtil.parentHashes(block)

      _ = parents should have size 1
      _ = parents.head shouldBe genesis.blockHash
      _ = deploys should have size 1
      _ = deploys.head shouldEqual deploy
      _ <- node.tearDown()
    } yield ()
  }

  it should "create a ballot when asked to create a message and there are no deploys" in effectTest {
    val node            = standaloneEff(genesis, validatorKeys.head)
    implicit val casper = node.casperEff

    for {
      _                 <- MultiParentCasper[Task].createMessage(canCreateBallot = false) shouldBeF NoNewDeploys
      createBlockResult <- MultiParentCasper[Task].createMessage(canCreateBallot = true)
      Created(block)    = createBlockResult
      parents           = ProtoUtil.parentHashes(block)

      _ = parents should have size 1
      _ = parents.head shouldBe genesis.blockHash
      _ = Message.fromBlock(block).get shouldBe a[Message.Ballot]
      _ <- node.tearDown()
    } yield ()
  }

  it should "create a block if a ballot is allowed but it has deploys in the buffer" in effectTest {
    val node = standaloneEff(genesis, validatorKeys.head)
    for {
      block <- node.randomDeployAndPropose()
      _     = Message.fromBlock(block).get shouldBe a[Message.Block]
      _     <- node.tearDown()
    } yield ()
  }

  it should "always choose the last block as the parent, never a ballot" in effectTest {
    val node            = standaloneEff(genesis, validatorKeys.head)
    implicit val casper = node.casperEff

    def createMessage(expectBallot: Boolean, expectedParent: ByteString) =
      for {
        createBlockResult <- MultiParentCasper[Task].createMessage(canCreateBallot = true)
        Created(block)    = createBlockResult
        _                 <- MultiParentCasper[Task].addBlock(block)
        msg               = Message.fromBlock(block).get
        _                 = if (expectBallot) msg shouldBe a[Message.Ballot] else msg shouldBe a[Message.Block]
        _                 = ProtoUtil.parentHashes(block).head shouldBe expectedParent
      } yield block

    for {
      deploy <- ProtoUtil.basicDeploy[Task]()
      // A ballot on top of genesis should not be built upon by the upcoming block.
      _      <- createMessage(expectBallot = true, expectedParent = genesis.blockHash)
      _      <- node.deployBuffer.addDeploy(deploy)
      block1 <- createMessage(expectBallot = false, expectedParent = genesis.blockHash)
      _      <- createMessage(expectBallot = true, expectedParent = block1.blockHash)
      _      <- createMessage(expectBallot = true, expectedParent = block1.blockHash)

      _ <- node.tearDown()
    } yield ()
  }

  it should "accept signed blocks" in effectTest {
    val node = standaloneEff(genesis, validatorKeys.head)
    import node._

    for {
      signedBlock         <- node.randomDeployAndPropose()
      _                   <- MultiParentCasper[Task].addBlock(signedBlock)
      _                   = logEff.warns.isEmpty should be(true)
      dag                 <- MultiParentCasper[Task].dag
      latestMessageHashes <- dag.latestMessageHashes
      equivocators        <- dag.getEquivocators
      lfbHash             <- node.lastFinalizedBlockHashContainer.get
      estimate            <- MultiParentCasper[Task].estimator(dag, lfbHash, latestMessageHashes, equivocators)
      _                   = estimate.toList shouldBe List(signedBlock.blockHash)
      _                   = node.tearDown()
    } yield ()
  }

  def deploysFromString(start: Long, strs: List[String]): List[Deploy] =
    strs.zipWithIndex.map(
      s =>
        ProtoUtil.deploy(
          0,
          ByteString.copyFromUtf8((start + s._2).toString)
        )
    )

  it should "be able to create a chain of blocks from different deploys" in effectTest {
    val node = standaloneEff(genesis, validatorKeys.head)
    import node._

    for {
      signedBlock1        <- node.randomDeployAndPropose()
      signedBlock2        <- node.randomDeployAndPropose()
      _                   = logEff.warns shouldBe empty
      _                   = ProtoUtil.parentHashes(signedBlock2) should be(Seq(signedBlock1.blockHash))
      dag                 <- MultiParentCasper[Task].dag
      latestMessageHashes <- dag.latestMessageHashes
      equivocators        <- dag.getEquivocators
      lfbHash             <- node.lastFinalizedBlockHashContainer.get
      estimate            <- MultiParentCasper[Task].estimator(dag, lfbHash, latestMessageHashes, equivocators)

      _ = estimate.toList shouldBe List(signedBlock2.blockHash)
      _ <- node.tearDown()
    } yield ()
  }

  it should "allow multiple deploys in a single block" in effectTest {
    val node = standaloneEff(genesis, validatorKeys.head)
    import node._

    val startTime = System.currentTimeMillis()
    val source    = " for(@x <- @0){ @0!(x) } | @0!(0) "
    val deploys   = deploysFromString(startTime, List(source, source))

    for {
      _                 <- deploys.traverse_(DeployBuffer[Task].addDeploy(_))
      createBlockResult <- MultiParentCasper[Task].createBlock
      Created(block)    = createBlockResult
      _                 <- MultiParentCasper[Task].addBlock(block)
      result            <- MultiParentCasper[Task].contains(block) shouldBeF true
      _                 <- node.tearDown()
    } yield result
  }

  it should "reject unsigned blocks" in effectTest {
    val node = standaloneEff(genesis, validatorKeys.head)

    for {
      basicDeployData   <- ProtoUtil.basicDeploy[Task]()
      createBlockResult <- node.deployBuffer.addDeploy(basicDeployData) *> node.casperEff.createBlock
      Created(block)    = createBlockResult
      invalidBlock      = block.withSignature(block.getSignature.withSig((ByteString.EMPTY)))
      _                 <- node.casperEff.addBlock(invalidBlock) shouldBeF InvalidUnslashableBlock
      _                 = node.logEff.warns.count(_.contains("because block signature")) should be(1)
      _                 <- node.tearDownNode()
      result <- node.validateBlockStorage { blockStorage =>
                 blockStorage.getBlockMessage(block.blockHash) shouldBeF None
               }
    } yield result
  }

  it should "not request invalid blocks from peers" in effectTest {
    val data0 = ProtoUtil.deploy(1, ByteString.EMPTY)
    val data1 = ProtoUtil.deploy(2, ByteString.EMPTY)

    for {
      nodes              <- networkEff(validatorKeys.take(2), genesis)
      List(node0, node1) = nodes.toList
      unsignedBlock <- (node0.deployBuffer.addDeploy(data0) *> node0.casperEff.createBlock)
                        .map {
                          case Created(block) =>
                            block.withSignature(
                              block.getSignature
                                .withSigAlgorithm("invalid")
                                .withSig(ByteString.EMPTY)
                            )
                        }
      _ <- node0.casperEff.addBlock(unsignedBlock)
      _ <- node0.casperEff.contains(unsignedBlock) shouldBeF false

      signedBlock <- (node0.deployBuffer.addDeploy(data1) *> node0.casperEff.createBlock)
                      .map { case Created(block) => block }

      // NOTE: It can include both data0 and data1 because they don't conflict.
      _ = signedBlock.getBody.deploys.map(_.getDeploy) should contain only (data0, data1)

      _ <- node0.casperEff.addBlock(signedBlock)
      _ <- node0.casperEff.contains(signedBlock) shouldBeF true
      // Broadcast signedBlock to peers.
      _ <- node0.broadcaster.networkEffects(signedBlock, Valid)
      _ <- node1.receive() //receives block1; should not ask for block0

      _ <- node0.casperEff.contains(unsignedBlock) shouldBeF false
      _ <- node1.casperEff.contains(signedBlock) shouldBeF true
      _ <- node1.casperEff.contains(unsignedBlock) shouldBeF false

    } yield ()
  }

  it should "reject blocks not from bonded validators" in effectTest {
    val node = standaloneEff(genesis, otherValidatorKey)

    for {
      basicDeployData      <- ProtoUtil.basicDeploy[Task]()
      createBlockResult    <- node.deployBuffer.addDeploy(basicDeployData) *> node.casperEff.createBlock
      Created(signedBlock) = createBlockResult
      status               <- node.casperEff.addBlock(signedBlock)
      _                    = assert(status == InvalidUnslashableBlock)
      _                    = exactly(1, node.logEff.warns) should include("Ignoring message")
      _                    <- node.tearDownNode()
      result <- node.validateBlockStorage { blockStorage =>
                 blockStorage.getBlockMessage(signedBlock.blockHash) shouldBeF None
               }
    } yield result
  }

  def assertCreator(b: Block, validator: Keys.PublicKeyHashBS): Unit =
    assert(b.getHeader.validatorPublicKeyHash == validator)

  def assertValidatorSeqNum(b: Block, seqNum: Int): Unit =
    assert(
      b.getHeader.validatorBlockSeqNum == seqNum,
      s"Expected validatorBlockSeqNum=$seqNum got ${b.getHeader.validatorBlockSeqNum}"
    )

  def assertValidatorPrevMessage(
      b: Block,
      validator: ByteString,
      maybeMsg: Option[ByteString]
  ): Unit =
    assert(
      b.getHeader.justifications
        .find(_.validatorPublicKeyHash == validator)
        .map(_.latestBlockHash) == maybeMsg
    )

  it should "start numbering validators' blocks from 1" in effectTest {
    val node =
      standaloneEff(
        genesis,
        validatorKeys.head,
        faultToleranceThreshold = 0.1
      )
    val validator = validators.head

    for {
      block1 <- node.randomDeployAndPropose()
      _      = assertCreator(block1, validator)
      _      = assertValidatorSeqNum(block1, 1)
      _      = assertValidatorPrevMessage(block1, validator, None)
      block2 <- node.randomDeployAndPropose()
      _ = assertValidatorPrevMessage(
        block2,
        validator,
        Some(block1.blockHash)
      )
      _ = assertCreator(block2, validator)
      _ = assertValidatorSeqNum(block2, 2)
      _ <- node.tearDown()
    } yield ()
  }

  it should "not treat Genesis block as validator's latest message if it hasn't produced any" in effectTest {
    val validatorA = (validators(0), validatorKeys(0))
    val validatorB = (validators(1), validatorKeys(1))
    for {
      nodes <- networkEff(
                IndexedSeq(validatorA._2, validatorB._2),
                genesis
              )
      deploy1         <- ProtoUtil.basicDeploy[Task]()
      _               <- nodes(0).deployBuffer.addDeploy(deploy1) shouldBeF Right(())
      Created(block1) <- nodes(0).casperEff.createBlock
      _               = assertValidatorPrevMessage(block1, validatorB._1, None)
      _               <- nodes(1).casperEff.addBlock(block1) shouldBeF Valid
      deploy2         <- ProtoUtil.basicDeploy[Task]()
      _               <- nodes(1).deployBuffer.addDeploy(deploy2) shouldBeF Right(())
      Created(block2) <- nodes(1).casperEff.createBlock
      _               = assertValidatorPrevMessage(block2, validatorA._1, Some(block1.blockHash))
      _               <- nodes.toList.traverse_(_.tearDown())
    } yield ()
  }

  it should "propose blocks it adds to peers" in effectTest {
    for {
      nodes       <- networkEff(validatorKeys.take(2), genesis)
      signedBlock <- nodes(0).randomDeployAndPropose()
      _           <- nodes(1).receive()
      result      <- nodes(1).casperEff.contains(signedBlock) shouldBeF true
      _           <- nodes.map(_.tearDownNode()).toList.sequence
      _ <- nodes.toList.traverse_[Task, Assertion] { node =>
            node.validateBlockStorage {
              _.getBlockMessage(signedBlock.blockHash)
                .map(_.map(_.toProtoString)) shouldBeF Some(
                signedBlock.toProtoString
              )
            }
          }
    } yield result
  }

  it should "add a valid block from peer" in effectTest {
    for {
      nodes             <- networkEff(validatorKeys.take(2), genesis)
      signedBlock1Prime <- nodes(0).randomDeployAndPropose()
      _                 <- nodes(1).receive()
      _                 = nodes(1).logEff.infos.count(_ startsWith "Added") should be(1)
      result            = nodes(1).logEff.warns.count(_ startsWith "Recording invalid block") should be(0)
      _                 <- nodes.map(_.tearDownNode()).toList.sequence
      _ <- nodes.toList.traverse_[Task, Assertion] { node =>
            node.validateBlockStorage(
              _.getBlockMessage(signedBlock1Prime.blockHash) shouldBeF Some(
                signedBlock1Prime
              )
            )
          }
    } yield result
  }

  def createTestBlock(node: HashSetCasperTestNode[Task]) =
    for {
      deploy         <- ProtoUtil.basicDeploy[Task]()
      result         <- node.deployBuffer.addDeploy(deploy) *> node.casperEff.createBlock
      Created(block) = result
    } yield block

  it should "process blocks in parallel" in effectTest {
    for {
      nodes <- networkEff(validatorKeys.take(4), genesis)
      // Create blocks on different nodes that can add in parallel.
      // NOTE: GossipServiceCasperTestNode would feed notifications one by one.
      blocks <- Task.sequence(nodes.map(createTestBlock))
      // Add them all concurrently to the one of the nodes; it shouldn't run into validation problems.
      results <- Task.gatherUnordered {
                  blocks.map(nodes.head.casperEff.addBlock(_))
                }
    } yield {
      forAll(results) {
        _ shouldBe Valid
      }
    }
  }

  it should "handle multi-parent blocks correctly" in effectTest {
    for {
      nodes       <- networkEff(validatorKeys.take(2), genesis)
      deployData0 <- ProtoUtil.basicDeploy[Task]()
      deployData1 <- ProtoUtil.basicDeploy[Task]()
      deployData2 <- ProtoUtil.basicDeploy[Task]()
      deploys = Vector(
        deployData0,
        deployData1,
        deployData2
      )
      _ <- nodes(0).deployBuffer.addDeploy(deploys(0))
      _ <- nodes(0).propose()
      _ <- nodes(1).deployBuffer.addDeploy(deploys(1))
      _ <- nodes(1).propose()
      _ <- nodes(0).receive()
      _ <- nodes(1).receive()
      _ <- nodes(0).receive()
      _ <- nodes(1).receive()

      //multiparent block joining block0 and block1 since they do not conflict
      _                <- nodes(0).deployBuffer.addDeploy(deploys(2))
      multiParentBlock <- nodes(0).propose()
      _                <- nodes(1).receive()

      _ = nodes(0).logEff.warns shouldBe empty
      _ = nodes(1).logEff.warns shouldBe empty
      _ = multiParentBlock.header.get.parentHashes.size shouldBe 2
      _ = nodes(0).casperEff.contains(multiParentBlock) shouldBeF true
      _ = nodes(1).casperEff.contains(multiParentBlock) shouldBeF true

      _ = nodes.foreach(_.tearDown())
    } yield ()
  }

  it should "not fail if the forkchoice changes after a bonding event" in {
    val localValidators = validatorKeys.take(3)

    val localBonds =
      localValidators
        .map(_.publicKeyHashBS)
        .zip(List(10L, 30L, 5000L))
        .toMap

    val BlockMsgWithTransform(Some(localGenesis), _) =
      buildGenesis(Map.empty, localBonds, 0L)

    for {
      nodes <- networkEff(
                localValidators,
                localGenesis
              )

      (sk, pk)    = Ed25519.newKeyPair
      pkStr       = Base16.encode(pk)
      forwardCode = BondingUtil.bondingForwarderDeploy(pkStr, pkStr)
      bondingCode <- BondingUtil.faucetBondDeploy[Task](50, "ed25519", pkStr, sk)(
                      Sync[Task]
                    )
      forwardDeploy = ProtoUtil
        .sourceDeploy(
          forwardCode,
          System.currentTimeMillis()
        )
      bondingDeploy = ProtoUtil
        .sourceDeploy(
          bondingCode,
          forwardDeploy.getHeader.timestamp + 1
        )

      _ <- nodes.head.deployBuffer.addDeploy(forwardDeploy)
      _ <- nodes.head.deployBuffer.addDeploy(bondingDeploy)
      _ <- nodes.head.propose()

      _ <- nodes(1).receive()
      _ <- nodes.head.receive()
      _ <- nodes(2).clearMessages() //nodes(2) misses bonding

      _ <- (ProtoUtil
            .basicDeploy[Task]()
            >>= nodes(1).deployBuffer.addDeploy) *> nodes(1).propose()
      _ <- nodes.head.receive()
      _ <- nodes(1).receive()
      _ <- nodes(2).clearMessages() //nodes(2) misses block built on bonding

      _ <- (ProtoUtil
            .basicDeploy[Task]()
            >>= nodes(2).deployBuffer.addDeploy) *> nodes(2).propose()
      _ <- nodes.toList.traverse_(_.receive())
      //Since weight of nodes(2) is higher than nodes(0) and nodes(1)
      //their fork-choice changes, thus the new validator
      //is no longer bonded

      _ <- (ProtoUtil
            .basicDeploy[Task]()
            >>= nodes.head.deployBuffer.addDeploy) *> nodes.head
            .propose()
      _ <- nodes.toList.traverse_(_.receive())

      _ = nodes.foreach(_.logEff.warns shouldBe Nil)

      _ <- nodes.map(_.tearDown()).toList.sequence
    } yield ()
  }

  it should "reject addBlock when there exist deploy by the same (user, millisecond timestamp) in the chain" in {
    for {
      nodes <- networkEff(validatorKeys.take(2), genesis)
      deployDatas <- (0L to 2L).toList
                      .traverse[Task, Deploy](_ => ProtoUtil.basicDeploy[Task]())
      deployPrim0 = deployDatas(1)
        .withHeader(
          deployDatas(1).getHeader
            .withTimestamp(deployDatas(0).getHeader.timestamp)
            .withAccountPublicKeyHash(deployDatas(0).getHeader.accountPublicKeyHash)
        ) // deployPrim0 has the same (user, millisecond timestamp) with deployDatas(0)
      _            <- nodes(0).deployBuffer.addDeploy(deployDatas(0))
      signedBlock1 <- nodes(0).propose()
      _            <- nodes(1).receive() // receive block1

      _            <- nodes(0).deployBuffer.addDeploy(deployDatas(1))
      signedBlock2 <- nodes(0).propose()
      _            <- nodes(1).receive() // receive block2

      _            <- nodes(0).deployBuffer.addDeploy(deployDatas(2))
      signedBlock3 <- nodes(0).propose()
      _            <- nodes(1).receive() // receive block3

      _ <- nodes(1).casperEff.contains(signedBlock3) shouldBeF true

      _            <- nodes(1).deployBuffer.addDeploy(deployPrim0)
      signedBlock4 <- nodes(1).propose()
      _            <- nodes(0).receive() // still receive signedBlock4

      _ <- nodes(1).casperEff
            .contains(signedBlock4) shouldBeF true // Invalid blocks are still added
      _ <- nodes(0).casperEff.contains(signedBlock4) shouldBeF (false)
      _ = nodes(0).logEff.warns
        .count(_ contains "found deploy by the same (user, millisecond timestamp) produced") shouldBe (1)
      _ <- nodes.map(_.tearDownNode()).toList.sequence

      _ = nodes.toList.traverse_[Task, Assertion] { node =>
        node.validateBlockStorage { blockStorage =>
          for {
            _ <- blockStorage.getBlockMessage(signedBlock1.blockHash) shouldBeF Some(signedBlock1)
            _ <- blockStorage.getBlockMessage(signedBlock2.blockHash) shouldBeF Some(signedBlock2)
            result <- blockStorage.getBlockMessage(signedBlock3.blockHash) shouldBeF Some(
                       signedBlock3
                     )
          } yield result
        }
      }
    } yield ()
  }

  it should "ask peers for blocks it is missing" in effectTest {
    for {
      nodes <- networkEff(validatorKeys.take(3), genesis)
      deployDatas = deploysFromString(
        System.currentTimeMillis(),
        List("for(_ <- @1){ Nil } | @1!(1)", "@2!(2)")
      )

      _            <- nodes(0).deployBuffer.addDeploy(deployDatas(0))
      signedBlock1 <- nodes(0).propose()
      _            <- nodes(1).receive()
      _            <- nodes(2).clearMessages() //nodes(2) misses this block

      _            <- nodes(0).deployBuffer.addDeploy(deployDatas(1))
      signedBlock2 <- nodes(0).propose()

      _ <- nodes(1).receive() //receives block2
      _ <- nodes(2).receive() //receives block2; asks for block1
      _ <- nodes(1).receive() //receives request for block1; sends block1
      _ <- nodes(2).receive() //receives block1; adds both block1 and block2

      _ <- nodes(2).casperEff.contains(signedBlock1) shouldBeF true
      _ <- nodes(2).casperEff.contains(signedBlock2) shouldBeF true
      // TransportLayer gets 1 block, 1 is missing. GossipService gets 1 hash, 2 block missing.
      _ = nodes(2).logEff.infos
        .count(_ startsWith "Requested missing message") should (be >= 1 and be <= 2)
      // TransportLayer controlled by .receive calls, only node(1) responds. GossipService has unlimited retrieve, goes to node(0).
      result = (0 to 1)
        .flatMap(nodes(_).logEff.infos)
        .count(
          s => (s startsWith "Received request for block") && (s endsWith "Response sent.")
        ) should be >= 1

      _ <- nodes.map(_.tearDownNode()).toList.sequence
      _ <- nodes.toList.traverse_[Task, Assertion] { node =>
            node.validateBlockStorage { blockStorage =>
              for {
                _ <- blockStorage.getBlockMessage(signedBlock1.blockHash) shouldBeF Some(
                      signedBlock1
                    )
                result <- blockStorage.getBlockMessage(signedBlock2.blockHash) shouldBeF Some(
                           signedBlock2
                         )
              } yield result
            }
          }
    } yield result
  }

  /*
   *   DAG Looks like this:
   *
   *              h1
   *             /  \
   *            g1   g2
   *            |  X |
   *            f1   f2
   *             \  /
   *              e1
   *              |
   *              d1
   *             /  \
   *            c1   c2
   *            |  X |
   *            b1   b2
   *            |  X |
   *            a1   a2
   *             \  /
   *           genesis
   *
   *  f2 has in its justifications list c2. This should be handled properly.
   *
   */
  it should "ask peers for blocks it is missing and add them" in effectTest {

    /** nodes 0 and 1 create blocks in parallel; node 2 misses both, e.g. a1 and a2. */
    def stepSplit(nodes: Seq[TestNode[Task]]) =
      for {
        _ <- ProtoUtil.basicDeploy[Task]() >>= { deploy =>
              nodes(0).deployAndPropose(deploy)
            }
        _ <- ProtoUtil.basicDeploy[Task]() >>= { deploy =>
              nodes(1).deployAndPropose(deploy)
            }

        _ <- nodes(0).receive()
        _ <- nodes(1).receive()
        _ <- nodes(2).clearMessages() //nodes(2) misses this block
      } yield ()

    /** node 0 creates a block; node 1 gets it but node 2 doesn't. */
    def stepSingle(nodes: Seq[TestNode[Task]]) =
      for {
        _ <- ProtoUtil.basicDeploy[Task]() >>= { deploy =>
              nodes(0).deployAndPropose(deploy)
            }

        _ <- nodes(0).receive()
        _ <- nodes(1).receive()
        _ <- nodes(2).clearMessages() //nodes(2) misses this block
      } yield ()

    def propagate(nodes: Seq[TestNode[Task]]) =
      for {
        _ <- nodes(0).receive()
        _ <- nodes(1).receive()
        _ <- nodes(2).receive()
      } yield ()

    for {
      nodes <- networkEff(validatorKeys.take(3), genesis)

      _ <- stepSplit(nodes) // blocks a1 a2
      _ <- stepSplit(nodes) // blocks b1 b2
      _ <- stepSplit(nodes) // blocks c1 c2

      _ <- stepSingle(nodes) // block d1
      _ <- stepSingle(nodes) // block e1

      _ <- stepSplit(nodes) // blocks f1 f2
      _ <- stepSplit(nodes) // blocks g1 g2

      // this block will be propagated to all nodes and force nodes(2) to ask for missing blocks.
      br <- ProtoUtil.basicDeploy[Task]() >>= { deploy =>
             nodes(0).deployAndPropose(deploy)
           }

      // node(0) just created this block, so it should have it.
      _ <- nodes(0).casperEff.contains(br) shouldBeF true
      // Let every node get everything.
      _ <- List.fill(22)(propagate(nodes)).toList.sequence
      // By now node(2) should have received all dependencies and added block h1
      _ <- nodes(2).casperEff.contains(br) shouldBeF true
      // And if we create one more block on top of h1 it should be the only parent.
      nr <- ProtoUtil.basicDeploy[Task]() >>= { deploy =>
             nodes(2).deployAndPropose(deploy)
           }
      _ = nr.header.get.parentHashes.map(PrettyPrinter.buildString) shouldBe Seq(
        PrettyPrinter.buildString(br.blockHash)
      )

      _ <- nodes.map(_.tearDownNode()).toList.sequence
    } yield ()
  }

  it should "add equivocation blocks" in effectTest {
    for {
      nodes <- networkEff(validatorKeys.take(2), genesis)

      // Creates a pair that constitutes equivocation blocks
      basicDeployData0 <- ProtoUtil.basicDeploy[Task]()
      createBlockResult1 <- nodes(0).deployBuffer
                             .addDeploy(basicDeployData0) *> nodes(0).casperEff.createBlock
      Created(signedBlock1) = createBlockResult1
      basicDeployData1      <- ProtoUtil.basicDeploy[Task]()
      createBlockResult1Prime <- nodes(0).deployBuffer
                                  .addDeploy(basicDeployData1) *> nodes(0).casperEff.createBlock
      Created(signedBlock1Prime) = createBlockResult1Prime

      _ <- nodes(0).casperEff
            .addBlock(signedBlock1)
            .flatTap(nodes(0).broadcaster.networkEffects(signedBlock1, _)) shouldBeF Valid
      _ <- nodes(1).receive()
      _ <- nodes(0).casperEff.addBlock(signedBlock1Prime) shouldBeF SelfEquivocatedBlock
      // NOTE: Actual implementation would NOT DO THIS.
      // signedBlock1Prime and signedBlock1 create a self-equivocation (node-0) sees itself equivocating
      // with this pair of blocks. We don't gossip self-equivocating blocks but we want to test
      // that a node adds to its DAG blocks that create an equivocation.
      _ <- nodes(0).broadcaster.networkEffects(signedBlock1Prime, EquivocatedBlock)
      _ <- nodes(1).receive()

      _ <- nodes(1).casperEff.contains(signedBlock1) shouldBeF true
      result <- nodes(1).casperEff
                 .contains(signedBlock1Prime) shouldBeF true // we still add the equivocated block to dag

      _ <- nodes(0).tearDownNode()
      _ <- nodes(1).tearDownNode()
      _ <- nodes(1).validateBlockStorage { blockStorage =>
            for {
              _ <- blockStorage.getBlockMessage(signedBlock1.blockHash) shouldBeF Some(signedBlock1)
              result <- blockStorage.getBlockMessage(signedBlock1Prime.blockHash) shouldBeF Some(
                         signedBlock1Prime
                       )
            } yield result
          }
    } yield result
  }

  it should "track equivocating blocks added at the same time" in effectTest {
    // Does not add block to the node's DAG.
    def deployAndCreate(node: TestNode[Task]): Task[Block] =
      for {
        deploy         <- ProtoUtil.basicDeploy[Task]()
        _              <- node.deployBuffer.addDeploy(deploy)
        result         <- node.casperEff.createBlock
        Created(block) = result
      } yield block

    for {
      nodes <- networkEff(validatorKeys.take(2), genesis)

      blockA <- deployAndCreate(nodes(0))
      blockB <- deployAndCreate(nodes(0))

      _ <- Task.gatherUnordered {
            List(blockA, blockB).map(nodes(1).casperEff.addBlock)
          }

      equivocators <- nodes(1).dagStorage.getRepresentation.flatMap(_.getEquivocators)
    } yield {
      equivocators.contains(nodes(0).ownValidatorKey) shouldBe true
    }
  }

  it should "not ignore adding equivocation blocks when a child is revealed later" in effectTest {
    for {
      nodes <- networkEff(validatorKeys.take(2), genesis)

      makeDeploy = (n: Int) => {
        for {
          deploy         <- ProtoUtil.basicDeploy[Task]()
          result         <- nodes(n).deployBuffer.addDeploy(deploy) *> nodes(n).casperEff.createBlock
          Created(block) = result
        } yield block
      }

      // Creates a pair that constitutes equivocation blocks
      block1      <- makeDeploy(0)
      block1Prime <- makeDeploy(0)

      _ <- nodes(0).casperEff.addBlock(block1) shouldBeF Valid
      _ <- nodes(0).casperEff.addBlock(block1Prime) shouldBeF SelfEquivocatedBlock
      _ <- nodes(1).clearMessages()
      _ <- nodes(1).casperEff.addBlock(block1Prime) shouldBeF Valid
      _ <- nodes(0).receive()

      _ <- nodes(0).casperEff.contains(block1) shouldBeF true
      _ <- nodes(0).casperEff.contains(block1Prime) shouldBeF true

      block2Prime <- makeDeploy(1)
      _           <- nodes(1).addAndBroadcast(block2Prime)
      _           <- nodes(0).receive()
      // Process dependencies
      _ <- nodes(1).receive()
      _ <- nodes(0).receive()

      _ <- nodes(0).casperEff.contains(block2Prime) shouldBeF true
      _ <- nodes(0).casperEff.contains(block1Prime) shouldBeF true

      _ <- nodes.toList.traverse(_.tearDown())
    } yield ()
  }

  it should "detect self equivocation when validating an incoming block" in effectTest {
    for {
      // Starting 2 nodes with the same validator key.
      nodes <- networkEff(
                IndexedSeq(validatorKeys.head, validatorKeys.head),
                genesis
              )
      // Creates a pair that constitutes equivocation blocks
      signedBlock1 <- createTestBlock(nodes(0))
      signedBlock2 <- createTestBlock(nodes(1))

      _ <- nodes(0).addAndBroadcast(signedBlock1)
      _ <- nodes(1).receive()
      _ <- nodes(1).casperEff.contains(signedBlock1) shouldBeF true

      _ <- nodes(1).casperEff
            .addBlock(signedBlock2) shouldBeF SelfEquivocatedBlock
    } yield ()
  }

  it should "not relay blocks that create a self equivocation" in effectTest {
    for {
      nodes <- networkEff(validatorKeys.take(2), genesis)
      // Creates a pair that constitutes equivocation blocks
      signedBlock1      <- createTestBlock(nodes(0))
      signedBlock1Prime <- createTestBlock(nodes(0))

      _ <- nodes(0).addAndBroadcast(signedBlock1)
      _ <- nodes(1).receive()
      _ <- nodes(1).casperEff.contains(signedBlock1) shouldBeF true

      _ <- nodes(0).addAndBroadcast(signedBlock1Prime)
      _ <- nodes(1).receive()

      _ <- nodes(1).casperEff
            .contains(signedBlock1Prime) shouldBeF false
    } yield ()
  }

  it should "reject blocks that include an invalid block pointer" in effectTest {
    for {
      nodes           <- networkEff(validatorKeys.take(3), genesis)
      deploys         <- (1L to 6L).toList.traverse(_ => ProtoUtil.basicDeploy[Task]())
      deploysWithCost = deploys.map(d => ProcessedDeploy(deploy = Some(d))).toIndexedSeq

      createBlockResult <- nodes(0).deployBuffer
                            .addDeploy(deploys(0)) *> nodes(0).casperEff.createBlock
      Created(signedBlock) = createBlockResult
      signedInvalidBlock = BlockUtil.resignBlock(
        signedBlock.withHeader(signedBlock.getHeader.withValidatorBlockSeqNum(-2)),
        nodes(0).validatorId.privateKey
      ) // Invalid seq num

      // This is going to be signed by node(1)
      blockWithInvalidJustification = buildBlockWithInvalidJustification(
        deploysWithCost,
        signedInvalidBlock
      )

      // Adding the block that only refers to an invalid node as justification; it will not send notifications.
      _ <- nodes(1).casperEff
            .addBlock(blockWithInvalidJustification)
      _ <- nodes(0)
            .clearMessages() // nodes(0) rejects normal adding process for blockThatPointsToInvalidBlock

      // NOTE: Disabled the following code becasue it relies on the TransportLayer message types.
      // signedInvalidBlockPacketMessage = packet(
      //   nodes(0).local,
      //   transport.Block,
      //   signedInvalidBlock.toByteString
      // )
      // _ <- nodes(0).transportLayerEff.send(nodes(1).local, signedInvalidBlockPacketMessage)
      // _ <- nodes(1).receive() // receives signedInvalidBlock; attempts to add both blocks
      // NOTE: Instead of the above let's just add it directly to node(1); node(0) doesn't have this block.
      _ <- nodes(1).casperEff
            .addBlock(signedInvalidBlock)

      _ = nodes(1).logEff.warns.count(_ startsWith "Recording invalid message") should be(1)
      _ <- nodes(1).casperEff.contains(signedInvalidBlock) shouldBeF false
      _ <- nodes.map(_.tearDown()).toList.sequence
    } yield ()
  }

  it should "handle a long chain of block requests appropriately" in effectTest {
    for {
      nodes <- networkEff(
                validatorKeys.take(2),
                genesis,
                storageSize = 1024L * 1024 * 10
              )

      _ <- (1L to 10L).toList.traverse_[Task, Unit] { _ =>
            for {
              deploy <- ProtoUtil.basicDeploy[Task]()
              _      <- nodes(0).deployBuffer.addDeploy(deploy)
              _      <- nodes(0).propose()
              _      <- nodes(1).clearMessages() //nodes(1) misses this block
            } yield ()
          }
      deployData10 <- ProtoUtil.basicDeploy[Task]()
      _            <- nodes(0).deployBuffer.addDeploy(deployData10)
      _            <- nodes(0).propose()
      // Cycle of requesting and passing blocks until block #9 from nodes(0) to nodes(1)
      _ <- (0 to 8).toList.traverse_[Task, Unit] { _ =>
            nodes(1).receive().void *> nodes(0).receive().void
          }

      // We simulate a network failure here by not allowing block #10 to get passed to nodes(1)
      _ <- nodes(0).receive()

      reqCnt = nodes(1).logEff.infos.count(_ startsWith "Requested missing message")
      _      = reqCnt should be >= 10 // TransportLayer
      _      = reqCnt should be <= 11 // GossipService

      resCnt = nodes(0).logEff.infos.count(
        s => (s startsWith "Received request for block") && (s endsWith "Response sent.")
      )
      _ = resCnt shouldBe reqCnt

      _ <- nodes.map(_.tearDown()).toList.sequence
    } yield ()
  }

  it should "create valid block when receives an invalid block" in effectTest {
    for {
      nodes <- networkEff(
                validatorKeys.take(2),
                genesis
              )
      deployData1 <- ProtoUtil.basicDeploy[Task]()
      createBlock1Result <- nodes(0).deployBuffer
                             .addDeploy(deployData1) *> nodes(0).casperEff.createBlock
      Created(block1) = createBlock1Result
      invalidBlock1 = BlockUtil.resignBlock(
        block1.withHeader(block1.getHeader.withTimestamp(Long.MaxValue)),
        nodes(0).validatorId.privateKey
      )
      _ <- nodes(0).casperEff
            .addBlock(invalidBlock1)
            .flatTap(nodes(0).broadcaster.networkEffects(invalidBlock1, _))
      _ = nodes(0).logEff.warns.count(_ startsWith "Recording invalid message") should be(1)
      // nodes(0) won't send invalid blocks
      _ <- nodes(1).receive()
      _ <- nodes(1).casperEff
            .contains(invalidBlock1) shouldBeF false
      // we manually add this invalid block to node1
      _ <- nodes(1).addAndBroadcast(invalidBlock1)
      _ <- nodes(1).casperEff
            .contains(invalidBlock1) shouldBeF false
      deployData2 <- ProtoUtil.basicDeploy[Task]()
      _           <- nodes(1).deployBuffer.addDeploy(deployData2)
      block2      <- nodes(1).propose()
      _           <- nodes(0).receive()
      _           <- nodes.map(_.tearDownNode()).toList.sequence
      _ <- nodes.toList.traverse_[Task, Assertion] { node =>
            node.validateBlockStorage { blockStorage =>
              for {
                _      <- blockStorage.getBlockMessage(invalidBlock1.blockHash) shouldBeF None
                result <- blockStorage.getBlockMessage(block2.blockHash) shouldBeF Some(block2)
              } yield result
            }
          }
    } yield ()
  }

  it should "put orphaned deploys back into the pending deploy buffer" in effectTest {
    for {
      nodes <- networkEff(
                validatorKeys.take(2),
                genesis,
                faultToleranceThreshold = 0.0,
                maybeMakeEE =
                  Some(HashSetCasperTestNode.simpleEEApi[Task](_, generateConflict = true))
              )

      sessionCode = ByteString.copyFromUtf8("Do the thing")
      deployA     = ProtoUtil.deploy(0, sessionCode)
      deployB     = ProtoUtil.deploy(0, sessionCode)

      _                <- nodes(0).deployBuffer.addDeploy(deployA)
      createA          <- nodes(0).casperEff.createBlock
      Created(blockA)  = createA
      _                <- nodes(0).casperEff.addBlock(blockA) shouldBeF Valid
      processedDeploys <- nodes(0).deployStorage.reader.readProcessed
      _                = processedDeploys should contain(deployA)

      _               <- nodes(1).deployBuffer.addDeploy(deployB)
      createB         <- nodes(1).casperEff.createBlock
      Created(blockB) = createB
      _               <- nodes(0).casperEff.addBlock(blockB) shouldBeF Valid
      // nodes(1) should have more weight than nodes(0) so it should take over
      // Need to propose a new block, it should again contain deployA
      // Since requeuing of orphans happens in the background fiber, trigerred during `createBlock`,
      // the very first `createBlock` most likely returns `NoNewDeploys` because background fiber
      // hasn't yet finished.
      _ <- (eventuallyIncludes(nodes(0), deployA)).timeout(500.millis)
      // Do another call to `createBlock` since now it should have requeuened orphaned deployA.
      createC         <- nodes(0).casperEff.createBlock
      Created(blockC) = createC
      _               = blockC.getBody.deploys.map(_.getDeploy) should contain(deployA)
      _               <- nodes.map(_.tearDown()).toList.sequence
    } yield ()
  }

  def eventuallyIncludes(node: TestNode[Task], deploy: Deploy): Task[Assertion] =
    node.casperEff.createBlock flatMap {
      case NoNewDeploys => eventuallyIncludes(node, deploy)
      case Created(block) =>
        if (block.getBody.deploys.map(_.getDeploy) contains deploy) {
          Task.now(assert(true))
        } else Task.now(assert(false, "Block did not include expected deploy"))
    }

  it should "not execute deploys until dependencies are met" in effectTest {
    import io.casperlabs.models.DeployImplicits._
    val node =
      standaloneEff(
        genesis,
        validatorKeys.head,
        faultToleranceThreshold = 0.1
      )

    for {
      deploy1 <- ProtoUtil.basicDeploy[Task]()
      deploy2 <- ProtoUtil
                  .basicDeploy[Task]()
                  .map(_.withDependencies(Seq(deploy1.deployHash)).signSingle)
      _               <- node.deployBuffer.addDeploy(deploy1) shouldBeF Right(())
      _               <- node.deployBuffer.addDeploy(deploy2) shouldBeF Right(())
      Created(block1) <- node.casperEff.createBlock
      _               <- node.casperEff.addBlock(block1) shouldBeF Valid
      Created(block2) <- node.casperEff.createBlock
      _               = block1.getBody.deploys.map(_.getDeploy) shouldBe Seq(deploy1)
      _               = block2.getBody.deploys.map(_.getDeploy) shouldBe Seq(deploy2)
      _               <- node.tearDown()
    } yield ()
  }

  it should "not include deploys with too long TTL in the block" in effectTest {
    val maxTtl = 1.day // maximum TTL used in tests is 1 day
    val node =
      standaloneEff(
        genesis,
        validatorKeys.head,
        faultToleranceThreshold = 0.1
      )

    for {
      deploy <- ProtoUtil.basicDeploy[Task]().map(_.withTtl(2 * maxTtl.toMillis.toInt))
      _      <- node.deployBuffer.addDeploy(deploy) shouldBeF Right(())
      status <- node.casperEff.createBlock
    } yield assert(status == NoNewDeploys)

  }

  it should "only execute deploys during the appropriate time window" in effectTest {
    import io.casperlabs.models.DeployImplicits._
    val node =
      standaloneEff(
        genesis,
        validatorKeys.head,
        faultToleranceThreshold = 0.1
      )
    val minTTL = 60 * 60 * 1000
    for {
      deploy1 <- ProtoUtil
                  .basicDeploy[Task]()
                  .map(_.withTimestamp(3L).withTtl(minTTL).signSingle)
      deploy2 <- ProtoUtil
                  .basicDeploy[Task]()
                  .map(_.withTimestamp(5L).withTtl(minTTL).signSingle)
      _               <- node.deployBuffer.addDeploy(deploy1) shouldBeF Right(()) // gets deploy1
      _               <- node.deployBuffer.addDeploy(deploy2) shouldBeF Right(()) // gets deploy2
      _               <- Task.delay { node.timeEff.clock = 0 }
      _               <- node.deployStorage.reader.readPending.map(_.toSet) shouldBeF Set(deploy1, deploy2)
      _               <- node.casperEff.createBlock shouldBeF NoNewDeploys // too early to execute deploy, since t = 1
      _               <- node.deployStorage.reader.readPending.map(_.toSet) shouldBeF Set(deploy1, deploy2)
      _               <- Task.delay { node.timeEff.clock = 3 }
      Created(block1) <- node.casperEff.createBlock //now we can execute deploy1, but not deploy2
      _               <- node.casperEff.addBlock(block1) shouldBeF Valid
      _               = block1.getBody.deploys.map(_.getDeploy) shouldBe Seq(deploy1)
      _               <- node.deployStorage.reader.readPending.map(_.toSet) shouldBeF Set(deploy2)
      _               <- Task.delay { node.timeEff.clock = minTTL.toLong + 10L }
      _               <- node.casperEff.createBlock shouldBeF NoNewDeploys // now it is too late to execute deploy2
      _               <- node.deployStorage.reader.readPending.map(_.toSet) shouldBeF Set.empty[Deploy]
      _               <- node.tearDown()
    } yield ()
  }

  it should "not execute deploys which are already in the past" in effectTest {
    val bonds = createBonds(validators.take(2))
    val BlockMsgWithTransform(Some(genesis), _) =
      buildGenesis(Map.empty, bonds, 0L)

    def deploySomething(nodes: IndexedSeq[TestNode[Task]], i: Int) =
      for {
        deploy         <- ProtoUtil.basicDeploy[Task]()
        _              <- nodes(i).deployBuffer.addDeploy(deploy)
        create         <- nodes(i).casperEff.createBlock
        Created(block) = create
        _              <- nodes(i).casperEff.addBlock(block) shouldBeF Valid
        _              <- nodes(1 - i).casperEff.addBlock(block) shouldBeF Valid
      } yield deploy

    for {
      nodes <- networkEff(
                validatorKeys.take(2),
                genesis
              )

      deploy <- deploySomething(nodes, 0)
      // Deploy a few more things to both nodes so things get finalized.
      // NOTE: A single node currently doesn't finalize anything.
      _ <- deploySomething(nodes, 1)
      _ <- deploySomething(nodes, 0)
      _ <- deploySomething(nodes, 1)

      // The first deploy should be finalized, so not stop it appearing again as pending.
      processedDeploys <- nodes(0).deployStorage.reader.readProcessed
      _                = processedDeploys should not contain (deploy)

      // Should be able to enqueue the deploy again.
      _               <- nodes(0).deployBuffer.addDeploy(deploy)
      pendingDeploys1 <- nodes(0).deployStorage.reader.readPending
      _               = pendingDeploys1 should contain(deploy)

      // Should not put it in a block.
      createB <- nodes(0).casperEff.createBlock
      _       = createB shouldBe CreateBlockStatus.noNewDeploys

      // Should discard the deploy.
      pendingDeploys2 <- nodes(0).deployStorage.reader.readPending
      _               = pendingDeploys2 should not contain (deploy)

      _ <- nodes.toList.traverse(_.tearDown())
    } yield ()
  }

  it should "not merge blocks which have the same deploy in their history" in effectTest {
    // Make a network where we don't validate nonces, I just want the merge conflict.
    for {
      nodes <- networkEff(
                validatorKeys.take(2),
                genesis
              )

      deployA          <- ProtoUtil.basicDeploy[Task]()
      _                <- nodes(0).deployBuffer.addDeploy(deployA)
      createA0         <- nodes(0).casperEff.createBlock
      Created(blockA0) = createA0
      _                <- nodes(0).casperEff.addBlock(blockA0) shouldBeF Valid

      _                <- nodes(1).deployBuffer.addDeploy(deployA)
      createA1         <- nodes(1).casperEff.createBlock
      Created(blockA1) = createA1
      _                <- nodes(1).casperEff.addBlock(blockA1) shouldBeF Valid

      // Tell nodes(1) about the block from nodes(0) which has the same deploy.
      _ <- nodes(1).casperEff.addBlock(blockA0) shouldBeF Valid

      // Try to build a new block on top of both, they shouldn't merge.
      deployB          <- ProtoUtil.basicDeploy[Task]()
      _                <- nodes(1).deployBuffer.addDeploy(deployB)
      createB1         <- nodes(1).casperEff.createBlock
      Created(blockB1) = createB1

      // nodes(1) has more weight then nodes(0)
      _ = blockB1.getHeader.parentHashes should have size 1
      _ = blockB1.getHeader.parentHashes.head shouldBe blockA1.blockHash

      _ <- nodes.map(_.tearDown()).toList.sequence
    } yield ()
  }

  it should "return NoNewDeploys status if there are no deploys to put into the block after executing contracts" in effectTest {
    val node = standaloneEff(genesis, validatorKeys.head)
    import node._

    val deploy = ProtoUtil.deploy(timestamp = 1L)

    for {
      Created(block) <- node.deployBuffer.addDeploy(deploy) *> MultiParentCasper[Task].createBlock
      _              <- MultiParentCasper[Task].addBlock(block)
      _              <- MultiParentCasper[Task].createBlock shouldBeF io.casperlabs.casper.NoNewDeploys
    } yield ()
  }

  it should "set node's LFB as block's key_block" in effectTest {
    val node = standaloneEff(genesis, validatorKeys.head)

    for {
      blockA <- node.deployAndPropose(ProtoUtil.deploy(timestamp = 1L))
      _      = assert(blockA.getHeader.keyBlockHash == genesis.blockHash)
      _      <- node.lastFinalizedBlockHashContainer.set(blockA.blockHash)
      blockB <- node.deployAndPropose(ProtoUtil.deploy(timestamp = 2L))
      _      = assert(blockB.getHeader.keyBlockHash == blockA.blockHash)
    } yield ()
  }

  import io.casperlabs.models.BlockImplicits._
  it should "create block correctly when there is an equivocator amongst validators" in effectTest {
    // An attempt to replicate error that occured in the CI.
    // Setup:
    // Two nodes (A and B).
    // Flow:
    // 1. A & B are up.
    // 2. A proposes blockA.
    // 3. Propagate blockA across the network. B receives it.
    // 4. Clear A's storage.
    // 5. A proposes blockAPrime (because of point 3, this creates an equivocation).
    // 6. Propagate blockAPrime across the network. B sees A equivocating.
    // 7. B proposes blockB1 (includes blockA and blockAPrime in its justifications, picks one as parent).
    // 8. B proposes blockB2 (includes blockA, blockAPrime and blockB1 as justifications, blockB1 is a parent).
    for {
      nodes       <- networkEff(validatorKeys.take(2), genesis)
      blockA      <- nodes(0).deployAndPropose(ProtoUtil.deploy(timestamp = 1L))
      _           <- nodes(1).receive()
      _           <- nodes(0).clearStorage()
      blockAPrime <- nodes(0).deployAndPropose(ProtoUtil.deploy(timestamp = 2L))
      _           <- nodes(1).receive()
      // Test that node-1 knows about node-0' equivocations
      _       <- nodes(1).getEquivocators shouldBeF Set(nodes(0).ownValidatorKey)
      blockB1 <- nodes(1).deployAndPropose(ProtoUtil.deploy(timestamp = 3))
      _ = blockB1.justificationHashes should contain theSameElementsAs Set(
        blockA.blockHash,
        blockAPrime.blockHash
      )
      Left(selfEquivocationError) <- nodes(0).receive().attempt
      _                           = assert(selfEquivocationError.getMessage.contains("SelfEquivocatedBlock"))
      _                           = assert(blockB1.getHeader.keyBlockHash == genesis.blockHash)
      _                           = assert(blockB1.parentHashes.size == 1)
      blockB2                     <- nodes(1).deployAndPropose(ProtoUtil.deploy(timestamp = 4L))
      _ = blockB2.justificationHashes should contain theSameElementsAs Set(
        blockA.blockHash,
        blockAPrime.blockHash,
        blockB1.blockHash
      )
      _ = blockB2.parentHashes should contain theSameElementsAs Set(blockB1.blockHash)
    } yield ()
  }

  private def buildBlockWithInvalidJustification(
      deploys: immutable.IndexedSeq[ProcessedDeploy],
      signedInvalidBlock: Block
  ): Block = {
    val postState =
      Block.GlobalState().withBonds(ProtoUtil.bonds(genesis))
    val serializedJustifications =
      Seq(
        Justification(
          signedInvalidBlock.getHeader.validatorPublicKeyHash,
          signedInvalidBlock.blockHash
        )
      )
    val body = Block.Body().withDeploys(deploys)
    val header = Block
      .Header()
      .withParentHashes(signedInvalidBlock.header.get.parentHashes)
      .withJustifications(serializedJustifications)
      .withBodyHash(ProtoUtil.protoHash(body))
      .withState(postState)
      .withJRank(1)
    val blockHash = ProtoUtil.protoHash(header)
    val blockThatPointsToInvalidBlock =
      Block()
        .withBlockHash(blockHash)
        .withHeader(header)
        .withBody(body)
    val validatorId = validatorKeys(1)
    ProtoUtil.signBlock(
      blockThatPointsToInvalidBlock,
      validatorId.privateKey,
      validatorId.signatureAlgorithm
    )
  }
}

object HashSetCasperTest {
  def createBonds(validators: Seq[Keys.PublicKeyHashBS]): Map[Keys.PublicKeyHashBS, Long] =
    validators.zipWithIndex.map { case (v, i) => v -> (2L * i.toLong + 1L) }.toMap

  def createGenesis(bonds: Map[Keys.PublicKeyHashBS, Long]): BlockMsgWithTransform =
    buildGenesis(Map.empty, bonds, 0L)

  def buildGenesis(
      wallets: Map[Keys.PublicKeyHashBS, Long],
      bonds: Map[Keys.PublicKeyHashBS, Long],
      timestamp: Long
  ): BlockMsgWithTransform = {
    implicit val metricsEff              = new Metrics.MetricsNOP[Task]
    implicit val logEff                  = LogStub[Task]()
    implicit val casperSmartContractsApi = HashSetCasperTestNode.simpleEEApi[Task](Map.empty)

    val spec = ipc.ChainSpec
      .GenesisConfig()
      .withName("casperlabs")
      .withTimestamp(timestamp)
      .withProtocolVersion(state.ProtocolVersion(1))
      .withEeConfig(
        ipc.ChainSpec.GenesisConfig
          .ExecConfig()
          .withAccounts((bonds.keySet ++ wallets.keySet).toSeq.map { key =>
            ipc.ChainSpec.GenesisConfig.ExecConfig
              .GenesisAccount()
              .withPublicKeyHash(key)
              .withBalance(state.BigInt(wallets.getOrElse(key, 0L).toString, bitWidth = 512))
              .withBondedAmount(state.BigInt(bonds.getOrElse(key, 0L).toString, bitWidth = 512))
          })
      )

    StorageFixture
      .createMemoryStorages[Task]()
      .use {
        case (
            implicit0(blockStorage: BlockStorage[Task]),
            _,
            _,
            implicit0(fs: FinalityStorage[Task]),
            _
            ) =>
          Genesis.fromChainSpec[Task](spec)
      }
      .unsafeRunSync
  }
}
