/** Copyright(c) 2014 by TimeTrade Systems.  All Rights Reserved. */
package com.timetrade.queueservice.server.core.definitioncrud

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

import spray.http.Uri
import spray.http.Uri.apply

import org.joda.time.DateTime
import org.scalatest.Matchers
import org.scalatest.fixture.FunSpec

import com.timetrade.queueservice.server.api.Api
import com.timetrade.queueservice.server.api.appointmentevents.AppointmentConvertToWalkin
import com.timetrade.queueservice.server.core.Core
import com.timetrade.queueservice.server.core.data.Licensee
import com.timetrade.queueservice.server.core.data.Location
import com.timetrade.queueservice.server.core.data.TesaInstance
import com.timetrade.queueservice.server.core.data.appointment.ConfirmationNumber
import com.timetrade.queueservice.server.core.data.externalids.LicenseeExternalId
import com.timetrade.queueservice.server.core.data.externalids.QueueExternalId
import com.timetrade.queueservice.server.core.data.internalids.ActivityInternalId
import com.timetrade.queueservice.server.core.data.internalids.CampaignInternalId
import com.timetrade.queueservice.server.core.data.internalids.InternalId
import com.timetrade.queueservice.server.core.data.internalids.LocationInternalId
import com.timetrade.queueservice.server.core.data.internalids.ProgramInternalId
import com.timetrade.queueservice.server.core.data.internalids.ResourceInternalId
import com.timetrade.queueservice.server.core.matching.AppointmentPropertiesMatcher
import com.timetrade.queueservice.server.core.matching.Matcher
import com.timetrade.queueservice.server.testtraits.ConfigurableActorSystems
import com.timetrade.queueservice.testtraits.TimedSuite

