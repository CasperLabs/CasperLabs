package io.casperlabs.models.cltype

import io.casperlabs.models.bytesrepr.SerializationTest.roundTrip
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.prop.PropertyChecks
import StoredValueSerializationTest.arbStoredValue

class StoredValueSerializationTest extends FlatSpec with Matchers with PropertyChecks {
  "StoredValues" should "serialize properly" in forAll { (v: StoredValue) =>
    roundTrip(v, StoredValue.deserializer)
  }
}

object StoredValueSerializationTest {
  val genStoredValue: Gen[StoredValue] = Gen.choose(0, 1).flatMap {
    case 0 => CLValueSerializationTest.genCLValue.map(v => StoredValue.CLValue(v))
    case 1 => AccountSerializationTest.genAccount.map(a => StoredValue.Account(a))
    // case 2 => ContractSerializationTest.genContract.map(c => StoredValue.Contract(c))
  }

  implicit val arbStoredValue: Arbitrary[StoredValue] = Arbitrary(genStoredValue)
}
