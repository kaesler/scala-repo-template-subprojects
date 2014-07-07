package com.timetrade.queueservice.server.core.data

import org.scalacheck.Gen

/** ScalaCheck generators. */
trait TesaInstanceGeneration {
  val genTesaInstance: Gen[TesaInstance] = Gen.chooseNum(1, 100) map { n => TesaInstance(s"TESA-$n")}
}

object TesaInstanceGeneration extends TesaInstanceGeneration

