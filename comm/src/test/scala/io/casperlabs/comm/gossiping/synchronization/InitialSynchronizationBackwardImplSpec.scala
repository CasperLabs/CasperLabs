package io.casperlabs.comm.gossiping.synchronization

import java.util.concurrent.TimeoutException

import cats.effect.concurrent.Semaphore
import cats.syntax.either._
import com.google.protobuf.ByteString
import io.casperlabs.crypto.Keys.PublicKeyHashBS
import io.casperlabs.casper.consensus.{Approval, Block, BlockSummary, DeploySummary}
import io.casperlabs.comm.discovery.{Node, NodeDiscovery, NodeIdentifier}
import io.casperlabs.comm.gossiping._
import io.casperlabs.comm.gossiping.downloadmanager._
import io.casperlabs.comm.gossiping.synchronization.InitialSynchronization.SynchronizationError
import io.casperlabs.comm.gossiping.synchronization.InitialSynchronizationBackwardImplSpec.TestFixture
import io.casperlabs.comm.gossiping.synchronization.Synchronizer.SyncError
import io.casperlabs.metrics.Metrics
import io.casperlabs.models.{ArbitraryConsensus, Message}
import io.casperlabs.shared.Log
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import monix.execution.atomic.{Atomic, AtomicInt}
import monix.tail.Iterant
import org.scalacheck.{Gen, Shrink}
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.{BeforeAndAfterEach, Inspectors, Matchers, WordSpecLike}

import scala.concurrent.duration._

class InitialSynchronizationBackwardImplSpec
    extends WordSpecLike
    with Matchers
    with BeforeAndAfterEach
    with ArbitraryConsensusAndComm
    with GeneratorDrivenPropertyChecks {
  private implicit def noShrink[T]: Shrink[T] = Shrink.shrinkAny

  private implicit val chainId: ByteString = sample(genHash)

  def genNodes(min: Int = 1, max: Int = 10) =
    Gen.choose(min, max).flatMap(n => Gen.listOfN(n, arbNode.arbitrary))

  def genTips(n: Int = 10) = Gen.listOfN(n, arbBlockSummary.arbitrary)

  "InitialSynchronization" when {
    "doesn't have nodes in the initial round" should {
      "try again later" in forAll(genNodes(), genTips()) { (nodes, tips) =>
        val counter = AtomicInt(0)
        TestFixture(
          nodes,
          tips,
          selectNodes = { nodes =>
            val cnt = counter.incrementAndGet()
            if (cnt == 1) Nil else nodes
          },
          minSuccessful = 1
        ) { (initialSynchronizer, _) =>
          for {
            w <- initialSynchronizer.sync()
            _ <- w.timeout(1.second)
          } yield {
            counter.get() shouldBe 2
          }
        }
      }
    }
    "specified to memoize nodes between rounds" should {
      def test(skipFailedNodesInNextRound: Boolean): Unit = forAll(genNodes(), genTips()) {
        (nodes, tips) =>
          val counter = Atomic(0)

          TestFixture(
            nodes,
            tips,
            memoizeNodes = true,
            selectNodes = { nodes =>
              counter.increment()
              nodes
            },
            minSuccessful = nodes.size,
            sync = (_, _) => Task.raiseError(new RuntimeException),
            skipFailedNodesInNextRounds = skipFailedNodesInNextRound
          ) { (initialSynchronizer, _) =>
            for {
              w <- initialSynchronizer.sync()
              _ <- w.timeout(75.millis).attempt
            } yield {
              counter.get() shouldBe 1
            }
          }
      }

      "not apply selectNodes function more than once if skipFailingNodesInNextRound is true" in {
        test(skipFailedNodesInNextRound = true)
      }
      "not apply selectNodes function more than once if skipFailingNodesInNextRound is false" in {
        test(skipFailedNodesInNextRound = false)
      }
    }
    "specified to skip nodes failed to response" should {
      def test(memoize: Boolean): Unit =
        forAll(genNodes().map(_.toSet), genNodes().map(_.toSet), genTips()) {
          (successfulNodes, failingNodes, tips) =>
            TestFixture(
              (successfulNodes ++ failingNodes).toList,
              tips,
              memoizeNodes = memoize,
              sync = { (node, _) =>
                if (successfulNodes(node))
                  Task(true)
                else
                  Task.raiseError(new RuntimeException)
              },
              skipFailedNodesInNextRounds = true
            ) { (initialSynchronizer, mockGossipServiceServer) =>
              for {
                w <- initialSynchronizer.sync()
                // It's configured with minimum isccessful being infinite so it will try
                // forever and fail. We just want to give it enough time to try all of them.
                _ <- w.timeout(200.millis).attempt
              } yield {
                val asked = mockGossipServiceServer.asked.get()
                Inspectors.forAll(failingNodes) { node =>
                  asked.count(n => n == node) shouldBe 1
                }
                Inspectors.forAll(successfulNodes) { node =>
                  asked.count(n => n == node) should be >= 1
                }
              }
            }
        }

      """
        |invoke successful nodes multiple times and
        |not include failed nodes in next rounds
        |if memoization is true
      """.stripMargin in {
        test(memoize = true)
      }
      """
        |invoke successful nodes multiple times and
        |not include failed nodes in next rounds
        |if memoization is false
      """.stripMargin in {
        test(memoize = false)
      }

      "fails with error if all nodes responds with error" in forAll(genNodes(), genTips()) {
        (nodes, tips) =>
          TestFixture(
            nodes,
            tips,
            sync = (_, _) => Task.raiseError(new RuntimeException),
            skipFailedNodesInNextRounds = true
          ) { (initialSynchronizer, _) =>
            for {
              w <- initialSynchronizer.sync()
              r <- w.attempt
            } yield {
              r.isLeft shouldBe true
              r.left.get shouldBe an[SynchronizationError]
            }
          }
      }
    }

    "reaches minSuccessful amount of successful syncs" should {
      "resolve the handle" in forAll(genNodes(), genTips()) { (nodes, tips) =>
        val flag = Atomic(false)
        TestFixture(
          nodes,
          tips,
          sync = { (_, _) =>
            if (flag.get()) {
              Task(true)
            } else {
              Task.raiseError(new RuntimeException)
            }
          },
          minSuccessful = nodes.size
        ) { (initialSynchronizer, _) =>
          for {
            w1 <- initialSynchronizer.sync()
            r1 <- w1.timeout(75.millis).attempt
            _ <- Task {
                  r1.isLeft shouldBe true
                  r1.left.get shouldBe an[TimeoutException]
                }
            _ <- Task {
                  flag.set(true)
                }
            w2 <- initialSynchronizer.sync()
            r2 <- w2.attempt
            _ <- Task {
                  r2.isRight shouldBe true
                }
          } yield ()
        }
      }
    }
  }
}

