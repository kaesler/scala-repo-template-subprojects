package com.timetrade.queueservice.server.core.activation

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

import akka.actor.ActorSystem

import spray.http.Uri

import org.scalacheck.Arbitrary.arbitrary

import org.scalactic.TypeCheckedTripleEquals

import org.scalatest.Matchers
import org.scalatest.concurrent.Futures
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.fixture.FunSpec
import org.scalatest.time.Millis
import org.scalatest.time.Seconds
import org.scalatest.time.Span

import com.timetrade.queueservice.server.core.Core
import com.timetrade.queueservice.server.core.CoreSettings
import com.timetrade.queueservice.server.core.QueueActorFailedToInitializeException
import com.timetrade.queueservice.server.core.definitioncrud.QueueDefinition
import com.timetrade.queueservice.server.core.definitioncrud.QueueDefinitionGeneration
import com.timetrade.queueservice.server.core.definitioncrud.QueueId
import com.timetrade.queueservice.server.core.definitioncrud.TesaRestUrl
import com.timetrade.queueservice.server.testtraits.ConfigurableActorSystems
import com.timetrade.queueservice.testtraits.TimedSuite

class QueueActivationFailureSpec
  extends FunSpec
  with Matchers
  with Futures
  with ScalaFutures
  with TypeCheckedTripleEquals
  with TimedSuite
  with ConfigurableActorSystems
  with QueueDefinitionGeneration {

  // Configure the behavior of the "whenReady" calls below.
  implicit val defaultPatience = PatienceConfig(timeout =  Span(30, Seconds),
                                                interval = Span(1, Millis))
  def await[T](f: Future[T]): T = whenReady(f) { t => t }

  type FixtureParam = ActorSystem

  def withFixture(test: OneArgTest) = {

    val system = defaultActorSystem()

    try {
      test(system)
    } finally {
      system.shutdown()
    }
  }

  describe ("The QueueActivation facet of the Core") {

    it ("should correctly handle failure to contact TESA") { implicit system =>

      val core = new Core(settings = CoreSettings.defaultForTesting)
      await(core.becomeReady())

      try {
      val id = arbitrary[QueueId].sample.get
      val definition = arbitrary[QueueDefinition].sample.get
        .copy(tesaRestUrl = TesaRestUrl(Uri("http://tesa/dummy/")))

      a [QueueActorFailedToInitializeException] should be thrownBy {
        Await.result(core.activate(id, definition), 10.seconds)
      }
      } finally {
        core.shutdown()
      }
    }
  }
}
