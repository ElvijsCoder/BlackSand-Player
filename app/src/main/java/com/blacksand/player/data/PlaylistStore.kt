package com.blacksand.player.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Playlist(val id: Long, val name: String, val songIds: List<Long>)

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
                Playlist(o.getLong("id"), o.getString("name"), (0 until ids.length()).map { ids.getLong(it) })
            }
        }.getOrDefault(emptyList())
    }

    suspend fun save(playlists: List<Playlist>) {
        withContext(Dispatchers.IO) {
            val arr = JSONArray()
            playlists.forEach { p ->
                arr.put(JSONObject().put("id", p.id).put("name", p.name).put("songs", JSONArray(p.songIds)))
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
