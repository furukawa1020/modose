package com.modose.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ModoseColors = lightColorScheme(
    primary = Color(0xFF005A55),
    onPrimary = Color.White,
    background = Color(0xFFF4F1E8),
    onBackground = Color(0xFF1A1C1B),
    surface = Color(0xFFF4F1E8),
    onSurface = Color(0xFF1A1C1B),
)

@Composable
fun ModoseTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ModoseColors,
        typography = Typography(),
        content = content,
    )
}
