package io.casperlabs.node.api.graphql.schema

import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.casper.MultiParentCasperRef.MultiParentCasperRef
import io.casperlabs.casper.api.BlockAPI
import io.casperlabs.casper.api.BlockAPI.BlockAndMaybeDeploys
import io.casperlabs.casper.consensus.info.{BlockInfo, DeployInfo}
import io.casperlabs.catscontrib.{Fs2Compiler, MonadThrowable}
import io.casperlabs.crypto.Keys.PublicKeyHash
import io.casperlabs.crypto.codec.{Base16, StringSyntax}
import io.casperlabs.models.SmartContractEngineError
import io.casperlabs.node.api.BlockInfoPagination.BlockInfoPageTokenParams
import io.casperlabs.node.api.DeployInfoPagination.DeployInfoPageTokenParams
import io.casperlabs.node.api.Utils.{
  validateAccountPublicKeyHash,
  validateBlockHashPrefix,
  validateDeployHash
}
import io.casperlabs.node.api.graphql.RunToFuture.ops._
import io.casperlabs.node.api.graphql._
import io.casperlabs.node.api.graphql.schema.blocks.types.GraphQLBlockTypes.AccountKey
import io.casperlabs.node.api.graphql.schema.blocks.types.{
  BlocksWithPageInfo,
  DeployInfosWithPageInfo,
  GraphQLBlockTypes,
  PageInfo
}
import io.casperlabs.node.api.{BlockInfoPagination, DeployInfoPagination, Utils}
import io.casperlabs.shared.Log
import io.casperlabs.smartcontracts.ExecutionEngineService
import io.casperlabs.models.cltype.StoredValueInstance
import io.casperlabs.storage.block._
import io.casperlabs.storage.dag.DagStorage
import io.casperlabs.storage.deploy.DeployStorage
import sangria.execution.deferred.{DeferredResolver, Fetcher, HasId}
import sangria.schema._
import scala.concurrent.Future
import scala.util.control.NonFatal
import sangria.execution.UserFacingError

