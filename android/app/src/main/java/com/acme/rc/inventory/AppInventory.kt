package com.acme.rc.inventory

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.os.Process
import com.acme.rc.model.AppInfo
import com.acme.rc.model.TargetApps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object AppInventory {

    suspend fun list(context: Context): List<AppInfo> = withContext(Dispatchers.IO) {
        val launcher = context.getSystemService(LauncherApps::class.java)
        val activities = launcher
            ?.getActivityList(null, Process.myUserHandle())
            .orEmpty()

        activities
            .map { it.applicationInfo }
            .distinctBy { it.packageName }
            .map { info ->
                val packageInfo = try {
                    context.packageManager.getPackageInfo(info.packageName, 0)
                } catch (_: Throwable) {
                    null
                }
                AppInfo(
                    label = info.loadLabel(context.packageManager).toString(),
                    packageName = info.packageName,
                    versionName = packageInfo?.versionName ?: "?",
                    versionCode = packageInfo?.longVersionCode ?: 0L,
                    isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    isTarget = TargetApps.isTarget(info.packageName)
                )
            }
            .sortedWith(
                compareByDescending<AppInfo> { it.isTarget }
                    .thenBy { it.label.lowercase() }
            )
    }

    fun launch(context: Context, packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            context.startActivity(intent)
            true
        } catch (_: Throwable) {
            false
        }
    }
}
