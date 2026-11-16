package com.godbless.lockin.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small key-value store whose values are encrypted with a key that never leaves the phone's keystore.
 *
 * Keystore calls can take tens of milliseconds (and much longer the first time a key is made), so
 * nothing here makes the screen wait: the key is fetched once, values are kept in memory after the
 * first read, writes are encrypted on a background thread, and [warmUp] does the slow part early.
 */
class SecureStore(context: Context) {
    private val prefs = context.getSharedPreferences("lockin_secure", Context.MODE_PRIVATE)
    private val cache = ConcurrentHashMap<String, String>()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "lockin-store").apply { isDaemon = true } }

    private val secretKey: SecretKey by lazy {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey) ?: run {
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            gen.init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            gen.generateKey()
        }
    }

    /** Loads the key and decrypts everything saved, off the main thread, so later reads are instant. */
    fun warmUp() {
        worker.execute {
            try {
                for (name in prefs.all.keys.toList()) {
                    if (!cache.containsKey(name)) decrypt(name)?.let { cache.putIfAbsent(name, it) }
                }
            } catch (_: Exception) {
            }
        }
    }

    fun put(name: String, value: String) {
        cache[name] = value
        worker.execute {
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, secretKey)
                val ct = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
                val packed = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
                prefs.edit().putString(name, packed).apply()
            } catch (e: Exception) {
                prefs.edit().remove(name).apply()
            }
        }
    }

    fun get(name: String): String? {
        cache[name]?.let { return if (it === GONE) null else it }
        if (!prefs.contains(name)) return null
        val v = decrypt(name) ?: return null
        cache.putIfAbsent(name, v)
        return v
    }

    fun remove(name: String) {
        cache[name] = GONE
        worker.execute { prefs.edit().remove(name).apply() }
    }

    private fun decrypt(name: String): String? {
        val packed = prefs.getString(name, null) ?: return null
        return try {
            val parts = packed.split(":")
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ct = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    private companion object {
        const val ALIAS = "lockin_store_key"
        // Marks a name that was just removed, until the background write catches up.
        val GONE = String(charArrayOf('\u0000'))
    }
}
