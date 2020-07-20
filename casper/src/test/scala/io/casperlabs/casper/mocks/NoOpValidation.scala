package io.casperlabs.casper.mocks

import cats._
import cats.implicits._
import cats.mtl.FunctorRaise
import io.casperlabs.casper.validation.Validation, Validation.BlockEffects
import io.casperlabs.casper.consensus.{Block, BlockSummary, Bond}
import io.casperlabs.casper.util.execengine.ExecEngineUtil, ExecEngineUtil.StateHash
import io.casperlabs.casper.util.CasperLabsProtocol
import io.casperlabs.casper.InvalidBlock
import io.casperlabs.storage.BlockHash
import io.casperlabs.storage.dag.DagRepresentation
import io.casperlabs.storage.block.BlockStorage
import io.casperlabs.smartcontracts.ExecutionEngineService
import io.casperlabs.catscontrib.Fs2Compiler

class NoOpValidation[F[_]: Applicative] extends Validation[F] {

  override def neglectedInvalidBlock(
      block: Block,
      invalidBlockTracker: Set[BlockHash]
  ): F[Unit] =
    ().pure[F]

  override def parents(
      b: Block,
      dag: DagRepresentation[F]
  )(
      implicit bs: BlockStorage[F]
  ): F[ExecEngineUtil.MergeResult[ExecEngineUtil.TransformMap, Block]] =
    ExecEngineUtil.MergeResult.empty.pure[F]

  override def transactions(
      block: Block,
      preStateHash: StateHash,
      preStateBonds: Seq[Bond],
      effects: BlockEffects
  )(
      implicit ee: ExecutionEngineService[F],
      bs: BlockStorage[F],
      clp: CasperLabsProtocol[F]
  ): F[Unit] = ().pure[F]

  override def blockFull(
      block: Block,
      dag: DagRepresentation[F],
      chainName: String,
      maybeGenesis: Option[Block]
  )(
      implicit bs: BlockStorage[F],
      versions: CasperLabsProtocol[F],
      compiler: Fs2Compiler[F]
  ): F[Unit] = ().pure[F]

  override def blockSummary(
      summary: BlockSummary,
      chainName: String
  )(implicit versions: CasperLabsProtocol[F]): F[Unit] = ().pure[F]

  override def checkEquivocation(dag: DagRepresentation[F], block: Block): F[Unit] = ().pure[F]
}
