package com.blacksand.player.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.SizeF
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.edit
import androidx.media3.session.MediaButtonReceiver
import com.blacksand.player.MainActivity
import com.blacksand.player.R
import com.blacksand.player.playback.PlaybackService
import com.blacksand.player.ui.formatTime

/** What the widget shows. Built by the playback service; [art] is already greyscale or dot-matrix. */
data class WidgetState(
    val title: String?,
    val artist: String?,
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val track: Int,
    val count: Int,
    val art: Bitmap? = null,
    val side: String = "A",
)

/**
 * The cassette deck widget. The playback service pushes updates while it runs; when the
 * launcher asks on its own (widget just added, phone rebooted), it draws the last snapshot, paused.
 */
class CassetteWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        push(context, loadSnapshot(context), ids)
    }

    companion object {
        private const val PREFS = "widget"

        fun push(context: Context, state: WidgetState, ids: IntArray? = null) {
            val manager = AppWidgetManager.getInstance(context)
            val targets = ids ?: manager.getAppWidgetIds(ComponentName(context, CassetteWidget::class.java))
            if (targets.isEmpty()) return
            // The launcher picks the layout that fits the widget's current size.
            val views = RemoteViews(
                mapOf(
                    SizeF(110f, 110f) to build(context, R.layout.widget_small, state),
                    SizeF(250f, 110f) to build(context, R.layout.widget_large, state),
                )
            )
            manager.updateAppWidget(targets, views)
        }

        fun saveSnapshot(context: Context, s: WidgetState) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
                putString("title", s.title)
                putString("artist", s.artist)
                putLong("position", s.positionMs)
                putLong("duration", s.durationMs)
                putInt("track", s.track)
                putInt("count", s.count)
            }
        }

        private fun loadSnapshot(context: Context): WidgetState {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return WidgetState(
                title = p.getString("title", null),
                artist = p.getString("artist", null),
                playing = false,
                positionMs = p.getLong("position", 0),
                durationMs = p.getLong("duration", 0),
                track = p.getInt("track", 0),
                count = p.getInt("count", 0),
            )
        }

        private fun build(context: Context, layout: Int, s: WidgetState): RemoteViews {
            val v = RemoteViews(context.packageName, layout)
            val large = layout == R.layout.widget_large
            val progress = if (s.durationMs > 0) (s.positionMs.toFloat() / s.durationMs).coerceIn(0f, 1f) else 0f

            v.setTextViewText(R.id.side, s.side)
            v.setTextViewText(R.id.title, s.title ?: context.getString(R.string.widget_idle_title))
            v.setTextViewText(R.id.artist, s.artist ?: "")
            if (s.art != null) v.setImageViewBitmap(R.id.art, s.art)
            else v.setImageViewResource(R.id.art, android.R.color.transparent)
            v.setImageViewResource(R.id.led, if (s.playing) R.drawable.led_on else R.drawable.led_off)

            // Reels: spinners turn on their own while playing; still hubs when paused.
            val spin = if (s.playing) View.VISIBLE else View.GONE
            val still = if (s.playing) View.GONE else View.VISIBLE
            v.setViewVisibility(R.id.spin_left, spin)
            v.setViewVisibility(R.id.spin_right, spin)
            v.setViewVisibility(R.id.hub_left, still)
            v.setViewVisibility(R.id.hub_right, still)

            // Tape moves from the left reel to the right as the song plays.
            val (min, max) = if (large) 10f to 18f else 9f to 16f
            setSize(v, R.id.pack_left, min + (max - min) * (1 - progress))
            setSize(v, R.id.pack_right, min + (max - min) * progress)

            // The play key stays pressed in while playing.
            v.setInt(R.id.key_play, "setBackgroundResource", if (s.playing) R.drawable.key_down else R.drawable.key_bg)
            v.setOnClickPendingIntent(R.id.key_prev, mediaButton(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS))
            v.setOnClickPendingIntent(
                R.id.key_play,
                mediaButton(context, if (s.playing) KeyEvent.KEYCODE_MEDIA_PAUSE else KeyEvent.KEYCODE_MEDIA_PLAY),
            )
            v.setOnClickPendingIntent(R.id.key_next, mediaButton(context, KeyEvent.KEYCODE_MEDIA_NEXT))
            v.setOnClickPendingIntent(R.id.cassette, openApp(context))

            v.setTextViewText(R.id.track, if (s.count > 0) "%02d/%02d".format(s.track, s.count) else "")
            if (large) {
                v.setTextViewText(R.id.status, if (s.playing) "PLAY" else "PAUSE")
                v.setProgressBar(R.id.progress, 1000, (progress * 1000).toInt(), false)
                // The counter ticks by itself while playing; no updates needed.
                v.setChronometer(R.id.elapsed, SystemClock.elapsedRealtime() - s.positionMs, null, s.playing)
                v.setTextViewText(R.id.total, "/ ${formatTime(s.durationMs)}")
            }
            return v
        }

        private fun setSize(v: RemoteViews, id: Int, dp: Float) {
            v.setViewLayoutWidth(id, dp, TypedValue.COMPLEX_UNIT_DIP)
            v.setViewLayoutHeight(id, dp, TypedValue.COMPLEX_UNIT_DIP)
        }

        /**
         * PLAY goes through Media3's media-button receiver, which can start the app from closed.
         * That receiver ignores every other key by design, so pause/next/previous go straight to the
         * running playback service, the same way the notification's buttons do.
         */
        private fun mediaButton(context: Context, keyCode: Int): PendingIntent {
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val event = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
            return if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY) {
                val intent = Intent(Intent.ACTION_MEDIA_BUTTON)
                    .setClass(context, MediaButtonReceiver::class.java)
                    .putExtra(Intent.EXTRA_KEY_EVENT, event)
                PendingIntent.getBroadcast(context, keyCode, intent, flags)
            } else {
                val intent = Intent(Intent.ACTION_MEDIA_BUTTON)
                    .setClass(context, PlaybackService::class.java)
                    .putExtra(Intent.EXTRA_KEY_EVENT, event)
                PendingIntent.getService(context, keyCode, intent, flags)
            }
        }

        private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
