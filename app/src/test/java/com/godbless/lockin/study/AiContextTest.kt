package com.godbless.lockin.study

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiContextTest {
    private fun card(id: String, book: String, q: String, right: Int, wrong: Int, last: Long = 0L) =
        Card(id, book, q, "a", 0, 0, 0L, right, wrong, last)

    @Test fun weakTopicsAreTheMostMissedOfThisBook() {
        val cards = listOf(
            card("1", "b", "What is net force?", right = 0, wrong = 3, last = 5),
            card("2", "b", "Define   mass\nin words", right = 1, wrong = 3),
            card("3", "b", "Balanced: right as often as wrong", right = 2, wrong = 2),
            card("4", "other", "Another book's question", right = 0, wrong = 9),
            card("5", "b", "Never answered", right = 0, wrong = 0),
            card("6", "b", "What is net force?", right = 0, wrong = 4), // the same question again counts once
        )
        assertEquals(listOf("What is net force?", "Define mass in words"), AiContext.weakTopics(cards, "b"))
        assertEquals(1, AiContext.weakTopics(cards, "b", limit = 1).size)
        assertTrue(AiContext.weakTopics(emptyList(), "b").isEmpty())
    }

    @Test fun samplePagesSpreadAcrossTheBook() {
        val all = (0 until 100).toList()
        val s = AiContext.samplePages(all, 12)
        assertEquals(12, s.size)
        assertEquals(0, s.first())
        assertTrue(s.zipWithNext().all { (a, b) -> b > a })
        assertTrue(s.last() > 80)
        assertEquals(listOf(2, 5, 9), AiContext.samplePages(listOf(2, 5, 9)))
        assertTrue(AiContext.samplePages(emptyList()).isEmpty())
    }

    @Test fun notesAreSummaryThenTerms() {
        assertEquals("About forces.\nKey terms: force: a push; mass: amount of matter", AiContext.notesText("About forces. ", listOf("force" to "a push", "mass" to "amount of matter")))
        assertEquals("Just a summary.", AiContext.notesText("Just a summary.", emptyList()))
    }

    @Test fun contextHoldsThePageItsNeighboursAndRelatedPassages() {
        val ix = BookIndex()
        ix.add(0, "Motion: an object moves when its position changes. ".repeat(3))
        ix.add(1, "Velocity is speed with a direction.")
        ix.add(2, "Newton's second law: force equals mass times acceleration.")
        ix.add(3, "Mass is the amount of matter in an object, measured in kilograms.")
        ix.add(4, "Energy is the ability to do work.")
        val c = AiContext.build(ix, page = 2, question = "what is mass", notes = "Forces and motion.", weak = listOf("What is net force?"))
        assertEquals("Newton's second law: force equals mass times acceleration.", c.ptext)
        assertEquals("Velocity is speed with a direction.", c.prev)
        assertEquals("Mass is the amount of matter in an object, measured in kilograms.", c.next)
        assertEquals(4, c.refs.first().page) // numbered from 1, as the student sees pages: index 3 is page 4
        assertFalse(c.refs.any { it.page == 3 }) // never the page already being read
        assertEquals("Forces and motion.", c.notes)
        assertFalse(c.isEmpty())
    }

    @Test fun contextIsCappedAndOptional() {
        val ix = BookIndex()
        ix.add(0, "word ".repeat(2000))
        ix.add(1, "y".repeat(5000))
        val c = AiContext.build(ix, 0, "", "n".repeat(2000), emptyList())
        assertEquals(3500, c.ptext.length)
        assertEquals(800, c.notes.length)
        assertEquals(900, c.next.length)
        assertTrue(c.refs.isEmpty()) // no question: nothing to look up
        assertTrue(AiContext.build(null, 0, "", "", emptyList()).isEmpty())
    }
}
