package app.mosaic.workspace

import android.content.Context
import android.graphics.Color

class SettingsStore private constructor(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("mosaic_settings", Context.MODE_PRIVATE)

    var setupDone: Boolean get() = sp.getBoolean("setup_done", false); set(v) = sp.edit().putBoolean("setup_done", v).apply()
    var warningShown: Boolean get() = sp.getBoolean("multi_warning_shown", false); set(v) = sp.edit().putBoolean("multi_warning_shown", v).apply()
    var themeIndex: Int get() = sp.getInt("theme_index", 0).coerceIn(0, 4); set(v) = sp.edit().putInt("theme_index", v.coerceIn(0, 4)).apply()
    var windowOpacity: Int get() = sp.getInt("window_opacity", 94).coerceIn(55, 100); set(v) = sp.edit().putInt("window_opacity", v.coerceIn(55, 100)).apply()
    var defaultWidth: Int get() = sp.getInt("window_width", 310).coerceIn(230, 620); set(v) = sp.edit().putInt("window_width", v.coerceIn(230, 620)).apply()
    var defaultHeight: Int get() = sp.getInt("window_height", 360).coerceIn(180, 760); set(v) = sp.edit().putInt("window_height", v.coerceIn(180, 760)).apply()
    var compactTiles: Boolean get() = sp.getBoolean("compact_tiles", false); set(v) = sp.edit().putBoolean("compact_tiles", v).apply()
    var reduceMotion: Boolean get() = sp.getBoolean("reduce_motion", false); set(v) = sp.edit().putBoolean("reduce_motion", v).apply()
    var barCount: Int get() = sp.getInt("bar_count", 36).coerceIn(12, 72); set(v) = sp.edit().putInt("bar_count", v.coerceIn(12, 72)).apply()
    var sensitivity: Float get() = sp.getFloat("sensitivity", 1.55f).coerceIn(.5f, 3.5f); set(v) = sp.edit().putFloat("sensitivity", v.coerceIn(.5f, 3.5f)).apply()
    var smoothing: Float get() = sp.getFloat("smoothing", .62f).coerceIn(.1f, .95f); set(v) = sp.edit().putFloat("smoothing", v.coerceIn(.1f, .95f)).apply()
    var barHeight: Int get() = sp.getInt("bar_height", 86).coerceIn(30, 100); set(v) = sp.edit().putInt("bar_height", v.coerceIn(30, 100)).apply()
    var barGap: Int get() = sp.getInt("bar_gap", 3).coerceIn(1, 9); set(v) = sp.edit().putInt("bar_gap", v.coerceIn(1, 9)).apply()
    var glow: Boolean get() = sp.getBoolean("glow", true); set(v) = sp.edit().putBoolean("glow", v).apply()
    var peaks: Boolean get() = sp.getBoolean("peaks", true); set(v) = sp.edit().putBoolean("peaks", v).apply()
    var apiEndpoint: String get() = sp.getString("ai_endpoint", "") ?: ""; set(v) = sp.edit().putString("ai_endpoint", v.trim()).apply()
    var apiToken: String get() = sp.getString("ai_token", "") ?: ""; set(v) = sp.edit().putString("ai_token", v.trim()).apply()
    var savedNotes: String get() = sp.getString("saved_notes", "") ?: ""; set(v) = sp.edit().putString("saved_notes", v).apply()

    fun accent(): Int = when (themeIndex) {
        1 -> Color.rgb(137, 165, 255)
        2 -> Color.rgb(207, 149, 255)
        3 -> Color.rgb(246, 174, 111)
        4 -> Color.rgb(239, 139, 159)
        else -> Color.rgb(122, 216, 200)
    }

    companion object { fun from(context: Context) = SettingsStore(context) }
}
