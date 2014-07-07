package com.timetrade.queueservice.server.core.data.internalids

import org.scalacheck.Arbitrary
import org.scalacheck.Gen

/** ScalaCheck generators for some data types in this package.
  * Add more as needed.
  */
trait InternalIdGeneration {

  def genInternalId: Gen[Int] = Gen.chooseNum(1, 1000000)

  val genActivityInternalId = genInternalId map (ActivityInternalId(_))
  val genAppointmentInternalId = genInternalId map (AppointmentInternalId(_))
  val genCampaignInternalId = genInternalId map (CampaignInternalId(_))
  val genClientInternalId = genInternalId map (ClientInternalId(_))
  val genLicenseeInternalId = genInternalId map (LicenseeInternalId(_))
  val genLocationInternalId = genInternalId map (LocationInternalId(_))
  val genProgramInternalId = genInternalId map (ProgramInternalId(_))
  val genResourceInternalId = genInternalId map (ResourceInternalId(_))

  // Implicit declarations to create those Arbitrary type classes we need.
  implicit val arbActivityInternalId = Arbitrary(genActivityInternalId)
  implicit val arbCampaignInternalId = Arbitrary(genCampaignInternalId)
  implicit val arbLicenseeInternalId = Arbitrary(genLicenseeInternalId)
  implicit val arbLocationInternalId = Arbitrary(genLocationInternalId)
  implicit val arbProgramInternalId = Arbitrary(genProgramInternalId)
  implicit val arbResourceInternalId = Arbitrary(genResourceInternalId)
}

object InternalIdGeneration extends InternalIdGeneration

