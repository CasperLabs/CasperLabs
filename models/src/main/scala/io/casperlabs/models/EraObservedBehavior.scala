package io.casperlabs.casper.dag

import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.models.ObservedValidatorBehavior.{Empty, Equivocated, Honest}
import io.casperlabs.crypto.Keys.PublicKeyHashBS
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.models.{BlockHash, EraId, Message, ObservedValidatorBehavior}
import shapeless.tag.@@
import io.casperlabs.shared.ByteStringPrettyPrinter._

import scala.util.control.NoStackTrace

final class EraObservedBehavior[A] private (
    val data: Map[EraId, Map[PublicKeyHashBS, ObservedValidatorBehavior[A]]]
) {
  private lazy val equivocators: Map[EraId, Set[PublicKeyHashBS]] =
    data
      .collect {
        case (keyBlockHash, lms) if keyBlockHashes(keyBlockHash) =>
          keyBlockHash -> lms.filter(_._2.isEquivocated).keySet
      }
  val erasValidators: Map[EraId, Set[PublicKeyHashBS]] = data.mapValues(_.keySet)

  // Returns set of equivocating validators that are visible in the j-past-cone
  // of the era.
  def equivocatorsVisibleInEras(
      keyBlockHashes: Set[EraId]
  ): Set[PublicKeyHashBS] =
    keyBlockHashes.foldLeft(Set.empty[PublicKeyHashBS])(_ ++ equivocators(_))

  def keyBlockHashes: Set[EraId] = data.keySet

  def validatorsInEra(keyBlockHash: EraId): Set[PublicKeyHashBS] =
    data.get(keyBlockHash).fold(Set.empty[PublicKeyHashBS])(_.keySet)

  def latestMessagesInEra(
      keyBlockHash: EraId
  ): Map[PublicKeyHashBS, Set[A]] =
    data.get(keyBlockHash) match {
      case None => Map.empty[PublicKeyHashBS, Set[A]]
      case Some(lms) =>
        lms.mapValues {
          case Empty               => Set.empty[A]
          case Honest(msg)         => Set(msg)
          case Equivocated(m1, m2) => Set(m1, m2)
        }
    }

  def filter(f: EraId => Boolean): EraObservedBehavior[A] =
    new EraObservedBehavior[A](data.filterKeys(f))

  def getEra(keyBlockHash: EraId): Map[PublicKeyHashBS, ObservedValidatorBehavior[A]] =
    data.getOrElse(keyBlockHash, Map.empty)

  def getStatus(
      keyBlockHash: EraId,
      validator: PublicKeyHashBS
  ): Option[ObservedValidatorBehavior[A]] =
    data.get(keyBlockHash).flatMap(_.get(validator))
}

object EraObservedBehavior {

  type LocalDagView[A] = EraObservedBehavior[A] @@ LocalView
  type MessageJPast[A] = EraObservedBehavior[A] @@ MessageView

  def local(data: Map[EraId, Map[PublicKeyHashBS, Set[Message]]]): LocalDagView[Message] =
    apply(data).asInstanceOf[LocalDagView[Message]]

  // An enum that describes validator's observed state.
  // When we traverse the j-past-cone of a message we will start from the tips,
  // following justificiations and learning what was the creator's view of the world.
  // We need to discover what's the status but at the end return where we started at.
  sealed trait ValidatorStatus {
    /* Update validator's state while traversing his swimlane by adding new message.
     */
    def validate(m: Message): ValidatorStatus = this match {
      case Undefined           => Swimlane(SwimlaneTip(m), PrevSeen(m))
      case Swimlane(tip, prev) =>
        // Example:
        // a1 <- a2 <- a3
        //     \ a2Prime
        // We start at a3 (the tip of the swimlane) and traverse backwards.
        if (prev.validatorPrevMessageHash == m.messageHash)
          // Honest validator that builds proper chain.
          // In the above example our previous message would be `a3` and current one `a2`.
          Swimlane(tip, PrevSeen(m))
        else
          // Newer message didn't cite the previous one.
          // We have the first fork in the swimlane.
          // In the example above, we would see a2Prime now and learn that previous
          // message (a3) did not point at it with ``validatorPrevMessageHash`,
          // This is the equivocation.
          Equivocation(
            Set(
              Swimlane(tip, prev),
              Swimlane(SwimlaneTip(m), PrevSeen(m))
            )
          )
      case Equivocation(tips) =>
        // We already know that the validator is equivocator but we're still travering his messages.
        Equivocation(
          tips
            .find {
              case Swimlane(_, lastSeen) =>
                lastSeen.validatorPrevMessageHash == m.messageHash
            }
            .fold(
              // New fork in the swmilane. Example:
              // a1 <- a2 <- a3
              //  \ \-a2Prime
              //   \-a1Prime
              tips + Swimlane(SwimlaneTip(m), PrevSeen(m))
            ) {
              case old @ Swimlane(tip, _) =>
                // Example:
                // a1 <- a2 <- a3
                //  \ \-a2Prime
                //   \-a1Prime - a1PrimePrime
                // Even though we know `a1PrimePrime` is already an equivocation it properly
                // points at `a1Prime` with its `validatorPrevMessageHash`.
                // If we wanted to return all validator's tips we need to update the state.
                // Replace `lastSeen` because it points at `m` as previous message.
                tips - old + Swimlane(tip, PrevSeen(m))
            }
        )
    }
  }

  final case class SwimlaneTip(m: Message)
  final case class PrevSeen(m: Message) {
    def validatorPrevMessageHash: ByteString = m.validatorPrevMessageHash
  }

  // Hasn't seen any messages yet.
  case object Undefined extends ValidatorStatus
  // `Tip` is the latest message by that validator observed in the j-past-cone.
  // `prev` is the previous message we saw in the swimlane.
  case class Swimlane(tip: SwimlaneTip, prev: PrevSeen) extends ValidatorStatus
  // We still need to store the tip of the swimlane b/c this will be part of justifications.
  case class Equivocation(tips: Set[Swimlane]) extends ValidatorStatus

  def unknown: ValidatorStatus = Undefined

  def apply(
      data: Map[EraId, Map[PublicKeyHashBS, Set[Message]]]
  ): EraObservedBehavior[Message] =
    new EraObservedBehavior(data.mapValues(_.map {
      case (v, lms) =>
        if (lms.isEmpty) v -> ObservedValidatorBehavior.Empty
        else if (lms.size == 1) v -> ObservedValidatorBehavior.Honest(lms.head)
        else v                    -> ObservedValidatorBehavior.Equivocated(lms.head, lms.drop(1).head)
    }))

  sealed trait LocalView

  sealed trait MessageView
}
