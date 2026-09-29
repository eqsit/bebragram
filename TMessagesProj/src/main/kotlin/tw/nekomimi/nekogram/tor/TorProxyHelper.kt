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
    private lateinit var app: Context
    private var generation = 0
    private var bound = false
    private var service: TorRemote? = null
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
    /** Hosts that failed ping or killed a bootstrap; never picked again this session. */
    private val badBridgeHosts = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private var rotationAttempts = 0
    private const val BRIDGE_CACHE_TTL_MS = 2L * 24 * 60 * 60 * 1000
    private var ownedProxy = false
    private var stoppedForOtherProxy = false
    private var startQueued = false
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
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (!bound) return
            val tor = binder?.let { TorRemote(it) } ?: return
            torBinder = binder
            try {
                binder.linkToDeath(torDeath, 0)
            } catch (e: android.os.RemoteException) {
                onTorProcessDied()
                return
            }
            service = tor
            val token = generation
            worker.execute {
                var lastPhase = ""
                for (i in 0 until 300) {
                    if (token != generation) return@execute
                    if (tor.controlReady) {
                        val phase = tor.getInfo("status/bootstrap-phase")
                        if (phase != null && phase != lastPhase) {
                            lastPhase = phase
                            Regex("PROGRESS=(\\d+)").find(phase)?.let { progress = it.groupValues[1].toInt() }
                            TorLog.add("bootstrap: $phase")
                        }
                        // Poll the control port instead of relying on broadcasts, which can be missed.
                        if (tor.getInfo("status/circuit-established") == "1" && tor.socksPort in 1..65535) {
                            progress = 100
                            TorLog.add("circuit established, tor SOCKS port ${tor.socksPort}")
                            AndroidUtilities.runOnUIThread {
                                if (token == generation) {
                                    if (ProxyUtil.isVpnProxySuppressionActive()) {
                                        pauseForVpn()
                                    } else {
                                        status = "ON"
                                        connectProxy(tor.socksPort)
                                        scheduleHealthCheck()
                                    }
                                }
                            }
                            return@execute
                        }
                    }
                    TimeUnit.MILLISECONDS.sleep(200)
                }
                AndroidUtilities.runOnUIThread {
                    if (token == generation) fail("Tor did not bootstrap" + if (progress >= 0) " ($progress%)" else "")
                }
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            AndroidUtilities.runOnUIThread {
                if (torBinder?.isBinderAlive == false) onTorProcessDied()
            }
        }
    }
    private val torDeath = IBinder.DeathRecipient {
        AndroidUtilities.runOnUIThread { onTorProcessDied() }
    }

    private fun onTorProcessDied() {
        torBinder = null
        service = null
        if (status == "STOPPING") {
            status = when {
                reconnectWhenVpnOff -> "WAITING_FOR_VPN"
                reconnectWhenOnline -> "WAITING_FOR_NETWORK"
                else -> "OFF"
            }
            if (startQueued) {
                startQueued = false
                start()
            } else if (reconnectWhenVpnOff && !ProxyUtil.isVpnProxySuppressionActive() && hasInternet()) {
                reconnectWhenVpnOff = false
                start()
            } else if (reconnectWhenOnline && hasInternet()) {
                reconnectWhenOnline = false
                start()
            }
        } else if (bound && TorConfig.enabled && (status == "ON" || status == "STARTING")) {
            fail("Tor process stopped")
        }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == TorService.ACTION_ERROR) {
                if (status != "STOPPING" && TorConfig.enabled) {
                    fail(intent.getStringExtra(Intent.EXTRA_TEXT) ?: "Tor error")
                }
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
            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                scheduleNetworkAction(network)
            }
        }

        override fun onLost(network: Network) {
            if (defaultNetwork == network) defaultNetwork = null
        }
    }

    private fun hasInternet(): Boolean {
        val connectivity = app.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = connectivity.activeNetwork ?: return false
        return connectivity.getNetworkCapabilities(network)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
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
                restartForNewBridges()
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
        if (torBinder != null && status != "ON" && status != "STARTING") {
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
        progress = -1
        lastError = null
        AndroidUtilities.runOnUIThread({
            if (token == generation && status == "STARTING") fail("Tor connection timed out")
        }, 45_000)
        worker.execute {
            try {
                val mode = TorConfig.mode
                TorLog.add("start: mode=$mode")
                val bridges = bridgeLines(mode)
                if (token != generation) {
                    TorLog.add("start cancelled before transports")
                    return@execute
                }
                val names = TorBridgeConfig.transports(mode, bridges)
                val ports = mutableMapOf<String, Long>()
                if (names.isNotEmpty()) {
                    if ("snowflake" in names) {
                        val sf = bridges.lineSequence().map { it.trim() }
                            .first { it.startsWith("snowflake ") || it.startsWith("snowflake\t") }
                        controller.snowflakeBrokerUrl = valueOf(sf, "url")
                        controller.snowflakeFrontDomains = valueOf(sf, "fronts").ifEmpty { valueOf(sf, "front") }
                        controller.snowflakeIceServers = valueOf(sf, "ice")
                        valueOf(sf, "ampcache").takeIf { it.isNotEmpty() }?.let { controller.snowflakeAmpCacheUrl = it }
                    }
                    for (name in names) {
                        if (token != generation) return@execute
                        if (name in startedTransports) continue
                        controller.start(name, null)
                        startedTransports.add(name)
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
                    bound = try {
                        app.bindService(Intent(app, TorHostService::class.java), connection, Context.BIND_AUTO_CREATE)
                    } catch (e: Exception) {
                        TorLog.add("bindService threw: ${e.message}")
                        false
                    }
                    TorLog.add("bindService -> $bound")
                    if (!bound) fail("Cannot start Tor service")
                }
            } catch (e: Exception) {
                TorLog.add("start failed: ${e.javaClass.simpleName}: ${e.message}")
                AndroidUtilities.runOnUIThread { if (token == generation) fail(e.localizedMessage ?: "Tor failed") }
            }
        }
    }

    fun stop() {
        TorLog.add("stop requested (status=$status)")
        TorConfig.enabled = false
        reconnectWhenOnline = false
        reconnectWhenVpnOff = false
        ++generation
        startQueued = false
        healthCheckScheduled = false
        missedHealthChecks = 0
        AndroidUtilities.cancelRunOnUIThread(healthTick)
        restoreProxy()
        stoppedForOtherProxy = false
        val wasBound = bound
        if (bound) { app.unbindService(connection); bound = false }
        service = null
        status = if (wasBound) "STOPPING" else "OFF"
        progress = -1
        if (wasBound) {
            val token = generation
            // Binder death confirms that the old native Tor instance is gone.
            AndroidUtilities.runOnUIThread({
                if (token == generation && status == "STOPPING" && !bound) {
                    if (torBinder == null) {
                        status = "OFF"
                        if (startQueued) { startQueued = false; start() }
                    } else {
                        lastError = "Tor process did not stop"
                        status = "ERROR: ${lastError}"
                        TorConfig.enabled = false
                        startQueued = false
                    }
                }
            }, 5_000)
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
        status = "ERROR: $reason"
        stop()
        if (offline) {
            TorConfig.enabled = true
            reconnectWhenOnline = true
            if (status != "STOPPING") status = "WAITING_FOR_NETWORK"
        } else {
            status = "ERROR: $reason"
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
            worker.execute {
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

    /** A dead bridge must not kill the connection: remember it, fetch fresh ones and retry. */
    private fun maybeRotateBridges(reason: String) {
        if (rotationAttempts >= 2 || !activeAutoBridges) return
        val mode = TorConfig.mode
        if (mode == "direct" || mode == "custom") return
        val lines = activeBridgeLines
        if (lines.isBlank()) return
        var added = false
        lines.lineSequence().forEach { line ->
            val host = pingTarget(line)?.first
            if (host != null && badBridgeHosts.add(host)) added = true
        }
        if (!added && badBridgeHosts.isNotEmpty()) return
        rotationAttempts++
        TorLog.add("bridges: rotating after \"$reason\" (bad hosts: ${badBridgeHosts.size})")
        val token = generation
        refreshBridges { count ->
            if (count > 0 && token == generation && mode == TorConfig.mode && !TorConfig.enabled) start()
        }
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
     * (auto-managed, so the scheduler may overwrite them later) and restarts Tor if needed.
     * Callback runs on the UI thread: line count, or -1 on failure.
     */
    @JvmStatic
    fun refreshBridges(callback: java.util.function.IntConsumer? = null) {
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
        worker.execute {
            val fresh = try {
                githubBridges(mode)
            } catch (e: Exception) {
                null
            }
            val lines = fresh ?: runCatching {
                rankByPing(bundledBridges(mode).lines().filter { line ->
                    pingTarget(line)?.first !in badBridgeHosts
                }.take(MAX_PING_CANDIDATES), SELECTED_BRIDGES).joinToString("\n")
            }.getOrNull()
            if (lines.isNullOrBlank()) {
                TorLog.add("bridge refresh failed: no $mode bridges")
                AndroidUtilities.runOnUIThread { callback?.accept(-1) }
                return@execute
            }
            val count = countBridges(lines)
            cacheBridges(mode, lines)
            TorConfig.setBridgesFor(mode, lines)
            TorConfig.setAutoManagedFor(mode, true)
            TorConfig.setLastBridgeRefreshFor(mode, System.currentTimeMillis())
            TorLog.add("bridges: refreshed $count $mode line(s)")
            AndroidUtilities.runOnUIThread {
                callback?.accept(count)
                if (TorConfig.enabled && TorConfig.mode == mode) restartForNewBridges()
            }
        }
    }

    private fun restartForNewBridges() {
        TorLog.add("restarting Tor with refreshed bridges")
        stop()
        AndroidUtilities.runOnUIThread { start() }
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
            refreshBridges()
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
        worker.execute {
            try {
                githubBridges(mode)?.let {
                    cacheBridges(mode, it)
                    TorLog.add("bridges: prefetched ${countBridges(it)} $mode line(s)")
                }
            } catch (e: Exception) {
                TorLog.add("bridge prefetch failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /** Resolve bridge lines for [mode]: manual input, then fresh GitHub lists, then bundled. */
    fun bridgeLines(mode: String): String {
        activeAutoBridges = false
        activeBridgeLines = ""
        if (mode == "direct") return ""
        val manual = TorConfig.bridgesFor(mode)
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
        githubBridges(mode)?.let {
            activeBridgeLines = it
            activeAutoBridges = true
            TorLog.add("bridges: fetched ${countBridges(it)} $mode line(s) from github")
            cacheBridges(mode, it)
            return it
        }
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
        return (0 until entries.length()).joinToString("\n") { entries.getString(it) }
    }

    private fun cachedBridges(mode: String): String? {
        val raw = app.getSharedPreferences("bebragram", Context.MODE_PRIVATE).getString(cacheKey(mode), null) ?: return null
        val split = raw.indexOf('|')
        if (split <= 0) return null
        val age = System.currentTimeMillis() - (raw.substring(0, split).toLongOrNull() ?: return null)
        if (age < 0 || age > BRIDGE_CACHE_TTL_MS) return null
        return raw.substring(split + 1).takeIf { it.isNotBlank() }
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
    private fun githubBridges(mode: String): String? {
        val files = when (mode) {
            "webtunnel" -> listOf("bridge/webtunnel_72h.txt", "bridge/webtunnel.txt")
            "snowflake" -> listOf("bridge/snowflake.txt")
            "obfs4" -> listOf("bridge/obfs4_72h.txt", "bridge/obfs4_tested.txt")
            else -> return null
        }
        for (base in githubSources) {
            val pool = LinkedHashMap<Pair<String, Int>, String>()
            for (file in files) {
                val text = httpGet(base + file) ?: continue
                text.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .map { if (it.startsWith("Bridge ")) it.substring(7).trim() else it }
                    .filter { it.startsWith("$mode ") }
                    .forEach { line ->
                        val endpoint = pingTarget(line)
                        if (endpoint != null && endpoint.first !in badBridgeHosts && pool.size < MAX_PING_CANDIDATES) {
                            pool.putIfAbsent(endpoint, line)
                        }
                    }
                if (pool.size >= MAX_PING_CANDIDATES) break
            }
            if (pool.isEmpty()) continue
            val ranked = rankByPing(pool.values.toList(), SELECTED_BRIDGES)
            if (ranked.isNotEmpty()) {
                TorLog.add("bridges: github ${pool.size} candidates, ${ranked.size} alive selected for $mode")
                return ranked.joinToString("\n")
            }
            TorLog.add("bridges: no alive $mode bridges in github list")
        }
        return null
    }

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

    /** WebTunnel and Snowflake connect through their url= host, not through the placeholder address. */
    private fun pingTarget(line: String): Pair<String, Int>? {
        val url = valueOf(line, "url").ifEmpty { valueOf(line, "ampcache") }
        if (url.isNotEmpty()) {
            return try {
                val uri = java.net.URI(url)
                val host = uri.host ?: return null
                host to (if (uri.port > 0) uri.port else 443)
            } catch (e: Exception) {
                null
            }
        }
        val addr = line.split(' ').getOrNull(1) ?: return null
        val host = addr.substringBeforeLast(':', addr).removeSurrounding("[", "]")
        val port = addr.substringAfterLast(':').toIntOrNull() ?: return null
        return host to port
    }

    /**
     * Real bridge check: an HTTPS request to the bridge endpoint (url=). Any HTTP answer
     * (the healthy WebTunnel fronts reply 502 to a plain GET) means the endpoint is alive;
     * DNS/TLS/timeout failures mean it is dead. Returns elapsed time or null.
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
    private fun rankByPing(candidates: List<String>, limit: Int): List<String> {
        if (candidates.isEmpty()) return emptyList()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(10)
        return try {
            candidates.map { line ->
                pool.submit(java.util.concurrent.Callable {
                    line to checkEndpoint(line)
                })
            }
                .mapNotNull { runCatching { it.get(4, java.util.concurrent.TimeUnit.SECONDS) }.getOrNull() }
                .filter { it.second != null }
                .sortedBy { it.second }
                .map { it.first }
                .take(limit)
        } catch (e: Exception) {
            emptyList()
        } finally {
            pool.shutdownNow()
        }
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
                while (result.length <= 1_000_000) {
                    val count = reader.read(buffer)
                    if (count < 0) return@use result.toString()
                    result.append(buffer, 0, count)
                }
                TorLog.add("bridge fetch: list too large")
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
        line.split(' ').firstOrNull { it.startsWith("$key=") }?.substringAfter('=') ?: ""
}
