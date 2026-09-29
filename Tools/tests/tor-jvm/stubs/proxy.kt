package tw.nekomimi.nekogram.utils
object ProxyUtil {
    var vpn = false
    fun isVpnProxySuppressionActive() = vpn
    fun recheckProxyState() {}
}
