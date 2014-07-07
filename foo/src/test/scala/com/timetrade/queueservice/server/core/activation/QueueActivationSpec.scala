/** Copyright(c) 2013-2014 by TimeTrade Systems.  All Rights Reserved. */
package com.timetrade.queueservice.server.core.activation

import java.io.EOFException

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

import akka.actor.ActorRef

import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.joda.time.Instant

import org.scalacheck.Arbitrary.arbitrary

import org.scalactic.TypeCheckedTripleEquals

import org.scalatest.Matchers
import org.scalatest.concurrent.Futures
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.fixture.FunSpec
import org.scalatest.time.Millis
import org.scalatest.time.Seconds
import org.scalatest.time.Span

import com.timetrade.queueservice.server.core.ActorNaming
import com.timetrade.queueservice.server.core.Core
import com.timetrade.queueservice.server.core.CoreSettings
import com.timetrade.queueservice.server.core.DisabledQueuesCannotBeActivated
import com.timetrade.queueservice.server.core.definitioncrud.QueueDefinition
import com.timetrade.queueservice.server.core.definitioncrud.QueueDefinitionGeneration
import com.timetrade.queueservice.server.core.definitioncrud.QueueId
import com.timetrade.queueservice.server.core.publishing.ConsumableQueueStateUsingActor
import com.timetrade.queueservice.server.testtraits.ConfigurableActorSystems
import com.timetrade.queueservice.testtraits.TimedSuite

class QueueActivationSpec
  extends FunSpec
  with Matchers
  with Futures
  with ScalaFutures
  with TypeCheckedTripleEquals
  with ConfigurableActorSystems
  with TimedSuite
  with QueueDefinitionGeneration {

  object MockFetcher extends QueueStateFetcher {
    def fetch(definition: QueueDefinition, dt: DateTime = DateTime.now)
      : Future[QueueFetchedState]
      = { Future.successful(QueueFetchedState(QueueInitialContents(Seq()), dt, DateTimeZone.UTC)) }
  }

  // Configure the behavior of the "whenReady" calls below.
  implicit val defaultPatience = PatienceConfig(timeout =  Span(30, Seconds),
                                                interval = Span(1, Millis))
  def await[T](f: Future[T]): T = whenReady(f) { t => t }

  type FixtureParam = Core

  def withFixture(test: OneArgTest) = {

    implicit val system = actorSystemWithClustering()

    val core = new Core(settings = CoreSettings.defaultForTesting)
    try {
      QueueStateFetcher.mockWith(MockFetcher)
      await(core.becomeReady())
      test(core)
    } finally {
      QueueStateFetcher.unMock()
      core.shutdown()
      system.shutdown()
      system.awaitTermination()
    }
  }

  describe ("The QueueActivation facet of the Core") {

    // Read from a PublishingActor.
    def readFrom(publisher: ActorRef, version: Option[Instant] = None)
                (implicit core: Core)
    = new ConsumableQueueStateUsingActor(publisher, 30.seconds)(core.actorSystem)
        .read(version)

    it ("should activate queues correctly") { implicit core =>

      val id = arbitrary[QueueId].sample.get
      val definition = arbitrary[QueueDefinition].sample.get
      whenReady(core.activate(id, definition)) { activeQueue =>

        // Verify that the actor's path looks right.
        activeQueue.actor.path.toString should endWith (ActorNaming.actorUriPath(definition.location, id))

        // Verify that the active queues table looks right.
        val activeQueues = await(core.getActiveQueues)
        activeQueues should have size (1)
        activeQueues.head._1 should === (id)
        activeQueues.head._2.definition should === (definition)

        whenReady(readFrom(activeQueues(id).publisher)) { case (publishedState, v) =>
          publishedState.contents should be ('empty)
        }
      }
    }

    it ("should deactivate queues correctly") { implicit core =>

      val id = arbitrary[QueueId].sample.get
      val definition = arbitrary[QueueDefinition].sample.get
      val ac = await(core.activate(id, definition))

      whenReady(core.deactivate(id)) { _ =>

        // Verify that the active queues table looks right.
        val activeQueues = await(core.getActiveQueues)
        activeQueues should have size (0)

        // Verify that we can't read the state of the deactivated queue.
        intercept[EOFException] {
          Await.result(readFrom(ac.publisher), 1.seconds)
        }
      }
    }

    it ("should deactivate queues correctly when there are none active") { core =>
      whenReady(core.deactivateAllQueues()) { _ => }
    }

    it ("should stop all actors correctly") { core =>
      val id = arbitrary[QueueId].sample.get
      val definition = arbitrary[QueueDefinition].sample.get
      await(core.activate(id, definition))

      whenReady(core.stopAllTesaInstances) { _ =>

        // Verify that the active queues table looks right.
        val activeQueues = await(core.getActiveQueues)
        activeQueues should have size (0)
      }
    }

    it ("should allow idle queue actors to deactivate themselves") { implicit core =>
      val id = arbitrary[QueueId].sample.get
      val definition = arbitrary[QueueDefinition].sample.get
      await(core.activate(id, definition))

      val publisher = await(core.getActiveQueue(id)).get.publisher

      // Wait enough time so that the queue actor should deactivate itself.
      Thread.sleep(core.settings.queueIdleTimeout.toMillis + 2000)

      // Verify that the queue deactivates and that its output channel is closed.
      await(core.isActive(id)) should === (false)
      intercept[EOFException] {
        Await.result(readFrom(publisher), 5.seconds)
      }
    }

    it ("should keep-alive queue actors when data is being consumed") { implicit core =>

      val id = arbitrary[QueueId].sample.get
      val definition = arbitrary[QueueDefinition].sample.get
      await(core.activate(id, definition))

      val publisher = await(core.getActiveQueue(id)).get.publisher

      (1 to 5) foreach { _ =>
        await(readFrom(publisher))
        Thread.sleep(core.settings.queueIdleTimeout.toMillis - 5000)
        await(core.isActive(id)) should === (true)
      }
    }

    it ("should reject an attempt to activate a disabled queue") { core =>
      val id = arbitrary[QueueId].sample.get
      val definition = arbitrary[QueueDefinition].sample.get copy (disabled = true)
      intercept[DisabledQueuesCannotBeActivated] {
        await(core.activate(id, definition))
      }
    }

    it ("should be able to disable an active queue") { core =>
      val dao = core.queueDefinitionDao
      await(dao.becomeReady)

      val definition = arbitrary[QueueDefinition].sample.get
      val id = await(dao.create(definition))

      await(core.activate(id, definition))
      await(core.isActive(id)) should === (true)

      await(core.setEnablement(false, id))
      await(core.isActive(id)) should === (false)
    }

    it ("should not activate a disabled queue that is enabled") { core =>
      val dao = core.queueDefinitionDao
      await(dao.becomeReady)

      val definition = arbitrary[QueueDefinition].sample.get copy (disabled = true)
      val id = await(dao.create(definition))
      await(core.isActive(id)) should === (false)

      await(core.setEnablement(true, id))
      await(core.isActive(id)) should === (false)
    }
  }

  // TODO:
  //   Test
  //     - LocationActor/LicenseeActor/TesaInstanceActor incurs errors (inject by special message)
  //       - everything beneath it gets restarted ?
  //     - shutdown of the various scopes
  //       - queue
  //       - location
  //       - licensee
  //       - tesa
}
