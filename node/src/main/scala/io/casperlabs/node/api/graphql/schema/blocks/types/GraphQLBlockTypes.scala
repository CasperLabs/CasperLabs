package io.casperlabs.node.api.graphql.schema.blocks.types

import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.casper.api.BlockAPI.BlockAndMaybeDeploys
import io.casperlabs.casper.consensus.Block._
import io.casperlabs.casper.consensus._
import io.casperlabs.casper.consensus.info.DeployInfo.ProcessingResult
import io.casperlabs.casper.consensus.info._
import io.casperlabs.crypto.codec.{Base16, ByteArraySyntax}
import io.casperlabs.models.BlockImplicits._
import io.casperlabs.node.api.graphql.schema.blocks
import io.casperlabs.node.api.graphql.schema.blocks.types.GraphQLBlockTypes.AccountKey
import io.casperlabs.node.api.graphql.schema.utils.{DateType, ProtocolVersionType}
import sangria.execution.deferred._
import sangria.schema._

case class PageInfo(endCursor: String, hasNextPage: Boolean)

case class DeployInfosWithPageInfo(deployInfos: List[DeployInfo], pageInfo: PageInfo)

case class BlocksWithPageInfo(blocks: List[BlockAndMaybeDeploys], pageInfo: PageInfo)

/**
  * Contains only GraphQL types without declaring actual ways of retrieving the information
  */
