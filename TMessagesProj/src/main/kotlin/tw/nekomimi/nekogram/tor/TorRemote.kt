package tw.nekomimi.nekogram.tor

import android.os.IBinder
import android.os.Parcel

/** Small synchronous control interface to the dedicated Tor process. Calls run off the UI thread. */
class TorRemote(private val binder: IBinder) {
    companion object {
        const val DESCRIPTOR = "tw.nekomimi.nekogram.tor.control"
    }

    private fun <T> call(code: Int, write: (Parcel) -> Unit = {}, read: (Parcel) -> T): T {
        val request = Parcel.obtain()
        val response = Parcel.obtain()
        try {
            request.writeInterfaceToken(DESCRIPTOR)
            write(request)
            if (!binder.transact(code, request, response, 0)) throw IllegalStateException("Tor control unavailable")
            response.readException()
            return read(response)
        } finally {
            request.recycle()
            response.recycle()
        }
    }

    val controlReady: Boolean get() = call(1, read = { it.readInt() != 0 })
    fun getInfo(key: String): String? = call(2, write = { it.writeString(key) }, read = { it.readString() })
    val socksPort: Int get() = call(3, read = { it.readInt() })
}
