package com.godbless.lockin.study

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookIndexTest {
    private val corpus = listOf(
        "Chapter 1: Motion. An object is in motion when its position changes with time. Speed is the distance travelled divided by the time taken.",
        "Velocity is speed in a given direction. Acceleration is the rate at which velocity changes. A car speeding up, slowing down or turning is accelerating.",
        "Newton's second law says the net force on an object equals its mass times its acceleration: F = ma. A bigger force gives a bigger acceleration.",
        "Mass is the amount of matter in an object, measured in kilograms. Weight is the force of gravity on that mass and is measured in newtons.",
        "Energy is the ability to do work. Kinetic energy depends on mass and speed. Potential energy depends on height.",
        "Photosynthesis is how plants make food from light, water and carbon dioxide. It happens in the chloroplasts of leaf cells.",
    )
    private fun index(): BookIndex = BookIndex().also { ix -> corpus.forEachIndexed { i, t -> ix.add(i, t) } }

    @Test fun rankingFindsTheRightPage() {
        val ix = index()
        assertEquals(3, ix.search("what is mass").first().page)
        assertEquals(2, ix.search("how does force change acceleration").first().page)
        assertEquals(listOf(5), ix.search("how do plants make food").map { it.page })
        assertEquals(4, ix.search("kinetic energy").first().page)
        assertEquals(3, ix.search("weight in newtons").first().page)
    }

    @Test fun unrelatedQuestionsMatchNothing() {
        assertTrue(index().search("something unrelated about volcanoes").isEmpty())
        assertTrue(index().search("the and of").isEmpty()) // only stop words
        assertTrue(BookIndex().search("mass").isEmpty()) // an empty book
    }

    @Test fun oneHitPerPageAndExclusionsAreHonoured() {
        val ix = index()
        val hits = ix.search("mass force energy speed", k = 6)
        assertEquals(hits.map { it.page }.distinct(), hits.map { it.page })
        assertTrue(hits.zipWithNext().all { (a, b) -> a.score >= b.score })
        assertEquals(4, ix.search("what is mass", exclude = setOf(3)).first().page)
        assertTrue(ix.search("what is mass", exclude = (0..5).toSet()).isEmpty())
    }

    @Test fun termsAreLowercasedStemmedAndStopWordsDropped() {
        assertEquals(listOf("force", "mov", "object", "accelerat", "quickly"), BookTerms.terms("The forces on moving objects, accelerating quickly!"))
        assertEquals(BookTerms.terms("force"), BookTerms.terms("Forces"))
        assertEquals(listOf("énergie"), BookTerms.terms("L'énergie".replace("L'", ""))) // letters beyond a-z are kept
    }

    @Test fun pagesAreCutAtSentencesAndCapped() {
        val sizes = BookIndex.chunk("Sentence one is here. ".repeat(60) + "\n\nSecond paragraph.").map { it.length }
        assertEquals(listOf(637, 637, 61), sizes)
        assertTrue(BookIndex.chunk("x".repeat(2000)).all { it.length <= 650 }) // no sentence end to cut at
        assertEquals(listOf("A short page."), BookIndex.chunk("A short page.\n"))
        assertTrue(BookIndex.chunk("   \n \n").isEmpty())
    }

    @Test fun pagesWithTextAreKnown() {
        val ix = BookIndex()
        ix.add(3, "Page three has words in it.")
        ix.add(1, "   ")
        ix.add(0, "Page zero too.")
        assertEquals(listOf(0, 3), ix.pagesWithText)
        assertEquals(2, ix.pageCount)
        assertEquals("Page zero too.", ix.pageText(0))
        assertEquals(null, ix.pageText(1))
    }

    @Test fun savedTextRoundTrips() {
        val pages = mapOf(0 to "Line one\nLine two\twith a tab and a \\ backslash", 7 to "Plain", 12 to "Ends with a slash \\")
        assertEquals(pages, BookTextCodec.decode(BookTextCodec.encode(pages)))
        assertEquals(emptyMap<Int, String>(), BookTextCodec.decode(""))
        assertEquals(mapOf(2 to "ok"), BookTextCodec.decode("garbage line\nnot-a-number\tx\n2\tok\n"))
    }
}
