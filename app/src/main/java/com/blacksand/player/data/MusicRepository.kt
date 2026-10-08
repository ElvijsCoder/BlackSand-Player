package com.blacksand.player.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val track: Int, // disc * 1000 + track, as MediaStore stores it
    val folder: String, // e.g. "Music/Radiohead/OK Computer/"
    val fileName: String,
    val durationMs: Long,
    val uri: Uri,
)

fun Song.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(id.toString())
    .setUri(uri)
    .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .build()
    )
    .build()

class MusicRepository(private val context: Context) {

    /**
     * All music on the phone. With [filtered], hidden folders and (if enabled) clips under 30 s
     * are left out; the settings screen loads unfiltered to list every folder.
     */
    suspend fun loadSongs(filtered: Boolean = true): List<Song> = withContext(Dispatchers.IO) {
        val settings = Settings.prefs(context)
        val skipShort = filtered && settings.getBoolean(Settings.SKIP_SHORT, true)
        val excluded = if (filtered) settings.getStringSet(Settings.EXCLUDED, emptySet()).orEmpty() else emptySet()
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.DISPLAY_NAME,
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= ?"
        val args = arrayOf(if (skipShort) "30000" else "0")
        val sort = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"

        val songs = mutableListOf<Song>()
        context.contentResolver.query(collection, projection, selection, args, sort)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durationCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val albumIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val trackCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val pathCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                songs += Song(
                    id = id,
                    title = c.getString(titleCol) ?: "Unknown title",
                    artist = c.getString(artistCol)
                        ?.takeUnless { it == MediaStore.UNKNOWN_STRING } ?: "Unknown artist",
                    album = c.getString(albumCol) ?: "",
                    albumId = c.getLong(albumIdCol),
                    track = c.getInt(trackCol),
                    folder = c.getString(pathCol) ?: "",
                    fileName = c.getString(nameCol) ?: "",
                    durationMs = c.getLong(durationCol),
                    uri = ContentUris.withAppendedId(collection, id),
                )
            }
        }
        if (excluded.isEmpty()) songs else songs.filterNot { it.folder in excluded }
    }
}
