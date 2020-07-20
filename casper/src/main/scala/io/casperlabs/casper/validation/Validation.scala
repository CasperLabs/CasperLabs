package io.casperlabs.casper.validation

import cats.{Applicative, Monad, Show}
import cats.implicits._
import cats.mtl.FunctorRaise
import com.google.protobuf.ByteString
import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper._
import io.casperlabs.casper.consensus.{state, Block, BlockSummary, Bond, Deploy}
import io.casperlabs.casper.dag.DagOperations
import io.casperlabs.casper.equivocations.EquivocationDetector
import io.casperlabs.casper.util.execengine.ExecEngineUtil
import io.casperlabs.casper.util.execengine.ExecEngineUtil.StateHash
import io.casperlabs.casper.util.{CasperLabsProtocol, ProtoUtil}
import io.casperlabs.casper.validation.Errors.DropErrorWrapper
import io.casperlabs.casper.validation.Validation.BlockEffects
import io.casperlabs.crypto.Keys, Keys.{PublicKey, Signature}
import io.casperlabs.catscontrib.Fs2Compiler
import io.casperlabs.ipc
import io.casperlabs.smartcontracts.ExecutionEngineService
import io.casperlabs.storage.block.BlockStorage
import io.casperlabs.storage.dag.DagRepresentation
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.crypto.signatures.SignatureAlgorithm
import io.casperlabs.ipc.ChainSpec.{DeployConfig => IPCDeployConfig}
import io.casperlabs.ipc.TransformEntry
import io.casperlabs.models.Message
import io.casperlabs.shared._
import io.casperlabs.models.BlockImplicits._
import io.casperlabs.models.Message.MainRank

import scala.concurrent.duration.{FiniteDuration, MILLISECONDS}
import scala.util.{Success, Try}
import io.casperlabs.shared.ByteStringPrettyPrinter._

trait Validation[F[_]] {

  /**
    * If block contains an invalid justification block B and the creator of B is still bonded,
    * return a RejectableBlock. Otherwise return an IncludeableBlock.
    */
  def neglectedInvalidBlock(
      block: Block,
      invalidBlockTracker: Set[BlockHash]
  ): F[Unit]

  /**
    * Checks that the parents of `b` were chosen correctly according to the
    * forkchoice rule. This is done by using the justifications of `b` as the
    * set of latest messages, so the justifications must be fully explicit.
    * For multi-parent blocks this requires doing commutativity checking, so
    * the combined effect of all parents except the first (i.e. the effect
    * which would need to be applied to the first parent's post-state to
    * obtain the pre-state of `b`) is given as the return value in order to
    * avoid repeating work downstream.
    */
  def parents(
      block: Block,
      dag: DagRepresentation[F]
  )(implicit bs: BlockStorage[F]): F[ExecEngineUtil.MergeResult[ExecEngineUtil.TransformMap, Block]]

  // Validates whether received block is valid (according to that nodes logic):
  // 1) Validates whether pre state hashes match
  // 2) Runs deploys from the block
  // 3) Validates whether post state hashes match
  // 4) Validates whether bonded validators, as at the end of executing the block, match.
  def transactions(
      block: Block,
      preStateHash: StateHash,
      preStateBonds: Seq[Bond],
      effects: BlockEffects
  )(
      implicit ee: ExecutionEngineService[F],
      bs: BlockStorage[F],
      clp: CasperLabsProtocol[F]
  ): F[Unit]

  /** Check the block without executing deploys. */
  def blockFull(
      block: Block,
      dag: DagRepresentation[F],
      chainName: String,
      maybeGenesis: Option[Block]
  )(
      implicit bs: BlockStorage[F],
      versions: CasperLabsProtocol[F],
      compiler: Fs2Compiler[F]
  ): F[Unit]

  def blockSummary(
      summary: BlockSummary,
      chainName: String
  )(implicit versions: CasperLabsProtocol[F]): F[Unit]

  def checkEquivocation(dag: DagRepresentation[F], block: Block): F[Unit]
}

object Validation {
  def apply[F[_]](implicit ev: Validation[F]) = ev

  type BlockHeight = MainRank
  type Data        = Array[Byte]

  /** Represents block's effects indexed by deploy's `stage` value.
    * Deploys with the same `stage` value can be run in parallel.
    * Execution must be ordered from lowest stage to the highest.
    */
  final case class BlockEffects(effects: Map[Int, Seq[TransformEntry]])

  object BlockEffects {
    def apply(effects: Seq[TransformEntry]): BlockEffects =
      BlockEffects(Map(0 -> effects))

