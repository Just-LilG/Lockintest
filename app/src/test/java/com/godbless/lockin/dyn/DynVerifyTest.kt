package com.godbless.lockin.dyn

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.util.Base64

/**
 * The gate every downloaded code update has to pass. These build a real signed bundle on the fly (same
 * format as scripts/publish-dynamic.sh) and check that exactly the right ones get through.
 */
class DynVerifyTest {

    private class Bundle(
        val manifest: ByteArray,
        val signature: ByteArray,
        val dex: File,
        val publicKey: String,
        val privateKey: PrivateKey,
    )

    private fun newKeys() = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()

    private fun sign(key: PrivateKey, data: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").apply { initSign(key); update(data) }.sign()

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** A correctly signed bundle; each parameter lets a test break exactly one thing. */
    private fun bundle(
        version: Long = 2000L,
        stable: Int = 111,
        variant: String = "release",
        payloadType: String = "full",
        dexName: String = "classes.dex",
        dexCount: Int = 1,
        dexBytes: ByteArray = ByteArray(4096) { (it * 31).toByte() },
    ): Bundle {
        val keys = newKeys()
        val dir = Files.createTempDirectory("dynverify").toFile()
        val dex = File(dir, "classes.dex").apply { writeBytes(dexBytes) }
        val digest = sha(dexBytes)
        val files = JSONArray()
        repeat(dexCount) {
            files.put(JSONObject().put("name", if (it == 0) dexName else "classes${it + 1}.dex").put("payload", "classes.dex").put("targetSha256", digest).put("targetSize", dexBytes.size))
        }
        val manifest = JSONObject()
            .put("payloadType", payloadType).put("variant", variant)
            .put("dynamicVersion", version).put("stableApiVersion", stable)
            .put("targetDexSha256", digest).put("targetSize", dexBytes.size)
            .put("dexFiles", files).put("hashes", JSONObject().put("classes.dex", digest))
            .toString().toByteArray()
        return Bundle(manifest, sign(keys.private, manifest), dex, Base64.getEncoder().encodeToString(keys.public.encoded), keys.private)
    }

    private fun Bundle.check(
        manifest: ByteArray = this.manifest,
        signature: ByteArray = this.signature,
        dex: File = this.dex,
        key: String = publicKey,
        variant: String = "release",
        stable: Int = 111,
    ) = DynVerify.check(manifest, signature, dex, key, variant, stable)

    @Test fun acceptsAGoodBundle() {
        val m = bundle(version = 2468L).check()
        assertNotNull(m)
        assertEquals(2468L, m!!.dynamicVersion)
        assertEquals(111, m.stableApi)
        assertEquals("release", m.variant)
    }

    @Test fun rejectsAChangedByteInTheCode() {
        val b = bundle()
        val tampered = File(b.dex.parentFile, "tampered.dex").apply { writeBytes(b.dex.readBytes().also { it[100] = (it[100] + 1).toByte() }) }
        assertNull(b.check(dex = tampered))
    }

    @Test fun rejectsCodeOfADifferentLength() {
        val b = bundle()
        val longer = File(b.dex.parentFile, "longer.dex").apply { writeBytes(b.dex.readBytes() + 0) }
        assertNull(b.check(dex = longer))
    }

    @Test fun rejectsAChangedManifestEvenWithAGoodSignatureOnTheOldOne() {
        val b = bundle(version = 2000L)
        val edited = JSONObject(String(b.manifest)).put("dynamicVersion", 9_999_999L).toString().toByteArray()
        assertNull(b.check(manifest = edited))
    }

    @Test fun rejectsASignatureFromAnotherKey() {
        val b = bundle()
        assertNull(b.check(signature = sign(newKeys().private, b.manifest)))
    }

    @Test fun rejectsAMissingOrGarbageSignature() {
        val b = bundle()
        assertNull(b.check(signature = ByteArray(0)))
        assertNull(b.check(signature = ByteArray(70) { 7 }))
    }

    @Test fun rejectsAnotherBuildNumber() {
        val b = bundle(stable = 111)
        assertNull(b.check(stable = 112))
        assertNull(b.check(stable = 0))
    }

    @Test fun rejectsAnotherBuildType() {
        assertNull(bundle(variant = "debug").check(variant = "release"))
        assertNull(bundle(variant = "release").check(variant = "debug"))
    }

    @Test fun acceptsNothingWhenTheAppHasNoKey() {
        assertNull(bundle().check(key = ""))
    }

    @Test fun anUnreadableKeyIsARefusalNotACrash() {
        val b = bundle()
        assertNull(b.check(key = "this is not base64!"))
        assertNull(b.check(key = Base64.getEncoder().encodeToString(ByteArray(40) { it.toByte() })))
    }

    @Test fun rejectsAnythingButAFullPayload() {
        assertNull(bundle(payloadType = "delta").check())
    }

    @Test fun rejectsMoreThanOneCodeFile() {
        assertNull(bundle(dexCount = 2).check())
    }

    @Test fun rejectsACodeFileWithAnotherName() {
        assertNull(bundle(dexName = "payload.dex").check())
    }

    @Test fun aSignedButUnreadableManifestIsRefused() {
        val b = bundle()
        val junk = "this is not json".toByteArray()
        assertNull(b.check(manifest = junk, signature = sign(b.privateKey, junk)))
    }

    @Test fun checksumsComeOutRight() {
        val f = File(Files.createTempDirectory("sha").toFile(), "abc.txt").apply { writeText("abc") }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", DynVerify.sha256(f))
    }
}
