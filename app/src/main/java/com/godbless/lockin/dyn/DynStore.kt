package com.godbless.lockin.dyn

import android.content.Context
import com.godbless.lockin.BuildConfig
import java.io.File

/**
 * Where downloaded code lives and whether it is allowed to run.
 *
 *  staged.*   downloaded and verified, waiting for the next start of the app
 *  active.*   what this start of the app is running (dex, manifest, signature)
 *  boot       "version,starts,ok": how many starts the active version has had without being confirmed healthy
 *  bad        the newest version that crashed on start; it and anything older is never loaded or offered again
 *
 * A bad update can't brick the app: a version that doesn't reach [confirm] within a few starts is thrown
 * away and the installed code runs instead.
 */
internal object DynStore {

    /** The dynamic version this process is running; 0 until a downloaded version is loaded. */
    @Volatile private var loaded = 0L

    /** The version of the code running right now: the downloaded one, else the one built into the app. */
    fun running(): Long = if (loaded > 0L) loaded else BuildConfig.DYNAMIC_VERSION

    private fun dir(context: Context) = File(context.filesDir, "dyn")

    // ----- At process start, before any app class exists (called from DynFactory) -----

    fun loaderFor(dataDir: String, parent: ClassLoader): ClassLoader? {
        val dir = File(dataDir, "files/dyn")
        if (!dir.isDirectory) return null
        promote(dir)
        val dex = File(dir, "active.dex")
        val manifest = File(dir, "active.json")
        val sig = File(dir, "active.sig")
        if (!dex.isFile || !manifest.isFile || !sig.isFile) return null

        val m = DynVerify.check(
            manifest.readBytes(), sig.readBytes(), dex,
            BuildConfig.DYNAMIC_PUBKEY, BuildConfig.BUILD_TYPE, BuildConfig.STABLE_API,
        )
        // Not valid, built for a different app, not newer than the installed code, or already known bad.
        if (m == null || m.dynamicVersion <= BuildConfig.DYNAMIC_VERSION || m.dynamicVersion <= badVersion(dir)) {
            discard(dir, remember = false)
            return null
        }

        // Starts without a "healthy" confirmation. Three in a row means the update is crashing.
        val boot = readBoot(dir)
        val starts = if (boot.first == m.dynamicVersion && boot.third) 0 else if (boot.first == m.dynamicVersion) boot.second + 1 else 1
        if (starts > MaxStarts) {
            discard(dir, remember = true, version = m.dynamicVersion)
            return null
        }
        if (!(boot.first == m.dynamicVersion && boot.third)) writeBoot(dir, m.dynamicVersion, starts, false)

        dex.setReadOnly() // Android 14 refuses to load dex files that can be written to.
        loaded = m.dynamicVersion
        return DynLoader(dex.path, parent)
    }

    /** A staged bundle becomes the active one. Done by whichever process starts first. */
    private fun promote(dir: File) {
        val dex = File(dir, "staged.dex")
        val manifest = File(dir, "staged.json")
        val sig = File(dir, "staged.sig")
        if (!dex.isFile || !manifest.isFile || !sig.isFile) return
        File(dir, "active.dex").delete()
        dex.renameTo(File(dir, "active.dex"))
        manifest.renameTo(File(dir, "active.json"))
        sig.renameTo(File(dir, "active.sig"))
        File(dir, "boot").delete()
    }

    private fun discard(dir: File, remember: Boolean, version: Long = 0L) {
        if (remember && version > 0L) File(dir, "bad").writeText(version.toString())
        listOf("active.dex", "active.json", "active.sig", "boot").forEach { File(dir, it).delete() }
    }

    private fun badVersion(dir: File): Long = File(dir, "bad").takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull() ?: 0L

    private fun readBoot(dir: File): Triple<Long, Int, Boolean> = try {
        val p = File(dir, "boot").readText().trim().split(',')
        Triple(p[0].toLong(), p[1].toInt(), p[2] == "1")
    } catch (_: Throwable) {
        Triple(0L, 0, false)
    }

    private fun writeBoot(dir: File, version: Long, starts: Int, ok: Boolean) {
        File(dir, "boot").writeText("$version,$starts,${if (ok) 1 else 0}")
    }

    private const val MaxStarts = 3

    // ----- While the app runs -----

    /** The app has been up long enough to call this version healthy. Call it a few seconds after start. */
    fun confirm(context: Context) {
        val v = loaded
        if (v <= 0L) return
        runCatching { writeBoot(dir(context), v, 0, true) }
    }

    /** Version of a bundle that is downloaded and waiting for a restart, or 0. */
    fun stagedVersion(context: Context): Long = runCatching {
        org.json.JSONObject(File(dir(context), "staged.json").readText()).getLong("dynamicVersion")
    }.getOrDefault(0L)

    fun bad(context: Context): Long = badVersion(dir(context))

    /** Puts a verified bundle in place for the next start. */
    fun stage(context: Context, manifest: ByteArray, signature: ByteArray, dex: File) {
        val d = dir(context).apply { mkdirs() }
        listOf("staged.dex", "staged.json", "staged.sig").forEach { File(d, it).delete() }
        // The manifest and signature go in last: they are what [promote] waits for.
        dex.copyTo(File(d, "staged.dex"), overwrite = true)
        File(d, "staged.dex").setReadOnly()
        File(d, "staged.json").writeBytes(manifest)
        File(d, "staged.sig").writeBytes(signature)
    }
}
