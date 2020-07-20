package io.casperlabs.smartcontracts

import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.state.SemVer
import io.casperlabs.ipc.DeployPayload.Payload
import io.casperlabs.ipc.{
  DeployCode,
  DeployItem,
  DeployPayload,
  StoredContractHash,
  StoredContractName,
  StoredContractPackage,
  StoredContractPackageHash
}
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.{Arbitrary, Gen}

trait ArbitraryIpc {

  def genBytes(length: Int): Gen[ByteString] =
    Gen.listOfN(length, arbitrary[Byte]).map { bytes =>
      ByteString.copyFrom(bytes.toArray)
    }

  implicit val arbDeployPayload: Arbitrary[DeployPayload] = Arbitrary {
    val arbDeployCode = Arbitrary {
      for {
        code <- Gen
                 .chooseNum(50 * 1024 /* 50 kB */, 700 * 1024 /* 700 kB */ )
                 .flatMap(genBytes(_))
        args <- Gen
                 .chooseNum(100, 1 * 1024 /* 1 kB */ )
                 .flatMap(genBytes(_))
      } yield Payload.DeployCode(DeployCode(code, args))
    }

    // val arbSemVer = Arbitrary {
    //   for {
    //     major <- Gen.chooseNum(1, 10)
    //     minor <- Gen.chooseNum(1, 10)
    //     patch <- Gen.chooseNum(1, 10)
    //   } yield SemVer(major, minor, patch)
    // }

    val arbStoredContractHash = Arbitrary {
      for {
        hash <- genBytes(32)
        args <- Gen.chooseNum(100, 10000).flatMap(genBytes(_))
      } yield Payload.StoredContractHash(StoredContractHash(hash = hash, args = args))
    }

    val arbStoredContractName = Arbitrary {
      for {
        name <- Gen.alphaStr.map(_.take(20))
        args <- Gen.chooseNum(100, 10000).flatMap(genBytes(_))
      } yield Payload.StoredContractName(StoredContractName(name, args))
    }
    val genList = List(arbDeployCode, arbStoredContractHash, arbStoredContractName)
      .map(_.arbitrary.map(DeployPayload(_)))
      .map(1 -> _)

    Gen.frequency(genList: _*)
  }

  implicit val arbDeployItem: Arbitrary[DeployItem] = Arbitrary {
    for {
      address           <- genBytes(32)
      sessionCode       <- arbDeployPayload.arbitrary
      paymentCode       <- arbDeployPayload.arbitrary
      gasPrice          <- Gen.choose(1L, 1000)
      authorizationKeys <- Gen.listOfN(10, genBytes(32))
    } yield DeployItem()
      .withAddress(address)
      .withSession(sessionCode)
      .withPayment(paymentCode)
      .withGasPrice(gasPrice)
      .withAuthorizationKeys(authorizationKeys)
  }

  def sizeDeployItemList(min: Int, max: Int): Gen[List[DeployItem]] =
    Gen.chooseNum(min, max).flatMap(n => Gen.listOfN(n, arbDeployItem.arbitrary))

}
