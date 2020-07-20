package io.casperlabs.casper.highway

import cats.data.NonEmptyList
import cats.effect.Sync
import cats.implicits._
import com.github.ghik.silencer.silent
import com.google.protobuf.ByteString
import io.casperlabs.casper.Estimator
import io.casperlabs.casper.util.ProtoUtil
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.crypto.Keys.{PublicKey, PublicKeyBS, PublicKeyHash}
import io.casperlabs.models.Message.Block
import io.casperlabs.models.{Message, Weight}
import io.casperlabs.metrics.implicits._
import io.casperlabs.metrics.Metrics
import io.casperlabs.storage.BlockHash
import io.casperlabs.storage.dag.DagRepresentation._
import io.casperlabs.storage.dag.{DagLookup, DagRepresentation, DagStorage}
import io.casperlabs.storage.era.EraStorage
import simulacrum.typeclass
import io.casperlabs.casper.consensus.Bond
import io.casperlabs.casper.dag.{DagOperations, EraObservedBehavior}
import io.casperlabs.storage.dag.AncestorsStorage
import io.casperlabs.storage.dag.AncestorsStorage.Relation

/** Some sort of stateful, memoizing implementation of a fast fork choice.
  * Should have access to the era tree to check what are the latest messages
  * along the path, who are the validators in each era, etc.
  */
@typeclass
trait ForkChoice[F[_]] {

  /** Execute the fork choice based on a key block of an era:
    * - go from the key block to the switch block of the era, using the validators in that era
    * - go from the switch block using the next era's validators to the end of the next era
    * - repeat until the we arrive at the tips
    * - return the fork choice block along with all the justifications taken into account.
    *
    * `keyBlockHash` is the identifier of the era in which we are seeking the
    * fork choice. The key block itself will have an era ID, which the implementation
    * can use to consult the `DagStorage` to find out the latest messages in that era.
    *
    * All the switch blocks based on the same keyblock lead to the same child era.
    * At some point the algorithm can find which switch block is the fork choice,
    * and carry on towards the latest messages in the era corresponding to `keyBlockHash`,
    * but stop there, without recursing into potential child eras.
    */
  def fromKeyBlock(keyBlockHash: BlockHash): F[ForkChoice.Result]

  /** Calculate the fork choice from a set of known blocks. This can be used
    * either to validate the main parent of an incoming block, or to pick a
    * target for a lambda response, given the lambda message and the validator's
    * own latest message as justifications.
    *
    * `keyBlockHash` is passed because in order to be able to validate that a
    * fork choice is correct we need to know what era it started the evaluation
    * from; for example if we don't have a block in the child era yet, just
    * the ballots that vote on the switch block in the parent era, we have to
    * know that the fork choice was run from the grandparent or the great-
    * grandparent era, and the parent-era justifications on their own don't
    * indicate this.
    */
  def fromJustifications(
      keyBlockHash: BlockHash,
      justifications: Set[BlockHash]
  ): F[ForkChoice.Result]
}

object ForkChoice {
  import DagRepresentation.Validator

  case class Result(
      block: Message.Block,
      // The fork choice must take into account messages from the parent
      // era's voting period as well, in order to be able to tell which
      // switch block in the end of the era to build on, and so which
      // blocks in the child era to follow. The new block we build
      // on top of the main parent can cite all these justifications.
      justifications: Set[Message]
  ) {
    lazy val justificationsMap: Map[Validator, Set[Message]] =
      justifications.toSeq
        .map(j => j.validatorId -> j)
        .groupBy(_._1)
        .mapValues(_.map(_._2).toSet)
  }

