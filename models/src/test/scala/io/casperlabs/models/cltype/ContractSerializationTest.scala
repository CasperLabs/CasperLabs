package io.casperlabs.models.cltype

import io.casperlabs.models.bytesrepr.SerializationTest.roundTrip
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.prop.PropertyChecks
import ContractSerializationTest.arbContract
import ByteArray32SerializationTest.genByteArray32

class ContractSerializationTest extends FlatSpec with Matchers with PropertyChecks {
  "Contracts" should "serialize properly" in forAll { (c: Contract) =>
    roundTrip(c, Contract.deserializer)
  }
}

object ContractSerializationTest {
  val genContract: Gen[Contract] = for {
    contractPackageHash <- genByteArray32
    contractWasmHash    <- genByteArray32
    namedKeys <- Gen.mapOf(
                  Gen.alphaStr.flatMap(s => KeySerializationTest.genKey.map(k => s -> k))
                )
    version <- SemVerSerializationTest.genSemVer
  } yield Contract(
    contractPackageHash,
    contractWasmHash,
    namedKeys,
    Map.empty[String, EntryPoint],
    version
  )

  implicit val arbContract: Arbitrary[Contract] = Arbitrary(genContract)
}
