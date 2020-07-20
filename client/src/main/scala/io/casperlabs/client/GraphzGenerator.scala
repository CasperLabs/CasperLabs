package io.casperlabs.client

import cats.{Monad, _}
import cats.effect.Sync
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper._
import io.casperlabs.models.BlockImplicits._
import io.casperlabs.casper.consensus.info.BlockInfo
import io.casperlabs.catscontrib.Catscontrib._
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.graphz._

final case class ValidatorBlock(
    blockHash: String,
    parentsHashes: List[String],
    justifications: List[String]
)

final case class GraphConfig(showJustificationLines: Boolean = false)

object GraphzGenerator {
  type BlockHash        = ByteString
  type Rank             = Long
  type ValidatorsBlocks = Map[Rank, List[ValidatorBlock]]

  // For debugging it is usefull to change Invis to Dotted.
  //
  val Invisible = Invis

  final case class DagInfo[G[_]](
      validators: Map[String, ValidatorsBlocks] = Map.empty,
      ranks: List[Rank] = List.empty
  )

  object DagInfo {
    def empty[G[_]]: DagInfo[G] = DagInfo[G]()
  }

  private def hexShort(hash: ByteString) = {
    val str = Base16.encode(hash.toByteArray)
    if (str.length <= 10) str else str.substring(0, 10) + "..."
  }

  def dagAsCluster[
      G[_]: Monad: GraphSerializer
  ](
      // Going from newest to oldest.
      blockInfos: List[BlockInfo],
      config: GraphConfig
  ): G[Graphz[G]] = {
    val acc = toDagInfo[G](blockInfos)

    val allBlockHashes = blockInfos.map(b => hexShort(b.getSummary.blockHash)).toSet

    // In the current model only genesis block has empty validatorPublicKey,
    // and there must be only one genesis block. However, if that ever changes
    // or there is a bug and there is more than one block like that,
    // then we want to visualise all of them.
    val genesisBlocks =
      blockInfos
        .map(_.getSummary)
        .filter(_.validatorPublicKeyHash.isEmpty)
        .map(b => hexShort(b.blockHash))

    val fakeGenesisBlocks = if (genesisBlocks.size > 0) List.empty else List("alignment_node")

    val lastFinalizedBlockHash =
      blockInfos
        .find(_.getStatus.finality.isFinalized)
        .map(x => hexShort(x.getSummary.blockHash))
        .getOrElse("")

    val ranks          = acc.ranks.reverse
    val firstRank      = ranks.head
    val validators     = acc.validators
    val validatorsList = validators.toList.sortBy(_._1)

    for {
      g <- initGraph[G]("dag")

      // draw genesis block first, if there is any
      _ <- genesisBlocks.traverse(b => g.node(b, style = Some(Bold), shape = Box))

      // draw invisible fake genesis block if needed
      _ <- fakeGenesisBlocks.traverse(b => g.node(b, style = Some(Invisible), shape = Box))

      // draw invisible edges from genesis block or the fake genesis block
      // to first node of each validator for alignment
      _ <- (if (genesisBlocks.size > 0) genesisBlocks else fakeGenesisBlocks)
            .flatMap(
              genesisBlock =>
                validatorsList.flatMap {
                  case (id, blocks) =>
                    nodesForRank(id, firstRank, blocks, lastFinalizedBlockHash).map(
                      node => (genesisBlock, node)
                    )
                }
            )
            .traverse {
              case (genesisBlock, node) =>
                g.edge(
                  genesisBlock,
                  node._2,
                  style = Some(Invisible)
                )
            }

      // draw clusters per validator
      _ <- validatorsList.traverse {
            case (id, blocks) =>
              g.subgraph(
                validatorCluster(id, blocks, ranks, lastFinalizedBlockHash)
              )
          }
      // draw parent dependencies
      _ <- drawParentDependencies[G](g, validatorsList.map(_._2), allBlockHashes)
      // draw justification dotted lines
      _ <- config.showJustificationLines.fold(
            drawJustificationDottedLines[G](g, validators, allBlockHashes),
            ().pure[G]
          )
      _ <- g.close
    } yield g
  }

