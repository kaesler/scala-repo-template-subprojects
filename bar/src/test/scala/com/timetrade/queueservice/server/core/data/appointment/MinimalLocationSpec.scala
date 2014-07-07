/** Copyright(c) 2013 by TimeTrade Systems.  All Rights Reserved. */
package com.timetrade.queueservice.server.core.data.appointment

import org.scalatest.FunSpec
import org.scalatest.Matchers
import com.timetrade.queueservice.testtraits.JsonConversionVerification
import com.timetrade.queueservice.testtraits.TimedSuite

class MinimalLocationSpec
  extends FunSpec
  with Matchers
  with JsonConversionVerification
  with TimedSuite {

  describe("MinimalLocation JSON conversion") {

    val mock = MockTesa()

    it("should be reversible for locations") {
      reversesToJson(mock.locations.getMinimal())
      reversesToJson(mock.locations.gimmee())
    }

    it("should be reversible for locations without timezones") {
      reversesToJson(mock.locations.getMinimalWithoutTz())
    }
  }
}
