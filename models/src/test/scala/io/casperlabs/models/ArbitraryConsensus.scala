package io.casperlabs.models

import cats._
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus._
import io.casperlabs.models.BlockImplicits._
import io.casperlabs.crypto.Keys.{PrivateKey, PublicKey}
import io.casperlabs.crypto.hash.Blake2b256
import io.casperlabs.crypto.signatures.SignatureAlgorithm.Ed25519
import io.casperlabs.shared.Sorting.jRankOrdering
import org.scalacheck.{Arbitrary, Gen, Shrink}

import scala.collection.JavaConverters._
import io.casperlabs.casper.consensus.state.ProtocolVersion
import io.casperlabs.crypto.Keys

trait ArbitraryConsensus {
  import Arbitrary.arbitrary

  /**
    * Needed for traversing collections like {{{(xs: List[Int]).traverse(_ => Gen.choose(1, 10))}}}
    */
  implicit val applicativeGen: Applicative[Gen] = new Applicative[Gen] {
    override def pure[A](x: A): Gen[A] = Gen.const(x)

    override def ap[A, B](ff: Gen[A => B])(fa: Gen[A]): Gen[B] =
      fa.flatMap(a => ff.map(f => f(a)))
  }

  case class ConsensusConfig(
      // Number of blocks in the DAG. 0 means no limit.
      dagSize: Int = 0,
      // Max depth of DAG. 0 means no limit.
      dagDepth: Int = 0,
      // Number of parents in each generation.
      dagWidth: Int = 1,
      // How much parents should have each children.
      dagBranchingFactor: Int = 5,
      // Maximum size of code in blocks. Slow to generate.
      maxSessionCodeBytes: Int = 500 * 1024,
      maxPaymentCodeBytes: Int = 100 * 1024,
      minSessionCodeBytes: Int = 0,
      minPaymentCodeBytes: Int = 0
  )

  def genBytes(length: Int): Gen[ByteString] =
    Gen.listOfN(length, arbitrary[Byte]).map { bytes =>
      ByteString.copyFrom(bytes.toArray)
    }

  // A generators .sample.get can sometimes return None, but these examples have no reason to not generate a result,
  // so defend against that and retry if it does happen.
  def sample[T](g: Gen[T]): T = {
    def loop(i: Int): T = {
      assert(i > 0, "Should be able to generate a sample.")
      g.sample.fold(loop(i - 1))(identity)
    }
    loop(10)
  }

  def sample[T](implicit a: Arbitrary[T]): T = sample(arbitrary[T])

  protected def protoHash[T <: scalapb.GeneratedMessage](proto: T): ByteString =
    ByteString.copyFrom(Blake2b256.hash(proto.toByteArray))

  val genHash = genBytes(32)
  val genKey  = genBytes(32)

  protected case class AccountKeys(
      privateKey: ByteString,
      publicKey: ByteString
  ) {
    val publicKeyHash: Keys.PublicKeyHashBS =
      Keys.PublicKeyHash(
        ByteString.copyFrom(Ed25519.publicKeyHash(PublicKey(publicKey.toByteArray)))
      )

    def sign(data: ByteString): Signature = {
      val sig = Ed25519.sign(data.toByteArray, PrivateKey(privateKey.toByteArray))
      Signature(Ed25519.name, ByteString.copyFrom(sig))
    }

    val sigAlgorithm = Ed25519.name
  }

  protected val genAccountKeys: Gen[AccountKeys] =
    Gen.delay(Gen.const(Ed25519.newKeyPair match {
      case (privateKey, publicKey) =>
        AccountKeys(
          privateKey = ByteString.copyFrom(privateKey),
          publicKey = ByteString.copyFrom(publicKey)
        )
    }))

  // Override these before generating values if you want more or less random account keys.
  def numAccounts: Int   = 10
  def numValidators: Int = 10

  protected lazy val randomAccounts   = sample(Gen.listOfN(numAccounts, genAccountKeys))
  protected lazy val randomValidators = sample(Gen.listOfN(numValidators, genAccountKeys))

