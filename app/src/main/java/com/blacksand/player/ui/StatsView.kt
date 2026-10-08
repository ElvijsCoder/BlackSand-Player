package com.blacksand.player.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blacksand.player.PlayerViewModel
import com.blacksand.player.data.Song
import java.time.LocalDate
import java.util.Locale

/** Listening stats: this week's time with a dot-matrix chart, most played songs, top artists. */
@Composable
fun StatsView(vm: PlayerViewModel, songs: List<Song>, onPlay: (List<Song>, Int) -> Unit) {
    val stats by vm.stats.collectAsState()
    LaunchedEffect(Unit) { vm.loadStats() }
    val s = stats ?: return

    val byId = remember(songs) { songs.associateBy { it.id } }
    val days = remember { (6 downTo 0).map { LocalDate.now().minusDays(it.toLong()) } }
    val dayMs = days.map { s.days[it.toString()] ?: 0L }
    val totalMs = s.days.values.sum()
    val topSongs = s.songs.entries
        .filter { it.value.plays > 0 && byId[it.key] != null }
        .sortedByDescending { it.value.plays }
        .take(10)
        .map { byId.getValue(it.key) to it.value.plays }
    val topArtists = s.songs.entries
        .mapNotNull { e -> byId[e.key]?.let { it.artist to e.value.listenedMs } }
        .groupBy({ it.first }, { it.second })
        .mapValues { it.value.sum() }
        .entries.sortedByDescending { it.value }
        .take(5)

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text("THIS WEEK", color = Sand.Dim, fontSize = 11.sp, letterSpacing = 2.sp)
                Text(formatListen(dayMs.sum()), fontFamily = DotFont, fontSize = 40.sp, color = Sand.White)
                Text("ALL TIME ${formatListen(totalMs)}", color = Sand.Dim, fontSize = 11.sp, letterSpacing = 2.sp)
                Spacer(Modifier.height(16.dp))
                WeekChart(days, dayMs)
            }
        }
        if (topSongs.isEmpty()) {
            item { Text("Listen to some music and your stats will build up here.", Modifier.padding(20.dp), color = Sand.Dim) }
        } else {
            item { StatsHeading("MOST PLAYED") }
            itemsIndexed(topSongs) { i, (song, plays) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPlay(topSongs.map { it.first }, i) }
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("%02d".format(i + 1), Modifier.width(36.dp), fontFamily = DotFont, fontSize = 18.sp, color = Sand.Dim)
                    Column(Modifier.weight(1f)) {
                        Text(song.title, fontFamily = TitleFont, fontWeight = FontWeight.Medium, fontSize = 15.sp,
                            color = Sand.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(song.artist, fontSize = 11.sp, color = Sand.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text("${plays}×", fontFamily = DotFont, fontSize = 18.sp, color = Sand.White)
                }
            }
            item { StatsHeading("TOP ARTISTS") }
            items(topArtists) { (artist, ms) ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(artist, Modifier.weight(1f), fontFamily = TitleFont, fontWeight = FontWeight.Medium, fontSize = 15.sp,
                        color = Sand.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(formatListen(ms), fontSize = 12.sp, color = Sand.Dim)
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun StatsHeading(title: String) {
    Text(title, Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 4.dp),
        fontFamily = DotFont, fontSize = 20.sp, color = Sand.White)
}

/** Seven columns of dots, one per day; today in red. */
@Composable
private fun WeekChart(days: List<LocalDate>, dayMs: List<Long>) {
    val max = (dayMs.maxOrNull() ?: 0L).coerceAtLeast(1L)
    val rows = 10
    Column {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(110.dp)
                .semantics {
                    contentDescription = "Listening time over the last 7 days: " +
                        days.zip(dayMs).joinToString { (d, ms) -> "${d.dayOfWeek}: ${formatListen(ms)}" }
                }
        ) {
            val colW = size.width / days.size
            val d = minOf(colW / 4.5f, size.height / (rows + 0.5f))
            dayMs.forEachIndexed { col, ms ->
                val filled = if (ms == 0L) 0 else ((ms.toFloat() / max) * rows).toInt().coerceIn(1, rows)
                val cx = colW * col + colW / 2
                val lit = if (col == days.lastIndex) Sand.Red else Sand.White
                for (row in 0 until rows) {
                    val y = size.height - d / 2 - row * d
                    for (k in -1..1) {
                        drawCircle(if (row < filled) lit else Sand.Line, d * 0.32f, Offset(cx + k * d, y))
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            days.forEachIndexed { i, day ->
                Text(
                    day.dayOfWeek.getDisplayName(java.time.format.TextStyle.NARROW, Locale.getDefault()),
                    Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    fontSize = 11.sp,
                    color = if (i == days.lastIndex) Sand.White else Sand.Dim,
                )
            }
        }
    }
}

private fun formatListen(ms: Long): String {
    val h = ms / 3_600_000
    val m = (ms / 60_000) % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
