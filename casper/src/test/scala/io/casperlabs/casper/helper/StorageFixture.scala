package io.casperlabs.casper.helper

import java.nio.file.{Files, Path}

import cats.effect._
import cats.implicits._
import doobie.util.ExecutionContexts
import doobie.util.transactor.Transactor
import io.casperlabs.catscontrib.Fs2Compiler
import io.casperlabs.catscontrib.TaskContrib.TaskOps
import io.casperlabs.metrics.Metrics
import io.casperlabs.metrics.Metrics.MetricsNOP
import io.casperlabs.shared.{Log, Time}
import io.casperlabs.storage.SQLiteStorage
import io.casperlabs.storage.block.BlockStorage
import io.casperlabs.storage.dag.{FinalityStorage, IndexedDagStorage}
import io.casperlabs.storage.deploy.DeployStorage
import java.sql.Connection
import javax.sql.DataSource
import java.util.Properties
import monix.eval.Task
import monix.execution.Scheduler
import monix.execution.schedulers.SchedulerService
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.Location
import org.scalatest.Suite
import org.sqlite.{SQLiteConnection, SQLiteDataSource}
import scala.concurrent.ExecutionContext

trait StorageFixture { self: Suite =>
  val scheduler: SchedulerService     = Scheduler.fixedPool("storage-fixture-scheduler", 4)
  implicit val metrics: Metrics[Task] = new MetricsNOP[Task]()
  implicit val log: Log[Task]         = Log.NOPLog[Task]

  def withStorage[R](
      f: BlockStorage[Task] => IndexedDagStorage[Task] => DeployStorage[Task] => FinalityStorage[
        Task
      ] => Task[R]
  ): R = {
    val testProgram = StorageFixture.createMemoryStorages[Task](scheduler).use {
      case (blockStorage, dagStorage, deployStorage, finalityStorage) =>
        f(blockStorage)(dagStorage)(deployStorage)(finalityStorage)
    }
    testProgram.unsafeRunSync(scheduler)
  }
}

object StorageFixture {

  type Storages[F[_]] =
    F[(BlockStorage[F], IndexedDagStorage[F], DeployStorage[F], FinalityStorage[F])]

  // The HashSetCasperTests are not closing the connections properly, so we are better off
  // storing data in temporary files, rather than fill up the memory with unclosed databases.
  def createFileStorages[F[_]: Metrics: Concurrent: ContextShift: Fs2Compiler: Time](
      connectEC: ExecutionContext = Scheduler.Implicits.global
  ): Storages[F] = {
    val createDbFile = Concurrent[F].delay(Files.createTempFile("casperlabs-storages-test-", ".db"))

    for {
      db       <- createDbFile
      ds       = new org.sqlite.SQLiteDataSource()
      _        = ds.setUrl(s"jdbc:sqlite:$db")
      storages <- createStorages[F](ds, connectEC)
    } yield storages
  }

  // Tests using in-memory storage are faster.
  def createMemoryStorages[F[_]: Metrics: Concurrent: ContextShift: Fs2Compiler: Time](
      connectEC: ExecutionContext = Scheduler.Implicits.global
  ): Resource[
    F,
    (BlockStorage[F], IndexedDagStorage[F], DeployStorage[F], FinalityStorage[F])
  ] = {
    val dataSourceR = Resource[F, DataSource] {
      Concurrent[F].delay {
        val ds = new InMemoryDataSource()
        ds -> Concurrent[F].delay(ds.connection.doClose())
      }
    }
    for {
      ds       <- dataSourceR
      storages <- Resource.liftF(createStorages[F](ds, connectEC))
    } yield storages
  }

  private def createStorages[F[_]: Metrics: Concurrent: ContextShift: Fs2Compiler: Time](
      ds: DataSource,
      connectEC: ExecutionContext
  ) =
    for {
      _                 <- initTables(ds)
      xa                = createTransactor(ds, connectEC)
      storage           <- SQLiteStorage.create[F](readXa = xa, writeXa = xa)
      indexedDagStorage <- IndexedDagStorage.create[F](storage)
    } yield (storage, indexedDagStorage, storage, storage)

  private def initTables[F[_]: Concurrent](ds: DataSource): F[Unit] =
    Concurrent[F].delay {
      val flyway = {
        val conf =
          Flyway
            .configure()
            .dataSource(ds)
            .locations(new Location("classpath:/db/migration"))
        conf.load()
      }
      flyway.migrate()
    }.void

  private def createTransactor[F[_]: Async: ContextShift](ds: DataSource, ec: ExecutionContext) =
    Transactor
      .fromDataSource[F](ds, ec, Blocker.liftExecutionContext(ExecutionContexts.synchronous))

  private class InMemoryDataSource extends SQLiteDataSource {
    setUrl("jdbc:sqlite::memory:")

    val connection =
      new NonClosingConnection(getUrl(), ":memory:", getConfig().toProperties())

    override def getConnection(): Connection =
      connection

    override def getConnection(username: String, password: String): SQLiteConnection =
      connection
  }

  private class NonClosingConnection(
      url: String,
      fileName: String,
      props: Properties
  ) extends org.sqlite.jdbc4.JDBC4Connection(url, fileName, props) {
    // Flyway would close the connection and discard the in-memory DB.
    override def close() = ()
    def doClose()        = super.close()
  }
}