class GraphQLBlockTypes(
    val blockFetcher: Fetcher[Unit, BlockAndMaybeDeploys, BlockAndMaybeDeploys, BlockHash],
    val blocksByValidator: (Validator, Int, String) => Action[Unit, BlocksWithPageInfo],
    val accountBalance: AccountKey => Action[Unit, BigInt],
    val accountDeploys: (AccountKey, Int, String) => Action[Unit, DeployInfosWithPageInfo]
) {

  val SignatureType = ObjectType(
    "Signature",
    "Signature used to sign Block or Deploy",
    fields[Unit, Signature](
      Field(
        "algorithm",
        StringType,
        "Algorithm used to create a signature".some,
        resolve = c => c.value.sigAlgorithm
      ),
      Field(
        "signature",
        StringType,
        "Base-16 encoded signature".some,
        resolve = c => Base16.encode(c.value.sig.toByteArray)
      )
    )
  )

  val ApprovalType = ObjectType(
    "Approval",
    "Public key and signature used to approve Block or Deploy",
    fields[Unit, Approval](
      Field(
        "approverPublicKey",
        StringType,
        "Base-16 encoded public key of approver".some,
        resolve = c => Base16.encode(c.value.approverPublicKey.toByteArray)
      ),
      Field(
        "signature",
        SignatureType,
        "Signature used to sign a Deploy".some,
        resolve = c => c.value.getSignature
      )
    )
  )

  val DeployType = ObjectType(
    "Deploy",
    "Code that is able to be executed on CasperLabs blockchain",
    fields[Unit, Deploy](
      Field(
        "deployHash",
        StringType,
        "Base-16 encoded BLAKE2b256 hash of deploy's header".some,
        resolve = c => Base16.encode(c.value.deployHash.toByteArray)
      ),
      Field(
        "account",
        AccountType,
        "Account related information".some,
        resolve = c => c.value.getHeader.accountPublicKeyHash
      ),
      Field(
        "timestamp",
        DateType,
        "Timestamp when the deploy was created".some,
        resolve = c => c.value.getHeader.timestamp
      ),
      Field(
        "gasPrice",
        LongType,
        "The price of gas for the deploy in units dust/gas.".some,
        resolve = c => c.value.getHeader.gasPrice
      ),
      Field(
        "approvals",
        ListType(ApprovalType),
        "Approvals used to sign the deploy".some,
        resolve = c => c.value.approvals.toList
      )
    )
  )

  val DeployResultInterface = InterfaceType(
    "DeployProcessingResult",
    "Basic information of results of a deploy processing",
    fields[Unit, Either[ProcessedDeploy, ProcessingResult]](
      Field(
        "cost",
        LongType,
        "Amount of gas spent to execute the deploy".some,
        resolve = c => c.value.fold(_.cost, _.cost)
      ),
      Field(
        "isError",
        BooleanType,
        "True if execution failed, false otherwise".some,
        resolve = c => c.value.fold(_.isError, _.isError)
      ),
      Field(
        "errorMessage",
        OptionType(StringType),
        "Error message if failed, null otherwise".some,
        resolve = c => Option(c.value.fold(_.errorMessage, _.errorMessage)).filter(_.nonEmpty)
      )
    )
  )

  val ProcessedDeployType = ObjectType(
    "ProcessedDeploy",
    "Results of executing a deploy",
    interfaces[Unit, Either[ProcessedDeploy, ProcessingResult]](DeployResultInterface),
    fields[Unit, Either[ProcessedDeploy, ProcessingResult]](
      Field("deploy", DeployType, resolve = c => c.value.left.get.getDeploy)
    )
  )

  lazy val ValidatorType = ObjectType(
    "Validator",
    "Validator related information",
    () =>
      fields[Unit, Validator](
        Field(
          "publicKeyHashBase16",
          StringType,
          "Validator's public key hash in Base16 encoding".some,
          resolve = c => c.value.toByteArray.base16Encode
        ),
        Field(
          "publicKeyHashBase64",
          StringType,
          "Validator's public key hash in Base64 encoding".some,
          resolve = c => c.value.toByteArray.base64Encode
        ),
        Field(
          "blocks",
          BlocksWithPageInfoType,
          "Blocks produced by the validator".some,
          arguments = blocks.arguments.First :: blocks.arguments.After :: Nil,
          resolve = c =>
            blocksByValidator(
              c.value,
              c.arg(blocks.arguments.First),
              c.arg(blocks.arguments.After)
            )
        )
      )
  )

  lazy val AccountType = ObjectType(
    "AccountInfo",
    "Account related information",
    () =>
      fields[Unit, AccountKey](
        Field(
          "accountHashBase16",
          StringType,
          "Account's public key hash in Base16 encoding".some,
          resolve = c => c.value.toByteArray.base16Encode
        ),
        Field(
          "accountHashBase64",
          StringType,
          "Account's public key hash in Base64 encoding".some,
          resolve = c => c.value.toByteArray.base64Encode
        ),
        Field(
          "balance",
          BigIntType,
          "Account's balance at the latest block in motes".some,
          resolve = c => accountBalance(c.value)
        ),
        Field(
          "deploys",
          DeployInfosWithPageInfoType,
          arguments = blocks.arguments.First :: blocks.arguments.After :: Nil,
          resolve = c =>
            accountDeploys(c.value, c.arg(blocks.arguments.First), c.arg(blocks.arguments.After))
        )
      )
  )

  lazy val BlockInfoInterface = InterfaceType(
    "BlockInfo",
    "Basic block information which doesn't require reading a full block",
    () =>
      fields[Unit, BlockAndMaybeDeploys](
        Field(
          "blockHash",
          StringType,
          "Base-16 encoded BLAKE2b256 hash of the block header".some,
          resolve = c => Base16.encode(c.value._1.getSummary.blockHash.toByteArray)
        ),
        Field(
          "parents",
          ListType(BlockType),
          "Parent blocks".some,
          resolve = c =>
            c.value match {
              case (blockInfo, _) => blockFetcher.deferSeq(blockInfo.getSummary.parents)
            }
        ),
        Field(
          "justifications",
          ListType(BlockType),
          "Justification blocks".some,
          resolve = c =>
            c.value match {
              case (blockInfo, _) =>
                blockFetcher.deferSeq(blockInfo.getSummary.justifications.map(_.latestBlockHash))
            }
        ),
        Field(
          "children",
          ListType(BlockType),
          "Child blocks".some,
          resolve = c =>
            c.value match {
              case (blockInfo, _) => blockFetcher.deferSeq(blockInfo.getStatus.childHashes)
            }
        ),
        Field(
          "timestamp",
          DateType,
          "Timestamp when the block was created".some,
          resolve = c => c.value._1.getSummary.timestamp
        ),
        Field(
          "protocolVersion",
          ProtocolVersionType,
          "Protocol version of CasperLabs blockchain".some,
          resolve = c => c.value._1.getSummary.getHeader.getProtocolVersion
        ),
        Field(
          "deployCount",
          IntType,
          "Amount of deploys in the block".some,
          resolve = c => c.value._1.getSummary.deployCount
        ),
        Field(
          "rank",
          LongType,
          "Amount of hops needed to reach a genesis from the block. Based on block's justifications".some,
          resolve = c => c.value._1.getSummary.jRank
        ),
        Field(
          "mainRank",
          LongType,
          "Block height along the main-tree in the DAG. Based on the block's main parent.".some,
          resolve = c => c.value._1.getSummary.mainRank
        ),
        Field(
          "validator",
          ValidatorType,
          "Validator related information".some,
          resolve = c => c.value._1.getSummary.validatorPublicKeyHash
        ),
        Field(
          "validatorBlockSeqNum",
          IntType,
          "The block's number by the validator".some,
          resolve = c => c.value._1.getSummary.validatorBlockSeqNum
        ),
        Field(
          "chainName",
          StringType,
          "Chain name of where the block was created".some,
          resolve = c => c.value._1.getSummary.chainName
        ),
        Field(
          "validatorPublicKey",
          StringType,
          "Validator's public key in Base16 encoding".some,
          resolve = c => Base16.encode(c.value._1.getSummary.validatorPublicKey.toByteArray)
        ),
        Field(
          "signature",
          SignatureType,
          "Signature of validator created the block over blockHash".some,
          resolve = c => c.value._1.getSummary.getSignature
        ),
        Field(
          "deploys",
          ListType(ProcessedDeployType),
          "Deploys in the block".some,
          resolve = c => c.value._2.get.map(_.asLeft[ProcessingResult])
        ),
        Field(
          "finality",
          StringType,
          "Indicate whether the block has been finalized or orphaned yet.".some,
          resolve = c => c.value._1.getStatus.finality.toString
        ),
        Field(
          "blockSizeBytes",
          OptionType(IntType),
          resolve = c => c.value._1.getStatus.stats.map(_.blockSizeBytes)
        ),
        Field(
          "deployErrorCount",
          OptionType(IntType),
          resolve = c => c.value._1.getStatus.stats.map(_.deployErrorCount)
        ),
        Field(
          "deployCostTotal",
          OptionType(LongType),
          resolve = c => c.value._1.getStatus.stats.map(_.deployCostTotal)
        ),
        Field(
          "deployGasPriceAvg",
          OptionType(LongType),
          resolve = c => c.value._1.getStatus.stats.map(_.deployGasPriceAvg)
        )
      )
  )

  val ProcessingResultType = ObjectType(
    "ProcessingResult",
    "Full deploys processing result information",
    interfaces[Unit, Either[ProcessedDeploy, ProcessingResult]](
      DeployResultInterface
    ),
    fields[Unit, Either[ProcessedDeploy, ProcessingResult]](
      Field(
        "block",
        BlockInfoInterface,
        // Producing the BlockInfo from the one embedded in `DeployInfo.ProcessingResult`; it won't have further deploys with it for the block.
        resolve = c => (c.value.right.get.getBlockInfo, none[List[Block.ProcessedDeploy]])
      )
    )
  )

  val DeployInfoType = ObjectType(
    "DeployInfo",
    "Deploy information",
    fields[Unit, DeployInfo](
      Field(
        "deploy",
        DeployType,
        resolve = c => c.value.getDeploy
      ),
      Field(
        "processingResults",
        ListType(ProcessingResultType),
        resolve = c => c.value.processingResults.map(_.asRight[ProcessedDeploy]).toList
      )
    )
  )

  lazy val BlockType: ObjectType[Unit, BlockAndMaybeDeploys] = ObjectType(
    "Block",
    interfaces[Unit, BlockAndMaybeDeploys](BlockInfoInterface),
    () =>
      fields[Unit, BlockAndMaybeDeploys](
        Field(
          "deploys",
          ListType(ProcessedDeployType),
          "Deploys in the block".some,
          resolve = c => c.value._2.get.map(_.asLeft[ProcessingResult])
        )
      )
  )

  val PageInfoType = ObjectType(
    "PageInfo",
    "Cursor based pagination information",
    fields[Unit, PageInfo](
      Field(
        "endCursor",
        StringType,
        "The cursor of the last item in result".some,
        resolve = _.value.endCursor
      ),
      Field(
        "hasNextPage",
        BooleanType,
        "Whether there is another page of data available".some,
        resolve = _.value.hasNextPage
      )
    )
  )

  val DeployInfosWithPageInfoType: ObjectType[Unit, DeployInfosWithPageInfo] = ObjectType(
    "deploys",
    "A list of deploys for the specified account",
    fields[Unit, DeployInfosWithPageInfo](
      Field("deployInfos", ListType(DeployInfoType), resolve = _.value.deployInfos),
      Field("pageInfo", PageInfoType, resolve = _.value.pageInfo)
    )
  )

  val BlocksWithPageInfoType: ObjectType[Unit, BlocksWithPageInfo] = ObjectType(
    "blocks",
    "A list of blocks for the specified validator",
    fields[Unit, BlocksWithPageInfo](
      Field("blocks", ListType(BlockType), resolve = _.value.blocks),
      Field("pageInfo", PageInfoType, resolve = _.value.pageInfo)
    )
  )
}

object GraphQLBlockTypes {
  type AccountKey      = ByteString
  type BlockHashPrefix = String
}
