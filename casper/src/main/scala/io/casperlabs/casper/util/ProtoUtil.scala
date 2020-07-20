package io.casperlabs.casper.util

import java.util.NoSuchElementException

import cats.Monad
import cats.data.NonEmptyList
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.casper.consensus.Block.{GlobalState, Justification, MessageType}
import io.casperlabs.casper.consensus.Deploy.Code.{StoredContract, StoredVersionedContract}
import io.casperlabs.casper.consensus.state.ProtocolVersion
import io.casperlabs.casper.consensus.{BlockSummary, _}
import io.casperlabs.casper.{PrettyPrinter, ValidatorIdentity}
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.models.DeployImplicits._
import io.casperlabs.crypto.Keys
import io.casperlabs.crypto.Keys.{PrivateKey, PublicKey}
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.crypto.hash.Blake2b256
import io.casperlabs.crypto.signatures.SignatureAlgorithm
import io.casperlabs.crypto.signatures.SignatureAlgorithm.Ed25519
import io.casperlabs.ipc
import io.casperlabs.models.BlockImplicits._
import io.casperlabs.models.{Message, SmartContractEngineError, Weight}
import io.casperlabs.shared.Time
import io.casperlabs.models.cltype
import io.casperlabs.models.bytesrepr._
import io.casperlabs.storage.block.BlockStorage
import io.casperlabs.storage.dag.DagRepresentation
import io.casperlabs.models.Message.{asJRank, asMainRank, JRank, MainRank}

import scala.collection.immutable
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}
import io.casperlabs.storage.dag.DagLookup
import io.casperlabs.shared.Sorting._
import io.casperlabs.shared.ByteStringPrettyPrinter._

object ProtoUtil {
  import Weight._

  /*
   * c is in the blockchain of b iff c == b or c is in the blockchain of the main parent of b
   */
  // TODO: Move into DAG and remove corresponding param once that is moved over from simulator
  def isInMainChain[F[_]: Monad](
      dag: DagLookup[F],
      candidateBlockSummary: Message.Block,
      targetBlockHash: BlockHash
  ): F[Boolean] =
    if (candidateBlockSummary.messageHash == targetBlockHash) {
      true.pure[F]
    } else {
      for {
        targetBlockOpt <- dag.lookup(targetBlockHash)
        result <- targetBlockOpt match {
                   case Some(targetBlockMeta) =>
                     if (targetBlockMeta.jRank <= candidateBlockSummary.jRank)
                       false.pure[F]
                     else {
                       targetBlockMeta.parents.headOption match {
                         case Some(mainParentHash) =>
                           isInMainChain(dag, candidateBlockSummary, mainParentHash)
                         case None => false.pure[F]
                       }
                     }
                   case None => false.pure[F]
                 }
      } yield result
    }

  // If targetBlockHash is main descendant of candidateBlockHash, then
  // it means targetBlockHash vote candidateBlockHash.
  def isInMainChain[F[_]: Monad](
      dag: DagRepresentation[F],
      candidateBlockHash: BlockHash,
      targetBlockHash: BlockHash
  ): F[Boolean] =
    for {
      messageSummary <- dag.lookup(candidateBlockHash).map(_.get)
      result <- messageSummary match {
                 // Ballot is never in a main-chain because it's not a block and main-chain
                 // is a sub-DAG of a p-DAG.
                 case _: Message.Ballot => false.pure[F]
                 case b: Message.Block  => isInMainChain(dag, b, targetBlockHash)
               }
    } yield result

