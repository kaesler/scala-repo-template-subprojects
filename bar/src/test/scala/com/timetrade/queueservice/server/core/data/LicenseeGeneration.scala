package com.timetrade.queueservice.server.core.data

import org.scalacheck.Gen

import com.timetrade.queueservice.server.core.data.externalids.ExternalIdGeneration

/** ScalaCheck generators. */
trait LicenseeGeneration
  extends TesaInstanceGeneration
  with ExternalIdGeneration {

  val genLicensee: Gen[Licensee] = for {
    t <- genTesaInstance
    id <- genLicenseeExternalId
  } yield Licensee(t, id)

  def genLicensee(tesaInstance: TesaInstance): Gen[Licensee] = for {
    id <- genLicenseeExternalId
  } yield Licensee(tesaInstance, id)
}

object LicenseeGeneration extends LicenseeGeneration

