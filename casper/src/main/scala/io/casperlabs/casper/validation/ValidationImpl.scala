package io.casperlabs.casper.validation

import cats.Applicative
import cats.implicits._
import cats.mtl.FunctorRaise
import cats.data.NonEmptyList
import com.google.protobuf.ByteString
import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper._
import io.casperlabs.casper.consensus.{Block, BlockSummary, Bond}
import io.casperlabs.casper.equivocations.EquivocationDetector
import io.casperlabs.casper.util.ProtoUtil.bonds
import io.casperlabs.casper.util.execengine.ExecEngineUtil
import io.casperlabs.casper.util.execengine.ExecEngineUtil.{StateHash, TransformMap}
import io.casperlabs.casper.util.{CasperLabsProtocol, ProtoUtil}
import io.casperlabs.casper.highway.ForkChoice
import io.casperlabs.catscontrib.Fs2Compiler
import io.casperlabs.metrics.Metrics
import io.casperlabs.models.Weight
import io.casperlabs.shared._
import io.casperlabs.smartcontracts.ExecutionEngineService
import io.casperlabs.storage.BlockHash
import io.casperlabs.storage.block.BlockStorage
import io.casperlabs.storage.dag.DagRepresentation
import cats.effect.Sync
import io.casperlabs.casper.consensus.state.ProtocolVersion
import io.casperlabs.smartcontracts.ExecutionEngineService.CommitResult
import io.casperlabs.models.Message
import cats.Monad

object ValidationImpl {
  def apply[F[_]](implicit ev: ValidationImpl[F]): Validation[F] = ev

  implicit val metricsSource = CasperMetricsSource / "validation"

  def metered[F[_]: Sync: Metrics](
      underlying: Validation[F]
  ): Validation[F] =
    new Validation[F] {
      override def neglectedInvalidBlock(
          block: Block,
          invalidBlockTracker: Set[BlockHash]
      ): F[Unit] =
        Metrics[F].timer("neglectedInvalidBlock")(
          underlying.neglectedInvalidBlock(block, invalidBlockTracker)
        )

      override def parents(b: Block, dag: DagRepresentation[F])(
          implicit bs: BlockStorage[F]
      ): F[ExecEngineUtil.MergeResult[TransformMap, Block]] =
        Metrics[F].timer("parents")(underlying.parents(b, dag))

      override def transactions(
          block: Block,
          preStateHash: StateHash,
          preStateBonds: Seq[Bond],
          effects: Validation.BlockEffects
      )(
          implicit ee: ExecutionEngineService[F],
          bs: BlockStorage[F],
          clp: CasperLabsProtocol[F]
      ): F[Unit] =
        Metrics[F].timer("transactions")(
          underlying.transactions(block, preStateHash, preStateBonds, effects)
        )

      override def blockFull(
          block: Block,
          dag: DagRepresentation[F],
          chainName: String,
          maybeGenesis: Option[Block]
      )(
          implicit bs: BlockStorage[F],
          versions: CasperLabsProtocol[F],
          compiler: Fs2Compiler[F]
      ): F[Unit] =
        Metrics[F].timer("blockFull")(underlying.blockFull(block, dag, chainName, maybeGenesis))

      override def blockSummary(summary: BlockSummary, chainName: String)(
          implicit versions: CasperLabsProtocol[F]
      ): F[Unit] =
        Metrics[F].timer("blockSummary")(underlying.blockSummary(summary, chainName))

      override def checkEquivocation(dag: DagRepresentation[F], block: Block): F[Unit] =
        Metrics[F].timer("checkEquivocation")(underlying.checkEquivocation(dag, block))
    }
}

