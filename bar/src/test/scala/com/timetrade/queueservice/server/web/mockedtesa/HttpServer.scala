package com.timetrade.queueservice.server.web.mockedtesa

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import akka.actor.Props
import akka.io.IO
import akka.io.Tcp.Bound
import akka.pattern.ask
import akka.util.Timeout

import spray.can.Http
import spray.http.HttpEntity
import spray.http.HttpResponse
import spray.http.StatusCodes
import spray.http.Uri
import spray.http.Uri.Path
import spray.httpx.marshalling.ToResponseMarshallable.isMarshallable
import spray.routing.Directive.pimpApply
import spray.routing.Directives
import spray.routing.HttpServiceActor
import spray.routing.Route

import org.joda.time.Instant

import com.timetrade.queueservice.server.api.JsonContentUtils
import com.timetrade.queueservice.server.api.appointmentevents.Unmarshalling.AppointmentEventUnmarshaller
import com.timetrade.queueservice.server.core.activation.DefaultQueueStateFetcher
import com.timetrade.queueservice.server.core.activation.DefaultQueueStateFetcher.Contents
import com.timetrade.queueservice.server.core.activation.DefaultQueueStateFetcher.Envelope
import com.timetrade.queueservice.server.core.data.Licensee
import com.timetrade.queueservice.server.core.data.Location
import com.timetrade.queueservice.server.core.data.TesaInstance
import com.timetrade.queueservice.server.core.data.appointment.Appointment
import com.timetrade.queueservice.server.core.data.externalids.LicenseeExternalId
import com.timetrade.queueservice.server.core.data.internalids.ActivityInternalId
import com.timetrade.queueservice.server.core.data.internalids.CampaignInternalId
import com.timetrade.queueservice.server.core.data.internalids.LocationInternalId
import com.timetrade.queueservice.server.core.data.internalids.ProgramInternalId
import com.timetrade.queueservice.server.core.data.internalids.ResourceInternalId
import com.timetrade.queueservice.server.core.definitioncrud.TesaRestUrl
import com.timetrade.queueservice.server.core.matching.AppointmentPropertiesMatcher
import com.timetrade.queueservice.server.core.matching.Matcher
import com.timetrade.queueservice.server.testutils.Utils

/** Module for exposing a REST API that the Queue server uses for
  *   - initial fetch
  *   - appointment update.
  */
trait HttpServer  extends Directives {  self: MockedTesa =>

  import actorSystem.dispatcher

  private val (hostname, port) = Utils.temporaryServerHostnameAndPort()

  implicit private val AskTimeout = Timeout(30.seconds)

  private def baseUri = Uri.from(scheme = "http", host = hostname, port = port)

  def tesaRestUri(tesaInstance: TesaInstance): TesaRestUrl =
    TesaRestUrl(
      baseUri.withPath(Path(s"/${tesaInstance.s}")))

  private val route: Route = {
    import com.timetrade.queueservice.server.api.appointmentevents.Unmarshalling._

    // Schema:
    //    GET  /status
    //    GET  /{tesaInstance}/{licenseeId}/queued-appointments?PARAMS
    //    POST /{tesaInstance}/{licenseeId}/queued-appointments/{confirmation-number}

    path("status") {
      get {
        complete(getStatus)
      }
    }~
    pathPrefix(Segment) { tesaInstance =>

      pathPrefix(Segment) { licensee =>

        path("queued-appointments") {
          get {
            parameterMultiMap { params =>
              Try(parseGETParams(params, tesaInstance, licensee)) match {
                case Success((location, _, matcher)) =>
                  complete(getQueueContentsResponse(location, matcher))

                case Failure(t) =>
                  t.printStackTrace()
                  complete(StatusCodes.BadRequest)
              }
            }
          }
        } ~
        path("queued-appointments" / Segment) { confirmationNumber =>
          post {
            entity(as[Appointment]) { appt =>
              if (confirmationNumber != appt.externalId.s)
                // Don't allow the changing of ConfirmationNumber/externalId
                complete(StatusCodes.BadRequest)
              else
                complete(updateAppointmentResponse(appt))
            }
          }
        }
      }
    }
  }

  /** Start the server. */
  def startHttp(): Future[Unit] = {
    // Create our handler actor which replies to incoming HttpRequests
    val handler = actorSystem.actorOf(HttpServer.props(route))

    // Tell the server where to listen.
    val f =
      (IO(Http) ? Http.Bind(handler, interface = "0.0.0.0", port = port)).mapTo[Bound]
    log.debug("Web server start requested")
    f map { bound =>
      log.info("Web server bound at {}", bound.localAddress.toString())
      ()
    }
  }

  /** Stop the server. */
  def stopHttp(): Unit = {
    log.debug("Web server stop requested")
    IO(Http) ! Http.Unbind
  }

  private def getStatus: HttpResponse = {
    HttpResponse(status = StatusCodes.OK,
                 entity = HttpEntity("Mocked TESA is running"))
  }

  private def parseGETParams(params: Map[String, List[String]], tesaInstance: String, licensee: String)
  : (Location, Instant, AppointmentPropertiesMatcher) = {

    val start = Instant.parse(params("start[]").head)

    val location =
      Location(
        Licensee(
          TesaInstance(tesaInstance),
          LicenseeExternalId(licensee)),
        LocationInternalId(params("locationId[]").head.toInt))

    val matcher =
      AppointmentPropertiesMatcher(
        campaigns =
          Matcher(
            params
              .getOrElse("campaignId[]", List())
              .map(s => CampaignInternalId(s.toInt))
              .toSet),
        programs =
          Matcher(
            params
              .getOrElse("appointmentTypeGroupId[]", List())
              .map(s => ProgramInternalId(s.toInt))
              .toSet),
        activities =
          Matcher(
            params
              .getOrElse("appointmentTypeId[]", List())
              .map(s => ActivityInternalId(s.toInt))
              .toSet),
        resources =
          Matcher(
            params
              .getOrElse("resourceId[]", List())
              .map(s => ResourceInternalId(s.toInt))
              .toSet))

    (location, start, matcher)
  }

  private def getQueueContentsResponse(
    location: Location,
    matcher: AppointmentPropertiesMatcher
  ): HttpResponse = {

    // If the location doesn't exist in our data return NOT_FOUND.
    if (!locationExists(location)) HttpResponse(StatusCodes.NotFound)
    else {
      val envelope = Envelope(Contents(timeZone.toString, findAppointments(location, matcher)))
      HttpResponse()
        .withEntity(
          JsonContentUtils.jsonHttpEntityFor(envelope, DefaultQueueStateFetcher.mediaType))
    }
  }

  private def updateAppointmentResponse(appt: Appointment): HttpResponse = {
    if (!modifyIfFoundNoEvent(appt)) HttpResponse(StatusCodes.NotFound)
    else HttpResponse()
  }
}

/** Companion object. */
object HttpServer {

  private class ServiceActor(route: Route) extends HttpServiceActor {
    // This actor only runs our route.
    def receive: Receive = runRoute(route)
  }

  // Actpr properties factory.
  private def props(route: Route) = Props(classOf[ServiceActor], route)
}
