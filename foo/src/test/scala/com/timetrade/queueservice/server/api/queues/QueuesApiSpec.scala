/** Copyright(c) 2014 by TimeTrade Systems.  All Rights Reserved. */
package com.timetrade.queueservice.server.api.queues

import java.util.UUID

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.Random

import spray.http.HttpEntity
import spray.http.HttpMethod
import spray.http.HttpMethods.DELETE
import spray.http.HttpMethods.GET
import spray.http.HttpMethods.POST
import spray.http.HttpMethods.PUT
import spray.http.HttpRequest
import spray.http.StatusCode
import spray.http.StatusCodes
import spray.http.Uri
import spray.http.Uri.apply
import spray.testkit.ScalatestRouteTest

import org.scalatest.Matchers
import org.scalatest.concurrent.Futures
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.fixture.FunSpec
import org.scalatest.time.Millis
import org.scalatest.time.Seconds
import org.scalatest.time.Span

import com.timetrade.queueservice.jsonutils.PimpedUUID
import com.timetrade.queueservice.jsonutils.PimpedUUID._PimpedUUID
import com.timetrade.queueservice.server.api.Api
import com.timetrade.queueservice.server.api.JsonContentUtils
import com.timetrade.queueservice.server.api.queues.Unmarshalling.AllInstancesViewUnmarshaller
import com.timetrade.queueservice.server.api.queues.Unmarshalling.BulkDeletedQueuesViewUnmarshaller
import com.timetrade.queueservice.server.api.queues.Unmarshalling.BulkUpsertedQueuesViewUnmarshaller
import com.timetrade.queueservice.server.api.queues.Unmarshalling.InstanceAllLicenseesViewUnmarshaller
import com.timetrade.queueservice.server.api.queues.Unmarshalling.NewFeedUnmarshaller
import com.timetrade.queueservice.server.api.queues.Unmarshalling.NewQueueViewUnmarshaller
import com.timetrade.queueservice.server.api.queues.Unmarshalling.QueueUnmarshaller
import com.timetrade.queueservice.server.api.queues.Unmarshalling.QueuesUnmarshaller
import com.timetrade.queueservice.server.api.queues.entities.AllInstancesView
import com.timetrade.queueservice.server.api.queues.entities.BulkDeleteQueues
import com.timetrade.queueservice.server.api.queues.entities.BulkDeletedQueuesView
import com.timetrade.queueservice.server.api.queues.entities.BulkUpsertedQueuesView
import com.timetrade.queueservice.server.api.queues.entities.InstanceAllLicenseesView
import com.timetrade.queueservice.server.api.queues.entities.InstanceView
import com.timetrade.queueservice.server.api.queues.entities.NewBulkUpsertQueues
import com.timetrade.queueservice.server.api.queues.entities.NewFeedView
import com.timetrade.queueservice.server.api.queues.entities.NewQueue
import com.timetrade.queueservice.server.api.queues.entities.NewQueueView
import com.timetrade.queueservice.server.api.queues.entities.OneLicenseeView
import com.timetrade.queueservice.server.api.queues.entities.QueueView
import com.timetrade.queueservice.server.api.queues.entities.SelectedActivities
import com.timetrade.queueservice.server.api.queues.entities.SelectedCampaigns
import com.timetrade.queueservice.server.api.queues.entities.SelectedLicensee
import com.timetrade.queueservice.server.api.queues.entities.SelectedLocation
import com.timetrade.queueservice.server.api.queues.entities.SelectedPrograms
import com.timetrade.queueservice.server.api.queues.entities.SelectedResources
import com.timetrade.queueservice.server.core.Core
import com.timetrade.queueservice.server.core.data.Licensee
import com.timetrade.queueservice.server.core.data.TesaInstance
import com.timetrade.queueservice.server.core.data.externalids.LicenseeExternalId
import com.timetrade.queueservice.server.core.data.externalids.QueueExternalId
import com.timetrade.queueservice.server.core.data.internalids.ActivityInternalId
import com.timetrade.queueservice.server.core.data.internalids.CampaignInternalId
import com.timetrade.queueservice.server.core.data.internalids.InternalId
import com.timetrade.queueservice.server.core.data.internalids.LocationInternalId
import com.timetrade.queueservice.server.core.data.internalids.ProgramInternalId
import com.timetrade.queueservice.server.core.data.internalids.ResourceInternalId
import com.timetrade.queueservice.server.core.definitioncrud.QueueRules
import com.timetrade.queueservice.server.core.definitioncrud.TesaRestUrl
import com.timetrade.queueservice.server.core.ticketing.TicketFound
import com.timetrade.queueservice.server.testtraits.ConfigurableActorSystems
import com.timetrade.queueservice.testtraits.TimedSuite

