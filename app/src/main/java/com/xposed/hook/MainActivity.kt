package com.xposed.hook

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.xposed.hook.entity.AppInfo
import com.xposed.hook.extension.dpInPx
import com.xposed.hook.config.Constants
import com.xposed.hook.extension.toBitmap
import com.xposed.hook.theme.AppTheme
import com.xposed.hook.utils.AppHelper
import com.xposed.hook.utils.SharedPreferencesHelper
import kotlinx.coroutines.launch

/**
 * Created by lin on 2021/8/7.
 */
class MainActivity : AppCompatActivity() {

    private var appList by mutableStateOf(emptyList<AppInfo>())
    private lateinit var preferences: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = getSharedPreferences(Constants.PREF_FILE_NAME, MODE_PRIVATE)
        setContent { AppScaffold(appList) }
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
        AppTheme {
            Column {
                var textState by remember { mutableStateOf(TextFieldValue()) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    UserInputText(
                        onTextChanged = { textState = it },
                        textFieldValue = textState,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = stringResource(R.string.show_system_apps),
                        fontSize = 12.sp
                    )
                    Switch(
                        checked = showSystemApps,
                        onCheckedChange = { enabled ->
                            showSystemApps = enabled
                            preferences.edit().putBoolean(Constants.SHOW_SYSTEM_APPS, enabled).commit()
                            SharedPreferencesHelper.makeWorldReadable(preferences)
                        }
                    )
                }
                val visibleList = if (showSystemApps) list else list.filterNot { it.isSystem }
                AppList(if (textState.text.isNotEmpty()) visibleList.filter { info ->
                    info.title.contains(textState.text, true)
                } else visibleList)
            }
        }
    }

    @Composable
    fun AppList(list: List<AppInfo>) {
        LazyColumn {
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
        Row(
            Modifier
                .fillMaxWidth()
                .clickable {
                    startActivity(Intent(this, RimetActivity::class.java).putExtra("appInfo", item))
                }
                .padding(12.dp, 12.dp), verticalAlignment = Alignment.CenterVertically
        ) {
            Image(item.icon.toBitmap(36.dpInPx, 36.dpInPx), item.title)
            Column(Modifier.padding(10.dp, 0.dp).weight(1f)) {
                Text(item.title)
                Text(item.packageName, Modifier.padding(0.dp, 3.dp, 0.dp, 0.dp), fontSize = 12.sp)
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
                }
            )
        }
    }

    @Composable
    private fun UserInputText(
        keyboardType: KeyboardType = KeyboardType.Text,
        onTextChanged: (TextFieldValue) -> Unit,
        textFieldValue: TextFieldValue,
        modifier: Modifier = Modifier
    ) {
        Surface(modifier = modifier) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .height(64.dp)
                        .weight(1f)
                        .align(Alignment.Bottom)
                ) {
                    BasicTextField(
                        value = textFieldValue,
                        onValueChange = { onTextChanged(it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 32.dp)
                            .align(Alignment.CenterStart),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = keyboardType,
                            imeAction = ImeAction.Search
                        ),
                        maxLines = 1,
                        cursorBrush = SolidColor(LocalContentColor.current),
                        textStyle = LocalTextStyle.current.copy(color = LocalContentColor.current)
                    )

                    val disableContentColor = MaterialTheme.colors.onSurface
                    if (textFieldValue.text.isEmpty()) {
                        Text(
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .padding(start = 32.dp),
                            text = stringResource(id = R.string.search_app),
                            style = MaterialTheme.typography.body1.copy(color = disableContentColor)
                        )
                    }
                }
            }
        }
    }
}