package com.timetrade.queueservice.server.core.activation

import java.io.EOFException

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

import akka.actor.ActorRef

import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.joda.time.Instant

import org.scalacheck.Arbitrary.arbitrary

import org.scalactic.TypeCheckedTripleEquals

import org.scalatest.Matchers
import org.scalatest.concurrent.Futures
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.fixture.FunSpec
import org.scalatest.time.Millis
import org.scalatest.time.Seconds
import org.scalatest.time.Span

import com.timetrade.queueservice.server.core.ActorNaming
import com.timetrade.queueservice.server.core.Core
import com.timetrade.queueservice.server.core.CoreSettings
import com.timetrade.queueservice.server.core.definitioncrud.QueueDefinition
import com.timetrade.queueservice.server.core.definitioncrud.QueueDefinitionGeneration
import com.timetrade.queueservice.server.core.definitioncrud.QueueId
import com.timetrade.queueservice.server.core.publishing.ConsumableQueueStateUsingActor
import com.timetrade.queueservice.server.testtraits.ConfigurableActorSystems
import com.timetrade.queueservice.testtraits.TimedSuite

class DailyReactivationSpec
  extends FunSpec
  with Matchers
  with Futures
  with ScalaFutures
  with TypeCheckedTripleEquals
  with ConfigurableActorSystems
  with TimedSuite
  with QueueDefinitionGeneration {

  object MockFetcher extends QueueStateFetcher {
    def fetch(definition: QueueDefinition, dt: DateTime = DateTime.now)
      : Future[QueueFetchedState]
      = { Future.successful(QueueFetchedState(QueueInitialContents(Seq()), dt, DateTimeZone.UTC)) }
  }

  // Configure the behavior of the "whenReady" calls below.
  implicit val defaultPatience = PatienceConfig(timeout =  Span(30, Seconds),
                                                interval = Span(1, Millis))
  def await[T](f: Future[T]): T = whenReady(f) { t => t }

  type FixtureParam = Core

  def withFixture(test: OneArgTest) = {

    implicit val system = actorSystemWithClustering()

    val coreSettings = CoreSettings.defaultForTesting
                            // Set a long idle timeout so a deactivation
                            // due to idleness won't trip up our test.
                            .copy(queueIdleTimeout = 120.seconds)
    val core = new Core(settings = coreSettings)
    try {
      QueueStateFetcher.mockWith(MockFetcher)
      await(core.becomeReady())
      test(core)
    } finally {
      QueueStateFetcher.unMock()
      LocationActor.setReactivationDelayForTesting(None)
      core.shutdown()
      system.shutdown()
    }
  }

  describe ("The LocationActor for a queue") {

    // Read from a PublishingActor.
    def readFrom(publisher: ActorRef, version: Option[Instant] = None)
                (implicit core: Core)
    = new ConsumableQueueStateUsingActor(publisher, 30.seconds)(core.actorSystem)
        .read(version)

    it ("should re-activate the queue at the appropriate time") { implicit core =>

      // Create a queue definition.
      val id = arbitrary[QueueId].sample.get
      val definition = arbitrary[QueueDefinition].sample.get

      // Before we activate it, set an artificially close re-activation time.
      val reactivationDelay = 30.seconds
      LocationActor.setReactivationDelayForTesting(Some(reactivationDelay))

      val firstActivation = whenReady(core.activate(id, definition)) { activeQueue =>

        // Verify that the actor's path looks right.
        activeQueue.actor.path.toString should endWith (ActorNaming.actorUriPath(definition.location, id))

        // After initial activation: verify that the active queues table looks right.
        whenReady(core.getActiveQueues) { activeQueues =>
          activeQueues should have size (1)
          activeQueues(id)
        }
      }
      firstActivation.definition should === (definition)

      // Verify that reading the published state of the queue succeeds.
      whenReady(readFrom(firstActivation.publisher)) { case (contents, version) =>
        contents.contents.size should === (0)
      }

      // Wait long enough for the reactivation to have taken place.
      Thread.sleep(reactivationDelay.toMillis + 3000)

      // Verify that the set of active queues is as expected.
      val secondActivation = whenReady(core.getActiveQueues) { activeQueues =>
        activeQueues should have size (1)
        activeQueues(id)
      }

      secondActivation.definition should === (definition)

      // Verify that the queue received a new actor upon re-activation.
      secondActivation.actor should !== (firstActivation.actor)


      // Verify that reading the state from the old activation incurs the expected error.
      intercept[EOFException] {
        Await.result(readFrom(firstActivation.publisher), 1.second)
      }

      // Verify that reading the published state of the queue succeeds.
      whenReady(readFrom(secondActivation.publisher)) { case (contents, version) =>
        contents.contents.size should === (0)
      }
    }
  }
}
