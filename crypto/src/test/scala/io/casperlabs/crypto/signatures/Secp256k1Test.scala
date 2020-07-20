package io.casperlabs.crypto.signatures

import io.casperlabs.crypto.Keys.{PrivateKey, PublicKey, Signature}
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.crypto.hash.Sha256
import io.casperlabs.crypto.signatures.SignatureAlgorithm.Secp256k1
import org.scalatest.{AppendedClues, BeforeAndAfterEach, FunSpec, Matchers}

import scala.util.Random

// Got examples from https://kjur.github.io/jsrsasign/sample/sample-ecdsa.html
class Secp256k1Test extends FunSpec with Matchers with BeforeAndAfterEach with AppendedClues {
  describe("Secp256k1") {

    it("verifies the given secp256k1 signature in native code with keypair") {
      val (sec, pub) = Secp256k1.newKeyPair
      val data       = Sha256.hash("testing".getBytes)
      val sig        = Secp256k1.sign(data, sec)
      Secp256k1.verify(data, sig, pub) shouldBe true
    }
    it("verifies the given secp256k1 signature in native code") {
      val data = Sha256.hash("testing".getBytes)
      val sig = Signature(
        Base16.decode(
          "3044022079BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F817980220294F14E883B3F525B5367756C2A11EF6CF84B730B36C17CB0C56F0AAB2C98589"
        )
      )
      val pub = PublicKey(
        Base16.decode(
          "040A629506E1B65CD9D2E0BA9C75DF9C4FED0DB16DC9625ED14397F0AFC836FAE595DC53F8B0EFE61E703075BD9B143BAC75EC0E19F82A2208CAEB32BE53414C40"
        )
      )
      Secp256k1.verify(data, sig, pub) shouldBe true
    }
    it("packaged in libsecp256k1 creates an ECDSA signature") {
      val data = Sha256.hash("testing".getBytes)
      val sec = PrivateKey(
        Base16.decode("67E56582298859DDAE725F972992A07C6C4FB9F62A8FFF58CE3CA926A1063530")
      )
      Base16
        .encode(Secp256k1.sign(data, sec))
        .toUpperCase() shouldBe "30440220182A108E1448DC8F1FB467D06A0F3BB8EA0533584CB954EF8DA112F1D60E39A202201C66F36DA211C087F3AF88B50EDF4F9BDAA6CF5FD6817E74DCA34DB12390C6E9"
    }
    it("verify returns true if valid, false if invalid") {
      val sec = Base16.decode("67E56582298859DDAE725F972992A07C6C4FB9F62A8FFF58CE3CA926A1063530")
      Secp256k1.secKeyVerify(sec) shouldBe true
    }
    it("computes public key from secret key") {
      val sec = PrivateKey(
        Base16.decode("67E56582298859DDAE725F972992A07C6C4FB9F62A8FFF58CE3CA926A1063530")
      )
      Base16
        .encode(Secp256k1.tryToPublic(sec).get)
        .toUpperCase() shouldBe "04C591A8FF19AC9C4E4E5793673B83123437E975285E7B442F4EE2654DFFCA5E2D2103ED494718C697AC9AEBCFD19612E224DB46661011863ED2FC54E71861E2A6"
    }
    it("parses private and public PEM keys") {
      // openssl ecparam -name secp256k1 -genkey -noout -out secp256k1-private.pem
      val privateKey = """
         |-----BEGIN EC PRIVATE KEY-----
         |MHQCAQEEIFS9fBey9dtXX+EsNWJsNS6+I30bZuT1lBUiP9bYTo9PoAcGBSuBBAAK
         |oUQDQgAEj1fgdbpNbt06EY/8C+wbBXq6VvG+vCVDNl74LvVAmXfpdzCWFKbdrnIl
         |X3EFDxkd9qpk35F/kLcqV3rDn/u3dg==
         |-----END EC PRIVATE KEY-----
      """.stripMargin

      // openssl ec -in secp256k1-private.pem -pubout -out secp256k1-public.pem
      val publicKey =
        """
          |-----BEGIN PUBLIC KEY-----
          |MFYwEAYHKoZIzj0CAQYFK4EEAAoDQgAEj1fgdbpNbt06EY/8C+wbBXq6VvG+vCVD
          |Nl74LvVAmXfpdzCWFKbdrnIlX3EFDxkd9qpk35F/kLcqV3rDn/u3dg==
          |-----END PUBLIC KEY-----
        """.stripMargin

      val sec  = Secp256k1.tryParsePrivateKey(privateKey).get
      val pub  = Secp256k1.tryParsePublicKey(publicKey).get
      val data = Array.ofDim[Byte](32)
      Random.nextBytes(data)
      val signature = Secp256k1.sign(data, sec)
      Secp256k1.verify(data, signature, pub) shouldBe true
    }
    it("parses raw private and public keys") {
      val privateKey = "IRhZtFy4Ku1BLUJ7kIyU54DhdtZq4xt5yjUR4mwtvYY="
      val publicKey =
        "BFK6dV60mW8hUxjhwkNq4bG5OX/6kr6SXS1pi1zH2BQVERpfIw9QELDvK6Vc7pDaUhEM0M+OwVo7AxJyqr5dXOo="
      val sec  = Secp256k1.tryParsePrivateKey(privateKey).get
      val pub  = Secp256k1.tryParsePublicKey(publicKey).get
      val data = Array.ofDim[Byte](32)
      Random.nextBytes(data)
      val signature = Secp256k1.sign(data, sec)
      Secp256k1.verify(data, signature, pub) shouldBe true
    }

    it("works with example values") {
      val data = Sha256.hash("testing".getBytes)
      val pub = PublicKey(
        Base16.decode(
          "043b9fed7c65b7f1e9c0f283bb3d72efdd15f182f95ec8a53968e9a6c549ce2b76a7dd52baf884ba1151469a95bec9783e6ac095cacd37ad728b3829ecdc7ca65d"
        )
      )
      val sec = PrivateKey(
        Base16.decode("2eb09ed84dff803a680368bb9b6bf31d87f5f93b30b640de63f5c4c4b9ca2124")
      )

      val sig = Signature(
        Base16.decode(
          "304402203abfd24b0474011715f8e43defb0f0a6582c1a92cd677bd933a4677b1eb076580220586a1c45a1ddea8ba66e9d67c5ca842c55bec2037accbc95577014ca4207af52"
        )
      )
      Secp256k1.verify(data, sig, pub) shouldBe true

      val sig2 = Secp256k1.sign(data, sec)
      Secp256k1.verify(data, sig2, pub) shouldBe true
    }
  }

}
