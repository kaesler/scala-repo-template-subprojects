/** Copyright(c) 2013-2014 by TimeTrade Systems.  All Rights Reserved. */
package com.timetrade.queueservice.server.core.definitioncrud

import spray.http.Uri

import org.scalacheck.Arbitrary
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen

import com.timetrade.queueservice.server.core.data.Location
import com.timetrade.queueservice.server.core.data.LocationGeneration
import com.timetrade.queueservice.server.core.data.SetGeneration
import com.timetrade.queueservice.server.core.data.TesaInstance
import com.timetrade.queueservice.server.core.data.externalids.ExternalIdGeneration
import com.timetrade.queueservice.server.core.data.externalids.QueueExternalId
import com.timetrade.queueservice.server.core.data.internalids.ActivityInternalId
import com.timetrade.queueservice.server.core.data.internalids.CampaignInternalId
import com.timetrade.queueservice.server.core.data.internalids.ProgramInternalId
import com.timetrade.queueservice.server.core.data.internalids.ResourceInternalId
import com.timetrade.queueservice.server.core.matching.AppointmentPropertiesMatcher
import com.timetrade.queueservice.server.core.matching.Matcher

/** ScalaCheck generators. */
trait QueueDefinitionGeneration
  extends ExternalIdGeneration
  with LocationGeneration
  with SetGeneration {

  val QueueExternalIdPrefix = "Queue"
  val genQueueExternalId: Gen[QueueExternalId] = for {
    s <- genExternalId(QueueExternalIdPrefix)
  } yield QueueExternalId(s)

  val genQueueRules: Gen[QueueRules] = Gen.const(QueueRules())  // For now always the default

  // This is a method which produces a Gen[_] not a Gen[_] per se..
  def genMatcher[T : Arbitrary]: Gen[Matcher[T]] =
    // Because T "has an" Arbitrary type class we can get a Gen[T] with "arbitrary[T]"
    // and use that to create the Gen[Matcher[T]] we want.
    for {
      // Choose a set size
      listSize <- Gen.choose(0, 10)

      // Generate a List of that size.
      smallList <- Gen.listOfN(listSize, arbitrary[T])

      // Remove duplicates.
      smallSet = smallList.toSet.toSeq
    } yield Matcher(smallSet:_*)

  val genAppointmentPropertiesMatcher: Gen[AppointmentPropertiesMatcher] =
    for {
      wcCampaign <- genMatcher[CampaignInternalId]
      wcProgram  <- genMatcher[ProgramInternalId]
      wcActivity <- genMatcher[ActivityInternalId]
      wcResource <- genMatcher[ResourceInternalId]
    } yield AppointmentPropertiesMatcher(wcCampaign, wcProgram, wcActivity, wcResource)

  private def genNDisjointAppointmentPropertiesMatchers(n: Int, alreadyGenerated: Set[AppointmentPropertiesMatcher] = Set())
  : Gen[Set[AppointmentPropertiesMatcher]]
  = {
    require(n > 0)
    if (n <= alreadyGenerated.size) Gen.const(alreadyGenerated)
    else {
      val newOne =
        genAppointmentPropertiesMatcher
          .retryUntil { newSelector => alreadyGenerated.forall { ! newSelector.conflictsWith(_) } }
          .sample
          .get
      genNDisjointAppointmentPropertiesMatchers(n, alreadyGenerated + newOne)
    }
  }

  // Make a generator that can create a set of QueueSelectors which are "disjoint" in the sense
  // that no two pairs from the set overlap.
  def genNDisjointAppointmentPropertiesMatchers(n: Int): Gen[Set[AppointmentPropertiesMatcher]] =
    genNDisjointAppointmentPropertiesMatchers(n, Set[AppointmentPropertiesMatcher]())


  val genQueueLabel = Gen.chooseNum(1, 100) map { n => s"QueueLabel-$n"}
  val genLicenseName = Gen.chooseNum(1, 100) map { n => s"LicenseeName-$n"}
  val genLocationName = Gen.chooseNum(1, 100) map { n => s"LocationName-$n"}

  val genQueueDefinition: Gen[QueueDefinition] =
    for {
      externalId <- genQueueExternalId
      tesaRestUrl <- Gen.const(TesaRestUrl(Uri("http://tesa/dummy")))
      location <- genLocation
      matcher <- genAppointmentPropertiesMatcher
      rules <- genQueueRules
      label <- Gen.option(genQueueLabel)
      notifierDisabled <- Gen.oneOf(true, false)
    } yield QueueDefinition(externalId,
                            tesaRestUrl,
                            location,
                            matcher,
                            rules,
                            label,
                            disabled = false,
                            notifierDisabled)

  def genQueueDefinition(location: Location): Gen[QueueDefinition] =
    for {
      externalId <- genQueueExternalId
      tesaRestUrl <- Gen.const(TesaRestUrl(Uri("http://tesa/dummy")))
      matcher <- genAppointmentPropertiesMatcher
      rules <- genQueueRules
      label <- Gen.option(genQueueLabel)
      notifierDisabled <- Gen.oneOf(true, false)
    } yield QueueDefinition(externalId,
                            tesaRestUrl,
                            location,
                            matcher,
                            rules,
                            label,
                            disabled = false,
                            notifierDisabled)

  def genQueueDefinition(tesaInstance: TesaInstance, tesaUrl: TesaRestUrl): Gen[QueueDefinition] =
    for {
      externalId <- genQueueExternalId
      location <- genLocation(tesaInstance)
      matcher <- genAppointmentPropertiesMatcher
      rules <- genQueueRules
      label <- Gen.option(genQueueLabel)
      notifierDisabled <- Gen.oneOf(true, false)
    } yield QueueDefinition(externalId,
                            tesaUrl,
                            location,
                            matcher,
                            rules,
                            label,
                            disabled = false,
                            notifierDisabled)

  val genQueueId: Gen[QueueId] =
    for {
      uuid <- Gen.uuid
    } yield QueueId(uuid)

  // Implicit declarations to create those Arbitrary type classes we need.
  implicit val arbQueueDefinition = Arbitrary(genQueueDefinition)
  implicit val arbQueueId = Arbitrary(genQueueId)
  implicit val arbQueueExternalId = Arbitrary(genQueueExternalId)
  implicit val arbLocation = Arbitrary(genLocation)
  implicit val arbTesaInstance = Arbitrary(genTesaInstance)

  def genNDistinctQueueExternalIds(n: Int) = genSetOfN[QueueExternalId](n)
}

object QueueDefinitionGeneration extends QueueDefinitionGeneration


