package com.timetrade.queueservice.server.core.publishing

import java.util.concurrent.TimeoutException

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.concurrent.duration.DurationInt

import akka.actor.ActorSystem

import org.joda.time.DateTimeZone

import org.scalactic.TypeCheckedTripleEquals

import org.scalatest.Matchers
import org.scalatest.concurrent.Eventually
import org.scalatest.concurrent.Futures
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.fixture.FunSpec
import org.scalatest.time.Seconds
import org.scalatest.time.Span

import com.timetrade.queueservice.server.core.data.QueuePublishedState
import com.timetrade.queueservice.server.testtraits.ConfigurableActorSystems

/** Trait that provides tests for PollableQueueState.
  */
trait PollableQueueStatePublisherBehaviors
  extends Matchers
  with Futures
  with ScalaFutures
  with Eventually
  with ConfigurableActorSystems
  with TypeCheckedTripleEquals { this: FunSpec =>

  type FixtureParam = ActorSystem

  def withFixture(test: OneArgTest) = {
    val system = defaultActorSystem()

    try {
      test(system)
    } finally {
      system.shutdown()
    }
  }

  def publisher(makePublisher: (FiniteDuration, ActorSystem) => QueueStatePublisher) {

    it ("should cause reads that precede any writes to await data") { system =>
      val publisher = makePublisher(5.seconds, system)
      val f = publisher.read(None)
      f.isCompleted should be (false)
    }

    it ("should time out pending reads") { system =>
      val readTimeout = 5.seconds
      val publisher = makePublisher(readTimeout, system)
      val f = publisher.read(None)
      Thread.sleep(readTimeout.toMillis + 2000)

      f.isCompleted should be (true)
      intercept[TimeoutException] { Await.result(f, 0.seconds) }
    }

    it ("should supply in read() what was written using write()") { system =>

      // Create a cell with a known read timeout.
      val readTimeout = 5.seconds
      val publisher = makePublisher(readTimeout, system)

      // Initiate a non-blocking read.
      val f = publisher.read(None)

      // Write data which should complete the read.
      val stateWritten = QueuePublishedState(contents = Seq(), DateTimeZone.UTC)
      publisher.write(stateWritten)
      eventually (timeout(Span(5, Seconds))) { f.isCompleted should be (true) }

      // Wait a while to let the timeout code occur if it is going to.
      Thread.sleep(readTimeout.toMillis + 2000)

      // Verify that we read what was written
      whenReady(f) { case (state, _)  => state should === (stateWritten) }
    }
  }
}
