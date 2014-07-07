/** Copyright(c) 2014 by TimeTrade Systems.  All Rights Reserved. */
package com.timetrade.queueservice.server.web

import scala.concurrent.Future
import scala.concurrent.duration._

import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.event.Logging
import akka.event.LoggingAdapter
import akka.testkit.ImplicitSender
import akka.testkit.TestKit
import akka.testkit.TestProbe

import spray.http.ChunkedResponseStart
import spray.http.{DateTime => SprayDateTime}
import spray.http.HttpHeaders.Accept
import spray.http.HttpHeaders._
import spray.http.HttpMethods
import spray.http.HttpRequest
import spray.http.MediaRange.apply
import spray.http.MessageChunk
import spray.http.StatusCodes
import spray.http.Uri
import spray.http.Uri.apply
import spray.json._
import spray.json.pimpString

import org.joda.time.DateTime
import org.joda.time.Instant

import org.scalatest.Matchers
import org.scalatest.concurrent.Futures
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.fixture.FunSpecLike
import org.scalatest.time.Millis
import org.scalatest.time.Seconds
import org.scalatest.time.Span

import com.timetrade.queueservice.jsonutils.InstantJsonProtocol.InstantJsonFormat
import com.timetrade.queueservice.netutils.HttpClient
import com.timetrade.queueservice.server.api.feeds.CustomMediaTypes._
import com.timetrade.queueservice.server.api.feeds.Data
import com.timetrade.queueservice.server.api.feeds.QueueJSONAppointment
import com.timetrade.queueservice.server.api.feeds.QueueJSONAppointment.format
import com.timetrade.queueservice.server.api.feeds.TimeoutRetryMsecs
import com.timetrade.queueservice.server.api.queues.QueuesApiSpec
import com.timetrade.queueservice.server.api.queues.entities.NewFeedView
import com.timetrade.queueservice.server.api.queues.entities.NewQueue
import com.timetrade.queueservice.server.api.queues.entities.NewQueueView
import com.timetrade.queueservice.server.core.CoreSettings
import com.timetrade.queueservice.server.core.data.Licensee
import com.timetrade.queueservice.server.core.data.appointment.Appointment
import com.timetrade.queueservice.server.core.data.appointment.AppointmentGeneration
import com.timetrade.queueservice.server.core.definitioncrud.QueueDefinition
import com.timetrade.queueservice.server.core.definitioncrud.QueueDefinitionGeneration
import com.timetrade.queueservice.server.testtraits.ConfigurableActorSystems
import com.timetrade.queueservice.server.testutils.Utils
import com.timetrade.queueservice.server.web.mockedtesa.MockedTesa
import com.timetrade.queueservice.testtraits.TimedSuite
import com.timetrade.queueservice.server.testtags.CrashesJenkins

case class ErrorChunk(status: Int, message: Option[String])

object ErrorChunk extends DefaultJsonProtocol {
  implicit lazy val _ = jsonFormat2(apply)
}

case class ClosedChunk(message: Option[String])

object ClosedChunk extends DefaultJsonProtocol {
  implicit lazy val _ = jsonFormat1(apply)
}

/**
  * Functional tests for the creation of push-based feeds, which are known internally to the queue
  * server as "subscriptions".  Simulates a client that creates these feeds using the Accept type
  * "text/event-source" on the feed creation request.  Pumps appointments into the queue of the
  * feed, and validates that the queue's contents are pushed to the client, via an HTML EventSource
  * (aka Server-Sent Events, SSEs) chunked response.
  */
