package io.casperlabs.crypto.signatures

import io.casperlabs.crypto.Keys.{PrivateKey, PublicKey, Signature}
import io.casperlabs.crypto.codec.{Base16, Base64}
import io.casperlabs.crypto.hash.Sha256
import io.casperlabs.crypto.signatures.SignatureAlgorithm.Secp256r1
import org.scalatest.{AppendedClues, BeforeAndAfterEach, FunSpec, Matchers}

import scala.util.Random

// Got examples from https://kjur.github.io/jsrsasign/sample/sample-ecdsa.html
class Secp256r1Test extends FunSpec with Matchers with BeforeAndAfterEach with AppendedClues {
  val privateKeyBase16 = "d14b3917b4d557017c56ef9cb3787910e1e90f34ccca72f8b80ea8496c577d39"
  val publicKeyBase16 =
    "0498e2b65b5632cea0be2f91f94e5a4e9ce5a3101cc34ac65121437d984e97731fe193d19b757fb99aaaa63bf90d1179cadea2c123b24c0d09b2467d2e7d3deca0"

  describe("Secp256r1") {

    it("generates a new with keypair, signs and verifies some data") {
      val (sec, pub) = Secp256r1.newKeyPair
      val data       = Sha256.hash("testing".getBytes)
      val sig        = Secp256r1.sign(data, sec)
      Secp256r1.verify(data, sig, pub) shouldBe true
    }

    it("verifies a correct secp256r1 signature") {
      val data = Sha256.hash("testing".getBytes)
      val sig = Signature(
        Base16.decode(
          "3046022100fe294b24b2a262992f6e67b5e149c46ec57a09cb5fdc9b8b2abcb43e29f4e8a0022100b455caf4e357cd3bf62aa7a012ef750dca6831120d050331244a8a66580ca3ef"
        )
      )
      val pub = PublicKey(Base16.decode(publicKeyBase16))
      Secp256r1.verify(data, sig, pub) shouldBe true
    }

    it("rejects an incorrect secp256r1 signature") {
      val data = Sha256.hash("testing".getBytes)
      val sig = Signature(
        Base16.decode(
          "304502202e2ce8c9c2e0fbec77b66f0b74872a4527c3cde19adc56982194104187896ef7022100ec93d18249aa93a7493cfa2ea012ee0da2b176609496fe1a9cf46c24aecbcfc9"
        )
      )
      val pub = PublicKey(Base16.decode(publicKeyBase16))
      Secp256r1.verify(data, sig, pub) shouldBe false
    }

    it("computes public key from secret key") {
      val sec = PrivateKey(Base16.decode(privateKeyBase16))
      val pub = Secp256r1.tryToPublic(sec).get
      Base16.encode(pub) shouldBe publicKeyBase16
    }

    it("parses private and public PEM keys") {
      // openssl ecparam -name secp256r1 -genkey -noout -out secp256r1-private.pem
      val privateKey = """
          |-----BEGIN EC PRIVATE KEY-----
          |MHcCAQEEIMan83UTfkWnHljlEzfRZwCwXLP+ZMxFWnBw1fNTF+/4oAoGCCqGSM49
          |AwEHoUQDQgAEhVolWwTE5QIbAMl9PBsUWr5VmIqlQz094XbcVh4s21vjPIXXQys+
          |SZNlmd3+NUsBHNPp5CbtER8z7UpELEGpSQ==
          |-----END EC PRIVATE KEY-----
      """.stripMargin

      // openssl ec -in secp256r1-private.pem -pubout -out secp256r1-public.pem
      val publicKey = """
          |-----BEGIN PUBLIC KEY-----
          |MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEhVolWwTE5QIbAMl9PBsUWr5VmIql
          |Qz094XbcVh4s21vjPIXXQys+SZNlmd3+NUsBHNPp5CbtER8z7UpELEGpSQ==
          |-----END PUBLIC KEY-----
        """.stripMargin

      val sec  = Secp256r1.tryParsePrivateKey(privateKey).get
      val pub  = Secp256r1.tryParsePublicKey(publicKey).get
      val data = Array.ofDim[Byte](32)
      Random.nextBytes(data)
      val signature = Secp256r1.sign(data, sec)
      Secp256r1.verify(data, signature, pub) shouldBe true
    }

    it("parses private and public Base64 keys, signs and verifies some data") {
      val privateKeyBase64 = Base64.encode(Base16.decode(privateKeyBase16))
      val publicKeyBase64  = Base64.encode(Base16.decode(publicKeyBase16))
      val sec              = Secp256r1.tryParsePrivateKey(privateKeyBase64).get
      val pub              = Secp256r1.tryParsePublicKey(publicKeyBase64).get
      val data             = Array.ofDim[Byte](32)
      Random.nextBytes(data)
      val signature = Secp256r1.sign(data, sec)
      Secp256r1.verify(data, signature, pub) shouldBe true
    }

    it("works with example values") {
      val data = Sha256.hash("testing".getBytes)
      val pub = PublicKey(
        Base16.decode(
          "04ea3833e4fc36ed780c6aaa4584dd28bffe6738dcc4cd0025c2705f0140bb25b52e2491cc636aad262e5817bccf5cf3f39ac496aa465da172c53a9f031c7024ff"
        )
      )
      val sec = PrivateKey(
        Base16.decode("b4009943ce911864e7a259e2cd304518646e83e3d0a687b97d72ef403eb80004")
      )

      val ownSig = Secp256r1.sign(data, sec)
      Secp256r1.verify(data, ownSig, pub) shouldBe true

      val exampleSig = Signature(
        Base16.decode(
          "30460221009feda8559ad1ff8b15145f0f65ccd7e76863c797da2b5c688842c8300154c01102210084dacbb7e6d9217bde24da6a5b1dc863864d16a8b2ed151fec7014933c8d07e4"
        )
      )
      Secp256r1.verify(data, exampleSig, pub) shouldBe true
    }
  }

}
