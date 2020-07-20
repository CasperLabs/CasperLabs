package io.casperlabs.node.api

import cats.implicits._
import cats.Monad
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.state
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.crypto.hash.Blake2b256

import scala.util.Try

object Utils {
  def check[F[_]: MonadThrowable, A](
      str: A,
      desc: String,
      cond: A => Boolean
  ): F[A] =
    MonadThrowable[F]
      .raiseError[A](new IllegalArgumentException(s"$desc"))
      .whenA(!cond(str))
      .as(str)

  // The API accepts base16 in case we want to use them in RESTful URLs but also raw bytes,
  // to make it less cumbersome for clients. Use whihever is present.
  private def fallback(hashBase16: String, hash: ByteString) =
    if (hashBase16.isEmpty) Base16.encode(hash.toByteArray) else hashBase16

  def validateBlockHashPrefix[F[_]: MonadThrowable](
      prefixBase16: String,
      prefix: ByteString,
      adaptError: PartialFunction[Throwable, Throwable] = PartialFunction.empty
  ): F[String] =
    Utils
      .check[F, String](
        fallback(prefixBase16, prefix),
        "BlockHash prefix must be >= 4 and <= 64 base16 characters (2 to 32 bytes) long",
        _.matches("[a-f0-9]{4,64}")
      )
      .adaptErr(adaptError)

  def validateDeployHash[F[_]: MonadThrowable](
      hashBase16: String,
      hash: ByteString,
      adaptError: PartialFunction[Throwable, Throwable] = PartialFunction.empty
  ): F[String] =
    Utils
      .check[F, String](
        fallback(hashBase16, hash),
        "DeployHash must be 64 base16 characters (32 bytes) long",
        _.matches("[a-f0-9]{64}")
      )
      .adaptError(adaptError)

  def validateAccountPublicKeyHash[F[_]: MonadThrowable](
      hashBase16: String,
      hash: ByteString,
      adaptError: PartialFunction[Throwable, Throwable] = PartialFunction.empty
  ): F[String] =
    Utils
      .check[F, String](
        fallback(hashBase16, hash),
        "AccountHash must be 64 base16 characters (32 bytes) long",
        _.matches("[a-f0-9]{64}")
      )
      .adaptError(adaptError)

  def toKey[F[_]: Monad](keyType: String, keyValue: String)(
      implicit appErr: MonadThrowable[F]
  ): F[state.Key] = {
    val keyBytes = ByteString.copyFrom(Base16.decode(keyValue))
    keyType.toLowerCase match {
      case "hash" =>
        keyBytes.size match {
          case 32 => state.Key(state.Key.Value.Hash(state.Key.Hash(keyBytes))).pure[F]
          case n =>
            appErr.raiseError(
              new IllegalArgumentException(
                s"Key of type hash must have exactly 32 bytes, $n =/= 32 provided."
              )
            )
        }
      case "uref" =>
        keyBytes.size match {
          case 32 => state.Key(state.Key.Value.Uref(state.Key.URef(keyBytes))).pure[F]
          case n =>
            appErr.raiseError(
              new IllegalArgumentException(
                s"Key of type uref must have exactly 32 bytes, $n =/= 32 provided."
              )
            )
        }
      case "address" =>
        keyBytes.size match {
          case 32 => state.Key(state.Key.Value.Address(state.Key.Address(keyBytes))).pure[F]
          case n =>
            appErr.raiseError(
              new IllegalArgumentException(
                s"Key of type address must have exactly 32 bytes, $n =/= 32 provided."
              )
            )
        }
      case _ =>
        appErr.raiseError(
          new IllegalArgumentException(
            s"Key variant $keyType not valid. Must be one of hash, uref, address."
          )
        )
    }
  }
}
