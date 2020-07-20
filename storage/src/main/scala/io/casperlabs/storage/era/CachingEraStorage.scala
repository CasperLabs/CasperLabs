package io.casperlabs.storage.era

import cats._
import cats.implicits._
import cats.effect.Sync
import com.google.common.cache.{Cache, CacheBuilder}
import java.util.concurrent.TimeUnit
import io.casperlabs.storage.BlockHash
import io.casperlabs.casper.consensus.Era

class CachingEraStorage[F[_]: Sync](
    underlying: EraStorage[F],
    eraCache: Cache[BlockHash, Era]
) extends EraStorage[F] {
  private def cacheEra(era: Era) = Sync[F].delay {
    eraCache.put(era.keyBlockHash, era)
  }

  override def addEra(era: Era) =
    for {
      isNew <- underlying.addEra(era)
      _     <- cacheEra(era).whenA(isNew)
    } yield isNew

  override def getEra(keyBlockHash: BlockHash): F[Option[Era]] =
    Sync[F].delay(Option(eraCache.getIfPresent(keyBlockHash))) flatMap {
      case Some(era) => era.some.pure[F]
      case None =>
        underlying.getEra(keyBlockHash) flatMap {
          case Some(era) => cacheEra(era).as(era.some)
          case None      => none.pure[F]
        }
    }

  override def getChildEras(keyBlockHash: BlockHash) =
    underlying.getChildEras(keyBlockHash)

  override def getChildlessEras =
    underlying.getChildlessEras
}

object CachingEraStorage {
  def apply[F[_]: Sync](underlying: EraStorage[F]): F[CachingEraStorage[F]] = Sync[F].delay {
    val cache = CacheBuilder
      .newBuilder()
      .expireAfterAccess(1, TimeUnit.HOURS)
      .build[BlockHash, Era]
    new CachingEraStorage[F](underlying, cache)
  }
}
