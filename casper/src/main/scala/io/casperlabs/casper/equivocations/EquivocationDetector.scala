package io.casperlabs.casper.equivocations

import cats.Monad
import cats.implicits._
import cats.syntax.show
import cats.mtl.FunctorRaise
import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.casper.consensus.Block
import io.casperlabs.casper.dag.DagOperations
import io.casperlabs.casper.util.ProtoUtil
import io.casperlabs.casper.{CasperState, EquivocatedBlock, InvalidBlock, PrettyPrinter}
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.models.Message
import io.casperlabs.shared.{Cell, Log, LogSource, StreamT}
import io.casperlabs.storage.dag.DagRepresentation
import io.casperlabs.shared.Sorting.jRankOrdering
import io.casperlabs.shared.ByteStringPrettyPrinter.byteStringShow
import scala.collection.immutable.{Map, Set}
import com.google.protobuf.ByteString

object EquivocationDetector {

  /** !!!CAUTION!!!: Must be called before storing block in the DAG
    *
    * Check whether a new block creates an equivocation when being added to the DAG,
    * if so raise an `EquivocatedBlock` error.
    *
    * For example:
    *
    *    v0            v1             v2
    *
    *           |  b3     b4   |
    *           |    \   /     |
    *           |     b2   b5  |
    *           |      |  /    |
    *           |      b1      |
    *
    * When the node receives b4, `checkEquivocations` will detect that b4 and b3 don't cite each other;
    * in other words, b4 creates an equivocation. After a while, the node receives b5;
    * since we had added all equivocating messages to the block DAG, once
    * a validator has been detected as equivocating, then for every message M1 he creates later,
    * we can find least one message M2 that M1 and M2 don't cite each other. In other words, a block
    * created by a validator who has equivocated will create another equivocation. In this way, b5
    * doesn't cite blocks (b2, b3, and b4), and blocks (b2, b3, and b4) don't cite b5 either. So b5
    * creates equivocations.
    */
  def checkEquivocation[F[_]: Monad: Log: FunctorRaise[*[_], InvalidBlock]](
      dag: DagRepresentation[F],
      message: Message,
      isHighway: Boolean
  ): F[Unit] =
    for {
      tips <- if (isHighway) {
               dag.latestInEra(message.eraId)
             } else {
               dag.latestGlobal
             }
      validatorLatestMessages <- tips.latestMessage(message.validatorId)
      equivocated             <- isEquivocation[F](message, validatorLatestMessages)
      _                       <- FunctorRaise[F, InvalidBlock].raise[Unit](EquivocatedBlock).whenA(equivocated)
    } yield ()