abstract class ValidationImpl[F[_]: Sync: FunctorRaise[*[_], InvalidBlock]: Log: Time: Metrics](
    isHighway: Boolean
) extends Validation[F] {
  import io.casperlabs.models.BlockImplicits._
  import Validation.{ignore, raise, reject}

  type Data        = Array[Byte]
  type BlockHeight = Long

  protected def tipsFromLatestMessages(
      dag: DagRepresentation[F],
      keyBlockHash: BlockHash,
      latestMessagesHashes: Map[Estimator.Validator, Set[BlockHash]]
  ): F[NonEmptyList[BlockHash]]

  /** Check the block without executing deploys. */
  override def blockFull(
      block: Block,
      dag: DagRepresentation[F],
      chainName: String,
      maybeGenesis: Option[Block]
  )(
      implicit bs: BlockStorage[F],
      versions: CasperLabsProtocol[F],
      compiler: Fs2Compiler[F]
  ): F[Unit] = {
    val summary = BlockSummary(block.blockHash, block.header, block.signature)
    for {
      _ <- Validation.checkDroppable(
            if (block.body.isEmpty)
              ignore[F](block.blockHash, s"block body is missing.").as(false)
            else true.pure[F],
            // Validate that the sender is a bonded validator.
            maybeGenesis.fold(summary.isGenesisLike.pure[F]) { _ =>
              Validation.blockSender[F](summary)
            }
          )
      _ <- blockSummary(summary, chainName)
      // Checks that need dependencies.
      _ <- Validation.missingBlocks[F](summary)
      _ <- Validation.timestamp[F](summary)
      _ <- Validation.blockRank[F](summary, dag)
      _ <- Validation.validatorPrevBlockHash[F](summary, dag, isHighway)
      _ <- Validation.sequenceNumber[F](summary, dag)
      _ <- Validation.swimlane[F](summary, dag, isHighway)
      // TODO: Validate that blocks only have block parents and ballots have a single parent which is a block.
      // Checks that need the body.
      _ <- Validation.blockHash[F](block)
      _ <- Validation.deployCount[F](block)
      _ <- Validation.deployHashes[F](block)
      _ <- Validation.deploySignatures[F](block)
      _ <- Validation.deployHeaders[F](block, dag, chainName)
      _ <- Validation.deployUniqueness[F](block, dag)
      _ <- Validation.totalCost[F](block)
    } yield ()
  }

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
  override def parents(
      b: Block,
      dag: DagRepresentation[F]
  )(
      implicit bs: BlockStorage[F]
  ): F[ExecEngineUtil.MergeResult[ExecEngineUtil.TransformMap, Block]] = {
    def printHashes(hashes: NonEmptyList[ByteString]) =
      hashes.toList.map(PrettyPrinter.buildString).mkString("[", ", ", "]")

    val latestMessagesHashes = ProtoUtil
      .getJustificationMsgHashes(b.getHeader.justifications)

    for {
      tipHashes            <- tipsFromLatestMessages(dag, b.getHeader.keyBlockHash, latestMessagesHashes)
      _                    <- Log[F].debug(s"Estimated tips are ${printHashes(tipHashes) -> "tips"}")
      tips                 <- tipHashes.traverse(ProtoUtil.unsafeGetBlock[F])
      tipsMessages         <- Sync[F].fromTry(tips.traverse(Message.fromBlock(_)))
      merged               <- ExecEngineUtil.merge[F](tipsMessages, dag)
      computedParentHashes = merged.parents.map(_.blockHash)
      parentHashes         = ProtoUtil.parentHashes(b)
      _ <- if (parentHashes.isEmpty)
            raise(InvalidParents)
          else if (parentHashes == computedParentHashes)
            Applicative[F].unit
          else {
            val parentsString =
              parentHashes.map(PrettyPrinter.buildString).mkString(",")
            val estimateString =
              computedParentHashes.map(PrettyPrinter.buildString).mkString(",")
            val justificationString = latestMessagesHashes.values
              .map(hashes => hashes.map(PrettyPrinter.buildString).mkString("[", ",", "]"))
              .mkString(",")
            reject[F](
              b,
              InvalidParents,
              s"block parents $parentsString did not match estimate $estimateString based on justification $justificationString."
            )
          }
    } yield merged
  }

  // Validates whether received block is valid (according to that nodes logic):
  // 1) Validates whether pre state hashes match
  // 2) Runs deploys from the block
  // 3) Validates whether post state hashes match
  // 4) Validates whether bonded validators, as at the end of executing the block, match.
  override def transactions(
      block: Block,
      preStateHash: StateHash,
      preStateBonds: Seq[Bond],
      blockEffects: Validation.BlockEffects
  )(
      implicit ee: ExecutionEngineService[F],
      bs: BlockStorage[F],
      clp: CasperLabsProtocol[F]
  ): F[Unit] = {
    val blockPreState  = ProtoUtil.preStateHash(block)
    val blockPostState = ProtoUtil.postStateHash(block)
    if (preStateHash == blockPreState) {
      for {
        possibleCommitResult <- ExecEngineUtil
                                 .commitEffects[F](
                                   preStateHash,
                                   preStateBonds,
                                   block.getHeader.getProtocolVersion,
                                   blockEffects
                                 )
                                 .attempt
        //TODO: distinguish "internal errors" and "user errors"
        _ <- possibleCommitResult match {
              case Left(ex) =>
                Log[F].error(
                  s"Could not commit effects of ${PrettyPrinter.buildString(block.blockHash) -> "message"}: $ex"
                ) *>
                  raise(InvalidTransaction)
              case Right(commitResult) =>
                for {
                  _ <- reject[F](block, InvalidPostStateHash, "invalid post state hash")
                        .whenA(commitResult.postStateHash != blockPostState)
                  _ <- Validation.bondsCache[F](block, commitResult.bondedValidators)
                } yield ()
            }
      } yield ()
    } else {
      raise(InvalidPreStateHash)
    }
  }

  /**
    * If block contains an invalid justification block B and the creator of B is still bonded,
    * return a RejectableBlock. Otherwise return an IncludeableBlock.
    */
  override def neglectedInvalidBlock(
      block: Block,
      invalidBlockTracker: Set[BlockHash]
  ): F[Unit] = {
    val invalidJustifications = block.justifications.toList
      .filter { justification =>
        invalidBlockTracker.contains(justification.latestBlockHash)
      }

    val neglectedInvalidJustification = invalidJustifications.exists { justification =>
      val slashedValidatorBond =
        bonds(block).find(
          _.validatorPublicKeyHash == justification.validatorPublicKeyHash
        )
      slashedValidatorBond match {
        case Some(bond) => Weight(bond.stake) > 0
        case None       => false
      }
    }

    if (neglectedInvalidJustification) {
      reject[F](block, NeglectedInvalidBlock, "Neglected invalid justification.")
    } else {
      Applicative[F].unit
    }
  }

  /** Validate just the BlockSummary, assuming we don't have the block yet, or all its dependencies.
    * We can check that all the fields are present, the signature is fine, etc.
    * We'll need the full body to validate the block properly but this preliminary check can prevent
    * obviously corrupt data from being downloaded. */
  override def blockSummary(
      summary: BlockSummary,
      chainName: String
  )(implicit versions: CasperLabsProtocol[F]): F[Unit] = {
    val treatAsGenesis = summary.isGenesisLike
    for {
      _ <- Validation.checkDroppable[F](
            Validation.formatOfFields[F](summary, treatAsGenesis),
            Validation.version(
              summary,
              CasperLabsProtocol[F].versionAt(_)
            ),
            if (!treatAsGenesis) Validation.blockSignature[F](summary) else true.pure[F],
            if (!treatAsGenesis) Validation.validatorPublicKeyHash[F](summary) else true.pure[F]
          )
      _ <- Validation.summaryHash[F](summary)
      _ <- Validation.chainIdentifier[F](summary, chainName)
      _ <- ballot(summary)
    } yield ()
  }

  // Validates that a message that is supposed to be a ballot adheres to ballot's specification.
  private def ballot(
      b: BlockSummary
  ): F[Unit] =
    FunctorRaise[F, InvalidBlock]
      .raise[Unit](InvalidTargetHash)
      .whenA(b.getHeader.messageType.isBallot && b.getHeader.parentHashes.size != 1)

  override def checkEquivocation(dag: DagRepresentation[F], block: Block): F[Unit] =
    for {
      message <- Sync[F].fromTry(Message.fromBlock(block))
      _       <- EquivocationDetector.checkEquivocation[F](dag, message, isHighway)
    } yield ()
}

