package io.casperlabs.storage.deploy

import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.info.DeployInfo
import io.casperlabs.crypto.Keys.PublicKeyHash
import io.casperlabs.shared.Sorting.byteStringOrdering
import io.casperlabs.storage.{SQLiteFixture, SQLiteStorage}
import monix.eval.Task
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen

import scala.concurrent.duration._

class SQLiteDeployStorageSpec
    extends DeployStorageSpec
    with SQLiteFixture[(DeployStorageReader[Task], DeployStorageWriter[Task])] {

  override protected def testFixture(
      test: (DeployStorageReader[Task], DeployStorageWriter[Task]) => Task[Unit],
      timeout: FiniteDuration = 5.seconds
  ): Unit = testFixtureWithView(test, timeout, DeployInfo.View.FULL)

  private def testFixtureWithView(
      test: (DeployStorageReader[Task], DeployStorageWriter[Task]) => Task[Unit],
      timeout: FiniteDuration = 5.seconds,
      deployView: DeployInfo.View
  ): Unit = runSQLiteTest[Unit](createTestResource(deployView), test.tupled, timeout)

  override def db: String = "/tmp/deploy_storage.db"

  override def numAccounts: Int   = 2
  override def numValidators: Int = 2

  override def createTestResource: Task[(DeployStorageReader[Task], DeployStorageWriter[Task])] =
    createTestResource(DeployInfo.View.FULL)

  private def createTestResource(
      deployView: DeployInfo.View
  ): Task[(DeployStorageReader[Task], DeployStorageWriter[Task])] =
    SQLiteStorage
      .create[Task](readXa = xa, writeXa = xa)
      .map(s => (s.reader(deployView), s.writer))

  "SQLiteDeployStorage" should {
    "not fetch the body if it's not asked for" in forAll(
      deploysGen(),
      Gen.oneOf(randomAccounts)
    ) {
      case (deploys, accountKey) =>
        testFixtureWithView(
          deployView = DeployInfo.View.BASIC,
          test = { (reader: DeployStorageReader[Task], writer: DeployStorageWriter[Task]) =>
            val accountDeploysWithoutBody = deploys
              .filter(_.getHeader.accountPublicKeyHash == accountKey.publicKeyHash)
              .sortBy(d => (d.getHeader.timestamp, d.deployHash))
              .reverse
              .map(_.clearBody)

            for {
              _ <- writer.addAsPending(deploys)
              all <- reader.getDeploysByAccount(
                      PublicKeyHash(accountKey.publicKeyHash),
                      limit = Int.MaxValue,
                      lastTimeStamp = Long.MaxValue,
                      lastDeployHash = ByteString.EMPTY,
                      isNext = true
                    )
              _ = all should contain theSameElementsInOrderAs accountDeploysWithoutBody
            } yield ()
          }
        )
    }

    "getDeploysByAccount" should {
      "return the correct paginated list of deploys for the specified account" in forAll(
        for {
          deploys              <- deploysGen()
          accountKey           <- Gen.oneOf(randomAccounts)
          accountPublicKeyHash = accountKey.publicKeyHash
          deploysByAccount = deploys
            .filter(_.getHeader.accountPublicKeyHash == accountPublicKeyHash)
            .sortBy(d => (d.getHeader.timestamp, d.deployHash))
            .reverse
          offset = if (deploysByAccount.isEmpty) {
            0
          } else {
            scala.util.Random.nextInt(deploysByAccount.size)
          }
          limit  <- Gen.choose(0, 100)
          isNext <- arbitrary[Boolean]
        } yield (deploys, accountPublicKeyHash, deploysByAccount, offset, limit, isNext)
      ) {
        case (deploys, accountPublicKeyHash, deploysByAccount, offset, limit, isNext) =>
          testFixture { (reader, writer) =>
            val (lastTimeStamp, lastDeployHash) =
              deploysByAccount
                .get(offset.toLong)
                .map(d => (d.getHeader.timestamp, d.deployHash))
                .getOrElse((Long.MaxValue, ByteString.EMPTY))

            val expectResult = if (isNext) {
              deploysByAccount.slice(offset + 1, offset + 1 + limit)
            } else {
              val reverseOffset = deploysByAccount.size - 1 - offset
              deploysByAccount.reverse.slice(reverseOffset + 1, reverseOffset + 1 + limit).reverse
            }

            for {
              _ <- writer.addAsPending(deploys)
              all <- reader.getDeploysByAccount(
                      PublicKeyHash(accountPublicKeyHash),
                      limit,
                      lastTimeStamp,
                      lastDeployHash,
                      isNext
                    )
              _ = assert(expectResult == all)
            } yield ()
          }
      }
    }
  }
}
