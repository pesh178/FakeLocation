package com.xposed.hook.location

import android.content.Context
import android.location.Location
import android.os.Bundle
import java.lang.reflect.Field
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/** Process-local configurations and object bindings for hooked packages. */
object LocationConfig {
    private const val PACKAGE_EXTRA = "com.xposed.hook.PACKAGE"

    data class Values(
        val latitude: Double,
        val longitude: Double,
        val lac: Long,
        val cid: Long
    )

    private val valuesByPackage = ConcurrentHashMap<String, Values>()
    private val objectPackages = Collections.synchronizedMap(WeakHashMap<Any, String>())
    private val classLoaderPackages = Collections.synchronizedMap(WeakHashMap<ClassLoader, String>())

    /**
     * Reflective package fields per class. `Class.getDeclaredFields` copies the whole array on
     * every call, so the hierarchy walk is resolved once and reused by the hot location getters.
     */
    private val packageFields = ConcurrentHashMap<Class<*>, Array<Field>>()

    @Volatile
    private var defaultPackage: String? = null

    @JvmStatic
    @Synchronized
    fun configure(
        packageName: String,
        latitude: Double,
        longitude: Double,
        lac: Long,
        cid: Long
    ): Values {
        val existing = valuesByPackage[packageName]
        if (existing != null) return existing
        val values = Values(latitude, longitude, lac, cid)
        valuesByPackage[packageName] = values
        if (defaultPackage == null) defaultPackage = packageName
        return values
    }

    @JvmStatic
    fun bindClassLoader(classLoader: ClassLoader?, packageName: String?) {
        if (classLoader != null && packageName != null) classLoaderPackages[classLoader] = packageName
    }

    @JvmStatic
    fun bindObject(target: Any?, packageName: String?) {
        if (target != null && packageName != null) objectPackages[target] = packageName
    }

    @JvmStatic
    fun bindListener(listener: Any?, packageName: String?) = bindObject(listener, packageName)

    @JvmStatic
    fun bindLocation(location: Location?, packageName: String?) {
        if (location == null || packageName == null) return
        val extras = location.extras ?: Bundle()
        extras.putString(PACKAGE_EXTRA, packageName)
        location.extras = extras
    }

    @JvmStatic
    fun bindCellLocation(cellLocation: Any?, packageName: String?) = bindObject(cellLocation, packageName)

    @JvmStatic
    fun bindCellInfo(cellInfo: android.telephony.CellInfo?, packageName: String?) {
        if (cellInfo == null || packageName == null) return
        bindObject(cellInfo, packageName)
        bindObject(cellInfo.cellIdentity, packageName)
    }

    @JvmStatic
    fun packageForObject(target: Any?): String? {
        if (target == null) return null
        if (target is Location) target.extras?.getString(PACKAGE_EXTRA)?.let { return it }
        objectPackages[target]?.let { return it }
        val resolved = resolvePackage(target) ?: return null
        objectPackages[target] = resolved
        return resolved
    }

    private fun resolvePackage(target: Any): String? {
        target.javaClass.classLoader?.let { loader ->
            classLoaderPackages[loader]?.let { return it }
        }
        if (target is Context) return target.packageName
        for (field in packageFieldsOf(target.javaClass)) {
            try {
                when (val value = field.get(target)) {
                    is Context -> return value.packageName
                    is String -> return value
                }
            } catch (_: Throwable) {
                // Framework internals vary by API level.
            }
        }
        return null
    }

    private fun packageFieldsOf(type: Class<*>): Array<Field> {
        packageFields[type]?.let { return it }
        val fields = ArrayList<Field>(2)
        var current: Class<*>? = type
        while (current != null && current != Any::class.java) {
            for (field in current.declaredFields) {
                if (field.name != "mContext" && field.name != "mPackageName") continue
                try {
                    field.isAccessible = true
                    fields.add(field)
                } catch (_: Throwable) {
                    // Hidden API restrictions; fall through to the next candidate.
                }
            }
            current = current.superclass
        }
        val resolved = fields.toTypedArray()
        packageFields[type] = resolved
        return resolved
    }

    @JvmStatic
    fun packageNameForObject(target: Any?): String? = packageForObject(target)

    @JvmStatic
    fun packageForListener(listener: Any?): String? = packageForObject(listener)

    @JvmStatic
    fun packageForLocation(location: Location?): String? = packageForObject(location)

    @JvmStatic
    fun getValues(packageName: String?): Values? = if (packageName == null) {
        defaultPackage?.let { valuesByPackage[it] }
    } else {
        valuesByPackage[packageName]
    }

    @JvmStatic
    fun getLatitude(packageName: String?): Double = getValues(packageName)?.latitude?.plus(randomOffset()) ?: 0.0

    @JvmStatic
    fun getLongitude(packageName: String?): Double = getValues(packageName)?.longitude?.plus(randomOffset()) ?: 0.0

    @JvmStatic
    fun getLac(target: Any?, fallback: Int): Int = getValues(packageForObject(target))?.lac?.toInt() ?: fallback

    @JvmStatic
    fun getCid(target: Any?, fallback: Int): Int {
        val cid = getValues(packageForObject(target))?.cid ?: return fallback
        return if (cid in 0..Int.MAX_VALUE.toLong()) cid.toInt() else -1
    }

    @JvmStatic
    fun getNci(target: Any?, fallback: Long): Long = getValues(packageForObject(target))?.cid ?: fallback

    private fun randomOffset(): Double = (Random.Default.nextInt(1000) - 500) * OFFSET_STEP

    private const val OFFSET_STEP = 1e-8
}
