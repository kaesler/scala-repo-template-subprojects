package com.timetrade.queueservice.server.web.mockedtesa

import scala.concurrent.duration._

import org.joda.time.Instant

import com.timetrade.queueservice.server.core.data.appointment.Appointment
import com.timetrade.queueservice.server.core.data.appointment.AppointmentGeneration
import com.timetrade.queueservice.server.core.definitioncrud.QueueDefinition


/** Methods for populating the Mocked TESA with appointments, in various ways. */
trait Populating
  extends AppointmentGeneration { self: MockedTesa =>

  /** Generate and add contiguous appointments targeted for a specific queue.
    * No appointments events are sent to the queue server.
    *
    * @param qdef the QueueDefinition to target
    * @param start the start time of the first appointment
    * @param duration the duration of each appointment
    * @param count how many appointments to generate
    * @param true if the queue server should be notified of the appointments
    * @return the generated appointments
    */
  def addAppointmentsFor(qdef: QueueDefinition,
                         start: Instant,
                         duration: FiniteDuration,
                         count: Int,
                         sendEvent: Boolean = false): Seq[Appointment] =
    queueContentsGenerator(qdef, start, 5.minutes, count)
      .sample
      .get
      .map { a => if (sendEvent) add(a) else addWithoutQueueServerNotification(a) }
  }
