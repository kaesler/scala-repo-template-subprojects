package com.timetrade.queueservice.server.core.data.appointment

import scala.io.Source

import spray.json._

import org.joda.time.DateTimeZone
import org.scalatest.FunSpec
import org.scalatest.Matchers

import com.timetrade.queueservice.jsonutils.DateTimeZoneJsonProtocol.DateTimeZoneJsonFormat
import com.timetrade.queueservice.testtraits.JsonConversionVerification
import com.timetrade.queueservice.testtraits.TimedSuite

class TesaAppointmentSpec
  extends FunSpec
  with Matchers
  with JsonConversionVerification
  with TimedSuite {

  /** Helium returns a payload that is an array of JSON'd Appointment objects. */
  case class Contents(
    tz: DateTimeZone,
    queuedAppointments: Seq[Appointment])

  object Contents extends DefaultJsonProtocol {
    implicit val _ = jsonFormat2(Contents.this.apply)
  }

  case class Envelope(
    initialContents: Contents)

  object Envelope extends DefaultJsonProtocol {
    implicit val _ = jsonFormat1(Envelope.this.apply)
  }

  describe("TesaAppointment JSON conversion") {

    val mock = MockTesa()

    it("should be reversible for appointments at random location") {
      reversesToJson(mock.appointments.gimmee())
    }

    it("should be reversible for appointments at a specified (random) location") {
      reversesToJson(mock.appointments.gimmee(location = Some(mock.locations.gimmee())))
    }

    it("should be reversible for lists (of random length) of appointments") {
      reversesToJson(mock.appointments.gimmeeMultiples())
    }

    // Temporarily disabled until appointments arrive with a tesaInstance field.
    it("should be capable of unmarshaling JSON sent by Helium for a list of appointments") {
      // Note: This assumes the location of the JSON-containing test resource, in the workspace.
      val jsonFile = new java.io.File(".").getCanonicalPath() + "/src/test/resources/AppointmentSpec.json";
      val json = Source.fromFile(new java.io.File(jsonFile)).mkString

      // We're not validating the details, just that we're copacetic with Helium's payloads.
      val payload = json.parseJson.convertTo[Envelope]
    }
  }
}
