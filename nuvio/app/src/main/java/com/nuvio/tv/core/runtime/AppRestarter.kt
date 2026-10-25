package com.nuvio.tv.core.runtime

import android.content.Context
import android.net.Uri
import android.os.Process
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.system.exitProcess

@Singleton
class AppRestarter @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    /** [data]: optional deep link the restarted app opens (e.g. `nuvio://account`). */
    fun restart(data: Uri? = null): Boolean {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return false
        if (data != null) {
            launchIntent.action = android.content.Intent.ACTION_VIEW
            launchIntent.data = data
        }
        launchIntent.addFlags(
            android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
        )
        // Vibe fork: start the new task now, while this process is still on screen, and let the
        // system bring it up in a fresh process. An alarm firing after the process is gone is a
        // background activity start, which Android 14 blocks (BAL_BLOCK).
        context.startActivity(launchIntent)
        Process.killProcess(Process.myPid())
        exitProcess(0)
    }
}
