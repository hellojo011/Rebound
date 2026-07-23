package dev.rebound.settings

import android.content.Context
import dev.rebound.render.Skin
import dev.rebound.render.Skins

/**
 * Player preferences, read at the start of a run and applied for its duration.
 *
 * Deliberately not live-updating: changing the sync offset or the object size
 * halfway through a song would move the goalposts mid-play, and a score set
 * under two different sets of settings would mean nothing.
 */
object Settings {

    private const val PREFS = "rebound.settings"

    private const val KEY_SYNC = "syncOffsetMs"
    private const val KEY_OBJECT_SCALE = "objectScale"
    private const val KEY_SPEED = "speed"
    private const val KEY_SKIN = "skinId"

    /** Sync range, in milliseconds. Wider than any device should need. */
    const val SYNC_MIN_MS = -200
    const val SYNC_MAX_MS = 200

    const val OBJECT_SCALE_MIN = 0.6f
    const val OBJECT_SCALE_MAX = 1.6f

    /**
     * Scroll speed as a multiplier. Higher means objects cross the field faster
     * and there are fewer of them on screen at once.
     */
    const val SPEED_MIN = 0.5f
    const val SPEED_MAX = 2.0f

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Milliseconds of playback latency the device does not report.
     *
     * Positive values push song time earlier, which is the correction wanted when
     * hits consistently register LATE.
     */
    fun syncOffsetMs(context: Context): Double =
        prefs(context).getInt(KEY_SYNC, 0).toDouble()

    fun setSyncOffsetMs(context: Context, value: Int) {
        prefs(context).edit()
            .putInt(KEY_SYNC, value.coerceIn(SYNC_MIN_MS, SYNC_MAX_MS))
            .apply()
    }

    fun objectScale(context: Context): Float =
        prefs(context).getFloat(KEY_OBJECT_SCALE, 1f)
            .coerceIn(OBJECT_SCALE_MIN, OBJECT_SCALE_MAX)

    fun setObjectScale(context: Context, value: Float) {
        prefs(context).edit()
            .putFloat(KEY_OBJECT_SCALE, value.coerceIn(OBJECT_SCALE_MIN, OBJECT_SCALE_MAX))
            .apply()
    }

    fun speed(context: Context): Float =
        prefs(context).getFloat(KEY_SPEED, 1f).coerceIn(SPEED_MIN, SPEED_MAX)

    fun setSpeed(context: Context, value: Float) {
        prefs(context).edit()
            .putFloat(KEY_SPEED, value.coerceIn(SPEED_MIN, SPEED_MAX))
            .apply()
    }

    fun skin(context: Context): Skin = Skins.byId(prefs(context).getString(KEY_SKIN, null))

    fun setSkin(context: Context, skin: Skin) {
        prefs(context).edit().putString(KEY_SKIN, skin.id).apply()
    }

    fun reset(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
