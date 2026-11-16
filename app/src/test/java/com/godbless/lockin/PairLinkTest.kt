package com.godbless.lockin

import com.godbless.lockin.data.PairLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairLinkTest {
    @Test fun plainCodes() {
        assertEquals("K7M2QX9P", PairLink.parse("K7M2QX9P"))
        assertEquals("K7M2QX9P", PairLink.parse(" k7m2 qx9p "))
        assertEquals("K7M2QX9P", PairLink.parse("K7M2-QX9P"))
    }

    @Test fun ourLinks() {
        assertEquals("K7M2QX9P", PairLink.parse("https://lockin.secrets.quest/join/K7M2QX9P"))
        assertEquals("K7M2QX9P", PairLink.parse("https://LockIn.Secrets.Quest/join/k7m2qx9p?utm=x"))
        assertEquals("K7M2QX9P", PairLink.parse("lockin://pair/K7M2QX9P"))
        assertEquals("K7M2QX9P", PairLink.parse("lockin://pair?code=K7M2QX9P"))
    }

    @Test fun otherLinksAreNotOurs() {
        assertNull(PairLink.parse("https://evil.example/join/K7M2QX9P"))
        assertNull(PairLink.parse("http://lockin.secrets.quest.evil.example/join/K7M2QX9P"))
        assertNull(PairLink.parse("https://lockin.secrets.quest/other/K7M2QX9P"))
        assertNull(PairLink.parse("lockin://auth/callback"))
    }

    @Test fun badCodes() {
        assertNull(PairLink.parse(""))
        assertNull(PairLink.parse(null))
        assertNull(PairLink.parse("K7M2QX9"))
        assertNull(PairLink.parse("K7M2QX9PP"))
        assertNull(PairLink.parse("K7M2QX90")) // 0 and 1 and O and I are never in a code
    }

    @Test fun linksRoundTrip() {
        assertEquals("K7M2QX9P", PairLink.parse(PairLink.web("K7M2QX9P")))
        assertEquals("K7M2QX9P", PairLink.parse(PairLink.app("K7M2QX9P")))
    }
}
