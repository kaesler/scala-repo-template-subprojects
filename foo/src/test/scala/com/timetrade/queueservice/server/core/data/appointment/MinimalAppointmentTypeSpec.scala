/** Copyright(c) 2013 by TimeTrade Systems.  All Rights Reserved. */
package com.timetrade.queueservice.server.core.data.appointment

import org.scalatest.FunSpec
import org.scalatest.Matchers
import com.timetrade.queueservice.testtraits.JsonConversionVerification
import com.timetrade.queueservice.testtraits.TimedSuite

class MinimalAppointmentTypeSpec
  extends FunSpec
  with Matchers
  with JsonConversionVerification
  with TimedSuite {

  describe("MinimalAppointmentType JSON conversion") {

    val mock = MockTesa()

    it("should be reversible for appointment-types") {
      reversesToJson(mock.activities.getMinimal())
      reversesToJson(mock.activities.gimmee())
    }
  }
}
