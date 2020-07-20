package io.casperlabs.casper

import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.DeploySelection.DeploySelection
import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.casper.consensus.Block.{Justification, MessageType}
import io.casperlabs.casper.consensus._
import io.casperlabs.casper.consensus.state.ProtocolVersion
import io.casperlabs.casper.helper.BlockGenerator._
import io.casperlabs.casper.helper.BlockUtil.{generateHash, generateValidator}
import io.casperlabs.casper.helper.{
  BlockGenerator,
  DeployOps,
  HashSetCasperTestNode,
  NoOpsEventEmitter,
  StorageFixture
}
import io.casperlabs.casper.helper.DeployOps.ChangeDeployOps
import io.casperlabs.casper.scalatestcontrib._
import io.casperlabs.casper.util.BondingUtil.Bond
import io.casperlabs.casper.util.{CasperLabsProtocol, ProtoUtil}
import io.casperlabs.casper.util.execengine.ExecEngineUtilTest.prepareDeploys
import io.casperlabs.casper.util.execengine.{
  DeploysCheckpoint,
  ExecEngineUtil,
  ExecutionEngineServiceStub
}
import io.casperlabs.casper.validation.Errors.{
  DeployHeaderError,
  DropErrorWrapper,
  ValidateErrorWrapper
}
import io.casperlabs.casper.validation.{Validation, ValidationImpl}
import io.casperlabs.catscontrib.{Fs2Compiler, MonadThrowable}
import io.casperlabs.crypto.Keys
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.crypto.signatures.SignatureAlgorithm.Ed25519
import io.casperlabs.ipc.ChainSpec.DeployConfig
import io.casperlabs.models.{ArbitraryConsensus, Message}
import io.casperlabs.mempool.DeployBuffer
import io.casperlabs.models.ArbitraryConsensus
import io.casperlabs.models.BlockImplicits.BlockOps
import io.casperlabs.p2p.EffectsTestInstances.LogicalTime
import io.casperlabs.shared.LogStub
import io.casperlabs.shared.Time
import io.casperlabs.smartcontracts.ExecutionEngineService
import io.casperlabs.storage.BlockMsgWithTransform
import io.casperlabs.storage.block._
import io.casperlabs.storage.dag._
import io.casperlabs.storage.deploy.DeployStorage
import io.casperlabs.casper.validation.NCBValidationImpl
import io.casperlabs.catscontrib
import monix.eval.Task
import monix.execution.Scheduler
import org.scalacheck.Arbitrary.arbitrary
import org.scalatest.{BeforeAndAfterEach, EitherValues, FlatSpec, Matchers}
import org.scalatest.prop.GeneratorDrivenPropertyChecks.forAll
import logstage.LogIO

import scala.collection.immutable.HashMap
import scala.concurrent.duration._

