package IPtProxy
interface OnTransportEvents {
    fun connected(name: String?)
    fun error(name: String?, error: Exception?)
    fun stopped(name: String?, error: Exception?)
}
class Controller(path: String, a: Boolean, b: Boolean, log: String, events: OnTransportEvents) {
    companion object { var listener: OnTransportEvents? = null }
    init { listener = events }
    fun probeWebtunnel(line: String, timeout: Long) = 1L
    fun beginProbeBatch() = 1L
    fun endProbeBatch(batch: Long) {}
    fun probeWebtunnelInBatch(line: String, timeout: Long, batch: Long) = probeWebtunnel(line, timeout)
    fun start(name: String, proxy: String?) {}
    fun stop(name: String) {}
    fun port(name: String) = 39499L
}