  /**
    * Check whether block creates equivocations
    *
    * Caution:
    *   Always use method `checkEquivocation` instead of calling this one directly.
    *   It may not work when receiving further blocks created by a validator who has equivocated.
    *   For example:
    *
    *       |   v0   |
    *       |        |
    *       |        |
    *       |     B4 |
    *       |     |  |
    *       | B2  B3 |
    *       |  \  /  |
    *       |   B1   |
    *
    *   Local node could detect that Validator v0 has equivocated after receiving B3,
    *   then when adding B4, this method doesn't work, it returns false but actually B4
    *   equivocated with B2.
    */
  private def isEquivocation[F[_]: Monad: Log](
      message: Message,
      validatorLatestMessages: Set[Message]
  ): F[Boolean] =
    for {
      equivocated <- validatorLatestMessages.toList match {
                      case Nil =>
                        // It is the first message by that validator.
                        false.pure[F]
                      case head :: Nil =>
                        // Since we've already validated that message.prevBlockHash is correct
                        // i.e. it correctly cites latest message by the creator.
                        // And we've also validated that message creator is not merging his swimlane,
                        // a message creates an equivocation iff latest message (as seen by local node)
                        // is different from what new message cites as the previous one.
                        if (message.messageHash == head.messageHash) {
                          // Maybe this is a retry of a message that failed partially.
                          Log[F]
                            .warn(
                              s"The latest message from ${PrettyPrinter
                                .buildString(message.validatorId) -> "validator"} is the current ${message.messageHash.show -> "message"} itself."
                            )
                            .as(false)
                        } else if (message.validatorPrevMessageHash != head.messageHash) {
                          Log[F]
                            .warn(
                              s"Found equivocation: justifications of ${message.messageHash.show -> "message"} don't cite the latest message by ${PrettyPrinter
                                .buildString(message.validatorId)                                -> "validator"}: ${head.messageHash.show -> "latestMessage"}"
                            )
                            .as(true)
                        } else false.pure[F]
                      case _ =>
                        Log[F]
                          .warn(
                            s"${PrettyPrinter.buildString(message.validatorId) -> "validator"} has already equivocated in the past."
                          )
                          .as(true)
                    }
    } yield equivocated

  /**
    * Find equivocating validators that a block can see based on its direct justifications
    *
    * We use `bfToposortTraverseF` to traverse from `latestMessageHashes` down beyond the minimal rank
    * of base block of equivocationRecords. Since we have already validated `validatorBlockSeqNum`
    * equals 1 plus that of previous block created by the same validator, if we find a duplicated
    * value, we know the validator has equivocated.
    *
    * @param dag the block dag
    * @param justificationMsgHashes generate from direct justifications
    * @tparam F effect type
    * @return validators that can be seen equivocating from the view of latestMessages
    */
  def detectVisibleFromJustifications[F[_]: MonadThrowable](
      dag: DagRepresentation[F],
      justificationMsgHashes: Map[Validator, Set[BlockHash]]
  ): F[Set[Validator]] =
    for {
      equivocations <- dag.getEquivocations
      minBaseRank   = findMinBaseRank(equivocations)
      equivocators <- minBaseRank.fold(Set.empty[Validator].pure[F])(minBaseRank => {
                       for {
                         justificationMessages <- justificationMsgHashes.values.toList
                                                   .flatTraverse(_.toList.traverse(dag.lookup))
                                                   .map(_.flatten)
                         equivocators = equivocations.keySet
                         acc <- DagOperations
                                 .toposortJDagDesc[F](dag, justificationMessages)
                                 .foldWhileLeft(State()) {
                                   case (state, b) =>
                                     val creator            = b.validatorId
                                     val creatorBlockSeqNum = b.validatorMsgSeqNum
                                     if (state
                                           .allDetected(equivocators) || b.jRank <= minBaseRank) {
                                       // Stop traversal if all known equivocations has been found in j-past-cone
                                       // of `b` or we traversed beyond the minimum rank of all equivocations.
                                       Right(state)
                                     } else if (state.alreadyDetected(creator)) {
                                       Left(state)
                                     } else if (state.alreadyVisited(creator, creatorBlockSeqNum)) {
                                       Left(state.addEquivocator(creator))
                                     } else {
                                       Left(state.addVisited(creator, creatorBlockSeqNum))
                                     }
                                 }
                       } yield acc.detectedEquivocators
                     })
    } yield equivocators

  private case class State(
      detectedEquivocators: Set[Validator] = Set.empty,
      visitedBlocks: Map[Validator, Int] = Map.empty
  ) {
    def addEquivocator(v: Validator): State = copy(detectedEquivocators = detectedEquivocators + v)
    def addVisited(v: Validator, blockSeqNum: Int): State =
      copy(visitedBlocks = visitedBlocks + (v -> blockSeqNum))
    def alreadyVisited(v: Validator, blockSeqNum: Int): Boolean =
      visitedBlocks.get(v).contains(blockSeqNum)
    def alreadyDetected(v: Validator): Boolean   = detectedEquivocators.contains(v)
    def allDetected(vs: Set[Validator]): Boolean = detectedEquivocators == vs
  }

  // Finds the "base rank" of the equivocations.
  // base rank is defined as the lowest block that sees _any_ equivocation.
  def findMinBaseRank(latestMessages: Map[Validator, Set[Message]]): Option[Long] = {
    val equivocators = latestMessages.filter(_._2.size > 1)
    if (equivocators.isEmpty) None
    else Some(equivocators.values.flatten.minBy(_.jRank).jRank - 1)
  }

}