class QueuesApiSpec
  extends FunSpec
  with Matchers
  with ScalatestRouteTest
  with Futures
  with ScalaFutures
  with ConfigurableActorSystems
  with TimedSuite {

  override def createActorSystem() = defaultActorSystem()

  type FixtureParam = Api
  implicit val timeout = RouteTestTimeout(120.seconds)
  private val rand = new Random()

  // Configure the behavior of the "whenReady" calls below.
  implicit val defaultPatience = PatienceConfig(timeout =  Span(30, Seconds),
                                                interval = Span(1, Millis))
  def await[T](f: Future[T]): T = whenReady(f) { t => t }

  def withFixture(test: OneArgTest) = {

    val api = Api()(Core.defaultForTesting(system))

    try {
      await(api.core.becomeReady())
      test(api)
    } finally {
      api.shutdown()
    }
  }

  val tesaInstanceId = "sprint"
  val tesaRestUri = "http://localhost:8080"
  val licenseeId = "sprint"
  val collectionUri = s"/${tesaInstanceId}/${licenseeId}/queues"

  describe("The Queues REST API") {

    val wildcard = InternalId.wildcard

    describe("of properties of QueueIds") {

      it("QueueID UUID conversions use Microsoft formatting, to achieve the same byte-ordering as SQL Server does") { api =>
        import com.timetrade.queueservice.jsonutils.PimpedUUID
        import com.timetrade.queueservice.jsonutils.PimpedUUID._

        /**
         * By manual inspection, we observed how a Java UUID was encoded in SQL Server, and then
         * converted to string by a SQL client reading that column of the DB.
         */
        val observedMsUuidEncoding = "00b5b2d9-2661-1948-9931-a2f5e1a01341"

        /**
         * By manual inspection, we observed how the same Java UUID was printed via UUID.toString.
         */
        val observedJavaUuidEncoding ="d9b2b500-6126-4819-9931-a2f5e1a01341"

        /**
         * If we can successfully parse the the Microsoft printing of a UUID into a UUID, we should
         * be able to print the UUID as a string, and get the observed Java stringification.
         */
        val msUuid = PimpedUUID.fromMicrosoftGuidString(observedMsUuidEncoding)
        msUuid.toString should equal(observedJavaUuidEncoding)

        /**
         * Similarly, we should be able to get back the original Microsoft stringification from the
         * Java UUID object.
         */
        msUuid.toMicrosoftGuidString should equal(observedMsUuidEncoding)
      }
    }

    describe(s"for a queue collection like ${collectionUri}") {

      describe("GET") {

        it("is allowed without a trailing slash") { implicit api =>
          postQueue(newRandomQueue)
          HttpRequest(GET, collectionUri) ~> api.combinedRoute ~> check {
            status should be(StatusCodes.OK)
            val resp = responseAs[Array[QueueView]]
            resp.length should equal(1)
          }
        }

        it("is allowed with a trailing slash") { implicit api =>
          postQueue(newRandomQueue)
          HttpRequest(GET, collectionUri + "/") ~> api.combinedRoute ~> check {
            status should be(StatusCodes.OK)
            val resp = responseAs[Array[QueueView]]
            resp.length should equal(1)
          }
        }

        it("can search by external ID") { implicit api =>
          postQueue(newWildQueue("ext1", 1))
          postQueue(newWildQueue("ext2", 2))

          HttpRequest(GET, collectionUri + "?externalId=ext2") ~> api.combinedRoute ~> check {
            status should be(StatusCodes.OK)
            val resp = responseAs[Array[QueueView]]
            resp.length should equal(1)
            resp(0).externalId.s should equal("ext2")
          }
        }

        it("treats search-by-external-ID as a pattern") { implicit api =>
          postQueue(newWildQueue("ext1", 1))
          postQueue(newWildQueue("ext2", 2))

          HttpRequest(GET, collectionUri + "?externalId=ext") ~> api.combinedRoute ~> check {
            status should be(StatusCodes.OK)
            val resp = responseAs[Array[QueueView]]
            resp.length should equal(2)
          }
        }

        it("can search by label") { implicit api =>
          postQueue(newWildQueue("ext1", 1, Some("label1")))
          postQueue(newWildQueue("ext2", 2, Some("label2")))

          HttpRequest(GET, collectionUri + "?label=label2") ~> api.combinedRoute ~> check {
            status should be(StatusCodes.OK)
            val resp = responseAs[Array[QueueView]]
            resp.length should equal(1)
            resp(0).label.getOrElse("") should equal("label2")
          }
        }

        it("treats search-by-label as a pattern") { implicit api =>
          postQueue(newWildQueue("ext1", 1, Some("label1")))
          postQueue(newWildQueue("ext2", 2, Some("label2")))

          HttpRequest(GET, collectionUri + "?label=label") ~> api.combinedRoute ~> check {
            status should be(StatusCodes.OK)
            val resp = responseAs[Array[QueueView]]
            resp.length should equal(2)
          }
        }

        it("can search by activity ID") { implicit api =>
          postQueue(newWildQueue("ext1", 1, activityId=1))
          postQueue(newWildQueue("ext2", 2, activityId=2))

          HttpRequest(GET, collectionUri + "?activityId=2") ~> api.combinedRoute ~> check {
            status should be(StatusCodes.OK)
            val resp = responseAs[Array[QueueView]]
            resp.length should equal(1)
            resp(0).activity.ids.toList should contain(ActivityInternalId(2))
          }
        }

        it("can search by campaign ID") { implicit api =>
          postQueue(newWildQueue("ext1", 1, campaignId=1))
          postQueue(newWildQueue("ext2", 2, campaignId=2))

          HttpRequest(GET, collectionUri + "?campaignId=2") ~> api.combinedRoute ~> check {
            status should be(StatusCodes.OK)
            val resp = responseAs[Array[QueueView]]
            resp.length should equal(1)
            resp(0).campaign.ids.toList should contain(CampaignInternalId(2))
          }
        }

        it("can search by location ID") { implicit api =>
          postQueue(newWildQueue("ext1", 1))
          postQueue(newWildQueue("ext2", 2))

          HttpRequest(GET, collectionUri + "?locationId=2") ~> api.combinedRoute ~> check {
            status should be(StatusCodes.OK)
            val resp = responseAs[Array[QueueView]]
            resp.length should equal(1)
            resp(0).location.id should be(LocationInternalId(2))
          }
        }

        it("can search by program ID") { implicit api =>
          postQueue(newWildQueue("ext1", 1, programId=1))
          postQueue(newWildQueue("ext2", 2, programId=2))

          HttpRequest(GET, collectionUri + "?programId=2") ~> api.combinedRoute ~> check {
            status should be(StatusCodes.OK)
            val resp = responseAs[Array[QueueView]]
            resp.length should equal(1)
            resp(0).program.ids.toList should contain(ProgramInternalId(2))
          }
        }

        it("can search by resource ID") { implicit api =>
          postQueue(newWildQueue("ext1", 1, resourceId=1))
          postQueue(newWildQueue("ext2", 2, resourceId=2))

          HttpRequest(GET, collectionUri + "?resourceId=2") ~> api.combinedRoute ~> check {
            status should be(StatusCodes.OK)
            val resp = responseAs[Array[QueueView]]
            resp.length should equal(1)
            resp(0).resource.ids.toList should contain(ResourceInternalId(2))
          }
        }
      }

      describe("POST") {

        it("creates queues") { implicit api =>
          postQueue(newRandomQueue)
        }

        it("allows queues with entirely wildcard properties matchers") { implicit api =>
          postQueue(newWildQueue("ext", 1))
        }

        // TODO: Not sure the current behavior is correct?
        it("allows two queues at the same location, one with wildcarded, one with specific properties matchers") { implicit api =>
          postQueue(newWildQueue("ext1", 1))
          postQueue(newWildQueue("ext2", 1, activityId = 1))

          // Try in reverse order: Create the specific one first.
          postQueue(newWildQueue("ext2", 2, activityId = 1))
          postQueue(newWildQueue("ext1", 2))
        }

        it("can clone an exisiting queue to multiple locations") { implicit api =>
          // Create a "source" queue, to be cloned at other locations.
          val sourceQ = newWildQueue("ext1", 1)
          postQueue(sourceQ)
          bulkPostQueue(sourceQ, List(2, 3, 4, 5))
        }

        it("rejects a wildcard location") { implicit api =>
          postQueue(newQueue("ext", wildcard, 1, 2, 4, 5), StatusCodes.BadRequest)
        }

        it("rejects multiple queues at the same location, with all wildcards for properties matchers") { implicit api =>
          postQueue(newWildQueue("ext1", 1))
          postQueue(newWildQueue("ext2", 1), StatusCodes.Conflict)
        }

        it("rejects multiple queues at the same location, with identical external IDs") { implicit api =>
          postQueue(newQueue("ext", 1, 1, 2, 4, 5))
          postQueue(newQueue("ext", 1, 6, 7, 9, 10), StatusCodes.Conflict)
        }

        it("rejects two queues at the same location, with identical activities") { implicit api =>
          postQueue(newWildQueue("ext1", 1, activityId = 1))
          postQueue(newWildQueue("ext2", 1, activityId = 1), StatusCodes.Conflict)
        }

        it("rejects two queues at the same location, with identical campaigns") { implicit api =>
          postQueue(newWildQueue("ext1", 1, campaignId = 1))
          postQueue(newWildQueue("ext2", 1, campaignId = 1), StatusCodes.Conflict)
        }

        it("rejects two queues at the same location, with identical programs") { implicit api =>
          postQueue(newWildQueue("ext1", 1, programId = 1))
          postQueue(newWildQueue("ext2", 1, programId = 1), StatusCodes.Conflict)
        }

        it("rejects two queues at the same location, with identical resources") { implicit api =>
          postQueue(newWildQueue("ext1", 1, resourceId = 1))
          postQueue(newWildQueue("ext2", 1, resourceId = 1), StatusCodes.Conflict)
        }

        it("rejects a bulk-clone if a clone at any location clashes with an existing queue") { implicit api =>
          val sourceQ = newWildQueue("ext1", 1)
          postQueue(sourceQ)
          postQueue(newWildQueue("ext1", 2))
          bulkPostQueue(sourceQ, List(2, 3), StatusCodes.Conflict, List(2))
        }

        it("rejects a bulk-clone with no specified locations") { implicit api =>
          val sourceQ = newWildQueue("ext1", 1)
          postQueue(sourceQ)
          bulkPostQueue(sourceQ, List(), StatusCodes.BadRequest)
        }
      }
    }

    describe("for specific queues") {

      describe("GET") {

        it("reads a queue") { implicit api =>
          // Must create a queue to get one.
          val newQ = newRandomQueue
          postQueue(newQ) map { qUri =>
            HttpRequest(GET, qUri) ~> api.combinedRoute ~> check {
              status should equal(StatusCodes.OK)
              val resp = responseAs[QueueView]
              resp.activity should equal(newQ.activity)
              resp.appointments should not be empty
              resp.campaign should equal(newQ.campaign)
              resp.enabled should equal(newQ.enabled)
              resp.feeds should not be empty
              resp.id.toString should not be empty
              resp.externalId should equal(newQ.externalId)
              resp.licensee should equal(newQ.licensee)
              resp.location should equal(newQ.location)
              resp.notifierEnabled should equal(newQ.notifierEnabled)
              resp.program should equal(newQ.program)
              resp.resource should equal(newQ.resource)
              resp.tesaInstanceId should equal(newQ.tesaInstanceId)
              resp.tesaRestUrl should equal(newQ.tesaRestUrl)
            }
          }
        }

        it("gracefully rejects a queue ID that is not a UUID") { implicit api =>
          HttpRequest(GET, collectionUri + "/nonesuch") ~> api.combinedRoute ~> check {
            status should be(StatusCodes.NotFound)
          }
        }

        it("gracefully rejects a nonexistant queue") { implicit api =>
          probeNonExistantQueue(GET)
        }
      }

      describe("DELETE") {

        it("deletes a queue") { implicit api =>
          // Must create a queue to delete one.
          postQueue(newRandomQueue) map { qUri =>
            HttpRequest(DELETE, qUri) ~> api.combinedRoute ~> check {
              status should equal(StatusCodes.NoContent)
            }

            // Verify that we can't find it now.
            HttpRequest(GET, qUri) ~> api.combinedRoute ~> check {
              status should equal(StatusCodes.NotFound)
            }
          }
        }

        it("gracefully rejects a nonexistant queue") { implicit api =>
          probeNonExistantQueue(DELETE)
        }

        it("can delete existing queues at multiple locations") { implicit api =>
          val qUri1 = postQueue(newWildQueue("ext1", 1))
          val qUri2 = postQueue(newWildQueue("ext1", 2))
          bulkDeleteQueue("ext1", List(1,2), StatusCodes.OK, List(1,2))

          // Verify that we can't find them now.
          HttpRequest(GET, qUri1.get) ~> api.combinedRoute ~> check {
            status should equal(StatusCodes.NotFound)
          }
          HttpRequest(GET, qUri2.get) ~> api.combinedRoute ~> check {
            status should equal(StatusCodes.NotFound)
          }
        }

        it("doesn't delete more than it should") { implicit api =>
          // Create two queues, same external ID, but different locations.
          val uri1 = postQueue(newWildQueue("ext1", 1))
          HttpRequest(GET, uri1.get) ~> api.combinedRoute ~> check {
            status should be(StatusCodes.OK)
          }
          val uri2 = postQueue(newWildQueue("ext1", 2))
          HttpRequest(GET, uri2.get) ~> api.combinedRoute ~> check {
            status should be(StatusCodes.OK)
          }
          HttpRequest(DELETE, uri1.get) ~> api.combinedRoute ~> check {
            status should be(StatusCodes.NoContent)
          }

          // We deleted the queue at location 1, but the queue at location 2 must still exist.
          HttpRequest(GET, uri2.get) ~> api.combinedRoute ~> check {
            status should be(StatusCodes.OK)
          }
        }

        it("is harmless to bulk-delete non-existant queues") { implicit api =>
          bulkDeleteQueue("ext1", List(1, 2, 3), StatusCodes.OK, List())
        }
      }

      describe("PUT") {

        it("updates a queue") { implicit api =>
          // Must create a queue to update one.
          val newQ = newRandomQueue
          val originalLabel = newQ.label.getOrElse("")
          val originalExternalId = newQ.externalId

          postQueue(newQ) map { qUri =>
            val changedLabel = Some("changed queue label")
            val changedExternalId = QueueExternalId("changed-queue-external-id")
            val changedQ = changedQueue(newQ, changedLabel, changedExternalId)
            HttpRequest(PUT, qUri, entity = QueuesApiSpec.entityFor(changedQ)) ~> api.combinedRoute ~> check {
              status should be(StatusCodes.NoContent)
            }

            // Verify that the changes were made.
            HttpRequest(GET, qUri) ~> api.combinedRoute ~> check {
              status should be(StatusCodes.OK)
              val q = responseAs[QueueView]
              q.externalId should equal(changedExternalId)
              q.label should equal(changedLabel)
            }
          }
        }

        it("gracefully rejects a nonexistant queue") { implicit api =>
          probeNonExistantQueue(PUT)
        }

        it("can update an existing queue at multiple locations") { implicit api =>
          // Create a "source" queue, same properties matcher and same external ID, at two locations.
          val sourceQ = newWildQueue("ext1", 1)
          postQueue(sourceQ)
          postQueue(newWildQueue("ext1", 2))
          bulkPutQueue(Some(QueueExternalId("ext1")), sourceQ, List(1, 2))
        }

        it("can use bulk-update to change queue external IDs") { implicit api =>
          val sourceQ = newWildQueue("ext1", 1, Some("label"), 2, 3, 4, 5)
          postQueue(sourceQ)

          // We change not just the external ID, but the label and all matchable properties as well.
          val updatedQ = newWildQueue("ext2", 1, None, -1, -1, -1, -1)
          val resp = bulkPutQueue(Some(QueueExternalId("ext1")), updatedQ, List(1)).get

          // Verify that the change was made.
          HttpRequest(GET, resp.queues.get.get("1").get) ~> api.combinedRoute ~> check {
            status should be(StatusCodes.OK)
            val q = responseAs[QueueView]
            q.externalId should equal(updatedQ.externalId)
            q.label should be(None)
            q.activity.ids shouldBe empty
            q.campaign.ids shouldBe empty
            q.program.ids shouldBe empty
            q.resource.ids shouldBe empty
          }
        }

        it("will create queues not found when doing a bulk-update") { implicit api =>
          val sourceQ = newWildQueue("ext1", 1)
          postQueue(sourceQ)
          /*
           * We verify that the operation touched both location 1 and 2, because sourceQ mentions
           * location 1, and upsert will thus consider it as a candidate for update.
           */
          val resp = bulkPutQueue(Some(QueueExternalId("ext1")), sourceQ, List(1, 2))
        }

        it("rejects a bulk-update without a specified queue external ID") { implicit api =>
          val sourceQ = newWildQueue("ext1", 1)
          postQueue(sourceQ)
          bulkPutQueue(None, sourceQ, List(1), StatusCodes.BadRequest)
        }
      }
    }

    describe(s"for creating feeds for an existing queue") {

      // TODO: This fails on Jenkins: timeout on first line of postQueue
      it("can POST to the feeds URI returned in a queue definition, to get a feed") { implicit api =>
        val qUri = postQueue(newRandomQueue)
        HttpRequest(GET, qUri.get) ~> api.combinedRoute ~> check {
          status should be(StatusCodes.OK)
          val resp = responseAs[QueueView]

          // The feed URI the queue advertises...
          val feedsUri = resp.feeds

          // ...must support POSTs, to create a new feed for this queue.
          HttpRequest(POST, feedsUri) ~> api.combinedRoute ~> check {
            status should be(StatusCodes.Created)
            val feedResp = responseAs[NewFeedView]

            val rawFid = feedResp.newFeed.id.uuid.toString()
            val rawLink = feedResp.newFeed.link.path.toString
            rawLink should include("/feeds")
            rawLink should endWith(rawFid)
          }
        }
      }

      it("can specify a feed duration, in minutes") { implicit api =>
        val qUri = postQueue(newRandomQueue)
        HttpRequest(GET, qUri.get) ~> api.combinedRoute ~> check {
          status should be(StatusCodes.OK)
          val resp = responseAs[QueueView]
          // The feed URI the queue advertises...
          val feedsUri = resp.feeds

          // ...must support POSTs, to create a new feed for this queue.
          HttpRequest(POST, feedsUri + "?duration=20") ~> api.combinedRoute ~> check {
            status should be(StatusCodes.Created)
            val feedResp = responseAs[NewFeedView]
            val fid = feedResp.newFeed.id

            // Since the ticket's deadline starts ticking as soon as it's created, it'll be less than 20 minutes remaining.
            val remaining = feedResp.newFeed.minutesLeft
            remaining should be(19)

            whenReady(api.core.lookupTicket(fid)) {
              case TicketFound(t) =>
                // Since the ticket's deadline starts ticking as soon as it's created, it'll be less than 20 minutes remaining.
                t.deadline.timeLeft.toMinutes should be(19)

              case _ => fail("Couldn't find created feed")
            }
          }
        }
      }
    }

    describe("provides URIs to interrogate TESA instances and licensees") {

      it("can GET a set of all the TESA instances which have defined queues") { implicit api =>
        val qUri1 = postQueue(newRandomQueue("instance1", "licensee1"))
        val qUri2 = postQueue(newRandomQueue("instance1", "licensee2"))
        val qUri3 = postQueue(newRandomQueue("instance2", "licensee3"))
        HttpRequest(GET, "/instances") ~> api.combinedRoute ~> check {
          status should be(StatusCodes.OK)
          val expectedInstances = List(
              InstanceView(TesaInstance("instance1"), 2, 2, Uri("/instance1")),
              InstanceView(TesaInstance("instance2"), 1, 1, Uri("/instance2")))
          responseAs[AllInstancesView] should be(AllInstancesView(expectedInstances))
        }
      }

      it("can GET a set of all the licensees of a TESA instance which has defined queues") { implicit api =>
        val qUri1 = postQueue(newRandomQueue("instance1", "licensee1"))
        val qUri2 = postQueue(newRandomQueue("instance1", "licensee2"))
        val qUri3 = postQueue(newRandomQueue("instance2", "licensee3"))
        HttpRequest(GET, "/instance1") ~> api.combinedRoute ~> check {
          status should be(StatusCodes.OK)
          val expectedLicensees = List(
              OneLicenseeView(LicenseeExternalId("licensee1"), 1, Uri("/instance1/licensee1")),
              OneLicenseeView(LicenseeExternalId("licensee2"), 1, Uri("/instance1/licensee2")))
          responseAs[InstanceAllLicenseesView] should be(InstanceAllLicenseesView(TesaInstance("instance1"), expectedLicensees))
        }
        HttpRequest(GET, "/instance2") ~> api.combinedRoute ~> check {
          status should be(StatusCodes.OK)
          val expectedLicensees = List(
              OneLicenseeView(LicenseeExternalId("licensee3"), 1, Uri("/instance2/licensee3")))
          responseAs[InstanceAllLicenseesView] should be(InstanceAllLicenseesView(TesaInstance("instance2"), expectedLicensees))
        }
      }

      // TODO: THis fails on Jenkins: timeout on first line of postQueue
      it("can GET the URIs below a TESA instance and licensee that the server responds to") { implicit api =>
        postQueue(newRandomQueue("instance", "licensee"))
        HttpRequest(GET, "/instance/licensee") ~> api.combinedRoute ~> check {
          status should be(StatusCodes.OK)
          responseAs[String] should include("instance/licensee/queues")
        }
      }

      it("rejects TESA instances which have no defined queues") { implicit api =>
        HttpRequest(GET, "/nonesuch") ~> api.combinedRoute ~> check {
          status should be(StatusCodes.NotFound)
        }
      }

      it("rejects TESA licensees which have no defined queues") { implicit api =>
        postQueue(newRandomQueue("instance", "licensee"))
        HttpRequest(GET, "/instance/nonesuch") ~> api.combinedRoute ~> check {
          status should be(StatusCodes.NotFound)
        }
      }
    }
  }

  def entityFor(bulk: NewBulkUpsertQueues): HttpEntity = {
    JsonContentUtils.jsonHttpEntityFor(
        bulk,
        CustomMediaTypes.`application/vnd.timetrade.queue-server.queues.bulk-new+json`)
  }

  def entityFor(bulk: BulkDeleteQueues): HttpEntity = {
    JsonContentUtils.jsonHttpEntityFor(
        bulk,
        CustomMediaTypes.`application/vnd.timetrade.queue-server.queues.bulk-delete+json`)
  }

  def probeNonExistantQueue(m: HttpMethod)(implicit api: Api) = {
    val id = UUID.randomUUID().toString
    val uri = collectionUri + "/" + id
    m match {
      case GET | DELETE => HttpRequest(m, uri) ~> api.combinedRoute ~> check { status should be(StatusCodes.NotFound) }
      case PUT => {
        val newQ = newRandomQueue
        HttpRequest(m, uri, entity = QueuesApiSpec.entityFor(newQ)) ~> api.combinedRoute ~> check {
          status should be(StatusCodes.NotFound)
        }
      }
    }
  }

  /** Create a queue with the specified definition, verify it, and return a URI to it if successful. */
  def postQueue(
      q: NewQueue,
      sc: StatusCode = StatusCodes.Created)
  (implicit api: Api): Option[Uri] =
  {
    HttpRequest(POST, collectionUri, entity = QueuesApiSpec.entityFor(q)) ~> api.combinedRoute ~> check {
      status should equal(sc)
      if (sc == StatusCodes.Created) {
        val resp = responseAs[NewQueueView]
        resp.newQueue.link.path.toString should startWith(collectionUri)
        resp.newQueue.externalId.s should equal(q.externalId.s)
        Some(resp.newQueue.link)
      }
      else None
    }
  }

  /** A bulk POST is atomic; it either succeeds at all locations, or fails at all.  It will fail if
   * the source queue clashes with any existing queue at any of the specified locations.
   */
  def bulkPostQueue(
      sourceQ: NewQueue,
      locs: Seq[Int],
      sc: StatusCode = StatusCodes.Created,
      expectedErrorLocs: Seq[Int] = List())
  (implicit api: Api): Option[BulkUpsertedQueuesView] =
  {
    val lic = Licensee(getTesaInstance, LicenseeExternalId(licenseeId))
    val locIds = locs.map(LocationInternalId(_))
    val bulk = NewBulkUpsertQueues(None, sourceQ, locIds)
    HttpRequest(POST, collectionUri, entity = entityFor(bulk)) ~> api.combinedRoute ~> check {
      if (sc == StatusCodes.Created) {
        val resp = responseAs[BulkUpsertedQueuesView]
        resp.errors should be(None)
        val successLocs = resp.queues.get.map( pair => pair._1.toInt ).toSet
        successLocs should equal(locs.toSet)
        Some(resp)
      } else if (sc == StatusCodes.Conflict) {
        val resp = responseAs[BulkUpsertedQueuesView]
        resp.queues should be(None)
        val errorLocs = resp.errors.get.map( pair => pair._1.toInt ).toSet
        errorLocs should equal(expectedErrorLocs.toSet)
        Some(resp)
      } else {
        // We don't know how to validate this.  Might not have a response payload.
        None
      }
    }
  }

  def bulkPutQueue(
      originalExternalId: Option[QueueExternalId],
      sourceQ: NewQueue,
      locs: Seq[Int],
      sc: StatusCode = StatusCodes.Created,
      expectedErrorLocs: Seq[Int] = List())
  (implicit api: Api): Option[BulkUpsertedQueuesView] =
  {
    val lic = Licensee(getTesaInstance, LicenseeExternalId(licenseeId))
    val locIds = locs.map(LocationInternalId(_))
    val bulk = NewBulkUpsertQueues(originalExternalId, sourceQ, locIds)
    HttpRequest(PUT, collectionUri, entity = entityFor(bulk)) ~> api.combinedRoute ~> check {
      if (sc == StatusCodes.Created) {
        val resp = responseAs[BulkUpsertedQueuesView]
        resp.errors should be(None)
        val successLocs = resp.queues.get.map( pair => pair._1.toInt ).toSet
        successLocs should equal(locs.toSet)
        Some(resp)
      } else if (sc == StatusCodes.Conflict) {
        val resp = responseAs[BulkUpsertedQueuesView]
        resp.queues should be(None)
        val errorLocs = resp.errors.get.map( pair => pair._1.toInt ).toSet
        errorLocs should equal(expectedErrorLocs.toSet)
        Some(resp)
      } else {
        // We don't know how to validate this.  Might not have a response payload.
        None
      }
    }
  }

  def bulkDeleteQueue(
      rawExternalId: String,
      locs: Seq[Int],
      sc: StatusCode = StatusCodes.OK,
      expectedLocs: Seq[Int] = List())
  (implicit api: Api): Option[BulkDeletedQueuesView] =
  {
    val lic = Licensee(getTesaInstance, LicenseeExternalId(licenseeId))
    val locIds = locs.map(LocationInternalId(_))
    val bulk = BulkDeleteQueues(QueueExternalId(rawExternalId), locIds)
    HttpRequest(DELETE, collectionUri, entity = entityFor(bulk)) ~> api.combinedRoute ~> check {
      if (sc == StatusCodes.OK) {
        val resp = responseAs[BulkDeletedQueuesView]
        resp.errors should be(None)
        val successLocs = resp.queues.get.map( pair => pair._1.toInt ).toSet
        successLocs should equal(expectedLocs.toSet)
        Some(resp)
      } else if (sc == StatusCodes.Conflict) {
        val resp = responseAs[BulkDeletedQueuesView]
        resp.queues should be(None)
        val errorLocs = resp.errors.get.map( pair => pair._1.toInt ).toSet
        errorLocs should equal(expectedLocs.toSet)
        Some(resp)
      } else {
        // We don't know how to validate this.  Might not have a response payload.
        None
      }
    }
  }

  /** Get a queue with specified external ID and location, in which the matchable properties are wildcarded if not specified. */
  def newWildQueue(
    externalId: String,
    locationId: Int,
    label: Option[String] = getLabel,
    activityId: Int = InternalId.wildcard,
    campaignId: Int = InternalId.wildcard,
    programId: Int = InternalId.wildcard,
    resourceId: Int = InternalId.wildcard) =
  {
    newQueue(externalId, locationId, activityId, campaignId, programId, resourceId, label)
  }

  /** Get a queue with the specified external ID, location and properties matcher, and random other things. */
  def newQueue(
    externalId: String,
    locationId: Int,
    activityId: Int,
    campaignId: Int,
    programId: Int,
    resourceId: Int,
    label: Option[String] = getLabel) =
  {
    NewQueue(
      SelectedActivities(ActivityInternalId(activityId)),
      SelectedCampaigns(CampaignInternalId(campaignId)),
      enabled = true,
      QueueExternalId(externalId),
      label,
      getLicensee,
      SelectedLocation(LocationInternalId(locationId)),
      notifierEnabled = true,
      SelectedPrograms(ProgramInternalId(programId)),
      SelectedResources(ResourceInternalId(resourceId)),
      getRules,
      getTesaInstance,
      getTesaRestUrl)
  }

  /** Get a queue with specified TESA instance ID and licensee ID, random everything else. */
  def newRandomQueue(
      rawTesaInstanceId: String,
      rawLicenseeId: String): NewQueue = {
    NewQueue(
      getActivity,
      getCampaign,
      enabled = true,
      getExternalId,
      getLabel,
      SelectedLicensee(LicenseeExternalId(rawLicenseeId)),
      getLocation,
      notifierEnabled = true,
      getProgram,
      getResource,
      getRules,
      TesaInstance(rawTesaInstanceId),
      getTesaRestUrl)
  }

  /** Get a queue with random definition. */
  def newRandomQueue: NewQueue = {
    NewQueue(
      getActivity,
      getCampaign,
      enabled = true,
      getExternalId,
      getLabel,
      getLicensee,
      getLocation,
      notifierEnabled = true,
      getProgram,
      getResource,
      getRules,
      getTesaInstance,
      getTesaRestUrl)
  }

  def changedQueue(
    q: NewQueue,
    label: Option[String] = getLabel,
    externalId: QueueExternalId = getExternalId) =
  {
    NewQueue(
      q.activity,
      q.campaign,
      q.enabled,
      externalId,
      label,
      q.licensee,
      q.location,
      q.notifierEnabled,
      q.program,
      q.resource,
      q.rules,
      q.tesaInstanceId,
      q.tesaRestUrl)
  }

  def getActivity = { SelectedActivities(ActivityInternalId(randomSmallNonZeroInt)) }
  def getCampaign = { SelectedCampaigns(CampaignInternalId(randomSmallNonZeroInt)) }
  def getExternalId = { QueueExternalId(randomSmallString) }
  def getLabel = { Some("queue label") }
  def getLicensee = { SelectedLicensee(LicenseeExternalId(licenseeId)) }
  def getLocation = { SelectedLocation(LocationInternalId(randomSmallNonZeroInt)) }
  def getProgram = { SelectedPrograms(ProgramInternalId(randomSmallNonZeroInt)) }
  def getResource = { SelectedResources(ResourceInternalId(randomSmallNonZeroInt)) }

  def getRules = {
    QueueRules(
      earlyOnTimeGracePeriodMinutes = 15,
      lateOnTimeGracePeriodMinutes = 15,
      maxWaitTimeMinutes = 45,
      searchLimitDaysBefore = 4,
      searchLimitDaysAfter = 4)
  }
  def getTesaInstance = { TesaInstance(tesaInstanceId) }
  def getTesaRestUrl = { TesaRestUrl(Uri(tesaRestUri)) }

  def randomSmallNonZeroInt = { rand.nextInt(10).abs + 1 }

  def randomSmallString = {
    val lowercase = ('a' to 'z').mkString
    Stream.continually(rand.nextInt(lowercase.length)).map(lowercase).take(8).mkString
  }
}

/** Companion object to facilitate re-use of some useful methods. */
object QueuesApiSpec {
  def entityFor(q: NewQueue): HttpEntity = {
    JsonContentUtils.jsonHttpEntityFor(
      q,
      CustomMediaTypes.`application/vnd.timetrade.queue-server.queues.new+json`)
  }
}
