package tw.nekomimi.nekogram.tor

import IPtProxy.Controller
import IPtProxy.OnTransportEvents
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.content.edit
import tw.nekomimi.nekogram.utils.ProxyUtil
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.SharedConfig
import org.telegram.tgnet.ConnectionsManager
import org.torproject.jni.TorService
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Owns the embedded Tor instance. Never enable Telegram's proxy until Tor has a circuit. */
object TorProxyHelper {
    @JvmStatic
    fun isTorProcess(): Boolean = runCatching {
        File("/proc/self/cmdline").readText().substringBefore('\u0000').endsWith(":bebragram_tor")
    }.getOrDefault(false)

    private val worker = Executors.newSingleThreadExecutor()
    private val bridgeWorker = Executors.newSingleThreadExecutor()
    private val controlWorker = Executors.newSingleThreadExecutor()
    private lateinit var app: Context
    @Volatile private var generation = 0
    private var bound = false
    private var connection: ServiceConnection? = null
    private var service: TorRemote? = null
    private var pendingNetworkBridges: Triple<String, String, Boolean>? = null
    private var torBinder: IBinder? = null
    private val startedTransports = mutableListOf<String>()
    private var localProxy: SharedConfig.ProxyInfo? = null
    private var previousProxy: SharedConfig.ProxyInfo? = null
    private var previousEnabled = false
    private var previousCallsEnabled = false
    private const val PREVIOUS_PROXY_KEY = "inu_tor_previous_proxy"
    private const val MAX_PING_CANDIDATES = 12
    private const val SELECTED_BRIDGES = 3
    private const val HEALTH_CHECK_MS = 90_000L
    @Volatile private var activeBridgeLines = ""
    @Volatile private var activeAutoBridges = false
    private var healthCheckScheduled = false
    private var missedHealthChecks = 0
    @Volatile private var defaultNetwork: Network? = null
    @Volatile private var networkChanged = false
    private var reconnectWhenOnline = false
    private var reconnectWhenVpnOff = false
    @Volatile private var failedBridgeLines = emptySet<String>()
    private data class BridgeRefresh(
        val mode: String, val token: Int,
        var restartStarting: Boolean,
        val callbacks: MutableList<java.util.function.IntConsumer> = mutableListOf()
    )
    private var bridgeRefresh: BridgeRefresh? = null
    private val prefetching = mutableSetOf<String>()
    private var rotationAttempts = 0
    private const val BRIDGE_CACHE_TTL_MS = 2L * 24 * 60 * 60 * 1000
    private var ownedProxy = false
    private var stoppedForOtherProxy = false
    private var startQueued = false
    private var stopError: String? = null
    private var previousRotationEnabled = false
    @Volatile var status: String = "OFF"
        private set
    /** Bootstrap progress 0-100 once tor reports it, -1 while unknown. */
    @Volatile var progress: Int = -1
        private set
    @Volatile var lastError: String? = null
        private set

    /**
     * One Controller per process, like Orbot does. gomobile keeps a Java/Go reference table
     * that desyncs ("trackGoRef called with Java refnum N") if the object is recreated.
     */
    private val controller: Controller by lazy {
        Controller(File(app.filesDir, "tor-transports").apply { mkdirs() }.absolutePath, true, false, "INFO", events)
    }

    private val events = object : OnTransportEvents {
        override fun connected(name: String?) { TorLog.add("transport $name: connected") }
        override fun error(name: String?, error: Exception?) { TorLog.add("transport $name: ERROR ${error?.message ?: error}") }
        override fun stopped(name: String?, error: Exception?) { TorLog.add("transport $name: stopped ${error?.message ?: ""}") }
    }
    private fun newConnection(token: Int): ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (connection !== this || !bound) return
            if (binder == null) {
                onNullBinding(name)
                return
            }
            val tor = TorRemote(binder)
            torBinder = binder
            val sessionConnection = this
            try {
                binder.linkToDeath({
                    AndroidUtilities.runOnUIThread { onTorProcessDied(sessionConnection, binder) }
                }, 0)
            } catch (e: android.os.RemoteException) {
                onTorProcessDied(this, binder)
                return
            }
            // A stop can precede this callback. Keep the binding until we have a
            // death recipient; unbinding earlier loses proof that native Tor exited.
            if (status == "STOPPING") {
                unbindTor()
                return
            }
            service = tor
            AndroidUtilities.runOnUIThread({
                if (token == generation && status == "STARTING") fail("Tor connection timed out")
            }, 120_000)
            controlWorker.execute {
                try {
                    var lastPhase = ""
                    var highestProgress = -1
                    var lastProgressAt = SystemClock.elapsedRealtime()
                    for (i in 0 until 600) {
                        if (token != generation) return@execute
                        if (tor.controlReady) {
                            val phase = tor.getInfo("status/bootstrap-phase")
                            if (token != generation) return@execute
                            if (phase != null && phase != lastPhase) {
                                lastPhase = phase
                                Regex("PROGRESS=(\\d+)").find(phase)?.let { progress = it.groupValues[1].toInt() }
                                TorLog.add("bootstrap: $phase")
                            }
                            if (progress > highestProgress) {
                                highestProgress = progress
                                lastProgressAt = SystemClock.elapsedRealtime()
                            }
                            // Poll the control port instead of relying on broadcasts, which can be missed.
                            val port = tor.socksPort
                            if (tor.getInfo("status/circuit-established") == "1" && port in 1..65535) {
                                progress = 100
                                TorLog.add("circuit established, tor SOCKS port $port")
                                AndroidUtilities.runOnUIThread {
                                    if (token == generation) {
                                        if (ProxyUtil.isVpnProxySuppressionActive()) {
                                            pauseForVpn()
                                        } else {
                                            status = "ON"
                                            connectProxy(port)
                                            scheduleHealthCheck()
                                        }
                                    }
                                }
                                return@execute
                            }
                            if (activeAutoBridges && highestProgress in 0..79 &&
                                SystemClock.elapsedRealtime() - lastProgressAt >= 45_000L) {
                                AndroidUtilities.runOnUIThread {
                                    if (token == generation) fail("Tor bootstrap stalled at $highestProgress%")
                                }
                                return@execute
                            }
                        }
                        TimeUnit.MILLISECONDS.sleep(200)
                    }
                    AndroidUtilities.runOnUIThread {
                        if (token == generation) fail("Tor did not bootstrap" + if (progress >= 0) " ($progress%)" else "")
                    }
                } catch (e: Exception) {
                    AndroidUtilities.runOnUIThread {
                        if (token == generation) fail("Tor control failed: ${e.message ?: e.javaClass.simpleName}")
                    }
                }
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            val binder = torBinder ?: return
            if (binder.isBinderAlive == false) onTorProcessDied(this, binder)
        }