    def empty: BlockEffects = BlockEffects(Map.empty[Int, Seq[TransformEntry]])
  }

  val DRIFT = 15000 // 15 seconds

  def ignore[F[_]: Log](blockHash: ByteString, reason: String): F[Unit] =
    Log[F].warn(
      s"Ignoring ${blockHash.show -> "message"} because ${reason -> "reason" -> null}"
    )

  def raise[F[_]: RaiseValidationError](status: InvalidBlock): F[Unit] =
    FunctorRaise[F, InvalidBlock].raise[Unit](status)

  def reject[F[_]: Applicative: RaiseValidationError: Log](
      blockHash: ByteString,
      status: InvalidBlock,
      reason: String
  ): F[Unit] =
    ignore[F](blockHash, reason) *> raise(status)

  def reject[F[_]: Applicative: RaiseValidationError: Log](
      block: Block,
      status: InvalidBlock,
      reason: String
  ): F[Unit] =
    reject[F](block.blockHash, status, reason)

  def reject[F[_]: Applicative: RaiseValidationError: Log](
      summary: BlockSummary,
      status: InvalidBlock,
      reason: String
  ): F[Unit] =
    reject[F](summary.blockHash, status, reason)

  // Check whether message merges its creator swimlane.
  // A block cannot have more than one latest message in its j-past-cone from its creator.
  // i.e. an equivocator cannot cite multiple of its latest messages.
  def swimlane[F[_]: MonadThrowable: RaiseValidationError: Log](
      b: BlockSummary,
      dag: DagRepresentation[F],
      isHighway: Boolean
  ): F[Unit] =
    for {
      equivocations <- dag.latestGlobal.flatMap(_.getEquivocations)
      message       <- MonadThrowable[F].fromTry(Message.fromBlockSummary(b))
      _ <- Monad[F].whenA(equivocations.contains(message.validatorId)) {
            val validatorEquivocations      = equivocations(message.validatorId)
            val validatorEquivocationHashes = validatorEquivocations.mapValues(_.map(_.messageHash))
            val minRank = EquivocationDetector
              .findMinBaseRank(
                Map(message.validatorId -> validatorEquivocations.values.flatten.toSet)
              )
              .getOrElse(0L) // We know it has to be defined by this point.
            // Look for a pair of equivocations both in the same era.
            val initAcc =
              Map.empty[BlockHash, Set[BlockHash]].withDefaultValue(Set.empty[BlockHash])
            for {
              seenEquivocations <- DagOperations
                                    .swimlaneV[F](message.validatorId, message, dag)
                                    .foldWhileLeft(initAcc) {
                                      case (acc, message) =>
                                        if (message.jRank <= minRank) {
                                          Right(acc)
                                        } else {
                                          // In Highway the key block hash is the era;
                                          // in NCB it's the LFB and should be ignored.
                                          val keyBlockHash =
                                            if (isHighway) message.eraId else ByteString.EMPTY

                                          // Which are the equivocations we know about in this era.
                                          val equivocationHashes =
                                            validatorEquivocationHashes.getOrElse(
                                              keyBlockHash,
                                              Set.empty
                                            )

                                          // If this is an equivocating message, remember we have seen it,
                                          // and see its pair from the same era turns up later.
                                          if (equivocationHashes.contains(message.messageHash)) {
                                            val prevSeen = acc(keyBlockHash)
                                            val nextSeen = prevSeen + message.messageHash
                                            val nextAcc  = acc.updated(keyBlockHash, nextSeen)
                                            if (nextSeen.size > 1) Right(nextAcc) else Left(nextAcc)
                                          } else Left(acc)
                                        }
                                    }
              maybeSeenEquivocationPair = seenEquivocations.values.find(_.size > 1)
              _ <- maybeSeenEquivocationPair.fold(().pure[F]) { seenEquivocations =>
                    val msg =
                      s"${PrettyPrinter.buildString(message.messageHash)} cites multiple latest message by its creator ${PrettyPrinter
                        .buildString(message.validatorId)}: ${seenEquivocations
                        .map(PrettyPrinter.buildString)
                        .mkString("[", ",", "]")}"
                    reject[F](b, SwimlaneMerged, msg)
                  }
            } yield ()
          }
    } yield ()

  /* If we receive block from future then we may fail to propose new block on top of it because of Validation.timestamp */
  def preTimestamp[F[_]: Monad: RaiseValidationError: Time](
      b: Block
  ): F[Option[FiniteDuration]] =
    for {
      currentMillis <- Time[F].currentMillis
      delay <- b.timestamp - currentMillis match {
                case n if n <= 0     => none[FiniteDuration].pure[F]
                case n if n <= DRIFT =>
                  // Sleep for a little bit more time to ensure we won't propose block on top of block from future
                  FiniteDuration(n + 500, MILLISECONDS).some.pure[F]
                case _ =>
                  RaiseValidationError[F].raise[Option[FiniteDuration]](InvalidUnslashableBlock)
              }
    } yield delay

  /** Check that none of the deploys in the block have been included in another block already
    * which was in the P-past cone of the block itself.
    */
  def deployUniqueness[F[_]: MonadThrowable: RaiseValidationError: Log](
      block: Block,
      dag: DagRepresentation[F]
  )(implicit bs: BlockStorage[F]): F[Unit] = {
    val deploys        = block.getBody.deploys.map(_.getDeploy).toList
    val maybeDuplicate = deploys.groupBy(_.deployHash).find(_._2.size > 1).map(_._2.head)
    maybeDuplicate match {
      case Some(duplicate) =>
        reject[F](
          block,
          InvalidRepeatDeploy,
          s"block contains duplicate ${PrettyPrinter.buildString(duplicate)}"
        )

      case None =>
        for {
          deployToBlocksMap <- bs.findBlockHashesWithDeployHashes(deploys.map(_.deployHash))

          blockHashes = deployToBlocksMap.values.flatten.toSet

          duplicateBlockHashes <- DagOperations.collectWhereDescendantPathExists(
                                   dag,
                                   blockHashes,
                                   Set(block.blockHash)
                                 )

          _ <- if (duplicateBlockHashes.isEmpty) ().pure[F]
              else {
                val exampleBlockHash = duplicateBlockHashes.head
                val exampleDeploy = deployToBlocksMap.collectFirst {
                  case (deploy, blockHashes) if blockHashes.contains(exampleBlockHash) =>
                    deploy
                }.get
                reject[F](
                  block,
                  InvalidRepeatDeploy,
                  s"block contains a duplicate ${exampleDeploy.show} already present in ${PrettyPrinter
                    .buildString(exampleBlockHash)}"
                )
              }

        } yield ()
    }
  }

  def totalCost[F[_]: MonadThrowable: RaiseValidationError: Log](
      block: Block
  )(
      implicit versions: CasperLabsProtocol[F]
  ): F[Unit] =
    for {
      config  <- versions.configAt(Message.asMainRank(block.getHeader.mainRank))
      zero    = 0L
      maxCost = config.deployConfig.maxBlockCost
      totalCost = if (maxCost == zero) zero
      else block.getBody.deploys.map(_.cost).foldLeft(zero)(_ + _)
      _ <- if (zero < maxCost && maxCost < totalCost) {
            reject[F](
              block,
              TooExpensive,
              s"total gas cost $totalCost is higher than the maximum $maxCost"
            )
          } else ().pure[F]
    } yield ()

  // Block rank is 1 plus the maximum of the rank of its justifications.
  def blockRank[F[_]: MonadThrowable: RaiseValidationError: Log](
      b: BlockSummary,
      dag: DagRepresentation[F]
  ): F[Unit] =
    for {
      justificationMsgs <- (b.parents ++ b.justifications.map(_.latestBlockHash)).toSet.toList
                            .traverse(dag.lookupUnsafe(_))
      calculatedRank = ProtoUtil.nextJRank(justificationMsgs)
      actuallyRank   = b.jRank
      result         = calculatedRank == actuallyRank
      _ <- if (result) {
            Applicative[F].unit
          } else {
            val msg =
              if (justificationMsgs.isEmpty)
                s"block number $actuallyRank is not zero, but block has no justifications."
              else
                s"block number $actuallyRank is not the maximum block number of justifications plus 1, i.e. $calculatedRank."
            reject[F](b, InvalidBlockNumber, msg)
          }
    } yield ()

  // This is not a slashable offence
  def timestamp[F[_]: MonadThrowable: RaiseValidationError: Log: Time](
      b: BlockSummary
  )(implicit bs: BlockStorage[F]): F[Unit] =
    for {
      currentTime  <- Time[F].currentMillis
      timestamp    = b.timestamp
      beforeFuture = currentTime + DRIFT >= timestamp
      dependencies = b.parentHashes ++ b.getHeader.justifications.map(_.latestBlockHash)
      latestDependencyTimestamp <- dependencies.distinct.toList.foldM(0L) {
                                    case (latestTimestamp, blockHash) =>
                                      ProtoUtil
                                        .unsafeGetBlockSummary[F](blockHash)
                                        .map(block => {
                                          val timestamp =
                                            block.header.fold(latestTimestamp)(_.timestamp)
                                          math.max(latestTimestamp, timestamp)
                                        })
                                  }
      afterLatestDependency = timestamp >= latestDependencyTimestamp
      _ <- if (beforeFuture && afterLatestDependency) {
            Applicative[F].unit
          } else {
            reject[F](
              b,
              InvalidUnslashableBlock,
              s"block timestamp $timestamp is not between latest justification block time and current time."
            )
          }
    } yield ()

  // Validate that stake map of the block matches locally calculated one.
  def bondsCache[F[_]: MonadThrowable: RaiseValidationError: Log](
      b: Block,
      computedBonds: Seq[Bond]
  ): F[Unit] = {
    val bonds = ProtoUtil.bonds(b)
    ProtoUtil.postStateHash(b) match {
      case globalStateRootHash if !globalStateRootHash.isEmpty =>
        if (bonds.toSet == computedBonds.toSet) {
          Applicative[F].unit
        } else {
          reject[F](
            b,
            InvalidBondsCache,
            "Bonds in proof of stake contract do not match block's bond cache."
          )
        }
      case _ =>
        reject[F](
          b,
          InvalidBondsCache,
          s"Block ${PrettyPrinter.buildString(b)} is missing a post state hash."
        )
    }
  }

  def formatOfFields[F[_]: Monad: RaiseValidationError: Log](
      b: BlockSummary,
      treatAsGenesis: Boolean = false
  ): F[Boolean] = {
    def invalid(msg: String) =
      ignore[F](b.blockHash, msg).as(false)

    if (b.blockHash.isEmpty) {
      invalid(s"block hash is empty.")
    } else if (b.header.isEmpty) {
      invalid(s"block header is missing.")
    } else if (b.getSignature.sig.isEmpty && !treatAsGenesis) {
      invalid(s"block signature is empty.")
    } else if (!b.getSignature.sig.isEmpty && treatAsGenesis) {
      invalid(s"block signature is not empty on Genesis.")
    } else if (b.getSignature.sigAlgorithm.isEmpty && !treatAsGenesis) {
      invalid(s"block signature algorithm is not empty on Genesis.")
    } else if (!b.getSignature.sigAlgorithm.isEmpty && treatAsGenesis) {
      invalid(s"block signature algorithm is empty.")
    } else if (b.chainName.isEmpty) {
      invalid(s"block chain identifier is empty.")
    } else if (b.state.postStateHash.isEmpty) {
      invalid(s"block post state hash is empty.")
    } else if (b.bodyHash.isEmpty) {
      invalid(s"block new code hash is empty.")
    } else {
      true.pure[F]
    }
  }

  private def signatureVerifiers(
      sigAlgorithm: String
  ): Option[(Data, Signature, PublicKey) => Boolean] =
    sigAlgorithm match {
      case SignatureAlgorithm(sa) => Some((data, sig, pub) => sa.verify(data, sig, pub))
      case _                      => None
    }

  def signature(d: Data, sig: consensus.Signature, key: PublicKey): Boolean =
    signatureVerifiers(sig.sigAlgorithm).fold(false) { verify =>
      verify(d, Signature(sig.sig.toByteArray), key)
    }

  def publicKeyHash(
      sig: consensus.Signature,
      key: Keys.PublicKeyBS,
      hash: Keys.PublicKeyHashBS
  ): Boolean = {
    val pk = Keys.PublicKey(key.toByteArray)
    val computed = sig.sigAlgorithm match {
      case SignatureAlgorithm(sa) =>
        sa.publicKeyHash(pk)
      case other =>
        Keys.publicKeyHash(other)(pk)
    }
    ByteString.copyFrom(computed) == hash
  }

  def deploySignature[F[_]: MonadThrowable: Log](
      d: consensus.Deploy
  ): F[Boolean] =
    if (d.approvals.isEmpty) {
      Log[F].warn(
        s"Deploy ${PrettyPrinter.buildString(d.deployHash) -> "deployHash"} has no signatures."
      ) *> false.pure[F]
    } else {
      d.approvals.toList
        .traverse { a =>
          signatureVerifiers(a.getSignature.sigAlgorithm)
            .map { verify =>
              Try {
                verify(
                  d.deployHash.toByteArray,
                  Signature(a.getSignature.sig.toByteArray),
                  PublicKey(a.approverPublicKey.toByteArray)
                )
              } match {
                case Success(true) =>
                  true.pure[F]
                case _ =>
                  Log[F]
                    .warn(
                      s"Signature of ${PrettyPrinter.buildString(d.deployHash) -> "deployHash"} is invalid."
                    )
                    .as(false)
              }
            } getOrElse {
            Log[F]
              .warn(
                s"Signature algorithm ${a.getSignature.sigAlgorithm} of deploy ${PrettyPrinter
                  .buildString(d.deployHash) -> "deployHash"} is unsupported."
              )
              .as(false)
          }
        }
        .map(_.forall(identity))
    }

  def blockSignature[F[_]: Monad: Log](b: BlockSummary): F[Boolean] =
    signatureVerifiers(b.getSignature.sigAlgorithm) map { verify =>
      Try(
        verify(
          b.blockHash.toByteArray,
          Signature(b.getSignature.sig.toByteArray),
          PublicKey(b.getHeader.validatorPublicKey.toByteArray)
        )
      ) match {
        case Success(true) => true.pure[F]
        case _             => ignore[F](b.blockHash, "signature is invalid.").as(false)
      }
    } getOrElse {
      ignore[F](
        b.blockHash,
        s"signature algorithm '${b.getSignature.sigAlgorithm}' is unsupported."
      ).as(false)
    }

  def validatorPublicKeyHash[F[_]: Monad: Log](b: BlockSummary): F[Boolean] =
    publicKeyHash(
      b.getSignature,
      Keys.PublicKey(b.getHeader.validatorPublicKey),
      Keys.PublicKeyHash(b.getHeader.validatorPublicKeyHash)
    ) match {
      case true =>
        true.pure[F]
      case false =>
        ignore[F](
          b.blockHash,
          s"the validator's public key hash doesn't match the key and the signature algorithm."
        ).as(false)
    }

  def deployHeader[F[_]: MonadThrowable: RaiseValidationError: Log: Time](
      d: consensus.Deploy,
      chainName: String,
      deployConfig: IPCDeployConfig
  ): F[List[Errors.DeployHeaderError]] =
    d.header match {
      case Some(header) =>
        Applicative[F].map4(
          maxTtl[F](
            ProtoUtil.getTimeToLive(header, deployConfig.maxTtlMillis),
            d.deployHash,
            deployConfig.maxTtlMillis
          ),
          validateDependencies[F](
            header.dependencies,
            d.deployHash,
            deployConfig.maxDependencies
          ),
          validateChainName[F](d, chainName),
          validateTimestamp[F](d)
        ) {
          case (validTTL, validDependencies, validChainNames, validTimestamp) =>
            validTTL.toList ::: validDependencies ::: validChainNames.toList ::: validTimestamp.toList
        }

      case None =>
        Errors.DeployHeaderError.MissingHeader(d.deployHash).logged[F].map(List(_))
    }

  private def maxTtl[F[_]: MonadThrowable: RaiseValidationError: Log](
      ttl: Int,
      deployHash: ByteString,
      maxTTL: Int
  ): F[Option[Errors.DeployHeaderError]] =
    if (ttl > maxTTL)
      Errors.DeployHeaderError.timeToLiveTooLong(deployHash, ttl, maxTTL).logged[F].map(_.some)
    else
      none[Errors.DeployHeaderError].pure[F]

  def minTtl[F[_]: Applicative: Log](
      deploy: Deploy,
      minTtl: FiniteDuration
  ): F[Option[Errors.DeployHeaderError]] =
    // If deploy's TTL is set to 0 it means user didn't want to set the TTL and is OK with how
    // node handles its deploy. If it's anything different than 0 (must be positive though) then
    // it also has to be correct as per node's min TTL configuration.
    if (deploy.getHeader.ttlMillis != 0 && deploy.getHeader.ttlMillis < minTtl.toMillis)
      Errors.DeployHeaderError
        .timeToLiveTooShort(deploy.deployHash, deploy.getHeader.ttlMillis, minTtl)
        .logged[F]
        .map(_.some)
    else
      none[Errors.DeployHeaderError].pure[F]

  private def validateDependencies[F[_]: MonadThrowable: RaiseValidationError: Log](
      dependencies: Seq[ByteString],
      deployHash: ByteString,
      maxDependencies: Int
  ): F[List[Errors.DeployHeaderError]] = {
    val numDependencies = dependencies.length
    val tooMany =
      if (numDependencies > maxDependencies)
        Errors.DeployHeaderError
          .tooManyDependencies(deployHash, numDependencies, maxDependencies)
          .logged[F]
          .map(_.some)
      else
        none[Errors.DeployHeaderError].pure[F]

    val invalid = dependencies.toList
      .filter(_.size != 32)
      .traverse(dep => Errors.DeployHeaderError.invalidDependency(deployHash, dep).logged[F])

    Applicative[F].map2(tooMany, invalid)(_.toList ::: _)
  }

  def validateChainName[F[_]: MonadThrowable: Log](
      deploy: Deploy,
      chainName: String
  ): F[Option[Errors.DeployHeaderError]] =
    if (deploy.getHeader.chainName.nonEmpty && deploy.getHeader.chainName != chainName)
      Errors.DeployHeaderError
        .invalidChainName(deploy.deployHash, deploy.getHeader.chainName, chainName)
        .logged[F]
        .map(_.some)
    else
      none[Errors.DeployHeaderError].pure[F]

  private def validateTimestamp[F[_]: Monad: Log: Time](
      deploy: consensus.Deploy
  ): F[Option[Errors.DeployHeaderError]] =
    Time[F].currentMillis flatMap { currentTime =>
      if (currentTime + DRIFT < deploy.getHeader.timestamp) {
        Errors.DeployHeaderError
          .timestampInFuture(deploy.deployHash, deploy.getHeader.timestamp, DRIFT)
          .logged[F]
          .map(_.some)
      } else {
        none[Errors.DeployHeaderError].pure[F]
      }
    }

  def deployHash[F[_]: Monad: Log](d: consensus.Deploy): F[Boolean] = {
    val bodyHash   = ProtoUtil.protoHash(d.getBody)
    val deployHash = ProtoUtil.protoHash(d.getHeader)
    val ok         = bodyHash == d.getHeader.bodyHash && deployHash == d.deployHash

    def logDiff = {
      // Print the full length, maybe the client has configured their hasher to output 64 bytes.
      def b16(bytes: ByteString) = Base16.encode(bytes.toByteArray)
      for {
        _ <- Log[F]
              .warn(
                s"Invalid deploy body hash; got ${b16(d.getHeader.bodyHash) -> "bodyHash"}, expected ${b16(bodyHash) -> "expectedBodyHash"}"
              )
        _ <- Log[F]
              .warn(
                s"Invalid deploy hash; got ${b16(d.deployHash) -> "deployHash"}, expected ${b16(deployHash) -> "expectedDeployHash"}"
              )
      } yield ()
    }

    logDiff.whenA(!ok).as(ok)
  }

  def blockSender[F[_]: Monad: Log](block: BlockSummary)(implicit bs: BlockStorage[F]): F[Boolean] =
    for {
      weight <- ProtoUtil.weightFromSender[F](block.getHeader)
      result <- if (weight > 0) true.pure[F]
               else
                 ignore[F](
                   block.blockHash,
                   s"block creator ${PrettyPrinter.buildString(block.validatorPublicKeyHash)} has 0 weight."
                 ).as(false)
    } yield result

  // Validates whether block was built using correct protocol version.
  def version[F[_]: Monad: Log](
      b: BlockSummary,
      m: MainRank => F[state.ProtocolVersion]
  ): F[Boolean] = {

    val blockVersion = b.getHeader.getProtocolVersion
    val blockHeight  = b.mainRank
    m(blockHeight).flatMap { version =>
      if (blockVersion == version) {
        true.pure[F]
      } else {
        ignore[F](
          b.blockHash,
          s"Received block version $blockVersion, expected version $version."
        ).as(false)
      }
    }
  }

  /**
    * Works with either efficient justifications or full explicit justifications
    */
  def missingBlocks[F[_]: Monad: RaiseValidationError: Log](
      block: BlockSummary
  )(implicit bs: BlockStorage[F]): F[Unit] =
    for {
      parentsPresent <- block.parentHashes.toList
                         .forallM(p => BlockStorage[F].contains(p))
      justificationsPresent <- block.justifications.toList
                                .forallM(j => BlockStorage[F].contains(j.latestBlockHash))
      _ <- reject[F](block, MissingBlocks, "parents or justifications are missing from storage")
            .whenA(!parentsPresent || !justificationsPresent)
    } yield ()

  /**
    * Works with either efficient justifications or full explicit justifications.
    * Specifically, with efficient justifications, if a block B doesn't update its
    * creator justification, this check will fail as expected. The exception is when
    * B's creator justification is the genesis block.
    */
  def sequenceNumber[F[_]: MonadThrowable: RaiseValidationError: Log](
      b: BlockSummary,
      dag: DagRepresentation[F]
  ): F[Unit] =
    if (b.isGenesisLike)
      FunctorRaise[F, InvalidBlock]
        .raise[Unit](InvalidSequenceNumber)
        .whenA(b.validatorBlockSeqNum != 0)
    else
      for {
        creatorJustificationSeqNumber <- ProtoUtil.nextValidatorBlockSeqNum(
                                          dag,
                                          b.getHeader.validatorPrevBlockHash
                                        )
        number = b.validatorBlockSeqNum
        ok     = creatorJustificationSeqNumber == number
        _ <- if (ok) {
              Applicative[F].unit
            } else {
              reject[F](
                b,
                InvalidSequenceNumber,
                s"seq number $number is not one more than creator justification number $creatorJustificationSeqNumber."
              )
            }
      } yield ()

  /** Validate that the j-past-cone of the block cites the previous block hash,
    * except if this is the first block the validator created.
    */
  def validatorPrevBlockHash[F[_]: MonadThrowable: RaiseValidationError: Log](
      b: BlockSummary,
      dag: DagRepresentation[F],
      isHighway: Boolean
  ): F[Unit] = {
    val prevBlockHash = b.getHeader.validatorPrevBlockHash
    val validatorId   = Keys.PublicKeyHash(b.getHeader.validatorPublicKeyHash)
    if (prevBlockHash.isEmpty) {
      ().pure[F]
    } else {
      def rejectWith(msg: String) =
        reject[F](b, InvalidPrevBlockHash, msg)

      dag.lookup(prevBlockHash).flatMap {
        case None =>
          rejectWith(
            s"DagStorage is missing previous block hash ${prevBlockHash.show}"
          )
        case Some(prev) if prev.validatorId != validatorId =>
          rejectWith(
            s"Previous block hash ${prevBlockHash.show} was not created by validator ${PrettyPrinter
              .buildString(validatorId)}"
          )
        case Some(prev) =>
          MonadThrowable[F].fromTry(Message.fromBlockSummary(b)) flatMap { blockMsg =>
            DagOperations
              .toposortJDagDesc(dag, List(blockMsg))
              .filter { j =>
                j.eraId == b.getHeader.keyBlockHash || !isHighway
              }
              .find { j =>
                // Go until we're passed the previous message they point at. It should be the first form this validator.
                j.validatorId == validatorId && j.messageHash != b.blockHash || j.jRank < prev.jRank
              }
              .flatMap {
                case Some(msg) if msg.messageHash == prevBlockHash =>
                  ().pure[F]
                case Some(msg) if msg.validatorId == validatorId =>
                  rejectWith(
                    s"The previous block hash from this validator in the j-past-cone is ${PrettyPrinter
                      .buildString(msg.messageHash)}, not the expected ${prevBlockHash.show}"
                  )
                case _ =>
                  rejectWith(
                    s"Could not find any previous block hash from the validator in the j-past-cone."
                  )
              }
          }
      }
    }
  }

  // Agnostic of justifications
  def chainIdentifier[F[_]: Applicative: RaiseValidationError: Log](
      b: BlockSummary,
      chainName: String
  ): F[Unit] =
    if (b.chainName == chainName) {
      Applicative[F].unit
    } else {
      reject[F](
        b,
        InvalidChainName,
        s"got chain identifier ${b.chainName} while $chainName was expected."
      )
    }

  def blockHash[F[_]: Monad: RaiseValidationError: Log](
      b: Block
  ): F[Unit] = {
    val blockHashComputed = ProtoUtil.protoHash(b.getHeader)
    val bodyHashComputed  = ProtoUtil.protoHash(b.getBody)

    if (b.blockHash == blockHashComputed &&
        b.bodyHash == bodyHashComputed) {
      Applicative[F].unit
    } else {
      for {
        _ <- Log[F]
              .warn(
                s"Expected block hash ${blockHashComputed.show -> "expectedBlockHash"}; got ${b.blockHash.show -> "blockHash"}"
              )
              .whenA(b.blockHash != blockHashComputed)
        _ <- Log[F]
              .warn(
                s"Expected body hash ${bodyHashComputed.show -> "expectedBodyHash"}; got ${b.bodyHash.show -> "bodyHash"}"
              )
              .whenA(b.bodyHash != bodyHashComputed)
        _ <- reject[F](
              b.blockHash,
              InvalidBlockHash,
              "block hash does not match to computed value."
            )
      } yield ()
    }
  }

  def summaryHash[F[_]: Monad: RaiseValidationError: Log](
      b: BlockSummary
  ): F[Unit] = {
    val blockHashComputed = ProtoUtil.protoHash(b.getHeader)
    val ok                = b.blockHash == blockHashComputed
    reject[F](b, InvalidBlockHash, s"invalid block hash").whenA(!ok)
  }

  def deployCount[F[_]: Applicative: RaiseValidationError: Log](
      b: Block
  ): F[Unit] =
    if (b.deployCount == b.getBody.deploys.length) {
      Applicative[F].unit
    } else {
      reject[F](
        b,
        InvalidDeployCount,
        s"block deploy count does not match to the amount of deploys."
      )
    }

  def deployHeaders[F[_]: MonadThrowable: RaiseValidationError: Log: Time: CasperLabsProtocol](
      b: Block,
      dag: DagRepresentation[F],
      chainName: String
  )(
      implicit blockStorage: BlockStorage[F]
  ): F[Unit] = {
    val deploys: List[consensus.Deploy] = b.getBody.deploys.flatMap(_.deploy).toList
    val parents: Set[BlockHash] =
      b.header.toSet.flatMap((h: consensus.Block.Header) => h.parentHashes)
    val timestamp       = b.getHeader.timestamp
    val isFromPast      = DeployFilters.timestampBefore(timestamp)
    val isNotExpired    = (maxTTL: Int) => DeployFilters.notExpired(timestamp, maxTTL)
    val dependenciesMet = DeployFilters.dependenciesMet[F](dag, parents)

    def singleDeployValidation(d: consensus.Deploy): F[Unit] =
      for {
        config <- CasperLabsProtocol[F].configAt(b.mainRank).map(_.deployConfig)
        staticErrors <- deployHeader[F](
                         d,
                         chainName,
                         config
                       )
        _                      <- raiseHeaderErrors(staticErrors).whenA(staticErrors.nonEmpty)
        header                 = d.getHeader
        isFromFuture           = !isFromPast(header)
        _                      <- raiseFutureDeploy(d.deployHash, header).whenA(isFromFuture)
        isExpired              = !isNotExpired(config.maxTtlMillis)(header)
        _                      <- raiseExpiredDeploy(d.deployHash, header, config.maxTtlMillis).whenA(isExpired)
        hasMissingDependencies <- dependenciesMet(d).map(!_)
        _                      <- raiseDeployDependencyNotMet(d).whenA(hasMissingDependencies)
      } yield ()

    def raiseHeaderErrors(errors: List[Errors.DeployHeaderError]): F[Unit] =
      reject[F](b, InvalidDeployHeader, errors.map(_.errorMessage).mkString(". "))

    def raiseFutureDeploy(deployHash: DeployHash, header: consensus.Deploy.Header): F[Unit] = {
      val hash = deployHash.show
      reject[F](
        b,
        DeployFromFuture,
        s"block timestamp $timestamp is earlier than timestamp of deploy $hash, ${header.timestamp}"
      )
    }

    def raiseExpiredDeploy(
        deployHash: DeployHash,
        header: consensus.Deploy.Header,
        defaultMaxTTL: Int
    ): F[Unit] = {
      val hash           = deployHash.show
      val ttl            = ProtoUtil.getTimeToLive(header, defaultMaxTTL)
      val expirationTime = header.timestamp + ttl
      reject[F](
        b,
        DeployExpired,
        s"block timestamp $timestamp is later than expiration time of deploy $hash, $expirationTime"
      )
    }

    def raiseDeployDependencyNotMet(deploy: consensus.Deploy): F[Unit] =
      reject[F](
        b,
        DeployDependencyNotMet,
        s"${PrettyPrinter.buildString(deploy)} did not have all dependencies met."
      )

    deploys.traverse(singleDeployValidation).as(())
  }

  def deployHashes[F[_]: Monad: RaiseValidationError: Log](
      b: Block
  ): F[Unit] =
    b.getBody.deploys.toList.findM(d => deployHash[F](d.getDeploy).map(!_)).flatMap {
      case None =>
        Applicative[F].unit
      case Some(d) =>
        reject[F](
          b,
          InvalidDeployHash,
          s"${PrettyPrinter.buildString(d.getDeploy)} has invalid hash."
        )
    }

  def deploySignatures[F[_]: MonadThrowable: RaiseValidationError: Log](
      b: Block
  ): F[Unit] =
    b.getBody.deploys.toList
      .findM(d => deploySignature[F](d.getDeploy).map(!_))
      .flatMap {
        case None =>
          Applicative[F].unit
        case Some(d) =>
          reject[F](
            b,
            InvalidDeploySignature,
            s"${PrettyPrinter.buildString(d.getDeploy)} has invalid signature."
          )
      }
      .whenA(!b.isGenesisLike)

  def checkDroppable[F[_]: MonadThrowable: RaiseValidationError: Log](
      checks: F[Boolean]*
  ): F[Unit] =
    checks.toList.sequence
      .map(_.forall(identity))
      .ifM(
        ().pure[F],
        MonadThrowable[F] //  TODO: Raise through Functor
          .raiseError[Unit](DropErrorWrapper(InvalidUnslashableBlock))
      )

}
