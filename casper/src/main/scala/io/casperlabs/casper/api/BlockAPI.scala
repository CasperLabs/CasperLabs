package io.casperlabs.casper.api

import cats.Monad
import cats.effect.concurrent.Semaphore
import cats.effect.{Concurrent, Resource}
import cats.implicits._
import com.google.protobuf.ByteString
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.NonNegative
import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.casper.MultiParentCasperImpl.Broadcaster
import io.casperlabs.casper.MultiParentCasperRef.MultiParentCasperRef
import io.casperlabs.casper._
import io.casperlabs.casper.consensus._
import io.casperlabs.casper.consensus.info._
import io.casperlabs.catscontrib.{Fs2Compiler, MonadThrowable}
import io.casperlabs.comm.ServiceError
import io.casperlabs.comm.ServiceError._
import io.casperlabs.comm.gossiping.relaying.DeployRelaying
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.crypto.hash.Blake2b256
import io.casperlabs.mempool.DeployBuffer
import io.casperlabs.metrics.Metrics
import io.casperlabs.shared.{FatalError, Log}
import io.casperlabs.smartcontracts.ExecutionEngineService
import io.casperlabs.models.cltype
import io.casperlabs.models.cltype.{ByteArray32, CLValueInstance}
import io.casperlabs.storage.StorageError
import io.casperlabs.storage.block.BlockStorage
import io.casperlabs.storage.dag.DagStorage
import io.casperlabs.storage.deploy.{DeployStorage, DeployStorageReader}

object BlockAPI {

  // GraphQL can serve processed deploys, if asked for.
  type BlockAndMaybeDeploys = (BlockInfo, Option[List[Block.ProcessedDeploy]])

  private implicit val metricsSource: Metrics.Source =
    Metrics.Source(CasperMetricsSource, "block-api")

  private def unsafeWithCasper[F[_]: MonadThrowable: Log: MultiParentCasperRef, A](
      msg: String
  )(f: MultiParentCasper[F] => F[A]): F[A] =
    MultiParentCasperRef
      .withCasper[F, A](
        f,
        msg,
        MonadThrowable[F].raiseError(Unavailable("Casper instance not available yet."))
      )

  /** Export base 0 values so we have non-empty series for charts. */
  def establishMetrics[F[_]: Monad: Metrics] =
    for {
      _ <- Metrics[F].incrementCounter("deploys", 0)
      _ <- Metrics[F].incrementCounter("deploys-success", 0)
      _ <- Metrics[F].incrementCounter("create-blocks", 0)
      _ <- Metrics[F].incrementCounter("create-blocks-success", 0)
    } yield ()

  def deploy[F[_]: MonadThrowable: Metrics: DeployBuffer: DeployRelaying](
      d: Deploy
  ): F[Unit] =
    for {
      _ <- Metrics[F].incrementCounter("deploys")
      r <- DeployBuffer[F].addDeploy(d)
      _ <- r match {
            case Right(_) =>
              Metrics[F].incrementCounter("deploys-success")
            case Left(ex: IllegalArgumentException) =>
              MonadThrowable[F].raiseError[Unit](InvalidArgument(ex.getMessage))
            case Left(ex: IllegalStateException) =>
              MonadThrowable[F].raiseError[Unit](FailedPrecondition(ex.getMessage))
            case Left(ex) =>
              MonadThrowable[F].raiseError[Unit](ex)
          }
      _ <- DeployRelaying[F].relay(List(d.deployHash))
    } yield ()

  def propose[F[_]: Concurrent: MultiParentCasperRef: Log: Metrics: Broadcaster](
      blockApiLock: Semaphore[F],
      canCreateBallot: Boolean
  ): F[ByteString] = {
    def raise[A](ex: ServiceError.Exception): F[ByteString] =
      MonadThrowable[F].raiseError(ex)

    unsafeWithCasper[F, ByteString]("Could not create block.") { implicit casper =>
      Resource.make(blockApiLock.tryAcquire)(blockApiLock.release.whenA).use {
        case true =>
          for {
            _          <- Metrics[F].incrementCounter("create-blocks")
            maybeBlock <- casper.createMessage(canCreateBallot)
            result <- maybeBlock match {
                       case Created(block) =>
                         for {
                           status <- casper.addBlock(block)
                           _      <- Broadcaster[F].networkEffects(block, status)
                           res <- status match {
                                   case _: ValidBlock =>
                                     block.blockHash.pure[F]
                                   case SelfEquivocatedBlock =>
                                     Concurrent[F].start(
                                       FatalError.selfEquivocationError(block.blockHash)
                                     ) >> raise(
                                       Internal(s"Node has equivocated with block ${PrettyPrinter
                                         .buildString(block.blockHash)}")
                                     )
                                   case _: InvalidBlock =>
                                     raise(InvalidArgument(s"Invalid block: $status"))
                                   case UnexpectedBlockException(ex) =>
                                     raise(Internal(s"Error during block processing: $ex"))
                                   case Processing | Processed =>
                                     raise(
                                       Aborted(
                                         "No action taken since other thread is already processing the block."
                                       )
                                     )
                                 }
                           _ <- Metrics[F].incrementCounter("create-blocks-success")
                         } yield res

                       case InternalDeployError(ex) =>
                         raise(Internal(ex.getMessage))

                       case ReadOnlyMode =>
                         raise(FailedPrecondition("Node is in read-only mode."))

                       case NoNewDeploys =>
                         raise(OutOfRange("No new deploys."))
                     }
          } yield result

        case false =>
          raise(
            Aborted(
              "There is another propose in progress, or node hasn't synced yet. Try again later."
            )
          )
      }
    }
  }

