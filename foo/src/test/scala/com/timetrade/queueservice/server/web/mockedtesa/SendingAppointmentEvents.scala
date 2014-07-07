package com.timetrade.queueservice.server.web.mockedtesa

import scala.concurrent.Future

import spray.http.ContentType
import spray.httpx.RequestBuilding
import spray.httpx.marshalling.Marshaller

import com.timetrade.queueservice.netutils.HttpClient
import com.timetrade.queueservice.netutils.NetUtils
import com.timetrade.queueservice.server.api.JsonContentUtils
import com.timetrade.queueservice.server.api.appointmentevents.AppointmentEventKind
import com.timetrade.queueservice.server.api.appointmentevents.CustomMediaTypes
import com.timetrade.queueservice.server.core.data.appointment.Appointment

/** Module for sending events to the Queue Server.
  */
trait SendingAppointmentEvents
  extends RequestBuilding { self: MockedTesa =>

  import actorSystem.dispatcher

  private implicit val marshaller = {
    val mediaType =
      CustomMediaTypes.`application/vnd.timetrade.queue-server.appointment-events+json`

    Marshaller.of[Appointment](mediaType) { (appt, contentType, ctx) =>
      ctx.marshalTo(JsonContentUtils.jsonHttpEntityFor(appt, mediaType))
    }
  }

  private lazy val client = new HttpClient(queueServerRestUri.authority.host.address,
                                           NetUtils.impliedPort(queueServerRestUri),
                                           queueServerRestUri.scheme == "https")

  /** Send an event to the queue server, normally as a side effect of creating or updating
    * the mocked TESA state.
    */
  protected def sendEvent(kind: AppointmentEventKind, appt: Appointment): Future[Unit] = {
    // POST Appointment-as-json to /{tesaInstance}/{licenseeId}/appointment-events/{eventKind}
    val path =
      s"/${appt.tesaInstanceId.s}/${appt.licensee.externalId.s}/appointment-events/${kind.name}"
    val request = Post(path, appt)

    client.apply(request) map { response =>
      require (response.status.isSuccess, "POST of appointment event to Queue Server must succeed")
      ()
    }
  }
}
