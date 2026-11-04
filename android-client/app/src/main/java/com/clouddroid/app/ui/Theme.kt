package com.clouddroid.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Dark = darkColorScheme(
    primary = Color(0xFF7CC4FF),
    onPrimary = Color(0xFF00324F),
    primaryContainer = Color(0xFF004B73),
    onPrimaryContainer = Color(0xFFCDE5FF),
    secondary = Color(0xFFB4C8DB),
    background = Color(0xFF0B0F14),
    onBackground = Color(0xFFE2E6EB),
    surface = Color(0xFF0B0F14),
    onSurface = Color(0xFFE2E6EB),
    surfaceVariant = Color(0xFF1A222B),
    onSurfaceVariant = Color(0xFFA7B2BF),
    surfaceContainer = Color(0xFF131A21),
    surfaceContainerHigh = Color(0xFF1A222B),
    error = Color(0xFFFFB4AB),
)

private val Light = lightColorScheme(
    primary = Color(0xFF00629B),
    primaryContainer = Color(0xFFCDE5FF),
    background = Color(0xFFF7F9FC),
    surface = Color(0xFFF7F9FC),
)

val Good = Color(0xFF4CD08B)
val Warn = Color(0xFFFFC857)

@Composable
fun CloudDroidTheme(dark: Boolean = true, content: @Composable () -> Unit) {
    // The design is a minimalist dark UI; the light scheme exists for accessibility when the system asks for it.
    val useDark = dark || isSystemInDarkTheme()
    MaterialTheme(colorScheme = if (useDark) Dark else Light, content = content)
}
