package com.timetrade.queueservice.server.core.definitioncrud

import org.scalactic.TypeCheckedTripleEquals

import org.scalatest.FunSpec
import org.scalatest.Matchers
import org.scalatest.prop.PropertyChecks

import com.timetrade.queueservice.testtraits.TimedSuite

/** Property-based test for the database-row level representation of a QueueDefinition. */
class QueueDefinitionDAODataRepresentationSpec
  extends FunSpec
  with Matchers
  with PropertyChecks
  with TypeCheckedTripleEquals
  with TimedSuite
  with QueueDefinitionGeneration {

  describe ("A QueueDefinitionDAO's row-level data representation") {

    it ("should be invertible") {

      forAll { (id: QueueId, definition: QueueDefinition) =>
        val asRow = QueueDefinitionDAO.toRowFormat((id, definition))
        asRow should !== (None)
        try {
          QueueDefinitionDAO.fromRowFormat(asRow.get) should === ((id, definition))
        } catch {
          case t: Throwable =>
            t.printStackTrace()
            throw t
        }
      }
    }
  }
}