class QueueDefinitionSpec
  extends FunSpec
  with Matchers
  with ConfigurableActorSystems
  with TimedSuite {

  type FixtureParam = Api

  def withFixture(test: OneArgTest) = {

    // Fresh ActorSystem for each test.
    implicit val system = defaultActorSystem()

    val api = Api()(Core.defaultForTesting(system))

    try {
      Await.result(api.core.becomeReady(), 30.seconds)
      test(api)
    } finally {
      api.shutdown()
      system.shutdown()
    }
  }

  private val testTesaRestRoot = "http://helium:8080"
  private val testLicenseeId = "sprint"
  private val testInstanceId = "sprint"
  private val testLocationId = 100
  private val testLicensee = Licensee(TesaInstance(testInstanceId), LicenseeExternalId(testLicenseeId))
  private val testLocation = Location(testLicensee, LocationInternalId(testLocationId))

  describe("QueueDefinition") {

    describe("appointmentUpdateUrl") {

      it("doesn't append matcher") { implicit api =>
        val matcher = AppointmentPropertiesMatcher(
            activities = matcherFor(Set(ActivityInternalId(1))),
            campaigns = matcherFor(Set(CampaignInternalId(2))),
            programs = matcherFor(Set(ProgramInternalId(3))),
            resources = matcherFor(Set(ResourceInternalId(4))))
        val qDef = getQueueDefinition(matcher)

        val confNo = ConfirmationNumber("ABC123")
        val updateUrl = qDef.appointmentUpdateUrl(AppointmentConvertToWalkin)
        val expected = Uri(List(testTesaRestRoot, testLicenseeId, "queued-appointments").mkString("/") + "?event=AppointmentConvertToWalkin")
        updateUrl should equal(expected)
      }
    }

    describe("queueFillUri") {

      it("computes with only a location") { implicit api =>
        val qDef = getQueueDefinition
        val fillUri = qDef.queueFillUrl()
        val expected = List(testTesaRestRoot, testLicenseeId, "queued-appointments").mkString("/") + "?" + getQueryParms(qDef)
        fillUri should equal (Uri(expected))
      }

      it("computes with single-value matcher") { implicit api =>
        val matcher = AppointmentPropertiesMatcher(
            activities = matcherFor(Set(ActivityInternalId(1))),
            campaigns = matcherFor(Set(CampaignInternalId(2))),
            programs = matcherFor(Set(ProgramInternalId(3))),
            resources = matcherFor(Set(ResourceInternalId(4))))
        val qDef = getQueueDefinition(matcher)

        val fillUri = qDef.queueFillUrl()
        val expectedUri = Uri(List(testTesaRestRoot, testLicenseeId, "queued-appointments").mkString("/") + "?" + getQueryParms(qDef))
        matches(fillUri, expectedUri)
      }

      it("computes with multiple-value matcher") { implicit api =>
        val matcher = AppointmentPropertiesMatcher(
            activities = matcherFor(Set(ActivityInternalId(1),ActivityInternalId(101))),
            campaigns = matcherFor(Set(CampaignInternalId(2),CampaignInternalId(102))),
            programs = matcherFor(Set(ProgramInternalId(3),ProgramInternalId(103))),
            resources = matcherFor(Set(ResourceInternalId(4),ResourceInternalId(104))))
        val qDef = getQueueDefinition(matcher)

        val fillUri = qDef.queueFillUrl()
        val expectedUri = Uri(List(testTesaRestRoot, testLicenseeId, "queued-appointments").mkString("/") + "?" + getQueryParms(qDef))
        matches(fillUri, expectedUri)
      }
    }
  }

  private def matches(actual: Uri, expected: Uri) = {
    /*
     * Because the implementations of QueueDefinition.queueFillUri is (intentionally) different
     * than how this test computes an expected Uri, we can't expect two to compare exactly.
     * Compare them in a way that accounts for (say) differences in the order of query params.
     */
    actual.path should equal(expected.path)

    val actualQuery = actual.query.toString.split("&").toSet
    val expectedQuery = expected.query.toString.split("&").toSet
    actualQuery should equal(expectedQuery)
  }

  private def getQueryParms(qDef: QueueDefinition): String = {
    // Gather the properties, which become query params.  At minimum, we have a location ID.
    val now = DateTime.now().toLocalDate()
    var parms: List[Tuple2[String, String]] =
      List(
          ("locationId", qDef.location.internalId.i.toString),
          ("start", now.toString))

    val matcher = qDef.matcher
    parms = parms ++ getIdParms("appointmentTypeId", matcher.activities)
    parms = parms ++ getIdParms("campaignId", matcher.campaigns)
    parms = parms ++ getIdParms("appointmentTypeGroupId", matcher.programs)
    parms = parms ++ getIdParms("resourceId", matcher.resources)
    val ans = parms.map(p => p._1 + "[]=" + p._2.toString).mkString("&")
    ans
  }

  private def getQueueDefinition: QueueDefinition = {
    getQueueDefinition(AppointmentPropertiesMatcher())
  }

  private def getQueueDefinition(matcher: AppointmentPropertiesMatcher): QueueDefinition = {
    QueueDefinition(
          QueueExternalId("dontcare"),
          TesaRestUrl(testTesaRestRoot),
          testLocation,
          matcher,
          QueueRules(),
          None,
          false,
          false)
  }

  private def getIdParms[T <: InternalId](parm: String, m: Matcher[T]): List[Tuple2[String, String]] = {
    val ids = getIds(m)
    ids.map(id => Tuple2(parm, id.i.toString))
  }

  private def getIds[T <: InternalId](m: Matcher[T]): List[T] = {
    val ids = if (m.matchesAny) List() else m.elements.toList
    ids
  }

  private def matcherFor[T <: InternalId](ids: Set[T]): Matcher[T] = {
    ids.toList match {
      case Nil => Matcher.any
      case head :: Nil => if (InternalId.isWildcard(head.i)) Matcher.any else Matcher(head)
      case head :: tail => Matcher(ids)
    }
  }
}
