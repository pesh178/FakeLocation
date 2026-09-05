package com.xposed.hook.storage

import android.content.SharedPreferences
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.xposed.hook.core.HookUtils
import com.xposed.hook.core.ProcessContext
import org.xmlpull.v1.XmlPullParserException
import java.io.FileNotFoundException
import kotlin.concurrent.thread

/**
 * Created by lin on 2021/8/21.
 */
class XSharedPreferences(
    packageName: String,
    prefFileName: String
) : SharedPreferences {

    private val TAG = "XSharedPreferences"
    private val lock = Object()
    private var mUri: Uri =
        Uri.parse("content://$packageName.fileprovider/shared_prefs/$prefFileName.xml")
    private var mMap: Map<String, Any>? = null
    private var mLoaded = false

    init {
        startLoadFromDisk()
    }

    override fun getAll(): MutableMap<String, *> {
        synchronized(lock) {
            awaitLoadedLocked()
            return HashMap(mMap)
        }
    }

    override fun getString(key: String?, defValue: String?): String? {
        synchronized(lock) {
            awaitLoadedLocked()
            val v = mMap?.get(key) as? String
            return v ?: defValue
        }
    }

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
        synchronized(lock) {
            awaitLoadedLocked()
            val v = mMap?.get(key) as? MutableSet<String>
            return v?.toMutableSet() ?: defValues
        }
    }

    override fun getInt(key: String?, defValue: Int): Int {
        synchronized(lock) {
            awaitLoadedLocked()
            val v = mMap?.get(key) as? Int
            return v ?: defValue
        }
    }

    override fun getLong(key: String?, defValue: Long): Long {
        synchronized(lock) {
            awaitLoadedLocked()
            val v = mMap?.get(key) as? Long
            return v ?: defValue
        }
    }

    override fun getFloat(key: String?, defValue: Float): Float {
        synchronized(lock) {
            awaitLoadedLocked()
            val v = mMap?.get(key) as? Float
            return v ?: defValue
        }
    }

    override fun getBoolean(key: String?, defValue: Boolean): Boolean {
        synchronized(lock) {
            awaitLoadedLocked()
            val v = mMap?.get(key) as? Boolean
            return v ?: defValue
        }
    }

    override fun contains(key: String?): Boolean {
        synchronized(lock) {
            awaitLoadedLocked()
            return mMap?.containsKey(key) ?: false
        }
    }

    override fun edit(): SharedPreferences.Editor {
        TODO("Not yet implemented")
    }

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        TODO("Not yet implemented")
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        TODO("Not yet implemented")
    }

    private fun startLoadFromDisk() {
        synchronized(lock) { mLoaded = false }
        thread {
            synchronized(lock) { loadFromDiskLocked() }
        }
    }

    private fun loadFromDiskLocked() {
        if (mLoaded) {
            return
        }
        var map: Map<String, Any>? = null
        try {
            val context = ProcessContext.create()
            val fileDescriptor = context.contentResolver.openFileDescriptor(mUri, "r")
            if (fileDescriptor != null) {
                ParcelFileDescriptor.AutoCloseInputStream(fileDescriptor).use { stream ->
                    map = HookUtils.callStaticMethod(
                        Class.forName("com.android.internal.util.XmlUtils"), "readMapXml", stream
                    ) as Map<String, Any>
                }
            }
        } catch (e: XmlPullParserException) {
            Log.w(TAG, "getSharedPreferences", e)
        } catch (ignored: FileNotFoundException) {
            // SharedPreferencesImpl has a canRead() check, so it doesn't log anything in case the file doesn't exist
        } catch (e: Throwable) {
            Log.w(TAG, "getSharedPreferences", e)
        }
        mLoaded = true
        mMap = map ?: HashMap()
        lock.notifyAll()
    }

    fun reload() {
        startLoadFromDisk()
    }

    private fun awaitLoadedLocked() {
        while (!mLoaded) {
            try {
                lock.wait()
            } catch (unused: InterruptedException) {
            }
        }
    }
}