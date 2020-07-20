package io.casperlabs.smartcontracts

import java.nio.file.Path

import cats.effect.{Concurrent, Resource, Sync}
import cats.implicits._
import cats.Defer
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.Bond
import io.casperlabs.crypto.Keys.PublicKey
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.casper.consensus.state.{Unit => SUnit, _}
import io.casperlabs.ipc._
import io.casperlabs.metrics.Metrics
import io.casperlabs.models.SmartContractEngineError
import io.casperlabs.shared.Log
import io.casperlabs.models.bytesrepr.FromBytes
import io.casperlabs.models.cltype.StoredValue
import io.casperlabs.smartcontracts.ExecutionEngineService.Stub
import monix.eval.{Task, TaskLift}
import simulacrum.typeclass

import scala.util.Either
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.ipc.ChainSpec.UpgradePoint

@typeclass trait ExecutionEngineService[F[_]] {
  def emptyStateHash: ByteString

  def runGenesis(
      genesisConfig: RunGenesisRequest
  ): F[Either[Throwable, GenesisResult]]

  def upgrade(
      prestate: ByteString,
      upgrade: UpgradePoint,
      protocolVersion: ProtocolVersion
  ): F[Either[Throwable, UpgradeResult]]

  /** Executes a sequence of deploys, returning the results in the same order as the inputs. */
  def exec(
      prestate: ByteString,
      blocktime: Long,
      deploys: Seq[DeployItem],
      protocolVersion: ProtocolVersion
  ): F[Either[Throwable, Seq[DeployResult]]]

  def commit(
      prestate: ByteString,
      effects: Seq[TransformEntry],
      protocolVersion: ProtocolVersion
  ): F[Either[Throwable, ExecutionEngineService.CommitResult]]

  def query(
      state: ByteString,
      baseKey: Key,
      path: Seq[String],
      protocolVersion: ProtocolVersion
  ): F[Either[Throwable, StoredValue]]
}