  implicit val arbProtocolVersion: Arbitrary[ProtocolVersion] = Arbitrary {
    for {
      major <- Gen.choose(0, 3)
      minor <- Gen.choose(0, 15)
      patch <- Gen.choose(0, 9)
    } yield {
      ProtocolVersion(major, minor, patch)
    }
  }

  implicit val arbSignature: Arbitrary[Signature] = Arbitrary {
    for {
      alg <- Gen.oneOf("ed25519", "secp256k1")
      sig <- genHash
    } yield {
      Signature(alg, sig)
    }
  }

  implicit val arbApproval: Arbitrary[Approval] = Arbitrary {
    for {
      pk  <- genKey
      sig <- arbitrary[Signature]
    } yield Approval().withApproverPublicKey(pk).withSignature(sig)
  }

  implicit val arbBond: Arbitrary[Bond] = Arbitrary {
    for {
      validator <- genHash
      stake     <- arbitrary[Long]
    } yield Bond()
      .withValidatorPublicKeyHash(validator)
      .withStake(state.BigInt(stake.toString, bitWidth = 512))
  }

  implicit def arbBlock(implicit c: ConsensusConfig): Arbitrary[Block] = Arbitrary {
    for {
      summary <- arbitrary[BlockSummary]
      block   <- genBlockFromSummary(summary)
    } yield block
  }

  implicit val arbJustification: Arbitrary[Block.Justification] = Arbitrary {
    for {
      blockHash <- genHash
      validator <- Gen.oneOf(randomValidators)
    } yield Block.Justification(validator.publicKeyHash, blockHash)
  }

  implicit val arbBlockHeader: Arbitrary[Block.Header] = Arbitrary {
    for {
      parentCount        <- Gen.choose(1, 5)
      parentHashes       <- Gen.listOfN(parentCount, genHash)
      justificationCount <- Gen.choose(1, 5)
      justifications     <- Gen.listOfN(justificationCount, arbJustification.arbitrary)
      deployCount        <- Gen.choose(1, 10)
      bodyHash           <- genHash
      preStateHash       <- genHash
      postStateHash      <- genHash
      validator          <- Gen.oneOf(randomValidators)
      protocolVersion    <- arbitrary[ProtocolVersion]
    } yield {
      Block
        .Header()
        .withParentHashes(parentHashes)
        .withState(Block.GlobalState(preStateHash, postStateHash, Seq.empty))
        .withDeployCount(deployCount)
        .withValidatorPublicKey(validator.publicKey)
        .withValidatorPublicKeyHash(validator.publicKeyHash)
        .withBodyHash(bodyHash)
        .withProtocolVersion(protocolVersion)
        .withJustifications(justifications)
    }
  }

  implicit val arbBlockSummary: Arbitrary[BlockSummary] = Arbitrary {
    for {
      blockHash <- genHash
      header    <- arbitrary[Block.Header]
      signature <- arbitrary[Signature]
    } yield {
      BlockSummary()
        .withBlockHash(blockHash)
        .withHeader(header)
        .withSignature(signature)
    }
  }

  implicit def arbDeploy(implicit c: ConsensusConfig): Arbitrary[Deploy] = Arbitrary {
    for {
      accountKeys     <- Gen.oneOf(randomAccounts)
      timeToLive      <- Gen.option(Gen.choose(1 * 60 * 60 * 1000, 24 * 60 * 60 * 1000))
      age             <- Gen.choose(0, timeToLive getOrElse 24 * 60 * 60 * 1000)
      timestamp       = System.currentTimeMillis - age
      gasPrice        <- arbitrary[Long]
      sessionCode     <- Gen.choose(0, c.maxSessionCodeBytes).flatMap(genBytes(_))
      paymentCode     <- Gen.choose(0, c.maxPaymentCodeBytes).flatMap(genBytes(_))
      numDependencies <- Gen.chooseNum(0, 10)
      dependencies    <- Gen.listOfN(numDependencies, genHash)
      body = Deploy
        .Body()
        .withSession(Deploy.Code().withWasmContract(Deploy.Code.WasmContract(sessionCode)))
        .withPayment(Deploy.Code().withWasmContract(Deploy.Code.WasmContract(paymentCode)))
      bodyHash = protoHash(body)
      header = Deploy
        .Header()
        .withAccountPublicKeyHash(accountKeys.publicKeyHash)
        .withTimestamp(timestamp)
        .withGasPrice(gasPrice)
        .withBodyHash(bodyHash)
        .withDependencies(dependencies)
        .withTtlMillis(timeToLive.getOrElse(0))
      deployHash = protoHash(header)
      signature  = accountKeys.sign(deployHash)
      deploy = Deploy()
        .withDeployHash(deployHash)
        .withHeader(header)
        .withBody(body)
        .withApprovals(
          List(
            Approval()
              .withApproverPublicKey(accountKeys.publicKey)
              .withSignature(signature)
          )
        )
    } yield deploy
  }

