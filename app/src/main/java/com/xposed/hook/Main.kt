package com.xposed.hook

import android.content.SharedPreferences
import com.xposed.hook.config.Constants
import com.xposed.hook.config.PkgConfig
import com.xposed.hook.core.XposedHolder
import com.xposed.hook.location.LocationHook
import com.xposed.hook.utils.CellLocationHelper
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

internal object CoordinateParser {
    fun parse(value: String?, fallback: String, minimum: Double, maximum: Double): Double {
        return parsed(value, minimum, maximum) ?: fallback.toDouble()
    }

    /** @return whether [value] is a finite number inside the inclusive [minimum]..[maximum] range. */
    fun isValid(value: String?, minimum: Double, maximum: Double): Boolean =
        parsed(value, minimum, maximum) != null

    private fun parsed(value: String?, minimum: Double, maximum: Double): Double? =
        value?.toDoubleOrNull()?.takeIf { it.isFinite() && it in minimum..maximum }
}

/**
 * Created by lin on 2017/7/22.
 * libxposed API 102 模块入口。
 */
class Main : XposedModule() {

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        XposedHolder.init(this, param.processName)
    }

    /**
     * 通过框架的远程配置读取模块 SharedPreferences：不经过 ContentProvider，
     * 因此不受目标应用 package visibility（targetSdk 30+ 的 <queries>）限制。
     * Hook 必须在此同步安装，否则目标进程启动瞬间会取到真实定位。
     */
    override fun onPackageReady(param: PackageReadyParam) {
        val packageName = param.packageName
        val classLoader = param.classLoader
        try {
            val preferences: SharedPreferences = getRemotePreferences(Constants.PREF_FILE_NAME)
            if (!preferences.getBoolean(packageName, false)) return

            val defaultLatitude = if (PkgConfig.pkg_dingtalk == packageName) {
                "0"
            } else {
                Constants.DEFAULT_LATITUDE
            }
            val defaultLongitude = if (PkgConfig.pkg_dingtalk == packageName) {
                "0"
            } else {
                Constants.DEFAULT_LONGITUDE
            }
            val prefix = packageName + "_"
            val latitude = CoordinateParser.parse(
                preferences.getString(prefix + "latitude", defaultLatitude),
                defaultLatitude,
                -90.0,
                90.0
            )
            val longitude = CoordinateParser.parse(
                preferences.getString(prefix + "longitude", defaultLongitude),
                defaultLongitude,
                -180.0,
                180.0
            )
            val lac = CellLocationHelper.getLac(preferences, prefix)
            val cid = CellLocationHelper.getCid(preferences, prefix)
            LocationHook.hookAndChange(packageName, classLoader, latitude, longitude, lac, cid)
        } catch (e: Throwable) {
            XposedHolder.log(e)
        }
    }
}
