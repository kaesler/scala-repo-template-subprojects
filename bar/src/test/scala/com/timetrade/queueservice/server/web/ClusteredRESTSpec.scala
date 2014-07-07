package com.timetrade.queueservice.server.web

import com.timetrade.queueservice.testtraits.TimedSuite
import org.scalatest.concurrent.ScalaFutures
import com.timetrade.queueservice.server.web.mockedtesa.MockedTesa
import org.scalatest.Matchers
import org.scalatest.concurrent.Futures
import com.timetrade.queueservice.server.core.data.appointment.AppointmentGeneration
import com.timetrade.queueservice.server.core.definitioncrud.QueueDefinitionGeneration
import com.timetrade.queueservice.server.testtraits.ConfigurableActorSystems
import org.scalactic.TypeCheckedTripleEquals
import org.scalatest.fixture.FunSpec
import scala.concurrent.Future
import com.timetrade.queueservice.netutils.HttpClient
import org.scalatest.time.Millis
import org.scalatest.time.Seconds
import org.scalatest.time.Span

/** Tests for scenarios using REST calls to multiple cluster members, */
abstract class ClusteredRESTSpec
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

  type FixtureParam = (Array[HttpClient], MockedTesa)


  // TODO:
  //   - create a cluster exposing N REST pots
  //   - create a ticket via one cluster member
  //   - use it via another cluster member

}
