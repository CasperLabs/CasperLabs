package io.casperlabs.crypto
import cats.syntax.either._
import com.google.protobuf.ByteString
import io.casperlabs.crypto.signatures.SignatureAlgorithm
import io.casperlabs.crypto.hash.Blake2b256
import java.nio.charset.StandardCharsets
import shapeless.tag.@@

object Keys {
  sealed trait PublicKeyTag

  /**
    * Encodes Array[Byte]
    */
  type PublicKey = Array[Byte] @@ PublicKeyTag

  /**
    * Encodes ByteString
    */
  type PublicKeyBS = ByteString @@ PublicKeyTag

  def PublicKey(a: Array[Byte]): PublicKey = a.asInstanceOf[PublicKey]

  def PublicKey(a: ByteString): PublicKeyBS = a.asInstanceOf[PublicKeyBS]

  implicit def convertTypeclasses[F[_]](implicit Typeclass: F[Array[Byte]]): F[PublicKey] =
    Typeclass.asInstanceOf[F[PublicKey]]

  sealed trait PrivateKeyTag
  type PrivateKey = Array[Byte] @@ PrivateKeyTag
  def PrivateKey(a: Array[Byte]): PrivateKey = a.asInstanceOf[PrivateKey]

  sealed trait SignatureTag
  type Signature = Array[Byte] @@ SignatureTag
  def Signature(a: Array[Byte]): Signature = a.asInstanceOf[Signature]

  sealed trait PublicKeyHashTag
  type PublicKeyHash = Array[Byte] @@ PublicKeyHashTag
  def PublicKeyHash(a: Array[Byte]): PublicKeyHash = a.asInstanceOf[PublicKeyHash]

  type PublicKeyHashBS = ByteString @@ PublicKeyHashTag
  def PublicKeyHash(a: ByteString): PublicKeyHashBS = a.asInstanceOf[PublicKeyHashBS]

  sealed trait ParseError { self =>
    def errorMessage: String
    def asParseError: ParseError = self
  }
  object ParseError {
    case object PublicKeysDoNotMatch extends ParseError {
      override def errorMessage: String =
        "Given public key not compatible with derived public key from private key."
    }
    case object KeysDoNotMatch extends ParseError {
      override def errorMessage: String = "Private and public keys do not match each other."
    }
    case object EmptyPrivateKey extends ParseError {
      override def errorMessage: String = "Private key must be specified."
    }
    case object KeyParseError extends ParseError {
      override def errorMessage: String =
        "An error occurred during parsing, there might be something wrong with the keys."
    }
    case object EmptyPublicKey extends ParseError {
      override def errorMessage: String =
        "Public must be specified or private key is invalid, failed to infer public key from private key."
    }
    case object UnknownSignatureAlgorithm extends ParseError {
      override def errorMessage: String =
        "Unknown signature algorithm. Supported values: ed25519"
    }
  }

  type ParseResult = (PrivateKey, PublicKey, SignatureAlgorithm)

  def parse(
      signatureAlgorithm: String,
      privateKeyRaw: String,
      maybePublicKeyRaw: Option[String]
  ): Either[ParseError, ParseResult] =
    signatureAlgorithm match {
      case SignatureAlgorithm(sa) => parse(sa, privateKeyRaw, maybePublicKeyRaw)
      case _                      => ParseError.UnknownSignatureAlgorithm.asLeft[ParseResult]
    }

  private def parse(
      signatureAlgorithm: SignatureAlgorithm,
      privateKeyRaw: String,
      maybePublicKeyRaw: Option[String]
  ): Either[ParseError, ParseResult] = {
    val maybePrivateKey        = signatureAlgorithm.tryParsePrivateKey(privateKeyRaw)
    val maybeGivenPublicKey    = maybePublicKeyRaw.flatMap(signatureAlgorithm.tryParsePublicKey)
    val maybeInferredPublicKey = maybePrivateKey.flatMap(signatureAlgorithm.tryToPublic)

    if (privateKeyRaw.isEmpty)
      ParseError.EmptyPrivateKey.asLeft[ParseResult]
    else
      maybePrivateKey.fold(ParseError.KeyParseError.asParseError.asLeft[ParseResult]) {
        privateKey =>
          (maybeGivenPublicKey, maybeInferredPublicKey) match {

            case (Some(pub1), Some(pub2)) if pub1 sameElements pub2 =>
              (privateKey, pub1, signatureAlgorithm).asRight[ParseError]

            case (Some(_), Some(_)) =>
              ParseError.PublicKeysDoNotMatch.asParseError.asLeft[ParseResult]

            case (Some(publicKey), None)
                if signatureAlgorithm.areMatchTogether(publicKey, privateKey) =>
              (privateKey, publicKey, signatureAlgorithm).asRight[ParseError]

            case (Some(_), None) =>
              ParseError.KeysDoNotMatch.asParseError.asLeft[ParseResult]

            case (None, Some(publicKey)) =>
              (privateKey, publicKey, signatureAlgorithm).asRight[ParseError]

            case (None, None) =>
              ParseError.EmptyPublicKey.asParseError.asLeft[ParseResult]
          }
      }
  }

  /** Compute a unique hash from the algorithm name and a public key, used for accounts. */
  val publicKeyHash: String => PublicKey => PublicKeyHash = {
    val separator = Array[Byte](0)
    (signatureAlgorithm: String) => {
      val prefix = signatureAlgorithm.toUpperCase.getBytes(StandardCharsets.UTF_8) ++ separator
      (publicKey: PublicKey) => {
        val hash =
          if (publicKey.isEmpty) Array.empty[Byte] else Blake2b256.hash(prefix ++ publicKey)
        PublicKeyHash(hash)
      }
    }
  }
}
