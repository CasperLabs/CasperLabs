package io.casperlabs.casper.finality.votingmatrix

import cats.effect.Concurrent
import cats.effect.concurrent.Semaphore
import cats.implicits._
import cats.mtl.MonadState
import cats.Monad
import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper.PrettyPrinter
import io.casperlabs.casper.finality.{
  CommitteeWithConsensusValue,
  FinalityDetector,
  FinalityDetectorUtil
}
import io.casperlabs.casper.finality.votingmatrix.FinalityDetectorVotingMatrix._votingMatrixS
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.models.Message
import io.casperlabs.shared.Log
import io.casperlabs.storage.dag.DagRepresentation
import io.casperlabs.storage.dag.AncestorsStorage

class FinalityDetectorVotingMatrix[F[_]: Concurrent: Log: AncestorsStorage] private (
    rFTT: Double,
    isHighway: Boolean
)(
    implicit private val matrix: _votingMatrixS[F]
) extends FinalityDetector[F] {

  override def addMessage(
      dag: DagRepresentation[F],
      message: Message,
      latestFinalizedBlock: BlockHash
  ): F[Unit] = {
    val highwayCheck = dag
      .getEquivocatorsInEra(
        message.eraId
      )
      .map(_.contains(message.validatorId))

    val ncbCheck = dag.getEquivocators.map(_.contains(message.validatorId))

    val isEquivocator = if (isHighway) highwayCheck else ncbCheck

    isEquivocator
      .ifM(
        Log[F].debug(
          s"Message ${PrettyPrinter.buildString(message.messageHash) -> "message"} is from an equivocator ${PrettyPrinter
            .buildString(message.validatorId)                        -> "validator"}"
        ),
        for {
          votedBranch <- io.casperlabs.casper.finality
                          .votedBranch[F](dag, latestFinalizedBlock, message)
          _ <- votedBranch match {
                case Some(lfbChild) =>
                  // Check if the vote (message) is in different era than LFB's child it votes for.
                  // We disallow validators from different era to advance the LFB chain.
                  val votedBranchIsDifferentEra = isHighway && lfbChild.eraId != message.eraId
                  val lfbChildHash              = lfbChild.messageHash
                  for {
                    _ <- if (votedBranchIsDifferentEra)
                          Log[F].debug(
                            s"${PrettyPrinter.buildString(message.messageHash) -> "Message"} from ${PrettyPrinter
                              .buildString(message.eraId)                      -> "era"} votes on an LFB child ${PrettyPrinter
                              .buildString(lfbChildHash)                       -> "hash"} from a different era."
                          )
                        else
                          for {
                            lfb                                          <- dag.lookupBlockUnsafe(latestFinalizedBlock)
                            validators: Set[DagRepresentation.Validator] = lfb.weightMap.keySet
                            panorama <- FinalityDetectorUtil
                                         .panoramaOfBlockByValidators[F](
                                           dag,
                                           message,
                                           lfb,
                                           validators
                                         )
                            _ <- matrix.withPermit(
                                  updateVoterPerspective[F](
                                    dag,
                                    message,
                                    panorama,
                                    lfbChildHash,
                                    isHighway
                                  )
                                )
                          } yield ()

                  } yield ()
                // If block doesn't vote on any of main children of latestFinalizedBlock,
                // then don't update voting matrix
                case None =>
                  Log[F]
                    .info(
                      s"The ${PrettyPrinter.buildString(message.messageHash) -> "message"} doesn't vote any main child of ${PrettyPrinter
                        .buildString(latestFinalizedBlock)                   -> "latestFinalizedBlock"}"
                    )
                    .void
                    .whenA(message.parentBlock != latestFinalizedBlock)
              }
        } yield ()
      )
  }

  override def checkFinality(dag: DagRepresentation[F]): F[Seq[CommitteeWithConsensusValue]] = {
    val checkFinality: F[Option[CommitteeWithConsensusValue]] =
      checkForCommittee[F](dag, rFTT, isHighway)
        .flatTap(_.traverse { newLFB =>
          val isBlock: F[Boolean] =
            dag
              .lookupUnsafe(newLFB.consensusValue)
              .map(_.isBlock)

          // On new LFB (but only if it's a block) we rebuild VotingMatrix and start the new game.
          Monad[F].ifM(isBlock)(
            for {
              newVotingMatrix <- VotingMatrix
                                  .create[F](
                                    dag,
                                    newLFB.consensusValue,
                                    isHighway
                                  )
                                  .flatMap(_.get)
              _ <- matrix.set(newVotingMatrix)
            } yield (),
            Log[F]
              .warn(
                s"New LFB is a ballot: ${PrettyPrinter
                  .buildString(newLFB.consensusValue) -> "message"}"
              )
          )
        })

    // It may be the case that after adding one message to the DAG multiple blocks are being finalized.
    def checkFinalityLoop: F[Seq[CommitteeWithConsensusValue]] =
      checkFinality.flatMap {
        case None => Seq.empty[CommitteeWithConsensusValue].pure[F]
        case Some(committee) =>
          checkFinalityLoop.map(tail => committee +: tail)
      }

    matrix.withPermit(checkFinalityLoop)
  }
}

