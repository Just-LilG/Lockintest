package com.godbless.lockin.dyn

import com.godbless.lockin.BuildConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

/**
 * A last look at a real code update before you upload it. Skipped unless OTA_DIR points at a folder that
 * holds the bundle (lockin-dyn-*.zip) and its latest.json. It checks them with this tree's own key and
 * STABLE_API, so run it from the same sources the installed app was built from:
 *
 *   OTA_DIR=/path/to/folder ./gradlew testReleaseUnitTest --tests '*DynRealBundleTest'
 *
 * Optionally set OTA_BASE_VERSION to the installed app's DYNAMIC_VERSION to also check the update is newer.
 */
class DynRealBundleTest {
    private val dir: File? = System.getenv("OTA_DIR")?.let(::File)

    private class Parts(val manifest: ByteArray, val signature: ByteArray, val dex: File, val zip: File)

    private fun parts(): Parts {
        assumeTrue("OTA_DIR not set: nothing to check", dir != null)
        val zip = dir!!.listFiles { f -> f.name.startsWith("lockin-dyn-") && f.extension == "zip" }.orEmpty().singleOrNull()
            ?: error("Expected exactly one lockin-dyn-*.zip in $dir")
        val tmp = Files.createTempDirectory("realbundle").toFile()
        ZipFile(zip).use { z ->
            for (n in listOf("manifest.json", "classes.dex", "signature.der")) {
                val e = z.getEntry(n) ?: error("The bundle has no $n")
                z.getInputStream(e).use { File(tmp, n).writeBytes(it.readBytes()) }
            }
        }
        return Parts(File(tmp, "manifest.json").readBytes(), File(tmp, "signature.der").readBytes(), File(tmp, "classes.dex"), zip)
    }

    @Test fun theBundlePassesTheAppsOwnCheck() {
        val p = parts()
        val m = DynVerify.check(p.manifest, p.signature, p.dex, BuildConfig.DYNAMIC_PUBKEY, BuildConfig.BUILD_TYPE, BuildConfig.STABLE_API)
        assertNotNull("The app would refuse this bundle (wrong key, build number or build type, or it was changed)", m)
        System.getenv("OTA_BASE_VERSION")?.toLongOrNull()?.let {
            assertTrue("The update (${m!!.dynamicVersion}) is not newer than the installed build ($it)", m.dynamicVersion > it)
        }
    }

    @Test fun latestJsonMatchesTheBundleFile() {
        val p = parts()
        val j = JSONObject(File(dir!!, "latest.json").readText()).getJSONObject("dynamic").getJSONObject(BuildConfig.BUILD_TYPE)
        assertEquals("sha256 in latest.json", j.getString("sha256"), DynVerify.sha256(p.zip))
        assertEquals("size in latest.json", j.getLong("size"), p.zip.length())
        assertTrue("url must end with the bundle's file name", j.getString("url").endsWith("/" + p.zip.name))
    }

    @Test fun theAppAcceptsLatestJson() {
        parts()
        val info = DynUpdates.parse(JSONObject(File(dir!!, "latest.json").readText()))
        assertNotNull("The app ignores this latest.json", info)
        assertEquals(BuildConfig.STABLE_API, info!!.stableApi)
    }
}
