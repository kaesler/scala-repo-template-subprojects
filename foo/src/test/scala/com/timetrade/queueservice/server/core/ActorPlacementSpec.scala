package com.timetrade.queueservice.server.core

import scala.concurrent.Future

import akka.actor.ActorRef
import akka.actor.Address

import org.joda.time.DateTime
import org.joda.time.DateTimeZone

import org.scalacheck.Arbitrary.arbitrary

import org.scalactic.TypeCheckedTripleEquals

import org.scalatest.FunSpec
import org.scalatest.Matchers
import org.scalatest.concurrent.Futures
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.Millis
import org.scalatest.time.Seconds
import org.scalatest.time.Span

import com.timetrade.queueservice.server.core.activation.ActiveQueue
import com.timetrade.queueservice.server.core.activation.ActorPlacementStrategy
import com.timetrade.queueservice.server.core.activation.LocationActorRoundRobin
import com.timetrade.queueservice.server.core.activation.LocationActorToLeastLoaded
import com.timetrade.queueservice.server.core.activation.LocationActorToOther
import com.timetrade.queueservice.server.core.activation.QueueFetchedState
import com.timetrade.queueservice.server.core.activation.QueueInitialContents
import com.timetrade.queueservice.server.core.activation.QueueStateFetcher
import com.timetrade.queueservice.server.core.definitioncrud.QueueDefinition
import com.timetrade.queueservice.server.core.definitioncrud.QueueDefinitionGeneration
import com.timetrade.queueservice.server.core.definitioncrud.QueueId
import com.timetrade.queueservice.server.testtags.CrashesJenkins
import com.timetrade.queueservice.server.testtraits.ConfigurableActorSystems
import com.timetrade.queueservice.testtraits.TimedSuite

/** Tests for actor placement strategies in a cluster.
  * TODO: Currently disabled because it crashes the JVM on Jenkins with OutOfMemoryError
  */
class ActorPlacementSpec
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

  def withCluster(clusterSize: Int, strategy: ActorPlacementStrategy)(testBody: Core => Unit)  = {

    // Create cluster.
    val systems = actorSystemsWithClustering(getClass.getSimpleName, clusterSize)

    // Start a core for each cluster member.
    val cores = systems map { implicit system =>
      new Core(
        settings = CoreSettings.defaultForTesting.copy(actorPlacementStrategy = strategy))
    }

    try {
      QueueStateFetcher.mockWith(MockFetcher)
      cores foreach { core => await(core.becomeReady())}
      testBody(cores.head)
    } finally {
      QueueStateFetcher.unMock()
      // Only call shutdown on one Core instance for cleanest result.
      cores.head.shutdown()
      cores foreach { core =>
        core.actorSystem.shutdown()
        core.actorSystem.awaitTermination()
      }
    }
  }

  def rootActorRef(core: Core): Future[ActorRef] = {
    import core.actorSystem.dispatcher
    core.askActor[ActorRef](core.rootActor, RootActor.Protocol.SendActorRef,
      "locating actual RootActor using SendActorRef")
  }

  def rootActorAddress(core: Core): Future[Address] = {
    import core.actorSystem.dispatcher
    core.askActor[ActorRef](core.rootActor, RootActor.Protocol.SendActorRef,
      "locating actual RootActor using SendActorRef")
     .map { _.path.address }
  }

  describe ("A clustered Core") {

    def activateNewQueue(core: Core): Future[ActiveQueue] =
      core.activate(arbitrary[QueueId].sample.get,
                    arbitrary[QueueDefinition].sample.get)

    it ("should place actors correctly when configured to use the LocationActorToOther strategy", CrashesJenkins) {
      withCluster(2, LocationActorToOther) { core =>

        // Record the RootActor's address (note: it may be a local address)
        val rootActorAddr = await(rootActorAddress(core))

        whenReady(activateNewQueue(core)) { activeQueue =>

          // Verify the actor's address.
          println(s"Queue deployed to ${activeQueue.parent.path.address}")
          activeQueue.parent.path.address should !== (rootActorAddr)
        }
      }
    }

    it ("should place actors correctly when configured to use the LocationActorRoundRobin strategy", CrashesJenkins) {
      withCluster(3, LocationActorRoundRobin) { core =>

        val activeQueue1 = await(activateNewQueue(core))
        val activeQueue2 = await(activateNewQueue(core))
        val activeQueue3 = await(activateNewQueue(core))

        // Verify that successive activations occurred on different addresses.
        activeQueue1.parent.path.address should !== (activeQueue2.parent.path.address)
        activeQueue2.parent.path.address should !== (activeQueue3.parent.path.address)
        activeQueue1.parent.path.address should !== (activeQueue3.parent.path.address)
      }
    }

    it ("should place actors correctly when configured to use the LocationActorToLeastLoaded strategy", CrashesJenkins) {
      withCluster(3, LocationActorToLeastLoaded) { core =>
        val activeQueue1 = await(activateNewQueue(core))
        val activeQueue2 = await(activateNewQueue(core))
        val activeQueue3 = await(activateNewQueue(core))

        // Not much verification we can do when this runs all cluster members in the same JVM.
        // So for now it is a sanity test.
      }
    }
  }
}
