package com.xposed.hook.location

import android.Manifest
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.floor

/**
 * 在真机/模拟器运行时（ART + 真实 LocationManager）上验证定位下发链路与派发循环生命周期。
 * Hook 安装本身需要 Xposed，但 LocationHandler 的派发、NMEA 生成与配置解析可以完整跑通。
 */
@RunWith(AndroidJUnit4::class)
class LocationRuntimeTest {

    private companion object {
        const val LATITUDE = 34.7526
        const val LONGITUDE = 113.662
        const val LAC = 4101L
        const val CID = 20561L
        /** 由 LocationConfig.bindLocation 写入 Location extras 的归属标记。 */
        const val PACKAGE_EXTRA = "com.xposed.hook.PACKAGE"
        /** 坐标带有 ±5e-6 度的随机扰动，用于规避固定值检测。 */
        const val COORDINATE_TOLERANCE = 1e-5
    }

    @get:Rule
    val locationPermission: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.ACCESS_FINE_LOCATION)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private lateinit var locationManager: LocationManager

    private val locations = LinkedBlockingQueue<Location>()
    private val sentences = LinkedBlockingQueue<String>()
    private var locationListener: LocationListener? = null
    private var nmeaListener: OnNmeaMessageListener? = null

    private class ContextHolder(private val mContext: Context)

    private class NameHolder(private val mPackageName: String)

    @Before
    fun setUp() {
        locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        LocationConfig.configure(context.packageName, LATITUDE, LONGITUDE, LAC, CID)
        locations.clear()
        sentences.clear()
    }

    @After
    fun tearDown() {
        removeListeners()
        awaitCondition(20_000) { !LocationHandler.getInstance().isRunning() }
    }

    @Test
    fun locationIsPushedToRegisteredFrameworkListener() {
        requireFrameworkPrivateAccess()
        val handler = LocationHandler.getInstance()
        registerLocationListener()
        handler.start()
        assertTrue("注册监听器后派发循环必须保持运行", handler.isRunning())

        val location = awaitMockedLocation(8_000)
        assertNotNull("注册监听器后必须收到模拟位置", location)
        assertEquals(LATITUDE, location!!.latitude, COORDINATE_TOLERANCE)
        assertEquals(LONGITUDE, location.longitude, COORDINATE_TOLERANCE)
        assertEquals(context.packageName, location.extras?.getString(PACKAGE_EXTRA))
        assertEquals(LocationManager.GPS_PROVIDER, location.provider)
    }

    @Test
    fun nmeaSentencesArePushedToRegisteredListener() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        requireFrameworkPrivateAccess()
        registerNmeaListener()
        LocationHandler.getInstance().start()

        val gga = awaitSentence("\$GPGGA", 8_000)
        assertNotNull("注册 NMEA 监听器后必须收到 GGA 语句", gga)
        val fields = gga!!.substringAfter("\$GPGGA,").substringBefore('*').split(',')
        assertTrue("GGA 语句字段数必须覆盖 NMEA 规范字段：" + fields.size, fields.size >= 14)
        assertTrue("时间必须是 UTC HHmmss.SS：" + fields[0], Regex("\\d{6}\\.\\d{2}").matches(fields[0]))
        assertEquals(LATITUDE, decimalDegrees(fields[1].toDouble()), COORDINATE_TOLERANCE * 3)
        assertEquals("N", fields[2])
        assertEquals(LONGITUDE, decimalDegrees(fields[3].toDouble()), COORDINATE_TOLERANCE * 3)
        assertEquals("E", fields[4])
        assertEquals(checksumOf(gga), gga.substringAfter('*'))

        val rmc = awaitSentence("\$GPRMC", 8_000)
        assertNotNull("注册 NMEA 监听器后必须收到 RMC 语句", rmc)
        assertEquals(checksumOf(rmc!!), rmc.substringAfter('*'))
    }

    @Test
    fun dispatchLoopStopsWhenNothingIsRegistered() {
        val handler = LocationHandler.getInstance()
        handler.start()
        assertTrue("启动后派发循环必须处于运行状态", handler.isRunning())

        // 没有监听器时循环必须自行停止，否则被 hook 的进程会被每 10 秒唤醒一次。
        assertTrue(
            "无监听器时派发循环必须停止",
            awaitCondition(8_000) { !handler.isRunning() }
        )
    }

    @Test
    fun dispatchLoopResumesAfterIdleStop() {
        requireFrameworkPrivateAccess()
        val handler = LocationHandler.getInstance()
        handler.start()
        assertTrue("无监听器时派发循环必须停止", awaitCondition(8_000) { !handler.isRunning() })

        // 空闲停止后重新注册监听器必须恢复下发，停止不能是永久的。
        registerLocationListener()
        handler.start()
        assertNotNull("空闲停止后重新注册必须恢复下发", awaitMockedLocation(8_000))
        assertTrue(handler.isRunning())
    }

    @Test
    fun packageResolutionUsesContextAndReflectiveFields() {
        assertEquals(context.packageName, LocationConfig.packageForObject(context))
        assertEquals(
            "com.example.fixture",
            LocationConfig.packageForObject(NameHolder("com.example.fixture"))
        )
        val holder = ContextHolder(context)
        assertEquals(context.packageName, LocationConfig.packageForObject(holder))
        // 同一对象的再次解析走缓存路径，必须与首次一致。
        assertEquals(context.packageName, LocationConfig.packageForObject(holder))
        assertNull(LocationConfig.packageForObject(Any()))
        assertNull(LocationConfig.packageForObject(null))
    }

    @Test
    fun packageResolutionResolvesFrameworkObjects() {
        requireFrameworkPrivateAccess()
        val manager = context.getSystemService(Context.LOCATION_SERVICE)
        assertEquals(
            "真实 LocationManager 必须解析到当前包名",
            context.packageName,
            LocationConfig.packageNameForObject(manager)
        )
        // 缓存命中路径必须给出同一结果。
        assertEquals(context.packageName, LocationConfig.packageForObject(manager))
    }

    /**
     * 读取真实框架私有成员需要 Xposed/LSPosed 的 hidden API 豁免；
     * 纯 AVD 环境可用 `adb shell settings put global hidden_api_policy 1` 放开。
     *
     * 能力判定与产品代码一致：`LocationHandler` 在 `sLocationListeners` 与 `mListeners` 之间
     * 按版本取值，任一可用即可下发，因此两者都不可用时才跳过（否则会在产品可工作的 API 上误跳过）。
     */
    private fun requireFrameworkPrivateAccess() {
        val allowed = com.xposed.hook.location.LocationManager.sLocationListeners != null ||
            com.xposed.hook.location.LocationManager.mListeners != null
        assumeTrue(
            "需要访问框架私有成员：请使用 Xposed/LSPosed，或执行 adb shell settings put global hidden_api_policy 1",
            allowed
        )
    }

    private fun registerLocationListener() {
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                locations.offer(location)
            }
        }
        locationListener = listener
        // LocationHook 在 requestLocationUpdates 中做同样的绑定。
        LocationConfig.bindListener(listener, context.packageName)
        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER, 1_000L, 0f, listener, Looper.getMainLooper()
        )
    }

    private fun registerNmeaListener() {
        val listener = OnNmeaMessageListener { message, _ -> sentences.offer(message) }
        nmeaListener = listener
        locationManager.addNmeaListener(listener, Handler(Looper.getMainLooper()))
    }

    private fun removeListeners() {
        locationListener?.let { locationManager.removeUpdates(it) }
        locationListener = null
        nmeaListener?.let { locationManager.removeNmeaListener(it) }
        nmeaListener = null
        locations.clear()
        sentences.clear()
    }

    private fun awaitMockedLocation(timeoutMs: Long): Location? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) return null
            val location = locations.poll(remaining, TimeUnit.MILLISECONDS) ?: return null
            // 设备真实定位可能同时到达，只接受带归属标记的模拟位置。
            if (location.extras?.getString(PACKAGE_EXTRA) == context.packageName) return location
        }
    }

    private fun awaitSentence(prefix: String, timeoutMs: Long): String? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) return null
            val sentence = sentences.poll(remaining, TimeUnit.MILLISECONDS) ?: return null
            if (sentence.startsWith(prefix)) return sentence
        }
    }

    private fun awaitCondition(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return condition()
    }

    /** DDMM.MMMM / DDDMM.MMMM -> 十进制度。 */
    private fun decimalDegrees(nmea: Double): Double {
        val degrees = floor(nmea / 100.0)
        return degrees + (nmea - degrees * 100.0) / 60.0
    }

    private fun checksumOf(sentence: String): String {
        var sum = 0
        for (c in sentence.substringAfter('$').substringBefore('*')) {
            sum = sum xor c.code
        }
        return String.format("%02X", sum)
    }
}