object InitialSynchronizationBackwardImplSpec extends ArbitraryConsensus {
  implicit val logNoOp = Log.NOPLog[Task]
  implicit val metris  = new Metrics.MetricsNOP[Task]

  class MockNodeDiscovery(nodes: List[Node]) extends NoOpsNodeDiscovery[Task] {
    override def recentlyAlivePeersAscendingDistance = Task.now(nodes)
  }

  object MockBackend extends NoOpsGossipServiceServerBackend[Task] {}

  object MockSynchronizer extends NoOpsSynchronizer[Task] {}

  object MockGossipServiceConnector extends (Node => Task[GossipService[Task]]) {
    override def apply(node: Node): Task[GossipService[Task]] = ???
  }

  object MockDeployDownloadManager extends NoOpsDeployDownloadManager[Task] {}
  object MockBlockDownloadManager  extends NoOpsBlockDownloadManager[Task]  {}

  object MockGenesisApprover extends NoOpsGenesisApprover[Task] {}

  val MockSemaphore = Semaphore[Task](1).runSyncUnsafe(1.second)

  class MockGossipServiceServer(sync: (Node, Seq[ByteString]) => Task[Boolean])
      extends GossipServiceServer[Task](
        MockBackend,
        MockSynchronizer,
        MockGossipServiceConnector,
        MockDeployDownloadManager,
        MockBlockDownloadManager,
        MockGenesisApprover,
        0,
        false,
        MockSemaphore
      ) {
    val asked = Atomic(Vector.empty[Node])

    override def newBlocksSynchronous(
        request: NewBlocksRequest,
        skipRelaying: Boolean
    ): Task[Either[SyncError, NewBlocksResponse]] = {
      asked.transform(_ :+ request.getSender)
      sync(request.getSender, request.blockHashes)
        .map(b => NewBlocksResponse(isNew = b).asRight[SyncError])
    }
  }

  class MockGossipService(latestMessages: Map[PublicKeyHashBS, Set[Message]])
      extends NoOpsGossipService[Task] {

    override def streamLatestMessages(
        request: StreamLatestMessagesRequest
    ): Iterant[Task, Block.Justification] =
      Iterant.fromSeq(
        latestMessages.values
          .flatMap(_.map(m => Block.Justification(m.validatorId, m.messageHash)))
          .toSeq
      )
  }

  object TestFixture {
    def apply(
        nodes: List[Node],
        latestMessages: List[BlockSummary],
        sync: (Node, Seq[ByteString]) => Task[Boolean] = (_, _) => Task(true),
        selectNodes: List[Node] => List[Node] = _.distinct,
        memoizeNodes: Boolean = false,
        minSuccessful: Int = Int.MaxValue,
        skipFailedNodesInNextRounds: Boolean = false,
        roundPeriod: FiniteDuration = 25.millis
    )(test: (InitialSynchronization[Task], MockGossipServiceServer) => Task[Unit]): Unit = {
      val mockGossipServiceServer = new MockGossipServiceServer(sync)
      val mockGossipService: GossipService[Task] = new MockGossipService(
        latestMessages
          .map(Message.fromBlockSummary(_).get)
          .groupBy(_.validatorId)
          .mapValues(_.toSet)
      )
      val effect = new InitialSynchronizationBackwardImpl[Task](
        nodeDiscovery = new MockNodeDiscovery(nodes),
        mockGossipServiceServer,
        selectNodes,
        memoizeNodes,
        _ => Task(mockGossipService),
        minSuccessful,
        skipFailedNodesInNextRounds,
        roundPeriod
      )
      test(effect, mockGossipServiceServer).runSyncUnsafe(5.seconds)
    }
  }
}
