package IPtProxy
interface OnTransportEvents {
    fun connected(name: String?)
    fun error(name: String?, error: Exception?)
    fun stopped(name: String?, error: Exception?)
}
class Controller(path: String, a: Boolean, b: Boolean, log: String, events: OnTransportEvents) {
    companion object {
        var listener: OnTransportEvents? = null
        var blockedHost: String? = null
        var slowHost: String? = null
        val probed = java.util.concurrent.CopyOnWriteArrayList<String>()
    }
    init { listener = events }
    fun probeWebtunnel(line: String, timeout: Long): Long {
        probed.add(line)
        if (slowHost?.let { line.contains(it) } == true) Thread.sleep(1_500)
        if (blockedHost?.let { line.contains(it) } == true) throw IllegalStateException("blocked on new network")
        return 1L
    }
    fun beginProbeBatch() = 1L
    fun endProbeBatch(batch: Long) {}
    fun probeWebtunnelInBatch(line: String, timeout: Long, batch: Long) = probeWebtunnel(line, timeout)
    fun start(name: String, proxy: String?) {}
    fun stop(name: String) {}
    fun port(name: String) = 39499L
}
