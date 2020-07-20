package io.casperlabs.casper.highway

import cats.data.NonEmptyList
import com.google.protobuf.ByteString
import io.casperlabs.crypto.hash.Blake2b256
import io.casperlabs.casper.consensus.Bond
import io.casperlabs.casper.consensus.state
import org.scalatest._

class LeaderSequencerSpec extends WordSpec with Matchers with Inspectors {
  "toByteArray" should {
    "concatenate bits to a byte array, padding on the right" in {
      val bits =
        List(true, false, false, true, true, false, false, false) ++
          List(false, true, true, false, true)

      val bytes = LeaderSequencer.toByteArray(bits)

      bytes should have size 2

      // If we just called .toInt on the bytes Java would interpret them as signed
      // think it's -104 instead of 152.
      def check(i: Int, b: String) =
        java.lang.Byte.toUnsignedInt(bytes(i)) shouldBe Integer.parseInt(b, 2)

      check(0, "10011000")
      check(1, "01101000")
    }
  }

  "seed" should {
    "concatenate the parent seed with the child magic bits" in {
      val parentSeed = "parent-seed".getBytes
      val magicBits  = List(false, true, false, false, true)
      val magicBytes = LeaderSequencer.toByteArray(magicBits)
      val seed       = LeaderSequencer.seed(parentSeed, magicBits)

      seed shouldBe Blake2b256.hash(parentSeed ++ magicBytes)
    }
  }

  "leaderFunction" should {
    val bonds = NonEmptyList.of(
      Bond(ByteString.copyFromUtf8("Alice")).withStake(state.BigInt("1000")),
      Bond(ByteString.copyFromUtf8("Bob")).withStake(state.BigInt("2000")),
      Bond(ByteString.copyFromUtf8("Charlie")).withStake(state.BigInt("3000"))
    )

    val leaderOf = LeaderSequencer.leaderFunction("leader-seed".getBytes, bonds)

    "create a deterministic function" in {
      val tick = Ticks(System.currentTimeMillis)
      leaderOf(tick) shouldBe leaderOf(tick)
    }

    "pick validators proportionately to their weight" in {
      val rnd    = new scala.util.Random()
      val total  = 1000 + 2000 + 3000
      val rounds = 10000
      val counts =
        List
          .fill(rounds)(Ticks(rnd.nextLong))
          .foldLeft(Map.empty[ByteString, Int].withDefaultValue(0)) {
            case (counts, tick) =>
              val v = leaderOf(tick)
              counts.updated(v, counts(v) + 1)
          }

      forAll(bonds.toList) { bond =>
        val w = bond.getStake.value.toDouble / total
        counts(bond.validatorPublicKeyHash).toDouble / rounds shouldBe (w +- 0.05)
      }
    }

    "work with an empty seed" in {
      val genesisLeader = LeaderSequencer.leaderFunction(Array.empty[Byte], bonds)
      genesisLeader(Ticks(System.currentTimeMillis))
    }
  }

  "omegaFunction" should {
    val bonds = NonEmptyList.of(
      Bond(ByteString.copyFromUtf8("Alice")).withStake(state.BigInt("1000")),
      Bond(ByteString.copyFromUtf8("Bob")).withStake(state.BigInt("2000")),
      Bond(ByteString.copyFromUtf8("Charlie")).withStake(state.BigInt("3000"))
    )

    val omegaOrder = LeaderSequencer.omegaFunction("leader-seed".getBytes, bonds)

    "create a deterministic function" in {
      val tick = Ticks(System.currentTimeMillis)
      omegaOrder(tick) shouldBe omegaOrder(tick)
    }

    "generate a random order for each round" in {
      val t0 = Ticks(32)
      val t1 = Ticks(64)
      omegaOrder(t0) should not be omegaOrder(t1)
    }

    "include every validator" in {
      omegaOrder(Ticks(0)) should contain theSameElementsAs bonds
        .map(_.validatorPublicKeyHash)
        .toList
    }
  }
}
