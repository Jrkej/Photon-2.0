package com.photon.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Ink = Color(0xFF1B1F3B)
private val Amber = Color(0xFFFFC94A)
private val Coral = Color(0xFFE5484D)

private val Light = lightColorScheme(
    primary = Ink,
    onPrimary = Color.White,
    secondary = Color(0xFF8A6A00),
    tertiary = Coral,
)

private val Dark = darkColorScheme(
    primary = Amber,
    onPrimary = Ink,
    secondary = Color(0xFFFFE08A),
    tertiary = Color(0xFFFF8A8E),
)

@Composable
fun PhotonTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
