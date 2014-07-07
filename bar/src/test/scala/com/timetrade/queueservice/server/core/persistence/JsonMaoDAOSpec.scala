package com.timetrade.queueservice.server.core.persistence

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Random

import spray.json._

import org.scalacheck.Gen

import org.scalactic.TypeCheckedTripleEquals

import org.scalatest.Matchers
import org.scalatest.concurrent.Futures
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.fixture.FunSpec
import org.scalatest.time.Millis
import org.scalatest.time.Seconds
import org.scalatest.time.Span

import com.timetrade.queueservice.server.testtraits.ConfigurableActorSystems
import com.timetrade.queueservice.testtraits.TimedSuite

/** Tests for JsonMapDAO. */
class JsonMapDAOSpec
  extends FunSpec
  with Matchers
  with Futures
  with ScalaFutures
  with TypeCheckedTripleEquals
  with ConfigurableActorSystems
  with TimedSuite {

  case class StringInABox(s: String)
  object StringInABox extends DefaultJsonProtocol {
    implicit val _ = jsonFormat1(apply)
  }

  type ValueType = StringInABox

  type MapType = Map[ValueType, ValueType]
  type DaoType = JsonMapDAO[ValueType, ValueType]

  type FixtureParam = DaoType

  def withFixture(test: OneArgTest) = {

    val system = defaultActorSystem()
    val datastore = Datastore.defaultForTesting(system)
    val dao = new JsonMapDAO[StringInABox, StringInABox](datastore, "JsonMapDAOSpec")

    try {
      test(dao)
    } finally {
      system.shutdown()
      datastore.close()
    }
  }

  // Configure the behavior of the "whenReady" calls below.
  implicit val defaultPatience = PatienceConfig(timeout =  Span(30, Seconds),
                                                interval = Span(1, Millis))

  describe ("A JsonMapDAO") {

    def createRandomPairs(size: Int): MapType = {

      // Use Scalacheck random data generation facilities.
      val valueGen: Gen[ValueType] = for { u <- Gen.uuid } yield StringInABox(u.toString)
      val valuePairGen = for {
        k <- valueGen
        v <- valueGen
      } yield (k, v)

      Gen.mapOfN(size, valuePairGen)
        // Necessary because a Gen can return None.
        .retryUntil(!_.isEmpty)
        .sample
        .get
    }

    def storeRandomPairs(dao: DaoType): Map[StringInABox, StringInABox] = {
      val result = createRandomPairs(100)
      await(dao.populateFromMap(result))
      result
    }

    def await[T](f: Future[T]): Unit = {
      implicit val _ = ExecutionContext.global
      f onFailure { case t: Throwable => t.printStackTrace }
      whenReady(f){ _ => }
    }

    it ("""should create its table when first used""") { dao =>
      await(dao.becomeReady)
      whenReady(dao.datastore.tableExists(dao.tableName)) { _ should be (true) }
    }

    it ("""should "put" and "getAll" data as expected""") { dao =>

      val data = createRandomPairs(10)
      data foreach { case (k, v) =>  await(dao.put(k, v)) }


      whenReady(dao.getAll) { pairs =>
        val dataAsRead = Map(pairs:_*)
        dataAsRead.toSet should === (data.toSet)
      }
    }

    it ("""should "put" the same data twice as expected""") { dao =>

      val data = createRandomPairs(10)
      data foreach { case (k, v) =>  await(dao.put(k, v)) }
      data foreach { case (k, v) =>  await(dao.put(k, v)) }

      whenReady(dao.getAll) { pairs =>
        val dataAsRead = Map(pairs:_*)
        dataAsRead.toSet should === (data.toSet)
      }
    }

    it ("""should "get" stored data as expected""") { dao =>

      val data = storeRandomPairs(dao)

      // Check each (K, V) pair that was stored
      data foreach { case (k, v) =>
        whenReady(dao.get(k)) { vOpt =>
          vOpt should === (Some(v))
        }
      }
    }

    it ("""should "deleteAll" data as expected""") { dao =>

      val data = storeRandomPairs(dao)

      await(dao.removeAll())

      whenReady(dao.getAll) { pairs =>
        pairs should have size (0)
      }
    }

    it ("""should "delete" data as expected""") { dao =>

      val data = storeRandomPairs(dao)

      val keyToDelete = data.head._1
      await(dao.remove(keyToDelete))

      whenReady(dao.get(keyToDelete)) { lookupResult =>
        lookupResult should === (None)
      }
    }

    it ("""should "retain" data as expected""") { dao =>

      val data = storeRandomPairs(dao)

      val predicate = { (k: ValueType, v: ValueType) => v.s.contains("z") }

      await(dao.retain(predicate))

      val expectedContents = data.filter(predicate.tupled)
      whenReady(dao.getAll) { contentsAsRead =>
         contentsAsRead.toMap should === (expectedContents)
      }
    }


    it ("""should "populateFromMap" as expected""") { dao =>

      // Create random data and store it.
      val data = createRandomPairs(200)
      await(dao.populateFromMap(data))

      // Read it back.
      whenReady(dao.getAll) { dataAsRead =>
        dataAsRead should have size (data.size)
      }

      // Check each (K, V) pair that was stored
      data foreach { case (k, v) =>
        whenReady(dao.get(k)) { vOpt =>
          vOpt should === (Some(v))
        }
      }
    }

    it ("""should "populateFromMap" repeatedly as expected""") { dao =>

        (1 to 5) foreach { _ =>
          val size = Random.nextInt(200)
          val data = createRandomPairs(size)

          // Store it directly from map.
          await(dao.populateFromMap(data))

          // Read it back.
          whenReady(dao.size) { size => size should === (size) }

          whenReady(dao.getAll) { _ should have size (data.size) }

          // Check each (K, V) pair that was stored
          data foreach {
            case (k, v) =>
              whenReady(dao.get(k)) { vOpt =>
                vOpt should === (Some(v))
              }
          }
        }
    }

    ignore ("""should "size" data as expected""") { dao =>
      val size = Random.nextInt(200)
      val data = createRandomPairs(size)

      // Store it directly from map.
      await(dao.populateFromMap(data))

      // Check its size.
      whenReady(dao.size) { _ should === (size) }
    }
  }
}
