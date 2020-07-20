package io.casperlabs.models

import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.Block.{GlobalState, Justification}
import io.casperlabs.casper.consensus.state.ProtocolVersion
import io.casperlabs.casper.consensus.{Block, BlockSummary, Deploy}
import io.casperlabs.models.Message._
import io.casperlabs.crypto.Keys.{PublicKey, PublicKeyBS, PublicKeyHash, PublicKeyHashBS}

object BlockImplicits {
  implicit class BlockOps(val block: Block) extends AnyVal {
    def isGenesisLike: Boolean =
      block.getHeader.parentHashes.isEmpty &&
        block.getHeader.validatorPublicKeyHash.isEmpty &&
        block.getSignature.sig.isEmpty

    def parentHashes: Seq[ByteString]      = block.getHeader.parentHashes
    def parents: Seq[ByteString]           = block.getHeader.parentHashes
    def justifications: Seq[Justification] = block.getHeader.justifications
    def justificationHashes: Seq[ByteString] =
      block.getHeader.justifications.map(_.latestBlockHash)
    def state: GlobalState               = block.getHeader.getState
    def bodyHash: ByteString             = block.getHeader.bodyHash
    def timestamp: Long                  = block.getHeader.timestamp
    def protocolVersion: ProtocolVersion = block.getHeader.getProtocolVersion
    def deployCount: Int                 = block.getHeader.deployCount
    def chainName: String                = block.getHeader.chainName
    def validatorBlockSeqNum: Int        = block.getHeader.validatorBlockSeqNum
    def validatorPublicKeyHash: PublicKeyHashBS =
      PublicKeyHash(block.getHeader.validatorPublicKeyHash)
    def validatorPublicKey: PublicKeyBS =
      PublicKey(block.getHeader.validatorPublicKey)
    def jRank: JRank       = asJRank(block.getHeader.jRank)
    def mainRank: MainRank = asMainRank(block.getHeader.mainRank)
    def weightMap: Map[PublicKeyHashBS, Weight] =
      block.getHeader.getState.bonds
        .map(b => (PublicKeyHash(b.validatorPublicKeyHash), Weight(b.stake)))
        .toMap

    def getSummary: BlockSummary =
      BlockSummary(block.blockHash, block.header, block.signature)

    /** Serve the block without deploy bodies in it, but keep the header. Used when deploy gossiping is enabled. */
    def clearDeployBodies: Block = block.update(
      _.body.deploys := block.getBody.deploys
        .map(
          processedDeploy => processedDeploy.withDeploy(processedDeploy.getDeploy.clearBody)
        )
    )

    /** Serve the block without deploy body or header in it, only keep the hash. Used when deploy gossiping is enabled. */
    def clearDeploysExceptHash: Block =
      block.update(
        _.body.deploys := block.getBody.deploys
          .map(
            processedDeploy =>
              processedDeploy.withDeploy(Deploy(processedDeploy.getDeploy.deployHash))
          )
      )

    /** Fill in the deploy bodies from a list of deploys fetched separately. */
    def withDeploys(deploys: List[Deploy]): Block = {
      val deployMap = deploys.map(d => d.deployHash -> d).toMap
      block.update(_.body.deploys := block.getBody.deploys.map { pd =>
        pd.withDeploy(deployMap(pd.getDeploy.deployHash))
      })
    }
  }

  implicit class BlockSummaryOps(val summary: BlockSummary) extends AnyVal {
    def isGenesisLike: Boolean =
      summary.getHeader.parentHashes.isEmpty &&
        summary.getHeader.validatorPublicKeyHash.isEmpty &&
        summary.getSignature.sig.isEmpty
    def parentHashes: Seq[ByteString]      = summary.getHeader.parentHashes
    def parents: Seq[ByteString]           = summary.getHeader.parentHashes
    def justifications: Seq[Justification] = summary.getHeader.justifications
    def state: GlobalState                 = summary.getHeader.getState
    def bodyHash: ByteString               = summary.getHeader.bodyHash
    def timestamp: Long                    = summary.getHeader.timestamp
    def protocolVersion: ProtocolVersion   = summary.getHeader.getProtocolVersion
    def deployCount: Int                   = summary.getHeader.deployCount
    def chainName: String                  = summary.getHeader.chainName
    def validatorBlockSeqNum: Int          = summary.getHeader.validatorBlockSeqNum
    def validatorPublicKeyHash: PublicKeyHashBS =
      PublicKeyHash(summary.getHeader.validatorPublicKeyHash)
    def validatorPublicKey: PublicKeyBS =
      PublicKey(summary.getHeader.validatorPublicKey)
    def jRank: JRank       = asJRank(summary.getHeader.jRank)
    def mainRank: MainRank = asMainRank(summary.getHeader.mainRank)
    def weightMap: Map[PublicKeyHashBS, Weight] =
      summary.getHeader.getState.bonds
        .map(b => (PublicKeyHash(b.validatorPublicKeyHash), Weight(b.stake)))
        .toMap
  }

  implicit class BlockSummaryObjectOps(val blockSummary: BlockSummary.type) extends AnyVal {
    def fromBlock(b: Block): BlockSummary = BlockSummary(b.blockHash, b.header, b.signature)
  }
}
