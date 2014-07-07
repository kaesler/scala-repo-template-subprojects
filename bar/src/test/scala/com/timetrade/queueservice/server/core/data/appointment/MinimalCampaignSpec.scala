package com.timetrade.queueservice.server.core.data.appointment

import org.scalatest.FunSpec
import org.scalatest.Matchers
import com.timetrade.queueservice.testtraits.JsonConversionVerification
import com.timetrade.queueservice.testtraits.TimedSuite

class MinimalCampaignSpec
  extends FunSpec
  with Matchers
  with JsonConversionVerification
  with TimedSuite {

  describe("MinimalCampaign JSON conversion") {

    val mock = MockTesa()

    it("should be reversible for campaigns") {
      reversesToJson(mock.campaigns.getMinimal())
      reversesToJson(mock.campaigns.gimmee())
    }

    it("should be reversible for campaigns without timezones") {
      reversesToJson(mock.campaigns.getMinimalWithoutTz())
    }
  }
}
