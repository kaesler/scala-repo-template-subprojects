/** Copyright(c) 2013-2014 by TimeTrade Systems.  All Rights Reserved. */
package com.timetrade.queueservice.server.web.mockedtesa

import scala.concurrent.stm._

import org.joda.time.DateTimeZone

import com.timetrade.queueservice.server.api.appointmentevents.AppointmentAdded
import com.timetrade.queueservice.server.api.appointmentevents.AppointmentScheduled
import com.timetrade.queueservice.server.api.appointmentevents.AppointmentCancelled
import com.timetrade.queueservice.server.api.appointmentevents.AppointmentCheckIn
import com.timetrade.queueservice.server.api.appointmentevents.AppointmentComplete
import com.timetrade.queueservice.server.api.appointmentevents.AppointmentEventKind
import com.timetrade.queueservice.server.api.appointmentevents.AppointmentModified
import com.timetrade.queueservice.server.api.appointmentevents.AppointmentNoShow
import com.timetrade.queueservice.server.api.appointmentevents.AppointmentStart
import com.timetrade.queueservice.server.api.appointmentevents.AttendeeModified
import com.timetrade.queueservice.server.core.data.Location
import com.timetrade.queueservice.server.core.data.appointment.Appointment
import com.timetrade.queueservice.server.core.data.appointment.AppointmentLifecycleStatus
import com.timetrade.queueservice.server.core.data.appointment.Cancelled
import com.timetrade.queueservice.server.core.data.appointment.CheckedIn
import com.timetrade.queueservice.server.core.data.appointment.Completed
import com.timetrade.queueservice.server.core.data.appointment.ConfirmationNumber
import com.timetrade.queueservice.server.core.data.appointment.InProgress
import com.timetrade.queueservice.server.core.data.appointment.MinimalClient
import com.timetrade.queueservice.server.core.data.appointment.NoShow
import com.timetrade.queueservice.server.core.data.appointment.Scheduled
import com.timetrade.queueservice.server.core.data.appointment.Undefined
import com.timetrade.queueservice.server.core.matching.AppointmentPropertiesMatcher

/** Module for managing all mutable state of the mocked TESA instances. */
trait MutableState { self: MockedTesa =>

  // The time zone for all queues.
  private val tz = Ref[DateTimeZone](DateTimeZone.getDefault())

  // This allows us to present an empty queue easily: just create a Location here.
  private val locations = TSet[Location]()

  // We use a Map to enforce uniqueness of ConfirmationNumber
  private val appointments = TMap[ConfirmationNumber, Appointment]()

  /////////////////// Mutators //////////////////////////////////

  def clear() = atomic { implicit txn =>
    locations.clear()
    appointments.clear()
  }

  /** Adds a Location. Only needed to represent empty queues. */
  def addLocation(loc: Location) = atomic { implicit txn =>
    locations += loc
  }

  /** Adds an appointment, notifies the queue server and returns the added appointment. */
  def add(appt: Appointment): Appointment = {
    val result = addWithoutQueueServerNotification(appt)
    sendEvent(AppointmentAdded, appt)
    result
  }

  /** Adds an appointment and returns the added appointment, without notifying the queue server. */
  def addWithoutQueueServerNotification(appt: Appointment): Appointment = atomic { implicit txn =>
    // Fail if the confirmation number already exists.
    require (!appointmentExists(appt.externalId))

    appointments += (appt.externalId -> appt)

    appt
  }

  def scheduleAppointment(conf: ConfirmationNumber) = updateLifecycleStatus(conf, Scheduled)
  def checkInAppointment(conf: ConfirmationNumber) = updateLifecycleStatus(conf, CheckedIn)
  def startAppointment(conf: ConfirmationNumber) = updateLifecycleStatus(conf, InProgress)
  def completeAppointment(conf: ConfirmationNumber) = updateLifecycleStatus(conf, Completed)
  def cancelAppointment(conf: ConfirmationNumber) = updateLifecycleStatus(conf, Cancelled)
  def noshowAppointment(conf: ConfirmationNumber) = updateLifecycleStatus(conf, NoShow)

  /** Updates life cycle status for an appointment. */
  def updateLifecycleStatus(conf: ConfirmationNumber,
                            newStatus: AppointmentLifecycleStatus) = atomic { implicit txn =>
    require (appointmentExists(conf))

    val oldAppt = appointments(conf)
    if (newStatus != oldAppt.lifecycleStatus) {
      val newAppt = oldAppt.copy(lifecycleStatus = newStatus)
      appointments += (conf -> newAppt)
      sendEvent(eventKindForNewStatus(newStatus), newAppt)
    }
  }

  /** Modify an appointment. */
  def modify(appt: Appointment): Boolean = atomic { implicit txn =>
    require (appointmentExists(appt.externalId))

    val oldAppt = appointments(appt.externalId)
    val result = appt != oldAppt
    if (result) {
      appointments += (appt.externalId -> appt)
      sendEvent(AppointmentModified, appt)
    }

    result
  }

  /** Modify the attendee of an appointment. */
  def modifyAttendee(conf: ConfirmationNumber, attendee: MinimalClient) = atomic { implicit txn =>
    require (appointmentExists(conf))

    val oldAppt = appointments(conf)
    if (attendee != oldAppt.client) {
      val newAppt = oldAppt.copy(client = attendee)
      appointments += (newAppt.externalId -> newAppt)
      sendEvent(AttendeeModified, newAppt)
    }
  }

  /* Modify an appointment if it exists and return whether it was found.
   * Don't send an event to QueueServer.
   */
  def modifyIfFoundNoEvent(appt: Appointment): Boolean = atomic { implicit txn =>
    val confirmationNum = appt.externalId
    if (!appointments.contains(confirmationNum)) false
    else {
      appointments += (confirmationNum -> appt)
      true
    }
  }


  /////////////////// Accessors //////////////////////////////////

  def timeZone = atomic { implicit txn => tz() }

  def locationExists(location: Location): Boolean = atomic { implicit txn =>
    (locations contains location) ||
    (appointments.values exists { _.loc == location })
  }

  def appointmentExists(id: ConfirmationNumber): Boolean = atomic { implicit txn =>
    appointments contains id
  }

  /** Find appointments matching the supplied location and properties matcher.
    *
    * @param location the Location to match against
    * @param matcher the properties matcher to match against.
    * @return the collection of Appoinments found to match
    */
  def findAppointments(
    location: Location,
    matcher: AppointmentPropertiesMatcher
  ): Seq[Appointment]

  = atomic { implicit txn =>
    appointments
      .values
      .filter { appt =>
        appt.loc == location &&
        matcher.isCompatibleWith(appt.matchables)
      }.toSeq
  }

  private def eventKindForNewStatus(status: AppointmentLifecycleStatus)
  : AppointmentEventKind
  = status match {
    case Undefined => ???
    case Scheduled => AppointmentAdded
    case CheckedIn => AppointmentCheckIn
    case InProgress => AppointmentStart
    case Completed => AppointmentComplete
    case Cancelled => AppointmentCancelled
    case NoShow  => AppointmentNoShow
  }
}
