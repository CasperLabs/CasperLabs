package io.casperlabs.crypto.signatures

import java.io.StringReader

import io.casperlabs.crypto.Keys.{PrivateKey, PublicKey, PublicKeyHash, Signature}
import io.casperlabs.crypto.codec.Base64
import org.bouncycastle.openssl.PEMKeyPair
import scala.util.control.NonFatal
import scala.util.{Random, Try}
import io.casperlabs.crypto.Keys
import java.math.BigInteger

/**
  * Useful links:
  * [[https://tls.mbed.org/kb/cryptography/asn1-key-structures-in-der-and-pem]]
  * [[https://wiki.openssl.org/index.php/Command_Line_Elliptic_Curve_Operations]]
  */
sealed trait SignatureAlgorithm {

  def name: String

  /**
    * Verifies the given signature.
    *
    * @param data      The data which was signed, must be exactly 32 bytes
    * @param signature The signature
    * @param pub       The public key which did the signing
    * @return Boolean value of verification
    */
  def verify(data: Array[Byte], signature: Signature, pub: PublicKey): Boolean

  def newKeyPair: (PrivateKey, PublicKey)

  /**
    * Create a signature.
    *
    * @param data Message hash, 32 bytes
    * @param sec  Secret key, 32 bytes
    * @return Byte array of signature
    */
  def sign(data: Array[Byte], sec: PrivateKey): Signature

  /**
    * Computes public key from secret key
    */
  def tryToPublic(privateKey: PrivateKey): Option[PublicKey]

  def tryParsePrivateKey(str: String): Option[PrivateKey]

  def tryParsePublicKey(str: String): Option[PublicKey]

  def areMatchTogether(publicKey: PublicKey, privateKey: PrivateKey): Boolean = {
    val a = Array.ofDim[Byte](32)
    Random.nextBytes(a)
    val signature = sign(a, privateKey)
    verify(a, signature, publicKey)
  }

  /** Compute a unique hash from the algorithm name and a public key, used for accounts. */
  val publicKeyHash: PublicKey => PublicKeyHash = Keys.publicKeyHash(name)
}

object SignatureAlgorithm {

  def unapply(alg: String): Option[SignatureAlgorithm] = alg match {
    case "ed25519"   => Some(Ed25519)
    case "secp256k1" => Some(Secp256k1)
    case "secp256r1" => Some(Secp256r1)
    // Not matching prime256v1 (equivalent to secp256r1) so that we only have 1 version for an account hash.
    case _ => None
  }

  /**
    * Short introduction [[https://ed25519.cr.yp.to]]
    * Useful link [[https://tools.ietf.org/id/draft-ietf-curdle-pkix-06.html#rfc.section.10.1]]
    */
  object Ed25519 extends SignatureAlgorithm {

    import org.abstractj.kalium.keys.{SigningKey, VerifyKey}

    override def name: String = "ed25519"

    /**
      * Expects key to be in OpenSSL format or raw key encoded in base64.
      *
      * Example OpenSSL:
      * ```
      * -----BEGIN PRIVATE KEY-----
      * MC4CAQAwBQYDK2VwBCIEIElVumd7dKQmSsMHZeRFSbxwFZ59PFNJQ8VpiCFmwoY0
      * -----END PRIVATE KEY-----
      * ```
      *
      * Example base64: `cQyHB8q9DiLcmVkaYUne2OFpkosW26fMBCKH0rZvZe0=`
      */
    override def tryParsePrivateKey(str: String): Option[PrivateKey] = {
      val KeyLength = 32

      /**
        * Skips header `-----BEGIN PRIVATE KEY-----` and footer `-----END PRIVATE KEY-----`
        */
      def cleanIfPemFile(s: String) =
        s.split('\n').filterNot(_.contains("PRIVATE KEY")).mkString("")

      def tryParse(a: Array[Byte]): Option[Array[Byte]] = a.length match {
        // Some of ed25519 private keys are a concatenation of both private (on the left) and public (on the right).
        case x if x == KeyLength || x == KeyLength * 2 => Some(a.take(KeyLength))
        // OpenSSL generates private keys with additional information
        // at the beginning of keys which is not part of keys themselves.
        // We can safely ignore them.
        case x if x > KeyLength && x < KeyLength * 2 =>
          Some(a.drop(x % KeyLength))
        case _ =>
          None
      }

      val cleaned = cleanIfPemFile(str)
      for {
        decoded <- Base64.tryDecode(cleaned)
        parsed  <- tryParse(decoded)
      } yield PrivateKey(parsed)
    }

