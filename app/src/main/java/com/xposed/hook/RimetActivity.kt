package com.xposed.hook

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.Settings
import android.telephony.*
import android.telephony.gsm.GsmCellLocation
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.MutableLiveData
import com.xposed.hook.config.Constants
import com.xposed.hook.config.PkgConfig
import com.xposed.hook.entity.AppInfo
import com.xposed.hook.extension.dpInPx
import com.xposed.hook.theme.AppTheme
import com.xposed.hook.utils.AppHelper
import com.xposed.hook.utils.CellLocationHelper

/** Continuous refresh interval of the current-position readout. */
private const val REFRESH_INTERVAL_MS = 5000L

internal object LocationProviderSelector {
    fun orderedProviders(providers: List<String>): List<String> {
        val preferredProviders = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        return (preferredProviders + providers).distinct().filter { it in providers }
    }

    /**
     * Providers worth holding a continuous request on: the two standard providers plus passive,
     * which only receives locations other apps already requested. Vendor providers are dropped so
     * the settings page does not keep a second positioning stack (for example a fused provider)
     * awake for as long as it is open — unless no standard provider is enabled at all, in which
     * case the best available one is kept so the readout still works.
     */
    fun activeProviders(providers: List<String>): List<String> {
        val ordered = orderedProviders(providers)
        val standard = ordered.filter {
            it == LocationManager.GPS_PROVIDER ||
                it == LocationManager.NETWORK_PROVIDER ||
                it == LocationManager.PASSIVE_PROVIDER
        }
        if (standard.any { it != LocationManager.PASSIVE_PROVIDER }) return standard
        val fallback = ordered.firstOrNull()?.takeIf { it !in standard }
        return if (fallback == null) standard else listOf(fallback) + standard
    }
}

internal object CurrentLocationSelector {
    private const val STALE_LOCATION_NANOS = 30_000_000_000L

    fun shouldReplace(current: Location?, candidate: Location): Boolean {
        if (current == null) return true
        if (candidate.elapsedRealtimeNanos < current.elapsedRealtimeNanos) return false
        val candidateIsGps = candidate.provider == LocationManager.GPS_PROVIDER
        val currentIsGps = current.provider == LocationManager.GPS_PROVIDER
        val age = candidate.elapsedRealtimeNanos - current.elapsedRealtimeNanos
        if (currentIsGps && !candidateIsGps && age < STALE_LOCATION_NANOS) return false
        if (!currentIsGps && candidateIsGps) return true
        if (candidate.hasAccuracy() && current.hasAccuracy() &&
            candidate.accuracy > current.accuracy + 10f && age < STALE_LOCATION_NANOS
        ) return false
        return true
    }
}


class RimetActivity : AppCompatActivity() {

    private lateinit var sp: SharedPreferences
    private lateinit var appInfo: AppInfo
    private var isDingTalk = false

    private lateinit var tm: TelephonyManager
    private lateinit var lm: LocationManager
    private var locationStarted = false
    private var cellListenerStarted = false
    private var locationGeneration = 0
    private var currentLocationRequest: CancellationSignal? = null

    private val _currentLatitude = MutableLiveData("")
    private val _currentLongitude = MutableLiveData("")
    private val _currentLac = MutableLiveData("")
    private val _currentCid = MutableLiveData("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureImmersiveStatusBar()
        val info = intent.getSerializableExtra("appInfo") as? AppInfo
        if (info == null) {
            // Nothing to configure; an empty window would only be a dead end.
            finish()
            return
        }
        appInfo = info
        title = appInfo.title
        isDingTalk = PkgConfig.pkg_dingtalk == appInfo.packageName
        tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        lm = getSystemService(LOCATION_SERVICE) as LocationManager
        sp = getSharedPreferences(Constants.PREF_FILE_NAME, MODE_PRIVATE)
        setContent { Container() }
        requestPermissions()
    }

    override fun onStart() {
        super.onStart()
        if (::lm.isInitialized && hasLocationPermission()) {
            startLocation()
        }
    }

    override fun onStop() {
        stopLocation()
        super.onStop()
    }

