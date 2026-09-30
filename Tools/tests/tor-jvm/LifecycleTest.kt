import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import android.net.ConnectivityManager
import android.net.Network
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.SharedConfig
import org.torproject.jni.TorService
import tw.nekomimi.nekogram.tor.TorConfig
import tw.nekomimi.nekogram.tor.TorProxyHelper
import tw.nekomimi.nekogram.utils.ProxyUtil
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLStreamHandler

@Volatile private var fetchGate: java.util.concurrent.CountDownLatch? = null
private val sourceRequests = java.util.concurrent.atomic.AtomicInteger()

private fun mockBridgeRequests() {
    URL.setURLStreamHandlerFactory { protocol ->
        if (protocol != "https") null else object : URLStreamHandler() {
            override fun openConnection(url: URL) = object : HttpURLConnection(url) {
                override fun connect() {}
                override fun disconnect() {}
                override fun usingProxy() = false
                override fun getResponseCode(): Int {
                    if (url.host == "cdn.jsdelivr.net" || url.host == "raw.githubusercontent.com") {
                        sourceRequests.incrementAndGet()
                        fetchGate?.await()
                    }
                    return 200
                }
                override fun getInputStream() =
                    "webtunnel 192.0.2.9:443 url=https://bridge.example.test/path".byteInputStream()
            }
        }
    }
}

private fun refreshWithoutRestart(app: Context, expectedStatus: String) {
    val bindings = app.bindings.size
    val unbindings = app.unbindings.size
    val proxy = SharedConfig.currentProxy
    TorConfig.mode = "webtunnel"
    var result: Int? = null
    TorProxyHelper.refreshBridges { result = it }
    awaitCondition("bridge refresh while $expectedStatus") { result != null }
    check(result == 1 && TorConfig.bridgesFor("webtunnel").contains("bridge.example.test"))
    check(TorProxyHelper.status == expectedStatus && TorConfig.enabled) {
        "Bridge download interrupted $expectedStatus"
    }
    check(app.bindings.size == bindings && app.unbindings.size == unbindings)
    check(SharedConfig.currentProxy === proxy) { "Bridge download replaced the active Telegram proxy" }
    TorConfig.mode = "direct"
}

