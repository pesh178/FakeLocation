package com.xposed.hook.config

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.xposed.hook.App
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

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

    /**
     * 串行执行远程同步。镜像是一次全量 clear + 写入，两次并发的全量写可能乱序提交并让旧快照
     * 覆盖新快照，因此只用这一个单线程执行器作为提交点。
     */
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "RemoteConfigSync").apply { isDaemon = true }
    }

    /** 是否有一次同步尚未完成（已排队或正在执行）。 */
    private val pending = AtomicBoolean(false)

    /** SharedPreferences 的变更监听是弱引用，必须由本对象强持有。 */
    private val localListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> scheduleSync() }

    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
        }
        localPreferences().registerOnSharedPreferenceChangeListener(localListener)
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                synchronized(lock) { this@RemoteConfig.service = service }
                scheduleSync()
            }

            override fun onServiceDied(service: XposedService) {
                synchronized(lock) {
                    if (this@RemoteConfig.service === service) this@RemoteConfig.service = null
                }
            }
        })
    }

    /**
     * 偏好写入发生在主线程，而镜像要跨进程写 LSPosed 数据库，所以同步在后台线程串行进行。
     * 同步期间发生的变更会让下一轮重新排队，既不会丢更新，也不会产生并发的全量写。
     */
    private fun scheduleSync() {
        if (!pending.compareAndSet(false, true)) return
        executor.execute {
            // 先清标记再读取快照：此刻之后的任何变更都会再排一轮，而不是被本轮吞掉。
            pending.set(false)
            sync()
        }
    }

    /** 把本地配置整体镜像到远程配置；框架未连接时静默跳过。 */
    private fun sync() {
        val bound = synchronized(lock) { service }
        if (bound == null) {
            Log.d(TAG, "sync skipped: framework not bound")
            return
        }
        try {
            val remote = bound.getRemotePreferences(Constants.PREF_FILE_NAME).edit()
            remote.clear()
            var keys = 0
            for ((key, value) in localPreferences().all) {
                when (value) {
                    is String -> remote.putString(key, value)
                    is Boolean -> remote.putBoolean(key, value)
                    is Long -> remote.putLong(key, value)
                    is Int -> remote.putInt(key, value)
                    is Float -> remote.putFloat(key, value)
                    else -> Log.w(TAG, "skip unsupported preference $key")
                }
                keys++
            }
            val committed = remote.commit()
            Log.d(TAG, "synced $keys keys committed=$committed")
        } catch (e: Throwable) {
            Log.w(TAG, "sync failed", e)
        }
    }

    private fun localPreferences(): SharedPreferences =
        App.current.getSharedPreferences(Constants.PREF_FILE_NAME, Context.MODE_PRIVATE)
}
