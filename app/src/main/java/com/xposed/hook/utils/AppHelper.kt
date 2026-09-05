package com.xposed.hook.utils

import android.content.Context
import android.content.pm.ApplicationInfo
import com.xposed.hook.App
import com.xposed.hook.config.Constants
import com.xposed.hook.entity.AppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Created by lin on 2021/8/7.
 */
object AppHelper {

    suspend fun getAppList(): List<AppInfo> = withContext(Dispatchers.IO) {
        val apps = ArrayList<AppInfo>()
        val pm = App.current.packageManager
        val sp =
            App.current.getSharedPreferences(Constants.PREF_FILE_NAME, Context.MODE_PRIVATE)
        val installedPackages = pm.getInstalledPackages(0)
        for (installedPackage in installedPackages) {
            val app = AppInfo()
            app.packageName = installedPackage.packageName
            app.title = installedPackage.applicationInfo.loadLabel(pm).toString()
            app.icon = installedPackage.applicationInfo.loadIcon(pm)
            app.enabled = sp.getBoolean(app.packageName, false)
            app.isSystem = installedPackage.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
            apps.add(app)
        }
        apps.sortWith(
            compareByDescending<AppInfo> { it.enabled }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
                .thenBy { it.packageName }
        )
        apps
    }
}