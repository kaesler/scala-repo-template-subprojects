package com.timetrade.queueservice.server.core.data.appointment

import org.scalatest.FunSpec
import org.scalatest.Matchers
import com.timetrade.queueservice.testtraits.JsonConversionVerification
import com.timetrade.queueservice.testtraits.TimedSuite

class MinimalClientSpec
  extends FunSpec
  with Matchers
  with JsonConversionVerification
  with TimedSuite {

  describe("MinimalClient JSON conversion") {

    val mock = MockTesa()

    it("should be reversible for clients") {
      reversesToJson(mock.clients.getMinimal())
      reversesToJson(mock.clients.gimmee())
    }

    it("should be reversible for clients without timezones") {
      reversesToJson(mock.clients.getMinimalWithoutTz())
    }
  }
}
