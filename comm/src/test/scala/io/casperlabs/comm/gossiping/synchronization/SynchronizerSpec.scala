package io.casperlabs.comm.gossiping.synchronization

import cats.effect.concurrent.Ref
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.models.BlockImplicits._
import io.casperlabs.casper.consensus.state
import io.casperlabs.casper.consensus.Block.Justification
import io.casperlabs.casper.consensus.{BlockSummary, Bond, GenesisCandidate}
import io.casperlabs.comm.discovery.Node
import io.casperlabs.comm.gossiping._
import io.casperlabs.comm.gossiping.synchronization._
import io.casperlabs.comm.gossiping.synchronization.Synchronizer.SyncError
import io.casperlabs.metrics.Metrics
import io.casperlabs.shared.LogStub
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import monix.execution.atomic.AtomicInt
import monix.execution.schedulers.CanBlock.permit
import monix.tail.Iterant
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.{BeforeAndAfterEach, Inspectors, Matchers, WordSpecLike}

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._

class SynchronizerSpec
    extends WordSpecLike
    with Matchers
    with BeforeAndAfterEach
    with ArbitraryConsensusAndComm
    with GeneratorDrivenPropertyChecks {
  import SynchronizerSpec._

  override implicit val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(minSuccessful = 25, workers = 1)

  override def numValidators = 3

  implicit val consensusConfig: ConsensusConfig = ConsensusConfig(dagDepth = 5, dagWidth = 5)

  def genPositiveInt(min: Int, max: Int): Gen[Int] =
    Gen.choose(min, max)

  def genDoubleGreaterEqualOf0(max: Double): Gen[Double] =
    Gen.choose(0.0, max)

  // A dependency-free summary.
  def sampleSummary = {
    val s = sample[BlockSummary]
    s.withHeader(s.getHeader.clearJustifications.clearParentHashes)
  }
  implicit class SampleSummaryOps(s: BlockSummary) {
    def withParents(ps: BlockSummary*) =
      s.withHeader(s.getHeader.withParentHashes(ps.map(_.blockHash)))
    def sampleChild: BlockSummary =
      sampleSummary.withParents(s)
  }
  implicit class SampleSummaryListOps(ss: List[BlockSummary]) {
    def sampleChild: BlockSummary =
      sampleSummary.withParents(ss: _*)
  }

  "Synchronizer" when {
    "streamed DAG contains cycle" should {
      "return SyncError.Cycle" in forAll(genPartialDagFromTips) { dag =>
        log.reset()
        val withCycle = dag ++ dag.take(1)

        def test(cycledDag: Vector[BlockSummary]): Unit = TestFixture(cycledDag)() {
          (synchronizer, _) =>
            synchronizer.syncDag(Node(), Set(dag.head.blockHash)) foreachL { dagOrError =>
              dagOrError.isLeft shouldBe true
              dagOrError.left.get shouldBe an[SyncError.Cycle]
              dagOrError.left.get
                .asInstanceOf[SyncError.Cycle]
                .summary shouldBe cycledDag.last
            }
        }
        test(withCycle)
      }
    }
    "streamed DAG is too deep" should {
      "return SyncError.TooDeep" in forAll(
        genPartialDagFromTips,
        genPositiveInt(1, consensusConfig.dagDepth - 1)
      ) { (dag, n) =>
        log.reset()
        TestFixture(dag)(maxPossibleDepth = n) { (synchronizer, _) =>
          synchronizer.syncDag(Node(), Set(dag.head.blockHash)).foreachL { dagOrError =>
            dagOrError.isLeft shouldBe true
            dagOrError.left.get shouldBe an[SyncError.TooDeep]
          }
        }
      }
    }
    "streamed DAG is abnormally wide" should {
      "return SyncError.TooWide" in forAll(
        genPartialDagFromTips(
          ConsensusConfig(dagDepth = 5, dagBranchingFactor = 4, dagWidth = Int.MaxValue)
        ),
        genDoubleGreaterEqualOf0(3)
      ) { (dag, n) =>
        log.reset()
        TestFixture(dag)(maxBondingRate = n, minBlockCountToCheckWidth = 0) { (synchronizer, _) =>
          synchronizer.syncDag(Node(), Set(dag.head.blockHash)).foreachL { dagOrError =>
            dagOrError.isLeft shouldBe true
            dagOrError.left.get shouldBe an[SyncError.TooWide]
          }
        }
      }
    }
    "streamed DAG is not abnormally wide but has justifications on different ranks" should {
      "not return SyncError.TooWide" in forAll(
        genSummaryDagFromGenesis(
          ConsensusConfig(dagSize = 20)
        )
      ) { dag =>
        val summaryMap = dag.groupBy(_.blockHash).mapValues(_.head)
        def collectAncestors(acc: Set[ByteString], blockHash: ByteString): Set[ByteString] = {
          val summary = summaryMap(blockHash)
          val deps = summary.justifications
            .map(_.latestBlockHash) ++ summary.parentHashes
          deps.foldLeft(acc) {
            case (acc, dep) if !acc(dep) =>
              collectAncestors(acc + dep, dep)
            case (acc, _) =>
              acc
          }
        }
        val target    = dag.last.blockHash
        val ancestors = collectAncestors(Set(target), target)
        val bonds =
          dag.map(_.validatorPublicKeyHash).distinct.map(Bond(_).withStake(state.BigInt("1", 512)))
        val subdag = dag.filter(x => ancestors(x.blockHash)).map { summary =>
          summary
            .withHeader(summary.getHeader.withState(summary.state.withBonds(bonds)))
        }
        log.reset()
        TestFixture(subdag.reverse)(
          maxBondingRate = 1.0,
          minBlockCountToCheckWidth = 0,
          maxDepthAncestorsRequest = 20
        ) { (synchronizer, _) =>
          synchronizer.syncDag(Node(), Set(target)).map {
            _ match {
              case Right(dag) => dag should contain theSameElementsAs subdag
              case Left(ex)   => throw ex
            }
          }
        }
      }
    }

    "streamed summary can not be connected to initial block hashes" should {
      "return SyncError.Unreachable" in forAll(
        genPartialDagFromTips,
        arbBlockSummary.arbitrary
      ) { (dag, arbitraryBlock) =>
        log.reset()
        TestFixture(dag :+ arbitraryBlock)() { (synchronizer, _) =>
          synchronizer.syncDag(Node(), Set(dag.head.blockHash)).foreachL { dagOrError =>
            dagOrError.isLeft shouldBe true
            dagOrError.left.get shouldBe an[SyncError.Unreachable]
          }
        }
      }
    }

    "streamed summary is too far away from initial block hashes" should {
      "return SyncError.Unreachable" in forAll(
        genPartialDagFromTips,
        genPositiveInt(1, consensusConfig.dagDepth - 2)
      ) { (dag, n) =>
        log.reset()
        TestFixture(dag)(maxDepthAncestorsRequest = n) { (synchronizer, _) =>
          synchronizer.syncDag(Node(), Set(dag.head.blockHash)).foreachL { dagOrError =>
            dagOrError.isLeft shouldBe true
            dagOrError.left.get shouldBe an[SyncError.Unreachable]
          }
        }
      }
    }

    "there are multiple paths leading to the same block" should {
      "use the shortest for reachability checks" in {
        // Say we have the following DAG and sync from J with max depth 2; all we have is A.
        //            E - G - I
        //          /          \
        // A - B - C - F - H --- J
        //
        // First we'll get [J,I,H,G,F], which will tell us that we are missing parent hashes C and E.
        // Second time we set targets [C,E] and get [E,C,B,A]. The distance check of A must be on the
        // the shorter [A,B,C] path, not the longer [A,B,C,E] which would be over the limit.

        val a = sampleSummary
        val b = a.sampleChild
        val c = b.sampleChild
        val e = c.sampleChild
        val f = c.sampleChild
        val g = e.sampleChild
        val h = f.sampleChild
        val i = g.sampleChild
        val j = List(h, i).sampleChild

        val first  = Vector(j, i, h, g, f)
        val second = Vector(e, c, b, a)

        TestFixture(first, second)(
          maxDepthAncestorsRequest = 2,
          notInDag = hash => Task.now(hash != a.blockHash)
        ) { (synchronizer, _) =>
          synchronizer.syncDag(Node(), Set(j.blockHash)).foreachL { dagOrError =>
            dagOrError match {
              case Left(err)  => fail(err.getMessage)
              case Right(dag) => dag should have size (first.size + second.size).toLong
            }
          }
        }
      }
    }

    "streamed block summary can not be validated" should {
      "return SyncError.ValidationError" in forAll(
        genPartialDagFromTips
      ) { dag =>
        log.reset()
        val e = new RuntimeException("Boom!")
        TestFixture(dag)(validate = _ => Task.raiseError[Unit](e)) { (synchronizer, _) =>
          synchronizer.syncDag(Node(), Set(dag.head.blockHash)).foreachL { dagOrError =>
            dagOrError.isLeft shouldBe true
            dagOrError.left.get shouldBe an[SyncError.ValidationError]
            dagOrError.left.get.asInstanceOf[SyncError.ValidationError].reason shouldBe e
          }
        }
      }
    }
    "returned new part of DAG can not be connected to our DAG" should {
      "return SyncError.MissingDependencies" in forAll(
        genPartialDagFromTips
      ) { dag =>
        log.reset()
        TestFixture(dag)(notInDag = _ => Task.now(true)) { (synchronizer, _) =>
          synchronizer.syncDag(Node(), Set(dag.head.blockHash)).foreachL { dagOrError =>
            dagOrError.isLeft shouldBe true
            dagOrError.left.get shouldBe an[SyncError.MissingDependencies]
          }
        }
      }
    }
    "streaming is halted with error" should {
      "log error and return empty DAG" in forAll(
        genPartialDagFromTips
      ) { dag =>
        log.reset()
        val e = new RuntimeException("Boom!")
        TestFixture(dag)(error = e.some) { (synchronizer, _) =>
          synchronizer.syncDag(Node(), Set(dag.head.blockHash)).attempt.foreachL { dagOrError =>
            dagOrError.isLeft shouldBe true
            dagOrError.left.get shouldBe e
            log.causes should have size 1
            log.causes.head shouldBe e
          }
        }
      }
    }

    "started multiple sync with the same node" should {
      "only run one sync at a time" in forAll(
        genPartialDagFromTips
      ) { dag =>
        log.reset()
        TestFixture(dag)() { (synchronizer, variables) =>
          for {
            fibers <- List.range(0, 5).traverse { _ =>
                       synchronizer
                         .syncDag(Node(), Set(dag.head.blockHash))
                         .map(_ => variables.requestsGauge.get())
                         .start
                     }
            readings <- fibers.traverse(_.join)
          } yield {
            readings.max should be <= 1
          }
        }
      }
    }

    "syncs repeatedy" when {
      "using the same source" should {
        "not traverse the DAG twice unless the download fails" in forAll(
          genPartialDagFromTips
        ) { dag =>
          // This was copied from the iterative test.
          log.reset()
          val ancestorsDepthRequest: Int = 2
          val grouped = {
            val headGroup   = Vector(Vector(dag.head))
            val generations = dag.tail.grouped(consensusConfig.dagWidth).toVector
            val groups      = headGroup ++ generations
            groups.grouped(ancestorsDepthRequest).toVector.map(_.flatten)
          }
          val finalParents = dag.takeRight(consensusConfig.dagWidth).map(_.blockHash).toSet
          // Initial request, second attempt with cache in place, third attempt after failure.
          TestFixture((grouped ++ grouped.take(1) ++ grouped): _*)(
            maxDepthAncestorsRequest = ancestorsDepthRequest,
            notInDag = bs => Task.now(!finalParents(bs))
          ) { (synchronizer, variables) =>
            val source = Node()
            for {
              r1 <- synchronizer.syncDag(source, Set(dag.head.blockHash))
              d1 = r1.fold(throw _, identity)

              // Simulate the DownloadManager telling the Synchronizer about the items being scheduled.
              _ <- d1.traverse { d =>
                    synchronizer.onScheduled(d, source)
                  }

              // Second time it should not go back as far as it did the first time.
              r2 <- synchronizer.syncDag(source, Set(dag.head.blockHash))
              d2 = r2.fold(throw _, identity)
              _  = d2.length shouldBe grouped(0).size
              // Shouldn't ask everything twice.
              _ = variables.requestsCounter.get should be < ((grouped.size.toDouble / ancestorsDepthRequest).ceil.toInt + 1) * 2

              // Simulate the DownloadManager telling the Synchronizer about the first items having failed.
              // Notifying about all of them just so that we can make sure that all of `d1` gets traversed again,
              // not just a part that depends on for example d1.head
              _ <- grouped(0).traverse(s => synchronizer.onFailed(s.blockHash))

              // Third time it should go back right to the failed block again.
              r3 <- synchronizer.syncDag(source, Set(dag.head.blockHash))
              d3 = r3.fold(throw _, identity)
              _  = d3.length shouldBe d1.length

            } yield {}
          }
        }
      }
    }

    "asked to sync DAG" should {
      "ignore branching factor checks until specified count-threshold is reached" in forAll(
        genPartialDagFromTips(
          ConsensusConfig(dagDepth = 3, dagBranchingFactor = 3, dagWidth = Int.MaxValue)
        ),
        genDoubleGreaterEqualOf0(2)
      ) { (dag, n) =>
        log.reset()
        TestFixture(dag)(maxBondingRate = n, minBlockCountToCheckWidth = Int.MaxValue) {
          (synchronizer, _) =>
            synchronizer.syncDag(Node(), Set(dag.head.blockHash)).foreachL { dagOrError =>
              dagOrError.isRight shouldBe true
              dagOrError.right.get should contain allElementsOf dag
            }
        }
      }

      "include justifications as known hashes" in forAll(
        genPartialDagFromTips,
        genHash
      ) { (dag, justification) =>
        log.reset()
        TestFixture(dag)(justifications = List(justification)) { (synchronizer, variables) =>
          synchronizer.syncDag(Node(), Set(dag.head.blockHash)).foreachL { _ =>
            variables.knownHashes should contain allElementsOf List(justification)
          }
        }
      }

      "iteratively traverse DAG" in forAll(
        genPartialDagFromTips
      ) { dag =>
        log.reset()
        val ancestorsDepthRequest: Int = 2
        val grouped = {
          val headGroup   = Vector(Vector(dag.head))
          val generations = dag.tail.grouped(consensusConfig.dagWidth).toVector
          val groups      = headGroup ++ generations
          groups.grouped(ancestorsDepthRequest).toVector.map(_.flatten)
        }
        val finalParents = dag.takeRight(consensusConfig.dagWidth).map(_.blockHash).toSet
        TestFixture(grouped: _*)(
          maxDepthAncestorsRequest = ancestorsDepthRequest,
          notInDag = bs => Task.now(!finalParents(bs))
        ) { (synchronizer, variables) =>
          synchronizer.syncDag(Node(), Set(dag.head.blockHash)).foreachL { dagOrError =>
            dagOrError.isRight shouldBe true
            val d = dagOrError.right.get
            d should contain allElementsOf dag.dropRight(consensusConfig.dagWidth)
            variables.requestsCounter
              .get() shouldBe (grouped.size.toDouble / ancestorsDepthRequest).ceil.toInt + 1
          }
        }
      }

      "return DAG in topological order" in forAll(genPartialDagFromTips) { dag =>
        log.reset()
        val hashToSummary = dag.map(s => (s.blockHash, s)).toMap.lift

        def allAncestors(summary: BlockSummary): List[BlockSummary] = {
          def directAncestors(s: BlockSummary): List[BlockSummary] =
            s.parentHashes
              .flatMap(p => hashToSummary(p).toList)
              .toList ::: s.justifications
              .flatMap(j => hashToSummary(j.latestBlockHash))
              .toList

          @annotation.tailrec
          def loop(acc: List[BlockSummary], summaries: List[BlockSummary]): List[BlockSummary] =
            if (summaries.isEmpty) {
              acc
            } else {
              val ancestors = summaries.flatMap(directAncestors).distinct
              loop(acc ::: ancestors, ancestors)
            }
          loop(Nil, List(summary)).distinct
        }

        TestFixture(dag)() { (synchronizer, _) =>
          synchronizer.syncDag(Node(), Set(dag.head.blockHash)).foreachL { dagOrError =>
            dagOrError.isRight shouldBe true
            val d = dagOrError.right.get
            val hashToIndex =
              d.zipWithIndex.map { case (s, i) => (s.blockHash, i) }.toMap.withDefaultValue(-1)
            d should not be empty
            Inspectors.forAll(d.zipWithIndex) {
              case (summary, index) =>
                val ancestors = allAncestors(summary)
                ancestors.forall(a => hashToIndex(a.blockHash) < index) shouldBe true
            }
          }
        }
      }
    }
  }
}

