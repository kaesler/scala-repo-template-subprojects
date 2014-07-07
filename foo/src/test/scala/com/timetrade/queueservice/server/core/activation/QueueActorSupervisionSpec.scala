package com.timetrade.queueservice.server.core.activation

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

import akka.actor.ActorRef
import akka.actor.actorRef2Scala
import akka.pattern.ask
import akka.util.Timeout.durationToTimeout

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

class QueueActorSupervisionSpec
  extends FunSpec
  with Matchers
  with Futures
  with ScalaFutures
  with TypeCheckedTripleEquals
  with TimedSuite
  with ConfigurableActorSystems
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

    implicit val system = defaultActorSystem()

    val core = new Core(settings = CoreSettings.defaultForTesting)
    try {
      await(core.becomeReady())
      QueueStateFetcher.mockWith(MockFetcher)
      test(core)
    } finally {
      QueueStateFetcher.unMock()
      core.shutdown()
      system.shutdown()
    }
  }

  describe ("A queue actor") {

    // Read from a PublishingActor.
    def readFrom(publisher: ActorRef, version: Option[Instant] = None)
                (implicit core: Core)
    = new ConsumableQueueStateUsingActor(publisher, 30.seconds)(core.actorSystem)
        .read(version)

    it ("should be restarted after an error exception") { implicit core =>

      // activate a queue
      // send a special msg to its actor that triggers an error
      // verify that the actor ref continues to respond
      val id = arbitrary[QueueId].sample.get
      val definition = arbitrary[QueueDefinition].sample.get
      whenReady(core.activate(id, definition)) { activeQueue =>

        // Verify that the actor's path looks right.
        activeQueue.actor.path.toString should endWith (ActorNaming.actorUriPath(definition.location, id))

        // Verify that the active queues table looks right.
        val activeQueues = await(core.getActiveQueues)
        activeQueues should have size (1)
        activeQueues.head._1 should === (id)
        activeQueues.head._2.definition should === (definition)

        whenReady(readFrom(activeQueues(id).publisher)) { case (publishedState, v) =>
          publishedState.contents should be ('empty)
        }

        // Now inject error
        val actorBeforeError = activeQueues(id).actor
        actorBeforeError ! QueueActor.Protocol.RaiseErrorForTesting(new NullPointerException())

        // Ping the actor to verify it is restarted and is still usable via the same ActorRef.
        await((actorBeforeError ? QueueActor.Protocol.PingForTesting)(10.seconds))

        // Verify the same actorRef is being used.
        await(core.getActiveQueues)(id).actor should === (actorBeforeError)

        // Verify we can still use the same published state location.≈ß
        whenReady(readFrom(activeQueues(id).publisher)) { case (publishedState, v) =>
          publishedState.contents should be ('empty)
        }
      }
    }
  }
}
