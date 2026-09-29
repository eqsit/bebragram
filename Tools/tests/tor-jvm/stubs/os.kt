package android.os
class RemoteException(message: String = "dead") : RuntimeException(message)
interface IBinder {
    fun interface DeathRecipient { fun binderDied() }
    val isBinderAlive: Boolean
    fun linkToDeath(recipient: DeathRecipient, flags: Int)
    fun transact(code: Int, request: Parcel, response: Parcel, flags: Int): Boolean
}
open class Binder : IBinder {
    override val isBinderAlive = true
    override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) {}
    override fun transact(code: Int, request: Parcel, response: Parcel, flags: Int) = onTransact(code, request, response, flags)
    open fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int) = false
}
class Parcel {
    companion object { fun obtain() = Parcel() }
    private val values = mutableListOf<Any?>()
    private var cursor = 0
    fun writeInterfaceToken(token: String) { writeString(token) }
    fun enforceInterface(token: String) { check(readString() == token) }
    fun writeNoException() {}
    fun readException() {}
    fun writeInt(value: Int) { values.add(value) }
    fun readInt() = values[cursor++] as Int
    fun writeString(value: String?) { values.add(value) }
    fun readString() = values[cursor++] as String?
    fun recycle() {}
}
object Build { object VERSION { const val SDK_INT = 36 } }
object Process { fun myPid() = 42; fun killProcess(pid: Int) {} }
object SystemClock { fun elapsedRealtime() = org.telegram.messenger.AndroidUtilities.elapsedRealtime() }