object SynchronizerSpec {
  implicit var log     = LogStub[Task]()
  implicit val metrics = new Metrics.MetricsNOP[Task]

  object MockBackend {
    def apply(
        mockJustifications: List[ByteString],
        mockNotInDag: ByteString => Task[Boolean],
        mockValidate: BlockSummary => Task[Unit]
    ): SynchronizerImpl.Backend[Task] =
      new SynchronizerImpl.Backend[Task] {
        def justifications: Task[List[ByteString]]           = Task.now(mockJustifications)
        def validate(blockSummary: BlockSummary): Task[Unit] = mockValidate(blockSummary)
        def notInDag(blockHash: ByteString): Task[Boolean]   = mockNotInDag(blockHash)
      }
  }

  object MockGossipService {
    def apply(
        requestsCounter: AtomicInt,
        requestsGauge: AtomicInt,
        error: Option[RuntimeException],
        knownHashes: ListBuffer[ByteString],
        // Each request will return the next DAG.
        dags: Vector[BlockSummary]*
    ): Task[GossipService[Task]] =
      Task.now {
        new NoOpsGossipService[Task] {
          override def streamAncestorBlockSummaries(
              request: StreamAncestorBlockSummariesRequest
          ) = {
            request.knownBlockHashes.foreach(h => knownHashes += h)
            Iterant
              .resource {
                Task.delay(requestsGauge.increment())
              } { _ =>
                Task.delay(requestsGauge.decrement())
              }
              .flatMap { _ =>
                error.fold(
                  Iterant.fromSeq[Task, BlockSummary] {
                    dags.lift(requestsCounter.getAndIncrement()).getOrElse(Vector.empty)
                  }
                ) { e =>
                  Iterant.raiseError(e)
                }
              }
          }
        }
      }
  }

