package io.casperlabs.node.api.graphql.schema.blocks

import sangria.schema._

package object arguments {
  val BlockHashPrefix =
    Argument(
      "blockHashBase16Prefix",
      StringType,
      description = "Prefix or full base-16 hash of a block"
    )

  val Depth =
    Argument(
      "depth",
      IntType,
      description = "How many of the top ranks of the DAG to show"
    )

  val SliceDepth =
    Argument(
      "sliceDepth",
      IntType,
      description =
        "How many of the recent blocks of a validator to show by their validator's block sequence number"
    )

  val MaxRank =
    Argument(
      "maxRank",
      OptionInputType(LongType),
      "The maximum rank to to go back from, 0 means go from the current tip of the DAG",
      0L
    )

  val MaxBlockSeqNum =
    Argument(
      "maxBlockSeqNum",
      OptionInputType(LongType),
      "The maximum block sequential number by a validator to to go back from, 0 means go from the latest block of a validator",
      0L
    )

  val DeployHash =
    Argument(
      "deployHashBase16",
      StringType,
      description = "Base-16 hash of a deploy, must be 64 characters long"
    )

  val PublicKeyHash = Argument(
    "publicKeyHash",
    StringType,
    description = "Base-16 or Base-64 public key hash"
  )

  val PublicKey = Argument(
    "publicKey",
    StringType,
    description = "Base-16 or Base-64 public key"
  )

  val AccountPublicKeyHashBase16 =
    Argument(
      "accountPublicKeyHashBase16",
      StringType,
      description = "Base-16 public key of a account, must be 64 characters long"
    )

  val First = Argument("first", IntType, description = "The maximum number of items to return.")

  val After =
    Argument(
      "after",
      OptionInputType(StringType),
      description = "The endCursor value returned from a previous request, if any.",
      ""
    )
}
