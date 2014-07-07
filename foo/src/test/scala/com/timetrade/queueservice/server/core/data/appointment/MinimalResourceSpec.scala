package com.timetrade.queueservice.server.core.data.appointment

import org.scalatest.FunSpec
import org.scalatest.Matchers
import com.timetrade.queueservice.testtraits.JsonConversionVerification
import com.timetrade.queueservice.testtraits.TimedSuite

class MinimalResourceSpec
  extends FunSpec
  with Matchers
  with JsonConversionVerification
  with TimedSuite {

  describe("MinimalResource JSON conversion") {

    val mock = MockTesa()

    it("should be reversible for resources") {
      reversesToJson(mock.resources.getMinimal())
      reversesToJson(mock.resources.gimmee())
    }

    it("should be reversible for resources without timezones") {
      reversesToJson(mock.resources.getMinimalWithoutTz())
    }
  }
}
