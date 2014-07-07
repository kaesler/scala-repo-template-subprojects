package com.timetrade.queueservice.server.web.mockedtesa

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import akka.actor.ActorSystem
import akka.event.Logging
import spray.http.Uri
import com.typesafe.config.ConfigFactory
import com.timetrade.queueservice.server.testtraits.ConfigurableActorSystems


/** Mock for one or more TESA instances.
  * @param queueServerRestUri  URI to REST interface on the associated queue server.
  */
class MockedTesa(val queueServerRestUri: Uri)
  extends MutableState
  with Populating
  with HttpServer
  with ConfigurableActorSystems
  with SendingAppointmentEvents {

  // Start its own ActorSystem and HTTP service.
  protected implicit val actorSystem = defaultActorSystem()
  protected val log = Logging.getLogger(actorSystem, classOf[MockedTesa])
  Await.result(startHttp, 30.seconds)

  // Shutdown
  def shutdown() = {
    //stopHttp()
    actorSystem.shutdown()
    actorSystem.awaitTermination()
    clear()
  }
}