    /**
      * Expects key to be in OpenSSL format or raw encoded in base64.
      *
      * Example OpenSSL:
      * ```
      * -----BEGIN PUBLIC KEY-----
      * MCowBQYDK2VwAyEAhRAJx+krVtJQ3+jRzE5HMAheSn7YzzPVBDMgyJQdUq0=
      * -----END PUBLIC KEY-----
      * ```
      *
      * Example base64: `T81Noks9FR3Qj3mBLn/+Az9UG5bgTAc5yWhAQ6WpFn8=`
      */
    override def tryParsePublicKey(str: String): Option[PublicKey] = {
      val KeyLength = 32

      /**
        * Skips header `-----BEGIN PUBLIC KEY-----` and footer `-----END PUBLIC KEY-----`
        */
      def cleanIfPemFile(s: String) =
        s.split('\n').filterNot(_.contains("PUBLIC KEY")).mkString("")

      def tryParse(a: Array[Byte]): Option[Array[Byte]] = a.length match {
        // Some of ed25519 private keys are a concatenation of both private (on the left) and public (on the right).
        case x if x == KeyLength || x == KeyLength * 2 => Some(a.takeRight(KeyLength))
        // OpenSSL generates public keys with additional information
        // at the beginning of keys which is not part of keys themselves.
        // We can safely ignore them.
        case x if x > KeyLength && x < KeyLength * 2 =>
          Some(a.drop(x % KeyLength))
        case _ => None
      }

      val cleaned = cleanIfPemFile(str)
      for {
        decoded <- Base64.tryDecode(cleaned)
        parsed  <- tryParse(decoded)
      } yield PublicKey(parsed)
    }

    override def newKeyPair: (PrivateKey, PublicKey) = {
      val key = new SigningKey()
      val sec = key.toBytes
      val pub = key.getVerifyKey.toBytes
      (PrivateKey(sec), PublicKey(pub))
    }

    /**
      * Computes public key from secret key
      */
    override def tryToPublic(sec: PrivateKey): Option[PublicKey] =
      try {
        val key = new SigningKey(sec)
        Some(PublicKey(key.getVerifyKey.toBytes))
      } catch {
        case NonFatal(_) => None
      }

    /**
      * Verifies the given signature.
      *
      * @param data      The data which was signed, must be exactly 32 bytes
      * @param signature The signature
      * @param pub       The public key which did the signing
      * @return Boolean value of verification
      */
    override def verify(data: Array[Byte], signature: Signature, pub: PublicKey): Boolean =
      try {
        new VerifyKey(pub).verify(data, signature)
      } catch {
        case ex: RuntimeException if ex.getMessage.contains("signature was forged or corrupted") =>
          false
      }

    /**
      * Create an ED25519 signature.
      *
      * @param data Message hash, 32 bytes
      * @param sec  Secret key, 32 bytes
      * @return Byte array of signature
      */
    override def sign(data: Array[Byte], sec: PrivateKey): Signature =
      Signature(new SigningKey(sec).sign(data))
  }

  trait Secp256 extends SignatureAlgorithm {
    import java.security.KeyPairGenerator
    import java.security.interfaces.ECPrivateKey
    import java.security.spec.ECGenParameterSpec

    import com.google.common.base.Strings
    import io.casperlabs.crypto.codec.Base16
    import io.casperlabs.crypto.util.SecureRandomUtil
    import org.bouncycastle.jce.provider.BouncyCastleProvider

    // Supported algorithms:
    // http://www.bouncycastle.org/wiki/pages/viewpage.action?pageId=362269
    protected val PrivateKeyLength = 32
    protected val PublicKeyLength  = 65

