package com.xposed.hook

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.xposed.hook.config.RemoteConfig
import com.xposed.hook.utils.AppHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Created by lin on 2021/8/7.
 */
class App : Application() {

    private val packageChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            AppHelper.invalidate()
            // Refresh immediately so a list that is already on screen reflects the change instead
            // of waiting for the next resume.
            background.launch { AppHelper.refresh() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        current = this
        ContextCompat.registerReceiver(
            this,
            packageChanges,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        RemoteConfig.start()
        // Warms the package list while the main activity inflates, so its first frame already has
        // rows instead of an empty screen waiting for the PackageManager.
        background.launch { AppHelper.refresh() }
    }

    companion object {
        lateinit var current: Application

        /** Application-lifetime scope for work that must outlive any single activity. */
        val background: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
