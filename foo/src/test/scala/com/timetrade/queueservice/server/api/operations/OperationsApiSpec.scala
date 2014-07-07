/** Copyright(c) 2013-2014 by TimeTrade Systems.  All Rights Reserved. */
package com.timetrade.queueservice.server.api.operations

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

import spray.http.HttpMethods.GET
import spray.http.HttpRequest
import spray.http.StatusCodes
import spray.http.Uri.apply
import spray.testkit.ScalatestRouteTest

import org.scalatest.Matchers
import org.scalatest.fixture.FunSpec

import com.timetrade.queueservice.server.api.Api
import com.timetrade.queueservice.server.core.Core
import com.timetrade.queueservice.server.testtraits.ConfigurableActorSystems
import com.timetrade.queueservice.testtraits.TimedSuite

class OperationsApiSpec
  extends FunSpec
  with Matchers
  with ScalatestRouteTest
  with ConfigurableActorSystems
  with TimedSuite {

  override def createActorSystem() = defaultActorSystem()

  type FixtureParam = (Api)

  def withFixture(test: OneArgTest) = {

    val api = Api()(Core.defaultForTesting(system))

    try {
      Await.result(api.core.becomeReady(), 30.seconds)
      test(api)
    }
    finally {
      api.shutdown()
    }
  }

  implicit val timeout = RouteTestTimeout(120.seconds)

  describe ("The Operations REST API") {

    it("should correctly handle GET /") { api =>
      HttpRequest(GET, "/") ~> api.combinedRoute ~> check {
        status should be(StatusCodes.OK)
        responseAs[String] should include("/about")
        responseAs[String] should include("/instances")
        responseAs[String] should include("/licensees")
        responseAs[String] should include("/status")
      }
    }

    it ("should correctly handle GET /about") { api =>
      HttpRequest(GET, "/about") ~> api.combinedRoute ~> check {
        status should be (StatusCodes.OK)
        responseAs[String] should include ("Concierge Queue Server")
      }
    }

    it("should correctly handle GET /instances") { api =>
      // As we have no queues defined by this test, this rejects.
      HttpRequest(GET, "/instances") ~> api.combinedRoute ~> check {
        status should be(StatusCodes.NotFound)
      }
    }

    it("should correctly handle GET /licensees") { api =>
      // As we have no queues defined by this test, this rejects.
      HttpRequest(GET, "/licensees") ~> api.combinedRoute ~> check {
        status should be(StatusCodes.NotFound)
      }
    }

    it ("should correctly handle GET /status") { api =>
      HttpRequest(GET, "/status") ~> api.combinedRoute ~> check {
        status should be (StatusCodes.OK)
        responseAs[String] should include ("Concierge Queue Server")
        responseAs[String] should include ("Metrics:")
      }
    }
  }
}
