package android.content
import java.io.File
import android.os.IBinder
import android.net.ConnectivityManager
interface SharedPreferences {
    fun getBoolean(key: String, fallback: Boolean): Boolean
    fun getString(key: String, fallback: String?): String?
    fun getInt(key: String, fallback: Int): Int
    fun getLong(key: String, fallback: Long): Long
    fun contains(key: String): Boolean
    fun edit(): Editor
    interface Editor {
        fun putBoolean(key: String, value: Boolean): Editor
        fun putString(key: String, value: String?): Editor
        fun putInt(key: String, value: Int): Editor
        fun putLong(key: String, value: Long): Editor
        fun remove(key: String): Editor
        fun apply()
    }
}
class MemoryPreferences : SharedPreferences, SharedPreferences.Editor {
    private val values = java.util.concurrent.ConcurrentHashMap<String, Any>()
    override fun getBoolean(key: String, fallback: Boolean) = values[key] as? Boolean ?: fallback
    override fun getString(key: String, fallback: String?) = values[key] as? String ?: fallback
    override fun getInt(key: String, fallback: Int) = values[key] as? Int ?: fallback
    override fun getLong(key: String, fallback: Long) = values[key] as? Long ?: fallback
    override fun contains(key: String) = values.containsKey(key)
    override fun edit() = this
    override fun putBoolean(key: String, value: Boolean) = apply { values[key] = value }
    override fun putString(key: String, value: String?) = apply { if (value != null) values[key] = value else values.remove(key) }
    override fun putInt(key: String, value: Int) = apply { values[key] = value }
    override fun putLong(key: String, value: Long) = apply { values[key] = value }
    override fun remove(key: String) = apply { values.remove(key) }
    override fun apply() {}
}
class Assets { fun open(name: String) = "{}".byteInputStream() }
open class Context {
    companion object { const val MODE_PRIVATE=0; const val BIND_AUTO_CREATE=1; const val RECEIVER_NOT_EXPORTED=4 }
    val applicationContext: Context get() = this
    val filesDir = File(System.getProperty("java.io.tmpdir"), "tor-jvm").apply { mkdirs() }
    val assets = Assets()
    val bindings = mutableListOf<ServiceConnection>()
    val unbindings = mutableListOf<ServiceConnection>()
    var receiver: BroadcastReceiver? = null
    private val stores = mutableMapOf<String, MemoryPreferences>()
    fun getSharedPreferences(name: String, flags: Int): SharedPreferences = stores.getOrPut(name) { MemoryPreferences() }
    fun <T> getSystemService(type: Class<T>): T? = if(type == ConnectivityManager::class.java) type.cast(ConnectivityManager()) else null
    fun registerReceiver(receiver: BroadcastReceiver, filter: IntentFilter, flags: Int = 0) { this.receiver = receiver }
    fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean { bindings.add(connection); return true }
    fun unbindService(connection: ServiceConnection) { check(connection in bindings); check(connection !in unbindings); unbindings.add(connection) }
}
class ComponentName
class Intent {
    companion object { const val EXTRA_TEXT = "text" }
    var action: String? = null
    private val extras = mutableMapOf<String, String>()
    constructor(context: Context, type: Class<*>)
    constructor(action: String) { this.action = action }
    fun putExtra(key: String, value: String) = apply { extras[key] = value }
    fun getStringExtra(key: String): String? = extras[key]
}
class IntentFilter(action: String) { fun addAction(action: String) {} }
abstract class BroadcastReceiver { abstract fun onReceive(context: Context?, intent: Intent?) }
interface ServiceConnection {
    fun onServiceConnected(name: ComponentName?, binder: IBinder?)
    fun onServiceDisconnected(name: ComponentName?)
}
