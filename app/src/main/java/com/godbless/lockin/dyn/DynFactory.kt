package com.godbless.lockin.dyn

import android.annotation.SuppressLint
import android.app.Application
import android.content.pm.ApplicationInfo
import androidx.annotation.RequiresApi
import androidx.core.app.CoreComponentFactory

/**
 * The first thing Android runs in the app, before any of the app's own classes exist. It hands Android a
 * class loader that prefers a downloaded, verified update over the installed code. Android 9 and up only;
 * older phones simply keep the installed code. If anything goes wrong here the installed code runs.
 */
@SuppressLint("RestrictedApi")
@RequiresApi(28)
class DynFactory : CoreComponentFactory() {
    override fun instantiateClassLoader(cl: ClassLoader, aInfo: ApplicationInfo): ClassLoader {
        val base = super.instantiateClassLoader(cl, aInfo)
        return try {
            // The tiny restart process only relaunches the app; it never needs the update.
            if (Application.getProcessName().endsWith(":restart")) base else DynStore.loaderFor(aInfo.dataDir, base) ?: base
        } catch (_: Throwable) {
            base
        }
    }
}