  def getMainChainUntilDepth[F[_]: MonadThrowable: BlockStorage](
      estimate: Block,
      acc: IndexedSeq[Block],
      depth: Int
  ): F[IndexedSeq[Block]] = {
    val parentsHashes       = ProtoUtil.parentHashes(estimate)
    val maybeMainParentHash = parentsHashes.headOption
    for {
      mainChain <- maybeMainParentHash match {
                    case Some(mainParentHash) =>
                      for {
                        updatedEstimate <- unsafeGetBlock[F](mainParentHash)
                        depthDelta      = blockNumber(updatedEstimate) - blockNumber(estimate)
                        newDepth        = depth + depthDelta.toInt
                        mainChain <- if (newDepth <= 0) {
                                      (acc :+ estimate).pure[F]
                                    } else {
                                      getMainChainUntilDepth[F](
                                        updatedEstimate,
                                        acc :+ estimate,
                                        newDepth
                                      )
                                    }
                      } yield mainChain
                    case None => (acc :+ estimate).pure[F]
                  }
    } yield mainChain
  }

  def unsafeGetBlockSummary[F[_]: MonadThrowable: BlockStorage](hash: BlockHash): F[BlockSummary] =
    BlockStorage[F].getBlockSummaryUnsafe(hash)

  def unsafeGetBlock[F[_]: MonadThrowable: BlockStorage](hash: BlockHash): F[Block] =
    BlockStorage[F].getBlockUnsafe(hash)

  def nextJRank(justificationMsgs: Seq[Message]): JRank =
    if (justificationMsgs.isEmpty) asJRank(0) // Genesis has rank=0
    else
      // For any other block `rank` should be 1 higher than the highest rank in its justifications.
      asJRank(justificationMsgs.map(_.jRank).max + 1)

  def nextMainRank(parents: Seq[Message]): MainRank =
    if (parents.isEmpty) asMainRank(0)
    else asMainRank(parents.head.mainRank + 1)

  def nextValidatorBlockSeqNum[F[_]: MonadThrowable](
      dag: DagRepresentation[F],
      validatorPrevBlockHash: ByteString
  ): F[Int] =
    if (validatorPrevBlockHash.isEmpty) {
      1.pure[F]
    } else {

      // TODO: Replace with lookupUnsafe
      dag.lookup(validatorPrevBlockHash).flatMap {
        case Some(meta) =>
          (1 + meta.validatorMsgSeqNum).pure[F]

        case None =>
          MonadThrowable[F].raiseError[Int](
            new NoSuchElementException(
              s"DagStorage is missing previous block hash ${validatorPrevBlockHash.show}"
            )
          )
      }
    }

  def creatorJustification(header: Block.Header): Set[Justification] =
    header.justifications.filter(_.validatorPublicKeyHash == header.validatorPublicKeyHash).toSet

  def weightMap(block: Block): Map[ByteString, Weight] =
    weightMap(block.getHeader)

  def weightMap(header: Block.Header): Map[ByteString, Weight] =
    header.getState.bonds.map {
      case Bond(validator, stake) => validator -> Weight(stake)
    }.toMap

  def weightMapTotal(weights: Map[ByteString, Weight]): Weight =
    weights.values.sum

  def totalWeight(block: Block): Weight =
    weightMapTotal(weightMap(block))

  private def mainParent[F[_]: Monad: BlockStorage](
      header: Block.Header
  ): F[Option[BlockSummary]] = {
    val maybeParentHash = header.parentHashes.headOption
    maybeParentHash match {
      case Some(parentHash) => BlockStorage[F].getBlockSummary(parentHash)
      case None             => none[BlockSummary].pure[F]
    }
  }

  /** Computes block's score by the [validator].
    *
    * @param dag
    * @param blockHash Block's hash
    * @param validator Validator that produced the block
    * @tparam F
    * @return Weight `validator` put behind the block
    */
  def weightFromValidatorByDag[F[_]: MonadThrowable](
      dag: DagRepresentation[F],
      blockHash: BlockHash,
      validator: Validator
  ): F[Weight] =
    mainParentWeightMap(dag, blockHash).map(_.getOrElse(validator, Weight.Zero))

