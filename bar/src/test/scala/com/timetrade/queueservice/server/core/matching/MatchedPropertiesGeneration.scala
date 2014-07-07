package com.timetrade.queueservice.server.core.matching

import org.scalacheck.Arbitrary
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen

import com.timetrade.queueservice.server.core.data.internalids.InternalIdGeneration

/** ScalaCheck generators. */
trait MatchedPropertiesGeneration
 extends InternalIdGeneration {

  /** Given a Matcher, return a Gen that generates matching values
    */
  def genValueThatMatches[T : Arbitrary](matcher: Matcher[T]): Gen[T] = {
    for {
      t <- if (matcher.matchesAny) arbitrary[T] else Gen.oneOf(matcher.elements.toSeq)
    } yield t
  }

  /** Given an AppointmentPropertiesMatcher, create a Gen[MatchableProperties] that produces values
    * matched by that matcher.
    */
  def genPropertiesMatching(matcher: AppointmentPropertiesMatcher): Gen[MatchableProperties] =
    for {
      campaign <- genValueThatMatches(matcher.campaigns)
      program <- genValueThatMatches(matcher.programs)
      activity <- genValueThatMatches(matcher.activities)
      resource <- genValueThatMatches(matcher.resources)
    } yield MatchableProperties(campaign, program, activity, resource)

  val genMatchables: Gen[MatchableProperties] =
    for {
      campaign <- genCampaignInternalId
      program <- genProgramInternalId
      activity <- genActivityInternalId
      resource <- genResourceInternalId
    } yield MatchableProperties(campaign, program, activity, resource)


  implicit val arbMatchables = Arbitrary(genMatchables)
}

object MatchedPropertiesGeneration extends MatchedPropertiesGeneration

