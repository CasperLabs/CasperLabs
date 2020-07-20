package io.casperlabs.casper

import java.nio.file.Path

import cats.Monad
import cats.effect.{Resource, Sync}
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.Signature
import io.casperlabs.crypto.Keys
import io.casperlabs.crypto.codec.Base64
import io.casperlabs.crypto.signatures.SignatureAlgorithm
import io.casperlabs.shared.{Log, LogSource}

import scala.io.Source
import scala.language.higherKinds

final case class ValidatorIdentity(
    publicKey: Keys.PublicKey,
    privateKey: Keys.PrivateKey,
    signatureAlgorithm: SignatureAlgorithm,
    publicKeyHash: Keys.PublicKeyHash
) {
  def signature(data: Array[Byte]): Signature =
    Signature(
      signatureAlgorithm.name,
      ByteString.copyFrom(signatureAlgorithm.sign(data, privateKey))
    )

  val publicKeyBS: Keys.PublicKeyBS =
    Keys.PublicKey(ByteString.copyFrom(publicKey))

  val publicKeyHashBS: Keys.PublicKeyHashBS =
    Keys.PublicKeyHash(ByteString.copyFrom(publicKeyHash))
}

object ValidatorIdentity {
  def apply(
      publicKey: Keys.PublicKey,
      privateKey: Keys.PrivateKey,
      signatureAlgorithm: SignatureAlgorithm
  ): ValidatorIdentity = ValidatorIdentity(
    publicKey,
    privateKey,
    signatureAlgorithm,
    publicKeyHash = signatureAlgorithm.publicKeyHash(publicKey)
  )

  val empty = ValidatorIdentity(
    Keys.PublicKey(Array.empty[Byte]),
    Keys.PrivateKey(Array.empty[Byte]),
    SignatureAlgorithm.Ed25519
  )

  private def fileContent[F[_]: Sync](path: Path): F[String] =
    Resource
      .fromAutoCloseable(Sync[F].delay(Source.fromFile(path.toFile)))
      .use(s => Sync[F].delay(s.mkString))

  private def createValidatorIdentity[F[_]: Log: Monad](
      signatureAlgorithm: String,
      privateKeyRaw: String,
      maybePublicKeyRaw: Option[String]
  ): F[Option[ValidatorIdentity]] =
    Keys
      .parse(signatureAlgorithm, privateKeyRaw, maybePublicKeyRaw)
      .fold(
        parseError =>
          Log[F]
            .error(s"Failed to parse keys, ${parseError.errorMessage}") >> none[ValidatorIdentity]
            .pure[F], {
          case (privateKey, publicKey, sa) =>
            Log[F].info(s"Validator identity: ${Base64.encode(publicKey) -> "validator"}") >>
              ValidatorIdentity(publicKey, privateKey, sa).some.pure[F]
        }
      )

  def fromConfig[F[_]: Sync: Log](conf: CasperConf): F[Option[ValidatorIdentity]] =
    conf.validatorPrivateKey
      .map(_.asLeft[Path])
      .orElse(conf.validatorPrivateKeyPath.map(_.asRight[String])) match {
      case Some(key) =>
        val readPublicKey = conf.validatorPublicKey
          .map(_.asLeft[Path])
          .orElse(conf.validatorPublicKeyPath.map(_.asRight[String]))
          .map(_.bimap(_.some.pure[F], p => fileContent(p).map(_.some)).merge)
          .getOrElse(none[String].pure[F])

        for {
          privateKey     <- key.bimap(_.pure[F], fileContent[F]).merge
          maybePublicKey <- readPublicKey
          maybeValidatorIdentity <- createValidatorIdentity(
                                     conf.validatorSigAlgorithm,
                                     privateKey,
                                     maybePublicKey
                                   )
        } yield maybeValidatorIdentity
      case None =>
        Log[F]
          .warn("No private key detected, cannot create validator identification.")
          .map(_ => none[ValidatorIdentity])
    }
}
