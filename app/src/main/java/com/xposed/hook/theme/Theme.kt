package com.xposed.hook.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.ContentAlpha
import androidx.compose.material.LocalContentAlpha
import androidx.compose.material.LocalContentColor
import androidx.compose.material.MaterialTheme
import androidx.compose.material.ProvideTextStyle
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

private val LightThemeColors = lightColors(
    primary = TealPrimary,
    primaryVariant = TealDark,
    secondary = TealPrimary,
    secondaryVariant = TealDark,
    background = CanvasLight,
    surface = SurfaceLight,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Ink,
    onSurface = Ink
)

private val DarkThemeColors = darkColors(
    primary = TealLight,
    primaryVariant = TealPrimary,
    secondary = TealLight,
    background = CanvasDark,
    surface = SurfaceDark,
    onPrimary = Color(0xff073b36),
    onSecondary = Color(0xff073b36),
    onBackground = Color(0xffe5eeec),
    onSurface = Color(0xffe5eeec)
)

private val LightTextStyle = TextStyle(color = Ink)

private val DarkTextStyle = TextStyle(color = Color(0xffe5eeec))

@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkThemeColors else LightThemeColors
    MaterialTheme(colors = colors) {
        CompositionLocalProvider(
            LocalContentColor provides colors.onSurface,
            LocalContentAlpha provides ContentAlpha.high
        ) {
            ProvideTextStyle(
                value = if (darkTheme) DarkTextStyle else LightTextStyle,
                content = content
            )
        }
    }
}
