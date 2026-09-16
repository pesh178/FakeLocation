package com.xposed.hook.utils

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.Bitmap
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.xposed.hook.App
import com.xposed.hook.config.Constants
import com.xposed.hook.entity.AppInfo
import com.xposed.hook.extension.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Created by lin on 2021/8/7.
 *
 * The package list, ordered with enabled hooks first, published through [apps].
 *
 * A cold start cannot resolve the list in one frame: reading every installed package and its
 * label costs the PackageManager hundreds of milliseconds. The list is therefore persisted as a
 * snapshot, restored at startup and re-scanned in the background, so the first frame already has
 * rows. Icons are never resolved during a scan — they are decoded by [loadIcon] for the rows that
 * are actually on screen, which was the dominant cost of the old eager scan.
 *
 * All three caches (labels/flags, icons, snapshot) are keyed on `PackageInfo.lastUpdateTime`, so a
 * cached entry can never outlive the APK it came from.
 */
object AppHelper {

    private const val TAG = "AppHelper"

    /** Enabled apps first, then by title, then by package name. */
    val enabledFirstOrder: Comparator<AppInfo> =
        compareByDescending<AppInfo> { it.enabled }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
            .thenBy { it.packageName }

    /** Decoded icons are shared by the list and the detail page, so the cache is bounded by bytes. */
    private const val ICON_CACHE_BYTES = 4 * 1024 * 1024

    private const val SNAPSHOT_FILE = "app_list.bin"
    private const val SNAPSHOT_MAGIC = 0x464C4131
    private const val SNAPSHOT_LIMIT = 10_000

    private val lock = Any()
    private val scanning = AtomicBoolean(false)

    private val state = MutableStateFlow<List<AppInfo>>(emptyList())

    /** Current list; emits the restored snapshot first and the scan result once it is ready. */
    val apps: StateFlow<List<AppInfo>> = state.asStateFlow()

    /** Label and flags per package; survives scans until the package is updated. */
    private var metadata = HashMap<String, Metadata>()

    private var current: List<AppInfo>? = null
    private var dirty = true

