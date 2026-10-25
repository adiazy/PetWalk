package com.karaoketv.ui

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

enum class BackgroundStyle(val label: String, val colors: List<Color>) {
    Night("Purple night", listOf(com.karaoketv.ui.Night, NightLight, com.karaoketv.ui.Night)),
    Ocean("Ocean", listOf(Color(0xFF021B2E), Color(0xFF0B4F6C), Color(0xFF021B2E))),
    Sunset("Sunset", listOf(Color(0xFF2B0A1E), Color(0xFF8C2F39), Color(0xFFE07A2E))),
    Forest("Forest", listOf(Color(0xFF07170F), Color(0xFF1B4D3E), Color(0xFF07170F))),
    Neon("Neon club", listOf(Color(0xFF0D0221), Color(0xFF261447), Color(0xFF2E2157), Color(0xFF0D0221))),
    Black("Plain black", listOf(Color.Black, Color.Black)),
    ;

    val brush get() = Brush.verticalGradient(colors)
}

enum class FontChoice(val label: String, val family: FontFamily, val weight: FontWeight) {
    Modern("Modern", FontFamily.SansSerif, FontWeight.Bold),
    Light("Light", FontFamily.SansSerif, FontWeight.Light),
    Classic("Classic serif", FontFamily.Serif, FontWeight.Bold),
    Typewriter("Typewriter", FontFamily.Monospace, FontWeight.Bold),
    Handwritten("Handwritten", FontFamily.Cursive, FontWeight.Bold),
}

enum class TextSize(val label: String, val scale: Float) {
    Small("Small", 0.8f),
    Medium("Medium", 1f),
    Large("Large", 1.25f),
    Huge("Huge", 1.5f),
}

enum class HighlightColor(val label: String, val color: Color) {
    Gold("Gold", com.karaoketv.ui.Gold),
    Pink("Pink", com.karaoketv.ui.Pink),
    Cyan("Cyan", Color(0xFF4FD8FF)),
    Lime("Lime", Color(0xFFB4F04F)),
    Orange("Orange", Color(0xFFFF9F43)),
}

data class Appearance(
    val background: BackgroundStyle = BackgroundStyle.Night,
    val font: FontChoice = FontChoice.Modern,
    val textSize: TextSize = TextSize.Medium,
    val highlight: HighlightColor = HighlightColor.Gold,
)

val LocalAppearance = staticCompositionLocalOf { Appearance() }

/** Saves the look on the TV so it survives restarts. */
class AppearanceStore(context: Context) {
    private val prefs = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)

    fun load() = Appearance(
        background = enumOr(prefs.getString(KEY_BACKGROUND, null), BackgroundStyle.Night),
        font = enumOr(prefs.getString(KEY_FONT, null), FontChoice.Modern),
        textSize = enumOr(prefs.getString(KEY_SIZE, null), TextSize.Medium),
        highlight = enumOr(prefs.getString(KEY_HIGHLIGHT, null), HighlightColor.Gold),
    )

    fun save(a: Appearance) {
        prefs.edit()
            .putString(KEY_BACKGROUND, a.background.name)
            .putString(KEY_FONT, a.font.name)
            .putString(KEY_SIZE, a.textSize.name)
            .putString(KEY_HIGHLIGHT, a.highlight.name)
            .apply()
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default

    private companion object {
        const val KEY_BACKGROUND = "background"
        const val KEY_FONT = "font"
        const val KEY_SIZE = "size"
        const val KEY_HIGHLIGHT = "highlight"
    }
}
