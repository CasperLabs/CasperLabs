package io.casperlabs.casper.highway

import cats._
import cats.implicits._
import cats.data.NonEmptyList
import io.casperlabs.crypto.hash.Blake2b256
import io.casperlabs.crypto.Keys.{PublicKey, PublicKeyHash, PublicKeyHashBS}
import io.casperlabs.casper.consensus.{Bond, Era}
import io.casperlabs.catscontrib.MonadThrowable
import java.security.SecureRandom
import java.nio.{ByteBuffer, ByteOrder}

trait LeaderSequencer {
  def leaderFunction[F[_]: MonadThrowable](era: Era): F[LeaderFunction]
  def omegaFunction[F[_]: MonadThrowable](era: Era): F[OmegaFunction]
}

object LeaderSequencer extends LeaderSequencer {

  override def leaderFunction[F[_]: MonadThrowable](era: Era): F[LeaderFunction] =
    withBonds[F, LeaderFunction](era)(leaderFunction(_, _))

  override def omegaFunction[F[_]: MonadThrowable](era: Era): F[OmegaFunction] =
    withBonds[F, OmegaFunction](era)(omegaFunction(_, _))

  private def withBonds[F[_]: MonadThrowable, T](
      era: Era
  )(f: (Array[Byte], NonEmptyList[Bond]) => T): F[T] =
    MonadThrowable[F].fromOption(
      NonEmptyList
        .fromList {
          era.bonds.filterNot(x => x.getStake.value.isEmpty || x.getStake.value == "0").toList
        }
        .map { bonds =>
          f(era.bookingBlockHash.toByteArray, bonds)
        },
      new IllegalStateException("There must be some bonded validators in the era!")
    )

  /** Concatentate all the magic bits into a byte array,
    * padding them with zeroes on the right.
    */
  def toByteArray(bits: Seq[Boolean]): Array[Byte] = {
    val arr = Array.fill(math.ceil(bits.size / 8.0).toInt)(0)
    bits.zipWithIndex.foreach {
      case (bit, i) =>
        if (bit) {
          val a = i / 8
          val b = 7 - i % 8
          arr(a) = arr(a) | 1 << b
        }
    }
    arr.map(_.toByte)
  }

  def seed(parentSeed: Array[Byte], magicBits: Seq[Boolean]) =
    Blake2b256.hash(parentSeed ++ toByteArray(magicBits))

  /** Make a function that assigns a leader to each round, deterministically,
    * with a relative frequency based on their weight. */
  def leaderFunction(leaderSeed: Array[Byte], bonds: NonEmptyList[Bond]): LeaderFunction = {
    // Make a list of (validator, from, to) triplets.
    type ValidatorRange = (PublicKeyHashBS, BigInt, BigInt)

    val (validators, total) = {
      val acc = bonds
        .foldLeft(List.empty[ValidatorRange] -> BigInt(0)) {
          case ((ranges, total), bond) =>
            val key   = PublicKeyHash(bond.validatorPublicKeyHash)
            val stake = BigInt(bond.getStake.value)
            // This should be trivial; the auction should not allow 0 bids,
            // but if it did, there would be no way to pick between them.
            require(stake > 0, s"Bonds must be positive: $stake")
            val from = total
            val to   = total + stake
            ((key, from, to) :: ranges) -> to
        }
      // Keep the order of validator, it's coming from the block, same for everyone.
      val ranges = acc._1.reverse.toVector
      // Using BigDecimal to be able to multiply with a Double later.
      val total = BigDecimal(acc._2)
      ranges -> total
    }

    // Given a target sum of bonds, find the validator with a total cumulative weight in that range.
    def bisect(target: BigInt, i: Int = 0, j: Int = validators.size - 1): PublicKeyHashBS = {
      val k = (i + j) / 2
      val v = validators(k)
      // The first validator has the 0 inclusive, upper exclusive.
      if (v._2 <= target && target < v._3 || i == j) {
        v._1
      } else if (target < v._2) {
        bisect(target, i, k)
      } else {
        bisect(target, k + 1, j)
      }
    }

    (tick: Ticks) => {
      val random = getRandom(leaderSeed, tick)
      // Pick a number between [0, 1) and use it to find a validator.
      // NODE-1096: If possible generate a random BigInt directly, without involving a Double.
      val r = BigDecimal.valueOf(random.nextDouble())
      // Integer arithmetic is supposed to be safer than Double.
      val t = (total * r).toBigInt
      // Find the first validator over the target.
      bisect(t)
    }
  }

  /** Make a function that assigns an order to all the validators within a round, deterministically. */
  def omegaFunction(leaderSeed: Array[Byte], bonds: NonEmptyList[Bond]): OmegaFunction = {
    val validators = bonds.map(b => PublicKeyHash(b.validatorPublicKeyHash))
    (tick: Ticks) => {
      val random = getRandom(leaderSeed, tick)
      val varray = validators.toIterable.toArray
      shuffle(varray, random)
    }
  }

  private def getRandom(leaderSeed: Array[Byte], tick: Ticks): SecureRandom = {
    // On Linux SecureRandom uses NativePRNG, and ignores the seed.
    // Re-seeding also doesn't reset the seed, just augments it, so a new instance is required.
    // https://stackoverflow.com/questions/50107982/rhe-7-not-respecting-java-secure-random-seed
    // NODE-1095: Find a more secure, cross platform algorithm.
    val random = SecureRandom.getInstance("SHA1PRNG", "SUN")
    // Ticks need to be deterministic, so each time we have to reset the seed.
    val tickSeed = leaderSeed ++ longToBytesLittleEndian(tick)
    random.setSeed(tickSeed)
    random
  }

  private def longToBytesLittleEndian(i: Long): Array[Byte] =
    ByteBuffer
      .allocate(8)
      .order(ByteOrder.LITTLE_ENDIAN)
      .putLong(i)
      .array

  /** Shuffles an array in place using the Knuth algorithm.
    * https://en.wikipedia.org/wiki/Fisher%E2%80%93Yates_shuffle
    * */
  private def shuffle[T](arr: Array[T], rand: SecureRandom): Array[T] = {
    for (i <- arr.size - 1 to 1 by -1) {
      val r = rand.nextInt(i + 1)
      val t = arr(i)
      arr(i) = arr(r)
      arr(r) = t
    }
    arr
  }
}
