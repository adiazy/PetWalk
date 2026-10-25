package com.karaoketv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.karaoketv.KaraokeViewModel

@Composable
fun SettingsPane(vm: KaraokeViewModel) {
    // Draw this screen 15% smaller: scaling density shrinks all dp sizes and sp text together.
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density.density * SCALE, density.fontScale),
    ) {
        SettingsContent(vm)
    }
}

private const val SCALE = 0.85f

@Composable
private fun SettingsContent(vm: KaraokeViewModel) {
    val look = vm.appearance
    LazyColumn(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Text("Look & feel", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            // Live preview of a lyric line, frozen halfway through.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .background(look.background.brush, RoundedCornerShape(16.dp))
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                HighlightedLine("Sing it loud, sing it proud", 0.5f, 44.sp * look.textSize.scale)
            }
        }
        item {
            OptionRow("Album art behind the lyrics", listOf(true, false), look.albumArtBackground, { if (it) "On" else "Off" }) { choice ->
                vm.updateAppearance { it.copy(albumArtBackground = choice) }
            }
        }
        item {
            OptionRow("Background (when there's no album art)", BackgroundStyle.entries, look.background, { it.label }) { choice ->
                vm.updateAppearance { it.copy(background = choice) }
            }
        }
        item {
            OptionRow("Font", FontChoice.entries, look.font, { it.label }) { choice ->
                vm.updateAppearance { it.copy(font = choice) }
            }
        }
        item {
            OptionRow("Lyrics size", TextSize.entries, look.textSize, { it.label }) { choice ->
                vm.updateAppearance { it.copy(textSize = choice) }
            }
        }
        item {
            OptionRow("Menu & list size (whole app)", UiScale.entries, look.uiScale, { it.label }) { choice ->
                vm.updateAppearance { it.copy(uiScale = choice) }
            }
        }
        item {
            OptionRow("Highlight color", HighlightColor.entries, look.highlight, { it.label }) { choice ->
                vm.updateAppearance { it.copy(highlight = choice) }
            }
        }
        item {
            Button(onClick = { vm.updateAppearance { Appearance() } }) { Text("Reset to default") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun <T> OptionRow(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.8f))
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(options) { option ->
                Button(onClick = { onSelect(option) }) {
                    Text(if (option == selected) "✓ ${label(option)}" else label(option))
                }
            }
        }
    }
}
