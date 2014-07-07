package com.timetrade.queueservice.server.core.matching

import org.scalactic.TypeCheckedTripleEquals

import org.scalatest.FunSpec
import org.scalatest.Matchers
import org.scalatest.prop.PropertyChecks

import com.timetrade.queueservice.server.core.data.internalids.ActivityInternalId
import com.timetrade.queueservice.server.core.data.internalids.CampaignInternalId
import com.timetrade.queueservice.server.core.data.internalids.InternalIdGeneration
import com.timetrade.queueservice.server.core.data.internalids.ProgramInternalId
import com.timetrade.queueservice.server.core.data.internalids.ResourceInternalId
import com.timetrade.queueservice.testtraits.TimedSuite

class AppointmentPropertiesMatcherSpec
  extends FunSpec
  with Matchers
  with InternalIdGeneration
  with MatchedPropertiesGeneration
  with PropertyChecks
  with TypeCheckedTripleEquals
  with TimedSuite {


  describe ("AppointmentPropertiesMatcher") {

    it ("'.any' value should be compatible with anything") {
      forAll { (matchables: MatchableProperties) =>
        AppointmentPropertiesMatcher.any.isCompatibleWith(matchables) should be (true)
      }
    }

    // Test expected affinities
    it ("'.affinity()' value should be produce expected values") {

      // Explicit match at Campaign
      {
        val campaign = genCampaignInternalId.sample.get
        val matcher1 = AppointmentPropertiesMatcher(Matcher(campaign))
        forAll { (program: ProgramInternalId,
          activity: ActivityInternalId,
          resource: ResourceInternalId) =>
          matcher1.affinity((MatchableProperties(campaign, program, activity, resource))) should ===(8)
        }
      }

      // Explicit match at Program
      {
        val program = genProgramInternalId.sample.get
        val matcher = AppointmentPropertiesMatcher(programs = Matcher(program))
        forAll { (campaign: CampaignInternalId,
          activity: ActivityInternalId,
          resource: ResourceInternalId) =>
          matcher.affinity((MatchableProperties(campaign, program, activity, resource))) should ===(4)
        }
      }
      // Explicit match at Activity
      {
        val activity = genActivityInternalId.sample.get
        val matcher = AppointmentPropertiesMatcher(activities = Matcher(activity))
        forAll { (campaign: CampaignInternalId,
                 program: ProgramInternalId,
                 resource: ResourceInternalId) =>
          matcher.affinity((MatchableProperties(campaign, program, activity, resource))) should ===(2)
        }
      }
      // Explicit match at Resource
      {
        val resource = genResourceInternalId.sample.get
        val matcher = AppointmentPropertiesMatcher(resources = Matcher(resource))
        forAll { (campaign: CampaignInternalId,
                  program: ProgramInternalId,
                  activity: ActivityInternalId) =>
          matcher.affinity((MatchableProperties(campaign, program, activity, resource))) should ===(1)
        }
      }
      // Wildcard match everywhere
      {
        val matcher = AppointmentPropertiesMatcher.any
        forAll { (campaign: CampaignInternalId,
                  program: ProgramInternalId,
                  activity: ActivityInternalId,
                  resource: ResourceInternalId) =>
          matcher.affinity((MatchableProperties(campaign, program, activity, resource))) should ===(0)
        }
      }
    }
  }

  // Test expected conflicts
  it ("'.conflictsWith()' should be produce expected values") {
    AppointmentPropertiesMatcher.any.conflictsWith(AppointmentPropertiesMatcher.any.copy()) should ===(true)

    val program = genProgramInternalId.sample.get
    val matcher = AppointmentPropertiesMatcher(programs = Matcher(program))
    matcher.conflictsWith(matcher) should === (true)

  }

}
