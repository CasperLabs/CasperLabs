package io.casperlabs.crypto.signatures

import io.casperlabs.crypto.codec.Base16
import io.casperlabs.crypto.Keys._
import io.casperlabs.crypto.signatures.SignatureAlgorithm.Ed25519
import org.scalatest.{AppendedClues, BeforeAndAfterEach, FunSpec, Matchers}

import scala.util.Random
import io.casperlabs.crypto.hash.Blake2b256

class Ed25519Test extends FunSpec with Matchers with BeforeAndAfterEach with AppendedClues {
  describe("Ed25519") {

    it("computes public key from secret key") {
      val sec = PrivateKey(
        Base16.decode("b18e1d0045995ec3d010c387ccfeb984d783af8fbb0f40fa7db126d889f6dadd")
      )
      Base16.encode(Ed25519.tryToPublic(sec).get) shouldBe "77f48b59caeda77751ed138b0ec667ff50f8768c25d48309a8f386a2bad187fb"

    }
    it("verifies the given Ed25519 signature") {
      val data = Base16.decode(
        "916c7d1d268fc0e77c1bef238432573c39be577bbea0998936add2b50a653171ce18a542b0b7f96c1691a3be6031522894a8634183eda38798a0c5d5d79fbd01dd04a8646d71873b77b221998a81922d8105f892316369d5224c9983372d2313c6b1f4556ea26ba49d46e8b561e0fc76633ac9766e68e21fba7edca93c4c7460376d7f3ac22ff372c18f613f2ae2e856af40"
      )
      val sig = Signature(
        Base16.decode(
          "6bd710a368c1249923fc7a1610747403040f0cc30815a00f9ff548a896bbda0b4eb2ca19ebcf917f0f34200a9edbad3901b64ab09cc5ef7b9bcc3c40c0ff7509"
        )
      )
      val pub =
        PublicKey(Base16.decode("77f48b59caeda77751ed138b0ec667ff50f8768c25d48309a8f386a2bad187fb"))
      Ed25519.verify(data, sig, pub) shouldBe true
    }
    it("creates an Ed25519 signature.") {
      val data = Base16.decode(
        "916c7d1d268fc0e77c1bef238432573c39be577bbea0998936add2b50a653171ce18a542b0b7f96c1691a3be6031522894a8634183eda38798a0c5d5d79fbd01dd04a8646d71873b77b221998a81922d8105f892316369d5224c9983372d2313c6b1f4556ea26ba49d46e8b561e0fc76633ac9766e68e21fba7edca93c4c7460376d7f3ac22ff372c18f613f2ae2e856af40"
      )
      val sig = Base16.decode(
        "6bd710a368c1249923fc7a1610747403040f0cc30815a00f9ff548a896bbda0b4eb2ca19ebcf917f0f34200a9edbad3901b64ab09cc5ef7b9bcc3c40c0ff7509"
      )
      val sec = PrivateKey(
        Base16.decode("b18e1d0045995ec3d010c387ccfeb984d783af8fbb0f40fa7db126d889f6dadd")
      )
      Ed25519.sign(data, sec).deep shouldBe sig.deep
    }
    it("parses private and public PEM keys") {
      // openssl genpkey -algorithm ED25519 -out ed25519-private.pem
      val privateKeyRaw =
        """
          |-----BEGIN PRIVATE KEY-----
          |MC4CAQAwBQYDK2VwBCIEIEb9CsbXsPSw85xTlPwbVTqC81TbZMthAA/RD8xB3VuI
          |-----END PRIVATE KEY-----
        """.stripMargin
      // openssl pkey -in ed25519-public.pem -pubout > ed25519-public.pem
      val publicKeyRaw =
        """
          |-----BEGIN PUBLIC KEY-----
          |MCowBQYDK2VwAyEAg/9QefpMjH3Y5rl5UEVmLSpJ/ABBJ6A8D7SN9GHTDug=
          |-----END PUBLIC KEY-----
        """.stripMargin

      val sec  = Ed25519.tryParsePrivateKey(privateKeyRaw).get
      val pub  = Ed25519.tryParsePublicKey(publicKeyRaw).get
      val data = Array.ofDim[Byte](32)
      Random.nextBytes(data)
      val signature = Ed25519.sign(data, sec)
      Ed25519.verify(data, signature, pub) shouldBe true
    }
    it("parses raw private and public keys") {
      // Got examples from http://ed25519.herokuapp.com
      val privateKey =
        "x/hJAxRMp7bGmSZ+WiappN/6xwsJOgxcHOKVAK1uCQ25zkIXTJCuagDGnECIPTJHrF1aDRES+nI95mTU6zLZtQ=="
      val publicKey = "uc5CF0yQrmoAxpxAiD0yR6xdWg0REvpyPeZk1Osy2bU="
      val sec       = Ed25519.tryParsePrivateKey(privateKey).get
      val pub       = Ed25519.tryParsePublicKey(publicKey).get
      val data      = Array.ofDim[Byte](32)
      Random.nextBytes(data)
      val signature = Ed25519.sign(data, sec)
      Ed25519.verify(data, signature, pub) shouldBe true
    }

    it("calculates the public key hash") {
      val (_, pk) = Ed25519.newKeyPair
      val name    = "ED25519".getBytes
      val sep     = Base16.decode("00")
      val bytes   = name ++ sep ++ pk
      val hash    = Blake2b256.hash(bytes)

      Ed25519.publicKeyHash(pk) shouldBe hash
    }

    it("returns empty hash for an empty key") {
      Ed25519.publicKeyHash(PublicKey(Array.empty[Byte])) shouldBe empty
    }
  }
}
