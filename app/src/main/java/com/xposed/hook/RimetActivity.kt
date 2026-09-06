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
import com.xposed.hook.extension.toBitmap
import com.xposed.hook.theme.AppTheme
import com.xposed.hook.utils.CellLocationHelper
import com.xposed.hook.utils.SharedPreferencesHelper

internal object LocationProviderSelector {
    fun orderedProviders(providers: List<String>): List<String> {
        val preferredProviders = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        return (preferredProviders + providers).distinct().filter { it in providers }
    }
}

class RimetActivity : AppCompatActivity() {

    private lateinit var sp: SharedPreferences
    private lateinit var appInfo: AppInfo
    private var isDingTalk = false

    private lateinit var tm: TelephonyManager
    private lateinit var l: GsmCellLocation
    private lateinit var lm: LocationManager
    private lateinit var gpsL: Location

    private val _currentLatitude = MutableLiveData("")
    private val _currentLongitude = MutableLiveData("")
    private val _currentLac = MutableLiveData("")
    private val _currentCid = MutableLiveData("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureImmersiveStatusBar()
        appInfo = intent.getSerializableExtra("appInfo") as? AppInfo ?: return
        title = appInfo.title
        isDingTalk = PkgConfig.pkg_dingtalk == appInfo.packageName
        tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        lm = getSystemService(LOCATION_SERVICE) as LocationManager
        sp = getSharedPreferences(Constants.PREF_FILE_NAME, MODE_PRIVATE)
        setContent { Container() }
        requestPermissions()
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
                            sp.edit()
                                .putString(prefix + "latitude", latitude)
                                .putString(prefix + "longitude", longitude)
                                .putLong(prefix + "lac", parseLong(lac))
                                .putLong(prefix + "cid", parseLong(cid))
                                .putLong(prefix + "time", System.currentTimeMillis())
                                .putBoolean(appInfo.packageName, isChecked)
                                .commit()
                            SharedPreferencesHelper.makeWorldReadable(sp)
                            Toast.makeText(
                                applicationContext,
                                R.string.save_success,
                                Toast.LENGTH_SHORT
                            ).show()
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
            if (appInfo.icon != null) {
                Image(
                    bitmap = appInfo.icon.toBitmap(48.dpInPx, 48.dpInPx),
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
        return try {
            str.toLong()
        } catch (e: Exception) {
            -1
        }
    }

    override fun finish() {
        stopLocation()
        super.finish()
    }

    private var listener: PhoneStateListener = object : PhoneStateListener() {
        override fun onCellLocationChanged(location: CellLocation) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return
            if (location is GsmCellLocation) {
                l = location
                _currentLac.value = l.lac.toString()
                _currentCid.value = l.cid.toString()
            }
        }

        override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>?) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
            if (cellInfo == null || cellInfo.isEmpty()) return
            when (val cellIdentity = cellInfo[0].cellIdentity) {
                is CellIdentityGsm -> {
                    _currentLac.value = cellIdentity.lac.toString()
                    _currentCid.value = cellIdentity.cid.toString()
                }
                is CellIdentityLte -> {
                    _currentLac.value = cellIdentity.tac.toString()
                    _currentCid.value = cellIdentity.ci.toString()
                }
                is CellIdentityNr -> {
                    _currentLac.value = cellIdentity.tac.toString()
                    _currentCid.value = cellIdentity.nci.toString()
                }
            }
        }
    }

    private var gpsListener: LocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            gpsL = location
            updateCurrentLocation(location)
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
        if (!hasLocationPermission()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ),
                101
            )
            return
        }
        startLocation()
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

    private fun startLocation() {
        if (!hasLocationPermission()) {
            return
        }

        val providers = LocationProviderSelector.orderedProviders(lm.getProviders(true))
        var lastKnownLocation: Location? = null
        for (provider in providers) {
            lm.getLastKnownLocation(provider)?.let { location ->
                if (lastKnownLocation == null || location.time > lastKnownLocation!!.time) {
                    lastKnownLocation = location
                }
            }
            lm.requestLocationUpdates(provider, 1000L, 0f, gpsListener, mainLooper)
        }
        lastKnownLocation?.let { updateCurrentLocation(it) }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            tm.listen(listener, PhoneStateListener.LISTEN_CELL_LOCATION)
        } else {
            tm.listen(listener, PhoneStateListener.LISTEN_CELL_INFO)
        }
    }

    private fun stopLocation() {
        tm.listen(listener, PhoneStateListener.LISTEN_NONE)
        lm.removeUpdates(gpsListener)
    }
}
