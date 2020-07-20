package io.casperlabs.node.api

import cats.effect._
import cats.implicits._
import com.google.protobuf.ByteString
import com.google.protobuf.empty.Empty
import io.casperlabs.casper.MultiParentCasperRef.MultiParentCasperRef
import io.casperlabs.casper.api.BlockAPI
import io.casperlabs.casper.consensus.{state, Block}
import io.casperlabs.casper.consensus.info._
import io.casperlabs.casper.consensus.state.ProtocolVersion
import io.casperlabs.casper.MultiParentCasperRef
import io.casperlabs.catscontrib.{Fs2Compiler, MonadThrowable}
import io.casperlabs.comm.ServiceError.{FailedPrecondition, InvalidArgument, Unavailable}
import io.casperlabs.comm.gossiping.relaying.DeployRelaying
import io.casperlabs.crypto.Keys.PublicKeyHash
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.mempool.DeployBuffer
import io.casperlabs.metrics.Metrics
import io.casperlabs.models.BlockImplicits._
import io.casperlabs.models.SmartContractEngineError
import io.casperlabs.node.api.Utils.{
  validateAccountPublicKeyHash,
  validateBlockHashPrefix,
  validateDeployHash
}
import io.casperlabs.node.api.casper._
import io.casperlabs.shared.Log
import io.casperlabs.smartcontracts.ExecutionEngineService
import io.casperlabs.models.cltype.protobuf.Mappings
import io.casperlabs.storage.block._
import io.casperlabs.storage.deploy.DeployStorage
import io.casperlabs.storage.dag.{DagStorage, FinalityStorage}
import monix.eval.{Task, TaskLike}
import monix.reactive.Observable

object GrpcCasperService {

