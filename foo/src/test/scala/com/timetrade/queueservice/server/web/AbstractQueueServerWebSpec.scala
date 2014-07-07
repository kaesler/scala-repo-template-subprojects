package com.timetrade.queueservice.server.web

import scala.concurrent.Future
import scala.concurrent.duration._

import spray.http.{DateTime => SprayDateTime}
import spray.http.HttpHeaders._
import spray.http.HttpMethods
import spray.http.HttpRequest
import spray.http.StatusCode
import spray.http.StatusCodes
import spray.http.Uri
import spray.http.Uri.apply
import spray.json._

import org.joda.time.DateTime
import org.joda.time.Instant

import org.scalactic.TypeCheckedTripleEquals

import org.scalatest.Matchers
import org.scalatest.concurrent.Futures
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.fixture.FunSpec
import org.scalatest.time.Millis
import org.scalatest.time.Seconds
import org.scalatest.time.Span

import com.timetrade.queueservice.jsonutils.InstantJsonProtocol.InstantJsonFormat
import com.timetrade.queueservice.netutils.HttpClient
import com.timetrade.queueservice.server.api.feeds.QueueJSONAppointment
import com.timetrade.queueservice.server.api.queues.QueuesApiSpec
import com.timetrade.queueservice.server.api.queues.entities.NewFeedView
import com.timetrade.queueservice.server.api.queues.entities.NewQueue
import com.timetrade.queueservice.server.api.queues.entities.NewQueueView
import com.timetrade.queueservice.server.core.data.Licensee
import com.timetrade.queueservice.server.core.data.appointment.Appointment
import com.timetrade.queueservice.server.core.data.appointment.AppointmentGeneration
import com.timetrade.queueservice.server.core.definitioncrud.QueueDefinition
import com.timetrade.queueservice.server.core.definitioncrud.QueueDefinitionGeneration
import com.timetrade.queueservice.server.testtraits.ConfigurableActorSystems
import com.timetrade.queueservice.server.web.mockedtesa.MockedTesa
import com.timetrade.queueservice.testtraits.TimedSuite

/** Tests that exercise the whole of the queue server using:
  *  - its REST APis
  *  - a MockedTesa
  */
