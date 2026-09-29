package tw.nekomimi.nekogram.tor

import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import org.torproject.jni.TorService

/** libtor cannot be started twice in one process. Give each Tor session a fresh process. */
class TorHostService : TorService() {
    private val remoteBinder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code !in 1..3) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(TorRemote.DESCRIPTOR)
            reply ?: return false
            reply.writeNoException()
            when (code) {
                1 -> reply.writeInt(if (torControlConnection != null) 1 else 0)
                2 -> reply.writeString(getInfo(data.readString() ?: ""))
                3 -> reply.writeInt(socksPort)
            }
            return true
        }
    }

    override fun onBind(intent: Intent?): IBinder = remoteBinder

    override fun onDestroy() {
        super.onDestroy()
        // Keep the native Tor globals out of the next session, even if Android caches the service process.
        Process.killProcess(Process.myPid())
    }
}
