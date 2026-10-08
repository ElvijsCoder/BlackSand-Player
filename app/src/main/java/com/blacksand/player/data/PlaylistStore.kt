package com.blacksand.player.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A playlist. With [tapeMinutes] set it's a mixtape: songs fill Side A, then Side B, each [tapeMinutes] / 2 long. */
data class Playlist(val id: Long, val name: String, val songIds: List<Long>, val tapeMinutes: Int? = null)

/** Splits songs onto the two sides of a [tapeMinutes] tape, in order. Returns null if they don't fit. */
fun sidesOf(songs: List<Song>, tapeMinutes: Int): Pair<List<Song>, List<Song>>? {
    val sideMs = tapeMinutes * 60_000L / 2
    var used = 0L
    var split = songs.size
    for ((i, s) in songs.withIndex()) {
        if (used + s.durationMs > sideMs) { split = i; break }
        used += s.durationMs
    }
    val a = songs.take(split)
    val b = songs.drop(split)
    return if (b.sumOf { it.durationMs } <= sideMs) a to b else null
}

/** Playlists live in one small JSON file in the app's private storage. */
class PlaylistStore(context: Context) {
    private val file = File(context.filesDir, "playlists.json")

    suspend fun load(): List<Playlist> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyList()
        runCatching {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val ids = o.getJSONArray("songs")
                Playlist(
                    o.getLong("id"), o.getString("name"), (0 until ids.length()).map { ids.getLong(it) },
                    if (o.has("tape")) o.getInt("tape") else null,
                )
            }
        }.getOrDefault(emptyList())
    }

    suspend fun save(playlists: List<Playlist>) {
        withContext(Dispatchers.IO) {
            val arr = JSONArray()
            playlists.forEach { p ->
                val o = JSONObject().put("id", p.id).put("name", p.name).put("songs", JSONArray(p.songIds))
                p.tapeMinutes?.let { o.put("tape", it) }
                arr.put(o)
            }
            // Write to a temp file first so a crash mid-write can't corrupt the playlists.
            val tmp = File(file.path + ".tmp")
            tmp.writeText(arr.toString())
            tmp.renameTo(file)
        }
    }
}

/** The file names listed in an .m3u/.m3u8 playlist; paths differ between devices, names usually don't. */
fun parseM3u(text: String): List<String> = text.lineSequence()
    .map { it.trim() }
    .filter { it.isNotEmpty() && !it.startsWith("#") }
    .map { Uri.decode(it.replace('\\', '/').substringAfterLast('/')) }
    .toList()
