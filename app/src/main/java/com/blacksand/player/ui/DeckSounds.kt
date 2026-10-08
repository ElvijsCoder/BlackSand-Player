package com.blacksand.player.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.blacksand.player.R
import com.blacksand.player.data.Settings

/**
 * Mechanical deck sounds: a click for REW/FF, a heavier latch for PLAY, and a looping motor
 * whirr while winding. Played as UI sounds, so they mix over the music without interrupting it.
 */
object DeckSounds {
    private var pool: SoundPool? = null
    private var click = 0
    private var latch = 0
    private var wind = 0
    private var windStream = 0
    private var appContext: Context? = null

    fun init(context: Context) {
        if (pool != null) return
        appContext = context.applicationContext
        pool = SoundPool.Builder()
            .setMaxStreams(3)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
            .also {
                click = it.load(context, R.raw.key_click, 1)
                latch = it.load(context, R.raw.key_latch, 1)
                wind = it.load(context, R.raw.tape_wind, 1)
            }
    }

    private val enabled: Boolean
        get() = appContext?.let { Settings.prefs(it).getBoolean(Settings.SOUND_FX, true) } ?: false

    fun click() { if (enabled) pool?.play(click, 0.7f, 0.7f, 1, 0, 1f) }
    fun latch() { if (enabled) pool?.play(latch, 0.8f, 0.8f, 1, 0, 1f) }

    /** Winding: forward runs the motor slightly faster than rewind. */
    fun startWind(forward: Boolean) {
        if (!enabled || windStream != 0) return
        windStream = pool?.play(wind, 0.6f, 0.6f, 2, -1, if (forward) 1.15f else 1f) ?: 0
    }

    fun stopWind() {
        if (windStream != 0) pool?.stop(windStream)
        windStream = 0
    }
}
