package com.godbless.lockin.dyn

import com.godbless.lockin.BuildConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** How the app reads the "dynamic" part of latest.json, and which addresses it will ever download from. */
class DynUpdatesParseTest {
    private val storage = "${BuildConfig.SUPABASE_URL}/storage/v1/object/public/updates"

    private fun entry(url: String = "$storage/lockin-dyn-1.zip", sha: String = "ab".repeat(32)) =
        JSONObject()
            .put("dynamicVersion", 1234L).put("stableApiVersion", 55)
            .put("url", url).put("sha256", sha).put("size", 999L).put("notes", "hello")

    private fun latest(type: String = BuildConfig.BUILD_TYPE, e: JSONObject = entry()) =
        JSONObject().put("dynamic", JSONObject().put(type, e))

    @Test fun readsTheEntryForThisBuildType() {
        val info = DynUpdates.parse(latest())
        assertNotNull(info)
        assertEquals(1234L, info!!.dynamicVersion)
        assertEquals(55, info.stableApi)
        assertEquals(999L, info.size)
        assertEquals("hello", info.notes)
        assertEquals("$storage/lockin-dyn-1.zip", info.url)
    }

    @Test fun ignoresTheOtherBuildType() {
        val other = if (BuildConfig.BUILD_TYPE == "release") "debug" else "release"
        assertNull(DynUpdates.parse(latest(type = other)))
    }

    @Test fun nothingPublishedMeansNothingOffered() {
        assertNull(DynUpdates.parse(JSONObject()))
        assertNull(DynUpdates.parse(JSONObject().put("dynamic", JSONObject())))
    }

    @Test fun onlyYourOwnStorageIsEverDownloadedFrom() {
        for (bad in listOf(
            "https://example.com/storage/v1/object/public/updates/x.zip",
            "http://huhhzfbmsdajkppllako.supabase.co/storage/v1/object/public/updates/x.zip", // not https
            "${BuildConfig.SUPABASE_URL}.evil.com/storage/x.zip",                              // look-alike host
            "${BuildConfig.SUPABASE_URL}/auth/v1/x.zip",                                       // not storage
            "",
        )) {
            assertNull("accepted $bad", DynUpdates.parse(latest(e = entry(url = bad))))
        }
    }

    @Test fun anIncompleteEntryIsIgnored() {
        val noSha = entry().also { it.remove("sha256") }
        val noVersion = entry().also { it.remove("dynamicVersion") }
        assertNull(DynUpdates.parse(latest(e = noSha)))
        assertNull(DynUpdates.parse(latest(e = noVersion)))
    }

    @Test fun theChecksumIsComparedInLowerCase() {
        val info = DynUpdates.parse(latest(e = entry(sha = "AB".repeat(32))))
        assertEquals("ab".repeat(32), info!!.sha256)
    }
}
