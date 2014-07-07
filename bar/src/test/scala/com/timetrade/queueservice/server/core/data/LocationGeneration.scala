package com.timetrade.queueservice.server.core.data

import org.scalacheck.Gen

import com.timetrade.queueservice.server.core.data.internalids.InternalIdGeneration

/** ScalaCheck generators. */
trait LocationGeneration
  extends LicenseeGeneration
  with InternalIdGeneration {

  val genLocation: Gen[Location] = for {
    lic <- genLicensee
    id <- genLocationInternalId
  } yield Location(lic, id)

 def genLocation(tesaInstance: TesaInstance): Gen[Location] = for {
    lic <- genLicensee(tesaInstance)
    id <- genLocationInternalId
  } yield Location(lic, id)
}

object LocationGeneration extends LocationGeneration

