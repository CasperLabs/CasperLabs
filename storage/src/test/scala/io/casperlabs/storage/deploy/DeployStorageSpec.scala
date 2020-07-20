package io.casperlabs.storage.deploy

import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.{Block, Deploy}
import io.casperlabs.casper.consensus.info.DeployInfo
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.crypto.Keys.{PublicKeyHash, PublicKeyHashBS}
import io.casperlabs.models.ArbitraryConsensus
import monix.eval.Task
import org.scalacheck.{Arbitrary, Gen, Shrink}
import org.scalacheck.Arbitrary.arbBool
import org.scalatest._
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import io.casperlabs.shared.Sorting.byteStringOrdering

import scala.concurrent.duration._
import scala.util.Random

/* Trait for testing various implementations of DeployStorageReader and DeployStorageWriter */
trait DeployStorageSpec
    extends WordSpec
    with Matchers
    with ArbitraryConsensus
    with GeneratorDrivenPropertyChecks {

  /* Implement this method in descendants substituting various DeployStorageReader and DeployStorageWriter implementations */
  protected def testFixture(
      test: (DeployStorageReader[Task], DeployStorageWriter[Task]) => Task[Unit],
      timeout: FiniteDuration = 5.seconds
  ): Unit

  protected implicit def noShrink[T]: Shrink[T] = Shrink.shrinkAny

  protected implicit val consensusConfig: ConsensusConfig =
    ConsensusConfig(maxSessionCodeBytes = 50, maxPaymentCodeBytes = 10)

  protected def deploysGen(min: Int = 1): Gen[List[Deploy]] =
    for {
      n       <- Gen.choose(min, 10)
      deploys <- Gen.listOfN(n, arbDeploy.arbitrary)
    } yield deploys

  "DeployStorageReader and DeployStorageWriter" when {
    "((addAsPending + markAsDiscarded) | (addAsProcessed + markAsFinalized)) + getPendingOrProcessed" should {
      "return None" in forAll(deploysGen(), Arbitrary.arbBool.arbitrary) { (ds, bool) =>
        testFixture { (reader, writer) =>
          for {
            _ <- if (bool)
                  writer.addAsPending(ds) >> writer.markAsDiscarded(ds)
                else writer.addAsProcessed(ds) >> writer.markAsFinalized(ds)
            _ <- reader.getPendingOrProcessed(chooseHash(ds)).foreachL(_ shouldBe None)
          } yield ()
        }
      }
    }

    "((addAsPending + markAsProcessed?) | (addAsProcessed + markAsPending?)) + getPendingOrProcessed" should {
      "return Some if exists" in forAll(
        deploysGen(),
        Arbitrary.arbBool.arbitrary,
        Arbitrary.arbBool.arbitrary
      ) { (ds, pendingOrProcessed, secondStep) =>
        testFixture { (reader, writer) =>
          for {
            _ <- if (pendingOrProcessed)
                  writer.addAsPending(ds) >> (if (secondStep) writer.markAsProcessed(ds)
                                              else Task.unit)
                else
                  writer.addAsProcessed(ds) >> (if (secondStep) writer.markAsPending(ds)
                                                else Task.unit)
            _ <- reader.getPendingOrProcessed(chooseHash(ds)).foreachL(_ should not be empty)
          } yield ()
        }
      }
    }

    "(addAsPending + addAsPending) | (addAsProcessed + addAsProcessed)" should {
      "not fail, ignore duplicates and insert new deploys from batch even if contains existing deploys" in forAll(
        Arbitrary.arbBool.arbitrary
      )(
        b =>
          testFixture { (reader, writer) =>
            val d1 = sample(arbDeploy.arbitrary)
            val d2 = sample(arbDeploy.arbitrary)

            val (add1, add2, read) =
              if (b)
                (writer.addAsPending(d1), writer.addAsPending(List(d1, d2)), reader.readPending)
              else
                (
                  writer.addAsProcessed(d1),
                  writer.addAsProcessed(List(d1, d2)),
                  reader.readProcessed
                )
            for {
              _ <- add1
              _ <- add2.attempt.foreachL(_ shouldBe an[Right[_, _]])
              _ <- read
                    .foreachL(
                      _ should contain theSameElementsAs List(d1, d2)
                    )
            } yield ()
          }
      )
    }

    "addAsPending + addAsProcessed + (getByHashes | getByHash)" should {
      "return the same list of deploys" in forAll(deploysGen()) { deploys =>
        testFixture { (reader, writer) =>
          val idx                  = scala.util.Random.nextInt(deploys.size)
          val (pending, processed) = deploys.splitAt(idx)
          val deployHashes         = deploys.map(_.deployHash)
          for {
            _    <- writer.addAsPending(pending)
            _    <- writer.addAsProcessed(processed)
            all1 <- reader.getByHashes(deployHashes.toSet).compile.toList
            all2 <- deployHashes.toList.traverse(reader.getByHash).map(_.flatten)
            _    = all1 should contain theSameElementsAs deploys
            _    = all2 should contain theSameElementsAs deploys
          } yield ()

        }
      }
    }

    "getBufferedStatus" should {
      "return the status from the buffer" in forAll { (deploy: Deploy) =>
        testFixture { (reader, writer) =>
          def checkSome(state: DeployInfo.State, msg: String = "") =
            reader.getBufferedStatus(deploy.deployHash) map {
              _ shouldBe Some(DeployInfo.Status(state, msg))
            }
          def checkNone() =
            reader.getBufferedStatus(deploy.deployHash) map {
              _ shouldBe None
            }

          for {
            _ <- checkNone()
            _ <- writer.addAsPending(List(deploy))
            _ <- checkSome(DeployInfo.State.PENDING)
            _ <- writer.markAsProcessedByHashes(List(deploy.deployHash))
            _ <- checkSome(DeployInfo.State.PROCESSED)
            _ <- writer.markAsFinalizedByHashes(List(deploy.deployHash))
            _ <- checkNone()
            _ <- writer.addAsPending(List(deploy))
            _ <- writer.markAsDiscardedByHashes(List(deploy.deployHash -> "Testing"))
            _ <- checkSome(DeployInfo.State.DISCARDED, "Testing")
          } yield ()

        }
      }
    }

    "countsByBufferedStates" should {
      val data = for {
        deploys   <- Arbitrary.arbitrary[List[Deploy]]
        pending   <- Gen.choose(0, deploys.length)
        processed <- Gen.choose(0, math.max(0, deploys.length - pending))
        discarded <- Gen.choose(0, deploys.length - pending - processed)
      } yield (deploys, pending, processed, discarded)

      "return the statuses from the buffer" in forAll(data) {
        case (deploys, a, b, c) =>
          val pending   = deploys.take(a)
          val processed = deploys.drop(a).take(b)
          val discarded = deploys.drop(a + b).take(c)

          testFixture { (reader, writer) =>
            for {
              _ <- writer.addAsPending(pending)
              _ <- writer.addAsProcessed(processed)
              _ <- writer.addAsPending(discarded)
              _ <- writer.markAsDiscarded(discarded.map(_ -> ""))

              countsAll <- reader.countsByBufferedStates().map(_.withDefaultValue(0))
              _         = countsAll(DeployInfo.State.PENDING) shouldBe pending.length
              _         = countsAll(DeployInfo.State.PROCESSED) shouldBe processed.length
              _         = countsAll(DeployInfo.State.DISCARDED) shouldBe discarded.length

              countSome <- reader
                            .countsByBufferedStates(
                              DeployInfo.State.PENDING,
                              DeployInfo.State.PROCESSED
                            )
                            .map(_.withDefaultValue(0))
              _ = countSome(DeployInfo.State.PENDING) shouldBe pending.length
              _ = countSome(DeployInfo.State.PROCESSED) shouldBe processed.length
              _ = countSome should not contain key(DeployInfo.State.DISCARDED)

              p <- reader.countByBufferedState(DeployInfo.State.PENDING)
              _ = p shouldBe pending.length
            } yield ()
          }
      }
    }

    "readProcessedByAccount" should {
      "return only processed deploys with appropriate account" in testFixture { (reader, writer) =>
        val pending   = sample(arbDeploy.arbitrary)
        val processed = sample(arbDeploy.arbitrary)
        val finalized = sample(arbDeploy.arbitrary)
        val discarded = sample(arbDeploy.arbitrary)
        val wrongAccount =
          processed
            .withHeader(processed.getHeader.withAccountPublicKeyHash(sample(genHash)))
            .withDeployHash(sample(genHash))
        for {
          //given
          _ <- writer.addAsPending(pending)
          _ <- writer.addAsProcessed(List(processed, wrongAccount))
          _ <- writer.addAsDiscarded(discarded)
          _ <- writer.addAsFinalized(finalized)
          //when
          p <- reader.readProcessedByAccount(
                PublicKeyHash(processed.getHeader.accountPublicKeyHash)
              )
          //should
        } yield {
          p shouldBe List(processed)
        }
      }
    }

    "markAsPending" should {
      "affect only processed deploys" in testFixture { (reader, writer) =>
        val pending   = sample(arbDeploy.arbitrary)
        val processed = sample(arbDeploy.arbitrary)
        val finalized = sample(arbDeploy.arbitrary)
        val discarded = sample(arbDeploy.arbitrary)
        val all       = List(pending, processed, finalized, discarded)
        for {
          //given
          _ <- writer.addAsPending(pending)
          _ <- writer.addAsProcessed(processed)
          _ <- writer.addAsDiscarded(discarded)
          _ <- writer.addAsFinalized(finalized)
          //when
          _ <- writer.markAsPending(all)
          //should
          p <- reader.readPending
        } yield {
          p should contain theSameElementsAs List(pending, processed)
        }
      }
    }

    "markAsProcessed" should {
      "affect only pending deploys" in testFixture { (reader, writer) =>
        val pending   = sample(arbDeploy.arbitrary)
        val processed = sample(arbDeploy.arbitrary)
        val finalized = sample(arbDeploy.arbitrary)
        val discarded = sample(arbDeploy.arbitrary)
        val all       = List(pending, processed, finalized, discarded)
        for {
          //given
          _ <- writer.addAsPending(pending)
          _ <- writer.addAsProcessed(processed)
          _ <- writer.addAsDiscarded(discarded)
          _ <- writer.addAsFinalized(finalized)
          //when
          _ <- writer.markAsProcessed(all)
          //should
          p <- reader.readProcessed
        } yield {
          p should contain theSameElementsAs List(pending, processed)
        }
      }
    }

    "markAsFinalized" should {
      "affect only processed deploys" in testFixture { (reader, writer) =>
        val pending   = sample(arbDeploy.arbitrary)
        val processed = sample(arbDeploy.arbitrary)
        val finalized = sample(arbDeploy.arbitrary)
        val discarded = sample(arbDeploy.arbitrary)
        val all       = List(pending, processed, finalized, discarded)
        for {
          //given
          _ <- writer.addAsPending(pending)
          _ <- writer.addAsProcessed(processed)
          _ <- writer.addAsDiscarded(discarded)
          _ <- writer.addAsFinalized(finalized)
          //when
          _ <- writer.markAsFinalized(all)
          //should
          _ <- reader.readProcessed.foreachL(_ shouldBe empty)
          _ <- reader.readPending.foreachL(_ shouldBe List(pending))
          _ <- writer.discardedNum().foreachL(_ shouldBe 1)
        } yield ()
      }
    }

    "markAsDiscarded" should {
      "affect only pending deploys" in testFixture { (reader, writer) =>
        val pending   = sample(arbDeploy.arbitrary)
        val processed = sample(arbDeploy.arbitrary)
        val finalized = sample(arbDeploy.arbitrary)
        val discarded = sample(arbDeploy.arbitrary)
        val all       = List(pending, processed, finalized, discarded)
        for {
          //given
          _ <- writer.addAsPending(pending)
          _ <- writer.addAsProcessed(processed)
          _ <- writer.addAsDiscarded(discarded)
          _ <- writer.addAsFinalized(finalized)
          //when
          _ <- writer.markAsDiscarded(all)
          //should
          _ <- reader.readProcessed.foreachL(_ shouldBe List(processed))
          _ <- reader.readPending.foreachL(_ shouldBe empty)
          _ <- writer.discardedNum().foreachL(_ shouldBe 2)
        } yield ()
      }
    }

    "(addAsPending + markAsDiscarded) | (addAsProcessed + markAsFinalized)" should {
      "return empty response for all 'read*' methods" in forAll(
        deploysGen(),
        Arbitrary.arbBool.arbitrary
      )(
        (ds: List[Deploy], b: Boolean) =>
          testFixture { (reader, writer) =>
            for {
              _ <- if (b) writer.addAsPending(ds) >> writer.markAsDiscarded(ds)
                  else writer.addAsProcessed(ds) >> writer.markAsFinalized(ds)
              _ <- (
                    reader.readPending,
                    reader.readPendingHashes,
                    reader.readProcessed,
                    reader.readProcessedHashes,
                    reader.readProcessedByAccount(chooseAccount(ds))
                  ).mapN {
                    (pending, pendingHashes, processed, processedHashes, processedByAccount) =>
                      pending shouldBe empty
                      pendingHashes shouldBe empty
                      processed shouldBe empty
                      processedHashes shouldBe empty
                      processedByAccount shouldBe empty
                  }
            } yield ()
          }
      )
    }

    "cleanupDiscarded" should {
      "delete discarded deploys only older than 'now - expirationPeriod'" in testFixture(
        { (_, writer) =>
          val first  = sample(arbDeploy.arbitrary)
          val second = sample(arbDeploy.arbitrary)
          for {
            _ <- writer.addAsPending(List(first, second))
            _ <- writer.markAsDiscarded(List(first))
            _ <- Task.sleep(2.seconds)
            _ <- writer.markAsDiscarded(List(second))
            _ <- writer.cleanupDiscarded(expirationPeriod = 1.second).foreachL(_ shouldBe 1)
            _ <- Task.sleep(2.seconds)
            _ <- writer.cleanupDiscarded(expirationPeriod = 1.second).foreachL(_ shouldBe 1)
            _ <- Task.sleep(2.seconds)
            _ <- writer.cleanupDiscarded(expirationPeriod = 1.second).foreachL(_ shouldBe 0)
          } yield ()
        },
        timeout = 15.seconds
      )

      "not delete deploys which were executed before" in
        testFixture { (reader, writer) =>
          val b  = sample(arbBlock.arbitrary.suchThat(_.getBody.deploys.nonEmpty))
          val ds = b.getBody.deploys.map(_.getDeploy).toList
          for {
            _      <- writer.addAsExecuted(b)
            _      <- writer.addAsDiscarded(ds)
            _      <- Task.sleep(200.millis)
            _      <- writer.cleanupDiscarded(Duration.Zero)
            gotNum <- ds.flatTraverse(d => reader.getProcessingResults(d.deployHash)).map(_.size)
          } yield {
            gotNum shouldBe ds.size
          }
        }
    }

    "markAsDiscarded(duration)" should {
      "mark only pending deploys were added more than 'now - expirationPeriod' time ago" in testFixture(
        { (reader, writer) =>
          val first  = sample(arbDeploy.arbitrary)
          val second = sample(arbDeploy.arbitrary)
          for {
            _ <- writer.addAsPending(List(first))
            _ <- reader.pendingNum.foreachL(_ shouldBe 1)
            _ <- Task.sleep(2.seconds)
            _ <- writer.addAsProcessed(List(second))
            _ <- writer.markAsPending(List(second))
            _ <- reader.pendingNum.foreachL(_ shouldBe 2)
            _ <- writer.markAsDiscarded(expirationPeriod = 1.second, "TTL Expired")
            _ <- reader.pendingNum.foreachL(_ shouldBe 1)
            _ <- Task.sleep(2.seconds)
            _ <- writer.markAsDiscarded(expirationPeriod = 1.second, "TTL Expired")
            _ <- reader.pendingNum.foreachL(_ shouldBe 0)
          } yield ()
        },
        timeout = 15.seconds
      )
    }

    "addAsExecuted + addAsExecuted" should {
      "not fail, ignore duplicates and insert new deploys from block even if contains existing deploys" in testFixture {
        (reader, writer) =>
          val b1         = sample(arbBlock.arbitrary)
          val difference = sample(arbBlock.arbitrary)
          val b2 = difference.withBody(
            difference.getBody.withDeploys(difference.getBody.deploys ++ b1.getBody.deploys)
          )

          def processingResults(bs: Block*) =
            bs.toList.flatMap(b => b.getBody.deploys.map((b.blockHash, _)).toList)

          val deployHashesB1         = b1.getBody.deploys.map(_.getDeploy.deployHash).toList
          val deployHashesB2         = b2.getBody.deploys.map(_.getDeploy.deployHash).toList
          val deployHashesDifference = difference.getBody.deploys.map(_.getDeploy.deployHash).toList

          for {
            _ <- writer.addAsExecuted(b1)
            _ <- writer.addAsExecuted(b2).attempt.foreachL(_ shouldBe an[Right[_, _]])
            processingResultsB1 <- deployHashesB1.flatTraverse { deployHash =>
                                    reader.getProcessingResults(deployHash)
                                  }
            processingResultsB2 <- deployHashesB2.flatTraverse { deployHash =>
                                    reader.getProcessingResults(deployHash)
                                  }
            processingResultsDiff <- deployHashesDifference.flatTraverse { deployHash =>
                                      reader.getProcessingResults(deployHash)
                                    }
          } yield {
            processingResultsB1 should contain theSameElementsAs processingResults(
              b1,
              b1.copy(blockHash = b2.blockHash)
            )
            processingResultsB2 should contain theSameElementsAs processingResults(b1, b2)
            processingResultsDiff should contain theSameElementsAs processingResults(difference)
          }
      }
    }

    "(addAsPending | addAsProcessed) + getPendingOrProcessed" should {
      "be able to properly (de)serialize data" in forAll(deploysGen(), arbBool.arbitrary) {
        (deploys, b) =>
          testFixture { (reader, writer) =>
            for {
              _ <- b.pure[Task].ifM(writer.addAsPending(deploys), writer.addAsProcessed(deploys))
              got <- deploys.flatTraverse[Task, Deploy](
                      d => reader.getPendingOrProcessed(d.deployHash).map(_.toList)
                    )
              _ <- Task {
                    val serialized = got.sortedByHash
                    val expected   = deploys.sortedByHash
                    Inspectors.forAll(serialized.zip(expected)) {
                      case (a, b) =>
                        assert(a.toByteArray.sameElements(b.toByteArray))
                    }
                  }
            } yield ()
          }
      }
    }
  }

  private def chooseHash(deploys: List[Deploy]): ByteString =
    deploys(Random.nextInt(deploys.size)).deployHash

  private def chooseAccount(deploys: List[Deploy]): PublicKeyHashBS =
    PublicKeyHash(deploys(Random.nextInt(deploys.size)).getHeader.accountPublicKeyHash)

  private implicit class DeployStorageWriterOps(writer: DeployStorageWriter[Task]) {
    def addAsPending(d: Deploy): Task[Unit]   = writer.addAsPending(List(d))
    def addAsProcessed(d: Deploy): Task[Unit] = writer.addAsProcessed(List(d))
    def addAsDiscarded(ds: List[Deploy]): Task[Unit] =
      writer.addAsPending(ds) >> writer.markAsDiscarded(ds.map((_, "")))
    def addAsDiscarded(d: Deploy): Task[Unit] =
      addAsDiscarded(List(d))
    def markAsDiscarded(ds: List[Deploy]): Task[Unit] = writer.markAsDiscarded(ds.map((_, "")))
    def addAsFinalized(d: Deploy): Task[Unit] =
      writer.addAsProcessed(List(d)) >> writer.markAsFinalized(List(d))
    def discardedNum(): Task[Int] = Task.sleep(50.millis) >> writer.cleanupDiscarded(Duration.Zero)
  }

  private implicit class DeployStorageReaderOps(reader: DeployStorageReader[Task]) {
    def pendingNum: Task[Int] = reader.readPending.map(_.size)
  }

  private implicit class DeploysOps(ds: List[Deploy]) {
    implicit val deployOrdering: Ordering[Deploy] =
      Ordering.by(d => Base16.encode(d.deployHash.toByteArray))
    def sortedByHash: List[Deploy] = ds.sorted
  }
}
