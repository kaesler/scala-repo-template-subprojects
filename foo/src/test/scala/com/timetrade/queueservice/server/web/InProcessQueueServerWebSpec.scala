package com.timetrade.queueservice.server.web

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

import spray.http.{DateTime => SprayDateTime}
import spray.http.HttpHeaders._
import spray.http.HttpMethods
import spray.http.HttpRequest
import spray.http.StatusCode
import spray.http.StatusCodes
import spray.http.Uri
import spray.http.Uri.apply
import spray.json.pimpString

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

/** Tests that exercise the whole of the queue server using:
  *  - its REST APis
  *  - a MockedTesa
  */
class InProcessQueueServerWebSpec extends AbstractQueueServerWebSpec {

  def withFixture(test: OneArgTest) = {

    // Create separate actor systems for better approximation to reality.
    val queueServerActorSystem = actorSystemWithClustering("QueueServerWeb")
    val httpClientActorSystem = defaultActorSystem("HttpClient")

    // Create a Queue Server Web instance and a client for it.
    val (_, port) = Utils.temporaryServerHostnameAndPort()
    val web = Web(coreSettings = CoreSettings.defaultForTesting
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
      test((qsClient, mockedTesa))
    } finally {
      qsClient.shutdown()
      mockedTesa.shutdown()
      web.shutdown()
      httpClientActorSystem.shutdown()
      httpClientActorSystem.awaitTermination()
      queueServerActorSystem.shutdown()
      queueServerActorSystem.awaitTermination()
    }
  }
}
