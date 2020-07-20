package io.casperlabs.storage.benchmarks

import java.util.Properties

import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.state.{Key, ProtocolVersion}
import io.casperlabs.casper.consensus.{Block, Deploy}
import io.casperlabs.ipc.Transform.TransformInstance
import io.casperlabs.ipc._
import io.casperlabs.metrics.Metrics
import io.casperlabs.shared.Log
import io.casperlabs.storage.BlockMsgWithTransform
import io.casperlabs.storage.BlockHash
import io.casperlabs.storage.BlockMsgWithTransform.StageEffects
import io.casperlabs.storage.dag.DagStorage
import io.casperlabs.{metrics, shared}
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global

import scala.collection.immutable.IndexedSeq
import scala.util.Random

object StorageBenchSuite {
  import scala.language.implicitConversions

  implicit def strToByteStr(str: String): ByteString =
    ByteString.copyFromUtf8(str)

  implicit val metricsNop: Metrics[Task] = new metrics.Metrics.MetricsNOP[Task]
  implicit val logNop: Log[Task]         = shared.Log.NOPLog[Task]

  private val props = {
    val p  = new Properties()
    val in = getClass.getResourceAsStream("/block-storage-benchmark.properties")
    p.load(in)
    in.close()
    p
  }

  def getIntProp(name: String) =
    props.get(name).asInstanceOf[String].toInt

  val blockSize    = getIntProp("blockSizeInKb")
  val preCreated   = getIntProp("preCreatedBlocks")
  val preAllocSize = getIntProp("preAllocBlocksEachIteration")

  private val preCreatedBlocks = (0 to preCreated).map(_ => randomBlock)

  val blocksIter = repeatedIteratorFrom(preCreatedBlocks)

  def randomHexString(numchars: Int): String = {
    val sb = new StringBuffer
    while (sb.length < numchars) sb.append(Integer.toHexString(Random.nextInt))

    sb.toString.substring(0, numchars)
  }

  def randomBlock: (BlockHash, BlockMsgWithTransform) =
    (randomHash, randomBlockMsgWithTransform)

  def randomHash: BlockHash =
    strToByteStr(randomHexString(32))

  //The implementation assumes that this method will return a data
  //with approximately 1KB size
  //Take this into account before change it
  def randomDeployData: Deploy =
    Deploy()
      .withHeader(Deploy.Header().withAccountPublicKeyHash(randomHexString(32)))
      .withBody(
        Deploy
          .Body()
          .withPayment(
            Deploy
              .Code()
              .withWasmContract(Deploy.Code.WasmContract(randomHexString(512)))
          )
          .withSession(
            Deploy
              .Code()
              .withWasmContract(Deploy.Code.WasmContract(randomHexString(480)))
          )
      )

  def randomDeploy: Block.ProcessedDeploy =
    Block
      .ProcessedDeploy()
      .withDeploy(randomDeployData)

  def randomBody: Block.Body =
    Block
      .Body()
      .withDeploys(
        (0 to blockSize) map (_ => randomDeploy)
      )

  def randomBlockMessage: Block = {
    val hash      = randomHash
    val validator = randomHexString(32)
    val version   = Random.nextLong()
    val timestamp = Random.nextLong()
    val parents   = (0 to Random.nextInt(3)) map (_ => randomHash)
    val justifications = (0 to Random.nextInt(10)) map (
        _ => Block.Justification(randomHash, randomHash)
    )
    Block()
      .withBlockHash(hash)
      .withHeader(
        Block
          .Header()
          .withParentHashes(parents)
          .withJustifications(justifications)
          .withProtocolVersion(ProtocolVersion(version.toInt))
          .withTimestamp(timestamp)
          .withValidatorPublicKeyHash(validator)
      )
      .withBody(randomBody)
  }

  def randomKey: Key = Key(
    Key.Value.Hash(Key.Hash(randomHash))
  )

  def randomTransform: Transform = Transform(
    TransformInstance.AddI32(TransformAddInt32(Random.nextInt()))
  )

  def randomTransformEntry: TransformEntry =
    TransformEntry(Some(randomKey), Some(randomTransform))

  def randomBlockMsgWithTransform: BlockMsgWithTransform =
    BlockMsgWithTransform(
      Some(randomBlockMessage),
      Seq(StageEffects(0, (0 to Random.nextInt(25)).map(_ => randomTransformEntry)))
    )

  //This is needed because the alternative `Iterator.continually(elems).flatten`
  //is not thread-safe, so it can throw OOB exception during the execution
  def repeatedIteratorFrom[A](elems: IndexedSeq[A]): Iterator[A] =
    Iterator
      .iterate((0, elems(0))) {
        case (n, _) =>
          val next = (n + 1) % elems.length
          (next, elems(next))
      }
      .map(_._2)
}