object FinalityDetectorVotingMatrix {
  def apply[F[_]](
      implicit detector: FinalityDetectorVotingMatrix[F]
  ): FinalityDetectorVotingMatrix[F] =
    detector

  type _votingMatrixS[F[_]] = MonadState[F, VotingMatrixState] with Semaphore[F]

  private def synchronizedVotingMatrix[F[_]: Monad](
      lock: Semaphore[F],
      state: MonadState[F, VotingMatrixState]
  ): _votingMatrixS[F] =
    new Semaphore[F] with MonadState[F, VotingMatrixState] {

      override def available: F[Long]               = lock.available
      override def count: F[Long]                   = lock.count
      override def acquireN(n: Long): F[Unit]       = lock.acquireN(n)
      override def tryAcquireN(n: Long): F[Boolean] = lock.tryAcquireN(n)
      override def releaseN(n: Long): F[Unit]       = lock.releaseN(n)
      override def withPermit[A](t: F[A]): F[A]     = lock.withPermit(t)

      override val monad: Monad[F]                                            = Monad[F]
      override def get: F[VotingMatrixState]                                  = state.get
      override def set(s: VotingMatrixState): F[Unit]                         = state.set(s)
      override def inspect[A](f: VotingMatrixState => A): F[A]                = state.inspect(f)
      override def modify(f: VotingMatrixState => VotingMatrixState): F[Unit] = state.modify(f)
    }

  /** Constructs an instance of FinalityDetector based on VotingMatrix, starting from `finalizedBlock`.
    *
    * NOTE: Raises an error if rFTT parameters is less than 0 or bigger than 0.5.
    */
  def of[F[_]: Concurrent: Log: AncestorsStorage](
      dag: DagRepresentation[F],
      finalizedBlock: BlockHash,
      rFTT: Double,
      isHighway: Boolean
  ): F[FinalityDetectorVotingMatrix[F]] =
    for {
      _ <- MonadThrowable[F]
            .raiseError(
              io.casperlabs.shared.FatalErrorShutdown(
                s"Relative FTT has to be bigger than 0 and less than 0.5. Got: $rFTT"
              )
            )
            .whenA(rFTT < 0 || rFTT > 0.5)
      lock                 <- Semaphore[F](1)
      votingMatrix         <- VotingMatrix.create[F](dag, finalizedBlock, isHighway)
      votingMatrixWithLock = synchronizedVotingMatrix(lock, votingMatrix)
    } yield new FinalityDetectorVotingMatrix[F](rFTT, isHighway) (
      Concurrent[F],
      Log[F],
      AncestorsStorage[F],
      votingMatrixWithLock
    )
}
