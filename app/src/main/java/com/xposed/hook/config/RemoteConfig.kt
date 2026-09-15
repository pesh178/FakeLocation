package com.xposed.hook.config

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.xposed.hook.App
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/**
 * 把模块本地配置推送到框架的远程配置（LSPosed 数据库），供被 Hook 进程读取。
 *
 * 目标进程无法访问模块 App 的 ContentProvider：Android 11+ 的 package visibility
 * 只允许调用方自己声明 `<queries>` 的包，模块无法要求任意第三方应用这样做。
 * 框架的远程配置通道由 LSPosed 主动下发 binder，与被 Hook 应用的可见性无关。
 */
object RemoteConfig {
    private const val TAG = "RemoteConfig"

    private val lock = Any()
    private var service: XposedService? = null
    private var started = false

    /** SharedPreferences 的变更监听是弱引用，必须由本对象强持有。 */
    private val localListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> sync() }

    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
        }
        localPreferences().registerOnSharedPreferenceChangeListener(localListener)
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                synchronized(lock) { this@RemoteConfig.service = service }
                sync()
            }

            override fun onServiceDied(service: XposedService) {
                synchronized(lock) {
                    if (this@RemoteConfig.service === service) this@RemoteConfig.service = null
                }
            }
        })
    }

    /** 把本地配置整体镜像到远程配置；框架未连接时静默跳过。 */
    fun sync() {
        val bound = synchronized(lock) { service } ?: return
        try {
            val remote = bound.getRemotePreferences(Constants.PREF_FILE_NAME).edit()
            remote.clear()
            for ((key, value) in localPreferences().all) {
                when (value) {
                    is String -> remote.putString(key, value)
                    is Boolean -> remote.putBoolean(key, value)
                    is Long -> remote.putLong(key, value)
                    is Int -> remote.putInt(key, value)
                    is Float -> remote.putFloat(key, value)
                    else -> Log.w(TAG, "skip unsupported preference $key")
                }
            }
            remote.apply()
        } catch (e: Throwable) {
            Log.w(TAG, "sync failed", e)
        }
    }

    private fun localPreferences(): SharedPreferences =
        App.current.getSharedPreferences(Constants.PREF_FILE_NAME, Context.MODE_PRIVATE)
}
