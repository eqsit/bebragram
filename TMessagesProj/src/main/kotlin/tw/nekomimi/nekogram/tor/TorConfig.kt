package tw.nekomimi.nekogram.tor

import android.content.Context
import android.content.SharedPreferences
import org.telegram.messenger.ApplicationLoader

/** Bebragram Tor preferences (separate store so the port stays self-contained). */
object TorConfig {
    private lateinit var prefs: SharedPreferences

    private fun store(): SharedPreferences {
        if (!::prefs.isInitialized) {
            prefs = ApplicationLoader.applicationContext.getSharedPreferences("bebragram", Context.MODE_PRIVATE)
        }
        return prefs
    }

    var enabled: Boolean
        get() = store().getBoolean("tor_enabled", false)
        set(value) {
            store().edit().putBoolean("tor_enabled", value).apply()
        }

    var autostart: Boolean
        get() = store().getBoolean("tor_autostart", false)
        set(value) {
            store().edit().putBoolean("tor_autostart", value).apply()
        }

    var mode: String
        get() = store().getString("tor_mode", "webtunnel") ?: "webtunnel"
        set(value) {
            store().edit().putString("tor_mode", value).apply()
        }

    var bridges: String
        get() = store().getString("tor_bridges", "") ?: ""
        set(value) {
            store().edit().putString("tor_bridges", value).apply()
        }

    /** Auto-refresh interval in hours; 0 = off. */
    var bridgeAutoRefreshHours: Int
        get() = store().getInt("tor_bridge_refresh_hours", 0)
        set(value) {
            store().edit().putInt("tor_bridge_refresh_hours", value).apply()
        }

    /** True while the stored bridge lines were written by us (button / auto-refresh), not pasted by the user. */
    var bridgesAutoManaged: Boolean
        get() = store().getBoolean("tor_bridges_auto", false)
        set(value) {
            store().edit().putBoolean("tor_bridges_auto", value).apply()
        }

    /** When the bridges were last refreshed. */
    var lastBridgeRefresh: Long
        get() = store().getLong("tor_bridges_refreshed_at", 0L)
        set(value) {
            store().edit().putLong("tor_bridges_refreshed_at", value).apply()
        }

    /** Manual bridge lines for [mode]. Each transport keeps its own, so switching modes never loses them. */
    fun bridgesFor(mode: String): String = store().getString("tor_bridges_$mode", "") ?: ""

    fun setBridgesFor(mode: String, value: String) {
        store().edit().putString("tor_bridges_$mode", value).apply()
    }

    /** One-time migration: per-mode bridge slots, and only webtunnel/snowflake are offered in the UI. */
    fun migrateLegacy() {
        val store = store()
        if (store.contains("tor_bridges")) {
            val legacy = store.getString("tor_bridges", "") ?: ""
            if (legacy.isNotBlank() && bridgesFor(mode).isBlank()) {
                setBridgesFor(mode, legacy)
            }
            store.edit().remove("tor_bridges").apply()
        }
        if (mode !in setOf("webtunnel", "snowflake")) {
            mode = "webtunnel"
        }
    }
}
