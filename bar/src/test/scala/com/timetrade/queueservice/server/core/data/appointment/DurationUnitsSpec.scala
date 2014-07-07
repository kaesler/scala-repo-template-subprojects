/** Copyright(c) 2013 by TimeTrade Systems.  All Rights Reserved. */
package com.timetrade.queueservice.server.core.data.appointment

import org.scalatest.FunSpec
import org.scalatest.Matchers
import com.timetrade.queueservice.testtraits.JsonConversionVerification
import com.timetrade.queueservice.testtraits.TimedSuite

class DurationUnitsSpec
  extends FunSpec
  with Matchers
  with JsonConversionVerification
  with TimedSuite {

  describe("DurationUnits JSON conversion") {

    it ("should be reversible for all DurationUnits") {
      reversesToJson(MINUTES.asInstanceOf[DurationUnits])
      reversesToJson(HOURS.asInstanceOf[DurationUnits])
      reversesToJson(DAYS.asInstanceOf[DurationUnits])
    }
  }
}
