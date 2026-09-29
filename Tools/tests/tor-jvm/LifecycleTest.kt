import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.torproject.jni.TorService
import tw.nekomimi.nekogram.tor.TorConfig
import tw.nekomimi.nekogram.tor.TorProxyHelper
import tw.nekomimi.nekogram.utils.ProxyUtil

class TestBinder(private val broken: Boolean = false) : IBinder {
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
                response.writeString(if(request.readString() == "status/bootstrap-phase") "PROGRESS=100" else "1")
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
        val app = Context()
        ApplicationLoader.applicationContext = app
        check(TorConfig.autostart) { "Autostart must default to true" }
        TorConfig.autostart = false
        check(!TorConfig.autostart) { "Explicit opt-out must survive" }
        TorConfig.setBridgesFor("webtunnel", "webtunnel 192.0.2.1:443 cert=dummy url=https://example.invalid/")
        TorProxyHelper.init(app)
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
        println("All Tor lifecycle regression scenarios passed")
        kotlin.system.exitProcess(0)
    } catch(t: Throwable) {
        t.printStackTrace()
        kotlin.system.exitProcess(1)
    }
}
