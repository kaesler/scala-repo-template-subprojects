package com.timetrade.queueservice.server.core.definitioncrud

import java.util.UUID

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

import org.scalacheck.Gen

import org.scalactic.TypeCheckedTripleEquals

import org.scalatest.Matchers
import org.scalatest.concurrent.Futures
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.fixture.FunSpec
import org.scalatest.time.Millis
import org.scalatest.time.Seconds
import org.scalatest.time.Span

import com.timetrade.queueservice.server.core.DuplicateExternalIdException
import com.timetrade.queueservice.server.core.QueueDefinitionAmbiguousException
import com.timetrade.queueservice.server.core.QueueIdNotFoundException
import com.timetrade.queueservice.server.core.data.Location
import com.timetrade.queueservice.server.core.data.externalids.QueueExternalId
import com.timetrade.queueservice.server.core.matching.MatchedPropertiesGeneration
import com.timetrade.queueservice.server.core.persistence.Datastore
import com.timetrade.queueservice.server.testtraits.ConfigurableActorSystems
import com.timetrade.queueservice.testtraits.TimedSuite

/** Tests for QueueDefinitionDAO. */
class QueueDefinitionDAOSpec
  extends FunSpec
  with Matchers
  with Futures
  with ScalaFutures
  with TypeCheckedTripleEquals
  with TimedSuite
  with ConfigurableActorSystems
  with QueueDefinitionGeneration
  with MatchedPropertiesGeneration {

  type FixtureParam = QueueDefinitionDAO

  def withFixture(test: OneArgTest) = {

    val system = defaultActorSystem()
    import system.dispatcher

    val datastore = Datastore.defaultForTesting(system)
    val dao = new QueueDefinitionDAO(datastore)
    await(dao.becomeReady)

    try {
      test(dao)
    } finally {
      system.shutdown()
      datastore.close()
    }
  }

  // Configure the behavior of the "whenReady" calls below.
  implicit val defaultPatience = PatienceConfig(timeout =  Span(30, Seconds),
                                                interval = Span(10, Millis))
  def await[T](f: Future[T]): T = whenReady(f) { t => t }

  def storeExpectingSuccess(dao: QueueDefinitionDAO, qd: QueueDefinition): QueueId = {
    await(dao.create(qd))
  }

  def store(dao: QueueDefinitionDAO, qds: Seq[QueueDefinition]): Seq[QueueId] =
    qds map { qd => storeExpectingSuccess(dao, qd) }

  def storeSomeDefs(dao: QueueDefinitionDAO): Seq[QueueDefinition] = {
    // Get some locations all different to be sure that the db put()s succeed because
    // there is no overlap.
    val locations = Gen.listOf(genLocation).sample.get.toSet.toSeq
    val qdefs = locations map { genQueueDefinition(_).sample.get }

    // Store them.
    store(dao, qdefs)

    // Return what we stored.
    qdefs
  }

  def generateDefinitionsForOneLocation(count: Int): IndexedSeq[QueueDefinition] = {
    // Generate a set of QueueSelectors at the same Location such that:
    //   - their matchers do not overlap.
    //   - their external ids are distinct
    val location = genLocation.sample.get
    val matchers = genNDisjointAppointmentPropertiesMatchers(count).sample.get
    val externalIds = genNDistinctQueueExternalIds(count).sample.get

    // Now create a collection of QueueDefinitions one containing each selector.
    matchers.zip(externalIds)
      .toIndexedSeq
      .map { case (matcher, eid) =>
        genQueueDefinition.sample.get.copy(location = location, matcher = matcher, externalId = eid)
      }
  }

  def generateAndStoreDefinitionsForOneLocation(dao: QueueDefinitionDAO, count: Int)
  : Seq[QueueDefinition] = {
    val definitions = generateDefinitionsForOneLocation(count)

    // Store them.
    store(dao, definitions)

    definitions
  }


  describe ("A QueueDefinitionDAO") {

    it ("Should create table on first reference") { dao =>
      await(dao.becomeReady)
      whenReady(dao.datastore.tableExists(dao.tableName)) { _ should be (true) }
    }

    it ("Should present empty table initially") { dao =>
      whenReady(dao.size) { _ should === (0) }
    }

    it ("Should implement 'put(QueueDefinition)' correctly") { dao =>
      val queueDef = genQueueDefinition.sample.get
      whenReady(dao.create(queueDef)) { id =>
        whenReady(dao.size) { _ should === (1) }
      }
    }

    it ("Should implement 'delete(QueueId)' correctly") { dao =>

      // Write a row.
      val queueDef = genQueueDefinition.sample.get
      val id = await(dao.create(queueDef))
      whenReady(dao.size) { size =>
        size should === (1)

        // Delete the row.
        whenReady(dao.delete(id)) { _ =>

          // Verify deletion.
          whenReady(dao.size) { size =>
            size should === (0)
          }
        }
      }
    }

    it ("Should implement 'get()' correctly") { dao =>
      // Write a row.
      val queueDef = genQueueDefinition.sample.get
      val id = await(dao.create(queueDef))

      whenReady(dao.all) { pairsRead =>
        pairsRead should === (Map(id -> queueDef))
      }
    }

    it ("Should implement 'get(QueueId)' correctly") { dao =>
      // Write a row.
      val queueDef = genQueueDefinition.sample.get
      val id = await(dao.create(queueDef))

      // Read it using its primary key.
      whenReady(dao.get(id)) { qdefOpt =>
        qdefOpt should === (queueDef)
      }
    }

    it ("Should implement 'findWithinLocation(Location)' correctly") { dao =>
      // Generate a set of QueueSelectors at the same Location that do not overlap.
      val Count = 20
      val definitions = generateAndStoreDefinitionsForOneLocation(dao, Count)
      definitions.size should === (Count)
      val location = definitions.head.location
      val matchers = definitions
        .map { _.matcher }
        .toSet

      whenReady(dao.findWithinLocation(location)) { pairs =>
        pairs should have size (Count)
        val selectorsRead = (pairs map {   _._2.matcher }).toSet

        selectorsRead should === (matchers)
      }
    }

    it ("Should implement 'findWithinLocation(location, None. None, matchables)' correctly") { dao =>
      val DefinitionCount = 20
      val definitions = generateAndStoreDefinitionsForOneLocation(dao, DefinitionCount)

      definitions foreach { definition =>
        val loc = definition.location
        val matcher = definition.matcher
        val MatchablesCount = 10
        val matchablesList = Gen.listOfN(MatchablesCount, genPropertiesMatching(matcher)).sample.get
        matchablesList foreach { matchables =>
          whenReady(dao.findWithinLocation(loc, None, None, Some(matchables))) { pairs =>
            pairs.size should be >= (1)
            val definitionsAsRead = pairs map { _._2 }
            definitionsAsRead should contain (definition)
          }
        }
      }
    }

    it ("Should fail 'put' of a definition whose matcher overlaps with that of an "
        + "already stored one at the same location") { dao =>

      // Store a single definition.
      val definition = genQueueDefinition.sample.get
      store(dao, Seq(definition))

      // Try to store it again after changing the externalId
      val newExternalId = QueueExternalId(definition.externalId.s + "xx")
      val definitionWithDifferentExternalId = definition.copy(externalId = newExternalId)
      val f = dao.create(definitionWithDifferentExternalId)

      intercept[QueueDefinitionAmbiguousException] {
        val _ = Await.result(f, 10.seconds)
      }
    }

    it ("Should fail 'put' of a definition whose external id matches that of an already stored one"
        + "for the same location") { dao =>

      // Generate two definitions that are storable at the same location.
      val defns = generateDefinitionsForOneLocation(2)
      val first = defns(0)
      val second = defns(1)

      // Store the first
      store(dao, Seq(first))

      // Now try to store the 2nd one but with its externalId changed to be that of the already
      // stored one.
      val f = dao.create(second.copy(externalId = first.externalId))

      intercept[DuplicateExternalIdException] {
        val _ = Await.result(f, 10.seconds)
      }
    }

    it ("Should implement 'update(QueueId, QueueDefinition)' correctly") { dao =>

      // Store a single definition.
      val definition = genQueueDefinition.sample.get
      val id = store(dao, Seq(definition)).head

      // Modify and update.
      val updatedDefinition = definition.copy(label = Gen.alphaStr.sample)
      whenReady(dao.update(id, updatedDefinition)) { _ =>

        whenReady(dao.get(id)) { optDef =>
          optDef should === (updatedDefinition)
        }
      }
    }

    it ("Should fail 'update(QueueId,_)' when id is not found") { dao =>
      val definition = generateAndStoreDefinitionsForOneLocation(dao, 1).head
      val newId = QueueId(UUID.randomUUID)
      val f = dao.update(newId, definition)
      intercept[QueueIdNotFoundException] {
        Await.result(f, 10.seconds)
      }
    }

    it ("Should implement searching by externalid pattern within Licensee") { dao =>
      val Count = 20
      val definitions = generateAndStoreDefinitionsForOneLocation(dao, Count)
      val location = definitions.head.location
      val licensee = location.licensee

      whenReady(dao.findWithinLicensee(licensee)) { results =>
        results.size should === (Count)
      }

      // Check pattern matching on external id.

      // We happen to know what external ids start with:
      val extIdPrefix = QueueExternalIdPrefix
      whenReady(dao.findWithinLicensee(licensee, Some(extIdPrefix + "%"))) {
        _.size should === (Count)
      }
      whenReady(dao.findWithinLicensee(licensee, Some("%" + extIdPrefix + "%"))) {
        _.size should === (Count)
      }
      whenReady(dao.findWithinLicensee(licensee, Some("%" + extIdPrefix.toLowerCase + "%"))) {
        _.size should === (Count)
      }
      whenReady(dao.findWithinLicensee(licensee, Some("%" + extIdPrefix.toUpperCase + "%"))) {
        _.size should === (Count)
      }
      whenReady(dao.findWithinLicensee(licensee, Some("%"))) {
        _.size should === (Count)
      }
    }

    it ("Should implement searching by externalid pattern within Location") { dao =>
      val Count = 20
      val definitions = generateAndStoreDefinitionsForOneLocation(dao, Count)
      val location = definitions.head.location
      val licensee = location.licensee

      whenReady(dao.findWithinLicensee(licensee)) { results =>
        results.size should === (Count)
      }

      // Check pattern matching on external id.

      // We happen to know what external ids start with:
      val extIdPrefix = QueueExternalIdPrefix
      whenReady(dao.findWithinLocation(location, Some(extIdPrefix + "%"))) {
        _.size should === (Count)
      }
      whenReady(dao.findWithinLocation(location, Some("%" + extIdPrefix + "%"))) {
        _.size should === (Count)
      }
      whenReady(dao.findWithinLocation(location, Some("%" + extIdPrefix.toLowerCase + "%"))) {
        _.size should === (Count)
      }
      whenReady(dao.findWithinLocation(location, Some("%" + extIdPrefix.toUpperCase + "%"))) {
        _.size should === (Count)
      }
      whenReady(dao.findWithinLocation(location, Some("%"))) {
        _.size should === (Count)
      }
    }

    it ("Should implement searching by label pattern within Licensee") { dao =>
      val Count = 20
      val definitions = generateAndStoreDefinitionsForOneLocation(dao, Count)
      val location = definitions.head.location
      val licensee = location.licensee

      whenReady(dao.findWithinLicensee(licensee)) { results =>
        results.size should === (Count)
      }

      // Check pattern matching on label.

      whenReady(dao.findWithinLicensee(licensee, None, Some("%"))) {
        _.size should === (definitions.count(_.label.isDefined))
      }
      whenReady(dao.findWithinLicensee(licensee, None, Some("%7%"))) {
        _.size should === (definitions.count { _.label.getOrElse("").contains("7") })
      }
    }

    it ("Should implement searching by label pattern within Location") { dao =>
      val Count = 20
      val definitions = generateAndStoreDefinitionsForOneLocation(dao, Count)
      val location = definitions.head.location
      val licensee = location.licensee

      whenReady(dao.findWithinLicensee(licensee)) { results =>
        results.size should === (Count)
      }

      // Check pattern matching on label.

      whenReady(dao.findWithinLocation(location, None, Some("%"))) {
        _.size should === (definitions.count(_.label.isDefined))
      }
      whenReady(dao.findWithinLocation(location, None, Some("%7%"))) {
        _.size should === (definitions.count { _.label.getOrElse("").contains("7") })
      }
    }

    it ("Should implement a successful createAtLocations() correctly"){ dao =>
      val Count = 10
      val locations = (genSetOfN[Location](Count)).sample.get
      val definition = genQueueDefinition.sample.get
      whenReady(dao.createAtLocations(locations, definition)) { pairs =>
        pairs.size should === (Count)
        pairs.foreach { case (loc, result) => result should be a 'success }

        val expectedContents =
          pairs
            .map { case (loc, result) => (result.get -> loc) }
            .toMap
        whenReady(dao.all) { pairs =>
          val actualContents = pairs map { case (id, definition) =>
            (id, definition.location)
          }
          actualContents should === (expectedContents)
        }
      }
    }

    it ("Should implement a failed createAtLocations() correctly"){ dao =>
      // store at 5 locations
      val Count = 10
      val ExpectedFailureCount = 5
      val locations = (genSetOfN[Location](Count)).sample.get
      val definition = genQueueDefinition.sample.get

      // First create the definition in some locations so that subsequent attempts to
      // create there will fail.
      whenReady(dao.createAtLocations(locations.take(ExpectedFailureCount), definition)) { pairs =>
        pairs.size should === (ExpectedFailureCount)
        pairs.foreach { case (loc, result) => result should be a 'success }


        // Now try to store again in full set of locations.
        val contentsBeforeFailure = await(dao.all)
        whenReady(dao.createAtLocations(locations, definition)) { pairs =>
          pairs.size should === (ExpectedFailureCount)
          pairs.foreach { case (loc, result) => result should be a 'failure }
        }
        val contentsAfterFailure = await(dao.all)
        contentsAfterFailure should === (contentsBeforeFailure)
      }
    }

    // TODO: write tests for all-create and mixed too:
    it ("Should implement a successful all-update upsertDefinitions() correctly"){ dao =>
      val Count = 10
      val locations = (genSetOfN[Location](Count)).sample.get
      locations.size should === (Count)
      val definition = genQueueDefinition.sample.get
      val storedPairs = await(dao.createAtLocations(locations, definition))
        .map { case (location, attempt) => (location, attempt.get)}
      storedPairs.size should === (Count)

      // Modify just the label of the definition.
      val newDefinition = definition.copy(label = Some(definition.label.getOrElse("") + "xxx"))
      whenReady(dao.upsertAtLocations(locations, definition.externalId, newDefinition)) { pairs =>
        pairs.size should === (Count)
        pairs.foreach { case (loc, result) => result should be a 'success }
      }
      val contentsAfterUpdate = await(dao.all)
      val expectedContents =
        storedPairs
          .map { case (location, id) =>
            (id -> newDefinition.withLocation(location))
          }
      contentsAfterUpdate should === (expectedContents)
    }

    it ("Should restrict upsertDefinitions() to the specified locations"){ dao =>
      val Count = 2
      val locations = (genSetOfN[Location](Count)).sample.get
      locations.size should === (Count)
      val definition = genQueueDefinition.sample.get
      val storedPairs = await(dao.createAtLocations(locations, definition))
        .map { case (location, attempt) => (location, attempt.get)}
      storedPairs.size should === (Count)

      // Modify just the label of the definition.
      val newDefinition = definition.copy(label = Some(definition.label.getOrElse("") + "xxx"))

      // Modifu at just one location.
      val updatedLocation = locations.head
      val unchangedLocation = locations.tail.head

      whenReady(dao.upsertAtLocations(Set(updatedLocation), definition.externalId, newDefinition)) { pairs =>
        pairs.size should === (1)
        pairs.foreach { case (loc, result) => result should be a 'success }
      }
      val contentsAfterUpdate = await(dao.all)
      val expectedContents =
        storedPairs
          .map { case (location, id) =>
            if (location === updatedLocation)
               (id -> newDefinition.withLocation(location))
             else
               (id -> definition.withLocation(location))
          }
      contentsAfterUpdate should === (expectedContents)
    }

    it ("Should implement a failed upsertAtLocations() correctly"){ dao =>

      // Create a definition at N different locations with a certain externalId.
      val Count = 10
      val locations = (genSetOfN[Location](Count)).sample.get
      locations.size should === (Count)

      // Generate two definitions that won't clash when stored for the same location.
      val definitions = generateDefinitionsForOneLocation(2)

      val originalDefinition = definitions(0)
      val originalExternalId = originalDefinition.externalId
      val storedPairs = await(dao.createAtLocations(locations, originalDefinition))
        .map { case (location, attempt) => (location, attempt.get)}
      storedPairs.size should === (Count)

      // Now create another definition at one of those locations with a new external Id we know.
      val clashingExternalId = QueueExternalId(originalExternalId.s + "xx")
      val clashingDefinition = definitions(1).copy(externalId = clashingExternalId)
      val clashingSetup = await(dao.createAtLocations(Set(locations.last), clashingDefinition))
      clashingSetup forall { case (id, attempt) =>  attempt.isSuccess }

      // Now attempt an update but with a new externalId that should clash at one location.
      val updatedDefinition =
        originalDefinition
          // Make the update try to change the label field.
          .copy(label = Some(originalDefinition.label.getOrElse("") + "xxx"))
          // Try to use an externalId that is already stored at one location.
          .copy(externalId = clashingExternalId)

      val contentsBeforeFailure = await(dao.all)
      contentsBeforeFailure.size should === (Count + 1)

      whenReady(dao.upsertAtLocations(locations, originalExternalId, updatedDefinition)) { pairs =>
        pairs.foreach { case (loc, result) => result should be a 'failure }
        pairs.size should === (1)
      }
      val contentsAfterFailure = await(dao.all)
      contentsAfterFailure should === (contentsBeforeFailure)
    }

    it ("Should implement a successful bulk deletion operation correctly"){ dao =>
      val Count = 10
      val locations = (genSetOfN[Location](Count)).sample.get
      locations.size should === (Count)
      val definition = genQueueDefinition.sample.get
      val storedPairs = await(dao.createAtLocations(locations, definition))
        .map { case (location, attempt) => (location, attempt.get)}
      storedPairs.size should === (Count)

      whenReady(dao.deleteAtLocations(locations, definition.externalId)) { pairs =>
        pairs.size should === (Count)
        pairs.foreach { case (loc, result) => result should be a 'success }
      }

      val contentsAfterUpdate = await(dao.all)
      contentsAfterUpdate.size should === (0)
    }

    it ("Should restrict bulk deletion operation to the specified locations"){ dao =>
      val Count = 2
      val locations = (genSetOfN[Location](Count)).sample.get
      locations.size should === (Count)
      val definition = genQueueDefinition.sample.get
      val storedPairs = await(dao.createAtLocations(locations, definition))
        .map { case (location, attempt) => (location, attempt.get)}
      storedPairs.size should === (Count)

      val deletee = locations.head
      val survivor = locations.tail.head
      whenReady(dao.deleteAtLocations(Set(deletee), definition.externalId)) { pairs =>
        pairs.size should === (1)
        pairs.foreach { case (loc, result) => result should be a 'success }
      }

      val expectedSurvivingQueueId = (storedPairs - deletee).head._2

      val contentsAfterDeletion = await(dao.all)
      contentsAfterDeletion.size should === (1)
      contentsAfterDeletion.head._1 should === (expectedSurvivingQueueId)
    }
  }
}