// format: off
private[graphql] class GraphQLSchemaBuilder[F[_]: Fs2SubscriptionStream
                                                : Log
                                                : RunToFuture
                                                : MultiParentCasperRef
                                                : BlockStorage
                                                : FinalizedBlocksStream
                                                : MonadThrowable
                                                : ExecutionEngineService
                                                : DeployStorage
                                                : DagStorage
                                                : Fs2Compiler] {
// format: on

  // GraphQL projections don't expose the body.
  val deployView = DeployInfo.View.BASIC

  val blockFetcher = Fetcher.caching(
    { (_: Unit, hashes: Seq[BlockHash]) =>
      // Fetches only unique blocks without repetitive reading of the same block many times.
      //
      // TODO: However, it still will be slow due to (in decreasing priority):
      // 1) One-by-one reading from the database: update underlying API to accept multiple block hashes using 'WHERE block_hash IN ...'
      //
      // 2) Reading a full block: make use of Sangria Projections, although, not clear if it's possible to do without modifying the library's source code
      // UPDATE: It reads full blocks only if a query contains 'children' at any depth.
      // On the other hand, if 'children' presented, then it will read *all* blocks as FULL, even those for which we didn't ask children.
      // UPDATE: Make sure to fetch blocks only when it's really needed.
      // For instance, for 'block { parents { blockHash }' it shouldn't fetch parent blocks, because we have all the required information in the child block itself.
      //
      // 3) Seq->List conversion: least critical, must be ignored until we solve the above 2 issues
      RunToFuture[F].unsafeToFuture(
        hashes.toList
          .traverse { hash =>
            BlockAPI.getBlockInfoWithDeploys[F](
              hash,
              DeployInfo.View.BASIC.some,
              BlockInfo.View.FULL
            )
          }
          .map(list => list: Seq[BlockAndMaybeDeploys])
      )
    }
  )(HasId {
    case (blockInfo, _) =>
      blockInfo.getSummary.blockHash
  })

  // TODO: Performance issue - make use of Sangria Projections.
  // The same as the TODO #2 of the 'blockFetcher'
  // TODO: common code can be abstracted from together with the `accountDeploys`.
  // However and most probably pagination will exist only for deploys and blocks.
  // Abstract only when a new pagination needs to be added.
  val blocksByValidator: (Validator, Int, String) => Action[Unit, BlocksWithPageInfo] = {
    (validator, first, after) =>
      val deployStorage = DeployStorage[F].reader(deployView)
      val program =
        for {
          first <- Utils
                    .check[F, Int](
                      first,
                      "First must be greater than 0",
                      _ > 0
                    )
          (pageSize, pageTokenParams) <- MonadThrowable[F].fromTry(
                                          BlockInfoPagination.parsePageToken(first, after)
                                        )
          dag <- DagStorage[F].getRepresentation
          blocksWithOneMoreElem <- dag.getBlockInfosByValidator(
                                    validator,
                                    pageSize + 1,
                                    pageTokenParams.lastTimeStamp,
                                    pageTokenParams.lastBlockHash
                                  )
          (blocks, hasNextPage) = if (blocksWithOneMoreElem.length == pageSize + 1) {
            (blocksWithOneMoreElem.take(pageSize), true)
          } else {
            (blocksWithOneMoreElem, false)
          }
          blocksAndMaybeDeploys <- blocks.traverse(
                                    b =>
                                      deployStorage
                                        .getProcessedDeploys(b.getSummary.blockHash)
                                        .map(l => b -> l.some)
                                  )
          endCursor = BlockInfoPagination.createPageToken(
            blocks.lastOption.map(
              b =>
                BlockInfoPageTokenParams(
                  b.getSummary.getHeader.timestamp,
                  b.getSummary.blockHash
                )
            )
          )
          pageInfo = PageInfo(endCursor, hasNextPage)
          result   = BlocksWithPageInfo(blocksAndMaybeDeploys, pageInfo)
        } yield result
      program.unsafeToFuture
  }

  val accountBalance: AccountKey => Action[Unit, BigInt] = { accountKey =>
    BlockAPI.accountBalance[F](accountKey).unsafeToFuture
  }

  val accountDeploys: (AccountKey, Int, String) => Action[Unit, DeployInfosWithPageInfo] = {
    (accountKey, first, after) =>
      val program =
        for {
          first <- Utils
                    .check[F, Int](
                      first,
                      "First must be greater than 0",
                      _ > 0
                    )
          (pageSize, pageTokenParams) <- MonadThrowable[F].fromTry(
                                          DeployInfoPagination
                                            .parsePageToken(
                                              first,
                                              after
                                            )
                                        )
          accountHash = PublicKeyHash(accountKey)
          deploysWithOneMoreElem <- DeployStorage[F]
                                     .reader(deployView)
                                     .getDeploysByAccount(
                                       accountHash,
                                       pageSize + 1,
                                       pageTokenParams.lastTimeStamp,
                                       pageTokenParams.lastDeployHash,
                                       isNext = true
                                     )
          (deploys, hasNextPage) = if (deploysWithOneMoreElem.length == pageSize + 1) {
            (deploysWithOneMoreElem.take(pageSize), true)
          } else {
            (deploysWithOneMoreElem, false)
          }
          deployInfos <- DeployStorage[F]
                          .reader(deployView)
                          .getDeployInfos(deploys)
          endCursor = DeployInfoPagination.createPageToken(
            deploys.lastOption.map(
              d =>
                DeployInfoPageTokenParams(
                  d.getHeader.timestamp,
                  d.deployHash,
                  isNext = true
                )
            )
          )
          pageInfo = PageInfo(endCursor, hasNextPage)
          result   = DeployInfosWithPageInfo(deployInfos, pageInfo)
        } yield result
      program.unsafeToFuture
  }

  val blockTypes =
    new GraphQLBlockTypes(blockFetcher, blocksByValidator, accountBalance, accountDeploys)

  private def projectionTerms(projections: Vector[ProjectedName]): Set[String] = {
    def flatToSet(ps: Vector[ProjectedName], acc: Set[String]): Set[String] =
      if (ps.isEmpty) {
        acc
      } else {
        val h = ps.head
        flatToSet(ps.tail, acc + h.name) ++ flatToSet(h.children, acc)
      }

    flatToSet(projections, Set.empty)
  }

  private def blockView(projections: Vector[ProjectedName]): BlockInfo.View =
    blockView(projectionTerms(projections))

  private def blockView(terms: Set[String]): BlockInfo.View =
    if (terms contains "children")
      BlockInfo.View.FULL
    else
      BlockInfo.View.BASIC

  private def deployView(projections: Vector[ProjectedName]): Option[DeployInfo.View] =
    deployView(projectionTerms(projections))

  private def deployView(terms: Set[String]): Option[DeployInfo.View] =
    if (terms contains "deploys") Some(deployView) else None

  def createDeferredResolver = DeferredResolver.fetchers(blockTypes.blockFetcher)

  def createSchema: Schema[Unit, Unit] =
    Schema(
      query = ObjectType(
        "Query",
        fields[Unit, Unit](
          Field(
            "block",
            OptionType(blockTypes.BlockType),
            arguments = blocks.arguments.BlockHashPrefix :: Nil,
            resolve = Projector { (context, projections) =>
              (for {
                blockHashPrefix <- validateBlockHashPrefix[F](
                                    context.arg(blocks.arguments.BlockHashPrefix),
                                    ByteString.EMPTY
                                  )
                res <- BlockAPI
                        .getBlockInfoWithDeploysOpt[F](
                          blockHashBase16 = blockHashPrefix,
                          maybeDeployView = deployView(projections),
                          blockView = blockView(projections)
                        )
              } yield res).unsafeToFuture
            }
          ),
          Field(
            "dagSlice",
            ListType(blockTypes.BlockType),
            arguments = blocks.arguments.Depth :: blocks.arguments.MaxRank :: Nil,
            resolve = Projector { (context, projections) =>
              BlockAPI
                .getBlockInfosWithDeploys[F](
                  depth = context.arg(blocks.arguments.Depth),
                  maxRank = context.arg(blocks.arguments.MaxRank),
                  maybeDeployView = deployView(projections),
                  blockView = blockView(projections)
                )
                .unsafeToFuture
            }
          ),
          Field(
            "deploy",
            OptionType(blockTypes.DeployInfoType),
            arguments = blocks.arguments.DeployHash :: Nil,
            resolve = { c =>
              (validateDeployHash[F](c.arg(blocks.arguments.DeployHash), ByteString.EMPTY) >>= (
                  deployHash => BlockAPI.getDeployInfoOpt[F](deployHash, deployView)
              )).unsafeToFuture
            }
          ),
          Field(
            "deploys",
            blockTypes.DeployInfosWithPageInfoType,
            arguments = blocks.arguments.AccountPublicKeyHashBase16 :: blocks.arguments.First :: blocks.arguments.After :: Nil,
            resolve = { c =>
              val accountHashBase16 = c.arg(blocks.arguments.AccountPublicKeyHashBase16)
              val after             = c.arg(blocks.arguments.After)
              val first             = c.arg(blocks.arguments.First)
              val accountKeyEither = validateAccountPublicKeyHash[Either[Throwable, *]](
                accountHashBase16,
                ByteString.EMPTY
              ).map(s => ByteString.copyFrom(Base16.decode(s)))
              accountKeyEither
                .fold(
                  e => Action.futureAction(Future.failed[DeployInfosWithPageInfo](e)),
                  accountKey => accountDeploys(accountKey, first, after)
                )
            }
          ),
          Field(
            "account",
            OptionType(blockTypes.AccountType),
            arguments = blocks.arguments.PublicKeyHash :: Nil,
            resolve = { c =>
              val key = c.arg(blocks.arguments.PublicKeyHash)
              key.tryBase64AndBase16Decode.map(ByteString.copyFrom)
            }
          ),
          Field(
            "validator",
            OptionType(blockTypes.ValidatorType),
            arguments = blocks.arguments.PublicKeyHash :: Nil,
            resolve = { c =>
              val key = c.arg(blocks.arguments.PublicKeyHash)
              key.tryBase64AndBase16Decode.map(ByteString.copyFrom).map(PublicKeyHash)
            }
          ),
          Field(
            "globalState",
            ListType(OptionType(globalstate.types.StoredValue)),
            arguments = globalstate.arguments.StateQueryArgument :: blocks.arguments.BlockHashPrefix :: Nil,
            resolve = { c =>
              val queries = c.arg(globalstate.arguments.StateQueryArgument).toList

              val program =
                for {
                  blockHashBase16Prefix <- validateBlockHashPrefix[F](
                                            c.arg(blocks.arguments.BlockHashPrefix),
                                            ByteString.EMPTY
                                          )
                  maybeBlockProps <- BlockAPI
                                      .getBlockInfoWithDeploysOpt[F](
                                        blockHashBase16Prefix,
                                        maybeDeployView = None,
                                        blockView = BlockInfo.View.BASIC
                                      )
                                      .map(_.map {
                                        case (info, _) =>
                                          info.getSummary.getHeader.getState.postStateHash ->
                                            info.getSummary.getHeader.getProtocolVersion
                                      })
                  values <- maybeBlockProps.fold(List.empty[Option[StoredValueInstance]].pure[F]) {
                             case (stateHash, protocolVersion) =>
                               for {

                                 values <- queries.traverse {
                                            query =>
                                              for {
                                                key <- Utils.toKey[F](
                                                        query.keyType,
                                                        query.key
                                                      )
                                                possibleResponse <- ExecutionEngineService[F]
                                                                     .query(
                                                                       stateHash,
                                                                       key,
                                                                       query.pathSegments,
                                                                       protocolVersion
                                                                     )
                                                possibleInstance = possibleResponse.flatMap { sv =>
                                                  StoredValueInstance
                                                    .from(sv)
                                                    .leftMap(
                                                      err =>
                                                        SmartContractEngineError(
                                                          s"Error during instantiation of $sv: $err"
                                                        )
                                                    )
                                                }
                                                value <- MonadThrowable[F]
                                                          .fromEither(possibleInstance)
                                                          .map(_.some)
                                                          .recover {
                                                            case SmartContractEngineError(message)
                                                                if message.contains(
                                                                  "Value not found"
                                                                ) || message.contains(
                                                                  "Failed to find base key"
                                                                ) =>
                                                              none[StoredValueInstance]
                                                          }
                                              } yield value
                                          }
                               } yield values
                           }
                } yield values

              program
                .recoverWith {
                  case ex: IllegalArgumentException =>
                    MonadThrowable[F]
                      .raiseError(new Exception(ex.getMessage()) with UserFacingError)
                }
                .onError {
                  case ex: UserFacingError =>
                    Log[F].warn(s"Invalid global state query: ${ex.getMessage -> "error"}")

                  case NonFatal(ex) =>
                    Log[F]
                      .error(
                        s"Error executing queries on global state: $ex"
                      )
                }
                .unsafeToFuture
            }
          )
        )
      ),
      subscription = ObjectType(
        "Subscription",
        fields[Unit, Unit](
          Field.subs(
            "finalizedBlocks",
            blockTypes.BlockType,
            "Subscribes to new finalized blocks".some,
            resolve = { c =>
              // Projectors don't work with Subscriptions
              val terms = c.query.renderCompact
                .split("[^a-zA-Z0-9]")
                .collect {
                  case s if s.trim.nonEmpty => s.trim
                }
                .toSet

              FinalizedBlocksStream[F].subscribe.evalMap { blockHash =>
                BlockAPI
                  .getBlockInfoWithDeploys[F](
                    blockHash = blockHash,
                    maybeDeployView = deployView(terms),
                    blockView = blockView(terms)
                  )
                  .map(Action(_))
              }
            }
          )
        )
      ).some
    )
}