class GrpcExecutionEngineService[F[_]: Defer: Concurrent: Log: TaskLift: Metrics] private[smartcontracts] (
    addr: Path,
    stub: Stub,
    messageSizeLimit: Int,
    // Target parallelism that the EE supports via the --threads parameter.
    // We can call send more requests since gRPC will handle the concurrent
    // requests (i.e. we didn't need locking when EE was using just a single thread),
    // but we should strive to send at least this many if we can, to achieve
    // higher throughput.
    parallelism: Int
) extends ExecutionEngineService[F] {
  import GrpcExecutionEngineService.EngineMetricsSource

  override def emptyStateHash: ByteString = {
    val arr: Array[Byte] = Array(
      197, 117, 38, 12, 241, 62, 54, 241, 121, 165, 11, 8, 130, 189, 100, 252, 4, 102, 236, 210, 91,
      221, 123, 200, 135, 102, 194, 204, 46, 76, 13, 254
    ).map(_.toByte)
    ByteString.copyFrom(arr)
  }

  private def sendMessage[A, B, R](msg: A, rpc: Stub => A => Task[B])(f: B => R): F[R] =
    rpc(stub)(msg).to[F].map(f(_)).recoverWith {
      case ex: io.grpc.StatusRuntimeException
          if ex.getStatus.getCode == io.grpc.Status.Code.UNAVAILABLE &&
            ex.getCause != null &&
            ex.getCause.isInstanceOf[java.io.FileNotFoundException] =>
        Sync[F].raiseError(
          new java.io.FileNotFoundException(
            s"It looks like the Execution Engine is not listening at the socket file ${addr}"
          )
        )
    }

  private def sendExecute(
      request: ExecuteRequest
  ): F[Either[SmartContractEngineError, Seq[DeployResult]]] =
    sendMessage(request, _.execute) {
      _.result match {
        case ExecuteResponse.Result.Success(ExecResult(deployResults)) =>
          Right(deployResults) //TODO: Capture errors better than just as a string
        case ExecuteResponse.Result.Empty =>
          Left(new SmartContractEngineError("empty response"))
        case ExecuteResponse.Result.MissingParent(RootNotFound(missing)) =>
          Left(
            new SmartContractEngineError(
              s"Missing states: ${Base16.encode(missing.toByteArray)}"
            )
          )
      }
    }

  private def updateGasMetrics(
      deployResults: Either[SmartContractEngineError, Seq[DeployResult]]
  ): F[Unit] =
    deployResults.fold(
      _ => ().pure[F],
      deployResults => {
        // XXX: EE returns cost as BigInt but metrics are in Long. In practice it will be unlikely exhaust the limits of Long.
        val gasSpent =
          deployResults.foldLeft(0L)(
            (a, d) => a + d.value.executionResult.fold(0L)(_.cost.fold(0L)(_.value.toLong))
          )
        Metrics[F].incrementCounter("gas_spent", gasSpent)
      }
    )

  override def exec(
      prestate: ByteString,
      blocktime: Long,
      deploys: Seq[DeployItem],
      protocolVersion: ProtocolVersion
  ): F[Either[Throwable, Seq[DeployResult]]] = Metrics[F].timer("eeExec") {
    val baseExecRequest =
      ExecuteRequest(prestate, blocktime, protocolVersion = Some(protocolVersion))
    // Build batches limited by the size of message sent to EE, targeting the level of
    // parallelism the EE is supposed to be configured with.
    val batches =
      ExecutionEngineService.batchDeploys(baseExecRequest, messageSizeLimit, parallelism)(deploys)

    val stream = fs2.Stream.evalSeq(batches.pure[F]).mapAsync(parallelism)(sendExecute)

    for {
      result <- stream.compile.toList.map(_.sequence.map(_.flatten))
      _      <- updateGasMetrics(result)
    } yield result
  }

  override def runGenesis(
      genesisConfig: RunGenesisRequest
  ): F[Either[Throwable, GenesisResult]] =
    sendMessage(genesisConfig, _.runGenesis) {
      _.result match {
        case GenesisResponse.Result.Success(result) =>
          Right(result)
        case GenesisResponse.Result.FailedDeploy(error) =>
          Left(new SmartContractEngineError(error.message))
        case GenesisResponse.Result.Empty =>
          Left(new SmartContractEngineError("empty response"))
      }
    }

  def upgrade(
      prestate: ByteString,
      upgrade: UpgradePoint,
      protocolVersion: ProtocolVersion
  ): F[Either[Throwable, UpgradeResult]] =
    sendMessage(
      UpgradeRequest(prestate, Some(upgrade), Some(protocolVersion)),
      _.upgrade
    ) {
      _.result match {
        case UpgradeResponse.Result.Success(result) =>
          Right(result)
        case UpgradeResponse.Result.FailedDeploy(error) =>
          Left(new SmartContractEngineError(error.message))
        case UpgradeResponse.Result.Empty =>
          Left(new SmartContractEngineError("empty response"))
      }
    }

  override def commit(
      prestate: ByteString,
      effects: Seq[TransformEntry],
      protocolVersion: ProtocolVersion
  ): F[Either[Throwable, ExecutionEngineService.CommitResult]] =
    Metrics[F].timer("eeCommit") {
      sendMessage(CommitRequest(prestate, effects, Some(protocolVersion)), _.commit) {
        _.result match {
          case CommitResponse.Result.Success(commitResult) =>
            Right(ExecutionEngineService.CommitResult(commitResult))
          case CommitResponse.Result.Empty => Left(SmartContractEngineError("empty response"))
          case CommitResponse.Result.MissingPrestate(RootNotFound(hash)) =>
            Left(SmartContractEngineError(s"Missing pre-state: ${Base16.encode(hash.toByteArray)}"))
          case CommitResponse.Result.FailedTransform(PostEffectsError(message)) =>
            Left(SmartContractEngineError(s"Error executing transform: $message"))
          case CommitResponse.Result.KeyNotFound(value) =>
            Left(SmartContractEngineError(s"Key not found in global state: $value"))
          case CommitResponse.Result.TypeMismatch(err) =>
            Left(SmartContractEngineError(err.toString))
        }
      }
    }

  override def query(
      state: ByteString,
      baseKey: Key,
      path: Seq[String],
      protocolVersion: ProtocolVersion
  ): F[Either[Throwable, StoredValue]] =
    sendMessage(QueryRequest(state, Some(baseKey), path, Some(protocolVersion)), _.query) {
      _.result match {
        case QueryResponse.Result.Success(bytes) =>
          FromBytes
            .deserialize(StoredValue.deserializer, bytes.toByteArray)
            .leftMap(err => SmartContractEngineError(s"Error in parsing EE response: $err"))

        case QueryResponse.Result.Empty        => Left(SmartContractEngineError("empty response"))
        case QueryResponse.Result.Failure(err) => Left(SmartContractEngineError(err))
      }
    }
}