abstract class AbstractQueueServerWebSpec
  extends FunSpec
  with Matchers
  with Futures
  with ScalaFutures
  with TypeCheckedTripleEquals
  with ConfigurableActorSystems
  with TimedSuite
  with HttpResponseChecking
  with QueueDefinitionGeneration
  with AppointmentGeneration {

  // Configure the behavior of the "whenReady" calls below.
  implicit val defaultPatience = PatienceConfig(timeout =  Span(40, Seconds),
                                                interval = Span(1, Millis))
  def await[T](f: Future[T]): T = whenReady(f) { t => t }

  type FixtureParam = (HttpClient, MockedTesa)

  // Subsclasses need to define one of these:
  // def withFixture(test: OneArgTest)

  // Create resource paths.
  def queuesResource(licensee: Licensee):String =
    s"/${licensee.tesaInstance.s}/${licensee.externalId.s}/queues"
  def queuesResource(queueDef: QueueDefinition): String = queuesResource(queueDef.location.licensee)

  describe ("The queue server") {

    /** Create a new queue definition in the queue server.
      * @param definition the QueueDefinition.
      * @param client an HttpClient bound to the queue server.
      * @return relative Uri for the newly created queue definition resource.
      */
    def createQueueDefinition(definition: QueueDefinition)(implicit client: HttpClient): Uri =
      HttpRequest(
        HttpMethods.POST,
        queuesResource(definition),
        entity = QueuesApiSpec.entityFor(NewQueue(definition))) ~> client ~> checkWhenReady {

        status should === (StatusCodes.Created)

        // Get the link to the new queue.
        val nqv = body.asString.parseJson.convertTo[NewQueueView]
        nqv.newQueue.link
      }

    // Create an If-Modified-Since header with the specified time.
    def ifModifiedSinceHeader(instant: Instant) =
      `If-Modified-Since`(SprayDateTime(instant.getMillis))

    /** Create a new feed for a queue.
      * @param queueDefUri the Uri for the queue definition
      * @param client an HttpClient bound to the queue server.
      * @return relative Uri for the newly created feed resources
      */
    def createFeed(queueDefUri: Uri)(implicit client: HttpClient): Uri =
      HttpRequest(
        HttpMethods.POST,
        queueDefUri + "/feeds") ~> client ~> checkWhenReady {

        status should === (StatusCodes.Created)

        // Get the link to the new feed.
        val nfv = body.asString.parseJson.convertTo[NewFeedView]
        Uri(nfv.newFeed.link.path.toString)
      }

    /** Reed the feed for a queue.
      * @param feedUri the Uri for the feed resource
      * @param ifModifiedSinceTime optional value to be used for If-Modified-Since header
      * @param client an HttpClient bound to the queue server.
      * @return a pair consisting of the queue contents and its time stamp
      */
    def readFeed(feedUri: Uri, time: Option[Instant] = None)(implicit client: HttpClient)
      : (Seq[QueueJSONAppointment], Instant)
      = {

      HttpRequest(
        HttpMethods.GET,
        headers = time.toList.map { ifModifiedSinceHeader(_) },
        uri = feedUri) ~> client ~> checkWhenReady {

          status should === (StatusCodes.OK)

          // Verify that there is a Last-Modified header.
          val lmh = headers.find {
            case `Last-Modified`(date) => true
            case _ => false
          }

          lmh should be ('defined)

          val lastModified = lmh.get match {
            case `Last-Modified`(date) => new Instant(date.clicks)
            case _ => fail
          }

          val appts = body.asString.parseJson.convertTo[Seq[QueueJSONAppointment]]

          (appts, lastModified)
        }
    }

    def readAppointments(queueUri: Uri, expectedStatus: StatusCode = StatusCodes.OK)(implicit client: HttpClient)
      :(Seq[Appointment])
      = {

      HttpRequest(
        HttpMethods.GET,
        uri = queueUri + "/appointments") ~> client ~> checkWhenReady {

          status should === (expectedStatus)

          if (status == StatusCodes.OK) {
              body.asString.parseJson.convertTo[Seq[Appointment]]
          } else {
            Seq[Appointment]()
          }
        }
    }

    it ("should start successfully in an app-level test") { case (client, tesa) =>
      HttpRequest(HttpMethods.GET, "/status") ~> client ~> checkWhenReady {
        status should === (StatusCodes.OK)
      }
    }

    it ("should correctly define, activate and publish an empty queue") { case (client, tesa) =>
      implicit val _ = client
      // Truncate to seconds because poll timestamps precision is 1 second.
      val beginningOfTest = DateTime.now.withMillisOfSecond(0)

      // Generate a QueueDefinition at a certain TesaInstance.
      val tesaInstance = genTesaInstance.sample.get
      val definition = genQueueDefinition(tesaInstance, tesa.tesaRestUri(tesaInstance)).sample.get

      // Create that location in TESA so the effect will be an empty queue.
      tesa.addLocation(definition.location)

      // Create the queue definition, a new feed, then read the feed.
      val (appointments, feedTimeStamp) =
        readFeed(
           createFeed(
             createQueueDefinition(definition)))

      // Verify that the feed time stamp is recent.
      feedTimeStamp.getMillis() should be >= beginningOfTest.getMillis()

      // Verify the queue length.
      appointments should be ('empty)
    }

    it ("should correctly define, activate and publish a non-empty queue") { case (client, tesa) =>
      implicit val _ = client
      // Truncate to seconds because poll timestamps precision is 1 second.
      val beginningOfTest = DateTime.now.withMillisOfSecond(0)

      // Generate a QueueDefinition at a certain TesaInstance.
      val tesaInstance = genTesaInstance.sample.get
      val definition = genQueueDefinition(tesaInstance, tesa.tesaRestUri(tesaInstance)).sample.get

      // Generate some appointments that will match the queue and add them to TESA.
      val nextHour = beginningOfTest.withMinuteOfHour(0).plusHours(2)
      val Count = 100
      val originalAppointments =
        tesa.addAppointmentsFor(definition, nextHour.toInstant(), 5.minutes, Count)

      // Create the queue definition, a new feed, then read the feed.
      val (appointments, feedTimeStamp) =
        readFeed(
          createFeed(
            createQueueDefinition(definition)))

      // Verify length of queue.
      appointments.size should ===(Count)

      // Verify that the feed time stamp is recent.
      feedTimeStamp.getMillis() should be >= beginningOfTest.getMillis()

      // Verify that the confirmation numbers and their order are preserved in the feed.
      appointments map {_.id} should ===(originalAppointments map { _.externalId.toString })
    }

    it ("should correctly read the appointments of an empty queue") { case (client, tesa) =>
      implicit val _ = client
      // Truncate to seconds because poll timestamps precision is 1 second.
      val beginningOfTest = DateTime.now.withMillisOfSecond(0)

      // Generate a QueueDefinition at a certain TesaInstance.
      val tesaInstance = genTesaInstance.sample.get
      val definition = genQueueDefinition(tesaInstance, tesa.tesaRestUri(tesaInstance)).sample.get

      // Define at least the location in TESA.
      tesa.addLocation(definition.location)

      // Create the queue definition, then read the queue's appointments synchronously.
      val appointments =
        readAppointments(
          createQueueDefinition(definition))

      // Verify length of queue.
      appointments.size should ===(0)
    }

    it ("should gracefully handle when the queue's appointments can't be read") { case (client, tesa) =>
      implicit val _ = client
      // Truncate to seconds because poll timestamps precision is 1 second.
      val beginningOfTest = DateTime.now.withMillisOfSecond(0)

      // Generate a QueueDefinition at a certain TesaInstance.
      val tesaInstance = genTesaInstance.sample.get
      val definition = genQueueDefinition(tesaInstance, tesa.tesaRestUri(tesaInstance)).sample.get

      // With not even a definition of the location in TESA, activating the queue should fail when
      // it attempts to fetch appointments.

      // Create the queue definition, then try to read the queue's appointments synchronously.
      val appointments =
        readAppointments(
          createQueueDefinition(definition),
          StatusCodes.ServiceUnavailable)
    }

    it ("should correctly read the appointments of a non-empty queue") { case (client, tesa) =>
      implicit val _ = client
      // Truncate to seconds because poll timestamps precision is 1 second.
      val beginningOfTest = DateTime.now.withMillisOfSecond(0)

      // Generate a QueueDefinition at a certain TesaInstance.
      val tesaInstance = genTesaInstance.sample.get
      val definition = genQueueDefinition(tesaInstance, tesa.tesaRestUri(tesaInstance)).sample.get

      // Generate some appointments that will match the queue and add them to TESA.
      val nextHour = beginningOfTest.withMinuteOfHour(0).plusHours(2)
      val Count = 100
      val originalAppointments =
        tesa.addAppointmentsFor(definition, nextHour.toInstant(), 5.minutes, Count)

      // Create the queue definition, then read the queue's appointments synchronously.
      val appointments =
        readAppointments(
          createQueueDefinition(definition))

      // Verify length of queue.
      appointments.size should ===(Count)

      // Verify that the confirmation numbers and their order are preserved in the feed.
      appointments map {_.externalId.toString} should ===(originalAppointments map { _.externalId.toString })
    }

    it ("should time out long-polls to a feed") { case (client, tesa) =>
      implicit val _ = client
      // Truncate to seconds because poll timestamps precision is 1 second.
      val beginningOfTest = DateTime.now.withMillisOfSecond(0)

      // Generate a QueueDefinition at a certain TesaInstance.
      val tesaInstance = genTesaInstance.sample.get
      val definition = genQueueDefinition(tesaInstance, tesa.tesaRestUri(tesaInstance)).sample.get

      // Create that location in TESA so the effect will be an empty queue.
      tesa.addLocation(definition.location)

      // Create the queue definition and a new feed
      val feedUri = createFeed(createQueueDefinition(definition))

      // Do initial read with no time stamp so it returns immediately.
      val (appointments, initialDataVersion) = readFeed(feedUri)
      initialDataVersion.getMillis() should be >= beginningOfTest.getMillis()
      appointments should be ('empty)

      // Do a second read and pass back in the timestamp returned.
      // There will be no fresh data so the read should time out,
      // responding with 304.

      val header = ifModifiedSinceHeader(initialDataVersion)
      HttpRequest(
        HttpMethods.GET,
        headers = List(header),
        uri = feedUri) ~> client ~> checkWhenReady {

          status should === (StatusCodes.NotModified)
        }
    }

    it ("should complete long-polls when new data is available") { case (client, tesa) =>
      implicit val _ = client
      // Truncate to seconds because poll timestamps precision is 1 second.
      val beginningOfTest = DateTime.now.withMillisOfSecond(0)

      // Generate a QueueDefinition at a certain TesaInstance.
      val tesaInstance = genTesaInstance.sample.get
      val definition = genQueueDefinition(tesaInstance, tesa.tesaRestUri(tesaInstance)).sample.get

      // Generate some appointments that will match the queue and add them to TESA.
      val nextHour = beginningOfTest.withMinuteOfHour(0).plusHours(2)
      val Count = 10
      val originalAppointments =
        tesa.addAppointmentsFor(definition, nextHour.toInstant(), 5.minutes, Count)

      // Create the queue definition and a new feed
      val feedUri = createFeed(createQueueDefinition(definition))

      // Do initial read with no time stamp so it returns immediately.
      val initialDataVersion = {
        val (appointments, version) = readFeed(feedUri)
        version.getMillis() should be >= beginningOfTest.getMillis()
        appointments.size should === (Count)

        version
      }

      // Create a change that should be reflected in the queue data.
      tesa.cancelAppointment(originalAppointments.head.externalId)

      // Do a second read and pass back in the timestamp returned.
      Thread.sleep(5)

      val finalDataVersion = {
        val (appointments, version) = readFeed(feedUri, Some(initialDataVersion))

        // Note that the new version may be equal to the previous one because
        // the timestamps passed are only accurate to 1 second.
        version.getMillis should be > initialDataVersion.getMillis
        appointments.size should === (Count - 1)
        version
      }
    }

    ignore ("should correctly define, activate and publish multiple non-empty queues") { case (client, tesa) =>
      implicit val _ = client
      // Truncate to seconds because poll timestamps precision is 1 second.
      val beginningOfTest = DateTime.now.withMillisOfSecond(0)

      ??? // TODO
    }

    ignore ("should correctly process appointment events from TESA") { case (client, tesa) =>
      implicit val _ = client
      // Truncate to seconds because poll timestamps precision is 1 second.
      val beginningOfTest = DateTime.now.withMillisOfSecond(0)
      ??? // TODO
    }

    // TODO:
    //  - test queue deactivation via shutdown
  }
}
