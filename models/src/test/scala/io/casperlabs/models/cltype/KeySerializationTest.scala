package io.casperlabs.models.cltype

import io.casperlabs.models.bytesrepr.SerializationTest.roundTrip
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.prop.PropertyChecks
import KeySerializationTest.arbKey

class KeySerializationTest extends FlatSpec with Matchers with PropertyChecks {
  "Keys" should "serialize properly" in forAll { (k: Key) =>
    roundTrip(k, Key.deserializer)
  }
}

object KeySerializationTest {
  val genAccountKey: Gen[Key.Account] =
    ByteArray32SerializationTest.genByteArray32.map(Key.Account(_))

  val genHashKey: Gen[Key.Hash] = ByteArray32SerializationTest.genByteArray32.map(Key.Hash(_))
  val genURefKey: Gen[Key.URef] = URefSerializationTest.genURef.map(Key.URef(_))

  val genKey: Gen[Key] = Gen.oneOf(genAccountKey, genHashKey, genURefKey)

  implicit val arbKey: Arbitrary[Key] = Arbitrary(genKey)
}