object ExecutionEngineService {
  type Stub = IpcGrpcMonix.ExecutionEngineServiceStub

  class CommitResult private (val postStateHash: ByteString, val bondedValidators: Seq[Bond])

  object CommitResult {
    def apply(ipcCommitResult: io.casperlabs.ipc.CommitResult): CommitResult = {
      // XXX: EE returns bonds as BigInt but we treat it as Long.
      val validators = ipcCommitResult.bondedValidators.map(
        b => Bond(b.validatorAccountHash, b.stake)
      )
      new CommitResult(ipcCommitResult.poststateHash, validators)
    }

    def apply(postStateHash: ByteString, bonds: Seq[Bond]): CommitResult =
      new CommitResult(postStateHash, bonds)
  }

  def batchElements[A](
      items: Seq[A],
      canAdd: (List[A], A) => Boolean
  ): List[List[A]] =
    items
      .foldRight(List.empty[List[A]]) {
        case (item, Nil) => List(item) :: Nil
        case (item, hd :: tl) =>
          if (canAdd(hd, item))
            (item :: hd) :: tl
          else
            List(item) :: hd :: tl
      }

  /** Partition items into pre-determined sized groups. */
  def groupElements[A](
      items: Seq[A],
      groupSizes: List[Int]
  ): List[Seq[A]] = {
    def loop(acc: List[Seq[A]], items: Seq[A], groupSizes: List[Int]): List[Seq[A]] =
      if (groupSizes.isEmpty || items.isEmpty) acc
      else {
        val (hd, tl) = items.splitAt(groupSizes.head)
        loop(hd :: acc, tl, groupSizes.tail)
      }

    loop(Nil, items, groupSizes).reverse
  }

  /** Calculate balanced sizes for partitioning a sequence of items of given length. */
  def groupSizes(size: Int, parallelism: Int): List[Int] = {
    require(parallelism > 0)
    val minSize = size / parallelism

    def loop(acc: List[Int], size: Int, remainder: Int): List[Int] =
      if (size == 0) acc
      else if (remainder > 0) loop((minSize + 1) :: acc, size - minSize - 1, remainder - 1)
      else loop(minSize :: acc, size - minSize, 0)

    loop(Nil, size, size % parallelism)
  }

  /** Batch deploys into a target level of parallelism,
    * making sure no batch exceeds the size limit.
    * The order of elements needs to be preserved. */
  def batchDeploys(base: ExecuteRequest, messageSizeLimit: Int, parallelism: Int = 1)(
      deploys: Seq[DeployItem]
  ): List[ExecuteRequest] = {
    val canAdd: (List[DeployItem], DeployItem) => Boolean =
      (batch, item) =>
        base
          .withDeploys(item :: batch)
          .serializedSize <= messageSizeLimit

    val sizes = groupSizes(deploys.size, parallelism)

    groupElements(deploys, sizes)
      .flatMap(batchElements(_, canAdd).map(base.withDeploys(_)))
  }

}

object GrpcExecutionEngineService {
  implicit val EngineMetricsSource: Metrics.Source =
    Metrics.Source(Metrics.BaseSource, "engine")

  private def initializeMetrics[F[_]: Metrics] =
    Metrics[F].incrementCounter("gas_spent", 0)

  def apply[F[_]: Concurrent: Log: TaskLift: Metrics](
      addr: Path,
      maxMessageSize: Int,
      parallelism: Int
  ): Resource[F, GrpcExecutionEngineService[F]] =
    for {
      service <- new ExecutionEngineConf[F](addr, maxMessageSize, parallelism).apply
      _       <- Resource.liftF(initializeMetrics)
    } yield service
}
