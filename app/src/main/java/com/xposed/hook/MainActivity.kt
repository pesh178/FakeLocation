package com.xposed.hook

import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.xposed.hook.config.Constants
import com.xposed.hook.entity.AppInfo
import com.xposed.hook.extension.dpInPx
import com.xposed.hook.extension.toBitmap
import com.xposed.hook.theme.AppTheme
import com.xposed.hook.utils.AppHelper
import com.xposed.hook.utils.SharedPreferencesHelper
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private var appList by mutableStateOf(emptyList<AppInfo>())
    private lateinit var preferences: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureImmersiveStatusBar()
        preferences = getSharedPreferences(Constants.PREF_FILE_NAME, MODE_PRIVATE)
        setContent { AppScaffold(appList) }
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

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            appList = AppHelper.getAppList()
        }
    }

    @Composable
    fun AppScaffold(list: List<AppInfo>) {
        var showSystemApps by remember {
            mutableStateOf(preferences.getBoolean(Constants.SHOW_SYSTEM_APPS, false))
        }
        var textState by remember { mutableStateOf(TextFieldValue()) }
        val visibleList = if (showSystemApps) list else list.filterNot { it.isSystem }
        val filteredList = if (textState.text.isNotEmpty()) {
            visibleList.filter { it.title.contains(textState.text, true) }
        } else {
            visibleList
        }

        AppTheme {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colors.background)
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp)
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.h5.copy(
                        color = MaterialTheme.colors.onBackground,
                        fontWeight = FontWeight.Bold
                    ),
                    modifier = Modifier.padding(top = 24.dp, bottom = 16.dp)
                )
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colors.surface,
                    elevation = 1.dp
                ) {
                    UserInputText(
                        onTextChanged = { textState = it },
                        textFieldValue = textState,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp, bottom = 16.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colors.surface,
                    elevation = 1.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.show_system_apps),
                                style = MaterialTheme.typography.body1.copy(fontWeight = FontWeight.Medium)
                            )
                            Text(
                                text = stringResource(R.string.show_system_apps_hint),
                                style = MaterialTheme.typography.caption.copy(color = MaterialTheme.colors.onSurface.copy(alpha = 0.65f))
                            )
                        }
                        Switch(
                            checked = showSystemApps,
                            onCheckedChange = { enabled ->
                                showSystemApps = enabled
                                preferences.edit()
                                    .putBoolean(Constants.SHOW_SYSTEM_APPS, enabled)
                                    .commit()
                                SharedPreferencesHelper.makeWorldReadable(preferences)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colors.primary,
                                checkedTrackColor = MaterialTheme.colors.primary.copy(alpha = 0.2f)
                            )
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.app_list_title, filteredList.size),
                    style = MaterialTheme.typography.subtitle2.copy(color = MaterialTheme.colors.onSurface.copy(alpha = 0.65f)),
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                AppList(filteredList, Modifier.weight(1f))
            }
        }
    }

    @Composable
    fun AppList(list: List<AppInfo>, modifier: Modifier = Modifier) {
        LazyColumn(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 20.dp)
        ) {
            items(list, key = { it.packageName }) { item ->
                AppItem(item)
            }
        }
    }

    @Composable
    fun AppItem(item: AppInfo) {
        var isHookEnabled by remember(item.packageName, item.enabled) {
            mutableStateOf(item.enabled)
        }
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    startActivity(Intent(this, RimetActivity::class.java).putExtra("appInfo", item))
                },
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colors.surface,
            elevation = 1.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    bitmap = item.icon.toBitmap(44.dpInPx, 44.dpInPx),
                    contentDescription = item.title,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp))
                )
                Column(
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .weight(1f)
                ) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.body1.copy(fontWeight = FontWeight.Medium)
                    )
                    Text(
                        text = item.packageName,
                        style = MaterialTheme.typography.caption.copy(color = MaterialTheme.colors.onSurface.copy(alpha = 0.65f)),
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
                Switch(
                    checked = isHookEnabled,
                    onCheckedChange = { enabled ->
                        isHookEnabled = enabled
                        item.enabled = enabled
                        preferences.edit().putBoolean(item.packageName, enabled).commit()
                        SharedPreferencesHelper.makeWorldReadable(preferences)
                        lifecycleScope.launch {
                            appList = AppHelper.getAppList()
                        }
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colors.primary,
                        checkedTrackColor = MaterialTheme.colors.primary.copy(alpha = 0.2f)
                    )
                )
            }
        }
    }

    @Composable
    private fun UserInputText(
        keyboardType: KeyboardType = KeyboardType.Text,
        onTextChanged: (TextFieldValue) -> Unit,
        textFieldValue: TextFieldValue,
        modifier: Modifier = Modifier
    ) {
        Row(
            modifier = modifier
                .height(56.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                BasicTextField(
                    value = textFieldValue,
                    onValueChange = { onTextChanged(it) },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = keyboardType,
                        imeAction = ImeAction.Search
                    ),
                    maxLines = 1,
                    cursorBrush = SolidColor(MaterialTheme.colors.primary),
                    textStyle = LocalTextStyle.current.copy(color = MaterialTheme.colors.onSurface)
                )
                if (textFieldValue.text.isEmpty()) {
                    Text(
                        text = stringResource(R.string.search_app),
                        style = MaterialTheme.typography.body1.copy(color = MaterialTheme.colors.onSurface.copy(alpha = 0.65f))
                    )
                }
            }
        }
    }
}
