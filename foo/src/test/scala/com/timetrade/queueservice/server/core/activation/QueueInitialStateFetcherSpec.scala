package com.timetrade.queueservice.server.core.activation

import akka.actor.ActorSystem

import spray.http.Uri

import org.joda.time.DateTime
import org.joda.time.DateTimeZone

import org.scalactic.TypeCheckedTripleEquals

import org.scalatest.Matchers
import org.scalatest.concurrent.Futures
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.fixture.FunSpec
import org.scalatest.time.Millis
import org.scalatest.time.Seconds
import org.scalatest.time.Span

import com.timetrade.queueservice.server.core.data.appointment.MockTesa
import com.timetrade.queueservice.server.core.data.internalids.LocationInternalId
import com.timetrade.queueservice.server.core.definitioncrud.QueueDefinitionGeneration
import com.timetrade.queueservice.server.core.definitioncrud.TesaRestUrl
import com.timetrade.queueservice.server.core.matching.AppointmentPropertiesMatcher
import com.timetrade.queueservice.server.core.matching.Matcher
import com.timetrade.queueservice.server.testtraits.ConfigurableActorSystems
import com.timetrade.queueservice.testtraits.TimedSuite

class QueueInitialStateFetcherSpec
  extends FunSpec
  with Matchers
  with Futures
  with ScalaFutures
  with TypeCheckedTripleEquals
  with TimedSuite
  with ConfigurableActorSystems
  with QueueDefinitionGeneration {

  type FixtureParam = ActorSystem

  def withFixture(test: OneArgTest) = {

    implicit val system = defaultActorSystem()

    try {
      test(system)
    } finally {
      system.shutdown()
    }
  }

  // Configure the behavior of the "whenReady" calls below.
  implicit val defaultPatience = PatienceConfig(timeout =  Span(30, Seconds),
                                                interval = Span(1, Millis))

  describe ("QueueInitialStateFetcher") {

    // Generate some values that will correspond to a valid licensee and valid location in
    // a real TESA server.
    val validTesaRestUrl = Uri("Http://10.192.239.125:8080")
    val validLocation = MockTesa().locs.head
    val validDefn = {
      val randomDefn = arbQueueDefinition.arbitrary.sample.get
      randomDefn
        .copy(
          location = validLocation,
          matcher = AppointmentPropertiesMatcher(Matcher.any, Matcher.any, Matcher.any, Matcher.any),
          tesaRestUrl = TesaRestUrl(validTesaRestUrl))
    }

    it ("should fetch empty queue contents correctly") { implicit system =>
      whenReady(QueueStateFetcher(system).fetch(validDefn)) {
        case QueueFetchedState(_, _, tz) =>
          tz should === (DateTimeZone.forID("America/Chicago"))
      }
    }

    it ("should fetch non-empty queue contents correctly") { implicit system =>
      val validLocationForNonEmpty = validLocation.copy(internalId = LocationInternalId(22))
      val validDayForNonEmpty = DateTime.parse("2013-12-04")
      whenReady(QueueStateFetcher(system).fetch(
        validDefn.withLocation(validLocationForNonEmpty), validDayForNonEmpty)) {
        case QueueFetchedState(contents, _, tz) =>
          tz should === (DateTimeZone.forID("America/Chicago"))
          contents.elements should not have size(0)
      }
    }
  }
}