  def apply[F[_]: Concurrent: TaskLike: Log: Metrics: FinalityStorage: BlockStorage: ExecutionEngineService: DeployStorage: Fs2Compiler: DeployBuffer: DeployRelaying: DagStorage: EventStream](
      isDeployEnabled: Boolean
  ): F[CasperGrpcMonix.CasperService] =
    BlockAPI.establishMetrics[F] *> Sync[F].delay {
      val adaptToInvalidArgument: PartialFunction[Throwable, Throwable] = {
        case e => InvalidArgument(e.getMessage)
      }

      new CasperGrpcMonix.CasperService {
        override def deploy(request: DeployRequest): Task[Empty] =
          TaskLike[F].apply {
            if (!isDeployEnabled)
              MonadThrowable[F].raiseError(
                FailedPrecondition("The node doesn't accept deploys.")
              )
            else BlockAPI.deploy[F](request.getDeploy).as(Empty())
          }

        override def getBlockInfo(request: GetBlockInfoRequest): Task[BlockInfo] =
          TaskLike[F].apply {
            validateBlockHashPrefix[F](
              request.blockHashBase16,
              request.blockHash,
              adaptToInvalidArgument
            ) >>= { blockHashPrefix =>
              BlockAPI
                .getBlockInfo[F](
                  blockHashPrefix,
                  request.view
                )
            }
          }

        override def streamBlockInfos(request: StreamBlockInfosRequest): Observable[BlockInfo] = {
          val infos = TaskLike[F].apply {
            BlockAPI.getBlockInfos[F](
              depth = request.depth,
              maxRank = request.maxRank,
              blockView = request.view
            )
          }
          Observable.fromTask(infos).flatMap(Observable.fromIterable)
        }

        override def getDeployInfo(request: GetDeployInfoRequest): Task[DeployInfo] =
          TaskLike[F].apply {
            validateDeployHash[F](
              request.deployHashBase16,
              request.deployHash,
              adaptToInvalidArgument
            ) >>= { deployHash =>
              BlockAPI
                .getDeployInfo[F](deployHash, request.view)
            }
          }

        override def streamBlockDeploys(
            request: StreamBlockDeploysRequest
        ): Observable[Block.ProcessedDeploy] = {
          val deploys = TaskLike[F].apply {
            validateBlockHashPrefix[F](
              request.blockHashBase16,
              request.blockHash,
              adaptToInvalidArgument
            ) >>= { blockHashPrefix =>
              BlockAPI.getBlockDeploys[F](blockHashPrefix, request.view)
            }
          }
          Observable.fromTask(deploys).flatMap(Observable.fromIterable)
        }

        override def getBlockState(request: GetBlockStateRequest): Task[state.StoredValueInstance] =
          batchGetBlockState(
            BatchGetBlockStateRequest(
              request.blockHashBase16,
              request.blockHash,
              List(request.getQuery)
            )
          ) map {
            _.values.head
          }

        override def batchGetBlockState(
            request: BatchGetBlockStateRequest
        ): Task[BatchGetBlockStateResponse] = TaskLike[F].apply {
          for {
            blockHashPrefix <- validateBlockHashPrefix[F](
                                request.blockHashBase16,
                                request.blockHash,
                                adaptToInvalidArgument
                              )
            info            <- BlockAPI.getBlockInfo[F](blockHashPrefix, BlockInfo.View.BASIC)
            stateHash       = info.getSummary.state.postStateHash
            protocolVersion = info.getSummary.getHeader.getProtocolVersion
            values          <- request.queries.toList.traverse(getState(stateHash, _, protocolVersion))
          } yield BatchGetBlockStateResponse(values)
        }

        private def getState(
            stateHash: ByteString,
            query: StateQuery,
            protocolVersion: ProtocolVersion
        ): F[state.StoredValueInstance] =
          for {
            key <- toKey[F](query.keyVariant, query.keyBase16)
            possibleResponse <- ExecutionEngineService[F].query(
                                 stateHash,
                                 key,
                                 query.pathSegments,
                                 protocolVersion
                               )
            protoValue = possibleResponse.flatMap { storedValue =>
              Mappings
                .toProto(storedValue)
                .leftMap(
                  err => SmartContractEngineError(s"Error with EE response $storedValue:\n$err")
                )
            }
            value <- Concurrent[F].fromEither(protoValue).recoverWith {
                      case SmartContractEngineError(msg) =>
                        MonadThrowable[F].raiseError(InvalidArgument(msg))
                    }
          } yield value

        override def listDeployInfos(
            request: ListDeployInfosRequest
        ): Task[ListDeployInfosResponse] =
          TaskLike[F].apply {
            for {
              accountPublicKeyHashBase16 <- validateAccountPublicKeyHash[F](
                                             request.accountPublicKeyHashBase16,
                                             request.accountPublicKeyHash,
                                             adaptToInvalidArgument
                                           )
              (pageSize, pageTokenParams) <- MonadThrowable[F].fromTry(
                                              DeployInfoPagination
                                                .parsePageToken(
                                                  request.pageSize,
                                                  request.pageToken
                                                )
                                            )
              accountPublicKeyHash = PublicKeyHash(
                ByteString.copyFrom(
                  Base16.decode(accountPublicKeyHashBase16)
                )
              )
              deploys <- DeployStorage[F]
                          .reader(request.view)
                          .getDeploysByAccount(
                            accountPublicKeyHash,
                            pageSize,
                            pageTokenParams.lastTimeStamp,
                            pageTokenParams.lastDeployHash,
                            pageTokenParams.isNext
                          )
              deployInfos <- DeployStorage[F]
                              .reader(request.view)
                              .getDeployInfos(deploys)
              (nextPageToken, prevPageToken) = DeployInfoPagination.createNextAndPrePageToken(
                deploys,
                pageTokenParams
              )
              result = ListDeployInfosResponse()
                .withDeployInfos(deployInfos)
                .withNextPageToken(nextPageToken)
                .withPrevPageToken(prevPageToken)
            } yield result
          }

        override def getLastFinalizedBlockInfo(
            request: GetLastFinalizedBlockInfoRequest
        ): Task[BlockInfo] =
          TaskLike[F].apply {
            FinalityStorage[F].getLastFinalizedBlock.flatMap { blockHash =>
              BlockAPI
                .getBlockInfo[F](
                  Base16.encode(blockHash.toByteArray),
                  request.view
                )
            }
          }

        override def streamEvents(request: StreamEventsRequest): Observable[Event] =
          EventStream[F].subscribe(request)
      }
    }

  def toKey[F[_]: MonadThrowable](
      keyType: StateQuery.KeyVariant,
      keyValue: String
  ): F[state.Key] =
    Utils.toKey[F](keyType.name, keyValue).recoverWith {
      case ex: java.lang.IllegalArgumentException =>
        MonadThrowable[F].raiseError(InvalidArgument(ex.getMessage))
    }
}
