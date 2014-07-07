package com.timetrade.queueservice.server.core.publishing

import scala.concurrent.duration._

import akka.actor.ActorSystem

import org.scalatest.fixture.FunSpec

import com.timetrade.queueservice.testtraits.TimedSuite

/** Tests for both implementations of PollableQueueState.
  */
class PollableQueueStatePublisherSpec
  extends FunSpec
  with TimedSuite
  with PollableQueueStatePublisherBehaviors {

  // Create a new STM based publisher
  def makeSTMBasedPublisher(readTimeout: FiniteDuration, system: ActorSystem): QueueStatePublisher
  = new QueueStatePublisherUsingSTM(readTimeout)(system)

  // Create a new Actor based publisher
  def makeActorBasedPublisher(readTimeout: FiniteDuration, system: ActorSystem): QueueStatePublisher
  = new QueueStatePublisherUsingActor(readTimeout)(system)

  describe ("A PollableSTMQueueState") { it should behave like publisher(makeSTMBasedPublisher _) }

  describe ("A PollableActorQueueState") { it should behave like publisher(makeActorBasedPublisher _) }
}
