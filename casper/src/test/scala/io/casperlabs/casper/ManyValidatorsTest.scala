package io.casperlabs.casper

import cats.effect.Sync
import com.github.ghik.silencer.silent
import com.google.protobuf.ByteString
import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper.MultiParentCasperRef.MultiParentCasperRef
import io.casperlabs.casper.api.BlockAPI
import io.casperlabs.casper.helper.BlockGenerator._
import io.casperlabs.casper.helper._
import io.casperlabs.casper.util.BondingUtil.Bond
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.crypto.Keys
import io.casperlabs.shared.Time
import io.casperlabs.storage.BlockMsgWithTransform
import monix.eval.Task
import org.scalatest.{FlatSpec, Matchers}

import scala.collection.immutable.HashMap
import scala.util.Random

@silent("deprecated")
class ManyValidatorsTest extends FlatSpec with Matchers with BlockGenerator with StorageFixture {

  "Show blocks" should "be processed quickly for a node with 300 validators" in withCombinedStorage() {
    implicit storage =>
      val bonds = Seq
        .fill(300)(
          ByteString.copyFromUtf8(Random.nextString(20)).substring(0, 32)
        )
        .map(Bond(_, 10))
      val v1 = Keys.PublicKeyHash(bonds.head.validatorPublicKeyHash)
      for {
        genesis <- createAndStoreMessage[Task](Seq(), EmptyValidator, bonds)(
                    MonadThrowable[Task],
                    Time[Task],
                    storage,
                    storage
                  )
        _ <- createAndStoreMessage[Task](Seq(genesis.blockHash), v1, bonds, bonds.map {
              case Bond(validator, _) => Keys.PublicKeyHash(validator) -> genesis.blockHash
            }.toMap)(MonadThrowable[Task], Time[Task], storage, storage)
        dag                 <- storage.getRepresentation
        latestMessageHashes <- dag.latestMessageHashes
        equivocators        <- dag.getEquivocators
        tips <- Estimator.tips[Task](
                 dag,
                 genesis.blockHash,
                 latestMessageHashes,
                 equivocators
               )
        casperEffect <- NoOpsCasperEffect[Task](
                         HashMap.empty[BlockHash, BlockMsgWithTransform],
                         tips
                       )(Sync[Task], storage, storage)
        implicit0(casperRef: MultiParentCasperRef[Task]) <- MultiParentCasperRef.of[Task]
        _                                                <- casperRef.set(casperEffect)

        result <- BlockAPI.getBlockInfos[Task](Int.MaxValue)
      } yield result
  }
}