  def getDeployInfoOpt[F[_]: MonadThrowable: Log: BlockStorage: DeployStorage](
      deployHashBase16: String,
      deployView: DeployInfo.View
  ): F[Option[DeployInfo]] =
    if (deployHashBase16.length != 64) {
      Log[F].warn("Deploy hash must be 32 bytes long") >> none[DeployInfo].pure[F]
    } else {
      val deployHash = ByteString.copyFrom(Base16.decode(deployHashBase16))
      DeployStorage[F].reader(deployView).getDeployInfo(deployHash)
    }

  def getDeployInfo[F[_]: MonadThrowable: Log: BlockStorage: DeployStorage](
      deployHashBase16: String,
      deployView: DeployInfo.View
  ): F[DeployInfo] =
    getDeployInfoOpt[F](deployHashBase16, deployView).flatMap(
      _.fold(
        MonadThrowable[F]
          .raiseError[DeployInfo](NotFound.deploy(deployHashBase16))
      )(_.pure[F])
    )

  def getBlockDeploys[F[_]: Monad: BlockStorage: DeployStorage](
      blockHashBase16: String,
      deployView: DeployInfo.View
  ): F[List[Block.ProcessedDeploy]] =
    BlockStorage[F]
      .getBlockInfoByPrefix(blockHashBase16)
      .flatMap {
        _.fold(List.empty[Block.ProcessedDeploy].pure[F]) { info =>
          DeployStorage[F].reader(deployView).getProcessedDeploys(info.getSummary.blockHash)
        }
      }

  def getBlockInfoWithDeploys[F[_]: MonadThrowable: BlockStorage: DeployStorage: DagStorage](
      blockHash: BlockHash,
      maybeDeployView: Option[DeployInfo.View],
      blockView: BlockInfo.View
  ): F[BlockAndMaybeDeploys] =
    for {
      blockInfo <- BlockStorage[F]
                    .getBlockInfo(blockHash)
                    .flatMap(
                      _.fold(
                        MonadThrowable[F]
                          .raiseError[BlockInfo](
                            NotFound.block(blockHash)
                          )
                      )(_.pure[F])
                    )
      withDeploys <- withViews[F](blockInfo, maybeDeployView, blockView)
    } yield withDeploys

  def getBlockInfoWithDeploysOpt[F[_]: Monad: BlockStorage: DeployStorage: DagStorage](
      blockHashBase16: String,
      maybeDeployView: Option[DeployInfo.View],
      blockView: BlockInfo.View
  ): F[Option[BlockAndMaybeDeploys]] =
    BlockStorage[F]
      .getBlockInfoByPrefix(blockHashBase16)
      .flatMap(
        _.traverse(
          withViews[F](_, maybeDeployView, blockView)
        )
      )

  private def withViews[F[_]: Monad: DeployStorage: DagStorage](
      blockInfo: BlockInfo,
      maybeDeployView: Option[DeployInfo.View],
      blockView: BlockInfo.View
  ): F[BlockAndMaybeDeploys] = {
    val deploysF = maybeDeployView.fold(none[List[Block.ProcessedDeploy]].pure[F]) { implicit dv =>
      DeployStorageReader[F]
        .getProcessedDeploys(blockInfo.getSummary.blockHash)
        .map(_.some)
    }

    val childrenF = blockView match {
      case BlockInfo.View.BASIC | BlockInfo.View.Unrecognized(_) =>
        List.empty[ByteString].pure[F]
      case BlockInfo.View.FULL =>
        for {
          dag      <- DagStorage[F].getRepresentation
          children <- dag.children(blockInfo.getSummary.blockHash)
        } yield children.toList
    }

    (deploysF, childrenF).mapN {
      case (maybeDeploys, maybeChildren) =>
        blockInfo.withStatus(blockInfo.getStatus.withChildHashes(maybeChildren)) -> maybeDeploys
    }
  }

  def getBlockInfo[F[_]: MonadThrowable: BlockStorage: DeployStorage: DagStorage](
      blockHashBase16: String,
      blockView: BlockInfo.View
  ): F[BlockInfo] =
    getBlockInfoWithDeploysOpt[F](blockHashBase16, None, blockView).flatMap(
      _.fold(
        MonadThrowable[F]
          .raiseError[BlockInfo](
            NotFound(s"Cannot find block matching hash $blockHashBase16")
          )
      )(_._1.pure[F])
    )

