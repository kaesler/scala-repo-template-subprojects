/** Copyright(c) 2014 by TimeTrade Systems.  All Rights Reserved. */
package com.timetrade.queueservice.server.web

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

import akka.actor.ActorSystem
import akka.actor.Props
import akka.actor.actorRef2Scala
import akka.event.Logging
import akka.io.Tcp
import akka.testkit.ImplicitSender
import akka.testkit.TestKit
import akka.testkit.TestProbe

import spray.http.ChunkedMessageEnd
import spray.http.MessageChunk

import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.scalacheck.Arbitrary.arbitrary
import org.scalatest.BeforeAndAfterAll
import org.scalatest.Matchers
import org.scalatest.WordSpecLike
import org.scalatest.concurrent.Futures
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.Millis
import org.scalatest.time.Seconds
import org.scalatest.time.Span

import com.timetrade.queueservice.server.api.feeds.Closed
import com.timetrade.queueservice.server.api.feeds.EventKind
import com.timetrade.queueservice.server.api.feeds.FeedError
import com.timetrade.queueservice.server.api.feeds.QueueSubscriptionActor
import com.timetrade.queueservice.server.core.Core
import com.timetrade.queueservice.server.core.CoreSettings
import com.timetrade.queueservice.server.core.activation.QueueFetchedState
import com.timetrade.queueservice.server.core.activation.QueueInitialContents
import com.timetrade.queueservice.server.core.activation.QueueStateFetcher
import com.timetrade.queueservice.server.core.data.QueuePublishedState
import com.timetrade.queueservice.server.core.data.appointment.MockTesa
import com.timetrade.queueservice.server.core.definitioncrud.QueueDefinition
import com.timetrade.queueservice.server.core.definitioncrud.QueueDefinitionGeneration
import com.timetrade.queueservice.server.core.definitioncrud.QueueId
import com.timetrade.queueservice.server.testtraits.ConfigurableActorSystems
import com.timetrade.queueservice.testtraits.TimedSuite

/**
  * Unit tests for the implementation of the subscriber side of queue feeds using push connections,
  * such as HTML5 Server-Sent Events.
  */
class FeedSubscriptionActorSpec(_system: ActorSystem)
  extends TestKit(_system)
  with ImplicitSender
  with WordSpecLike
  with Matchers
  with BeforeAndAfterAll
  with Futures
  with ScalaFutures
  with TimedSuite
  with QueueDefinitionGeneration {

  def this() = this(ConfigurableActorSystems.defaultActorSystem("FeedSubscriptionActorSpec"))

  val log = Logging.getLogger(system, this)

  implicit val core = new Core(settings = CoreSettings.defaultForTesting)

  implicit lazy val ec = core.actorSystem.dispatcher

  // Configure the behavior of the "whenReady" calls below.
  implicit val defaultPatience = PatienceConfig(timeout =  Span(30, Seconds),
                                                interval = Span(1, Millis))
  def await[T](f: Future[T]): T = whenReady(f) { t => t }

  object MockFetcher extends QueueStateFetcher {
    def fetch(definition: QueueDefinition, dt: DateTime = DateTime.now)
      : Future[QueueFetchedState]
      = { Future.successful(QueueFetchedState(QueueInitialContents(Seq()), dt, DateTimeZone.UTC)) }
  }

  override def beforeAll {
    QueueStateFetcher.mockWith(MockFetcher)
    await(core.becomeReady())
  }

  override def afterAll {
    QueueStateFetcher.unMock()
    core.shutdown()
    TestKit.shutdownActorSystem(system)
  }

  "FeedSubscriptionActor" must {

    "respond to updated queue state" in {
      val id = arbitrary[QueueId].sample.get

      // Grab some queue contents to send the subscriber.
      val appointments = MockTesa().appointments.gimmeeMultiples()
      val contents = QueuePublishedState(contents = appointments, tz = DateTimeZone.UTC)

      // Create the feeds actor.
      val probe = TestProbe()
      val subscriber = core.actorSystem.actorOf {
        Props {
          new QueueSubscriptionActor(probe.ref, id, None)
        }
      }
      subscriber ! contents

      // Verify that each appointment sent to the subscriber comes back in the feed data.
      val rawChunk = probe.expectMsgClass(classOf[MessageChunk])
      val jsonString = rawChunk.data.asString
      appointments foreach { appt =>
        val id = appt.externalId.s
        jsonString should include(id)
      }
    }

    "gracefully respond to the client closing their end of the (feed) connection" in {
      val id = arbitrary[QueueId].sample.get

      // Create the feeds actor.
      val probe = TestProbe()
      val subscriber = core.actorSystem.actorOf {
        Props {
          new QueueSubscriptionActor(probe.ref, id, None)
        }
      }

      // Tcp.Closed should be what Http.ConnectionClosed is defined to be.  The latter is what the
      // feeds actor is expecting to receive and handle.
      subscriber ! Tcp.Closed

      // We expect no further chunks, because the feeds actor thinks the client connection is gone.
      probe.expectNoMsg(1.seconds)
    }

    "gracefully respond to the queue closing all subscriptions" in {
      val id = arbitrary[QueueId].sample.get

      // Create the feeds actor.
      val probe = TestProbe()
      val subscriber = core.actorSystem.actorOf {
        Props {
          new QueueSubscriptionActor(probe.ref, id, None)
        }
      }

      // This is what the queue publisher sends to the feeds actor when the publisher is closed.
      subscriber ! QueueSubscriptionActor.Protocol.Closed

      val errChunk = probe.expectMsgClass(classOf[MessageChunk])
      val jsonString = errChunk.data.asString
      jsonString should startWith(s"${EventKind}:${Closed}")
      jsonString should include("\"message\": \"The server has closed this feed\"")

      probe.expectMsg(ChunkedMessageEnd)
    }

    "gracefully respond to an unknown message" in {
      val id = arbitrary[QueueId].sample.get

      // Create the feeds actor.
      val probe = TestProbe()
      val subscriber = core.actorSystem.actorOf {
        Props {
          new QueueSubscriptionActor(probe.ref, id, None)
        }
      }

      subscriber ! "unhandled"

      val errChunk = probe.expectMsgClass(classOf[MessageChunk])
      val jsonString = errChunk.data.asString
      jsonString should startWith(s"${EventKind}:${FeedError}")
      jsonString should include("\"status\": 500")

      probe.expectMsg(ChunkedMessageEnd)
    }
  }
}