    private fun configureImmersiveStatusBar() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        val isDarkTheme = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = if (isDarkTheme) {
            android.graphics.Color.rgb(16, 24, 23)
        } else {
            android.graphics.Color.rgb(243, 246, 245)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            if (isDarkTheme) {
                0
            } else {
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                    } else {
                        0
                    }
            }
    }

    @Composable
    fun Container() {
        val prefix = appInfo.packageName + "_"
        val defaultLatitude = if (isDingTalk) "" else Constants.DEFAULT_LATITUDE
        val defaultLongitude = if (isDingTalk) "" else Constants.DEFAULT_LONGITUDE
        var latitude by remember {
            mutableStateOf(sp.getString(prefix + "latitude", null) ?: defaultLatitude)
        }
        var longitude by remember {
            mutableStateOf(sp.getString(prefix + "longitude", null) ?: defaultLongitude)
        }
        var lac by remember {
            mutableStateOf(
                CellLocationHelper.getLac(sp, prefix).let {
                    if (it == Constants.DEFAULT_LAC) "" else it.toString()
                }
            )
        }
        var cid by remember {
            mutableStateOf(
                CellLocationHelper.getCid(sp, prefix).let {
                    if (it == Constants.DEFAULT_CID) "" else it.toString()
                }
            )
        }
        var isChecked by remember {
            mutableStateOf(sp.getBoolean(appInfo.packageName, false))
        }
        val currentLatitude by _currentLatitude.observeAsState("")
        val currentLongitude by _currentLongitude.observeAsState("")
        val currentLac by _currentLac.observeAsState("")
        val currentCid by _currentCid.observeAsState("")

        AppTheme {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colors.background)
                    .statusBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 16.dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AppHeader()
                SectionTitle(stringResource(R.string.gps_location))
                OutlinedTextField(
                    value = latitude,
                    onValueChange = { latitude = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("latitude") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    shape = RoundedCornerShape(10.dp)
                )
                OutlinedTextField(
                    value = longitude,
                    onValueChange = { longitude = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("longitude") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    shape = RoundedCornerShape(10.dp)
                )
                ValueComparisonCard(
                    title = stringResource(R.string.current_gps_info),
                    rows = listOf(
                        stringResource(R.string.latitude_label) to (currentLatitude to latitude),
                        stringResource(R.string.longitude_label) to (currentLongitude to longitude)
                    )
                )
                if (currentLatitude.isNotEmpty() && currentLongitude.isNotEmpty()) {
                    TextButton(
                        onClick = {
                            latitude = currentLatitude
                            longitude = currentLongitude
                        },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text(stringResource(R.string.auto_fill))
                    }
                }

                SectionTitle(stringResource(R.string.cell_location))
                OutlinedTextField(
                    value = lac,
                    onValueChange = { lac = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Area Code") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = RoundedCornerShape(10.dp)
                )
                OutlinedTextField(
                    value = cid,
                    onValueChange = { cid = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Cell Identity") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = RoundedCornerShape(10.dp)
                )
                ValueComparisonCard(
                    title = stringResource(R.string.current_cell_info),
                    rows = listOf(
                        stringResource(R.string.area_code_label) to (currentLac to lac),
                        stringResource(R.string.cell_identity_label) to (currentCid to cid)
                    )
                )
                if (currentLac.isNotEmpty() && currentCid.isNotEmpty()) {
                    TextButton(
                        onClick = {
                            lac = currentLac
                            cid = currentCid
                        },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text(stringResource(R.string.auto_fill))
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colors.surface,
                    elevation = 1.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.open_location_hook),
                                style = MaterialTheme.typography.body1.copy(fontWeight = FontWeight.Medium)
                            )
                            Text(
                                text = stringResource(R.string.open_location_hook_hint),
                                style = MaterialTheme.typography.caption.copy(color = MaterialTheme.colors.onSurface.copy(alpha = 0.65f))
                            )
                        }
                        Switch(
                            checked = isChecked,
                            onCheckedChange = { isChecked = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colors.primary,
                                checkedTrackColor = MaterialTheme.colors.primary.copy(alpha = 0.2f)
                            )
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = {
                            // The module silently falls back to its defaults for unusable values, so
                            // saving one would report success while hooking a different position.
                            val invalid = isCoordinateInvalid(latitude, -90.0, 90.0) ||
                                isCoordinateInvalid(longitude, -180.0, 180.0) ||
                                isCellValueInvalid(lac) ||
                                isCellValueInvalid(cid)
                            if (invalid) {
                                Toast.makeText(
                                    applicationContext,
                                    R.string.invalid_input,
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                sp.edit()
                                    .putString(prefix + "latitude", latitude)
                                    .putString(prefix + "longitude", longitude)
                                    .putLong(prefix + "lac", parseLong(lac))
                                    .putLong(prefix + "cid", parseLong(cid))
                                    .putLong(prefix + "time", System.currentTimeMillis())
                                    .putBoolean(appInfo.packageName, isChecked)
                                    .apply()
                                Toast.makeText(
                                    applicationContext,
                                    R.string.save_success,
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = MaterialTheme.colors.primary,
                            contentColor = MaterialTheme.colors.onPrimary
                        ),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(vertical = 12.dp)
                    ) {
                        Text(text = stringResource(R.string.save))
                    }
                    OutlinedButton(
                        onClick = {
                            val intent = Intent().apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                                data = Uri.fromParts("package", appInfo.packageName, null)
                            }
                            startActivity(intent)
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        border = ButtonDefaults.outlinedBorder.copy(
                            brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colors.primary)
                        ),
                        contentPadding = PaddingValues(vertical = 12.dp)
                    ) {
                        Text(text = stringResource(R.string.reboot_app), color = MaterialTheme.colors.primary)
                    }
                }
            }
        }
    }

    @Composable
    private fun AppHeader() {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { finish() }) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = stringResource(R.string.back)
                )
            }
            // The list already decoded this icon; reuse its cache instead of touching the
            // PackageManager on the main thread while the header composes.
            val icon by produceState<ImageBitmap?>(null, appInfo.packageName, appInfo.lastUpdateTime) {
                value = AppHelper.loadIcon(appInfo.packageName, appInfo.lastUpdateTime, 48.dpInPx)
            }
            val bitmap = icon
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = appInfo.title,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp))
                )
            }
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(
                    text = appInfo.title,
                    style = MaterialTheme.typography.h6.copy(
                        color = MaterialTheme.colors.onSurface,
                        fontWeight = FontWeight.Bold
                    )
                )
                Text(
                    text = appInfo.packageName,
                    style = MaterialTheme.typography.caption.copy(color = MaterialTheme.colors.onSurface.copy(alpha = 0.65f)),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }

    @Composable
    private fun SectionTitle(title: String) {
        Text(
            text = title,
            style = MaterialTheme.typography.subtitle1.copy(
                color = MaterialTheme.colors.primary,
                fontWeight = FontWeight.Bold
            ),
            modifier = Modifier.padding(top = 4.dp)
        )
    }

    @Composable
    private fun ValueComparisonCard(
        title: String,
        rows: List<Pair<String, Pair<String, String>>>
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colors.surface,
            elevation = 1.dp
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.body1.copy(fontWeight = FontWeight.Medium)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = stringResource(R.string.current_value),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.caption.copy(color = MaterialTheme.colors.onSurface.copy(alpha = 0.65f))
                    )
                    Text(
                        text = stringResource(R.string.pending_value),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.caption.copy(color = MaterialTheme.colors.primary)
                    )
                }
                Divider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.12f)
                )
                rows.forEach { (label, values) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = label,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.caption.copy(color = MaterialTheme.colors.onSurface.copy(alpha = 0.65f))
                        )
                        Text(
                            text = values.first.ifEmpty { "--" },
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.body2
                        )
                        Text(
                            text = values.second.ifEmpty { "--" },
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.body2.copy(color = MaterialTheme.colors.primary)
                        )
                    }
                }
            }
        }
    }

    private fun parseLong(str: String): Long {
        return str.toLongOrNull() ?: -1L
    }

    /** An empty field means "keep the built-in default"; anything else must be a usable value. */
    private fun isCoordinateInvalid(value: String, minimum: Double, maximum: Double): Boolean =
        value.isNotEmpty() && !CoordinateParser.isValid(value, minimum, maximum)

    private fun isCellValueInvalid(value: String): Boolean =
        value.isNotEmpty() && parseLong(value) < 0

    private var listener: PhoneStateListener = object : PhoneStateListener() {
        override fun onCellLocationChanged(location: CellLocation) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return
            if (location is GsmCellLocation) {
                _currentLac.value = location.lac.toString()
                _currentCid.value = location.cid.toString()
            }
        }

        override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>?) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
            val cells = cellInfo ?: return
            // Neighbour cells share this list, and an unsupported or unavailable identity would
            // otherwise be shown (and saved) as the current cell. Prefer the registered cell.
            val cell = cells.filter { it.isRegistered }.firstNotNullOfOrNull(::cellOf)
                ?: cells.firstNotNullOfOrNull(::cellOf)
                ?: return
            _currentLac.value = cell.first.toString()
            _currentCid.value = cell.second.toString()
        }
    }

    /** @return the (area code, cell identity) pair a usable identity reports, or null. */
    private fun cellOf(info: CellInfo): Pair<Long, Long>? {
        return when (val identity = info.cellIdentity) {
            is CellIdentityGsm -> cellPair(identity.lac, identity.cid)
            is CellIdentityWcdma -> cellPair(identity.lac, identity.cid)
            is CellIdentityTdscdma -> cellPair(identity.lac, identity.cid)
            is CellIdentityLte -> cellPair(identity.tac, identity.ci)
            is CellIdentityNr -> if (identity.tac == CellInfo.UNAVAILABLE ||
                identity.nci == CellInfo.UNAVAILABLE.toLong()
            ) {
                null
            } else {
                identity.tac.toLong() to identity.nci
            }
            else -> null
        }
    }

    private fun cellPair(lac: Int, cid: Int): Pair<Long, Long>? {
        if (lac == CellInfo.UNAVAILABLE || cid == CellInfo.UNAVAILABLE) return null
        return lac.toLong() to cid.toLong()
    }

    private var currentLocation: Location? = null

    private var gpsListener: LocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            acceptLocation(location)
        }

        override fun onStatusChanged(provider: String, status: Int, extras: Bundle) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private fun updateCurrentLocation(location: Location) {
        _currentLatitude.value = location.latitude.toString()
        _currentLongitude.value = location.longitude.toString()
    }

    private fun requestPermissions() {
        val permissions = mutableListOf<String>()
        // Cell identity needs FINE, so a COARSE-only grant has to be upgraded: otherwise the cell
        // section stays empty forever with no way to fix it from this page.
        if (!hasFineLocationPermission()) {
            permissions += Manifest.permission.ACCESS_FINE_LOCATION
            permissions += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !hasPhoneStatePermission()) {
            permissions += Manifest.permission.READ_PHONE_STATE
        }
        if (permissions.isEmpty()) {
            startLocation()
        } else {
            ActivityCompat.requestPermissions(this, permissions.distinct().toTypedArray(), 101)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && hasLocationPermission()) {
            startLocation()
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED || ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasFineLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
    private fun hasPhoneStatePermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasCellPermission(): Boolean {
        return hasFineLocationPermission() &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || hasPhoneStatePermission())
    }

    private fun startLocation() {
        if (!hasLocationPermission()) return
        if (!locationStarted) {
            locationStarted = true
            val generation = ++locationGeneration
            val enabledProviders = lm.getProviders(true)
            // The cached fix of any provider is free to read, so try them all before requesting.
            for (provider in enabledProviders) {
                lm.getLastKnownLocation(provider)?.let(::acceptLocation)
            }
            for (provider in LocationProviderSelector.activeProviders(enabledProviders)) {
                lm.requestLocationUpdates(provider, REFRESH_INTERVAL_MS, 0f, gpsListener, mainLooper)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val provider = LocationProviderSelector.activeProviders(enabledProviders)
                    .firstOrNull { it != LocationManager.PASSIVE_PROVIDER }
                if (provider != null) {
                    // A one-shot request ending in the first fix; cancelled in stopLocation so that
                    // leaving the page cannot leave a running request behind.
                    val signal = CancellationSignal()
                    currentLocationRequest = signal
                    lm.getCurrentLocation(provider, signal, mainExecutor) { location ->
                        if (locationStarted && generation == locationGeneration) {
                            location?.let(::acceptLocation)
                        }
                    }
                }
            }
        }
        if (!cellListenerStarted && hasCellPermission()) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                tm.listen(listener, PhoneStateListener.LISTEN_CELL_LOCATION)
            } else {
                tm.listen(listener, PhoneStateListener.LISTEN_CELL_INFO)
            }
            cellListenerStarted = true
        }
    }

    /** Keeps the newest fix of the enabled providers, whatever its source. */
    private fun acceptLocation(location: Location) {
        if (CurrentLocationSelector.shouldReplace(currentLocation, location)) {
            currentLocation = location
            updateCurrentLocation(location)
        }
    }

    private fun stopLocation() {
        locationGeneration++
        if (!locationStarted && !cellListenerStarted) return
        locationStarted = false
        currentLocationRequest?.cancel()
        currentLocationRequest = null
        if (::tm.isInitialized && cellListenerStarted) {
            tm.listen(listener, PhoneStateListener.LISTEN_NONE)
            cellListenerStarted = false
        }
        if (::lm.isInitialized) {
            lm.removeUpdates(gpsListener)
        }
    }
}