  def create[F[_]: Sync: Metrics: EraStorage: DagStorage: AncestorsStorage]: ForkChoice[F] =
    new ForkChoice[F] {

      implicit val metricsSource = HighwayMetricsSource / "ForkChoice"

      /**
        * Computes fork choice within single era.
        *
        * @param dag
        * @param keyBlock key block of the era.
        * @param eraStartBlock block where fork choice starts from. Note that it may not
        *                      necessarily belong to the same era as key block (see switch blocks).
        * @param latestMessages validators' latest messages in that era.
        * @param equivocators known equivocators. Using latest messages is not enough since
        *                     we have a "forgiveness period" which states that a validator
        *                     is an equivocator in the era he equivocated and `n` descendant eras.
        * @return fork choice and a set of reduced justifications.
        */
      private def eraForkChoice(
          dag: DagRepresentation[F],
          keyBlock: Message.Block,
          eraStartBlock: Block,
          latestMessages: Map[Validator, Set[Message]],
          equivocators: Set[Validator]
      ): F[(Block, Map[Validator, Set[Message]])] =
        for {
          weights <- EraStorage[F]
                      .getEraUnsafe(keyBlock.messageHash)
                      .map(_.bonds.map {
                        case Bond(validator, stake) => PublicKeyHash(validator) -> Weight(stake)
                      }.toMap)
          honestValidators = weights.keys.toSet.filterNot(equivocators(_))
          latestHonestMessages = latestMessages.collect {
            // It may be the case that validator is honest in current era,
            // but equivocated in the past and we haven't yet forgiven him.
            case (v, lms) if lms.size == 1 && honestValidators.contains(v) =>
              v -> lms.head
          }
          forkChoice <- MonadThrowable[F].tailRecM(eraStartBlock) { startBlock =>
                         for {
                           // Collect latest messages from honest validators that vote for the block.
                           relevantMessages <- honestValidators.toList
                                                .foldLeftM(Map.empty[Validator, Message]) {
                                                  case (acc, v) =>
                                                    latestHonestMessages
                                                      .get(v)
                                                      .fold(acc.pure[F]) { latestMessage =>
                                                        previousVoteForDescendant(
                                                          dag,
                                                          latestMessage,
                                                          startBlock
                                                        ).map(_.fold(acc) { vote =>
                                                            acc.updated(v, vote)
                                                          })
                                                          .timerGauge("previousVoteForDescendant")
                                                      }
                                                }
                                                .timerGauge("relevantMessages")

                           scores <- relevantMessages.toList
                                      .foldLeftM(Scores.init(startBlock)) {
                                        case (scores, (v, msg)) =>
                                          msg match {
                                            case block: Message.Block =>
                                              // A block is a vote for itself.
                                              // Even if it's an omega-block, it votes for its parent as well,
                                              // and itself can become the fork choice.
                                              scores.update(block, weights(v)).pure[F]

                                            case ballot: Message.Ballot
                                                if ballot.parentBlock != startBlock.messageHash =>
                                              // Ballot votes for its parent.
                                              dag
                                                .lookupBlockUnsafe(ballot.parentBlock)
                                                .map(block => scores.update(block, weights(v)))

                                            case ballot: Message.Ballot
                                                if ballot.parentBlock == startBlock.messageHash =>
                                              // Ignore ballots that vote for the start block directly.
                                              // They don't advance the fork choice.
                                              scores.pure[F]
                                          }

                                      }
                           result <- if (scores.isEmpty)
                                      // No one voted for anything - there are no descendants of `start`.
                                      startBlock.asRight[Block].pure[F]
                                    else
                                      scores
                                        .tip[F](Sync[F], dag)
                                        .map(_.asLeft[Block])
                         } yield result
                       }
        } yield (forkChoice, latestMessages)

      /**
        * Computes the fork choice across multiple eras (as defined by `keyBlock`).
        *
        * @param startBlock Starting block for the fork choice (already known DAG tip).
        * @param keyBlocks List of eras over which we will calculate the fork choice.
        * @param dagView
        * @param dag
        * @return Main parent and set of justifications.
        */
      private def erasForkChoice(
          startBlock: Message.Block,
          keyBlocks: List[Message.Block],
          dagView: EraObservedBehavior[Message]
      )(
          implicit dag: DagRepresentation[F]
      ): F[(Block, Map[Validator, Set[Message]])] =
        keyBlocks
          .foldM(
            startBlock -> Map
              .empty[Validator, Set[Message]]
          ) {
            case ((startBlock, accLatestMessages), currKeyBlock) =>
              val eraLatestMessages = dagView
                .latestMessagesInEra(
                  currKeyBlock.messageHash
                )
              val visibleEquivocators = dagView
                .equivocatorsVisibleInEras(
                  // TODO: CON-633
                  // Equivocator count only in era he equivocated
                  Set(currKeyBlock.messageHash)
                )
              for {
                (forkChoice, eraLatestMessagesReduced) <- eraForkChoice(
                                                           dag,
                                                           currKeyBlock,
                                                           startBlock,
                                                           eraLatestMessages,
                                                           visibleEquivocators
                                                         ).timerGauge("eraForkChoice")
              } yield (
                forkChoice,
                accLatestMessages |+| eraLatestMessagesReduced
              )
          }

      override def fromKeyBlock(keyBlockHash: BlockHash): F[Result] =
        for {
          implicit0(dag: DagRepresentation[F]) <- DagStorage[F].getRepresentation
          keyBlock                             <- dag.lookupBlockUnsafe(keyBlockHash)
          keyBlocks <- MessageProducer
                        .collectKeyBlocks[F](keyBlockHash)
                        .timer("fromKeyBlock_collectKeyBlocks")
          erasLatestMessages <- DagOperations
                                 .latestMessagesInEras[F](dag, keyBlocks)
                                 .map(EraObservedBehavior.local(_))
                                 .timerGauge("fromKeyBlock_latestMessagesInEras")
          (forkChoice, justifications) <- erasForkChoice(keyBlock, keyBlocks, erasLatestMessages)
                                           .timerGauge("fromKeyBlock_erasForkChoice")
        } yield Result(forkChoice, justifications.values.flatten.toSet)

      override def fromJustifications(
          keyBlockHash: BlockHash,
          justifications: Set[BlockHash]
      ): F[Result] =
        for {
          implicit0(dag: DagRepresentation[F]) <- DagStorage[F].getRepresentation
          keyBlock                             <- dag.lookupBlockUnsafe(keyBlockHash)
          // Build a local view of the DAG.
          // We can use it to optimize calculations of the block's panorama.
          erasObservedBehaviors <- DagOperations
                                    .latestMessagesInErasUntil[F](keyBlock.messageHash)
                                    .map(EraObservedBehavior.local(_))
                                    .timerGauge("fromJustifications_eraObservedBehaviors")
          justificationsMessages <- justifications.toList.traverse(dag.lookupUnsafe)
          panoramaOfTheBlock <- DagOperations
                                 .messageJPast[F](
                                   dag,
                                   justificationsMessages,
                                   erasObservedBehaviors
                                 )
                                 .timerGauge("fromJustifications_messageJPast")
          keyBlocks <- MessageProducer.collectKeyBlocks[F](keyBlockHash)
          (forkChoice, forkChoiceJustifications) <- erasForkChoice(
                                                     keyBlock,
                                                     keyBlocks,
                                                     panoramaOfTheBlock
                                                   ).timerGauge("fromJustifications_erasForkChoice")
        } yield Result(forkChoice, forkChoiceJustifications.values.flatten.toSet)
    }

