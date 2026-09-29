package IPtProxy
interface OnTransportEvents {
    fun connected(name: String?)
    fun error(name: String?, error: Exception?)
    fun stopped(name: String?, error: Exception?)
}
class Controller(path: String, a: Boolean, b: Boolean, log: String, events: OnTransportEvents) {
    var snowflakeBrokerUrl = ""
    var snowflakeFrontDomains = ""
    var snowflakeIceServers = ""
    var snowflakeAmpCacheUrl = ""
    fun start(name: String, proxy: String?) {}
    fun stop(name: String) {}
    fun port(name: String) = 39499L
}
