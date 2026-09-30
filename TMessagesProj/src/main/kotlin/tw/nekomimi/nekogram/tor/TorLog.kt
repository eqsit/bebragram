package tw.nekomimi.nekogram.tor

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** In-memory ring buffer of Tor diagnostics, so the user can copy what happened from the UI. */
object TorLog {
    private const val LIMIT = 400
    private val lines = ArrayDeque<String>()
    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun add(message: String) {
        // INFO is retained in release builds (DEBUG/VERBOSE are removed by R8).
        android.util.Log.i("BebragramTor", message)
        lines.addLast("${stamp.format(Date())} $message")
        while (lines.size > LIMIT) lines.removeFirst()
    }

    @Synchronized
    fun snapshot(): String = if (lines.isEmpty()) "(no Tor activity yet)" else lines.joinToString("\n")

    @Synchronized
    fun clear() = lines.clear()
}
