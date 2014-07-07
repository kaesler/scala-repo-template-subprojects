package com.timetrade.queueservice.server.core.data.externalids

import org.scalacheck.Arbitrary
import org.scalacheck.Gen

/** ScalaCheck generators. */
trait ExternalIdGeneration {

  // For now just our external ids are stylized strings.
  def genExternalId(prefix: String): Gen[String] =
    Gen.chooseNum(1, 1000000) map { n => s"$prefix-$n"}

  val genLicenseeExternalId = genExternalId("Licensee") map (LicenseeExternalId(_))
  val genWorkflowExternalId = genExternalId("Workflow") map (WorkflowExternalId(_))
}
object ExternalIdGeneration extends ExternalIdGeneration

