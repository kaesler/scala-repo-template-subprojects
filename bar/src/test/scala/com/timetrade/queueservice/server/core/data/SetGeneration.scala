package com.timetrade.queueservice.server.core.data

import org.scalacheck.Arbitrary
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen

/* ScalaCheck generator for sets. */
trait SetGeneration {

  /** Returns a Gen capable of producing a Set of elements of a specified size.
    * @tparam T a type for which there is an Arbitrary type class instance.
    * @param n the size of set desired
    */
  def genSetOfN[T : Arbitrary](n: Int, alreadyGenerated: Set[T] = Set[T]()): Gen[Set[T]] = {
    require (alreadyGenerated.size <= n)
    if (alreadyGenerated.size == n) alreadyGenerated
    else {
      val newOne =
        arbitrary[T]
          .retryUntil { t => !alreadyGenerated.contains(t) }
          .sample
          .get
      genSetOfN(n, alreadyGenerated + newOne)
    }
  }
}

object SetGeneration extends SetGeneration

