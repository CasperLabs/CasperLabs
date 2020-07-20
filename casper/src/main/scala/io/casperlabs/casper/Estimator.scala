package io.casperlabs.casper

import cats.Monad
import cats.data.NonEmptyList
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.util.ProtoUtil.weightFromValidatorByDag
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.metrics.Metrics
import io.casperlabs.metrics.implicits._
import io.casperlabs.models.{Message, Weight}
import io.casperlabs.storage.dag.{DagLookup, DagRepresentation}
import io.casperlabs.shared.{Log, Sorting}
import Sorting.{byteStringOrdering, jRankOrdering, publicKeyHashOrdering}
import io.casperlabs.casper.dag.DagOperations

import scala.collection.immutable.Map

object Estimator {
  type BlockHash = ByteString
  type Validator = DagRepresentation.Validator

  import Weight._

  implicit val metricsSource = CasperMetricsSource
  val increasingOrder        = jRankOrdering

  def tips[F[_]: MonadThrowable: Metrics: Log](
      dag: DagRepresentation[F],
      lfbHash: BlockHash,
      latestMessageHashes: Map[Validator, Set[BlockHash]],
      equivocators: Set[Validator]
  ): F[NonEmptyList[BlockHash]] =
    for {
      lfb <- dag.lookupUnsafe(lfbHash)
      latestMessages <- latestMessageHashes.values.flatten.toList
                         .traverse(dag.lookupUnsafe(_))
                         .map(_.filterNot(_.jRank < lfb.jRank)) // Filter out messages that are older than LFB.
                         .map(NonEmptyList.fromList(_).getOrElse(NonEmptyList.one(lfb)))
      latestMessagesByV = latestMessages.groupBy(_.validatorId)(
        cats.Order.fromOrdering[Validator]
      )
      lfbDistance = latestMessages.toList.maxBy(_.jRank)(increasingOrder).jRank - lfb.jRank
      _           <- Metrics[F].record("lfbDistance", lfbDistance)
      scores <- lmdScoring(
                 dag,
                 lfb.messageHash,
                 latestMessagesByV.mapValues(_.toList.map(_.messageHash).toSet),
                 equivocators
               ).timer("lmdScoring")
      newMainParent <- forkChoiceTip(dag, lfb.messageHash, scores).timer("forkChoiceTip")
      parents <- tipsOfLatestMessages[F](
                  dag,
                  latestMessages,
                  lfb.messageHash
                ).timer("tipsOfLatestMessages")
      secondaryParents = parents.filter(_.messageHash != newMainParent).filterNot { message =>
        // Filter out blocks created by equivocators from the secondary parents.
        // Secondary parents are not subject to the fork choice rule, the only requirement
        // is that they don't conflict with the main chain. This opens up possibility for various
        // kinds of attacks. An example could be a block created in the past, that includes a deploy
        // that should have expired by now, if that block did not conflict with the main parent
        // fork-choice would include it in the p-dag.
        equivocators.contains(message.validatorId)
      }
      sortedSecParents = secondaryParents
        .sortBy(b => scores.getOrElse(b.messageHash, Zero) -> b.messageHash.toStringUtf8)
        .reverse
    } yield NonEmptyList(newMainParent, sortedSecParents.map(_.messageHash))

  /** Eliminate any latest message which has a descendant which is a latest message
    * of another validator, because in that case those descendants should be the tips. */
  def tipsOfLatestMessages[F[_]: MonadThrowable](
      dag: DagLookup[F],
      latestMessages: NonEmptyList[Message],
      stopHash: BlockHash
  ): F[List[Message]] = {
    // Start from the highest latest messages and traverse backwards
    implicit val ord = DagOperations.blockTopoOrderingDesc
    val minRank      = latestMessages.map(_.jRank).toList.min
    for {
      tips <- DagOperations
               .bfToposortTraverseF[F](latestMessages.toList)(
                 _.parents.toList.traverse(dag.lookupUnsafe(_))
               )
               .takeWhile(_.jRank >= minRank)
               .takeUntil(_.messageHash == stopHash)
               // We start with the tips and remove any message
               // that is reachable through the parent-child link from other tips.
               // This should leave us only with the tips that cannot be reached from others.
               .foldLeft(latestMessages.toList.toSet) {
                 case (tips, message) =>
                   tips.filterNot(msg => message.parents.toSet.contains(msg.messageHash))
               }
    } yield tips.toList
  }

  /** Computes scores for LMD GHOST.
    *
    * Starts at the latest messages from currently bonded validators
    * and traverses up to the stop hash, collecting blocks' scores
    * (which is the weight of validators who include that block as main parent of their block).
    *
    * @param stopHash Block at which we stop computing scores. Should be latest common ancestor of `latestMessagesHashes`.
    * @return Scores map.
    */
  def lmdScoring[F[_]: MonadThrowable](
      dag: DagRepresentation[F],
      stopHash: BlockHash,
      latestMessageHashes: Map[Validator, Set[BlockHash]],
      equivocatingValidators: Set[Validator]
  ): F[Map[BlockHash, Weight]] = {
    implicit val messageOrder = DagOperations.blockTopoOrderingDesc
    latestMessageHashes.toList.foldLeftM(Map.empty[BlockHash, Weight]) {
      case (acc, (validator, latestMessageHashes)) =>
        for {
          sortedMessages <- latestMessageHashes.toList
                             .traverse(dag.lookup(_))
                             .map(_.flatten.sortBy(_.jRank))
          lmdScore <- DagOperations
                       .bfToposortTraverseF[F](sortedMessages)(
                         _.parents.take(1).toList.traverse(dag.lookupUnsafe(_))
                       )
                       .takeUntil(_.messageHash == stopHash)
                       .foldLeftF(acc) {
                         case (acc2, message) =>
                           (if (equivocatingValidators.contains(validator)) {
                              Zero.pure[F]
                            } else {
                              weightFromValidatorByDag(dag, message.messageHash, validator)
                            }).map { realWeight =>
                             val oldValue = acc2.getOrElse(message.messageHash, Zero)
                             acc2.updated(message.messageHash, realWeight + oldValue)
                           }
                       }
        } yield lmdScore
    }
  }

  /**
    * Computes fork choice.
    *
    * @param dag Representation of the Block DAG.
    * @param startingBlock Starting block for the fork choice rule.
    * @param scores Map of block's scores.
    * @return Block hash chosen by the fork choice rule.
    */
  def forkChoiceTip[F[_]: Monad](
      dag: DagRepresentation[F],
      startingBlock: BlockHash,
      scores: Map[BlockHash, Weight]
  ): F[BlockHash] =
    dag.getMainChildren(startingBlock).flatMap { mainChildren =>
      {
        // make sure they are reachable from latestMessages
        val reachableMainChildren = mainChildren.filter(scores.contains)
        if (reachableMainChildren.isEmpty) {
          startingBlock.pure[F]
        } else {
          val highestScoreChild =
            reachableMainChildren.maxBy(b => scores(b) -> b)(DagOperations.bigIntByteStringOrdering)
          forkChoiceTip[F](
            dag,
            highestScoreChild,
            scores
          )
        }
      }
    }

}
