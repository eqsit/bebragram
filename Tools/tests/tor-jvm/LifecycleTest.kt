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
@Volatile private var sourceBridgeLines = "webtunnel 192.0.2.9:443 url=https://bridge.example.test/path"

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
                override fun getInputStream() = sourceBridgeLines.byteInputStream()
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

class TestBinder(private val broken: Boolean = false, private val bootstrapProgress: Int = 100,
    private val circuitPurpose: String = "GENERAL") : IBinder {
    @Volatile var liveCircuit = true
    @Volatile var dormant = false
    @Volatile var readBytes = 0L
    val trafficQueries = java.util.concurrent.atomic.AtomicInteger()
    val circuitQueries = java.util.concurrent.atomic.AtomicInteger()
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
                val key = request.readString()
                response.writeString(when (key) {
                    "status/bootstrap-phase" -> "PROGRESS=$bootstrapProgress"
                    "dormant" -> if (dormant) "1" else "0"
                    "traffic/read" -> { trafficQueries.incrementAndGet(); readBytes.toString() }
                    "circuit-status" -> {
                        circuitQueries.incrementAndGet()
                        if (liveCircuit) "7 BUILT $" + "guard,$" + "middle,$" + "exit PURPOSE=$circuitPurpose" else ""
                    }
                    else -> if (bootstrapProgress == 100) "1" else "0"
                })
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
        val secondBinder = TestBinder(circuitPurpose = "CONFLUX_LINKED")
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

        val nativeChecks = org.telegram.tgnet.ConnectionsManager.pingCallbacks
        TorProxyHelper.checkTelegramConnection()
        TorProxyHelper.checkTelegramConnection()
        check(nativeChecks.size == 1 && TorProxyHelper.checkingTelegram)
        nativeChecks.last()(321)
        awaitCondition("Telegram round trip result") { TorProxyHelper.telegramPingMs == 321L }
        TorProxyHelper.checkTelegramConnection()
        AndroidUtilities.advance(30_000)
        AndroidUtilities.drain()
        check(!TorProxyHelper.checkingTelegram && TorProxyHelper.telegramPingMs == -1L)
        nativeChecks.last()(999)
        AndroidUtilities.drain()
        check(TorProxyHelper.telegramPingMs == -1L) { "Late callback overwrote timed-out check" }
        TorProxyHelper.checkTelegramConnection()
        val stalePing = nativeChecks.last()

        TorProxyHelper.stop()
        TorProxyHelper.start()
        TorProxyHelper.stop()
        secondBinder.die()
        awaitCondition("cancel queued restart") { TorProxyHelper.status == "OFF" }
        stalePing(111)
        AndroidUtilities.drain()
        check(!TorProxyHelper.checkingTelegram && TorProxyHelper.telegramPingMs == -1L)
        check(app.bindings.size == 2 && !TorConfig.enabled)
        println("PASS: explicit stop cancels pending restart")
        println("PASS: Telegram checks are on request, coalesce clicks, and ignore late callbacks after timeout/stop")

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
        check(sourceRequests.get() - requestCount in 2..4) { "Duplicate refresh downloaded the lists twice" }
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
        ConnectivityManager.callback!!.onAvailable(ConnectivityManager.network!!)
        ConnectivityManager.callback!!.onCapabilitiesChanged(ConnectivityManager.network!!, android.net.NetworkCapabilities())
        AndroidUtilities.drain()
        AndroidUtilities.advance(249)
        AndroidUtilities.drain()
        check(TorProxyHelper.status == "ON") { "Network callbacks were not coalesced" }
        AndroidUtilities.advance(1)
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
        val reserveBridge = "webtunnel 192.0.2.15:443 url=https://reserve.example.test/path"
        val secondReserve = "webtunnel 192.0.2.16:443 url=https://second-reserve.example.test/path"
        val slowReserve = "webtunnel 192.0.2.17:443 url=https://slow-reserve.example.test/path"
        app.getSharedPreferences("bebragram", 0).edit()
            .putString("inu_tor_bridges_webtunnel_standby", "${System.currentTimeMillis()}|$reserveBridge\n$secondReserve\n$slowReserve").apply()
        IPtProxy.Controller.blockedHost = "known.example.test"
        IPtProxy.Controller.slowHost = "slow-reserve.example.test"
        val handoverStarted = System.nanoTime()
        val requestsBeforeNetwork = sourceRequests.get()
        ConnectivityManager.network = Network()
        ConnectivityManager.callback!!.onAvailable(ConnectivityManager.network!!)
        AndroidUtilities.drain()
        AndroidUtilities.advance(250)
        AndroidUtilities.drain()
        check(TorProxyHelper.status == "STOPPING")
        networkBinder.die()
        awaitCondition("blocked endpoint replaced on new network") { app.bindings.size == bindings + 3 }
        check(System.nanoTime() - handoverStarted < 1_000_000_000L) { "Network handover waited for a slow third bridge" }
        val newTorrc = TorService.getTorrc(app).readText()
        check(!newTorrc.contains("known.example.test") && newTorrc.contains("reserve.example.test"))
        check(sourceRequests.get() == requestsBeforeNetwork) { "Network change fetched lists despite a reachable standby" }
        IPtProxy.Controller.blockedHost = null
        IPtProxy.Controller.slowHost = null
        val reserveBinder = TestBinder(circuitPurpose = "CONFLUX_LINKED")
        app.bindings.last().onServiceConnected(null, reserveBinder)
        awaitCondition("linked Conflux network circuit connected") { TorProxyHelper.status == "ON" }
        TorProxyHelper.stop()
        reserveBinder.die()
        awaitCondition("network session stopped") { TorProxyHelper.status == "OFF" }
        println("PASS: network change checks current endpoints and replaces blocked ones with cached standby; Conflux is ready")

        // VPN recovery must keep the actual session's bridges even after background refresh.
        TorConfig.setBridgesFor("webtunnel", knownBridge)
        bindings = app.bindings.size
        TorProxyHelper.start()
        awaitCondition("VPN bridge binding") { app.bindings.size == bindings + 1 }
        val vpnBinder = TestBinder()
        app.bindings.last().onServiceConnected(null, vpnBinder)
        awaitCondition("VPN bridge connected") { TorProxyHelper.status == "ON" }
        refreshWithoutRestart(app, "ON")
        TorConfig.mode = "webtunnel"
        ProxyUtil.vpn = true
        TorProxyHelper.onVpnPreferenceChanged()
        vpnBinder.die()
        awaitCondition("VPN paused") { TorProxyHelper.status == "WAITING_FOR_VPN" }
        ProxyUtil.vpn = false
        ConnectivityManager.network = Network()
        ConnectivityManager.callback!!.onAvailable(ConnectivityManager.network!!)
        AndroidUtilities.drain()
        AndroidUtilities.advance(250)
        awaitCondition("VPN callback resumes in 250ms") { app.bindings.size == bindings + 2 }
        check(TorService.getTorrc(app).readText().contains("known.example.test"))
        val vpnResumeBinder = TestBinder()
        app.bindings.last().onServiceConnected(null, vpnResumeBinder)
        awaitCondition("VPN resumed") { TorProxyHelper.status == "ON" }
        println("PASS: VPN resume in 250ms reuses connected bridges instead of refreshed candidates")

        val sourcesBeforeAuto = sourceRequests.get()
        val proxyBeforeAuto = SharedConfig.currentProxy
        TorConfig.bridgeAutoRefreshHours = 1
        TorConfig.setLastBridgeRefreshFor("webtunnel", 0)
        AndroidUtilities.advance(60_000)
        awaitCondition("auto refresh within one minute") { TorConfig.lastBridgeRefreshFor("webtunnel") > 0 }
        check(sourceRequests.get() - sourcesBeforeAuto in 2..4)
        check(TorProxyHelper.status == "ON" && SharedConfig.currentProxy === proxyBeforeAuto)
        check(app.bindings.size == bindings + 2)
        TorConfig.bridgeAutoRefreshHours = 0
        println("PASS: due auto refresh runs within one minute without restarting the live circuit")

        val queriesBeforeHealthyError = vpnResumeBinder.circuitQueries.get()
        IPtProxy.Controller.listener!!.stopped("webtunnel", Exception("unrecognized reply"))
        AndroidUtilities.drain()
        AndroidUtilities.advance(250)
        awaitCondition("verify circuit after single bridge error") { vpnResumeBinder.circuitQueries.get() > queriesBeforeHealthyError }
        check(TorProxyHelper.status == "ON") { "One failed bridge restarted a healthy circuit" }
        vpnResumeBinder.liveCircuit = false
        vpnResumeBinder.dormant = true
        val queriesBeforeDormant = vpnResumeBinder.circuitQueries.get()
        IPtProxy.Controller.listener!!.stopped("webtunnel", null)
        AndroidUtilities.drain()
        AndroidUtilities.advance(250)
        awaitCondition("dormant circuit check") { vpnResumeBinder.circuitQueries.get() > queriesBeforeDormant }
        check(TorProxyHelper.status == "ON")
        vpnResumeBinder.dormant = false
        println("PASS: deliberate Tor dormancy does not cause a false bridge restart")
        val queryCount = vpnResumeBinder.circuitQueries.get()
        val sourcesBeforeRecovery = sourceRequests.get()
        val standby = "webtunnel 192.0.2.11:443 url=https://standby.example.test/path"
        app.getSharedPreferences("bebragram", 0).edit()
            .putString("inu_tor_bridges_webtunnel_standby", "${System.currentTimeMillis()}|$standby").apply()
        IPtProxy.Controller.listener!!.stopped("webtunnel", Exception("unrecognized reply"))
        AndroidUtilities.drain()
        AndroidUtilities.advance(250)
        awaitCondition("first missing live circuit check") { vpnResumeBinder.circuitQueries.get() > queryCount }
        Thread.sleep(20)
        AndroidUtilities.drain()
        check(TorProxyHelper.status == "ON")
        AndroidUtilities.advance(5_000)
        awaitCondition("Tor can rebuild circuits without a process restart") { vpnResumeBinder.circuitQueries.get() > queryCount + 1 }
        Thread.sleep(20)
        AndroidUtilities.drain()
        check(TorProxyHelper.status == "ON") { "Brief circuit rebuild restarted Tor" }
        vpnResumeBinder.liveCircuit = true
        AndroidUtilities.advance(5_000)
        awaitCondition("live circuit recovered") { vpnResumeBinder.circuitQueries.get() > queryCount + 2 }
        Thread.sleep(20)
        AndroidUtilities.drain()
        check(TorProxyHelper.status == "ON")
        vpnResumeBinder.liveCircuit = false
        AndroidUtilities.advance(15_000)
        awaitCondition("second loss starts grace period") { vpnResumeBinder.circuitQueries.get() > queryCount + 3 }
        Thread.sleep(20)
        AndroidUtilities.drain()
        AndroidUtilities.advance(30_000)
        awaitCondition("lost circuit despite old success flag") { TorProxyHelper.status == "STOPPING" }
        awaitCondition("standby selected without list download") { TorConfig.bridgesFor("webtunnel").contains("standby.example.test") }
        check(sourceRequests.get() == sourcesBeforeRecovery)
        vpnResumeBinder.die()
        awaitCondition("standby restart") { app.bindings.size == bindings + 3 }
        val standbyBinder = TestBinder()
        app.bindings.last().onServiceConnected(null, standbyBinder)
        awaitCondition("standby connected") { TorProxyHelper.status == "ON" }
        println("PASS: healthy circuit survives bridge errors; lost circuit retries standby without downloading lists")

        val oldNetwork = ConnectivityManager.network!!
        ConnectivityManager.network = null
        ConnectivityManager.callback!!.onLost(oldNetwork)
        AndroidUtilities.drain()
        AndroidUtilities.advance(250)
        AndroidUtilities.drain()
        check(TorProxyHelper.status == "STOPPING")
        standbyBinder.die()
        awaitCondition("offline pause") { TorProxyHelper.status == "WAITING_FOR_NETWORK" }
        ConnectivityManager.network = Network()
        ConnectivityManager.callback!!.onAvailable(ConnectivityManager.network!!)
        AndroidUtilities.drain()
        AndroidUtilities.advance(250)
        awaitCondition("online restart in 250ms") { app.bindings.size == bindings + 4 }
        check(TorService.getTorrc(app).readText().contains("standby.example.test"))
        val onlineBinder = TestBinder()
        app.bindings.last().onServiceConnected(null, onlineBinder)
        awaitCondition("online connected") { TorProxyHelper.status == "ON" }
        TorProxyHelper.restart()
        TorProxyHelper.restart()
        check(app.bindings.size == bindings + 4 && TorProxyHelper.status == "STOPPING")
        onlineBinder.die()
        awaitCondition("one explicit restart after death") { app.bindings.size == bindings + 5 }
        val restartBinder = TestBinder()
        app.bindings.last().onServiceConnected(null, restartBinder)
        awaitCondition("explicit restart connected") { TorProxyHelper.status == "ON" }
        TorProxyHelper.stop()
        restartBinder.die()
        awaitCondition("recovery tests stopped") { TorProxyHelper.status == "OFF" }
        println("PASS: offline/online resumes in 250ms; repeated Restart clicks queue one safe restart")

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
        AndroidUtilities.advance(20_001)
        Thread.sleep(250)
        AndroidUtilities.drain()
        check(TorProxyHelper.status == "STARTING") { "Consensus download was aborted after 20 seconds" }
        stalledBinder.readBytes = 32_768
        val trafficPolls = stalledBinder.trafficQueries.get()
        AndroidUtilities.advance(30_000)
        awaitCondition("download activity sampled") { stalledBinder.trafficQueries.get() > trafficPolls }
        AndroidUtilities.advance(59_999)
        Thread.sleep(250)
        AndroidUtilities.drain()
        check(TorProxyHelper.status == "STARTING") { "Productive consensus download was aborted" }
        AndroidUtilities.advance(2)
        awaitCondition("stalled bootstrap stopped early") { TorProxyHelper.status == "STOPPING" }
        check(TorProxyHelper.lastError == "Tor bootstrap stalled at 25%")
        awaitCondition("stalled bridge replaced") { TorConfig.bridgesFor("webtunnel") != knownBridge }
        check(!TorConfig.bridgesFor("webtunnel").contains("known.example.test"))
        TorProxyHelper.stop()
        stalledBinder.die()
        awaitCondition("stalled session stopped") { TorProxyHelper.status == "OFF" }
        println("PASS: consensus download survives 20-second stall and incoming bytes reset its 60-second budget")

        TorConfig.setBridgesFor("webtunnel", knownBridge)
        bindings = app.bindings.size
        TorProxyHelper.start()
        awaitCondition("late bootstrap binding") { app.bindings.size == bindings + 1 }
        val lateBinder = TestBinder(bootstrapProgress = 95)
        app.bindings.last().onServiceConnected(null, lateBinder)
        awaitCondition("late bootstrap reached 95 percent") { TorProxyHelper.progress == 95 }
        AndroidUtilities.advance(44_999)
        AndroidUtilities.drain()
        check(TorProxyHelper.status == "STARTING")
        AndroidUtilities.advance(1)
        awaitCondition("late bootstrap stall is bounded") { TorProxyHelper.status == "STOPPING" }
        check(TorProxyHelper.lastError == "Tor bootstrap stalled at 95%")
        TorProxyHelper.stop()
        lateBinder.die()
        awaitCondition("late stall stopped") { TorProxyHelper.status == "OFF" }
        println("PASS: late bootstrap gets 45 seconds to progress instead of waiting the full 120 seconds")

        // Exhaust the small mocked pool: recovery must resume later rather than
        // leaving the enabled toggle permanently stranded in ERROR.
        TorConfig.setBridgesFor("webtunnel", knownBridge)
        bindings = app.bindings.size
        TorProxyHelper.start()
        awaitCondition("recovery cooldown binding") { app.bindings.size == bindings + 1 }
        val recoveryBinder = TestBinder(broken = true)
        app.bindings.last().onServiceConnected(null, recoveryBinder)
        awaitCondition("recovery first fetch") { TorConfig.bridgesFor("webtunnel") != knownBridge }
        recoveryBinder.die()
        awaitCondition("recovery replacement binding") { app.bindings.size == bindings + 2 }
        val exhaustedBinder = TestBinder(broken = true)
        app.bindings.last().onServiceConnected(null, exhaustedBinder)
        awaitCondition("exhausted pool retry queued") {
            tw.nekomimi.nekogram.tor.TorLog.snapshot().contains("retry scheduled in 30s")
        }
        exhaustedBinder.die()
        awaitCondition("recovery waiting for cooldown") { TorProxyHelper.status.startsWith("ERROR:") }
        val requestsBeforeCooldown = sourceRequests.get()
        AndroidUtilities.advance(29_999)
        AndroidUtilities.drain()
        check(sourceRequests.get() == requestsBeforeCooldown)
        AndroidUtilities.advance(1)
        awaitCondition("recovery resumes after cooldown") { app.bindings.size == bindings + 3 }
        val recoveredBinder = TestBinder()
        app.bindings.last().onServiceConnected(null, recoveredBinder)
        awaitCondition("cooldown recovery connected") { TorProxyHelper.status == "ON" }
        TorProxyHelper.stop()
        recoveredBinder.die()
        awaitCondition("cooldown test stopped") { TorProxyHelper.status == "OFF" }
        val stoppedBindings = app.bindings.size
        AndroidUtilities.advance(300_000)
        AndroidUtilities.drain()
        check(app.bindings.size == stoppedBindings && !TorConfig.enabled)
        println("PASS: exhausted bridge pool recovers after cooldown; explicit stop prevents delayed restarts")
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
        TorConfig.mode = "webtunnel"
        sourceBridgeLines = (1..6).flatMap { host ->
            (1..3).map { path -> "webtunnel 192.0.2.$host:443 url=https://host$host.example.test/path$path" }
        }.joinToString("\n")
        var diverseCount: Int? = null
        TorProxyHelper.refreshBridges { diverseCount = it }
        awaitCondition("six distinct bridge hosts selected") { diverseCount != null }
        check(diverseCount == 6)
        val savedHosts = TorConfig.bridgesFor("webtunnel").lines().map {
            java.net.URI(it.substringAfter("url=")).host
        }
        check(savedHosts.toSet().size == 6) { "Bridge selection picked duplicate tunnel hosts" }
        check(TorProxyHelper.status == "OFF" && !TorConfig.enabled)
        println("PASS: bridge selection retains six different hosts even when source paths are duplicated")
        println("All Tor lifecycle regression scenarios passed")
        kotlin.system.exitProcess(0)
    } catch(t: Throwable) {
        t.printStackTrace()
        kotlin.system.exitProcess(1)
    }
}
