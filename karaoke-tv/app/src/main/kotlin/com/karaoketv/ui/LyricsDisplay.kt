package com.karaoketv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.karaoketv.core.lyrics.Lyrics

@Composable
fun LyricsDisplay(lyrics: Lyrics, positionMs: Long, durationMs: Long?, modifier: Modifier = Modifier) {
    if (lyrics.synced) SyncedLyrics(lyrics, positionMs, durationMs, modifier)
    else ScrollingLyrics(lyrics, positionMs, durationMs, modifier)
}

@Composable
private fun SyncedLyrics(lyrics: Lyrics, positionMs: Long, durationMs: Long?, modifier: Modifier) {
    val look = LocalAppearance.current
    val scale = look.textSize.scale
    val current = lyrics.lineIndexAt(positionMs)
    Column(
        modifier.fillMaxSize().padding(horizontal = 64.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val firstStart = lyrics.lines.firstOrNull()?.startMs ?: 0
        if (current == -1 && firstStart - positionMs in 1..COUNTDOWN_MS) {
            // Get-ready dots before the first line, like a real karaoke machine.
            val dots = ((firstStart - positionMs) / 1000 + 1).toInt()
            Text("● ".repeat(dots).trim(), color = look.highlight.color, fontSize = 40.sp * scale)
        }
        for (offset in -1..2) {
            val i = current + offset
            val line = lyrics.lines.getOrNull(i)
            val text = line?.text?.ifBlank { "♪" } ?: ""
            if (offset == 0 && line != null) {
                HighlightedLine(text, lyrics.progressAt(i, positionMs, durationMs), 56.sp * scale)
            } else {
                Text(
                    text,
                    fontSize = (if (offset < 0) 30.sp else 38.sp) * scale,
                    fontFamily = look.font.family,
                    color = Color.White.copy(alpha = if (offset < 0) 0.35f else 0.7f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
    }
}

/** White text with a colored fill sweeping across it, line by line for wrapped text. */
@Composable
fun HighlightedLine(text: String, progress: Float, fontSize: TextUnit) {
    val look = LocalAppearance.current
    var layout by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
    Box(Modifier.padding(vertical = 12.dp)) {
        Text(
            text,
            fontSize = fontSize,
            fontFamily = look.font.family,
            fontWeight = look.font.weight,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        Text(
            text,
            fontSize = fontSize,
            fontFamily = look.font.family,
            fontWeight = look.font.weight,
            color = look.highlight.color,
            textAlign = TextAlign.Center,
            onTextLayout = { layout = it },
            modifier = Modifier.drawWithContent {
                val l = layout ?: return@drawWithContent
                val charPos = progress * text.length
                val path = Path()
                for (line in 0 until l.lineCount) {
                    val start = l.getLineStart(line)
                    val end = l.getLineEnd(line)
                    val left = l.getLineLeft(line)
                    val right = l.getLineRight(line)
                    val top = l.getLineTop(line)
                    val bottom = l.getLineBottom(line)
                    when {
                        charPos >= end -> path.addRect(Rect(left, top, right, bottom))
                        charPos > start -> {
                            val f = (charPos - start) / (end - start).coerceAtLeast(1)
                            path.addRect(Rect(left, top, left + (right - left) * f, bottom))
                        }
                    }
                }
                clipPath(path) { this@drawWithContent.drawContent() }
            },
        )
    }
}

/** Unsynced lyrics: scroll through them in proportion to song progress. */
@Composable
private fun ScrollingLyrics(lyrics: Lyrics, positionMs: Long, durationMs: Long?, modifier: Modifier) {
    val state = rememberLazyListState()
    val target = if (durationMs != null && durationMs > 0) {
        ((positionMs.toFloat() / durationMs) * lyrics.lines.size).toInt().coerceIn(0, lyrics.lines.lastIndex.coerceAtLeast(0))
    } else {
        0
    }
    LaunchedEffect(target) { state.animateScrollToItem((target - 3).coerceAtLeast(0)) }
    val look = LocalAppearance.current
    Column(modifier.fillMaxSize()) {
        Text(
            "Lyrics aren't time-synced for this song, so they scroll with the music.",
            color = look.highlight.color,
            fontSize = 18.sp,
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            textAlign = TextAlign.Center,
        )
        LazyColumn(state = state, modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            itemsIndexed(lyrics.lines) { i, line ->
                Text(
                    line.text,
                    fontSize = 34.sp * look.textSize.scale,
                    fontFamily = look.font.family,
                    color = if (i == target) look.highlight.color else Color.White.copy(alpha = 0.55f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }
        }
    }
}

private const val COUNTDOWN_MS = 3_000L
