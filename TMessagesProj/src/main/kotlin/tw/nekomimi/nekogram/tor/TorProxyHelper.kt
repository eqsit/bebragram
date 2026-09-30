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
    @Volatile private var attemptStartedAt = 0L
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
    private const val SELECTED_BRIDGES = 6
    private const val HEALTH_CHECK_MS = 15_000L
    private const val NETWORK_SETTLE_MS = 250L
    private const val BOOTSTRAP_STALL_MS = 20_000L
    private const val BOOTSTRAP_TIMEOUT_MS = 240_000L
    private const val CIRCUIT_RECOVERY_MS = 30_000L
    @Volatile private var activeBridgeLines = ""
    @Volatile private var activeBridgeMode = ""
    @Volatile private var activeAutoBridges = false
    private var healthCheckScheduled = false
    private var healthCheckUrgent = false
    private var healthCheckInFlight = false
    private var missedHealthChecks = 0
    private var circuitMissingSince = -1L
    private var recoveryRetry: Runnable? = null
    private var recoveryBackoffMs = 30_000L
    @Volatile private var defaultNetwork: Network? = null
    @Volatile private var networkChanged = false
    private var reconnectWhenOnline = false
    private var reconnectWhenVpnOff = false
    @Volatile private var failedBridgeLines = emptySet<String>()
    private data class BridgeRefresh(
        val mode: String, val token: Int,
        var restartStarting: Boolean, var recovery: Boolean,
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
    @Volatile var telegramPingMs = -1L
        private set
    @Volatile var checkingTelegram = false
        private set
    private var pingRequest = 0
    private var lastTelegramState = -1
    private var proxyStartedAt = 0L

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
        override fun stopped(name: String?, error: Exception?) {
            TorLog.add("transport $name: stopped ${error?.message ?: ""}")
            // One transport socket may close while another bridge still works.
            // Verify live circuits before replacing a healthy Tor session.
            AndroidUtilities.runOnUIThread { scheduleHealthCheck(250) }
        }
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
            }, BOOTSTRAP_TIMEOUT_MS)
            controlWorker.execute {
                try {
                    var lastPhase = ""
                    var highestProgress = -1
                    var lastProgressAt = SystemClock.elapsedRealtime()
                    var lastWaitLogAt = lastProgressAt
                    var lastReadBytes = 0L
                    var lastTrafficPollAt = -1L
                    for (i in 0 until 1200) {
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
                            val trafficNow = SystemClock.elapsedRealtime()
                            // Consensus/descriptors can download without changing the percentage.
                            // Restarting at 25% repeatedly throws away a productive cold bootstrap.
                            if (highestProgress in 25..79 && trafficNow - lastTrafficPollAt >= 1_000) {
                                lastTrafficPollAt = trafficNow
                                val readBytes = tor.getInfo("traffic/read")?.toLongOrNull() ?: lastReadBytes
                                if (readBytes - lastReadBytes >= 4_096) {
                                    lastReadBytes = readBytes
                                    lastProgressAt = trafficNow
                                }
                            }
                            // Poll the control port instead of relying on broadcasts, which can be missed.
                            val port = tor.socksPort
                            if (tor.getInfo("status/circuit-established") == "1" && port in 1..65535 && hasBuiltCircuit(tor)) {
                                progress = 100
                                TorLog.add("circuit established in ${SystemClock.elapsedRealtime() - attemptStartedAt}ms, tor SOCKS port $port")
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
                            val stallBudget = when (highestProgress) {
                                in 25..79 -> 60_000L
                                in 80..100 -> 45_000L
                                else -> BOOTSTRAP_STALL_MS
                            }
                            if (activeAutoBridges && highestProgress in 0..100 &&
                                SystemClock.elapsedRealtime() - lastProgressAt >= stallBudget) {
                                AndroidUtilities.runOnUIThread {
                                    if (token == generation) fail("Tor bootstrap stalled at $highestProgress%")
                                }
                                return@execute
                            }
                        }
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastWaitLogAt >= 5_000) {
                            lastWaitLogAt = now
                            TorLog.add(if (highestProgress < 0) "waiting for Tor control connection" else
                                "bootstrap waiting at $highestProgress% (${now - lastProgressAt}ms without progress, $lastReadBytes bytes received)")
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
            scheduleNetworkAction()
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                scheduleNetworkAction()
            }
        }

        override fun onLost(network: Network) {
            if (defaultNetwork == network) {
                defaultNetwork = null
                networkChanged = true
                scheduleNetworkAction()
            }
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

    private val networkAction = Runnable {
        if (!::app.isInitialized) return@Runnable
        val wasVpnPaused = reconnectWhenVpnOff
        onVpnPreferenceChanged()
        if (ProxyUtil.isVpnProxySuppressionActive() || wasVpnPaused) {
            networkChanged = false
            return@Runnable
        }
        if (!hasInternet()) {
            if (TorConfig.enabled && (status == "ON" || status == "STARTING")) {
                val reusable = currentBridges()
                TorLog.add("network lost; waiting for internet")
                stopInternal(resetRecovery = false)
                pendingNetworkBridges = reusable
                TorConfig.enabled = true
                reconnectWhenOnline = true
                if (status == "OFF") status = "WAITING_FOR_NETWORK"
            }
            return@Runnable
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
        maybeAutoRefresh()
    }

    private fun scheduleNetworkAction() {
        AndroidUtilities.runOnUIThread {
            if (!networkChanged && !reconnectWhenOnline && !reconnectWhenVpnOff &&
                !ProxyUtil.isVpnProxySuppressionActive()) return@runOnUIThread
            AndroidUtilities.cancelRunOnUIThread(networkAction)
            AndroidUtilities.runOnUIThread(networkAction, NETWORK_SETTLE_MS)
        }
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
        recoveryRetry?.let { AndroidUtilities.cancelRunOnUIThread(it) }
        recoveryRetry = null
        val token = ++generation
        attemptStartedAt = SystemClock.elapsedRealtime()
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
                activeBridgeMode = mode
                TorLog.add("start: mode=$mode")
                val bridges = if (networkBridges != null && networkBridges.first == mode) {
                    activeAutoBridges = networkBridges.third
                    val lines = if (networkBridges.third) {
                        recheckNetworkBridges(mode, networkBridges.second, token)
                    } else networkBridges.second
                    activeBridgeLines = lines
                    TorLog.add("bridges: reusing reachable session candidates after network change")
                    lines
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

    /** Explicit restart uses saved settings and never waits for another bridge download. */
    @JvmStatic
    fun restart() {
        if (!::app.isInitialized) return
        rotationAttempts = 0
        failedBridgeLines = emptySet()
        if (status == "STOPPING") {
            start()
        } else {
            restartForNewBridges()
        }
    }

    private fun stopInternal(resetRecovery: Boolean) {
        if (resetRecovery) {
            rotationAttempts = 0
            failedBridgeLines = emptySet()
            recoveryBackoffMs = 30_000L
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
        healthCheckUrgent = false
        healthCheckInFlight = false
        missedHealthChecks = 0
        circuitMissingSince = -1L
        telegramPingMs = -1L
        checkingTelegram = false
        ++pingRequest
        recoveryRetry?.let { AndroidUtilities.cancelRunOnUIThread(it) }
        recoveryRetry = null
        AndroidUtilities.cancelRunOnUIThread(healthTick)
        AndroidUtilities.cancelRunOnUIThread(networkAction)
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
        val reusable = currentBridges()
        TorLog.add("VPN active; pausing Tor")
        stopInternal(resetRecovery = false)
        pendingNetworkBridges = reusable
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

    private fun hasBuiltCircuit(tor: TorRemote): Boolean =
        TorCircuitStatus.hasUsableCircuit(tor.getInfo("circuit-status"))

    /** Check Tor's current circuits; bootstrap's historical success flag alone is insufficient. */
    private val healthTick = object : Runnable {
        override fun run() {
            healthCheckScheduled = false
            healthCheckUrgent = false
            if (status != "ON" || !TorConfig.enabled) return
            if (healthCheckInFlight) return
            val token = generation
            val tor = service ?: return
            healthCheckInFlight = true
            controlWorker.execute {
                val established = runCatching { hasBuiltCircuit(tor) }.getOrDefault(false)
                // Tor may deliberately stop building circuits when unused. This is
                // not a broken bridge and must not create a background restart loop.
                val dormant = !established && runCatching {
                    (tor.getInfo("dormant")?.toIntOrNull() ?: 0) > 0
                }.getOrDefault(false)
                AndroidUtilities.runOnUIThread {
                    if (token != generation || status != "ON") return@runOnUIThread
                    healthCheckInFlight = false
                    logTelegramState()
                    missedHealthChecks = if (established || dormant) 0 else missedHealthChecks + 1
                    val now = SystemClock.elapsedRealtime()
                    if (established || dormant) circuitMissingSince = -1L
                    else if (circuitMissingSince < 0) {
                        circuitMissingSince = now
                        TorLog.add("no live circuit; allowing Tor to rebuild before restarting")
                    }
                    if (circuitMissingSince >= 0 && now - circuitMissingSince >= CIRCUIT_RECOVERY_MS) fail("Tor circuit lost")
                    else scheduleHealthCheck(when {
                        dormant -> 30_000
                        established -> HEALTH_CHECK_MS
                        else -> 5_000L
                    })
                }
            }
        }
    }

    private fun scheduleHealthCheck(delayMs: Long = HEALTH_CHECK_MS) {
        if (status != "ON" || healthCheckInFlight) return
        val urgent = delayMs < HEALTH_CHECK_MS
        if (healthCheckScheduled && (!urgent || healthCheckUrgent)) return
        AndroidUtilities.cancelRunOnUIThread(healthTick)
        healthCheckScheduled = true
        healthCheckUrgent = urgent
        AndroidUtilities.runOnUIThread(healthTick, delayMs)
    }

    /** Try different candidates; a Tor/transport failure cannot identify which bridge failed. */
    private fun maybeRotateBridges(reason: String) {
        if (!activeAutoBridges) return
        val mode = TorConfig.mode
        if (mode == "direct" || mode == "custom") return
        if (rotationAttempts >= 2) {
            scheduleRecoveryRetry()
            return
        }
        val lines = activeBridgeLines
        if (lines.isBlank()) return
        failedBridgeLines = failedBridgeLines + lines.lineSequence().filter { it.isNotBlank() }.toSet()
        rotationAttempts++
        TorLog.add("bridges: retry $rotationAttempts after \"$reason\" (previous lines excluded)")
        refreshBridgesInternal(restartStarting = true, callback = null, recovery = true)
    }

    /** Keep recovering after a bad batch, with a pause rather than a tight restart loop. */
    private fun scheduleRecoveryRetry() {
        if (!TorConfig.enabled || !activeAutoBridges || recoveryRetry != null) return
        val token = generation
        val retry = Runnable {
            recoveryRetry = null
            if (token != generation || !TorConfig.enabled || !status.startsWith("ERROR:")) return@Runnable
            if (ProxyUtil.isVpnProxySuppressionActive()) {
                reconnectWhenVpnOff = true
                status = "WAITING_FOR_VPN"
                return@Runnable
            }
            if (!hasInternet()) {
                reconnectWhenOnline = true
                status = "WAITING_FOR_NETWORK"
                return@Runnable
            }
            rotationAttempts = 0
            failedBridgeLines = emptySet()
            TorLog.add("bridges: resuming recovery after cooldown")
            refreshBridgesInternal(restartStarting = true, callback = null, recovery = true)
        }
        recoveryRetry = retry
        val delay = recoveryBackoffMs
        recoveryBackoffMs = minOf(300_000L, delay * 2)
        TorLog.add("bridges: retry scheduled in ${delay / 1000}s")
        AndroidUtilities.runOnUIThread(retry, delay)
    }

    @JvmStatic fun isTorProxy(proxy: SharedConfig.ProxyInfo): Boolean = proxy == localProxy

    /** User-requested Telegram round trip, not a repeating network probe. */
    fun checkTelegramConnection() {
        val proxy = localProxy ?: return
        if (status != "ON" || checkingTelegram) return
        val token = generation
        val request = ++pingRequest
        checkingTelegram = true
        telegramPingMs = -1
        fun complete(time: Long) {
            if (token != generation || request != pingRequest || localProxy !== proxy || !checkingTelegram) return
            checkingTelegram = false
            telegramPingMs = time
            TorLog.add(if (time >= 0) "Telegram round trip through Tor: ${time}ms" else
                "Telegram round trip through Tor failed or timed out")
        }
        try {
            ConnectionsManager.getInstance(org.telegram.messenger.UserConfig.selectedAccount)
                .checkProxy(proxy.address, proxy.port, "", "", "") { time ->
                    AndroidUtilities.runOnUIThread { complete(time) }
                }
            AndroidUtilities.runOnUIThread({ complete(-1) }, 30_000L)
        } catch (e: Exception) {
            complete(-1)
        }
    }

    private fun logTelegramState() {
        val state = ConnectionsManager.getInstance(org.telegram.messenger.UserConfig.selectedAccount).connectionState
        if (state == lastTelegramState) return
        lastTelegramState = state
        val name = when (state) {
            ConnectionsManager.ConnectionStateConnected -> "connected"
            ConnectionsManager.ConnectionStateUpdating -> "updating"
            ConnectionsManager.ConnectionStateWaitingForNetwork -> "waiting for network"
            else -> "connecting"
        }
        TorLog.add("Telegram state: $name (${SystemClock.elapsedRealtime() - proxyStartedAt}ms after proxy selected)")
    }

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
        recoveryBackoffMs = 30_000L
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
        proxyStartedAt = SystemClock.elapsedRealtime()
        lastTelegramState = -1
        logTelegramState()
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

    private fun refreshBridgesInternal(restartStarting: Boolean, callback: java.util.function.IntConsumer?, recovery: Boolean = false) {
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
            existing.recovery = existing.recovery || recovery
            callback?.let { existing.callbacks.add(it) }
            return
        }
        val token = generation
        val previousLines = TorConfig.bridgesFor(mode)
        val request = BridgeRefresh(mode, token, restartStarting, recovery)
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
                val standby = if (request.recovery) cachedStandbyBridges(mode, token) else null
                standby ?: githubBridges(mode, token)
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
                    if (request.recovery && token == generation) scheduleRecoveryRetry()
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

    private fun currentBridges(): Triple<String, String, Boolean>? =
        if (activeBridgeMode == TorConfig.mode && activeBridgeLines.isNotBlank()) {
            Triple(TorConfig.mode, activeBridgeLines, activeAutoBridges)
        } else pendingNetworkBridges

    private fun restartForNewBridges(keepCurrentBridges: Boolean = false) {
        val reusable = if (keepCurrentBridges) currentBridges() else null
        TorLog.add(if (reusable != null) "restarting Tor with the connected session's bridges" else "restarting Tor with refreshed bridges")
        stopInternal(resetRecovery = false)
        pendingNetworkBridges = reusable
        start()
    }

    private val autoRefreshTick = object : Runnable {
        override fun run() {
            maybeAutoRefresh()
            // Checking the timestamp is local; download only when the interval is due.
            AndroidUtilities.runOnUIThread(this, 60_000L)
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

    /** Standby candidates still need an endpoint check, but no list download on failure. */
    private fun cachedStandbyBridges(mode: String, token: Int): String? {
        val candidates = distinctEndpoints(standbyCandidates(mode)
            .filterNot { it in failedBridgeLines }.shuffled()).take(MAX_PING_CANDIDATES)
        if (candidates.isEmpty()) return null
        TorLog.add("bridges: checking ${candidates.size} standby candidates before fetching lists")
        return rankByPing(candidates, SELECTED_BRIDGES, token = token)
            .joinToString("\n").takeIf { it.isNotBlank() }
    }

    private fun standbyCandidates(mode: String): List<String> {
        val raw = app.getSharedPreferences("bebragram", Context.MODE_PRIVATE)
            .getString("${cacheKey(mode)}_standby", null) ?: return emptyList()
        val split = raw.indexOf('|')
        if (split <= 0) return emptyList()
        val age = System.currentTimeMillis() - (raw.substring(0, split).toLongOrNull() ?: return emptyList())
        if (age !in 0..BRIDGE_CACHE_TTL_MS) return emptyList()
        return validBridgeLines(mode, raw.substring(split + 1))
    }

    /** A successful LTE probe says nothing about the same endpoint on Wi-Fi. */
    private fun recheckNetworkBridges(mode: String, previous: String, token: Int): String {
        failedBridgeLines = emptySet()
        rotationAttempts = 0
        val candidates = distinctEndpoints(validBridgeLines(mode, previous) + standbyCandidates(mode).shuffled())
            .take(MAX_PING_CANDIDATES)
        TorLog.add("bridges: checking ${candidates.size} session/standby endpoints on the new network")
        // Two responsive endpoints suffice for a quick handover. Waiting for six
        // forces every switch to pay dead endpoints' full timeout, even with a
        // working pair already available. The larger standby pool stays cached.
        val checked = rankByPing(candidates, minOf(2, candidates.size), token = token).joinToString("\n")
        if (token != generation) throw java.util.concurrent.CancellationException("Network check cancelled")
        val lines = checked.ifBlank {
            TorLog.add("bridges: saved endpoints unavailable on the new network; fetching fresh candidates")
            githubBridges(mode, token) ?: throw IllegalStateException("No reachable bridges on the new network")
        }
        cacheBridges(mode, lines)
        AndroidUtilities.runOnUIThread {
            if (token == generation && TorConfig.mode == mode && TorConfig.autoManagedFor(mode)) {
                TorConfig.setBridgesFor(mode, lines)
            }
        }
        return lines
    }

    /** Bridge sources that are reachable from Russia: GitHub raw + jsDelivr mirror. */
    private val githubSources = listOf(
        "https://cdn.jsdelivr.net/gh/Delta-Kronecker/Tor-Bridges-Collector@main/",
        "https://raw.githubusercontent.com/Delta-Kronecker/Tor-Bridges-Collector/main/",
    )

    /**
     * Sample up to 12 distinct endpoints from GitHub lists and keep six responsive ones.
     * This is only an endpoint check; Tor bootstrap is the actual bridge check.
     */
    private fun githubBridges(mode: String, token: Int? = null): String? {
        val files = when (mode) {
            "webtunnel" -> listOf("bridge/webtunnel.txt", "bridge/webtunnel_72h.txt")
            else -> return null
        }
        val selected = LinkedHashSet<String>()
        val allCandidates = LinkedHashSet<String>()
        fun saveStandby() {
            val selectedHosts = selected.map { bridgeEndpoint(it) }.toSet()
            val standby = distinctEndpoints(allCandidates.filterNot { bridgeEndpoint(it) in selectedHosts }
                .shuffled()).take(48).joinToString("\n")
            app.getSharedPreferences("bebragram", Context.MODE_PRIVATE).edit {
                putString("${cacheKey(mode)}_standby", "${System.currentTimeMillis()}|$standby")
            }
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
            allCandidates.addAll(pool)
            val selectedEndpoints = selected.map { bridgeEndpoint(it) }.toSet()
            val candidates = distinctEndpoints(pool.filterNot { bridgeEndpoint(it) in selectedEndpoints }
                .shuffled()).take(MAX_PING_CANDIDATES)
            val probeMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (probeMs <= 0) break
            val ranked = rankByPing(candidates, SELECTED_BRIDGES, minOf(4_000L, probeMs), token)
            if (ranked.isNotEmpty()) {
                selected.addAll(ranked)
                TorLog.add("bridges: ${pool.size} valid candidates, ${ranked.size} selected for $mode; bootstrap will verify them")
                if (selected.size >= SELECTED_BRIDGES) {
                    saveStandby()
                    return selected.take(SELECTED_BRIDGES).joinToString("\n")
                }
                TorLog.add("bridges: only ${selected.size} candidates so far; checking the other list")
                continue
            }
            TorLog.add("bridges: no reachable $mode endpoints in $file; trying fallback")
        }
        if (selected.isNotEmpty()) saveStandby()
        return selected.joinToString("\n").takeIf { it.isNotBlank() }
    }

    private fun validBridgeLines(mode: String, text: String): List<String> = text.lineSequence()
        .flatMap { runCatching { TorBridgeConfig.lines(mode, it).asSequence() }.getOrDefault(emptySequence()) }
        .distinct().toList()

    private fun bridgeEndpoint(line: String): String = line.split(Regex("\\s+"))
        .firstOrNull { it.startsWith("url=") }?.let {
            runCatching { java.net.URI(it.substringAfter('=')).host?.lowercase() }.getOrNull()
        } ?: line

    private fun distinctEndpoints(lines: List<String>): List<String> = lines.distinctBy { bridgeEndpoint(it) }

    /** Probe the production TLS/HTTP-upgrade path; Tor bootstrap still verifies the Tor relay. */
    private fun checkEndpoint(line: String, batch: Long): Long? =
        runCatching { controller.probeWebtunnelInBatch(line, 3_500, batch) }.getOrNull()

    /** Returns only endpoints that answered the check, fastest first. */
    private fun rankByPing(candidates: List<String>, limit: Int, timeoutMs: Long = 4_000, token: Int? = null): List<String> {
        if (candidates.isEmpty()) return emptyList()
        val batch = controller.beginProbeBatch()
        val tasks = candidates.distinct().map { line ->
            java.util.concurrent.Callable { checkEndpoint(line, batch)?.let { line to it } }
        }
        // One deadline for the entire batch, collect in completion order, and return
        // as soon as enough endpoints answer instead of waiting for every dead bridge.
        val start = SystemClock.elapsedRealtime()
        val ranked = try {
            TorBridgeTasks.collect(tasks, timeoutMs, limit) { token != null && token != generation }
                .sortedBy { it.second }.map { it.first }
        } finally {
            controller.endProbeBatch(batch)
        }
        TorLog.add("bridges: ${ranked.size}/${tasks.size} WebTunnel handshakes passed in ${SystemClock.elapsedRealtime() - start}ms")
        return ranked
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

}