  /** Returns previous message by the creator of `latestMessage` that is a descendant
    * (in the main tree) of the target block. If such message doesn't exists - returns `None`.
    *
    * Validator can "change his mind". His latest message may be a descendant of different
    * block than his second to last message.
    */
  private def previousVoteForDescendant[F[_]: MonadThrowable: AncestorsStorage](
      dag: DagLookup[F],
      latestMessage: Message,
      target: Block
  ): F[Option[Message]] =
    if (latestMessage.mainRank <= target.mainRank) none[Message].pure[F]
    else {
      DagOperations
        .relation[F](latestMessage, target)
        .flatMap {
          case None =>
            Option(latestMessage.validatorPrevMessageHash)
              .filterNot(_ == ByteString.EMPTY)
              .fold(none[Message].pure[F]) { prevMsgHash =>
                dag
                  .lookupUnsafe(prevMsgHash)
                  .flatMap(previousVoteForDescendant(dag, _, target))
              }
          case Some(Relation.Ancestor | Relation.Equal) =>
            none.pure[F]
          case Some(Relation.Descendant) =>
            latestMessage.some.pure[F]
        }
    }

  /* The scores map keeps track of the votes for a descendant of `start` by height.
   * This allows us to skip multiple descendants from `start`
   * if there are enough votes farther up the main tree.
   *
   * This is true because later votes have to be in the same main tree as theirs ancestors.
   * We need to find rank of the highest block that is plurality driven (has most votes).
   * If we know that block rank=5 has >50% of total votes we don't have to check its ancestors.
   */
  private[highway] case class Scores(
      scores: Map[Scores.Height, Map[BlockHash, Weight]],
      stopHeight: Scores.Height
  ) {
    // Update weight of votes at height.
    def update(vote: Block, weight: Weight): Scores = {
      val currVotes = scores.getOrElse(vote.mainRank, Map.empty[BlockHash, Weight])
      val currScore = currVotes.getOrElse(vote.messageHash, Weight.Zero)
      val newScore  = currScore + weight
      val newVotes  = currVotes.updated(vote.messageHash, newScore)
      copy(scores.updated(vote.mainRank, newVotes))
    }

    private def removeHeight(height: Scores.Height): Scores =
      copy(scores - height)

    def votesAtHeight(height: Scores.Height): Map[BlockHash, Weight] =
      scores.getOrElse(height, Map.empty)

    lazy val totalWeight: Weight = scores.valuesIterator.flatMap(_.valuesIterator).sum
    def maxHeight: Scores.Height = scores.keysIterator.max
    def isEmpty: Boolean         = scores.isEmpty

    /**
      * Finds the tip of the accumulated scores map.
      */
    def tip[F[_]: Sync](implicit dag: DagLookup[F]): F[Block] =
      Scores
        .findTip[F](maxHeight, this)
        .flatMap(dag.lookupBlockUnsafe(_))
  }