    /**
      * Expects the key to be in PEM format without parameters section or raw key encoded in base64.
      *
      * Example of PEM:
      * ```
      * -----BEGIN EC PRIVATE KEY-----
      * MHQCAQEEIFS9fBey9dtXX+EsNWJsNS6+I30bZuT1lBUiP9bYTo9PoAcGBSuBBAAK
      * oUQDQgAEj1fgdbpNbt06EY/8C+wbBXq6VvG+vCVDNl74LvVAmXfpdzCWFKbdrnIl
      * X3EFDxkd9qpk35F/kLcqV3rDn/u3dg==
      * -----END EC PRIVATE KEY-----
      * ```
      *
      * Example of base64: `IRhZtFy4Ku1BLUJ7kIyU54DhdtZq4xt5yjUR4mwtvYY=`
      */
    override def tryParsePrivateKey(str: String): Option[PrivateKey] =
      try {
        Base64
          .tryDecode(str.trim)
          .collectFirst {
            case a if a.length == PrivateKeyLength => PrivateKey(a)
          }
          .orElse {
            import scala.collection.JavaConverters._

            val parser  = new org.bouncycastle.openssl.PEMParser(new StringReader(str.trim))
            val pemPair = parser.readObject().asInstanceOf[PEMKeyPair]
            pemPair.getPrivateKeyInfo
              .parsePrivateKey()
              .asInstanceOf[org.bouncycastle.asn1.DLSequence]
              .iterator()
              .asScala
              .collectFirst {
                case octet: org.bouncycastle.asn1.DEROctetString => PrivateKey(octet.getOctets)
              }
          }
      } catch {
        case NonFatal(_) => None
      }

    /**
      * Expects key to be in PEM format or raw key encoded in base64.
      *
      * Example PEM:
      * ```
      * -----BEGIN PUBLIC KEY-----
      * MFYwEAYHKoZIzj0CAQYFK4EEAAoDQgAEj1fgdbpNbt06EY/8C+wbBXq6VvG+vCVD
      * Nl74LvVAmXfpdzCWFKbdrnIlX3EFDxkd9qpk35F/kLcqV3rDn/u3dg==
      * -----END PUBLIC KEY-----
      * ```
      *
      * Example base64: `BFK6dV60mW8hUxjhwkNq4bG5OX/6kr6SXS1pi1zH2BQVERpfIw9QELDvK6Vc7pDaUhEM0M+OwVo7AxJyqr5dXOo=`
      */
    override def tryParsePublicKey(str: String): Option[PublicKey] =
      try {
        Base64
          .tryDecode(str.trim)
          .collectFirst {
            case a if a.length == PublicKeyLength => PublicKey(a)
          }
          .orElse {
            Some(
              PublicKey(
                new org.bouncycastle.openssl.PEMParser(new StringReader(str.trim))
                  .readObject()
                  .asInstanceOf[org.bouncycastle.asn1.x509.SubjectPublicKeyInfo]
                  .getPublicKeyData
                  .getBytes
              )
            )
          }
      } catch {
        case NonFatal(_) => None
      }

    override def newKeyPair: (PrivateKey, PublicKey) = {
      val kpg = KeyPairGenerator.getInstance("ECDSA", new BouncyCastleProvider())
      kpg.initialize(new ECGenParameterSpec(name), SecureRandomUtil.secureRandomNonBlocking)
      val kp = kpg.generateKeyPair

      val padded = toPaddedHex(kp.getPrivate.asInstanceOf[ECPrivateKey].getS)

      val sec = PrivateKey(Base16.decode(padded))
      val pub = PublicKey(tryToPublic(sec).get)

      (sec, pub)
    }

    protected def toPaddedHex(i: BigInteger): String =
      Strings.padStart(i.toString(16), 64, '0')
  }

  // Key type used by Bitcoin and Ethereum.
  object Secp256k1 extends Secp256 {
    import org.bitcoin.NativeSecp256k1

    override def name: String = "secp256k1"

    override def verify(data: Array[Byte], signature: Signature, pub: PublicKey): Boolean =
      NativeSecp256k1.verify(data, signature, pub)

    override def sign(data: Array[Byte], sec: PrivateKey): Signature =
      Signature(NativeSecp256k1.sign(data, sec))

