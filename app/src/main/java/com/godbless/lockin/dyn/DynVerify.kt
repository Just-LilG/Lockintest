package com.godbless.lockin.dyn

import org.json.JSONObject
import java.io.File
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Decides whether a code bundle may run. A bundle is three files: manifest.json, classes.dex and
 * signature.der. The signature (ECDSA, P-256, SHA-256) covers the manifest bytes, and the manifest holds
 * the dex's checksum, so changing any byte of either one makes the check fail. The public key is built into
 * the app; the private key never leaves the developer's computer.
 *
 * This file is part of the fixed host (package dyn), which an update can't change.
 */
internal object DynVerify {

    class Manifest(val dynamicVersion: Long, val stableApi: Int, val variant: String)

    /** The manifest if everything holds, else null. Never throws. */
    fun check(manifest: ByteArray, signature: ByteArray, dex: File, publicKeyBase64: String, variant: String, stableApi: Int): Manifest? {
        return try {
            if (publicKeyBase64.isEmpty()) return null
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64)))
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(key)
            verifier.update(manifest)
            if (!verifier.verify(signature)) return null

            val j = JSONObject(String(manifest, Charsets.UTF_8))
            if (j.getString("payloadType") != "full") return null
            val files = j.getJSONArray("dexFiles")
            if (files.length() != 1 || files.getJSONObject(0).getString("name") != "classes.dex") return null
            if (j.getString("variant") != variant) return null
            if (j.getInt("stableApiVersion") != stableApi) return null
            if (j.getLong("targetSize") != dex.length()) return null
            val want = j.getString("targetDexSha256").lowercase()
            if (sha256(dex) != want) return null
            Manifest(j.getLong("dynamicVersion"), j.getInt("stableApiVersion"), j.getString("variant"))
        } catch (_: Throwable) {
            null
        }
    }

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
