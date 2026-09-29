package org.torproject.jni
import android.content.Context
import android.content.Intent
import android.os.IBinder
import java.io.File
open class TorService {
    companion object {
        const val ACTION_STATUS = "status"
        const val ACTION_ERROR = "error"
        const val EXTRA_STATUS = "status-value"
        fun getTorrc(context: Context) = File(context.filesDir, "torrc")
    }
    val torControlConnection: Any? = null
    val socksPort = 9050
    fun getInfo(key: String): String? = null
    open fun onBind(intent: Intent?): IBinder? = null
    open fun onDestroy() {}
}