    override def tryToPublic(seckey: PrivateKey): Option[PublicKey] =
      Try(PublicKey(NativeSecp256k1.computePubkey(seckey))).toOption

    /**
      * libsecp256k1 Seckey Verify - returns true if valid, false if invalid
      *
      * Input value
      *
      * @param seckey ECDSA Secret key, 32 bytes
      *
      *               Return value
      *               Boolean of secret key verification
      */
    def secKeyVerify(seckey: Array[Byte]): Boolean =
      NativeSecp256k1.secKeyVerify(seckey)

  }

  // Key type supported by Secure Enclave.
  object Secp256r1 extends Secp256 {
    // https://metamug.com/article/security/sign-verify-digital-signature-ecdsa-java.html

    import java.security.{AlgorithmParameters, KeyFactory}
    import java.security.spec.{
      ECGenParameterSpec,
      ECParameterSpec,
      ECPoint,
      ECPrivateKeySpec,
      ECPublicKeySpec
    }
    import org.bouncycastle.asn1.sec.SECNamedCurves
    import org.bouncycastle.crypto.params.ECDomainParameters

    override def name: String = "secp256r1" // same as prime256v1

    // https://docs.oracle.com/javase/7/docs/technotes/guides/security/StandardNames.html#Signature
    private def getSigner     = java.security.Signature.getInstance("NONEwithECDSA")
    private def getKeyFactory = KeyFactory.getInstance("EC")

    // See Example 5 at https://www.programcreek.com/java-api-examples/index.php?api=java.security.spec.ECPrivateKeySpec
    def toPrivateKey(sec: PrivateKey): java.security.PrivateKey = {
      val keySpec = new ECPrivateKeySpec(new BigInteger(1, sec), parameterSpec)
      getKeyFactory.generatePrivate(keySpec)
    }

    // See Example 5 at https://www.programcreek.com/java-api-examples/index.php?api=java.security.spec.ECPublicKeySpec
    def toPublicKey(pub: PublicKey): java.security.PublicKey = {
      require(
        pub.size == PublicKeyLength,
        s"Expected the public key to be ${PublicKeyLength} long; got ${pub.size}"
      )
      require(
        pub(0) == 0x04,
        "EC uncompressed point indicator with byte value 04 missing"
      )
      val x       = new BigInteger(1, pub.slice(1, 1 + PublicKeyLength / 2))
      val y       = new BigInteger(1, pub.slice(1 + PublicKeyLength / 2, PublicKeyLength))
      val q       = new ECPoint(x, y)
      val keySpec = new ECPublicKeySpec(q, parameterSpec)
      getKeyFactory.generatePublic(keySpec)
    }

    // https://stackoverflow.com/questions/22003407/bouncy-castle-ecdsa-create-public-key-from-private-key
    // https://github.com/kjur/jsrsasign/blob/master/src/ecdsa-modified-1.0.js#L176
    override def tryToPublic(seckey: PrivateKey): Option[PublicKey] =
      Try {
        val d = new BigInteger(1, seckey)
        val q = domainParameters.getG.multiply(d)
        PublicKey(q.getEncoded(false))
      }.toOption

    private val parameterSpec: ECParameterSpec = {
      val algorithm = AlgorithmParameters.getInstance("EC")
      algorithm.init(new ECGenParameterSpec(name))
      algorithm.getParameterSpec(classOf[ECParameterSpec])
    }

    private val domainParameters = {
      val curve = SECNamedCurves.getByName(name)
      new ECDomainParameters(curve.getCurve, curve.getG, curve.getN, curve.getH)
    }

    override def verify(data: Array[Byte], signature: Signature, pub: PublicKey): Boolean = {
      val publicKey = toPublicKey(pub)
      val signer    = getSigner
      signer.initVerify(publicKey)
      signer.update(data)
      signer.verify(signature)
    }

    override def sign(data: Array[Byte], sec: PrivateKey): Signature = {
      val privateKey = toPrivateKey(sec)
      val signer     = getSigner
      signer.initSign(privateKey)
      signer.update(data)
      Signature(signer.sign())
    }
  }

}