  case class TestVariables(
      requestsCounter: AtomicInt,
      requestsGauge: AtomicInt,
      knownHashes: ListBuffer[ByteString]
  )

  object TestFixture {

    /** The `dags` contain successive responses to requests. */
    def apply(dags: Vector[BlockSummary]*)(
        maxPossibleDepth: Int = Int.MaxValue,
        maxBondingRate: Double = 1.0,
        maxDepthAncestorsRequest: Int = Int.MaxValue,
        minBlockCountToCheckWidth: Int = Int.MaxValue,
        validate: BlockSummary => Task[Unit] = _ => Task.unit,
        notInDag: ByteString => Task[Boolean] = _ => Task.now(false),
        error: Option[RuntimeException] = None,
        justifications: List[ByteString] = Nil
    )(test: (Synchronizer[Task], TestVariables) => Task[Unit]): Unit = {
      val requestsCounter = AtomicInt(0)
      val requestsGauge   = AtomicInt(0)
      val knownHashes     = ListBuffer.empty[ByteString]

      SynchronizerImpl[Task](
        connectToGossip =
          _ => MockGossipService(requestsCounter, requestsGauge, error, knownHashes, dags: _*),
        backend = MockBackend(justifications, notInDag, validate),
        maxPossibleDepth = maxPossibleDepth,
        minBlockCountToCheckWidth = minBlockCountToCheckWidth,
        maxBondingRate = maxBondingRate,
        maxDepthAncestorsRequest = maxDepthAncestorsRequest,
        disableValidations = false,
        maxParallel = Int.MaxValue
      ).flatMap { synchronizer =>
          test(synchronizer, TestVariables(requestsCounter, requestsGauge, knownHashes))
        }
        .runSyncUnsafe(5.seconds)
    }
  }
}