  implicit def arbProcessedDeploy(implicit c: ConsensusConfig): Arbitrary[Block.ProcessedDeploy] =
    Arbitrary {
      for {
        deploy  <- arbitrary[Deploy]
        isError <- arbitrary[Boolean]
        cost    <- arbitrary[Long]
        stage   <- Gen.choose(0, 5)
      } yield {
        Block
          .ProcessedDeploy()
          .withDeploy(deploy)
          .withStage(stage)
          .withCost(cost)
          .withIsError(isError)
          .withErrorMessage(if (isError) "Kaboom!" else "")
      }
    }

  // Used to generate a DAG of blocks if we need them.
  // It's backwards but then the DAG of summaries doesn't need to spend time generating bodies.
  private def genBlockFromSummary(summary: BlockSummary)(implicit c: ConsensusConfig): Gen[Block] =
    for {
      deploys   <- Gen.listOfN(summary.deployCount, arbitrary[Block.ProcessedDeploy])
      timestamp = deploys.map(_.getDeploy.getHeader.timestamp).maximumOption.getOrElse(0L)
    } yield {
      Block()
        .withBlockHash(summary.blockHash)
        .withHeader(summary.getHeader.withTimestamp(timestamp))
        .withBody(Block.Body(deploys))
        .withSignature(summary.getSignature)
    }

  /** Grow a DAG by adding layers on top of the tips. */
  def genSummaryDagFromGenesis(implicit c: ConsensusConfig): Gen[Vector[BlockSummary]] = {
    def loop(
        acc: Vector[BlockSummary],
        tips: Set[BlockSummary]
    ): Gen[Vector[BlockSummary]] = {
      // Each child will choose some parents and some justifications from the tips.
      val genChild =
        for {
          parents        <- Gen.choose(1, tips.size).flatMap(Gen.pick(_, tips))
          justifications <- Gen.someOf(tips -- parents)
          block          <- arbitrary[BlockSummary]
        } yield {
          val header = block.getHeader
            .withParentHashes(parents.map(_.blockHash))
            .withJustifications(justifications.toSeq.map { j =>
              Block.Justification(
                latestBlockHash = j.blockHash,
                validatorPublicKeyHash = j.validatorPublicKeyHash
              )
            })
            .withJRank((parents ++ justifications).map(_.jRank).max + 1)
            .withMainRank(parents.head.mainRank + 1)
          block.withHeader(header)
        }

      val genChildren =
        for {
          growth   <- Gen.frequency((1, 0), (2, 1), (3, 2), (3, 3), (2, 4), (1, 5))
          children <- Gen.listOfN(growth, genChild)
        } yield children

      // Continue until no children were generated or we reach maximum height.
      genChildren.flatMap { children =>
        val parentHashes = children.flatMap(_.parentHashes).toSet
        val stillTips    = tips.filterNot(tip => parentHashes(tip.blockHash))
        val nextTips     = stillTips ++ children
        val nextAcc      = acc ++ children
        c.dagSize match {
          case 0 if children.isEmpty           => Gen.const(nextAcc)
          case n if 0 < n && n <= nextAcc.size => Gen.const(nextAcc.take(n))
          case _                               => loop(nextAcc, nextTips)
        }
      }
    }
    // Always start from the Genesis block.
    arbitrary[BlockSummary] map { summary =>
      summary
        .update(_.header.parentHashes := Seq.empty)
        .update(_.header.justifications := Seq.empty)
        .update(_.header.jRank := 0)
        .update(_.header.mainRank := 0)
        .update(_.header.validatorPublicKeyHash := ByteString.EMPTY)
        .update(_.header.validatorPublicKey := ByteString.EMPTY)
        .clearSignature
    } flatMap { genesis =>
      loop(Vector(genesis), Set(genesis))
    }
  }

