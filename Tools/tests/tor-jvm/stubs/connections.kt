package org.telegram.tgnet
object ConnectionsManager {
    const val ConnectionStateConnected = 3
    const val ConnectionStateUpdating = 5
    const val ConnectionStateWaitingForNetwork = 2
    var connectionState = ConnectionStateConnected
    val pingCallbacks = mutableListOf<(Long) -> Unit>()
    fun getInstance(account: Int) = this
    fun checkProxy(address: String, port: Int, user: String, pass: String, secret: String, callback: (Long) -> Unit): Long {
        pingCallbacks.add(callback)
        return pingCallbacks.size.toLong()
    }
    fun setProxySettings(enabled: Boolean, address: String, port: Int, user: String, pass: String, secret: String) {}
}
