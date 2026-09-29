package org.telegram.messenger
import android.content.Context
import java.util.concurrent.ConcurrentLinkedQueue
object ApplicationLoader { lateinit var applicationContext: Context }
object AndroidUtilities {
    val uiThread = Thread.currentThread()
    private val queue = ConcurrentLinkedQueue<Runnable>()
    private val delayed = mutableListOf<Pair<Long, Runnable>>()
    @Volatile private var clock = 0L
    fun elapsedRealtime() = clock
    fun runOnUIThread(task: Runnable) { queue.add(task) }
    @Synchronized fun runOnUIThread(task: Runnable, delay: Long) { delayed.add(clock+delay to task) }
    @Synchronized fun cancelRunOnUIThread(task: Runnable) { delayed.removeAll { it.second === task } }
    fun drain() { check(Thread.currentThread() === uiThread); while(true) (queue.poll() ?: break).run() }
    @Synchronized fun advance(ms: Long) {
        clock += ms
        val due = delayed.filter { it.first <= clock }
        delayed.removeAll(due.toSet()); due.forEach { queue.add(it.second) }
    }
}
object MessagesController { fun getGlobalMainSettings() = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", 0) }
class NotificationCenter {
    companion object { const val proxySettingsChanged = 1; fun getGlobalInstance() = NotificationCenter() }
    fun postNotificationName(event: Int) {}
}
object SharedConfig {
    data class ProxyInfo(val address: String, val port: Int, val username: String, val password: String, val secret: String)
    var currentProxy: ProxyInfo? = null
    val proxyList = mutableListOf<ProxyInfo>()
    var proxyRotationEnabled = false
    fun loadProxyList() {}
    fun isProxyEnabled() = MessagesController.getGlobalMainSettings().getBoolean("proxy_enabled", false)
    fun addProxy(proxy: ProxyInfo) = proxy.also { proxyList.add(it) }
    fun deleteProxy(proxy: ProxyInfo) { proxyList.remove(proxy) }
    fun saveConfig() {}
}
