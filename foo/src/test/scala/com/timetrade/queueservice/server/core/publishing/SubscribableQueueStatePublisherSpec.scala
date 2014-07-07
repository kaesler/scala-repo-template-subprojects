/** Copyright(c) 2014 by TimeTrade Systems.  All Rights Reserved. */
package com.timetrade.queueservice.server.core.publishing

import scala.concurrent.duration.DurationDouble
import scala.concurrent.duration.DurationInt
import akka.actor.ActorSystem
import akka.actor.Status.Failure
import akka.event.Logging
import akka.testkit.ImplicitSender
import akka.testkit.TestKit
import akka.testkit.TestProbe
import org.scalatest.BeforeAndAfterAll
import org.scalatest.Matchers
import org.scalatest.WordSpecLike
import com.timetrade.queueservice.server.core.data.QueuePublishedState
import com.timetrade.queueservice.server.testtraits.ConfigurableActorSystems
import com.timetrade.queueservice.server.api.feeds.QueueSubscriptionActor
import com.timetrade.queueservice.server.web.ClosedChunk
import org.joda.time.DateTimeZone

/** Tests for the implementation of the producer side of queue feeds using push connections,
  * such as HTML5 Server-Sent Events.
  */
class SubscribableQueueStatePublisherSpec(_system: ActorSystem)
  extends TestKit(_system)
  with ImplicitSender
  with WordSpecLike
  with Matchers
  with BeforeAndAfterAll {

  def this() = this(ConfigurableActorSystems.defaultActorSystem("SubscribableQueueStateSpec"))

  private lazy val log = Logging.getLogger(system, this)

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  "QueueStatePublisher" must {

    "expect queue contents when subscriptions are started" in {
      val subscribable = new QueueStatePublisherUsingActor(2.seconds)(system)
      val probe = TestProbe()
      subscribable.subscribe(probe.ref, QueueSubscriptionActor.Protocol.Closed)
      probe.expectNoMsg(0.5.seconds)
    }

    "allow unsubscriptions without subscriptions" in {
      val subscribable = new QueueStatePublisherUsingActor(2.seconds)(system)
      val probe = TestProbe()
      subscribable.unsubscribe(probe.ref)
      probe.expectNoMsg(0.5.seconds)
    }

    "notify subscribers when writes occur" in {
      val subscribable = new QueueStatePublisherUsingActor(2.seconds)(system)
      val probe = TestProbe()
      subscribable.subscribe(probe.ref, QueueSubscriptionActor.Protocol.Closed)
      probe.expectNoMsg(0.5.seconds)

      val stateWritten = QueuePublishedState(contents = Seq(), DateTimeZone.UTC)
      subscribable.write(stateWritten)
      probe.expectMsg(0.5.seconds, stateWritten)
    }

    "notify multiple subscribers when writes occur" in {
      val subscribable = new QueueStatePublisherUsingActor(2.seconds)(system)
      val probe1 = TestProbe()
      val probe2 = TestProbe()
      subscribable.subscribe(probe1.ref, QueueSubscriptionActor.Protocol.Closed)
      subscribable.subscribe(probe2.ref, QueueSubscriptionActor.Protocol.Closed)
      probe1.expectNoMsg(0.5.seconds)
      probe2.expectNoMsg(0.5.seconds)

      val stateWritten = QueuePublishedState(contents = Seq(), DateTimeZone.UTC)
      subscribable.write(stateWritten)
      probe1.expectMsg(0.5.seconds, stateWritten)
      probe2.expectMsg(0.5.seconds, stateWritten)
    }

    "honor unsubscriptions" in {
      val subscribable = new QueueStatePublisherUsingActor(2.seconds)(system)
      val probe1 = TestProbe()
      val probe2 = TestProbe()
      subscribable.subscribe(probe1.ref, QueueSubscriptionActor.Protocol.Closed)
      subscribable.subscribe(probe2.ref, QueueSubscriptionActor.Protocol.Closed)
      probe1.expectNoMsg(0.5.seconds)
      probe2.expectNoMsg(0.5.seconds)
      subscribable.unsubscribe(probe1.ref)
      probe1.expectMsg(0.5.seconds, QueueSubscriptionActor.Protocol.Closed)

      val stateWritten = QueuePublishedState(contents = Seq(), DateTimeZone.UTC)
      subscribable.write(stateWritten)
      probe1.expectNoMsg(0.5.seconds)
      probe2.expectMsg(0.5.seconds, stateWritten)
    }

    "unsubscribe all upon close" in {
      val subscribable = new QueueStatePublisherUsingActor(2.seconds)(system)
      val probe1 = TestProbe()
      val probe2 = TestProbe()
      subscribable.subscribe(probe1.ref, QueueSubscriptionActor.Protocol.Closed)
      subscribable.subscribe(probe2.ref, QueueSubscriptionActor.Protocol.Closed)
      probe1.expectNoMsg(0.5.seconds)
      probe2.expectNoMsg(0.5.seconds)

      subscribable.close
      probe1.expectMsg(0.5.seconds, QueueSubscriptionActor.Protocol.Closed)
      probe2.expectMsg(0.5.seconds, QueueSubscriptionActor.Protocol.Closed)
    }
  }
}
