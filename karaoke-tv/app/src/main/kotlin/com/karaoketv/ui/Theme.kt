package com.karaoketv.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

val Pink = Color(0xFFFF4FA3)
val Gold = Color(0xFFFFD84F)
val Night = Color(0xFF12081F)
val NightLight = Color(0xFF2A1248)

@Composable
fun KaraokeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Pink,
            secondary = Gold,
            background = Night,
            surface = NightLight,
        ),
        content = content,
    )
}

fun formatTime(ms: Long?): String {
    if (ms == null || ms < 0) return "--:--"
    val totalSec = ms / 1000
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}