class NCBValidationImpl[F[_]: Sync: FunctorRaise[*[_], InvalidBlock]: Log: Time: Metrics]
    extends ValidationImpl[F](isHighway = false) {

  override def tipsFromLatestMessages(
      dag: DagRepresentation[F],
      keyBlockHash: BlockHash,
      latestMessagesHashes: Map[Estimator.Validator, Set[BlockHash]]
  ): F[NonEmptyList[BlockHash]] =
    EquivocationDetector.detectVisibleFromJustifications(
      dag,
      latestMessagesHashes
    ) flatMap { equivocators =>
      Estimator
        .tips[F](dag, keyBlockHash, latestMessagesHashes, equivocators)
    }
}

class HighwayValidationImpl[F[_]: Sync: FunctorRaise[*[_], InvalidBlock]: Log: Time: Metrics: ForkChoice]
    extends ValidationImpl[F](isHighway = true) {

  override def tipsFromLatestMessages(
      dag: DagRepresentation[F],
      keyBlockHash: BlockHash,
      latestMessagesHashes: Map[Estimator.Validator, Set[BlockHash]]
  ): F[NonEmptyList[BlockHash]] =
    for {
      choice <- ForkChoice[F]
                 .fromJustifications(keyBlockHash, latestMessagesHashes.values.flatten.toSet)
      tips = NonEmptyList.one(choice.block.messageHash)
    } yield tips
}
