package com.example.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val CozyDarkColorScheme = darkColorScheme(
    primary = AmberPrimaryDark,
    onPrimary = CozyDarkBackground,
    primaryContainer = AmberSecondaryLight,
    onPrimaryContainer = AmberTertiaryDark,
    secondary = AmberSecondaryDark,
    onSecondary = CozyDarkBackground,
    tertiary = AmberTertiaryDark,
    background = CozyDarkBackground,
    surface = CozyDarkSurface,
    surfaceVariant = CozyDarkSurfaceVariant,
    onBackground = CozyDarkOnSurface,
    onSurface = CozyDarkOnSurface,
    outline = CozyDarkOutline
)

private val CozyLightColorScheme = lightColorScheme(
    primary = AmberPrimaryLight,
    onPrimary = CozyLightSurface,
    primaryContainer = AmberTertiaryDark,
    onPrimaryContainer = AmberSecondaryLight,
    secondary = AmberSecondaryLight,
    onSecondary = CozyLightSurface,
    tertiary = AmberTertiaryLight,
    background = CozyLightBackground,
    surface = CozyLightSurface,
    surfaceVariant = CozyLightSurfaceVariant,
    onBackground = CozyLightOnSurface,
    onSurface = CozyLightOnSurface,
    outline = CozyLightOutline
)

@Composable
fun OmniFileTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Material You dynamic color enabled by default on Android 12+
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> CozyDarkColorScheme
        else -> CozyLightColorScheme
    }

    // Edge-to-Edge System Bars matching our warm palette
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !darkTheme
            insetsController.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}