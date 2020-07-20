package io.casperlabs.storage.era

import cats._
import cats.implicits._
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.casper.consensus.Era
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.storage.BlockHash
import simulacrum.typeclass

@typeclass
trait EraStorage[F[_]] {

  /** Persist an era. Return true if the era was new, false if it already existed. */
  def addEra(era: Era): F[Boolean]

  /** Retrieve the era, if it exists, by its key block hash. */
  def getEra(keyBlockHash: BlockHash): F[Option[Era]]

  /** Check if an era already exists. */
  def containsEra(keyBlockHash: BlockHash)(implicit A: Applicative[F]): F[Boolean] =
    getEra(keyBlockHash).map(_.isDefined)

  /** Retrieve the era, or raise an error if it's not found. */
  def getEraUnsafe(keyBlockHash: BlockHash)(implicit E: MonadThrowable[F]): F[Era] =
    getEra(keyBlockHash) flatMap {
      MonadThrowable[F].fromOption(
        _,
        new NoSuchElementException(
          s"Era ${Base16.encode(keyBlockHash.toByteArray)} could not be found."
        )
      )
    }

  /** Retrieve the child eras from the era tree. */
  def getChildEras(keyBlockHash: BlockHash): F[Set[Era]]

  /** Retrieve childless eras, which are the leaves of the era tree. */
  def getChildlessEras: F[Set[Era]]
}
