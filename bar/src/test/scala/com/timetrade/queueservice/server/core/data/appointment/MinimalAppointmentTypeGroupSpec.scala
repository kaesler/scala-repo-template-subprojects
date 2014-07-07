package com.timetrade.queueservice.server.core.data.appointment

import org.scalatest.FunSpec
import org.scalatest.Matchers
import com.timetrade.queueservice.testtraits.JsonConversionVerification
import com.timetrade.queueservice.testtraits.TimedSuite

class MinimalAppointmentTypeGroupSpec
  extends FunSpec
  with Matchers
  with JsonConversionVerification
  with TimedSuite {

  describe("MinimalAppointmentTypeGroup JSON conversion") {

    val mock = MockTesa()

    it("should be reversible for appointment-type-groups") {
      reversesToJson(mock.programs.getMinimal())
      reversesToJson(mock.programs.gimmee())
    }
  }
}
