package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val OrganicDarkColorScheme = darkColorScheme(
    primary = SagePrimaryDark,
    onPrimary = SageOnPrimaryDark,
    primaryContainer = SagePrimaryContainerDark,
    onPrimaryContainer = SageOnPrimaryContainerDark,
    secondary = TerracottaSecondaryDark,
    onSecondary = TerracottaOnSecondaryDark,
    secondaryContainer = TerracottaSecondaryContainerDark,
    onSecondaryContainer = TerracottaOnSecondaryContainerDark,
    tertiary = OliveTertiaryDark,
    onTertiary = OliveOnTertiaryDark,
    background = OrganicBackgroundDark,
    onBackground = OrganicOnBackgroundDark,
    surface = OrganicSurfaceDark,
    onSurface = OrganicOnSurfaceDark,
    surfaceVariant = OrganicSurfaceVariantDark,
    onSurfaceVariant = OrganicOnSurfaceVariantDark,
    outline = OrganicOutlineDark
)

private val OrganicLightColorScheme = lightColorScheme(
    primary = SagePrimaryLight,
    onPrimary = SageOnPrimaryLight,
    primaryContainer = SagePrimaryContainerLight,
    onPrimaryContainer = SageOnPrimaryContainerLight,
    secondary = TerracottaSecondaryLight,
    onSecondary = TerracottaOnSecondaryLight,
    secondaryContainer = TerracottaSecondaryContainerLight,
    onSecondaryContainer = TerracottaOnSecondaryContainerLight,
    tertiary = OliveTertiaryLight,
    onTertiary = OliveOnTertiaryLight,
    background = OrganicBackgroundLight,
    onBackground = OrganicOnBackgroundLight,
    surface = OrganicSurfaceLight,
    onSurface = OrganicOnSurfaceLight,
    surfaceVariant = OrganicSurfaceVariantLight,
    onSurfaceVariant = OrganicOnSurfaceVariantLight,
    outline = OrganicOutlineLight
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> OrganicDarkColorScheme
        else -> OrganicLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