class FeedSubscriptionSpec(_system: ActorSystem)
  extends TestKit(_system)
  with ImplicitSender
  with FunSpecLike
  with Matchers
  with Futures
  with ScalaFutures
  with HttpResponseChecking
  with TimedSuite
  with ConfigurableActorSystems
  with QueueDefinitionGeneration
  with DefaultJsonProtocol
  with AppointmentGeneration {

  def this() = this(ConfigurableActorSystems.defaultActorSystem("FeedSubscriptionSpec"))

  // Configure the behavior of the "whenReady" calls below.
  implicit val defaultPatience = PatienceConfig(timeout = Span(30, Seconds), interval = Span(1, Millis))

  def await[T](f: Future[T]): T = whenReady(f) { t => t }

  type FixtureParam = (Web, HttpClient, MockedTesa, ActorSystem, LoggingAdapter)

  override def withFixture(test: OneArgTest) = {

    // Create separate actor systems for better approximation to reality.
    val queueServerActorSystem = defaultActorSystem("QueueServerWeb")
//    val httpClientActorSystem = defaultActorSystem("HttpClient")
    val httpClientActorSystem = _system

    lazy val log = Logging.getLogger(httpClientActorSystem, this)

    // Create a Queue Server Web instance and a client for it.
    val (_, port) = Utils.temporaryServerHostnameAndPort()
    val web = Web(
      coreSettings = CoreSettings.defaultForTesting
        // Set a value smaller than defaultPatience above
        .copy(clientTimeout = 10.seconds),
      port = port,
      feedUrlBase = Uri(s"http://localhost:${port}"))(queueServerActorSystem)

    val qsClient = new HttpClient(web.hostName, web.restApiPort)(httpClientActorSystem)

    // Create a MockedTesa bound to that Queue Server.
    val mockedTesa = new MockedTesa(web.restUri)
    try {
      await(web.api.core.becomeReady())
      await(web.startHttp())
      test((web, qsClient, mockedTesa, httpClientActorSystem, log))
    } finally {
      mockedTesa.shutdown()
      qsClient.shutdown()
      web.shutdown()
//      httpClientActorSystem.shutdown()
//      httpClientActorSystem.awaitTermination()
      queueServerActorSystem.shutdown()
      queueServerActorSystem.awaitTermination()
    }
  }

  val dataPrefix = s"${Data}:"
  val closedEventLine = "event:closed\n"

  describe("Push-based queue feeds") {

    // Create resource paths.
    def queuesResourceFromLicensee(licensee: Licensee): String = s"/${licensee.tesaInstance.s}/${licensee.externalId.s}/queues"

    def queuesResource(queueDef: QueueDefinition): String = queuesResourceFromLicensee(queueDef.location.licensee)

    /**
      * Create a new queue definition in the queue server.
      * @param definition the QueueDefinition.
      * @param client an HttpClient bound to the queue server.
      * @return relative Uri for the newly created queue definition resource.
      */
    def createQueueDefinition(definition: QueueDefinition)(implicit client: HttpClient): Uri = {
      HttpRequest(
        HttpMethods.POST,
        queuesResource(definition),
        entity = QueuesApiSpec.entityFor(NewQueue(definition))) ~> client ~> checkWhenReady {

          status should ===(StatusCodes.Created)

          // Get the link to the new queue.
          val nqv = body.asString.parseJson.convertTo[NewQueueView]
          nqv.newQueue.link
        }
    }

    // Create an If-Modified-Since header with the specified time.
    def ifModifiedSinceHeader(instant: Instant) =
      `If-Modified-Since`(SprayDateTime(instant.getMillis))

    def acceptEventStreamHeader = `Accept`(`text/event-stream`)

    /**
      * Create a new feed for a queue, which asks for an event stream for the feed, rather than
      * long-polled events.
      *
      * @param queueDefUri the Uri for the queue definition
      * @param client an HttpClient bound to the queue server.
      * @return relative Uri for the newly created feed resources
      */
    def createFeed(queueDefUri: Uri)(implicit client: HttpClient): Uri = {
      HttpRequest(
        HttpMethods.POST,
        uri = queueDefUri + "/feeds") ~> client ~> checkWhenReady {

          status should ===(StatusCodes.Created)

          // Get the link to the new feed.
          val nfv = body.asString.parseJson.convertTo[NewFeedView]
          Uri(nfv.newFeed.link.path.toString)
        }
    }

    /**
      * Reed the feed for a queue.
      *
      * @param receiver the actor which should receive asynchronous, chunked responses
      * @param feedUri the Uri for the feed resource
      * @param ifModifiedSinceTime optional value to be used for If-Modified-Since header
      * @param client an HttpClient bound to the queue server.
      */
    def readFeed(receiver: ActorRef, feedUri: Uri, time: Option[Instant] = None)(implicit client: HttpClient, log: LoggingAdapter) = {
      val req = HttpRequest(
        HttpMethods.GET,
        headers = time.toList.map { ifModifiedSinceHeader(_) } ::: List(acceptEventStreamHeader),
        uri = feedUri)

      // TODO: Is there a way to check the response status before we even get any chunks??
      log.debug("Making aysnchronous request {}", req)
      client.sendAndHandleChunkedResponseWith(req, receiver)
    }

    def killQueue(queueDefUri: Uri)(implicit client: HttpClient, log: LoggingAdapter) = {
      val killUri = Uri("/admin/kill" + queueDefUri.path)
      HttpRequest(
        HttpMethods.POST,
        uri = killUri) ~> client ~> checkWhenReady {

          status should ===(StatusCodes.Accepted)
        }
    }

    // TODO: Why does this test frequently fail with OutOfMemoryError in Jenkins, but not locally??
    // For now, disable, so we can get clean, installable Jenkins builds again...
    it ("should return the queue's initial state on the first read", CrashesJenkins) {
      case (web, client, tesa, actorSystem, logger) =>
        implicit val _ = client
        implicit val system = actorSystem
        implicit val log = logger

        // Truncate to seconds because poll timestamps precision is 1 second.
        val beginningOfTest = DateTime.now.withMillisOfSecond(0)

        // Generate a QueueDefinition at a certain TesaInstance.
        val tesaInstance = genTesaInstance.sample.get
        val definition = genQueueDefinition(tesaInstance, tesa.tesaRestUri(tesaInstance)).sample.get

        // Generate some appointments that will match the queue and add them to TESA.
        val nextHour = beginningOfTest.withMinuteOfHour(0).plusHours(2)
        val count = 10
        val originalAppointments =
          tesa.addAppointmentsFor(definition, nextHour.toInstant, 5.minutes, count)

        // Create an actor to receive chunked responses from the feed.
        val probe = TestProbe()

        // Create the queue definition, a new feed, then read the feed.
        readFeed(probe.ref,
          createFeed(
            createQueueDefinition(definition)))

        validateQueueFeedStart(probe, originalAppointments)

        // Should not see the end of the chunks yet.
        probe.expectNoMsg(1.seconds)
    }

    // TODO: Why does this test frequently fail with OutOfMemoryError in Jenkins, but not locally??
    // For now, disable, so we can get clean, installable Jenkins builds again...
    it ("should push changes to the queue's state", CrashesJenkins) {
      case (web, client, tesa, actorSystem, logger) =>

        implicit val _ = client
        implicit val system = actorSystem
        implicit val log = logger

        // Truncate to seconds because poll timestamps precision is 1 second.
        val beginningOfTest = DateTime.now.withMillisOfSecond(0)

        // Generate a QueueDefinition at a certain TesaInstance.
        val tesaInstance = genTesaInstance.sample.get
        val definition = genQueueDefinition(tesaInstance, tesa.tesaRestUri(tesaInstance)).sample.get

        // Generate some appointments that will match the queue and add them to TESA.
        val nextHour = beginningOfTest.withMinuteOfHour(0).plusHours(2)
        val count = 10
        val originalAppointments =
          tesa.addAppointmentsFor(definition, nextHour.toInstant, 5.minutes, count)

        // Create an actor to receive chunked responses from the feed.
        val probe = TestProbe()

        // Create the queue definition, a new feed, then read the feed.
        readFeed(probe.ref,
          createFeed(
            createQueueDefinition(definition)))

        validateQueueFeedStart(probe, originalAppointments)

        // Add another appointment.
        val newAppointments =
          tesa.addAppointmentsFor(definition, nextHour.plusHours(3).toInstant, 10.minutes, 1, true)

        // Validate that the feed saw it.  (The feed should give us all of the original, plus this new appointment.)
        val rawBody = probe expectMsgClass(classOf[MessageChunk])
        val rawJson = unwrapFromSse(rawBody.data.asString)
        val newFeedAppointments = rawJson.parseJson.convertTo[Seq[QueueJSONAppointment]]

        val currentAppointments = originalAppointments ++ newAppointments
        newFeedAppointments.size should be(currentAppointments.size)
        newFeedAppointments.map {_.id} should ===(currentAppointments.map { _.externalId.toString })
    }

    // TODO: Why does this test frequently fail with OutOfMemoryError in Jenkins, but not locally??
    // For now, disable, so we can get clean, installable Jenkins builds again...
    it("should see the feed be explicitly closed if the queue is restarted", CrashesJenkins) {
      case (web, client, tesa, actorSystem, logger) =>

        implicit val _ = client
        implicit val system = actorSystem
        implicit val log = logger

        // Truncate to seconds because poll timestamps precision is 1 second.
        val beginningOfTest = DateTime.now.withMillisOfSecond(0)

        // Generate a QueueDefinition at a certain TesaInstance.
        val tesaInstance = genTesaInstance.sample.get
        val definition = genQueueDefinition(tesaInstance, tesa.tesaRestUri(tesaInstance)).sample.get

        // Generate some appointments that will match the queue and add them to TESA.
        val nextHour = beginningOfTest.withMinuteOfHour(0).plusHours(2)
        val count = 10
        val originalAppointments =
          tesa.addAppointmentsFor(definition, nextHour.toInstant, 5.minutes, count)

        // Create an actor to receive chunked responses from the feed.
        val probe = TestProbe()

        // Create the queue definition, a new feed, then read the feed.
        val qUri = createQueueDefinition(definition)
        readFeed(probe.ref,
          createFeed(
            qUri))

        validateQueueFeedStart(probe, originalAppointments)

        // Restart the queue, which should stop the feed.
        killQueue(qUri)

        val chunk = probe.expectMsgClass(classOf[MessageChunk])
        val rawEvent = unwrapFromSse(chunk.data.asString)

        // The first line should be an indication that this is a close event.
        rawEvent.startsWith(closedEventLine) should be(true)
        val rawJson = rawEvent.stripPrefix(closedEventLine)

        val error = rawJson.parseJson.convertTo[ClosedChunk]
        error should ===(ClosedChunk(Some("The server has closed this feed")))
    }

    def validateQueueFeedStart(probe: TestProbe, expectedAppointments: Seq[Appointment]): Unit = {
       // We expect a start to the streamed response.
        val start: ChunkedResponseStart = probe expectMsgClass (classOf[ChunkedResponseStart])
        start.response.status should be(StatusCodes.OK)

        // The first chunk should contain an SSE retry command.  (A browser is expected to consume this.)
        val initial = probe expectMsgClass(classOf[MessageChunk])
        initial.data.asString should be(s"${TimeoutRetryMsecs}: 10000\n")

        // The next chunk should contain the queue's initial state.
        val rawBody = probe expectMsgClass(classOf[MessageChunk])
        val rawJson = unwrapFromSse(rawBody.data.asString)
        val appointments = rawJson.parseJson.convertTo[Seq[QueueJSONAppointment]]

        // Verify length of queue.
        appointments.size should ===(expectedAppointments.size)

        // Verify that the confirmation numbers and their order are preserved in the feed.
        appointments.map {_.id} should ===(expectedAppointments.map { _.externalId.toString })
   }

    def unwrapFromSse(sse: String): String = {
      sse.split("\n").map { s => s.stripPrefix(dataPrefix) }.mkString("\n")
    }

    def stripSsePrefix(s: String): String = {
      if (s.startsWith(dataPrefix)) s.substring(dataPrefix.length, s.length) else s
    }
  }
}