  def genBlockDagFromGenesis(implicit c: ConsensusConfig): Gen[Vector[Block]] =
    for {
      summaries <- genSummaryDagFromGenesis
      blocks    <- Gen.sequence(summaries.map(genBlockFromSummary))
    } yield blocks.asScala.toVector

  /**
    * Generates partial (no genesis) DAG starting from newest element.
    * Possible example:
    *          *  (newest)
    *         * *
    *         * *
    *         * *
    *         * * (oldest)
    */
  def genPartialDagFromTips(implicit c: ConsensusConfig): Gen[Vector[BlockSummary]] = {

    def pair(child: BlockSummary, parents: Seq[BlockSummary]): BlockSummary =
      child.withHeader(
        child.getHeader
          .withParentHashes(parents.map(_.blockHash))
          .withJustifications(parents.map { j =>
            Block.Justification(
              latestBlockHash = j.blockHash,
              validatorPublicKeyHash = j.validatorPublicKeyHash
            )
          })
      )

    def loop(
        acc: Vector[BlockSummary],
        children: Vector[BlockSummary],
        depth: Int
    ): Gen[Vector[BlockSummary]] =
      if (depth > c.dagDepth) {
        Gen.const(acc ++ children)
      } else {
        for {
          parents <- Gen
                      .listOfN(
                        math.min(
                          c.dagWidth,
                          math.pow(c.dagBranchingFactor.toDouble, depth.toDouble).toInt
                        ),
                        arbitrary[BlockSummary]
                      )
                      .map(
                        _.map(
                          s =>
                            s.withHeader(
                              s.getHeader
                                .withParentHashes(Seq.empty)
                                .withJustifications(Seq.empty)
                                .withJRank((c.dagDepth - depth).toLong)
                            )
                        )
                      )
          updatedChildren = children.map { child =>
            pair(child, parents)
          }
          res <- loop(acc ++ updatedChildren, parents.toVector, depth + 1)
        } yield res
      }

    arbitrary[BlockSummary].flatMap { newest =>
      loop(
        Vector.empty,
        Vector(
          newest.withHeader(
            newest.getHeader
              .withParentHashes(Seq.empty)
              .withJustifications(Seq.empty)
              .withJRank(c.dagDepth.toLong)
          )
        ),
        1
      )
    }
  }

  implicit val arbEra: Arbitrary[Era] = Arbitrary {
    for {
      keyBlockHash       <- genHash
      bookingBlockHash   <- genHash
      parentKeyBlockHash <- Gen.oneOf(Gen.const(ByteString.EMPTY), genHash)
      length             <- Gen.choose(1, 168).map(_ * 60 * 60 * 1000)
      now                = System.currentTimeMillis
      startTick          <- Gen.choose(now - length, now)
      endTick            = startTick + length
      bonds              <- arbitrary[List[Bond]]
      seed               <- genHash
    } yield {
      Era()
        .withKeyBlockHash(keyBlockHash)
        .withParentKeyBlockHash(parentKeyBlockHash)
        .withBookingBlockHash(bookingBlockHash)
        .withStartTick(startTick)
        .withEndTick(endTick)
        .withBonds(bonds)
        .withLeaderSeed(seed)
    }
  }

  // It doesn't make sense to shrink DAGs because the default shrink
  // will most likely get rid of parents, and make any derived selections
  // invalid too.
  implicit val noShrinkBlockDag: Shrink[Vector[Block]]               = Shrink(_ => Stream.empty)
  implicit val noShrinkBlockSummaryDag: Shrink[Vector[BlockSummary]] = Shrink(_ => Stream.empty)
}