    private val icons = object : LruCache<String, Icon>(ICON_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Icon): Int = value.bitmap.byteCount
    }

    private class Metadata(val lastUpdateTime: Long, val title: String, val isSystem: Boolean)

    private class Icon(val lastUpdateTime: Long, val sizePx: Int, val bitmap: Bitmap)

    /**
     * Marks the installed package set as stale. Called when a package is installed, removed or
     * replaced; the next [refresh] re-reads it from the PackageManager.
     */
    fun invalidate() {
        synchronized(lock) { dirty = true }
    }

    /**
     * Publishes the package list: restores the snapshot on a cold start, then rescans when the
     * package set is stale. Cheap enough to call from every `onResume`, because an up-to-date list
     * is only re-checked against the preferences.
     */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        val stale = synchronized(lock) {
            if (current == null) restore()
            dirty
        }
        if (!stale) {
            synchronized(lock) { state.value = refreshFlags() }
            return@withContext
        }
        // A scan is already running and will publish its own result.
        if (!scanning.compareAndSet(false, true)) return@withContext
        try {
            val list = scan()
            synchronized(lock) { state.value = list }
            writeSnapshot(list)
        } finally {
            scanning.set(false)
        }
    }

    /**
     * Persists the hook flag of [packageName] and republishes the list, so ordering stays owned by
     * a single place. The remote configuration mirror is updated by
     * [com.xposed.hook.config.RemoteConfig]'s preference listener.
     */
    suspend fun setEnabled(packageName: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        if (synchronized(lock) { current } == null) refresh()
        synchronized(lock) {
            preferences().edit().putBoolean(packageName, enabled).apply()
            val list = current ?: return@synchronized
            for (app in list) {
                if (app.packageName == packageName) app.enabled = enabled
            }
            state.value = list.sortedWith(enabledFirstOrder).also { current = it }
        }
    }

    /**
     * Icon of [packageName] scaled to [sizePx], decoded off the caller's thread. Returns the
     * previously decoded bitmap while it still matches [lastUpdateTime] and [sizePx].
     */
    suspend fun loadIcon(packageName: String, lastUpdateTime: Long, sizePx: Int): ImageBitmap? {
        val cached = icons.get(packageName)
        if (cached != null && cached.lastUpdateTime == lastUpdateTime && cached.sizePx == sizePx) {
            return cached.bitmap.asImageBitmap()
        }
        val bitmap = withContext(Dispatchers.IO) {
            runCatching {
                App.current.packageManager.getApplicationIcon(packageName).toBitmap(sizePx, sizePx)
            }.onFailure { icons.remove(packageName) }.getOrNull()
        } ?: return null
        icons.put(packageName, Icon(lastUpdateTime, sizePx, bitmap))
        return bitmap.asImageBitmap()
    }

    /** Re-reads the hook flags of the cached list; caller holds [lock]. */
    private fun refreshFlags(): List<AppInfo> {
        val list = current ?: return emptyList()
        val stored = preferences().all
        var changed = false
        for (app in list) {
            val enabled = stored[app.packageName] as? Boolean ?: false
            if (app.enabled != enabled) {
                app.enabled = enabled
                changed = true
            }
        }
        if (!changed) return list
        val sorted = list.sortedWith(enabledFirstOrder)
        current = sorted
        return sorted
    }

    /** Restores the persisted list so the first frame has rows. Caller holds [lock]. */
    private fun restore() {
        val restored = readSnapshot() ?: return
        val stored = preferences().all
        val list = ArrayList<AppInfo>(restored.size)
        val meta = HashMap<String, Metadata>(restored.size)
        for (app in restored) {
            meta[app.packageName] = Metadata(app.lastUpdateTime, app.title, app.isSystem)
            app.enabled = stored[app.packageName] as? Boolean ?: false
            list.add(app)
        }
        metadata = meta
        val sorted = list.sortedWith(enabledFirstOrder)
        current = sorted
        state.value = sorted
        Log.d(TAG, "restored ${sorted.size} packages from snapshot")
    }

    private fun scan(): List<AppInfo> {
        val pm = App.current.packageManager
        val stored = preferences().all
        val installedPackages = pm.getInstalledPackages(0)
        val previous = metadata
        val meta = HashMap<String, Metadata>(installedPackages.size)
        val result = ArrayList<AppInfo>(installedPackages.size)
        for (installedPackage in installedPackages) {
            val info = installedPackage.applicationInfo ?: continue
            val packageName = installedPackage.packageName
            val cached = previous[packageName]
            val entry = if (cached != null && cached.lastUpdateTime == installedPackage.lastUpdateTime) {
                cached
            } else {
                resolve(installedPackage, info)
            }
            meta[packageName] = entry
            val app = AppInfo()
            app.packageName = packageName
            app.title = entry.title
            app.lastUpdateTime = entry.lastUpdateTime
            app.isSystem = entry.isSystem
            app.enabled = stored[packageName] as? Boolean ?: false
            result.add(app)
        }
        for (packageName in icons.snapshot().keys) {
            if (packageName !in meta) icons.remove(packageName)
        }
        val sorted = result.sortedWith(enabledFirstOrder)
        synchronized(lock) {
            metadata = meta
            current = sorted
            dirty = false
        }
        return sorted
    }

    private fun resolve(installedPackage: PackageInfo, info: ApplicationInfo): Metadata {
        val pm = App.current.packageManager
        return Metadata(
            installedPackage.lastUpdateTime,
            info.loadLabel(pm).toString(),
            info.flags and ApplicationInfo.FLAG_SYSTEM != 0
        )
    }

    private fun preferences(): SharedPreferences =
        App.current.getSharedPreferences(Constants.PREF_FILE_NAME, Context.MODE_PRIVATE)

    /** Writes the list through a temporary file so a crash cannot leave a half-written snapshot. */
    private fun writeSnapshot(list: List<AppInfo>) {
        val file = File(App.current.filesDir, SNAPSHOT_FILE)
        val temp = File(file.parentFile, "$SNAPSHOT_FILE.tmp")
        try {
            DataOutputStream(temp.outputStream().buffered()).use { out ->
                out.writeInt(SNAPSHOT_MAGIC)
                out.writeInt(list.size)
                for (app in list) {
                    out.writeUTF(app.packageName)
                    out.writeLong(app.lastUpdateTime)
                    out.writeUTF(app.title)
                    out.writeBoolean(app.isSystem)
                }
            }
            if (!temp.renameTo(file)) {
                file.delete()
                if (!temp.renameTo(file)) temp.delete()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "snapshot write failed", e)
            temp.delete()
        }
    }

    private fun readSnapshot(): List<AppInfo>? {
        val file = File(App.current.filesDir, SNAPSHOT_FILE)
        if (!file.isFile) return null
        return try {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readInt() != SNAPSHOT_MAGIC) return null
                val size = input.readInt()
                if (size <= 0 || size > SNAPSHOT_LIMIT) return null
                val list = ArrayList<AppInfo>(size)
                repeat(size) {
                    val app = AppInfo()
                    app.packageName = input.readUTF()
                    app.lastUpdateTime = input.readLong()
                    app.title = input.readUTF()
                    app.isSystem = input.readBoolean()
                    list.add(app)
                }
                list
            }
        } catch (e: Throwable) {
            Log.w(TAG, "snapshot read failed", e)
            null
        }
    }
}