class ValidationTest
    extends FlatSpec
    with Matchers
    with EitherValues
    with BeforeAndAfterEach
    with BlockGenerator
    with StorageFixture
    with ArbitraryConsensus {
  implicit val emitter                                = NoOpsEventEmitter.create[Task]
  implicit val timeEff                                = new LogicalTime[Task](System.currentTimeMillis)
  override implicit val log: LogIO[Task] with LogStub = LogStub[Task]()
  implicit val raiseValidateErr                       = validation.raiseValidateErrorThroughApplicativeError[Task]
  implicit val versions =
    CasperLabsProtocol.unsafe[Task](
      (
        0L,
        state.ProtocolVersion(1),
        Some(DeployConfig(24 * 60 * 60 * 1000, 10, 10 * 1024 * 1024, 0))
      )
    )

  implicit val validationEff = new NCBValidationImpl[Task]()

  // Necessary because errors are returned via Sync which has an error type fixed to _ <: Throwable.
  // When raise errors we wrap them with Throwable so we need to do the same here.
  implicit def wrapWithThrowable[A <: InvalidBlock](err: A): Throwable =
    ValidateErrorWrapper(err)

  implicit def `Long => MainRank`(in: Long): Message.MainRank = Message.asMainRank(in)

  implicit val consensusConfig = ConsensusConfig()

  val ed25519   = "ed25519"
  val chainName = "testnet"

  def toPublicKeyHash(pk: Keys.PublicKey) =
    Keys.PublicKeyHash(ByteString.copyFrom(Ed25519.publicKeyHash(pk)))

  override def beforeEach(): Unit = {
    log.reset()
    timeEff.reset()
  }

  def withoutStorage(t: => Task[_]) = {
    import Scheduler.Implicits.global
    t.runSyncUnsafe(5.seconds)
  }

  def createChain[F[_]: MonadThrowable: Time: BlockStorage: DagStorage](
      length: Int,
      bonds: Seq[Bond] = Seq.empty[Bond],
      creator: Validator = EmptyValidator,
      maybeGenesis: Option[Block] = None
  ): F[List[Block]] =
    (0 until length)
      .foldLeft(
        maybeGenesis
          .fold(createAndStoreMessage[F](Seq.empty, bonds = bonds).map(List(_)))(
            b => List(b).pure[F]
          )
      ) {
        case (blocksF, _) =>
          for {
            blocks         <- blocksF
            bprev          = blocks.head
            dag            <- DagStorage[F].getRepresentation
            latestMsgs     <- dag.latestMessages
            justifications = latestMsgs.mapValues(_.map(_.messageHash))
            bnext <- createAndStoreMessageNew[F](
                      Seq(bprev.blockHash),
                      maybeGenesis.map(_.blockHash).getOrElse(ByteString.EMPTY),
                      creator,
                      bonds,
                      justifications
                    )
          } yield bnext :: blocks
      }
      .map(_.reverse)

  def createChainWithRoundRobinValidators[F[_]: MonadThrowable: Time: BlockStorage: DagStorage: DeployStorage](
      length: Int,
      validatorLength: Int
  ): F[List[Block]] = {
    val validatorRoundRobinCycle = Stream.continually(0 until validatorLength).flatten
    val validators               = List.fill(validatorLength)(generateValidator())
    (0 until length).toList
      .zip(validatorRoundRobinCycle)
      .foldLeft(
        for {
          genesis             <- createAndStoreMessage[F](Seq.empty)
          emptyLatestMessages <- HashMap.empty[Validator, BlockHash].pure[F]
        } yield (List(genesis), emptyLatestMessages)
      ) {
        case (acc, (_, validatorNum)) =>
          val creator = validators(validatorNum)
          for {
            unwrappedAcc             <- acc
            (blocks, latestMessages) = unwrappedAcc
            bnext <- createAndStoreMessage[F](
                      parentsHashList = Seq(blocks.head.blockHash),
                      creator = creator,
                      justifications = latestMessages
                    )
            latestMessagesNext = latestMessages.updated(
              Keys.PublicKeyHash(bnext.getHeader.validatorPublicKeyHash),
              bnext.blockHash
            )
          } yield (bnext :: blocks, latestMessagesNext)
      }
      .map(_._1)
  }

  def signedBlock(
      block: Block
  )(implicit sk: Keys.PrivateKey): Block =
    ProtoUtil.signBlock(block, sk, Ed25519)

  implicit class ChangeBlockOps(b: Block) {
    def changeBlockNumber(n: Long): Block = {
      val header    = b.getHeader
      val newHeader = header.withJRank(n)
      // NOTE: blockHash should be recalculated.
      b.withHeader(newHeader)
    }
    def changeSeqNum(n: Int): Block = {
      val header    = b.getHeader
      val newHeader = header.withValidatorBlockSeqNum(n)
      // NOTE: blockHash should be recalculated.
      b.withHeader(newHeader)
    }
    def changeValidator(key: Validator): Block = {
      val header    = b.getHeader
      val newHeader = header.withValidatorPublicKeyHash(key)
      // NOTE: blockHash should be recalculated.
      b.withHeader(newHeader)
    }
    def changeTimestamp(t: Long): Block = {
      val header    = b.getHeader
      val newHeader = header.withTimestamp(t)

      ProtoUtil.unsignedBlockProto(b.getBody, newHeader)
    }
    def changeSigAlgorithm(sigAlgorithm: String): Block =
      b.withSignature(b.getSignature.withSigAlgorithm(sigAlgorithm))
    def changeSig(sig: ByteString): Block =
      b.withSignature(b.getSignature.withSig(sig))

  }

  // Originally validation methods wanted blocks, now they work on summaries.
  implicit def `Block => BlockSummary`(b: Block) =
    BlockSummary(b.blockHash, b.header, b.signature)

  "Block signature validation" should "return false on unknown algorithms" in withCombinedStorage() {
    implicit storage =>
      for {
        blocks           <- createChain[Task](2)
        unknownAlgorithm = "unknownAlgorithm"
        rsa              = "RSA"
        block0           = blocks(0).changeSigAlgorithm(unknownAlgorithm)
        block1           = blocks(1).changeSigAlgorithm(rsa)
        _                <- Validation.blockSignature[Task](block0) shouldBeF false
        _ = log.warns.last
          .contains(s"signature algorithm '$unknownAlgorithm' is unsupported") should be(
          true
        )
        _ <- Validation.blockSignature[Task](block1) shouldBeF false
        result = log.warns.last.contains(s"signature algorithm '$rsa' is unsupported") should be(
          true
        )
      } yield result
  }

  it should "return false on invalid ed25519 signatures" in withCombinedStorage() {
    implicit storage =>
      implicit val (sk, _) = Ed25519.newKeyPair
      for {
        blocks        <- createChain[Task](6)
        (_, wrongPk)  = Ed25519.newKeyPair
        empty         = Keys.PublicKeyHash(ByteString.EMPTY)
        invalidKey    = Keys.PublicKeyHash(ByteString.copyFrom(Base16.decode("abcdef1234567890")))
        wrongKey      = Keys.PublicKeyHash(ByteString.copyFrom(wrongPk))
        block0        = signedBlock(blocks(0)).changeValidator(empty)
        block1        = signedBlock(blocks(1)).changeValidator(invalidKey)
        block2        = signedBlock(blocks(2)).changeValidator(wrongKey)
        block3        = signedBlock(blocks(3)).changeSig(empty)
        block4        = signedBlock(blocks(4)).changeSig(invalidKey)
        block5        = signedBlock(blocks(5)).changeSig(block0.getSignature.sig) //wrong sig
        invalidBlocks = Vector(block0, block1, block2, block3, block4, block5)
        _             <- invalidBlocks.existsM[Task](b => Validation.blockSignature[Task](b)) shouldBeF false
        _             = log.warns.size should be(invalidBlocks.length)
        result        = log.warns.forall(_.contains("signature is invalid")) should be(true)
      } yield result
  }

  it should "return true on valid ed25519 signatures" in withCombinedStorage() { _ =>
    implicit val (sk, pk) = Ed25519.newKeyPair

    val block = ProtoUtil.block(
      Seq.empty,
      ByteString.EMPTY,
      ByteString.EMPTY,
      Seq.empty,
      Seq.empty,
      ProtocolVersion(1),
      Seq.empty,
      1,
      ByteString.EMPTY,
      "casperlabs",
      1,
      Message.asJRank(1),
      Message.asMainRank(1),
      ValidatorIdentity(pk, sk, Ed25519),
      ByteString.EMPTY,
      0,
      false
    )
    Validation.blockSignature[Task](block) shouldBeF true
  }

  "Validator public key hash validation" should "return true when the signature and key matches the hash" in withoutStorage {
    val (sk, pk)  = Ed25519.newKeyPair
    val validator = ValidatorIdentity(pk, sk, Ed25519)
    val sig       = validator.signature("sign this".getBytes())
    val isValid   = Validation.publicKeyHash(sig, validator.publicKeyBS, validator.publicKeyHashBS)
    Task.now(isValid) shouldBeF true
  }

  it should "return false when the hash doesn't match the public key" in withoutStorage {
    val (sk, pk)  = Ed25519.newKeyPair
    val validator = ValidatorIdentity(pk, sk, Ed25519)
    val sig       = validator.signature("sign this".getBytes())
    val isValid = Validation.publicKeyHash(
      sig,
      validator.publicKeyBS,
      Keys.PublicKeyHash(ByteString.copyFrom("wrong hash".getBytes))
    )
    Task.now(isValid) shouldBeF false
  }

  "Deploy signature validation" should "return true for valid signatures" in withoutStorage {
    val deploy = sample(arbitrary[consensus.Deploy])
    Validation.deploySignature[Task](deploy) shouldBeF true
  }

  it should "return false if a key in an approval is empty" in withoutStorage {
    val genDeploy = for {
      d <- arbitrary[consensus.Deploy]
    } yield d.withApprovals(d.approvals.map(x => x.withApproverPublicKey(ByteString.EMPTY)))

    val deploy = sample(genDeploy)
    Validation.deploySignature[Task](deploy) shouldBeF false
  }

  it should "return false for missing signatures" in withoutStorage {
    val genDeploy = for {
      d <- arbitrary[consensus.Deploy]
    } yield d.withApprovals(Nil)

    val deploy = sample(genDeploy)
    Validation.deploySignature[Task](deploy) shouldBeF false
  }

  it should "return false for invalid signatures" in withoutStorage {
    val genDeploy = for {
      d <- arbitrary[consensus.Deploy]
      h <- genHash
    } yield d.withApprovals(d.approvals.map(a => a.withSignature(a.getSignature.withSig(h))))

    val deploy = sample(genDeploy)
    Validation.deploySignature[Task](deploy) shouldBeF false
  }

  it should "return false if there are valid and invalid signatures mixed" in withoutStorage {
    val genDeploy = for {
      d <- arbitrary[consensus.Deploy]
      h <- genHash
    } yield d.withApprovals(
      d.approvals ++ d.approvals.take(1).map(a => a.withSignature(a.getSignature.withSig(h)))
    )

    val deploy = sample(genDeploy)
    Validation.deploySignature[Task](deploy) shouldBeF false
  }

  val deployConfig = DeployConfig(
    maxTtlMillis = 24 * 60 * 60 * 1000, // 1 day
    maxDependencies = 10
  )

  val minTtl = FiniteDuration(1, "hour")

  "Deploy header validation" should "accept valid headers" in {
    implicit val consensusConfig =
      ConsensusConfig(dagSize = 10, maxSessionCodeBytes = 5, maxPaymentCodeBytes = 5)

    forAll { (deploy: consensus.Deploy) =>
      withoutStorage { Validation.deployHeader[Task](deploy, chainName, deployConfig) } shouldBe Nil
    }
  }

  it should "not accept too short time to live" in withoutStorage {
    val deploy = DeployOps.randomTooShortTTL(minTtl)
    Validation.minTtl[Task](deploy, minTtl) shouldBeF Some(
      DeployHeaderError
        .timeToLiveTooShort(
          deploy.deployHash,
          deploy.getHeader.ttlMillis,
          minTtl
        )
    )
  }

  it should "not accept too long time to live" in withoutStorage {
    val deploy = DeployOps.randomTooLongTTL()
    Validation.deployHeader[Task](deploy, chainName, deployConfig) shouldBeF List(
      DeployHeaderError
        .timeToLiveTooLong(
          deploy.deployHash,
          deploy.getHeader.ttlMillis,
          deployConfig.maxTtlMillis
        )
    )
  }

  it should "not accept deploys too far in the future" in withoutStorage {
    val deploy = DeployOps.randomTimstampInFuture()
    Validation.deployHeader[Task](deploy, chainName, deployConfig) shouldBeF List(
      DeployHeaderError
        .timestampInFuture(deploy.deployHash, deploy.getHeader.timestamp, Validation.DRIFT)
    )
  }

  it should "not accept too many dependencies" in withoutStorage {
    val deploy = DeployOps.randomTooManyDependencies()
    Validation.deployHeader[Task](deploy, chainName, deployConfig) shouldBeF List(
      DeployHeaderError.tooManyDependencies(
        deploy.deployHash,
        deploy.getHeader.dependencies.size,
        deployConfig.maxDependencies
      )
    )
  }

  it should "not accept invalid dependencies" in withoutStorage {
    val deploy = DeployOps.randomInvalidDependency()
    Validation.deployHeader[Task](deploy, chainName, deployConfig) shouldBeF List(
      DeployHeaderError
        .invalidDependency(deploy.deployHash, deploy.getHeader.dependencies.head)
    )
  }

  it should "not accept invalid chain names" in withoutStorage {
    val chainName = "nevernet"
    val deploy = sample {
      arbitrary[consensus.Deploy].map(_.withChainName(s"never say $chainName"))
    }

    Validation.deployHeader[Task](deploy, chainName, deployConfig) shouldBeF List(
      DeployHeaderError
        .invalidChainName(deploy.deployHash, deploy.getHeader.chainName, chainName)
    )
  }

  "Timestamp validation" should "not accept blocks with future time" in withCombinedStorage() {
    implicit storage =>
      for {
        blocks                  <- createChain[Task](1)
        block                   = blocks.head
        modifiedTimestampHeader = block.getHeader.withTimestamp(Long.MaxValue)
        _ <- Validation
              .timestamp[Task](
                block.withHeader(modifiedTimestampHeader)
              )
              .attempt shouldBeF Left(InvalidUnslashableBlock)
        _      <- Validation.timestamp[Task](block).attempt shouldBeF Right(())
        _      = log.warns.size should be(1)
        result = log.warns.head.contains("block timestamp") should be(true)
      } yield result
  }

  it should "not accept blocks that were published before parent time" in withCombinedStorage() {
    implicit storage =>
      for {
        blocks                  <- createChain[Task](2)
        block                   = blocks(1)
        modifiedTimestampHeader = block.header.get.withTimestamp(-1)
        _ <- Validation
              .timestamp[Task](
                block.withHeader(modifiedTimestampHeader)
              )
              .attempt shouldBeF Left(InvalidUnslashableBlock)
        _      <- Validation.timestamp[Task](block).attempt shouldBeF Right(())
        _      = log.warns.size should be(1)
        result = log.warns.head.contains("block timestamp") should be(true)
      } yield result
  }

  it should "not accept blocks that were published before justification time" in withCombinedStorage() {
    implicit storage =>
      for {
        blocks <- createChain[Task](
                   3,
                   creator = Keys.PublicKeyHash(ByteString.copyFrom(Array[Byte](1)))
                 )
        genesis = blocks.head
        // Create a new block on top of genesis which will use the previous ones as justifications.
        _ <- createChain[Task](
              1,
              creator = Keys.PublicKeyHash(ByteString.copyFrom(Array[Byte](2))),
              maybeGenesis = Some(genesis)
            )
        block4                  = blocks(3)
        modifiedTimestampHeader = block4.header.get.withTimestamp(genesis.getHeader.timestamp + 1)
        _ <- Validation
              .timestamp[Task](
                block4.withHeader(modifiedTimestampHeader)
              )
              .attempt shouldBeF Left(InvalidUnslashableBlock)
        _      <- Validation.timestamp[Task](block4).attempt shouldBeF Right(())
        _      = log.warns.size should be(1)
        result = log.warns.head.contains("block timestamp") should be(true)
      } yield result
  }

  "Block rank validation" should "only accept 0 as the number for a block with no parents" in withCombinedStorage() {
    implicit storage =>
      for {
        blocks <- createChain[Task](1)
        block  = blocks(0)
        dag    <- storage.getRepresentation
        _ <- Validation.blockRank[Task](block.changeBlockNumber(1), dag).attempt shouldBeF Left(
              InvalidBlockNumber
            )
        _ <- Validation.blockRank[Task](block, dag) shouldBeF Unit
        _ = log.warns.size should be(1)
        result = log.warns.head.contains("not zero, but block has no justifications") should be(
          true
        )
      } yield result
  }

  it should "return true for sequential numbering" in withCombinedStorage() { implicit storage =>
    val n         = 6
    val validator = generateValidator("Validator")
    for {
      blocks <- createChain[Task](n.toInt, bonds = List(Bond(validator, 1)), creator = validator)
      dag    <- storage.getRepresentation
      _      <- blocks.forallM(b => Validation.blockRank[Task](b, dag).attempt.map(_.isRight)) shouldBeF true
      result = log.warns should be(Nil)
    } yield result
  }

  it should "correctly validate a multiparent block where the parents have different block numbers" in withCombinedStorage() {
    implicit storage =>
      def createBlockWithNumber(
          n: Long,
          justificationBlocks: Seq[Block] = Nil
      ): Task[Block] = {
        val blockWithNumber = Block().changeBlockNumber(n)
        val header = blockWithNumber.getHeader
          .withJustifications(
            justificationBlocks.map(
              b => Justification(b.getHeader.validatorPublicKeyHash, b.blockHash)
            )
          )
        val block = ProtoUtil.unsignedBlockProto(blockWithNumber.getBody, header)
        storage.put(block.blockHash, block, Map.empty) *>
          block.pure[Task]
      }
      val validator = generateValidator("Validator")

      for {
        _   <- createChain[Task](8, bonds = List(Bond(validator, 1)), creator = validator) // Note we need to create a useless chain to satisfy the assert in TopoSort
        b1  <- createBlockWithNumber(3)
        b2  <- createBlockWithNumber(7)
        b3  <- createBlockWithNumber(8, Seq(b1, b2))
        dag <- storage.getRepresentation
        _   <- Validation.blockRank[Task](b3, dag) shouldBeF Unit
        result <- Validation.blockRank[Task](b3.changeBlockNumber(4), dag).attempt shouldBeF Left(
                   InvalidBlockNumber
                 )
      } yield result
  }

  "Sequence number validation" should "only accept 0 as the number for a block with no parents" in withCombinedStorage() {
    implicit storage =>
      for {
        blocks <- createChain[Task](1)
        block  = blocks(0)
        _ = assert(
          block.getHeader.justifications.isEmpty,
          "Justification list of Genesis block should be empty."
        )
        dag <- storage.getRepresentation
        _ <- Validation
              .sequenceNumber[Task](
                block.withHeader(block.getHeader.withValidatorBlockSeqNum(1)),
                dag
              )
              .attempt shouldBeF Left(
              InvalidSequenceNumber
            )
        _ <- Validation.sequenceNumber[Task](
              block.withHeader(block.getHeader.withValidatorBlockSeqNum(0)),
              dag
            ) shouldBeF Unit
      } yield ()
  }

  it should "return false for non-sequential numbering" in withCombinedStorage() {
    implicit storage =>
      for {
        blocks <- createChainWithRoundRobinValidators[Task](2, 2)
        block  = blocks(1)
        dag    <- storage.getRepresentation
        _ <- Validation
              .sequenceNumber[Task](
                block.withHeader(block.getHeader.withValidatorBlockSeqNum(2)),
                dag
              )
              .attempt shouldBeF Left(
              InvalidSequenceNumber
            )
        result = log.warns.size should be(1)
      } yield result
  }

  it should "return true for sequential numbering" in withCombinedStorage() { implicit storage =>
    val n              = 20
    val validatorCount = 3
    for {
      blocks <- createChainWithRoundRobinValidators[Task](n, validatorCount)
      _ <- (0 to n).toList.forallM[Task](
            i =>
              for {
                dag   <- storage.getRepresentation
                block = blocks(i)
                _ <- Validation.sequenceNumber[Task](
                      block,
                      dag
                    )
              } yield true
          ) shouldBeF true
      result = log.warns should be(Nil)
    } yield result
  }

  "Previous block hash validation" should "pass if the hash is in the j-past-cone" in withCombinedStorage() {
    implicit storage =>
      val List(v1, v2) = List(1, 2).map(i => generateValidator(s"v$i"))
      for {
        g   <- createAndStoreMessage[Task](Nil)
        b0  <- createAndStoreBlockFull[Task](v1, List(g), Nil)
        b1  <- createAndStoreBlockFull[Task](v2, List(b0), List(b0))
        b2  <- createAndStoreBlockFull[Task](v1, List(b1), List(b1))
        dag <- storage.getRepresentation
        _   <- Validation.validatorPrevBlockHash[Task](b2.getSummary, dag, isHighway = false)
      } yield ()
  }
  it should "pass if the hash is in the justifications" in withCombinedStorage() {
    implicit storage =>
      val v1 = generateValidator("v1")
      for {
        g   <- createAndStoreMessage[Task](Nil)
        b0  <- createAndStoreBlockFull[Task](v1, List(g), Nil)
        b1  <- createAndStoreBlockFull[Task](v1, List(b0), List(b0))
        dag <- storage.getRepresentation
        _   <- Validation.validatorPrevBlockHash[Task](b1.getSummary, dag, isHighway = false)
      } yield ()
  }
  it should "fail if the hash belongs to somebody else" in withCombinedStorage() {
    implicit storage =>
      val List(v1, v2) = List(1, 2).map(i => generateValidator(s"v$i"))
      for {
        g  <- createAndStoreMessage[Task](Nil)
        b0 <- createAndStoreBlockFull[Task](v1, List(g), Nil)
        b1 <- createAndStoreBlockFull[Task](v2, List(b0), List(b0))
        b2 <- createAndStoreBlockFull[Task](
               v1,
               List(b1),
               List(b1, b0),
               maybeValidatorPrevBlockHash = Some(b1.blockHash)
             )
        dag <- storage.getRepresentation
        result <- Validation
                   .validatorPrevBlockHash[Task](b2.getSummary, dag, isHighway = false)
                   .attempt
      } yield {
        result shouldBe Left(ValidateErrorWrapper(InvalidPrevBlockHash))
      }
  }
  it should "fail if the hash is not in the j-past-cone" in withCombinedStorage() {
    implicit storage =>
      val List(v1, v2) = List(1, 2).map(i => generateValidator(s"v$i"))
      for {
        g  <- createAndStoreMessage[Task](Nil)
        b0 <- createAndStoreBlockFull[Task](v1, List(g), Nil)
        b1 <- createAndStoreBlockFull[Task](v1, List(g), Nil)
        b2 <- createAndStoreBlockFull[Task](v2, List(b0), List(b0))
        b3 <- createAndStoreBlockFull[Task](
               v1,
               List(b2),
               List(b2),
               maybeValidatorPrevBlockHash = Some(b1.blockHash)
             )
        dag <- storage.getRepresentation
        result <- Validation
                   .validatorPrevBlockHash[Task](b3.getSummary, dag, isHighway = false)
                   .attempt
      } yield {
        result shouldBe Left(ValidateErrorWrapper(InvalidPrevBlockHash))
      }
  }
  it should "fail if the hash does not exist" in withCombinedStorage() { implicit storage =>
    val v1 = generateValidator("v1")
    val bx = generateHash("non-existent")
    for {
      g <- createAndStoreMessage[Task](Nil)
      b0 <- createAndStoreBlockFull[Task](
             v1,
             List(g),
             Nil,
             maybeValidatorPrevBlockHash = Some(bx),
             maybeValidatorBlockSeqNum = Some(1)
           )
      dag <- storage.getRepresentation
      result <- Validation
                 .validatorPrevBlockHash[Task](b0.getSummary, dag, isHighway = false)
                 .attempt
    } yield {
      result shouldBe Left(ValidateErrorWrapper(InvalidPrevBlockHash))
    }
  }
  it should "not fail if it encounters a message in the parent era" in withCombinedStorage() {
    implicit storage =>
      val v1 = generateValidator("v1")
      // era-0: G = B0 = B1 = b2 = b4
      //                  \\         \
      // era-1:             B3 = = = B5
      for {
        g  <- createAndStoreMessage[Task](Nil)
        b0 <- createAndStoreBlockFull[Task](v1, List(g), Nil)
        b1 <- createAndStoreBlockFull[Task](v1, List(b0), List(b0), keyBlockHash = b0.blockHash)
        // Voting ballot in the parent era (using StoreBlock because it's easier to pass justifications).
        b2 <- createAndStoreBlockFull[Task](
               v1,
               List(b1),
               List(b1),
               keyBlockHash = b0.blockHash
             )
        // A block in the child era.
        b3 <- createAndStoreBlockFull[Task](
               v1,
               List(b1),
               List(b1),
               maybeValidatorPrevBlockHash = Some(ByteString.EMPTY),
               maybeValidatorBlockSeqNum = Some(0),
               keyBlockHash = b1.blockHash
             )
        // Another voting ballot.
        b4 <- createAndStoreBlockFull[Task](
               v1,
               List(b1),
               List(b2),
               keyBlockHash = b0.blockHash
             )
        // Another block that cites the parent era voting ballot.
        b5 <- createAndStoreBlockFull[Task](
               v1,
               List(b3),
               List(b3, b4),
               maybeValidatorPrevBlockHash = Some(b3.blockHash),
               keyBlockHash = b1.blockHash
             )
        dag <- storage.getRepresentation
        _   <- Validation.validatorPrevBlockHash[Task](b3.getSummary, dag, isHighway = true)
        _   <- Validation.validatorPrevBlockHash[Task](b5.getSummary, dag, isHighway = true)
      } yield ()
  }

  "Sender validation" should "return true for genesis and blocks from bonded validators and false otherwise" in withCombinedStorage() {
    implicit storage =>
      val validator = generateValidator("Validator")
      val impostor  = generateValidator("Impostor")
      for {
        blocks       <- createChain[Task](3, List(Bond(validator, 1)))
        validBlock   = blocks(1).changeValidator(validator)
        invalidBlock = blocks(2).changeValidator(impostor)
        _            <- Validation.blockSender[Task](validBlock) shouldBeF true
        result       <- Validation.blockSender[Task](invalidBlock) shouldBeF false
      } yield result
  }

  // Turns sequence of blocks into a mapping between validators and block hashes
  def latestMessages(messages: Seq[Block]): Map[Validator, Set[BlockHash]] =
    messages
      .map(b => Keys.PublicKeyHash(b.getHeader.validatorPublicKeyHash) -> b.blockHash)
      .groupBy(_._1)
      .mapValues(_.map(_._2).toSet)

  def createValidatorBlock[F[_]: MonadThrowable: Time: BlockStorage: DagStorage](
      parents: Seq[Block],
      bonds: Seq[Bond],
      justifications: Seq[Block],
      validator: Validator,
      keyBlock: Block
  ): F[Block] =
    for {
      deploy <- ProtoUtil.basicProcessedDeploy[F]()
      block <- createAndStoreMessageNew[F](
                parents.map(_.blockHash),
                keyBlock.blockHash,
                creator = validator,
                bonds = bonds,
                deploys = Seq(deploy),
                justifications = latestMessages(justifications)
              )
    } yield block

  "Parent validation" should "return true for proper justifications and false otherwise" in withCombinedStorage() {
    implicit storage =>
      val v0 = generateValidator("V1")
      val v1 = generateValidator("V2")
      val v2 = generateValidator("V3")

      val bonds = Seq(v0, v1, v2).zipWithIndex.map {
        case (v, i) => Bond(v, 2 * i + 1)
      }

      for {
        b0 <- createAndStoreMessage[Task](Seq.empty, bonds = bonds)
        b1 <- createValidatorBlock[Task](Seq(b0), bonds, Seq(b0), v0, b0)
        b2 <- createValidatorBlock[Task](Seq(b0), bonds, Seq(b0), v1, b0)
        b3 <- createValidatorBlock[Task](Seq(b0), bonds, Seq(b0), v2, b0)
        b4 <- createValidatorBlock[Task](Seq(b1), bonds, Seq(b1), v0, b0)
        b5 <- createValidatorBlock[Task](Seq(b3, b2, b1), bonds, Seq(b1, b2, b3), v1, b0)
        b6 <- createValidatorBlock[Task](Seq(b5, b4), bonds, Seq(b1, b4, b5), v0, b0)
        b7 <- createValidatorBlock[Task](Seq(b4), bonds, Seq(b1, b4, b5), v1, b0) //not highest score parent
        b8 <- createValidatorBlock[Task](Seq(b1, b2, b3), bonds, Seq(b1, b2, b3), v2, b0) //parents wrong order
        b9 <- createValidatorBlock[Task](Seq(b6), bonds, Seq.empty, v0, b0)
               .map(b => b.withHeader(b.getHeader.withJustifications(Seq.empty))) //empty justification
        // Set obviously incorrect parent. Later we want to test that validation raises `InvalidParent` error.
        b10 <- createValidatorBlock[Task](Seq(b0), bonds, Seq(b9), v0, b0)
        result <- for {
                   dag <- storage.getRepresentation
                   // Valid
                   _ <- Validation[Task].parents(
                         b1,
                         dag
                       )
                   _ <- Validation[Task].parents(
                         b2,
                         dag
                       )
                   _ <- Validation[Task].parents(
                         b3,
                         dag
                       )
                   _ <- Validation[Task].parents(
                         b4,
                         dag
                       )
                   _ <- Validation[Task].parents(
                         b5,
                         dag
                       )
                   _ <- Validation[Task].parents(
                         b6,
                         dag
                       )

                   // Not valid
                   _ <- Validation[Task]
                         .parents(b7, dag)
                         .attempt
                         .map(_ shouldBe 'left)
                   _ <- Validation[Task]
                         .parents(b8, dag)
                         .attempt
                         .map(_ shouldBe 'left)
                   _ <- Validation[Task]
                         .parents(b9, dag)
                         .attempt
                         .map(_ shouldBe 'left)

                   _ = log.warns should have size 3
                   _ = log.warns.forall(
                     _.matches(
                       ".* block parents .* did not match estimate .* based on justification .*"
                     )
                   ) should be(
                     true
                   )

                   result <- Validation[Task]
                              .parents(b10, dag)
                              .attempt shouldBeF Left(ValidateErrorWrapper(InvalidParents))

                 } yield result
      } yield result
  }

  // See [[/resources/casper/localDetectedForeignDidnt.jpg]]
  it should "use only j-past-cone of the block when detecting equivocators" in withCombinedStorage() {
    implicit storage =>
      val v0 = generateValidator("v0")
      val v1 = generateValidator("v1")
      val v2 = generateValidator("v2")
      val v3 = generateValidator("v3")

      val bondsMap = Map(
        v0 -> 2,
        v1 -> 3,
        v2 -> 5,
        v3 -> 2
      )

      val bonds = bondsMap.map(b => Bond(b._1, b._2)).toSeq

      for {
        genesis <- createAndStoreMessage[Task](Seq.empty, bonds = bonds)
        a       <- createValidatorBlock[Task](Seq(genesis), bonds, Seq(genesis), v1, genesis)
        b       <- createValidatorBlock[Task](Seq(genesis), bonds, Seq(genesis), v2, genesis)
        c       <- createValidatorBlock[Task](Seq(genesis), bonds, Seq(genesis), v2, genesis)
        d       <- createValidatorBlock[Task](Seq(c, a), bonds, Seq(a, c), v3, genesis)
        e       <- createValidatorBlock[Task](Seq(a), bonds, Seq(a, b, c), v3, genesis)
        dag     <- storage.getRepresentation
        // v3 hasn't seen v2 equivocating (in contrast to what "local" node saw).
        // It will choose C as a main parent and A as a secondary one.
        _ <- Validation[Task]
              .parents(d, dag)
              .map(_.parents.map(_.blockHash)) shouldBeF Vector(c.blockHash, a.blockHash)
        // While v0 has seen everything so it will use 0 as v2's weight when scoring.
        _ <- Validation[Task]
              .parents(e, dag)
              .map(_.parents.map(_.blockHash)) shouldBeF e.getHeader.parentHashes.toVector
      } yield ()
  }

  // Creates a block with an invalid block number and sequence number
  "Block validation" should "short circuit after first invalidity" in withCombinedStorage() {
    implicit storage =>
      for {
        blocks   <- createChain[Task](2)
        block    = blocks(1)
        dag      <- storage.getRepresentation
        (sk, pk) = Ed25519.newKeyPair
        signedBlock = ProtoUtil.signBlock(
          block.changeBlockNumber(17).changeSeqNum(1),
          sk,
          Ed25519
        )
        result <- Validation[Task]
                   .blockFull(
                     signedBlock,
                     dag,
                     "casperlabs",
                     Block.defaultInstance.some
                   )
                   .attempt
        _ = result shouldBe Left(
          DropErrorWrapper(InvalidUnslashableBlock)
        )

      } yield ()
  }

  "Bonds cache validation" should "succeed on a valid block and fail on modified bonds" in withCombinedStorage() {
    implicit storage =>
      val validators = (1 to 4).map(_ => Ed25519.newKeyPair).unzip._2.map { pk =>
        Keys.PublicKeyHash(ByteString.copyFrom(Ed25519.publicKeyHash(pk)))
      }
      val bonds                                   = HashSetCasperTest.createBonds(validators)
      val BlockMsgWithTransform(Some(genesis), _) = HashSetCasperTest.createGenesis(bonds)
      val genesisBonds                            = ProtoUtil.bonds(genesis)
      implicit val casperSmartContractsApi        = ExecutionEngineServiceStub.noOpApi[Task]()
      implicit val log                            = LogStub[Task]()
      for {
        dag <- storage.getRepresentation
        _ <- ExecutionEngineServiceStub
              .validateBlockCheckpoint[Task](
                genesis,
                dag
              )
        _                 <- Validation.bondsCache[Task](genesis, genesisBonds) shouldBeF Unit
        modifiedBonds     = Seq.empty[Bond]
        modifiedPostState = genesis.getHeader.getState.withBonds(modifiedBonds)
        modifiedHeader    = genesis.getHeader.withState(modifiedPostState)
        modifiedGenesis   = genesis.withHeader(modifiedHeader)
        result <- Validation.bondsCache[Task](modifiedGenesis, genesisBonds).attempt shouldBeF Left(
                   InvalidBondsCache
                 )
      } yield result
  }

  "Field format validation" should "succeed on a valid block and fail on empty fields" in withCombinedStorage() {
    _ =>
      implicit val log = LogStub[Task]()
      val (sk, pk)     = Ed25519.newKeyPair
      val validator    = toPublicKeyHash(pk)
      val BlockMsgWithTransform(Some(block), _) =
        HashSetCasperTest.createGenesis(Map(validator -> 1))
      val genesis = ProtoUtil.signBlock(block, sk, Ed25519)

      for {
        _ <- Validation.formatOfFields[Task](genesis) shouldBeF true
        _ <- Validation.formatOfFields[Task](genesis.withBlockHash(ByteString.EMPTY)) shouldBeF false
        _ <- Validation.formatOfFields[Task](genesis.clearHeader) shouldBeF false
        _ <- Validation.formatOfFields[Task](genesis.clearBody) shouldBeF true // Body is only checked in `Validate.blockFull`
        _ <- Validation.formatOfFields[Task](
              genesis.withSignature(genesis.getSignature.withSig(ByteString.EMPTY))
            ) shouldBeF false
        _ <- Validation.formatOfFields[Task](
              genesis.withSignature(genesis.getSignature.withSigAlgorithm(""))
            ) shouldBeF false
        _ <- Validation.formatOfFields[Task](
              genesis.withHeader(genesis.getHeader.withChainName(""))
            ) shouldBeF false
        _ <- Validation.formatOfFields[Task](genesis.withHeader(genesis.getHeader.clearState)) shouldBeF false
        _ <- Validation.formatOfFields[Task](
              genesis.withHeader(
                genesis.getHeader
                  .withState(genesis.getHeader.getState.withPostStateHash(ByteString.EMPTY))
              )
            ) shouldBeF false
        _ <- Validation.formatOfFields[Task](
              genesis.withHeader(genesis.getHeader.withBodyHash(ByteString.EMPTY))
            ) shouldBeF false
      } yield ()
  }

  "Deploy hash validation" should "return false for invalid hashes" in {
    val genDeploy = for {
      d <- arbitrary[consensus.Deploy]
      h <- genHash
    } yield d.withDeployHash(h)

    val deploy = sample(genDeploy)
    Validation.deployHash[Task](deploy) shouldBeF false
  }

  it should "return true for valid hashes" in {
    val deploy = sample(arbitrary[consensus.Deploy])
    Validation.deployHash[Task](deploy) shouldBeF true
  }

  "Processed deploy validation" should "fail a block with a deploy having an invalid hash" in withCombinedStorage() {
    _ =>
      val block = sample {
        for {
          b <- arbitrary[consensus.Block]
          h <- genHash
        } yield b.withBody(
          b.getBody.withDeploys(
            b.getBody.deploys.take(1).map(x => x.withDeploy(x.getDeploy.withDeployHash(h))) ++
              b.getBody.deploys.tail
          )
        )
      }
      for {
        result <- Validation.deployHashes[Task](block).attempt
        _      = result shouldBe Left(ValidateErrorWrapper(InvalidDeployHash))
      } yield ()
  }

  it should "fail a block with a deploy having no signature" in withCombinedStorage() { _ =>
    val block = sample {
      for {
        b <- arbitrary[consensus.Block]
      } yield b.withBody(
        b.getBody.withDeploys(
          b.getBody.deploys
            .take(1)
            .map(x => x.withDeploy(x.getDeploy.withApprovals(Seq.empty))) ++
            b.getBody.deploys.tail
        )
      )
    }
    for {
      result <- Validation.deploySignatures[Task](block).attempt
      _      = result shouldBe Left(ValidateErrorWrapper(InvalidDeploySignature))
    } yield ()
  }

  it should "fail a block with a deploy having an invalid signature" in withCombinedStorage() { _ =>
    val block = sample {
      for {
        b <- arbitrary[consensus.Block]
        h <- genHash
      } yield b.withBody(
        b.getBody.withDeploys(
          b.getBody.deploys
            .take(1)
            .map(
              x =>
                x.withDeploy(
                  x.getDeploy.withApprovals(
                    x.getDeploy.approvals.map(a => a.withSignature(a.getSignature.withSig(h)))
                  )
                )
            ) ++
            b.getBody.deploys.tail
        )
      )
    }
    for {
      result <- Validation.deploySignatures[Task](block).attempt
      _      = result shouldBe Left(ValidateErrorWrapper(InvalidDeploySignature))
    } yield ()
  }

  it should "fail a block with a deploy having an foreign chain name" in withCombinedStorage() {
    implicit storage =>
      val block = sample {
        arbitrary[consensus.Block] map { block =>
          block.update {
            _.body.deploys := block.getBody.deploys.map { pd =>
              pd.withDeploy(pd.getDeploy.withChainName("la la land"))
            }
          }
        }
      }
      for {
        dag <- storage.getRepresentation
        result <- Validation
                   .deployHeaders[Task](
                     block,
                     dag,
                     chainName = "no country for old men"
                   )
                   .attempt
      } yield {
        result shouldBe Left(ValidateErrorWrapper(InvalidDeployHeader))
      }
  }

  it should "pass a block with a deploy having no chain name" in withCombinedStorage() {
    implicit storage =>
      val block = sample {
        arbitrary[consensus.Block] map { b =>
          b.update(
            _.body.deploys :=
              b.getBody.deploys.map { pd =>
                pd.update(
                  _.deploy.header :=
                    pd.getDeploy.getHeader
                      .withChainName("")
                      .clearDependencies
                )
              }
          )
        }
      }
      for {
        dag <- storage.getRepresentation
        _   <- Validation.deployHeaders[Task](block, dag, chainName = "area 51")
      } yield ()
  }

  "Block hash format validation" should "fail on invalid hash" in withCombinedStorage() { _ =>
    val (sk, pk)  = Ed25519.newKeyPair
    val validator = toPublicKeyHash(pk)
    val BlockMsgWithTransform(Some(block), _) =
      HashSetCasperTest.createGenesis(Map(validator -> 1))
    val signedBlock = ProtoUtil.signBlock(block, sk, Ed25519)
    for {
      _ <- Validation.blockHash[Task](signedBlock) shouldBeF Unit
      result <- Validation
                 .blockHash[Task](
                   signedBlock.withBlockHash(ByteString.copyFromUtf8("123"))
                 )
                 .attempt shouldBeF Left(InvalidBlockHash)
    } yield result
  }

  "Block deploy count validation" should "fail on invalid number of deploys" in withCombinedStorage() {
    _ =>
      val (sk, pk)  = Ed25519.newKeyPair
      val validator = toPublicKeyHash(pk)
      val BlockMsgWithTransform(Some(block), _) =
        HashSetCasperTest.createGenesis(Map(validator -> 1))
      val signedBlock = ProtoUtil.signBlock(block, sk, Ed25519)
      for {
        _ <- Validation.deployCount[Task](signedBlock) shouldBeF Unit
        result <- Validation
                   .deployCount[Task](
                     signedBlock.withHeader(signedBlock.header.get.withDeployCount(100))
                   )
                   .attempt shouldBeF Left(InvalidDeployCount)
      } yield result
  }

  "Block version validation" should "work" in withCombinedStorage() { _ =>
    val (sk, pk)                              = Ed25519.newKeyPair
    val validator                             = toPublicKeyHash(pk)
    val BlockMsgWithTransform(Some(block), _) = HashSetCasperTest.createGenesis(Map(validator -> 1))
    // Genesis' block version is 1.  `missingProtocolVersionForBlock` will fail ProtocolVersion lookup
    // while `protocolVersionForGenesisBlock` returns proper one (version=1)

    val missingProtocolVersionForBlock: Long => Task[ProtocolVersion] =
      _ => Task.now(ProtocolVersion(-1))
    val protocolVersionForGenesisBlock: Long => Task[ProtocolVersion] =
      _ => Task.now(ProtocolVersion(1))

    val signedBlock = ProtoUtil.signBlock(block, sk, Ed25519)

    for {
      _ <- Validation.version[Task](
            signedBlock,
            missingProtocolVersionForBlock
          ) shouldBeF false
      result <- Validation.version[Task](
                 signedBlock,
                 protocolVersionForGenesisBlock
               ) shouldBeF true
    } yield result
  }

  "validateTransactions" should "return InvalidPreStateHash when preStateHash of block is not correct" in withCombinedStorage() {
    implicit storage =>
      implicit val executionEngineService: ExecutionEngineService[Task] =
        HashSetCasperTestNode.simpleEEApi[Task](Map.empty)
      val contract = ByteString.copyFromUtf8("some contract")

      val genesisDeploysWithCost = prepareDeploys(Vector.empty, 1)
      val b1DeploysWithCost      = prepareDeploys(Vector(contract), 2)
      val b2DeploysWithCost      = prepareDeploys(Vector(contract), 1)
      val b3DeploysWithCost      = prepareDeploys(Vector.empty, 5)
      val invalidHash            = ByteString.copyFromUtf8("invalid")

      for {
        genesis <- createAndStoreMessage[Task](Seq.empty, deploys = genesisDeploysWithCost)
        b1      <- createAndStoreMessage[Task](Seq(genesis.blockHash), deploys = b1DeploysWithCost)
        b2      <- createAndStoreMessage[Task](Seq(genesis.blockHash), deploys = b2DeploysWithCost)
        // set wrong preStateHash for b3
        b3 <- createAndStoreMessage[Task](
               Seq(b1.blockHash, b2.blockHash),
               deploys = b3DeploysWithCost,
               preStateHash = invalidHash
             )
        dag <- storage.getRepresentation

        // calls Validate.transactions internally
        postState <- ExecutionEngineServiceStub.validateBlockCheckpoint[Task](
                      b3,
                      dag
                    )
      } yield postState shouldBe Left(ValidateErrorWrapper(InvalidPreStateHash))
  }

  private def shouldBeInvalidDeployHeader(deploy: consensus.Deploy) = withCombinedStorage() {
    implicit storage =>
      val deploysWithCost = Vector(deploy.processed(1))
      for {
        block <- createMessage[Task](
                  Seq.empty,
                  deploys = deploysWithCost
                )
        dag    <- storage.getRepresentation
        result <- Validation.deployHeaders[Task](block, dag, chainName).attempt
      } yield result shouldBe Left(ValidateErrorWrapper(InvalidDeployHeader))
  }

  it should "return InvalidDeployHeader when a deploy has too long a TTL" in {
    shouldBeInvalidDeployHeader(DeployOps.randomTooLongTTL())
  }

  it should "return InvalidDeployHeader when a deploy has too many dependencies" in {
    shouldBeInvalidDeployHeader(DeployOps.randomTooManyDependencies())
  }

  it should "return InvalidDeployHeader when a deploy has invalid dependencies" in {
    shouldBeInvalidDeployHeader(DeployOps.randomInvalidDependency())
  }

  it should "return DeployFromFuture when a deploy timestamp is later than the block timestamp" in withCombinedStorage() {
    implicit storage =>
      val deploy         = DeployOps.randomNonzeroTTL()
      val blockTimestamp = deploy.getHeader.timestamp - 1
      for {
        block <- createMessage[Task](Seq.empty, deploys = Vector(deploy.processed(1)))
                  .map(_.changeTimestamp(blockTimestamp))
        _      <- storage.put(block.blockHash, block, Map.empty)
        dag    <- storage.getRepresentation
        result <- Validation.deployHeaders[Task](block, dag, chainName).attempt
      } yield result shouldBe Left(ValidateErrorWrapper(DeployFromFuture))
  }

  it should "return DeployExpired when a deploy is past its TTL" in withCombinedStorage() {
    implicit storage =>
      val deploy         = DeployOps.randomNonzeroTTL()
      val blockTimestamp = deploy.getHeader.timestamp + deploy.getHeader.ttlMillis + 1
      for {
        block <- createMessage[Task](Seq.empty, deploys = Vector(deploy.processed(1)))
                  .map(_.changeTimestamp(blockTimestamp))
        _      <- storage.put(block.blockHash, block, Map.empty)
        dag    <- storage.getRepresentation
        result <- Validation.deployHeaders[Task](block, dag, chainName).attempt
      } yield result shouldBe Left(ValidateErrorWrapper(DeployExpired))
  }

  it should "return DeployDependencyNotMet when a deploy has a dependency not in the p-past cone" in withCombinedStorage() {
    implicit storage =>
      val deployA        = DeployOps.randomNonzeroTTL()
      val deployB        = deployA.withDependencies(List(deployA.deployHash))
      val blockTimestamp = deployB.getHeader.timestamp + deployB.getHeader.ttlMillis - 1
      for {
        block <- createMessage[Task](Seq.empty, deploys = Vector(deployB.processed(1)))
                  .map(_.changeTimestamp(blockTimestamp))
        _      <- storage.put(block.blockHash, block, Map.empty)
        dag    <- storage.getRepresentation
        result <- Validation.deployHeaders[Task](block, dag, chainName).attempt
      } yield result shouldBe Left(ValidateErrorWrapper(DeployDependencyNotMet))
  }

  it should "work for valid deploys" in withCombinedStorage() { implicit storage =>
    // The last validation would fail if the deploy timestamp was in the future,
    // so pretend that all these blocks with their deploys happened a week ago.
    val timestamp = System.currentTimeMillis - 7 * 24 * 60 * 60 * 1000
    val deployA   = DeployOps.randomNonzeroTTL().withTimestamp(timestamp)
    val deployB = DeployOps
      .randomNonzeroTTL()
      .withTimestamp(deployA.getHeader.timestamp + deployA.getHeader.ttlMillis)
    val deployC = DeployOps
      .randomNonzeroTTL()
      .withDependencies(List(deployA.deployHash, deployB.deployHash))
      .withTimestamp(deployB.getHeader.timestamp + deployB.getHeader.ttlMillis)

    val timeA = deployA.getHeader.timestamp + deployA.getHeader.ttlMillis - 1
    val timeB = deployB.getHeader.timestamp + deployB.getHeader.ttlMillis - 1
    val timeC = deployC.getHeader.timestamp + deployC.getHeader.ttlMillis - 1

    for {
      blockA <- createMessage[Task](Seq.empty, deploys = Vector(deployA.processed(1)))
                 .map(_.changeTimestamp(timeA))
      _ <- storage.put(blockA.blockHash, blockA, Map.empty)
      blockB <- createMessage[Task](
                 List(blockA.blockHash),
                 deploys = Vector(deployB.processed(1))
               ).map(_.changeTimestamp(timeB))
      _ <- storage.put(blockB.blockHash, blockB, Map.empty)
      blockC <- createMessage[Task](
                 List(blockB.blockHash),
                 deploys = Vector(deployC.processed(1))
               ).map(_.changeTimestamp(timeC))
      _      <- storage.put(blockC.blockHash, blockC, Map.empty)
      dag    <- storage.getRepresentation
      result <- Validation.deployHeaders[Task](blockC, dag, chainName).attempt
    } yield result shouldBe Right(())
  }

  "deployUniqueness" should "return InvalidRepeatDeploy when a deploy is present in an ancestor" in withCombinedStorage() {
    implicit storage =>
      val contract        = ByteString.copyFromUtf8("some contract")
      val deploysWithCost = prepareDeploys(Vector(contract), 1)
      for {
        genesis <- createAndStoreMessage[Task](Seq.empty, deploys = deploysWithCost)
        block   <- createAndStoreMessage[Task](Seq(genesis.blockHash), deploys = deploysWithCost)
        dag     <- storage.getRepresentation
        result  <- Validation.deployUniqueness[Task](block, dag).attempt
      } yield result shouldBe Left(ValidateErrorWrapper(InvalidRepeatDeploy))
  }

  it should "return InvalidRepeatDeploy when a deploy is present in the body twice" in withCombinedStorage() {
    implicit storage =>
      val contract        = ByteString.copyFromUtf8("some contract")
      val deploysWithCost = prepareDeploys(Vector(contract), 1)
      for {
        genesis <- createAndStoreMessage[Task](
                    Seq.empty,
                    deploys = deploysWithCost ++ deploysWithCost
                  )
        dag    <- storage.getRepresentation
        result <- Validation.deployUniqueness[Task](genesis, dag).attempt
      } yield result shouldBe Left(ValidateErrorWrapper(InvalidRepeatDeploy))
  }

  "totalCost" should "return TooExpensive when the cost is above the maximum" in withCombinedStorage() {
    implicit storage =>
      val maxCost = 5L
      implicit val versions =
        CasperLabsProtocol.unsafe[Task](
          (
            0L,
            state.ProtocolVersion(1),
            Some(
              DeployConfig(24 * 60 * 60 * 1000, 10, 10 * 1024 * 1024, maxCost)
            )
          )
        )
      val contract = ByteString.copyFromUtf8("some contract")
      val deploysWithCost =
        Vector.fill(2)(prepareDeploys(Vector(contract), cost = maxCost - 1)).flatten
      for {
        block <- createAndStoreMessage[Task](
                  Seq.empty,
                  deploys = deploysWithCost
                )
        result <- Validation.totalCost[Task](block).attempt
      } yield result shouldBe Left(ValidateErrorWrapper(TooExpensive))
  }

  "Validation" should "return InvalidPostStateHash when postStateHash of block is not correct" in withCombinedStorage() {
    implicit storage =>
      implicit val executionEngineService: ExecutionEngineService[Task] =
        HashSetCasperTestNode.simpleEEApi[Task](Map.empty)
      val deploys          = Vector(ProtoUtil.deploy(System.currentTimeMillis, ByteString.EMPTY))
      val processedDeploys = deploys.map(d => Block.ProcessedDeploy().withDeploy(d).withCost(1))
      val invalidHash      = ByteString.copyFromUtf8("invalid")
      for {
        genesis <- createAndStoreMessage[Task](
                    Seq.empty,
                    deploys = processedDeploys,
                    postStateHash = invalidHash
                  )
        dag <- storage.getRepresentation
        // calls Validate.transactions internally
        validateResult <- ExecutionEngineServiceStub.validateBlockCheckpoint[Task](
                           genesis,
                           dag
                         )
      } yield {
        validateResult match {
          case Left(ValidateErrorWrapper(InvalidPostStateHash)) =>
          case Left(ValidateErrorWrapper(other)) =>
            fail(s"Expected InvalidPostStateHash, got $other")
          case other => fail(s"Unexpected result: $other")
        }
      }
  }

  it should "return a checkpoint with the right hash for a valid block" in withCombinedStorage() {
    implicit val executionEngineService: ExecutionEngineService[Task] =
      HashSetCasperTestNode.simpleEEApi[Task](Map.empty)
    implicit storage =>
      val deploys =
        Vector(ProtoUtil.deploy(System.currentTimeMillis, ByteString.EMPTY))
      implicit val deploySelection: DeploySelection[Task] = DeploySelection.create[Task]()

      def implicitFs2Compiler(implicit ev: Fs2Compiler[Task]) = ev

      implicit val deployBuffer = DeployBuffer.create[Task]("casperlabs", Duration.Zero)(
        MonadThrowable[Task],
        implicitFs2Compiler,
        log,
        metrics,
        storage,
        storage,
        storage,
        storage,
        DeployEventEmitter[Task]
      )

      for {
        _ <- storage.writer.addAsPending(deploys.toList)
        deploysCheckpoint <- ExecEngineUtil.computeDeploysCheckpoint[Task](
                              ExecEngineUtil.MergeResult.empty,
                              fs2.Stream.fromIterator[Task](deploys.toIterator),
                              System.currentTimeMillis,
                              ProtocolVersion(1),
                              mainRank = 0,
                              maxBlockSizeBytes = 5 * 1024 * 1024,
                              maxBlockCost = 0,
                              upgrades = Nil
                            )
        DeploysCheckpoint(
          preStateHash,
          computedPostStateHash,
          bondedValidators,
          processedDeploys,
          _,
          _
        ) = deploysCheckpoint
        block <- createAndStoreMessage[Task](
                  Seq.empty,
                  deploys = processedDeploys,
                  postStateHash = computedPostStateHash,
                  preStateHash = preStateHash,
                  bonds = bondedValidators
                )
        dag2 <- storage.getRepresentation

        // calls Validate.transactions internally
        validateResult <- ExecutionEngineServiceStub.validateBlockCheckpoint[Task](
                           block,
                           dag2
                         )
        Right(postStateHash) = validateResult
      } yield postStateHash should be(computedPostStateHash)
  }

  "swimlane validation" should "not allow merging equivocator's swimlane" in withCombinedStorage() {
    implicit storage =>
      val v0 = generateValidator("v0")
      val v1 = generateValidator("v1")

      val bondsMap = Map(
        v0 -> 2,
        v1 -> 3
      )

      val bonds = bondsMap.map(b => Bond(b._1, b._2)).toSeq

      for {
        genesis <- createAndStoreMessage[Task](Seq.empty, bonds = bonds)
        a       <- createValidatorBlock[Task](Seq(genesis), bonds, Seq.empty, v0, genesis)
        b       <- createValidatorBlock[Task](Seq(genesis), bonds, Seq.empty, v0, genesis)
        c       <- createValidatorBlock[Task](Seq(genesis), bonds, Seq(a), v1, genesis)
        d       <- createValidatorBlock[Task](Seq(b), bonds, Seq(c), v0, genesis)
        dag     <- storage.getRepresentation
        _ <- Validation.swimlane[Task](d, dag, isHighway = false).attempt shouldBeF Left(
              ValidateErrorWrapper(SwimlaneMerged)
            )
      } yield ()
  }

  it should "not raise errors when j-past-cone does not merge a swmilane" in withCombinedStorage() {
    implicit storage =>
      val v0 = generateValidator("v0")
      val v1 = generateValidator("v1")

      val bondsMap = Map(
        v0 -> 2,
        v1 -> 3
      )

      val bonds = bondsMap.map(b => Bond(b._1, b._2)).toSeq

      for {
        genesis <- createAndStoreMessage[Task](Seq.empty, bonds = bonds)
        a       <- createValidatorBlock[Task](Seq(genesis), bonds, Seq.empty, v0, genesis)
        _       <- createValidatorBlock[Task](Seq(genesis), bonds, Seq.empty, v0, genesis)
        c       <- createValidatorBlock[Task](Seq(genesis), bonds, Seq(a), v1, genesis)
        d       <- createValidatorBlock[Task](Seq(a), bonds, Seq(c), v0, genesis)
        dag     <- storage.getRepresentation
        _       <- Validation.swimlane[Task](d, dag, isHighway = false).attempt shouldBeF Right(())
      } yield ()
  }

  it should "not raise when the j-past-cone contain blocks and ballots across eras" in withCombinedStorage() {
    implicit db =>
      val v1 = generateValidator("v1")
      // era-0: G - B0 - B1 - B2
      //                   \
      // era-1:             B3
      for {
        g  <- createAndStoreMessage[Task](Nil)
        eg <- createAndStoreEra[Task](g.blockHash)
        b0 <- createAndStoreBlockFull[Task](v1, List(g), Nil, keyBlockHash = eg.keyBlockHash)
        e0 <- createAndStoreEra[Task](b0.blockHash)
        b1 <- createAndStoreBlockFull[Task](v1, List(b0), List(b0), keyBlockHash = e0.keyBlockHash)
        b2 <- createAndStoreBlockFull[Task](v1, List(b1), List(b1), keyBlockHash = e0.keyBlockHash)
        e1 <- createAndStoreEra[Task](b1.blockHash)
        b3 <- createAndStoreBlockFull[Task](
               v1,
               List(b1),
               List(b1, b2),
               maybeValidatorPrevBlockHash = Some(ByteString.EMPTY),
               maybeValidatorBlockSeqNum = Some(0),
               keyBlockHash = e1.keyBlockHash
             )
        dag <- db.getRepresentation
        _   <- Validation.swimlane[Task](b3, dag, isHighway = true).attempt shouldBeF Right(())
      } yield ()
  }

  it should "raise when the j-past-cone contains an equivocation in an era" in withCombinedStorage() {
    implicit db =>
      val v1 = generateValidator("v1")
      // era-0: G - B0 - B1 - B2
      //                   \    \
      //                    B3   \
      //                      \   \
      // era-1:                B4 - B5
      for {
        g  <- createAndStoreMessage[Task](Nil)
        eg <- createAndStoreEra[Task](g.blockHash)
        b0 <- createAndStoreBlockFull[Task](v1, List(g), Nil, keyBlockHash = eg.keyBlockHash)
        e0 <- createAndStoreEra[Task](b0.blockHash)
        b1 <- createAndStoreBlockFull[Task](v1, List(b0), List(b0), keyBlockHash = e0.keyBlockHash)
        b2 <- createAndStoreBlockFull[Task](v1, List(b1), List(b1), keyBlockHash = e0.keyBlockHash)
        b3 <- createAndStoreBlockFull[Task](v1, List(b1), List(b1), keyBlockHash = e0.keyBlockHash)
        e1 <- createAndStoreEra[Task](b1.blockHash)
        b4 <- createAndStoreBlockFull[Task](
               v1,
               List(b3),
               List(b3),
               maybeValidatorPrevBlockHash = Some(ByteString.EMPTY),
               maybeValidatorBlockSeqNum = Some(0),
               keyBlockHash = e1.keyBlockHash
             )
        b5 <- createAndStoreBlockFull[Task](
               v1,
               List(b4),
               List(b4, b2),
               keyBlockHash = e1.keyBlockHash
             )
        dag <- db.getRepresentation
        _ <- Validation.swimlane[Task](b5, dag, isHighway = true).attempt shouldBeF Left(
              ValidateErrorWrapper(SwimlaneMerged)
            )
      } yield ()
  }

  // TODO: Bring back once there is an easy way to create a _valid_ block.
  ignore should "return InvalidTargetHash for a message of type ballot that has invalid number of parents" in withCombinedStorage() {
    implicit storage =>
      import io.casperlabs.models.BlockImplicits._
      val chainName = "test"
      for {
        blockA <- createMessage[Task](
                   parentsHashList = Seq.empty,
                   messageType = MessageType.BALLOT,
                   chainName = chainName
                 )
        blockB <- createMessage[Task](
                   parentsHashList =
                     Seq(ByteString.EMPTY, ByteString.copyFrom(Array.ofDim[Byte](32))),
                   messageType = MessageType.BALLOT,
                   chainName = chainName
                 )
        _ <- Validation[Task]
              .blockSummary(BlockSummary.fromBlock(blockA), chainName)
              .attempt shouldBeF Left(
              ValidateErrorWrapper(InvalidTargetHash)
            )
        _ <- Validation[Task]
              .blockSummary(BlockSummary.fromBlock(blockB), chainName)
              .attempt shouldBeF Left(
              ValidateErrorWrapper(InvalidTargetHash)
            )
      } yield ()
  }

}
