package com.timetrade.queueservice.server.core.definitioncrud

import scala.concurrent.Future

import org.joda.time.DateTime
import org.joda.time.DateTimeZone

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
import com.timetrade.queueservice.server.core.activation.QueueFetchedState
import com.timetrade.queueservice.server.core.activation.QueueInitialContents
import com.timetrade.queueservice.server.core.activation.QueueStateFetcher
import com.timetrade.queueservice.server.core.data.Location
import com.timetrade.queueservice.server.testtraits.ConfigurableActorSystems
import com.timetrade.queueservice.testtraits.TimedSuite

class QueueDefinitionCrudSpec
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

    implicit val system = defaultActorSystem()

    val core = new Core(settings = CoreSettings.defaultForTesting)
    try {
      await(core.becomeReady())
      QueueStateFetcher.mockWith(MockFetcher)
      test(core)
    } finally {
      QueueStateFetcher.unMock()
      core.shutdown()
      system.shutdown()
    }
  }

  describe ("The QueueDefinitionCrud aspect of the core") {

    it ("should deactivate active queues when their definitions are bulk-deleted") { core =>
      // Create some definitions at multiple locations
      val Count = 10
      val locations = (genSetOfN[Location](Count)).sample.get
      locations.size should === (Count)
      val definition = genQueueDefinition.sample.get
      val creationPairs = await(core.createDefinitions(locations, definition))
      val queueIds = creationPairs map { case (loc, trye) =>
        trye.isSuccess should be (true)
        trye.get
      }

      // Activate all the queues created.
      queueIds foreach { id => await(core.activate(id, definition)) }
      queueIds foreach { id => await(core.isActive(id)) should be (true)}

      // Now delete all the queue definitions.
      await(core.deleteDefinitions(locations, definition.externalId))

      // Verify that all the queues are inactive.
      queueIds foreach { id => await(core.isActive(id)) should be (false)}
    }

    it ("should deactivate an active queue when its definition is deleted") { core =>
      val definition = arbitrary[QueueDefinition].sample.get
      val id = await(core.createDefinition(definition))
      await(core.activate(id, definition))
      await(core.isActive(id)) should be (true)

      whenReady(core.deleteDefinition(id)) { _ =>
        await(core.isActive(id)) should be (false)
      }
    }

    it ("should re-activate active queues when their definitions are bulk-updated") { core =>
      // Create some definitions at multiple locations
      val Count = 10
      val locations = (genSetOfN[Location](Count)).sample.get
      locations.size should === (Count)
      val definition = genQueueDefinition.sample.get
      val creationPairs = await(core.createDefinitions(locations, definition))
      val queueIds = creationPairs map { case (loc, trye) =>
        trye.isSuccess should be (true)
        trye.get
      }

      // Activate all the queues created and capture their ActiveQueue instances.
      queueIds foreach { id => await(core.activate(id, definition)) }
      queueIds foreach { id => await(core.isActive(id)) should be (true) }
      val preUpdateActiveQueues =
        queueIds
          .map { id => (id, core.getActiveQueue(id)) }
          .toMap

      // Modify just the label of the definition.
      val newDefinition = definition.copy(label = Some(definition.label.getOrElse("") + "xxx"))
      whenReady(core.upsertDefinitions(locations, definition.externalId, newDefinition)) { pairs =>
        pairs foreach { case (loc, trye) =>
          trye.isSuccess should be (true)
        }
      }

      // Capture the ActiveQueue records for the queues after update.
      val postUpdateActiveQueues =
        queueIds
          .map { id => (id, core.getActiveQueue(id)) }
          .toMap
      preUpdateActiveQueues.keySet should === (postUpdateActiveQueues.keySet)

      // Verify that they all changed indicating restart.
      queueIds foreach { id =>
        preUpdateActiveQueues(id) should !== (postUpdateActiveQueues(id))
      }
    }

    it ("should re-activate an active queue when its definition is updated") { core =>
      // Create a queue and activate it.
      val definition = arbitrary[QueueDefinition].sample.get
      val id = await(core.createDefinition(definition))
      val originalActiveQueue = await(core.activate(id, definition))
      await(core.isActive(id)) should be (true)

      // Update the queue definition.
      val newDefinition = definition.copy(
        label = Some(definition.label.getOrElse("") + "XXX")
      )
      whenReady(core.updateDefinition(id, newDefinition)) { _ =>

        await(core.isActive(id)) should be (true)

        // The update should have restart the queue and that should change the
        // contents of its ActiveQueue (e.g. its ActorRef).
        await(core.getActiveQueue(id)).get should !== (originalActiveQueue)
      }
    }
  }
}
