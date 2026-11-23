package com.swipegallery.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF7ED99A),
    onPrimary = Color(0xFF06280F),
    secondary = Color(0xFF8FB8FF),
    background = Color(0xFF000000),
    onBackground = Color(0xFFEDEDED),
    surface = Color(0xFF141414),
    onSurface = Color(0xFFEDEDED),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF2A0000),
)

@Composable
fun SwipeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkScheme, content = content)
}

val KeepGreen = Color(0xFF35C759)
val DeleteRed = Color(0xFFFF453A)
