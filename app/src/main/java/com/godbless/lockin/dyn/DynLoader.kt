package com.godbless.lockin.dyn

import dalvik.system.DexClassLoader

/**
 * Loads the app's own classes from the downloaded dex first and falls back to the installed app for
 * everything else. Libraries, the resources class (R) and this dyn package always come from the installed
 * app, so they never differ from what the phone really has.
 */
internal class DynLoader(dexPath: String, parent: ClassLoader) : DexClassLoader(dexPath, null, null, parent) {

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        if (fromUpdate(name)) {
            // getClassLoadingLock is hidden API on Android, so one lock for this loader stands in for it.
            synchronized(this) {
                findLoadedClass(name)?.let { return it }
                try {
                    val c = findClass(name)
                    if (resolve) resolveClass(c)
                    return c
                } catch (_: ClassNotFoundException) {
                    // Not in the update (for example a class that was removed): use the installed one.
                }
            }
        }
        return super.loadClass(name, resolve)
    }

    private fun fromUpdate(name: String): Boolean {
        if (!name.startsWith(App)) return false
        if (name.startsWith(Host)) return false
        val rest = name.substring(App.length)
        return !(rest == "R" || rest.startsWith("R$"))
    }

    private companion object {
        const val App = "com.godbless.lockin."
        const val Host = "com.godbless.lockin.dyn."
    }
}