  object Scores {
    type Height = Long
    def init(startBlock: Message.Block): Scores = Scores(Map.empty, startBlock.mainRank + 1)

    def findTip[F[_]: DagLookup: Sync](
        currHeight: Scores.Height,
        scores: Scores
    ): F[BlockHash] =
      if (currHeight == scores.stopHeight) {
        // We reached the starting block. This means there is no block that has majority of votes.
        // Return a child of starting block that has the highest score.
        import DagOperations.bigIntByteStringOrdering
        scores.votesAtHeight(currHeight).toList.map(_.swap).max(bigIntByteStringOrdering)._2.pure[F]
      } else {
        scores.votesAtHeight(currHeight).toList.filter {
          case (_, weight) =>
            2 * weight >= scores.totalWeight
        } match {
          case Nil =>
            // Not enough weight at this height. We need to go deeper
            // Propagate this height weights downward. That's safe b/c
            // if a validator voted for ancestor of a block it also voted for the block itself.
            val newScores: F[Scores] = {
              val currHeightVotes = scores.votesAtHeight(currHeight)
              currHeightVotes.toList.foldM(scores.removeHeight(currHeight)) {
                case (acc, (hash, weight)) =>
                  for {
                    msg    <- DagLookup[F].lookupUnsafe(hash)
                    parent <- DagLookup[F].lookupBlockUnsafe(msg.parentBlock)
                  } yield acc.update(parent, weight)
              }
            }

            newScores.flatMap(findTip[F](currHeight - 1, _))
          case List((b1, _), (b2, _)) =>
            import io.casperlabs.shared.Sorting.byteStringOrdering
            // Two blocks have weight greater or equal than half ot the total weight.
            // That's possible iff they both have 50% of totalWeight.
            // Pick the higher one using ByteString ordering.
            List(b1, b2).max(byteStringOrdering).pure[F]
          case (hash, _) :: Nil =>
            hash.pure[F]
          case _ =>
            Sync[F].raiseError(
              new IllegalStateException(
                "More than two blocks had at least half of the total scores."
              )
            )
        }
      }
  }
}

/** A component which can be notified when an era higher up in the tree has a new message. */
@typeclass
trait ForkChoiceManager[F[_]] extends ForkChoice[F] {

  /** Tell the fork choice that deals with a given era that an ancestor era has a new message. */
  def updateLatestMessage(
      // The era in which we want to update the latest message.
      keyBlockHash: BlockHash,
      // The latest message in the ancestor era that must be taken into account from now.
      message: Message
  ): F[Unit]
}

object ForkChoiceManager {
  def create[F[_]: Sync: EraStorage: DagStorage: Metrics: AncestorsStorage]
      : ForkChoiceManager[F] = {
    val fc = ForkChoice.create[F]
    new ForkChoiceManager[F] {
      // TODO (CON-636): Implement the ForkChoiceManager.
      override def updateLatestMessage(keyBlockHash: BlockHash, message: Message): F[Unit] =
        Sync[F].unit

      override def fromJustifications(
          keyBlockHash: BlockHash,
          justifications: Set[BlockHash]
      ): F[ForkChoice.Result] =
        fc.fromJustifications(keyBlockHash, justifications)

      override def fromKeyBlock(keyBlockHash: BlockHash): F[ForkChoice.Result] =
        fc.fromKeyBlock(keyBlockHash)
    }
  }

}