  def weightFromValidator[F[_]: Monad: BlockStorage](
      header: Block.Header,
      validator: Validator
  ): F[Weight] =
    for {
      maybeMainParent <- mainParent[F](header)
      weightFromValidator = maybeMainParent
        .map(_.weightMap.getOrElse(validator, Weight.Zero))
        .getOrElse(weightMap(header).getOrElse(validator, Weight.Zero)) //no parents means genesis -- use itself
    } yield weightFromValidator

  def weightFromValidator[F[_]: Monad: BlockStorage](
      b: Block,
      validator: Validator
  ): F[Weight] =
    weightFromValidator[F](b.getHeader, validator)

  def weightFromSender[F[_]: Monad: BlockStorage](b: Block): F[Weight] =
    weightFromValidator[F](b, Keys.PublicKeyHash(b.getHeader.validatorPublicKeyHash))

  def weightFromSender[F[_]: Monad: BlockStorage](header: Block.Header): F[Weight] =
    weightFromValidator[F](header, Keys.PublicKeyHash(header.validatorPublicKeyHash))

  def mainParentWeightMap[F[_]: MonadThrowable](
      dag: DagRepresentation[F],
      candidateBlockHash: BlockHash
  ): F[Map[Validator, Weight]] =
    dag.lookup(candidateBlockHash).flatMap { messageOpt =>
      val message = messageOpt.get
      if (message.isGenesisLike) {

        // We know that Gensis is of [[Message.Block]] type.
        message.asInstanceOf[Message.Block].weightMap.pure[F]
      } else {
        dag.lookup(message.parentBlock).flatMap {
          case Some(b: Message.Block) => b.weightMap.pure[F]
          case Some(b: Message.Ballot) =>
            MonadThrowable[F].raiseError[Map[Validator, Weight]](
              new IllegalArgumentException(
                s"A ballot ${PrettyPrinter.buildString(b.messageHash)} was a parent block for ${PrettyPrinter
                  .buildString(message.messageHash)}"
              )
            )
          case None =>
            MonadThrowable[F].raiseError[Map[Validator, Weight]](
              new IllegalArgumentException(
                s"Missing dependency ${PrettyPrinter.buildString(message.parentBlock)}"
              )
            )
        }
      }
    }

  def parentHashes(b: Block): Seq[ByteString] =
    b.getHeader.parentHashes

  def unsafeGetParents[F[_]: MonadThrowable: BlockStorage](b: Block): F[List[Block]] =
    ProtoUtil.parentHashes(b).toList.traverse { parentHash =>
      ProtoUtil.unsafeGetBlock[F](parentHash)
    } recoverWith {
      case ex: NoSuchElementException =>
        MonadThrowable[F].raiseError {
          new NoSuchElementException(
            s"Could not retrieve parents of ${PrettyPrinter.buildString(b.blockHash)}: ${ex.getMessage}"
          )
        }
    }

  def deploys(b: Block): Map[Int, NonEmptyList[Block.ProcessedDeploy]] =
    b.getBody.deploys
      .groupBy(_.stage)
      .map {
        case (stage, deploys) =>
          // It's safe b/c it's preceeded with `groupBy`.
          stage -> NonEmptyList.fromListUnsafe(deploys.toList)
      }

  def postStateHash(b: Block): ByteString =
    b.getHeader.getState.postStateHash

  def preStateHash(b: Block): ByteString =
    b.getHeader.getState.preStateHash

  def bonds(b: Block): Seq[Bond] =
    b.getHeader.getState.bonds

  def bonds(b: BlockSummary): Seq[Bond] =
    b.getHeader.getState.bonds

  def blockNumber(b: Block): Long =
    b.getHeader.jRank

  /** Removes redundant messages that are available in the immediate justifications of another message in the set */
  def removeRedundantMessages(
      messages: Iterable[Message]
  ): Set[Message] = {
    // Builds a dependencies map.
    // ancestor -> {descendant}
    // Allows for quick test whether a block is in justifications of another one.
    val dependantsOf = messages
      .foldLeft(Map.empty[ByteString, Set[Message]]) {
        case (acc, m) =>
          m.justifications
            .map(_.latestBlockHash)
            .map(_ -> Set(m))
            .toMap |+| acc
      }
    val ancestors   = dependantsOf.keySet
    val descendants = dependantsOf.values.flatten.toSet
    // Filter out messages that are in justifications of another one.
    descendants.filterNot(m => ancestors.contains(m.messageHash))
  }

  def toJustification(
      latestMessages: Seq[Message]
  ): Seq[Justification] =
    latestMessages.map { messageSummary =>
      Block
        .Justification()
        .withValidatorPublicKeyHash(messageSummary.validatorId)
        .withLatestBlockHash(messageSummary.messageHash)
    }

  def getJustificationMsgHashes(
      justifications: Seq[Justification]
  ): immutable.Map[Validator, Set[BlockHash]] =
    justifications
      .groupBy(j => Keys.PublicKeyHash(j.validatorPublicKeyHash))
      .mapValues(_.map(_.latestBlockHash).toSet)

  def getJustificationMsgs[F[_]: MonadThrowable](
      dag: DagRepresentation[F],
      justifications: Seq[Justification]
  ): F[Map[Validator, Set[Message]]] =
    justifications.toList.foldM(Map.empty[Validator, Set[Message]]) {
      case (acc, Justification(validator, hash)) =>
        dag.lookup(hash).flatMap {
          case Some(meta) =>
            acc.combine(Map(Keys.PublicKeyHash(validator) -> Set(meta))).pure[F]

          case None =>
            MonadThrowable[F].raiseError(
              new NoSuchElementException(
                s"DagStorage is missing hash ${hash.show}"
              )
            )
        }
    }

  def protoHash[A <: scalapb.GeneratedMessage](data: A): ByteString =
    ByteString.copyFrom(Blake2b256.hash(data.toByteArray))

  /* Creates a Genesis block. Genesis is not signed */
  def genesis(
      preStateHash: ByteString,
      postStateHash: ByteString,
      bonds: Seq[Bond],
      chainName: String,
      protocolVersion: ProtocolVersion,
      now: Long
  ): Block = {
    val header = Block
      .Header()
      .withMessageType(MessageType.BLOCK)
      .withProtocolVersion(protocolVersion)
      .withTimestamp(now)
      .withChainName(chainName)
      .withState(
        GlobalState()
          .withPreStateHash(preStateHash)
          .withPostStateHash(postStateHash)
          .withBonds(bonds)
      )

    unsignedBlockProto(Block.Body(), header)
  }

  /* Creates a signed block */
  def block(
      justifications: Seq[Justification],
      preStateHash: ByteString,
      postStateHash: ByteString,
      bondedValidators: Seq[Bond],
      deploys: Seq[Block.ProcessedDeploy],
      protocolVersion: ProtocolVersion,
      parents: Seq[ByteString],
      validatorSeqNum: Int,
      validatorPrevBlockHash: ByteString,
      chainName: String,
      now: Long,
      jRank: JRank,
      mainRank: MainRank,
      validator: ValidatorIdentity,
      keyBlockHash: ByteString,
      roundId: Long,
      magicBit: Boolean,
      messageRole: Block.MessageRole = Block.MessageRole.UNDEFINED
  ): Block = {
    val body = Block.Body().withDeploys(deploys)
    val postState = Block
      .GlobalState()
      .withPreStateHash(preStateHash)
      .withPostStateHash(postStateHash)
      .withBonds(bondedValidators)

    val header = blockHeader(
      body,
      parentHashes = parents,
      justifications = justifications,
      state = postState,
      jRank = jRank,
      mainRank = mainRank,
      protocolVersion = protocolVersion,
      timestamp = now,
      chainName = chainName,
      creator = validator,
      validatorSeqNum = validatorSeqNum,
      validatorPrevBlockHash = validatorPrevBlockHash,
      keyBlockHash = keyBlockHash,
      roundId = roundId,
      magicBit = magicBit
    ).withMessageRole(messageRole)

    val unsigned = unsignedBlockProto(body, header)

    signBlock(
      unsigned,
      validator.privateKey,
      validator.signatureAlgorithm
    )
  }

  /* Creates a signed ballot */
  def ballot(
      justifications: Seq[Justification],
      stateHash: ByteString,
      bondedValidators: Seq[Bond],
      protocolVersion: ProtocolVersion,
      parent: ByteString,
      validatorSeqNum: Int,
      validatorPrevBlockHash: ByteString,
      chainName: String,
      now: Long,
      jRank: JRank,
      mainRank: MainRank,
      validator: ValidatorIdentity,
      keyBlockHash: ByteString,
      roundId: Long,
      messageRole: Block.MessageRole = Block.MessageRole.UNDEFINED
  ): Block = {
    val body = Block.Body()

    val postState = Block
      .GlobalState()
      .withPreStateHash(stateHash)
      .withPostStateHash(stateHash)
      .withBonds(bondedValidators)

    val header = blockHeader(
      body,
      parentHashes = List(parent),
      justifications = justifications,
      state = postState,
      jRank = jRank,
      mainRank = mainRank,
      protocolVersion = protocolVersion,
      timestamp = now,
      chainName = chainName,
      creator = validator,
      validatorSeqNum = validatorSeqNum,
      validatorPrevBlockHash = validatorPrevBlockHash,
      keyBlockHash = keyBlockHash,
      roundId = roundId
    ).withMessageRole(messageRole).withMessageType(Block.MessageType.BALLOT)

    val unsigned = unsignedBlockProto(body, header)

    signBlock(
      unsigned,
      validator.privateKey,
      validator.signatureAlgorithm
    )
  }

  def blockHeader(
      body: Block.Body,
      creator: ValidatorIdentity,
      parentHashes: Seq[ByteString],
      justifications: Seq[Justification],
      state: Block.GlobalState,
      jRank: JRank,
      mainRank: MainRank,
      validatorSeqNum: Int,
      validatorPrevBlockHash: ByteString,
      protocolVersion: ProtocolVersion,
      timestamp: Long,
      chainName: String,
      keyBlockHash: ByteString = ByteString.EMPTY, // For Genesis it will be empty.
      roundId: Long = 0,
      magicBit: Boolean = false
  ): Block.Header =
    Block
      .Header()
      .withKeyBlockHash(keyBlockHash)
      .withRoundId(roundId)
      .withMagicBit(magicBit)
      .withParentHashes(parentHashes)
      .withJustifications(justifications)
      .withDeployCount(body.deploys.size)
      .withState(state)
      .withJRank(jRank)
      .withMainRank(mainRank)
      .withValidatorPublicKey(ByteString.copyFrom(creator.publicKey))
      .withValidatorPublicKeyHash(ByteString.copyFrom(creator.publicKeyHash))
      .withValidatorBlockSeqNum(validatorSeqNum)
      .withValidatorPrevBlockHash(validatorPrevBlockHash)
      .withProtocolVersion(protocolVersion)
      .withTimestamp(timestamp)
      .withChainName(chainName)
      .withBodyHash(protoHash(body))

  def unsignedBlockProto(
      body: Block.Body,
      header: Block.Header
  ): Block = {
    val headerWithBodyHash = header
      .withBodyHash(protoHash(body))

    val blockHash = protoHash(headerWithBodyHash)

    Block()
      .withBlockHash(blockHash)
      .withHeader(headerWithBodyHash)
      .withBody(body)
  }

  def signBlock(
      block: Block,
      sk: PrivateKey,
      sigAlgorithm: SignatureAlgorithm
  ): Block = {
    val blockHash = protoHash(block.getHeader)
    val sig       = ByteString.copyFrom(sigAlgorithm.sign(blockHash.toByteArray, sk))
    block
      .withBlockHash(blockHash)
      .withSignature(
        Signature()
          .withSigAlgorithm(sigAlgorithm.name)
          .withSig(sig)
      )
  }

  def stringToByteString(string: String): ByteString =
    ByteString.copyFrom(Base16.decode(string))

  def getTimeToLive(h: Deploy.Header, default: Int): Int =
    if (h.ttlMillis == 0) default
    else h.ttlMillis

  def basicDeploy[F[_]: Monad: Time](ttl: FiniteDuration = 1.minute): F[Deploy] =
    Time[F].currentMillis.map { now =>
      // The timestamp needs to be earlier than the time the node
      // thinks it is; in the tests we use "logical time", so 0
      // is the only safe value.
      deploy(0, ByteString.copyFromUtf8(now.toString), ttl)
    }

  // This is only used for tests.
  def deploy(
      timestamp: Long,
      sessionCode: ByteString = ByteString.EMPTY,
      ttl: FiniteDuration = 1.minute,
      signatureAlgorithm: SignatureAlgorithm = Ed25519
  ): Deploy = {
    val (sk, pk) = signatureAlgorithm.newKeyPair
    val b = Deploy
      .Body()
      .withSession(Deploy.Code().withWasmContract(Deploy.Code.WasmContract(sessionCode)))
      .withPayment(Deploy.Code().withWasmContract(Deploy.Code.WasmContract(sessionCode)))
    val h = Deploy
      .Header()
      .withAccountPublicKeyHash(ByteString.copyFrom(signatureAlgorithm.publicKeyHash(pk)))
      .withTimestamp(timestamp)
      .withBodyHash(protoHash(b))
      .withTtlMillis(ttl.toMillis.toInt)
    Deploy()
      .withHeader(h)
      .withBody(b)
      .withHashes
      .approve(signatureAlgorithm, sk, pk)

  }

  def basicProcessedDeploy[F[_]: Monad: Time](): F[Block.ProcessedDeploy] =
    basicDeploy[F]().map(deploy => Block.ProcessedDeploy(deploy = Some(deploy), cost = 1L))

  def sourceDeploy(source: String, timestamp: Long, ttl: FiniteDuration = 1.minute): Deploy =
    deploy(timestamp, ByteString.copyFromUtf8(source), ttl)

  // https://casperlabs.atlassian.net/browse/EE-283
  // We are hardcoding exchange rate for DEV NET at 10:1
  // (1 gas costs you 10 motes).
  // Later, post DEV NET, conversion rate will be part of a deploy.
  val GAS_PRICE = 10L

  def deployDataToEEDeploy(d: Deploy): Try[ipc.DeployItem] = {
    def toPayload(maybeCode: Option[Deploy.Code]): Try[Option[ipc.DeployPayload]] =
      maybeCode match {
        case None       => Try(none[ipc.DeployPayload])
        case Some(code) => (deployCodeToDeployPayload(code).map(Some(_)))
      }

    for {
      session <- toPayload(d.getBody.session)
      payment <- toPayload(d.getBody.payment)
      keyHashes <- d.approvals.toList.map { approval =>
                    approval.getSignature.sigAlgorithm match {
                      case SignatureAlgorithm(alg) =>
                        val key  = PublicKey(approval.approverPublicKey.toByteArray)
                        val hash = alg.publicKeyHash(key)
                        Success(ByteString.copyFrom(hash))

                      case unknown =>
                        Failure(
                          new IllegalArgumentException(s"Unknown signature algorithm: $unknown")
                        )
                    }
                  }.sequence
    } yield {
      ipc.DeployItem(
        address = d.getHeader.accountPublicKeyHash,
        session = session,
        payment = payment,
        gasPrice = GAS_PRICE,
        authorizationKeys = keyHashes,
        deployHash = d.deployHash
      )
    }
  }

  def deployCodeToDeployPayload(code: Deploy.Code): Try[ipc.DeployPayload] = {
    val argsF: Try[ByteString] = code.args.toList.traverse(cltype.protobuf.Mappings.fromArg) match {
      case Left(err) =>
        Try(throw new SmartContractEngineError(s"Error parsing deploy arguments: $err"))
      case Right(args) =>
        Try(
          ByteString.copyFrom(
            ToBytes[Map[String, cltype.CLValue]].toBytes(args.toMap)
          )
        )
    }

    argsF.flatMap { args =>
      val payload = code.contract match {
        case Deploy.Code.Contract.WasmContract(contract) =>
          Success(ipc.DeployPayload.Payload.DeployCode(ipc.DeployCode(contract.wasm, args)))

        case Deploy.Code.Contract.StoredContract(contract) =>
          if (contract.address.isName) {
            Success(
              ipc.DeployPayload.Payload.StoredContractName(
                io.casperlabs.ipc
                  .StoredContractName(
                    contract.getName,
                    args,
                    contract.entryPoint
                  )
              )
            )
          } else if (contract.address.isContractHash) {
            Success(
              ipc.DeployPayload.Payload.StoredContractHash(
                io.casperlabs.ipc
                  .StoredContractHash(
                    contract.getContractHash,
                    args,
                    contract.entryPoint
                  )
              )
            )
          } else
            Failure(
              new SmartContractEngineError(
                s"${contract.address} is not valid contract pointer. Only hash and named key label are supported."
              )
            )

        case Deploy.Code.Contract
              .StoredVersionedContract(contract) =>
          if (contract.address.isName) {
            Success(
              ipc.DeployPayload.Payload.StoredPackageByName(
                io.casperlabs.ipc
                  .StoredContractPackage(
                    contract.getName,
                    contract.entryPoint,
                    args,
                    contract.version match {
                      case 0 => ipc.StoredContractPackage.OptionalVersion.Empty
                      case v => ipc.StoredContractPackage.OptionalVersion.Version(v)
                    }
                  )
              )
            )
          } else if (contract.address.isPackageHash) {
            Success(
              ipc.DeployPayload.Payload.StoredPackageByHash(
                io.casperlabs.ipc
                  .StoredContractPackageHash(
                    contract.getPackageHash,
                    contract.entryPoint,
                    args,
                    contract.version match {
                      case 0 => ipc.StoredContractPackageHash.OptionalVersion.Empty
                      case v => ipc.StoredContractPackageHash.OptionalVersion.Version(v)
                    }
                  )
              )
            )
          } else
            Failure(
              new SmartContractEngineError(
                s"${contract.address} is not valid contract pointer. Only hash and named key label are supported."
              )
            )

        case Deploy.Code.Contract.TransferContract(_) =>
          Success(
            ipc.DeployPayload.Payload.Transfer(
              io.casperlabs.ipc.Transfer(args)
            )
          )

        case Deploy.Code.Contract.Empty =>
          Success(ipc.DeployPayload.Payload.DeployCode(ipc.DeployCode(ByteString.EMPTY, args)))
      }
      payload.map(ipc.DeployPayload(_))
    }
  }

  def dependenciesHashesOf(b: Block): List[BlockHash] = {
    val missingParents = parentHashes(b).toSet
    val missingJustifications = b.getHeader.justifications
      .map(_.latestBlockHash)
      .toSet
    (missingParents union missingJustifications).toList
  }

  def createdBy(validatorId: ValidatorIdentity, block: Block): Boolean =
    block.getHeader.validatorPublicKeyHash == ByteString.copyFrom(validatorId.publicKeyHash)

  def randomAccountAddress(): ByteString =
    ByteString.copyFrom(scala.util.Random.nextString(32), "UTF-8")
}