        override fun onNullBinding(name: ComponentName?) {
            if (connection !== this) return
            // No Binder/death callback will ever arrive for a null binding.
            val stopping = status == "STOPPING"
            unbindTor()
            connection = null
            torBinder = null
            service = null
            if (stopping) {
                finishStopping()
            } else {
                fail("Tor service returned no control connection")
            }
        }
    }

    private fun unbindTor() {
        val current = connection ?: return
        if (bound) {
            app.unbindService(current)
            bound = false
        }
    }

    private fun onTorProcessDied(sessionConnection: ServiceConnection, binder: IBinder) {
        // Both onServiceDisconnected and linkToDeath can report the same death.
        // A delayed notification from an old session must not stop its replacement.
        if (connection !== sessionConnection || torBinder !== binder) return
        val stopping = status == "STOPPING"
        unbindTor()
        connection = null
        torBinder = null
        service = null
        if (stopping) {
            finishStopping()
        } else if (TorConfig.enabled && (status == "ON" || status == "STARTING")) {
            fail("Tor process stopped")
        }
    }

    private fun finishStopping() {
        status = when {
            reconnectWhenVpnOff -> "WAITING_FOR_VPN"
            reconnectWhenOnline -> "WAITING_FOR_NETWORK"
            stopError != null -> "ERROR: $stopError"
            else -> "OFF"
        }
        if (startQueued && TorConfig.enabled) {
            startQueued = false
            start()
        } else if (reconnectWhenVpnOff && !ProxyUtil.isVpnProxySuppressionActive() && hasInternet()) {
            reconnectWhenVpnOff = false
            start()
        } else if (reconnectWhenOnline && hasInternet()) {
            reconnectWhenOnline = false
            start()
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == TorService.ACTION_ERROR) {
                // Broadcasts have no session identifier and can arrive after a restart.
                // Control polling/death callbacks own failures; keep these as diagnostics.
                TorLog.add("tor service error: ${intent.getStringExtra(Intent.EXTRA_TEXT) ?: "Tor error"}")
                return
            }
            val state = intent?.getStringExtra(TorService.EXTRA_STATUS) ?: return
            TorLog.add("tor status: $state")
            // The broadcast can arrive before process death (or late from an old process).
            // Bootstrap and the Binder death recipient own state transitions.
        }
    }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val previous = defaultNetwork
            defaultNetwork = network
            if (previous != network) networkChanged = true
            scheduleNetworkAction(network)
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                scheduleNetworkAction(network)
            }
        }

        override fun onLost(network: Network) {
            if (defaultNetwork == network) defaultNetwork = null
        }
    }

    // Android's connectivity probe can be blocked while bridges remain reachable.
    // Do not require Google's/captive-portal validation to permit a Tor attempt.
    private fun hasInternet(): Boolean {
        val connectivity = app.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = connectivity.activeNetwork ?: return false
        return connectivity.getNetworkCapabilities(network)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    private fun scheduleNetworkAction(network: Network) {
        if (!networkChanged && !reconnectWhenOnline && !reconnectWhenVpnOff) return
        AndroidUtilities.runOnUIThread({
            if (defaultNetwork != network || !hasInternet()) return@runOnUIThread
            val wasVpnPaused = reconnectWhenVpnOff
            onVpnPreferenceChanged()
            if (ProxyUtil.isVpnProxySuppressionActive() || wasVpnPaused) {
                networkChanged = false
                return@runOnUIThread
            }
            if (reconnectWhenOnline) {
                reconnectWhenOnline = false
                networkChanged = false
                TorLog.add("internet restored; starting Tor")
                start()
            } else if (networkChanged && TorConfig.enabled &&
                (status == "ON" || status == "STARTING")) {
                networkChanged = false
                TorLog.add("default network changed; restarting Tor")
                restartForNewBridges(keepCurrentBridges = true)
            } else {
                networkChanged = false
            }
        }, 3000)
    }

    @JvmStatic
    fun init(context: Context) {
        if (isTorProcess()) return
        app = context.applicationContext
        TorConfig.migrateLegacy()
        if (!(TorConfig.enabled && TorConfig.autostart)) prefetchBridges()
        AndroidUtilities.runOnUIThread(autoRefreshTick, 5000)
        try {
            SharedConfig.loadProxyList()
            val saved = app.getSharedPreferences("bebragram", Context.MODE_PRIVATE).getString(PREVIOUS_PROXY_KEY, null)
            if (saved != null) restoreInterruptedSession()
            // Without an ownership marker, a localhost SOCKS proxy may belong to the user.
            // Never delete it just because Tor is configured or the process restarted.
        } catch (e: Exception) { Log.d("BebragramTor", "Recover proxy failed", e) }
        val filter = IntentFilter(TorService.ACTION_STATUS).apply { addAction(TorService.ACTION_ERROR) }
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") app.registerReceiver(receiver, filter)
        if (Build.VERSION.SDK_INT >= 24) {
            val connectivity = app.getSystemService(ConnectivityManager::class.java)
            defaultNetwork = connectivity?.activeNetwork
            runCatching { connectivity?.registerDefaultNetworkCallback(networkCallback) }
                .onFailure { TorLog.add("network callback unavailable: ${it.javaClass.simpleName}") }
        }
        if (TorConfig.enabled && TorConfig.autostart) start() // opt-in only
        else if (TorConfig.enabled) {
            TorConfig.enabled = false
            TorLog.add("Tor left off on launch (autostart disabled)")
        }
    }

    fun start() {
        if (!::app.isInitialized) return
        if (connection != null && status != "ON" && status != "STARTING") {
            startQueued = true
            TorConfig.enabled = true
            status = "STOPPING"
            return
        }
        if (status.startsWith("ERROR:")) status = "OFF"
        if (status == "WAITING_FOR_NETWORK") status = "OFF"
        if (status == "WAITING_FOR_VPN") status = "OFF"
        if (status == "STOPPING") {
            // TorService must be destroyed before a new torrc is written, otherwise the old instance keeps running.
            startQueued = true
            TorConfig.enabled = true
            return
        }
        if (status != "OFF") return
        if (ProxyUtil.isVpnProxySuppressionActive()) {
            TorConfig.enabled = true
            reconnectWhenVpnOff = true
            status = "WAITING_FOR_VPN"
            TorLog.add("waiting for VPN to disconnect before starting Tor")
            return
        }
        if (!hasInternet()) {
            TorConfig.enabled = true
            reconnectWhenOnline = true
            status = "WAITING_FOR_NETWORK"
            TorLog.add("waiting for internet before starting Tor")
            return
        }
        reconnectWhenOnline = false
        reconnectWhenVpnOff = false
        TorConfig.enabled = true
        val token = ++generation
        status = "STARTING"
        stopError = null
        progress = -1
        lastError = null
        val networkBridges = pendingNetworkBridges
        pendingNetworkBridges = null
        AndroidUtilities.runOnUIThread({
            if (token == generation && status == "STARTING" && service == null) fail("Tor service start timed out")
        }, 60_000)
        worker.execute {
            try {
                val mode = TorConfig.mode
                TorLog.add("start: mode=$mode")
                val bridges = if (networkBridges != null && networkBridges.first == mode) {
                    activeBridgeLines = networkBridges.second
                    activeAutoBridges = networkBridges.third
                    TorLog.add("bridges: reusing the connected session's bridges after network change")
                    networkBridges.second
                } else bridgeLines(mode, token)
                if (token != generation) {
                    TorLog.add("start cancelled before transports")
                    return@execute
                }
                val names = TorBridgeConfig.transports(mode, bridges)
                val ports = mutableMapOf<String, Long>()
                if (names.isNotEmpty()) {
                    for (name in names) {
                        if (token != generation) return@execute
                        if (name !in startedTransports) {
                            controller.start(name, null)
                            startedTransports.add(name)
                        }
                        ports[name] = controller.port(name)
                        TorLog.add("transport $name listening on ${ports[name]}")
                    }
                }
                val torrc = TorBridgeConfig.build(mode, bridges, ports)
                    // SafeSocks must stay off: Telegram dials its DCs by IP and tor rejects IP-only SOCKS requests.
                    .plus("ClientOnly 1")
                    .joinToString("\n", postfix = "\n")
                if (token != generation) return@execute
                TorService.getTorrc(app).writeText(torrc)
                TorLog.add("torrc written (${countBridges(bridges)} bridge line(s))")
                AndroidUtilities.runOnUIThread {
                    if (token != generation) return@runOnUIThread
                    val sessionConnection = newConnection(token)
                    connection = sessionConnection
                    bound = try {
                        app.bindService(Intent(app, TorHostService::class.java), sessionConnection, Context.BIND_AUTO_CREATE)
                    } catch (e: Exception) {
                        TorLog.add("bindService threw: ${e.message}")
                        false
                    }
                    TorLog.add("bindService -> $bound")
                    if (!bound) {
                        connection = null
                        fail("Cannot start Tor service")
                    }
                }
            } catch (e: Exception) {
                TorLog.add("start failed: ${e.javaClass.simpleName}: ${e.message}")
                AndroidUtilities.runOnUIThread { if (token == generation) fail(e.localizedMessage ?: "Tor failed") }
            }
        }
    }

    fun stop() = stopInternal(resetRecovery = true)

    private fun stopInternal(resetRecovery: Boolean) {
        if (resetRecovery) {
            rotationAttempts = 0
            failedBridgeLines = emptySet()
        }
        TorLog.add("stop requested (status=$status)")
        TorConfig.enabled = false
        reconnectWhenOnline = false
        reconnectWhenVpnOff = false
        ++generation
        startQueued = false
        pendingNetworkBridges = null
        stopError = null
        healthCheckScheduled = false
        missedHealthChecks = 0
        AndroidUtilities.cancelRunOnUIThread(healthTick)
        restoreProxy()
        stoppedForOtherProxy = false
        val stoppingService = connection != null
        if (torBinder != null) unbindTor()
        service = null
        status = if (stoppingService) "STOPPING" else "OFF"
        progress = -1
        if (stoppingService) {
            val token = generation
            // Binder death confirms that the old native Tor instance is gone.
            AndroidUtilities.runOnUIThread({
                if (token == generation && status == "STOPPING" && connection != null) {
                    lastError = "Waiting for the previous Tor process to exit"
                    TorLog.add(lastError!!)
                    // Keep the start queued. Only Binder death permits a replacement.
                }
            }, 10_000)
        }
        worker.execute {
            startedTransports.forEach { name -> try { controller.stop(name) } catch (e: Exception) { TorLog.add("stop $name: ${e.message}") } }
            startedTransports.clear()
        }
    }

    /** The VPN setting pauses native Tor too, so an unused circuit does not drain the battery. */
    @JvmStatic
    fun onVpnPreferenceChanged() {
        if (!::app.isInitialized) return
        if (ProxyUtil.isVpnProxySuppressionActive()) {
            if (TorConfig.enabled && (status == "ON" || status == "STARTING")) pauseForVpn()
        } else if (reconnectWhenVpnOff && status != "STOPPING") {
            reconnectWhenVpnOff = false
            start()
        }
    }

    private fun pauseForVpn() {
        TorLog.add("VPN active; pausing Tor")
        stop()
        TorConfig.enabled = true
        reconnectWhenVpnOff = true
        if (status == "OFF") status = "WAITING_FOR_VPN"
    }

    private fun fail(reason: String) {
        TorLog.add("FAIL: $reason")
        val offline = !hasInternet()
        lastError = reason
        stopInternal(resetRecovery = false)
        stopError = reason
        TorConfig.enabled = true // transient failures must not disable the user's autostart preference
        if (offline) {
            TorConfig.enabled = true
            reconnectWhenOnline = true
            if (status != "STOPPING") status = "WAITING_FOR_NETWORK"
        } else {
            if (status != "STOPPING") status = "ERROR: $reason"
            maybeRotateBridges(reason)
        }
    }

    /** Tor can lose its circuit after bootstrap. Poll only while enabled and let Tor retry first. */
    private val healthTick = object : Runnable {
        override fun run() {
            healthCheckScheduled = false
            if (status != "ON" || !TorConfig.enabled) return
            val token = generation
            val tor = service ?: return
            controlWorker.execute {
                val established = runCatching { tor.getInfo("status/circuit-established") == "1" }.getOrDefault(false)
                AndroidUtilities.runOnUIThread {
                    if (token != generation || status != "ON") return@runOnUIThread
                    missedHealthChecks = if (established) 0 else missedHealthChecks + 1
                    if (missedHealthChecks >= 3) fail("Tor circuit lost")
                    else scheduleHealthCheck()
                }
            }
        }
    }

    private fun scheduleHealthCheck() {
        if (healthCheckScheduled || status != "ON") return
        healthCheckScheduled = true
        AndroidUtilities.runOnUIThread(healthTick, HEALTH_CHECK_MS)
    }

    /** Try different candidates; a Tor/transport failure cannot identify which bridge failed. */
    private fun maybeRotateBridges(reason: String) {
        if (rotationAttempts >= 2 || !activeAutoBridges) return
        val mode = TorConfig.mode
        if (mode == "direct" || mode == "custom") return
        val lines = activeBridgeLines
        if (lines.isBlank()) return
        failedBridgeLines = failedBridgeLines + lines.lineSequence().filter { it.isNotBlank() }.toSet()
        rotationAttempts++
        TorLog.add("bridges: retry $rotationAttempts after \"$reason\" (previous lines excluded)")
        refreshBridgesInternal(restartStarting = true, callback = null)
    }

    @JvmStatic fun isTorProxy(proxy: SharedConfig.ProxyInfo): Boolean = proxy == localProxy

    /** Stop Tor when another proxy is chosen, rather than fighting for SharedConfig.currentProxy. */
    @JvmStatic fun onOtherProxySelected() {
        if (!TorConfig.enabled) return
        previousProxy = null
        previousEnabled = false
        stoppedForOtherProxy = true
        stop()
    }

    private fun connectProxy(port: Int) {
        if (!TorConfig.enabled || localProxy != null) return
        val vpnSuppressed = ProxyUtil.isVpnProxySuppressionActive()
        SharedConfig.loadProxyList()
        previousProxy = SharedConfig.currentProxy
        previousEnabled = SharedConfig.isProxyEnabled()
        previousCallsEnabled = MessagesController.getGlobalMainSettings().getBoolean("proxy_enabled_calls", false)
        // Keep the previous proxy durable across Android process death while Tor is selected.
        app.getSharedPreferences("bebragram", Context.MODE_PRIVATE).edit {
            putString(PREVIOUS_PROXY_KEY, org.json.JSONObject().apply {
                put("address", previousProxy?.address ?: "")
                put("port", previousProxy?.port ?: 1080)
                put("user", previousProxy?.username ?: "")
                put("pass", previousProxy?.password ?: "")
                put("secret", previousProxy?.secret ?: "")
                put("enabled", previousEnabled)
                put("calls", previousCallsEnabled)
                put("rotation", SharedConfig.proxyRotationEnabled)
                put("owned", SharedConfig.proxyList.none { it.address == "127.0.0.1" && it.port == port && it.username.isEmpty() && it.secret.isEmpty() })
            }.toString())
        }
        val existing = SharedConfig.proxyList.firstOrNull { it.address == "127.0.0.1" && it.port == port && it.username.isEmpty() && it.secret.isEmpty() }
        ownedProxy = existing == null
        val proxy = existing ?: SharedConfig.addProxy(SharedConfig.ProxyInfo("127.0.0.1", port, "", "", ""))
        localProxy = proxy
        rotationAttempts = 0
        failedBridgeLines = emptySet()
        lastError = null
        SharedConfig.currentProxy = proxy
        MessagesController.getGlobalMainSettings().edit {
            putBoolean("proxy_enabled", !vpnSuppressed)
            putBoolean("proxy_enabled_calls", false) // calls are not safe over Tor
            putString("proxy_ip", proxy.address)
            putInt("proxy_port", proxy.port)
            putString("proxy_user", "")
            putString("proxy_pass", "")
            putString("proxy_secret", "")
        }
        ConnectionsManager.setProxySettings(!vpnSuppressed, proxy.address, proxy.port, "", "", "")
        TorLog.add("Telegram proxy -> ${proxy.address}:${proxy.port} (calls off)" +
            if (vpnSuppressed) ", left disabled while VPN is active" else "")
        // Stock rotation would silently swap the proxy out from under Tor.
        previousRotationEnabled = SharedConfig.proxyRotationEnabled
        if (previousRotationEnabled) {
            SharedConfig.proxyRotationEnabled = false
            SharedConfig.saveConfig()
        }
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged)
    }

    private fun restoreProxy() {
        val tor = localProxy ?: return
        localProxy = null
        if (SharedConfig.currentProxy == tor && !stoppedForOtherProxy) {
            val old = previousProxy
            SharedConfig.currentProxy = old
            val enabled = previousEnabled && old != null
            MessagesController.getGlobalMainSettings().edit {
                putBoolean("proxy_enabled", enabled)
                putBoolean("proxy_enabled_calls", previousCallsEnabled && enabled)
                putString("proxy_ip", old?.address ?: "")
                putInt("proxy_port", old?.port ?: 1080)
                putString("proxy_user", old?.username ?: "")
                putString("proxy_pass", old?.password ?: "")
                putString("proxy_secret", old?.secret ?: "")
            }
            ConnectionsManager.setProxySettings(enabled, old?.address ?: "", old?.port ?: 1080,
                old?.username ?: "", old?.password ?: "", old?.secret ?: "")
        }
        if (stoppedForOtherProxy && SharedConfig.currentProxy == tor) SharedConfig.currentProxy = null
        if (ownedProxy) SharedConfig.deleteProxy(tor)
        ownedProxy = false
        if (previousRotationEnabled) {
            SharedConfig.proxyRotationEnabled = true
            SharedConfig.saveConfig()
            previousRotationEnabled = false
        }
        previousProxy = null
        app.getSharedPreferences("bebragram", Context.MODE_PRIVATE).edit { remove(PREVIOUS_PROXY_KEY) }
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged)
        // The restored proxy may still need VPN suppression (or its removal).
        ProxyUtil.recheckProxyState()
    }

    private fun restoreInterruptedSession() {
        val saved = app.getSharedPreferences("bebragram", Context.MODE_PRIVATE).getString(PREVIOUS_PROXY_KEY, null) ?: return
        try {
            SharedConfig.loadProxyList()
            val old = org.json.JSONObject(saved)
            val address = old.optString("address")
            val current = SharedConfig.currentProxy
            if (current?.address != "127.0.0.1") {
                app.getSharedPreferences("bebragram", Context.MODE_PRIVATE).edit { remove(PREVIOUS_PROXY_KEY) }
                return
            }
            val restored = if (address.isNotEmpty()) SharedConfig.addProxy(SharedConfig.ProxyInfo(address,
                old.optInt("port", 1080), old.optString("user"), old.optString("pass"), old.optString("secret"))) else null
            SharedConfig.currentProxy = restored
            val enabled = old.optBoolean("enabled") && restored != null
            // Startup recovery must not construct MessagesController/account services just to
            // write preferences. They read this same file during normal Telegram initialization.
            app.getSharedPreferences("mainconfig", Context.MODE_PRIVATE).edit {
                putBoolean("proxy_enabled", enabled)
                putBoolean("proxy_enabled_calls", old.optBoolean("calls") && enabled)
                putString("proxy_ip", restored?.address ?: "")
                putInt("proxy_port", restored?.port ?: 1080)
                putString("proxy_user", restored?.username ?: "")
                putString("proxy_pass", restored?.password ?: "")
                putString("proxy_secret", restored?.secret ?: "")
            }
            if (old.optBoolean("owned")) SharedConfig.deleteProxy(current)
            if (old.optBoolean("rotation")) {
                SharedConfig.proxyRotationEnabled = true
                SharedConfig.saveConfig()
            }
            app.getSharedPreferences("bebragram", Context.MODE_PRIVATE).edit { remove(PREVIOUS_PROXY_KEY) }
        } catch (e: Exception) { Log.d("BebragramTor", "Restore prior proxy failed", e) }
    }

    /**
     * Fetches fresh bridges for the current mode, stores them as the active bridge lines
     * (auto-managed, so the scheduler may overwrite them later). An established
     * connection keeps its current bridges until the next start. A manual refresh
     * retries an unfinished bootstrap immediately with the new bridges.
     * Callback runs on the UI thread: line count, or -1 on failure.
     */
    @JvmStatic
    fun refreshBridges(callback: java.util.function.IntConsumer? = null) {
        rotationAttempts = 0
        failedBridgeLines = emptySet()
        refreshBridgesInternal(restartStarting = true, callback = callback)
    }

    private fun refreshBridgesInternal(restartStarting: Boolean, callback: java.util.function.IntConsumer?) {
        android.util.Log.d("BebragramTor", "refreshBridges: mode=${TorConfig.mode} enabled=${TorConfig.enabled}")
        if (!::app.isInitialized) {
            callback?.accept(-1)
            return
        }
        val mode = TorConfig.mode
        if (mode == "direct" || mode == "custom") {
            callback?.accept(-1)
            return
        }
        if (!hasInternet()) {
            callback?.accept(-1)
            return
        }
        val existing = bridgeRefresh
        if (existing != null && existing.mode == mode && existing.token == generation) {
            existing.restartStarting = existing.restartStarting || restartStarting
            callback?.let { existing.callbacks.add(it) }
            return
        }
        val token = generation
        val previousLines = TorConfig.bridgesFor(mode)
        val request = BridgeRefresh(mode, token, restartStarting)
        callback?.let { request.callbacks.add(it) }
        bridgeRefresh = request
        fun complete(count: Int) {
            if (bridgeRefresh === request) bridgeRefresh = null
            request.callbacks.forEach { it.accept(count) }
        }
        bridgeWorker.execute {
            if (token != generation) {
                AndroidUtilities.runOnUIThread { complete(-1) }
                return@execute
            }
            val fresh = try {
                githubBridges(mode, token)
            } catch (e: Exception) {
                null
            }
            if (token != generation) {
                AndroidUtilities.runOnUIThread { complete(-1) }
                return@execute
            }
            val lines = fresh ?: runCatching {
                rankByPing(bundledBridges(mode).lines().filterNot { it in failedBridgeLines }.take(MAX_PING_CANDIDATES), SELECTED_BRIDGES, token = token).joinToString("\n")
            }.getOrNull()
            if (lines.isNullOrBlank()) {
                TorLog.add("bridge refresh failed: no $mode bridges")
                AndroidUtilities.runOnUIThread {
                    complete(-1)
                }
                return@execute
            }
            val count = countBridges(lines)
            cacheBridges(mode, lines)
            AndroidUtilities.runOnUIThread {
                // A completed fetch must not overwrite pasted bridges or restart a
                // session the user stopped while the network request was running.
                if (token != generation || mode != TorConfig.mode || TorConfig.bridgesFor(mode) != previousLines) {
                    complete(-1)
                    return@runOnUIThread
                }
                TorConfig.setBridgesFor(mode, lines)
                TorConfig.setAutoManagedFor(mode, true)
                TorConfig.setLastBridgeRefreshFor(mode, System.currentTimeMillis())
                TorLog.add("bridges: refreshed $count $mode line(s)")
                val restart = TorConfig.enabled && status != "ON" &&
                    (status != "STARTING" || request.restartStarting)
                if (TorConfig.enabled && !restart) {
                    TorLog.add("bridges: saved for the next start; keeping current Tor session")
                }
                complete(count)
                if (restart && token == generation && TorConfig.enabled && TorConfig.mode == mode) restartForNewBridges()
            }
        }
    }

    private fun restartForNewBridges(keepCurrentBridges: Boolean = false) {
        val reusable = if (keepCurrentBridges && status == "ON" && activeBridgeLines.isNotBlank()) {
            Triple(TorConfig.mode, activeBridgeLines, activeAutoBridges)
        } else null
        TorLog.add(if (reusable != null) "restarting Tor with the connected session's bridges" else "restarting Tor with refreshed bridges")
        stopInternal(resetRecovery = false)
        pendingNetworkBridges = reusable
        start()
    }

    private val autoRefreshTick = object : Runnable {
        override fun run() {
            maybeAutoRefresh()
            AndroidUtilities.runOnUIThread(this, 60 * 60 * 1000L)
        }
    }

    private fun maybeAutoRefresh() {
        val hours = TorConfig.bridgeAutoRefreshHours
        val mode = TorConfig.mode
        if (hours <= 0 || !TorConfig.autoManagedFor(mode) || !hasInternet()) return
        val due = System.currentTimeMillis() - TorConfig.lastBridgeRefreshFor(mode) >= hours * 3600_000L
        if (due) {
            TorLog.add("auto-refresh: bridges are older than $hours h")
            refreshBridgesInternal(restartStarting = false, callback = null)
        }
    }

    /** Warm the bridge cache in the background so starting Tor does not wait for a fetch. */
    @JvmStatic
    fun prefetchBridges() {
        if (!::app.isInitialized) return
        if (TorConfig.enabled) return
        val mode = TorConfig.mode
        if (mode == "direct" || mode == "custom") return
        if (TorConfig.bridgesFor(mode).isNotBlank()) return
        if (cachedBridges(mode) != null) return
        if (!prefetching.add(mode)) return
        bridgeWorker.execute {
            try {
                if (cachedBridges(mode) != null) return@execute
                githubBridges(mode)?.let {
                    cacheBridges(mode, it)
                    TorLog.add("bridges: prefetched ${countBridges(it)} $mode line(s)")
                }
            } catch (e: Exception) {
                TorLog.add("bridge prefetch failed: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                AndroidUtilities.runOnUIThread { prefetching.remove(mode) }
            }
        }
    }

    /** Resolve bridge lines for [mode]: manual input, then fresh GitHub lists, then bundled. */
    fun bridgeLines(mode: String, token: Int? = null): String {
        activeAutoBridges = false
        activeBridgeLines = ""
        if (mode == "direct") return ""
        var manual = TorConfig.bridgesFor(mode)
        if (manual.isNotBlank()) {
            if (TorConfig.autoManagedFor(mode)) {
                manual = validBridgeLines(mode, manual).joinToString("\n")
                if (manual.isBlank()) TorLog.add("bridges: ignoring invalid saved $mode lines")
            } else {
                manual = TorBridgeConfig.lines(mode, manual).joinToString("\n")
            }
        }
        if (manual.isNotBlank()) {
            activeBridgeLines = manual
            activeAutoBridges = TorConfig.autoManagedFor(mode)
            TorLog.add("bridges: using ${countBridges(manual)} line(s) from settings")
            return manual
        }
        if (mode == "custom") throw IllegalArgumentException("Paste bridge lines for custom mode")
        cachedBridges(mode)?.let {
            activeBridgeLines = it
            activeAutoBridges = true
            TorLog.add("bridges: ${countBridges(it)} cached $mode line(s)")
            return it
        }
        githubBridges(mode, token)?.let {
            activeBridgeLines = it
            activeAutoBridges = true
            TorLog.add("bridges: fetched ${countBridges(it)} $mode line(s) from github")
            cacheBridges(mode, it)
            return it
        }
        if (token != null && token != generation) throw java.util.concurrent.CancellationException("Tor start cancelled")
        val bundled = bundledBridges(mode)
        activeBridgeLines = bundled
        activeAutoBridges = true
        TorLog.add("bridges: ${countBridges(bundled)} bundled $mode line(s)")
        return bundled
    }

    private fun countBridges(text: String) = text.lineSequence().count { it.isNotBlank() }

    private fun bundledBridges(mode: String): String {
        val json = org.json.JSONObject(app.assets.open("inu_tor_bridges.json").bufferedReader().use { it.readText() })
        val entries = json.optJSONArray(mode) ?: throw IllegalArgumentException(
            "No built-in $mode bridges shipped and the fetch failed - paste your own lines under Bridge lines"
        )
        if (entries.length() == 0) throw IllegalArgumentException("No built-in $mode bridges available and all fetch sources failed. Paste a fresh $mode bridge line in Bridge lines.")
        val lines = validBridgeLines(mode, (0 until entries.length()).joinToString("\n") { entries.getString(it) })
        require(lines.isNotEmpty()) { "No valid built-in $mode bridges" }
        return lines.joinToString("\n")
    }

    private fun cachedBridges(mode: String): String? {
        val raw = app.getSharedPreferences("bebragram", Context.MODE_PRIVATE).getString(cacheKey(mode), null) ?: return null
        val split = raw.indexOf('|')
        if (split <= 0) return null
        val age = System.currentTimeMillis() - (raw.substring(0, split).toLongOrNull() ?: return null)
        if (age < 0 || age > BRIDGE_CACHE_TTL_MS) return null
        return validBridgeLines(mode, raw.substring(split + 1)).joinToString("\n").takeIf { it.isNotBlank() }
    }

    private fun cacheBridges(mode: String, bridges: String) {
        app.getSharedPreferences("bebragram", Context.MODE_PRIVATE).edit {
            putString(cacheKey(mode), "${System.currentTimeMillis()}|$bridges")
        }
    }

    private fun cacheKey(mode: String) = "inu_tor_bridges_$mode"

    /** Bridge sources that are reachable from Russia: GitHub raw + jsDelivr mirror. */
    private val githubSources = listOf(
        "https://cdn.jsdelivr.net/gh/Delta-Kronecker/Tor-Bridges-Collector@main/",
        "https://raw.githubusercontent.com/Delta-Kronecker/Tor-Bridges-Collector/main/",
    )

    /**
     * Sample up to 12 distinct endpoints from GitHub lists and keep three responsive ones.
     * This is only an endpoint check; Tor bootstrap is the actual bridge check.
     */
    private fun githubBridges(mode: String, token: Int? = null): String? {
        val files = when (mode) {
            "webtunnel" -> listOf("bridge/webtunnel_72h.txt", "bridge/webtunnel.txt")
            else -> return null
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(16)
        for (file in files) {
            if (token != null && token != generation) return null
            val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (remainingMs <= 0) break
            // Race mirrors, trying the short fresh list before the larger fallback.
            val downloads = githubSources.map { base ->
                java.util.concurrent.Callable {
                    httpGet(base + file)?.let {
                        validBridgeLines(mode, it).filterNot { line -> line in failedBridgeLines }
                            .takeIf { lines -> lines.isNotEmpty() }
                    }
                }
            }
            val pool = TorBridgeTasks.collect(downloads, minOf(8_000L, remainingMs), 1) {
                token != null && token != generation
            }.firstOrNull() ?: continue
            val candidates = pool.shuffled().take(MAX_PING_CANDIDATES)
            val probeMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (probeMs <= 0) break
            val ranked = rankByPing(candidates, SELECTED_BRIDGES, minOf(4_000L, probeMs), token)
            if (ranked.isNotEmpty()) {
                TorLog.add("bridges: ${pool.size} valid candidates, ${ranked.size} selected for $mode; bootstrap will verify them")
                return ranked.joinToString("\n")
            }
            TorLog.add("bridges: no reachable $mode endpoints in $file; trying fallback")
        }
        return null
    }

    private fun validBridgeLines(mode: String, text: String): List<String> = text.lineSequence()
        .flatMap { runCatching { TorBridgeConfig.lines(mode, it).asSequence() }.getOrDefault(emptySequence()) }
        .distinct().toList()

    /** TCP-connect time to the endpoint that actually matters for this bridge line. */
    private fun pingHost(host: String, port: Int, timeoutMs: Int = 1500): Long {
        val start = System.currentTimeMillis()
        return try {
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(host, port), timeoutMs)
            }
            System.currentTimeMillis() - start
        } catch (e: Exception) {
            Long.MAX_VALUE
        }
    }

    /** WebTunnel connects through its url= host, not through the placeholder address. */
    private fun pingTarget(line: String): Pair<String, Int>? {
        val url = valueOf(line, "url").ifEmpty { valueOf(line, "ampcache") }
        if (url.isNotEmpty()) {
            return try {
                val uri = java.net.URI(url)
                val host = uri.host ?: return null
                host to (if (uri.port > 0) uri.port else if (uri.scheme == "http") 80 else 443)
            } catch (e: Exception) {
                null
            }
        }
        val addr = line.trim().removePrefix("Bridge ").trim().split(Regex("\\s+")).getOrNull(1) ?: return null
        val host = addr.substringBeforeLast(':', addr).removeSurrounding("[", "]")
        val port = addr.substringAfterLast(':').toIntOrNull() ?: return null
        return host to port
    }

    /**
     * An HTTPS answer only checks front-end reachability, not WebTunnel's POST/TLS
     * handshake or the Tor bridge behind it. Bootstrap remains the real bridge test.
     */
    private fun checkEndpoint(line: String): Long? {
        val target = pingTarget(line) ?: return null
        val url = valueOf(line, "url").ifEmpty { valueOf(line, "ampcache") }
        val start = System.currentTimeMillis()
        if (url.isEmpty()) {
            val t = pingHost(target.first, target.second)
            return if (t == Long.MAX_VALUE) null else t
        }
        var connection: java.net.HttpURLConnection? = null
        return try {
            connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 1800
                readTimeout = 1800
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Bebragram")
            }
            connection.responseCode
            System.currentTimeMillis() - start
        } catch (e: Exception) {
            null
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    /** Returns only endpoints that answered the check, fastest first. */
    private fun rankByPing(candidates: List<String>, limit: Int, timeoutMs: Long = 4_000, token: Int? = null): List<String> {
        if (candidates.isEmpty()) return emptyList()
        val tasks = candidates.distinct().map { line ->
            java.util.concurrent.Callable { checkEndpoint(line)?.let { line to it } }
        }
        // One deadline for the entire batch, collect in completion order, and return
        // as soon as enough endpoints answer instead of waiting for every dead bridge.
        return TorBridgeTasks.collect(tasks, timeoutMs, limit) { token != null && token != generation }
            .sortedBy { it.second }.map { it.first }
    }

    private fun httpGet(url: String): String? {
        var connection: java.net.HttpURLConnection? = null
        TorLog.add("bridge fetch: github $url")
        return try {
            connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3000
                readTimeout = 5000
                setRequestProperty("User-Agent", "Bebragram")
            }
            if (connection.responseCode != 200) {
                TorLog.add("bridge fetch: github HTTP ${connection.responseCode}")
                android.util.Log.d("BebragramTor", "github HTTP ${connection.responseCode}: $url")
                return null
            }
            connection.inputStream.bufferedReader().use { reader ->
                val buffer = CharArray(8192)
                val result = StringBuilder()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(7)
                while (result.length <= 1_000_000 && !Thread.currentThread().isInterrupted && System.nanoTime() < deadline) {
                    val count = reader.read(buffer)
                    if (count < 0) return@use result.toString()
                    result.append(buffer, 0, count)
                }
                TorLog.add("bridge fetch: list too large or request budget exhausted")
                null
            }
        } catch (e: Exception) {
            TorLog.add("bridge fetch: github failed (${e.javaClass.simpleName})")
            android.util.Log.d("BebragramTor", "github failed: ${e.javaClass.simpleName} ${e.message} $url")
            null
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    private fun valueOf(line: String, key: String): String =
        line.trim().split(Regex("\\s+")).firstOrNull { it.startsWith("$key=") }?.substringAfter('=') ?: ""
}
