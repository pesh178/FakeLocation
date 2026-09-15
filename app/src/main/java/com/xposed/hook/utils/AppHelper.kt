package com.xposed.hook.utils

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.drawable.Drawable
import com.xposed.hook.App
import com.xposed.hook.config.Constants
import com.xposed.hook.entity.AppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Created by lin on 2021/8/7.
 */
object AppHelper {

    /** Enabled apps first, then by title, then by package name. */
    val enabledFirstOrder: Comparator<AppInfo> =
        compareByDescending<AppInfo> { it.enabled }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
            .thenBy { it.packageName }

    private val lock = Any()

    /**
     * Resolved label/icon per package. `loadIcon` decodes the full drawable, which dominates a
     * scan, so entries survive across scans until the package itself is updated.
     */
    private var resolved = HashMap<String, Resolved>()

    private class Resolved(
        val lastUpdateTime: Long,
        val title: String,
        val icon: Drawable?,
        val isSystem: Boolean
    )

    suspend fun getAppList(): List<AppInfo> = withContext(Dispatchers.IO) {
        synchronized(lock) { scan() }
    }

    private fun scan(): List<AppInfo> {
        val pm = App.current.packageManager
        val sp = App.current.getSharedPreferences(Constants.PREF_FILE_NAME, Context.MODE_PRIVATE)
        val installedPackages = pm.getInstalledPackages(0)
        val previous = resolved
        val current = HashMap<String, Resolved>(installedPackages.size)
        val apps = ArrayList<AppInfo>(installedPackages.size)
        for (installedPackage in installedPackages) {
            val info = installedPackage.applicationInfo ?: continue
            val packageName = installedPackage.packageName
            val cached = previous[packageName]
            val entry = if (cached != null && cached.lastUpdateTime == installedPackage.lastUpdateTime) {
                cached
            } else {
                resolve(installedPackage, info)
            }
            current[packageName] = entry
            val app = AppInfo()
            app.packageName = packageName
            app.title = entry.title
            app.icon = entry.icon
            app.isSystem = entry.isSystem
            app.enabled = sp.getBoolean(packageName, false)
            apps.add(app)
        }
        resolved = current
        apps.sortWith(enabledFirstOrder)
        return apps
    }

    private fun resolve(installedPackage: PackageInfo, info: ApplicationInfo): Resolved {
        val pm = App.current.packageManager
        return Resolved(
            installedPackage.lastUpdateTime,
            info.loadLabel(pm).toString(),
            info.loadIcon(pm),
            info.flags and ApplicationInfo.FLAG_SYSTEM != 0
        )
    }
}
