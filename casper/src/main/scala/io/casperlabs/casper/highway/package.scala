package io.casperlabs.casper

import java.time.Instant
import java.util.concurrent.TimeUnit

import cats._
import cats.data.WriterT
import cats.effect.Clock
import cats.implicits._
import io.casperlabs.crypto.Keys.PublicKeyHashBS
import io.casperlabs.models.Message
import io.casperlabs.storage.BlockHash
import org.apache.commons.math3.util.ArithmeticUtils
import shapeless.tag.@@

import scala.concurrent.duration.FiniteDuration

package highway {
  sealed trait TimestampTag
  sealed trait TicksTag
  sealed trait ValidatedTag
}

package object highway {

  val HighwayMetricsSource = CasperMetricsSource / "highway"

  /** Time since Unix epoch in milliseconds. */
  type Timestamp = Long @@ TimestampTag
  def Timestamp(t: Long) = t.asInstanceOf[Timestamp]

  /** Ticks since Unix epoch in the Highway specific time unit. */
  type Ticks = Long @@ TicksTag
  object Ticks {
    def apply(t: Long) = t.asInstanceOf[Ticks]

    /** Calculate round length as 2^exp */
    def roundLength(exp: Int): Ticks = Ticks(ArithmeticUtils.pow(2L, exp))

    /** Calculate the next round tick. */
    def nextRound(epoch: Ticks, exp: Int)(now: Ticks): Ticks =
      roundBoundaries(epoch, exp)(now)._2

    /** Calculate the round boundaries, given regular round lengths from an epoch. */
    def roundBoundaries(epoch: Ticks, exp: Int)(now: Ticks): (Ticks, Ticks) = {
      val length        = roundLength(exp)
      val elapsed       = now - epoch
      val relativeStart = elapsed - elapsed % length
      val absoluteStart = epoch + relativeStart
      val absoluteEnd   = absoluteStart + length
      Ticks(absoluteStart) -> Ticks(absoluteEnd)
    }
  }

  implicit class InstantOps(val a: Instant) extends AnyVal {
    def plus(b: FiniteDuration) =
      a.plus(b.length, b.unit.toChronoUnit)

    def minus(b: FiniteDuration) =
      a.minus(b.length, b.unit.toChronoUnit)
  }

  implicit class ClockOps[F[_]: Applicative](clock: Clock[F]) {
    def currentTimeMillis: F[Long] =
      clock.realTime(TimeUnit.MILLISECONDS)
  }

  /** Models a state transition of an era, returning the domain events that
    * were raised during the operation. For example a round, or handling the
    * a message may have created a new era, which is now persisted in the
    * database, but scheduling has not been started for it (as we may be in
    * playback mode).
    */
  type HighwayLog[F[_], T] = WriterT[F, Vector[HighwayEvent], T]

  object HighwayLog {
    def liftF[F[_]: Applicative, T](value: F[T]): HighwayLog[F, T] =
      WriterT.liftF(value)

    def unit[F[_]: Applicative]: HighwayLog[F, Unit] =
      ().pure[HighwayLog[F, *]]

    def tell[F[_]: Applicative](events: HighwayEvent*) =
      WriterT.tell[F, Vector[HighwayEvent]](events.toVector)
  }

  /** Determine the leader of any given round. */
  type LeaderFunction = Ticks => PublicKeyHashBS

  /** Determine an order in which this validator should produce their omega messages in any given round. */
  type OmegaFunction = Ticks => Seq[PublicKeyHashBS]

  type ValidatedMessage = Message @@ ValidatedTag
  def Validated(m: Message) = m.asInstanceOf[ValidatedMessage]
}
