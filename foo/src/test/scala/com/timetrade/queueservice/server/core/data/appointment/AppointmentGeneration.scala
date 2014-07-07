package com.timetrade.queueservice.server.core.data.appointment

import scala.concurrent.duration._

import org.joda.time.Instant
import org.joda.time.DateTime
import org.joda.time.DateTimeZone

import org.scalacheck.Arbitrary
import org.scalacheck.Gen

import com.timetrade.queueservice.server.core.data.LocationGeneration
import com.timetrade.queueservice.server.core.data.externalids.ExternalIdGeneration
import com.timetrade.queueservice.server.core.data.internalids.InternalIdGeneration
import com.timetrade.queueservice.server.core.data.SetGeneration
import com.timetrade.queueservice.server.core.definitioncrud.QueueDefinition
import com.timetrade.queueservice.server.core.matching.MatchedPropertiesGeneration

/** ScalaCheck generators. */
trait AppointmentGeneration
  extends InternalIdGeneration
  with ExternalIdGeneration
  with LocationGeneration
  with MatchedPropertiesGeneration
  with SetGeneration {

  /** Returns a generator for sets of distinct ConfirmationNumbers. */
  def genSetOfNConfirmationNumbers(n: Int) = genSetOfN[ConfirmationNumber](n)

  /** Most general generator: randomizes all the the fields it makes sense to randomize.
    */
  val genAppointment: Gen[Appointment] = for {
    location <- genLocation
    minCampaign <- genMinimalCampaign
    minProgram <- genMinimalProgram
    minActivity <- genMinimalActivity
    minResource <- genMinimalResource
    minClient <- genMinimalClient
    confirmationNumber <- genConfirmationNumber
    internalId <- genAppointmentInternalId
    dtm <- genDateTime
  } yield Appointment(
    id = internalId,
      externalId = confirmationNumber,

    // Fields derived from genLocation:
    tesaInstanceId = location.licensee.tesaInstance,
    licensee = MinimalLicensee(externalId = location.licensee.externalId),
    location = MinimalLocation(location.internalId),

    // Fields that are used to match against a queue definition:
    campaign = minCampaign,
    appointmentTypeGroup = minProgram,
    appointmentType = minActivity,
    resource = minResource,

    dateTime = dtm,
    duration = AppointmentDuration(dtm),

    client = minClient,

    // Fields we don't randomize:
    comments = None,
    workflow = None,
    isCanceled = false,
    isCheckedIn = false,
    isConfirmed = false,
    isWalkin = false,
    lifecycleStatus = Scheduled,
    timestamps = AppointmentTimestamp(),
    queueId = None
  )

  /** Given an Appointment generator create a new generator that produces appointments
    * that match a given QueueDefinition.
    * @param defn the QueueDefinition
    * @param gen the generator
    * @return a new Gen[Appointment]
    */
  def genAppointmentsMatching(defn: QueueDefinition,
                              gen: Gen[Appointment] = genAppointment): Gen[Appointment] =
    for {
      appt <- gen
      matchables <- genPropertiesMatching(defn.matcher)
    } yield appt.copy (
      tesaInstanceId = defn.location.licensee.tesaInstance,
      licensee = MinimalLicensee(externalId = defn.location.licensee.externalId),
      location = MinimalLocation(defn.location.internalId),

      campaign = MinimalCampaign(matchables.campaign.get),
      appointmentTypeGroup = MinimalProgram(matchables.program.get),
      appointmentType = MinimalActivity(matchables.activity.get),
      resource = MinimalResource(matchables.resource.get)
    )


  /** Given an Appointment generator create a new generator that produces a sequence
    * of contiguous equal-duration appointments.
    * that match a given QueueDefinition.
    * @param start the start time of the first appointment
    * @param duration the duration of each appointment
    * @param count the number of appointments
    * @param the original generator
    * @return a new Gen[SeqAppointment]]
    */
  def genContiguousAppointments(begin: Instant,
                                duration: FiniteDuration,
                                count: Int,
                                gen: Gen[Appointment]): Gen[Seq[Appointment]] =
    for {
       appts <- Gen.listOfN(count, gen)
    } yield {
      (0 until count)
        .zip(appts)
        .map { case (i, appt) =>
          val slotStart = begin.plus(duration.toMillis * i)
          val slotEnd = slotStart.plus(duration.toMillis)
          appt.copy(
            dateTime = AppointmentDateTime(slotStart, slotEnd, DateTimeZone.getDefault)
          )
      }
    }

  /** Create a new generator that produces a sequence of contiguous equal-duration appointments
    * matching a given QueueDefinition.
    * @param defn the QueueDefinition
    * @param start the start time of the first appointment
    * @param duration the duration of each appointment
    * @param count the number of appointments
    * @return a new Gen[Seq[Appointment]]
    */
  def queueContentsGenerator(defn: QueueDefinition,
                             start: Instant,
                             duration: FiniteDuration,
                             count: Int)
  : Gen[Seq[Appointment]]
  = genContiguousAppointments(start,
                              duration,
                              count,
                              genAppointmentsMatching(defn, genAppointment))


  private val genMinimalCampaign: Gen[MinimalCampaign] =
    for { id <- genCampaignInternalId } yield MinimalCampaign(id)
  private val genMinimalProgram: Gen[MinimalProgram] =
    for { id <- genProgramInternalId } yield MinimalProgram(id)
  private val genMinimalActivity: Gen[MinimalActivity] =
    for { id <- genActivityInternalId } yield MinimalActivity(id)
  private val genMinimalResource: Gen[MinimalResource] =
    for { id <- genResourceInternalId } yield MinimalResource(id)
  private val genMinimalWorkflow: Gen[MinimalWorkflow] =
    for { id <- genWorkflowExternalId } yield MinimalWorkflow(id)

  // Generator for PersonName.
  private val genPersonName: Gen[PersonName] =
    for {
      first <- Gen.oneOf("Joan", "Norm", "Kevin", "Patricia", "Laurie", "Trish",
                         "Brian", "Marg", "Enid", "John", "Clare", "Tony")
    } yield PersonName(first)

  private val genMinimalClient: Gen[MinimalClient] = for {
    id <- genClientInternalId
    name <- genPersonName
  } yield MinimalClient(id, name)


  private val genConfirmationNumber: Gen[ConfirmationNumber] = for {
    // AAANNNAAA
    prefix <- Gen.listOfN(3, Gen.alphaUpperChar)
    middle <- Gen.listOfN(3, Gen.numChar)
    suffix <- Gen.listOfN(3, Gen.alphaUpperChar)
  } yield ConfirmationNumber(prefix.mkString ++ middle.mkString ++ suffix.mkString)

  implicit val arbConfirmationNumber = Arbitrary(genConfirmationNumber)

  private val genDateTime: Gen[AppointmentDateTime] = for {
    startOffsetIn5MinuteChunks <- Gen.oneOf( 1 to 288)
    durationIn5MinuteChunks <- Gen.oneOf( 1 to 24)

    nextHour = DateTime.now.withMinuteOfHour(0)
    start = nextHour.plusMinutes(5 * startOffsetIn5MinuteChunks)
    end = start.plusMinutes(5 * durationIn5MinuteChunks)
  } yield AppointmentDateTime(start.toInstant, end.toInstant, DateTimeZone.getDefault)
}

object AppointmentGeneration extends AppointmentGeneration
