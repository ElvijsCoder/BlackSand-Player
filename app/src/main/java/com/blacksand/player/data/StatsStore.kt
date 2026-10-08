package com.blacksand.player.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class SongStat(var plays: Int = 0, var listenedMs: Long = 0, var lastPlayed: Long = 0)

/** Listening history: per-song plays and time, plus total time per day ("2026-10-07" → ms). */
class ListeningStats(
    val songs: MutableMap<Long, SongStat> = mutableMapOf(),
    val days: MutableMap<String, Long> = mutableMapOf(),
)

/** Stats live in one JSON file. The playback service writes it; the UI only reads. */
class StatsStore(context: Context) {
    private val file = File(context.filesDir, "stats.json")

    suspend fun load(): ListeningStats = withContext(Dispatchers.IO) {
        val stats = ListeningStats()
        if (!file.exists()) return@withContext stats
        runCatching {
            val root = JSONObject(file.readText())
            val songs = root.getJSONObject("songs")
            songs.keys().forEach { id ->
                val a = songs.getJSONArray(id)
                stats.songs[id.toLong()] = SongStat(a.getInt(0), a.getLong(1), a.getLong(2))
            }
            val days = root.getJSONObject("days")
            days.keys().forEach { day -> stats.days[day] = days.getLong(day) }
        }
        stats
    }

    fun toJson(stats: ListeningStats): String {
        val songs = JSONObject()
        stats.songs.forEach { (id, s) -> songs.put(id.toString(), JSONArray(listOf(s.plays, s.listenedMs, s.lastPlayed))) }
        val days = JSONObject()
        stats.days.forEach { (day, ms) -> days.put(day, ms) }
        return JSONObject().put("songs", songs).put("days", days).toString()
    }

    /** Blocking write via a temp file; call off the main thread except when the service is shutting down. */
    fun write(json: String) {
        runCatching {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(json)
            tmp.renameTo(file)
        }
    }
}
