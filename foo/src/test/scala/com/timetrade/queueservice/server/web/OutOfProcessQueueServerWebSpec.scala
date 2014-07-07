package com.timetrade.queueservice.server.web

import scala.util.Random

import spray.http.Uri

import com.timetrade.queueservice.netutils.HttpClient
import com.timetrade.queueservice.server.core.data.Licensee
import com.timetrade.queueservice.server.core.data.TesaInstance
import com.timetrade.queueservice.server.core.data.externalids.LicenseeExternalId
import com.timetrade.queueservice.server.web.mockedtesa.MockedTesa

// Note: this is meant for ad-hoc tests against a manually started cluster
// and so should not be run automatically. To run it manually uncomment the
// following line.
@org.scalatest.Ignore
class OutOfProcessQueueServerWebSpec extends AbstractQueueServerWebSpec {

  // Randomize cluster member
  //
  val clusterMemberRestPorts = Array(
    ("localhost", 8091)
    ,("localhost", 8092)
    ,("localhost", 8093)
  )

  def deleteAllQueues(client: HttpClient) = {
    // TODO
    //???
  }

  def withFixture(test: OneArgTest) = {
    val httpClientActorSystem = defaultActorSystem("HttpClient")
    // Choose a random cluster member
    val (hostName, port) = clusterMemberRestPorts(Random.nextInt(clusterMemberRestPorts.size))
    val qsClient = new HttpClient(hostName, port)(httpClientActorSystem)

    val queueServerRestUri = Uri(s"http://${hostName}:${port}")
    val tesa = new MockedTesa(queueServerRestUri)

    try {
      test((qsClient, tesa))
    } finally {
      deleteAllQueues(qsClient)
      tesa.clear()
      qsClient.shutdown()
      httpClientActorSystem.shutdown()
      httpClientActorSystem.awaitTermination()
    }
  }
}