  private def toDagInfo[G[_]](
      blockInfos: List[BlockInfo]
  ): DagInfo[G] = {
    val ranks = blockInfos.map(_.getSummary.jRank).distinct

    val validators = blockInfos
      .map(_.getSummary)
      .filterNot(_.validatorPublicKeyHash.isEmpty)
      .foldMap { b =>
        val blockHash       = hexShort(b.blockHash)
        val blockSenderHash = hexShort(b.validatorPublicKeyHash)
        val parents         = b.parentHashes.toList.map(hexShort)
        val justifications = b.justifications
          .map(_.latestBlockHash)
          .map(hexShort)
          .toSet
          .toList

        val validatorBlocks: Map[Long, List[io.casperlabs.client.ValidatorBlock]] =
          Map(b.jRank -> List(ValidatorBlock(blockHash, parents, justifications)))

        Map(blockSenderHash -> validatorBlocks)
      }

    DagInfo[G](validators, ranks)
  }

  private def initGraph[G[_]: Monad: GraphSerializer](name: String): G[Graphz[G]] =
    Graphz[G](
      name,
      DiGraph,
      rankdir = Some(BT),
      splines = Some("false"),
      node = Map("width" -> "0", "height" -> "0", "margin" -> "0.03", "fontsize" -> "8")
    )

  private def drawParentDependencies[G[_]: Applicative](
      g: Graphz[G],
      validators: List[ValidatorsBlocks],
      allBlockHashes: Set[String]
  ): G[Unit] =
    validators
      .flatMap(_.values.toList)
      .flatten
      .traverse {
        case ValidatorBlock(blockHash, parentsHashes, _) =>
          parentsHashes
            .filter(allBlockHashes)
            .zipWithIndex
            .traverse {
              case (p, index) =>
                // Bolding the edge to main parent.
                val style = if (index == 0) {
                  Some(Bold)
                } else {
                  None
                }
                g.edge(blockHash, p, style = style, constraint = Some(false))
            }
      }
      .as(())

  private def drawJustificationDottedLines[G[_]: Applicative](
      g: Graphz[G],
      validators: Map[String, ValidatorsBlocks],
      allBlockHashes: Set[String]
  ): G[Unit] =
    validators.values.toList
      .flatMap(_.values.toList)
      .flatten
      .traverse {
        case ValidatorBlock(blockHash, _, justifications) =>
          justifications
            .filter(p => allBlockHashes.contains(p))
            .traverse(
              j =>
                g.edge(
                  blockHash,
                  j,
                  style = Some(Dotted),
                  constraint = Some(false),
                  arrowHead = Some(NoneArrow)
                )
            )

      }
      .as(())

  private def nodesForRank(
      validatorId: String,
      rank: Rank,
      blocks: ValidatorsBlocks,
      lastFinalizedBlockHash: String
  ): List[(Option[GraphStyle], String)] =
    blocks.get(rank) match {
      case Some(blocks) =>
        blocks.map(b => (styleFor(b.blockHash, lastFinalizedBlockHash), b.blockHash))
      case None => List((Some(Invisible), s"${rank.show}_$validatorId"))
    }

  private def validatorCluster[G[_]: Monad: GraphSerializer](
      id: String,
      blocks: ValidatorsBlocks,
      ranks: List[Rank],
      lastFinalizedBlockHash: String
  ): G[Graphz[G]] =
    for {
      g     <- Graphz.subgraph[G](s"cluster_$id", DiGraph, label = Some(id))
      nodes = ranks.map(rank => nodesForRank(id, rank, blocks, lastFinalizedBlockHash)).flatten
      _ <- nodes.traverse {
            case (style, name) => g.node(name, style = style, shape = Box)
          }

      // Draw invisible edges from nodes rank i to nodes rank i+1 for alignment.
      _ <- ranks.reverse.tail.reverse
            .flatMap(
              rank =>
                nodesForRank(id, rank, blocks, lastFinalizedBlockHash).flatMap(n1 => {
                  nodesForRank(id, rank + 1, blocks, lastFinalizedBlockHash).map(n2 => {
                    (n1, n2)
                  })
                })
            )
            .traverse {
              case ((_, n1), (_, n2)) => g.edge(n1, n2, style = Some(Invisible))
            }

      _ <- g.close
    } yield g

  private def styleFor(blockHash: String, lastFinalizedBlockHash: String): Option[GraphStyle] =
    if (blockHash == lastFinalizedBlockHash) Some(Filled) else None

}
