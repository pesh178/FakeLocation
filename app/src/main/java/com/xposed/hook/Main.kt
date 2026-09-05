package com.xposed.hook

import android.util.Log
import com.xposed.hook.config.Constants
import com.xposed.hook.config.PkgConfig
import com.xposed.hook.core.XposedHolder
import com.xposed.hook.location.LocationHook
import com.xposed.hook.storage.XSharedPreferences
import com.xposed.hook.utils.CellLocationHelper
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import kotlin.concurrent.thread

/**
 * Created by lin on 2017/7/22.
 * libxposed API 102 模块入口。
 */
class Main : XposedModule() {

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        XposedHolder.init(this, param.processName)
    }

    override fun onPackageReady(param: PackageReadyParam) {
        val packageName = param.packageName
        val classLoader = param.classLoader
        thread(name = "FakeLocation-$packageName") {
            try {
                val preferences = XSharedPreferences(
                    BuildConfig.APPLICATION_ID,
                    Constants.PREF_FILE_NAME
                )
                if (!preferences.getBoolean(packageName, false))
                    return@thread

                var defaultLatitude = Constants.DEFAULT_LATITUDE
                var defaultLongitude = Constants.DEFAULT_LONGITUDE
                if (PkgConfig.pkg_dingtalk == packageName) {
                    defaultLatitude = "0"
                    defaultLongitude = "0"
                }
                val prefix = packageName + "_"
                var latitude = 0.0
                var longitude = 0.0
                try {
                    preferences.getString(prefix + "latitude", defaultLatitude)?.let {
                        latitude = it.toDouble()
                    }
                    preferences.getString(prefix + "longitude", defaultLongitude)?.let {
                        longitude = it.toDouble()
                    }
                } catch (e: NumberFormatException) {
                    e.printStackTrace()
                }
                val lac = CellLocationHelper.getLac(preferences, prefix)
                val cid = CellLocationHelper.getCid(preferences, prefix)
                Log.d("FakeLocation", "Preparing hooks for $packageName")
                LocationHook.hookAndChange(packageName, classLoader, latitude, longitude, lac, cid)
            } catch (e: Throwable) {
                XposedHolder.log(e)
            }
        }
    }
}
