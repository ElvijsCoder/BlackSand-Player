package com.blacksand.player.data

import android.content.Context

/**
 * App settings, kept in one SharedPreferences file shared by the UI and the playback service.
 * The service listens for changes and applies sound settings live.
 */
object Settings {
    const val PREFS = "settings"

    const val EQ_ON = "eq_on"
    const val EQ_LEVELS = "eq_levels" // band levels in millibels, comma-separated
    const val EQ_BANDS = "eq_bands" // band centre frequencies in Hz, written by the service
    const val EQ_MIN = "eq_min" // band level range in millibels, written by the service
    const val EQ_MAX = "eq_max"
    const val BASS = "bass" // 0..1000
    const val NORMALIZE = "normalize" // use ReplayGain tags
    const val FADE_SECONDS = "fade_seconds" // fade between songs: 0 = off
    const val TAPE_MODE = "tape_mode" // 0 = off, 1 = light, 2 = worn
    const val SOUND_FX = "sound_fx" // deck key and winding sounds, on by default
    const val FLIP_PAUSE = "flip_pause" // face down pauses, face up resumes
    const val DOT_ART = "dot_art" // dot-matrix covers on the cassette label, on by default
    const val SKIP_SHORT = "skip_short" // hide clips under 30 s
    const val EXCLUDED = "excluded_folders"

    fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