class TestBinder(private val broken: Boolean = false, private val bootstrapProgress: Int = 100) : IBinder {
    override var isBinderAlive = true
    private val recipients = mutableListOf<IBinder.DeathRecipient>()
    override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) { recipients.add(recipient) }
    override fun transact(code: Int, request: Parcel, response: Parcel, flags: Int): Boolean {
        check(Thread.currentThread() !== AndroidUtilities.uiThread) { "Binder IPC on UI thread" }
        if (!isBinderAlive || broken) throw RemoteException("test control failure")
        response.writeNoException()
        when (code) {
            1 -> response.writeInt(1)
            2 -> {
                request.readString()
                response.writeString(if(request.readString() == "status/bootstrap-phase") "PROGRESS=$bootstrapProgress" else if (bootstrapProgress == 100) "1" else "0")
            }
            3 -> response.writeInt(9050)
        }
        return true
    }
    fun die() { isBinderAlive = false; recipients.forEach { it.binderDied() } }
}
fun awaitCondition(description: String, condition: () -> Boolean) {
    val deadline = System.nanoTime() + 3_000_000_000L
    while(!condition() && System.nanoTime() < deadline) { AndroidUtilities.drain(); Thread.sleep(5) }
    AndroidUtilities.drain()
    check(condition()) { "$description: ${TorProxyHelper.status}" }
}
fun main() {
    try {
        testBridgeValidationAndBudgets()
        mockBridgeRequests()
        val app = Context()
        ApplicationLoader.applicationContext = app
        check(TorConfig.autostart) { "Autostart must default to true" }
        TorConfig.autostart = false
        check(!TorConfig.autostart) { "Explicit opt-out must survive" }
        TorConfig.setBridgesFor("webtunnel", "webtunnel 192.0.2.1:443 url=https://example.invalid/")
        TorConfig.mode = "snowflake"
        TorProxyHelper.init(app)
        check(TorConfig.mode == "webtunnel") { "Retired Snowflake preference was not migrated" }
        TorConfig.mode = "direct"
        TorProxyHelper.start()
        awaitCondition("initial binding") { app.bindings.size == 1 }
        val oldConnection = app.bindings[0]
        TorProxyHelper.stop()
        TorProxyHelper.start()
        AndroidUtilities.advance(10_000)
        AndroidUtilities.drain()
        check(app.bindings.size == 1 && app.unbindings.isEmpty()) { "Restart raced an unconnected old service" }
        val oldBinder = TestBinder()
        oldConnection.onServiceConnected(null, oldBinder)
        check(app.unbindings.single() === oldConnection)
        check(app.bindings.size == 1)
        oldBinder.die()
        awaitCondition("restart after death") { app.bindings.size == 2 }
        val secondBinder = TestBinder()
        app.bindings[1].onServiceConnected(null, secondBinder)
        awaitCondition("bootstrap without UI IPC") { TorProxyHelper.status == "ON" }
        oldBinder.die()
        oldConnection.onServiceDisconnected(null)
        app.receiver!!.onReceive(app, Intent(TorService.ACTION_ERROR).putExtra(Intent.EXTRA_TEXT, "stale old error"))
        AndroidUtilities.drain()
        check(TorProxyHelper.status == "ON") { "Late old-session event stopped the replacement" }
        println("PASS: restart before connection; Binder IPC off UI; duplicate death and stale errors ignored")

        refreshWithoutRestart(app, "ON")
        println("PASS: bridge refresh saves new bridges without interrupting the active connection")

        TorProxyHelper.stop()
        TorProxyHelper.start()
        TorProxyHelper.stop()
        secondBinder.die()
        awaitCondition("cancel queued restart") { TorProxyHelper.status == "OFF" }
        check(app.bindings.size == 2 && !TorConfig.enabled)
        println("PASS: explicit stop cancels pending restart")

        TorProxyHelper.start()
        awaitCondition("third binding") { app.bindings.size == 3 }
        val thirdBinder = TestBinder()
        app.bindings[2].onServiceConnected(null, thirdBinder)
        awaitCondition("third bootstrap") { TorProxyHelper.status == "ON" }
        thirdBinder.die()
        awaitCondition("unexpected death") { TorProxyHelper.status.startsWith("ERROR:") }
        check(TorConfig.enabled) { "Transient death disabled saved Tor preference" }
        println("PASS: unexpected death restores proxy and preserves enabled preference")

        TorProxyHelper.start()
        awaitCondition("fourth binding") { app.bindings.size == 4 }
        val brokenBinder = TestBinder(broken = true)
        app.bindings[3].onServiceConnected(null, brokenBinder)
        awaitCondition("control exception handled") { TorProxyHelper.status == "STOPPING" }
        brokenBinder.die()
        awaitCondition("error after control failure") { TorProxyHelper.status.startsWith("ERROR:") }
        check(TorConfig.enabled)
        println("PASS: control exception is handled and waits for process death")

        TorProxyHelper.start()
        awaitCondition("fifth binding") { app.bindings.size == 5 }
        val fifthBinder = TestBinder()
        app.bindings[4].onServiceConnected(null, fifthBinder)
        awaitCondition("fifth bootstrap") { TorProxyHelper.status == "ON" }
        ProxyUtil.vpn = true
        TorProxyHelper.onVpnPreferenceChanged()
        check(TorProxyHelper.status == "STOPPING")
        ProxyUtil.vpn = false
        TorProxyHelper.onVpnPreferenceChanged()
        check(app.bindings.size == 5)
        fifthBinder.die()
        awaitCondition("VPN resume after death") { app.bindings.size == 6 }
        println("PASS: VPN pause/resume waits for old process exit")
        TorProxyHelper.stop()
        val lastBinder = TestBinder()
        app.bindings[5].onServiceConnected(null, lastBinder)
        lastBinder.die()
        awaitCondition("final stop") { TorProxyHelper.status == "OFF" }

        TorConfig.mode = "webtunnel"
        val gate = java.util.concurrent.CountDownLatch(1)
        fetchGate = gate
        val requestCount = sourceRequests.get()
        var firstRefresh: Int? = null
        var secondRefresh: Int? = null
        TorProxyHelper.refreshBridges { firstRefresh = it }
        TorProxyHelper.refreshBridges { secondRefresh = it }
        awaitCondition("coalesced source requests") { sourceRequests.get() >= requestCount + 2 }
        gate.countDown()
        fetchGate = null
        awaitCondition("both refresh callbacks") { firstRefresh != null && secondRefresh != null }
        check(firstRefresh == 1 && secondRefresh == 1)
        check(sourceRequests.get() == requestCount + 2) { "Duplicate refresh downloaded the lists twice" }
        println("PASS: repeated refresh shares one fetch and completes both callbacks")

        fetchGate = java.util.concurrent.CountDownLatch(1)
        val cancelCount = sourceRequests.get()
        val originalLines = TorConfig.bridgesFor("webtunnel")
        var cancelledRefresh: Int? = null
        TorProxyHelper.refreshBridges { cancelledRefresh = it }
        awaitCondition("fetch before stop") { sourceRequests.get() >= cancelCount + 2 }
        TorProxyHelper.stop()
        TorConfig.setBridgesFor("webtunnel", "webtunnel 192.0.2.5:443 url=https://manual.example/path")
        awaitCondition("cancelled refresh callback without waiting for network") { cancelledRefresh != null }
        fetchGate!!.countDown()
        fetchGate = null
        check(cancelledRefresh == -1 && TorProxyHelper.status == "OFF")
        check(TorConfig.bridgesFor("webtunnel").contains("manual.example"))
        TorConfig.setBridgesFor("webtunnel", originalLines)
        println("PASS: stopped download cannot overwrite manual bridges or restart Tor")

        val knownBridge = "webtunnel 192.0.2.10:443 url=https://known.example.test/path"
        TorConfig.mode = "webtunnel"
        TorConfig.setBridgesFor("webtunnel", knownBridge)
        TorConfig.setAutoManagedFor("webtunnel", true)
        var bindings = app.bindings.size
        TorProxyHelper.start()
        awaitCondition("known bridge binding") { app.bindings.size == bindings + 1 }
        val knownBinder = TestBinder()
        app.bindings.last().onServiceConnected(null, knownBinder)
        awaitCondition("known bridge connected") { TorProxyHelper.status == "ON" }
        refreshWithoutRestart(app, "ON")
        TorConfig.mode = "webtunnel"
        ConnectivityManager.network = Network()
        ConnectivityManager.callback!!.onAvailable(ConnectivityManager.network)
        AndroidUtilities.advance(3_000)
        AndroidUtilities.drain()
        check(TorProxyHelper.status == "STOPPING")
        knownBinder.die()
        awaitCondition("network restart binding") { app.bindings.size == bindings + 2 }
        check(TorService.getTorrc(app).readText().contains("known.example.test")) {
            "Network change replaced connected bridges with untested downloaded bridges"
        }
        val networkBinder = TestBinder()
        app.bindings.last().onServiceConnected(null, networkBinder)
        awaitCondition("network restart connected") { TorProxyHelper.status == "ON" }
        TorProxyHelper.stop()
        networkBinder.die()
        awaitCondition("network session stopped") { TorProxyHelper.status == "OFF" }
        println("PASS: network change reuses the connected session's bridges")

        TorConfig.setBridgesFor("webtunnel", knownBridge)
        bindings = app.bindings.size
        TorProxyHelper.start()
        awaitCondition("manual retry binding") { app.bindings.size == bindings + 1 }
        val unfinishedConnection = app.bindings.last()
        var refreshed: Int? = null
        TorProxyHelper.refreshBridges { refreshed = it }
        awaitCondition("manual bridge retry queued") { refreshed != null }
        check(refreshed == 1 && TorProxyHelper.status == "STOPPING")
        check(app.bindings.size == bindings + 1) { "Retry raced the previous process" }
        val unfinishedBinder = TestBinder()
        unfinishedConnection.onServiceConnected(null, unfinishedBinder)
        unfinishedBinder.die()
        awaitCondition("manual retry uses fresh bridges") { app.bindings.size == bindings + 2 }
        check(TorService.getTorrc(app).readText().contains("bridge.example.test"))
        val retryBinder = TestBinder()
        app.bindings.last().onServiceConnected(null, retryBinder)
        awaitCondition("manual retry connected") { TorProxyHelper.status == "ON" }
        TorProxyHelper.stop()
        retryBinder.die()
        awaitCondition("manual retry session stopped") { TorProxyHelper.status == "OFF" }
        println("PASS: manual refresh retries STARTING with fresh bridges after old process death")

        TorConfig.setAutoManagedFor("webtunnel", true)
        TorConfig.setBridgesFor("webtunnel", knownBridge)
        bindings = app.bindings.size
        TorProxyHelper.start()
        awaitCondition("stall scenario binding") { app.bindings.size == bindings + 1 }
        val stalledBinder = TestBinder(bootstrapProgress = 25)
        app.bindings.last().onServiceConnected(null, stalledBinder)
        awaitCondition("bootstrap reached 25 percent") { TorProxyHelper.progress == 25 }
        AndroidUtilities.advance(45_001)
        awaitCondition("stalled bootstrap stopped early") { TorProxyHelper.status == "STOPPING" }
        check(TorProxyHelper.lastError == "Tor bootstrap stalled at 25%")
        awaitCondition("stalled bridge replaced") { TorConfig.bridgesFor("webtunnel") != knownBridge }
        check(!TorConfig.bridgesFor("webtunnel").contains("known.example.test"))
        TorProxyHelper.stop()
        stalledBinder.die()
        awaitCondition("stalled session stopped") { TorProxyHelper.status == "OFF" }
        println("PASS: stalled automatic bridges retry after 45 seconds without progress")
        TorConfig.mode = "direct"
        bindings = app.bindings.size
        TorProxyHelper.start()
        awaitCondition("null binding scenario") { app.bindings.size == bindings + 1 }
        app.bindings.last().onNullBinding(null)
        check(TorProxyHelper.status.startsWith("ERROR:")) { "Null binding left Tor waiting forever" }
        TorProxyHelper.stop()
        TorProxyHelper.start()
        awaitCondition("stop before null binding") { app.bindings.size == bindings + 2 }
        TorProxyHelper.stop()
        app.bindings.last().onNullBinding(null)
        check(TorProxyHelper.status == "OFF") { "Null binding left Tor stuck in STOPPING" }
        println("PASS: null service binding releases STARTING and STOPPING")
        println("All Tor lifecycle regression scenarios passed")
        kotlin.system.exitProcess(0)
    } catch(t: Throwable) {
        t.printStackTrace()
        kotlin.system.exitProcess(1)
    }
}
