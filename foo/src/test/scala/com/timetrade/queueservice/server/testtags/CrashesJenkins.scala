package com.timetrade.queueservice.server.testtags

import org.scalatest.Tag

/** Use to tag a test that is a good test and should be run by devlopers,
  * but that crashes Jenkins until we figure out why.
  */
object CrashesJenkins extends Tag("com.timetrade.queueservice.server.testtags.CrashesJenkins")
