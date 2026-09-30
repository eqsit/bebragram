import tw.nekomimi.nekogram.tor.TorBridgeConfig
import tw.nekomimi.nekogram.tor.TorBridgeTasks
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

fun testBridgeValidationAndBudgets() {
    val bridge = "webtunnel [2001:db8::1]:443 0123456789ABCDEF0123456789ABCDEF01234567 url=https://bridge.example/path"
    check(TorBridgeConfig.lines("webtunnel", "# comment\nBridge\t$bridge\n$bridge").single() == bridge)
    val invalid = listOf(
        "webtunnel", "webtunnel 999.0.0.1:443 url=https://bridge.example/path",
        "webtunnel 192.0.2.1:0 url=https://bridge.example/path",
        "webtunnel 192.0.2.1:65536 url=https://bridge.example/path",
        "webtunnel 192.0.2.1:443 badfingerprint url=https://bridge.example/path",
        "webtunnel 192.0.2.1:443", "$bridge url=https://duplicate.example/path",
        "$bridge cert=dummy", "$bridge\u0000", "$bridge\nSocksPort 0",
        "webtunnel 192.0.2.1:443 url=file:///tmp/bridge",
        "webtunnel 192.0.2.1:443 url=https://user:password@bridge.example/path",
        "snowflake 192.0.2.1:443 url=https://bridge.example/"
    )
    invalid.forEach { line ->
        check(runCatching { TorBridgeConfig.lines("webtunnel", line) }.isFailure) { "Accepted invalid bridge: $line" }
    }
    check(runCatching { TorBridgeConfig.transports("snowflake", "snowflake 192.0.2.1:443") }.isFailure)
    println("PASS: bridge validation, comments, whitespace, deduplication and retired transport rejection")

    val gate = CountDownLatch(1)
    var start = System.nanoTime()
    val fast = TorBridgeTasks.collect(listOf(
        Callable<String> { gate.await(); "slow" },
        Callable<String> { "fast" }
    ), 2_000, 1)
    check(fast == listOf("fast"))
    check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1_000)
    start = System.nanoTime()
    val stalled = TorBridgeTasks.collect((1..12).map { Callable<String> { gate.await(); "stalled" } }, 150, 3)
    check(stalled.isEmpty())
    check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1_000) { "Timeout multiplied by bridge count" }
    gate.countDown()
    println("PASS: first completed result wins; all stalled checks share one deadline")
}
