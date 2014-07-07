/** Copyright(c) 2014 by TimeTrade Systems.  All Rights Reserved. */
package com.timetrade.queueservice.server.api.appointmentevents

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

import spray.http.HttpMethods.POST
import spray.http.HttpRequest
import spray.http.StatusCodes
import spray.http.Uri
import spray.testkit.ScalatestRouteTest

import org.scalatest.Matchers
import org.scalatest.fixture.FunSpec

import com.timetrade.queueservice.server.api.Api
import com.timetrade.queueservice.server.api.JsonContentUtils
import com.timetrade.queueservice.server.core.Core
import com.timetrade.queueservice.server.core.data.appointment.Appointment
import com.timetrade.queueservice.server.core.data.appointment.MockTesa
import com.timetrade.queueservice.server.testtraits.ConfigurableActorSystems
import com.timetrade.queueservice.testtraits.TimedSuite

class AppointmentEventsApiSpec
  extends FunSpec
  with Matchers
  with ScalatestRouteTest
  with ConfigurableActorSystems
  with TimedSuite {

  type FixtureParam = (Api)
  implicit val timeout = RouteTestTimeout(120.seconds)

  override def createActorSystem() = defaultActorSystem()

  def withFixture(test: OneArgTest) = {

    val api = Api()(Core.defaultForTesting(system))

    try {
      Await.result(api.core.becomeReady(), 30.seconds)
      test(api)
    } finally {
      api.shutdown()
    }
  }

  val tesaInstanceId = "sprint"
  val licenseeId = "sprint"
  val collectionUri = s"/${tesaInstanceId}/${licenseeId}/appointment-events"

  describe("The Appointment Events REST API") {


    it("Requires an event-kind") { implicit api =>
      val appt = MockTesa().appointments.gimmee()
      val eventsUri = Uri(collectionUri)
      HttpRequest(POST, eventsUri, entity = entityFor(appt)) ~> api.combinedRoute ~> check {
        /* Since this was rejected by Spray due to no route matched, and since we're testing at the
         * Spray route level (and not really making HTTP requests), we can't look at status.
         */
        handled should be(false)
      }
    }

    it("requires a recognizable event-kind") { implicit api =>
      val appt = MockTesa().appointments.gimmee()
      val eventsUri = Uri(collectionUri + "/FOO")
      HttpRequest(POST, eventsUri, entity = entityFor(appt)) ~> api.combinedRoute ~> check {
        status should be(StatusCodes.BadRequest)
      }
    }

    it("harmlessly ignores an event that can't be mapped to an active queue") { implicit api =>
      val appt = MockTesa().appointments.gimmee()
      val eventsUri = Uri(collectionUri + "/" + AppointmentAdded.name)
      HttpRequest(POST, eventsUri, entity = entityFor(appt)) ~> api.combinedRoute ~> check {
        status should be(StatusCodes.Accepted)
      }
    }
  }

  private def entityFor(appt: Appointment) = {
    JsonContentUtils.jsonHttpEntityFor(
      appt,
      CustomMediaTypes.`application/vnd.timetrade.queue-server.appointment-events+json`)
  }
}