  /** Return block infos and maybe the corresponding deploy summaries in the a slice of the DAG.
    * Use `maxRank` 0 to get the top slice,
    * then we pass previous ranks to paginate backwards. */
  def getBlockInfosWithDeploys[F[_]: MonadThrowable: Log: DeployStorage: DagStorage: Fs2Compiler](
      depth: Int,
      maxRank: Long,
      maybeDeployView: Option[DeployInfo.View],
      blockView: BlockInfo.View
  ): F[List[BlockAndMaybeDeploys]] =
    DagStorage[F].getRepresentation flatMap { dag =>
      maxRank match {
        case 0 =>
          dag.topoSortTail(depth).compile.toVector
        case r =>
          dag
            .topoSort(
              endBlockNumber = r,
              startBlockNumber = math.max(r - depth + 1, 0)
            )
            .compile
            .toVector
      }
    } recoverWith {
      case ex: StorageError =>
        MonadThrowable[F].raiseError(InvalidArgument(StorageError.errorMessage(ex)))
      case ex: IllegalArgumentException =>
        MonadThrowable[F].raiseError(InvalidArgument(ex.getMessage))
    } flatMap { infosByRank =>
      infosByRank.flatten.reverse.toList.traverse { info =>
        withViews[F](info, maybeDeployView, blockView)
      }
    }

  /** Return block infos in the a slice of the DAG. Use `maxRank` 0 to get the top slice,
    * then we pass previous ranks to paginate backwards. */
  def getBlockInfos[F[_]: MonadThrowable: Log: DeployStorage: DagStorage: Fs2Compiler](
      depth: Int,
      maxRank: Long = 0,
      blockView: BlockInfo.View = BlockInfo.View.BASIC
  ): F[List[BlockInfo]] =
    getBlockInfosWithDeploys[F](depth, maxRank, None, blockView).map(_.map(_._1))

  def accountBalance[F[_]: MonadThrowable: Log: MultiParentCasperRef: DeployStorage: DagStorage: Fs2Compiler: ExecutionEngineService](
      accountKey: ByteString
  ): F[BigInt] = {
    val program = for {
      info <- BlockAPI
               .getBlockInfos[F](1)
               .map(_.head) // Safe to unwrap because there is at least a genesis block
      stateHash       = info.getSummary.getHeader.getState.postStateHash
      protocolVersion = info.getSummary.getHeader.getProtocolVersion
      error           = (s: String) => new IllegalStateException(s)
      getState = (k: cltype.Key) =>
        ExecutionEngineService[F]
          .query(stateHash, cltype.protobuf.Mappings.toProto(k), Nil, protocolVersion)
          .rethrow
      accountKey <- MonadThrowable[F].fromOption(
                     ByteArray32(accountKey.toByteArray),
                     error("Account key must be 32 bytes long")
                   )
      account <- getState(cltype.Key.Account(accountKey)).flatMap {
                  case cltype.StoredValue.Account(account) => account.pure[F]
                  case x =>
                    MonadThrowable[F]
                      .raiseError[cltype.Account](error(s"Expected cltype.Account, got $x"))
                }
      hash <- MonadThrowable[F].fromOption(
               ByteArray32(account.mainPurse.address.bytes.toArray),
               error("Address must 32 bytes long")
             )
      balanceUref <- getState(cltype.Key.Hash(hash)).flatMap {
                      case cltype.StoredValue.CLValue(clValue) =>
                        cltype.CLValueInstance
                          .from(clValue)
                          .fold(
                            e => MonadThrowable[F].raiseError[ByteArray32](error(e.toString)), {
                              case CLValueInstance.Key(cltype.Key.URef(uref)) =>
                                uref.address.pure[F]
                              case x =>
                                MonadThrowable[F]
                                  .raiseError[ByteArray32](
                                    error(s"Expected cltype.Key.URef, got $x")
                                  )
                            }
                          )
                      case x =>
                        MonadThrowable[F]
                          .raiseError[ByteArray32](error(s"Expected cltype.CLValue, got $x"))
                    }
      balance <- getState(cltype.Key.URef(cltype.URef(balanceUref, cltype.AccessRights.None)))
                  .flatMap {
                    case cltype.StoredValue.CLValue(clValue) =>
                      cltype.CLValueInstance
                        .from(clValue)
                        .fold(
                          e =>
                            MonadThrowable[F]
                              .raiseError[BigInt Refined NonNegative](error(e.toString)), {
                            case CLValueInstance.U512(bigInt) =>
                              bigInt.pure[F]
                            case x =>
                              MonadThrowable[F]
                                .raiseError[BigInt Refined NonNegative](
                                  error(s"Expected cltype.U512, got $x")
                                )
                          }
                        )
                    case x =>
                      MonadThrowable[F]
                        .raiseError[BigInt Refined NonNegative](
                          error(s"Expected cltype.CLValue, got $x")
                        )
                  }
    } yield balance.value
    program.adaptErr {
      case _ => NotFound.balance(accountKey)
    }
  }
}
