package com.timetrade.queueservice.server.core.matching

import org.scalatest.FunSpec
import org.scalatest.Matchers

import com.timetrade.queueservice.testtraits.JsonConversionVerification
import com.timetrade.queueservice.testtraits.TimedSuite

class MatcherJsonConversionSpec
  extends FunSpec
  with Matchers
  with JsonConversionVerification
  with TimedSuite {

  import Matcher._
  describe("Matcher[_] JSON conversion") {

    it ("should be reversible for non-any values") {
      reversesToJson(Matcher("a", "b", "c"))
    }

    it ("should be reversible for \"any\" values") {
      reversesToJson(Matcher.any[Int])
    }
  }
}
