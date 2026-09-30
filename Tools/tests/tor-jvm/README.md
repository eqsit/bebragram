# Tor lifecycle regression harness

Run `Tools/tests/tor-jvm/run.sh` with JDK 17+ and Kotlin 2.2.20 or later (`KOTLINC`
can point to the compiler; the Android app itself still requires JDK 21). It compiles the actual Tor helper/config/remote/service
sources and runs them with small JVM doubles for Android, IPtProxy and Telegram.

Checks: stop before service connection; restart only after Binder death; duplicate
old death and stale error broadcasts; no synchronous Binder IPC on UI; cancellation
of a queued restart; control exceptions; unexpected process death; VPN pause/resume;
default autostart and explicit opt-out; bridge downloads preserve an established
connection; manual refresh retries an unfinished bootstrap; network changes reuse
the connected session's bridges; initial transport stalls retry after 20 seconds,
consensus/descriptor downloads get 60 seconds without progress (incoming data resets
that budget), and stalls at 80–100% retry after 45 seconds without progress.
HTTPS requests and the monotonic clock are stubbed locally. Android/device behavior still needs an APK
smoke test; this harness does not simulate Android's process manager or native Tor.

Bridge regressions also cover malformed addresses/ports/fingerprints/URLs/certificate
hashes, directive injection, comments and whitespace, duplicate lines, retired
Snowflake preferences, one deadline for a batch of stalled endpoints, fast results
behind slow requests, coalesced manual refresh, cancellation without waiting for
the network, and null service bindings. Network stubs intentionally do not report
Android VALIDATED, to check that blocked connectivity probes do not prevent Tor.

Recovery regressions cover 250 ms coalescing of network events, reuse of the live
session's bridges after VPN and offline/online transitions, one safe restart for
repeated Restart clicks, one-minute checks for due background refresh, preservation
of healthy circuits when one transport socket fails, live circuit checks despite
a stale bootstrap success flag, no false restart during deliberate Tor dormancy,
and standby selection without downloading a list. Brief circuit loss is allowed to
recover in the same process; a persistent loss triggers recovery after 30 seconds.

Further checks cover usable GENERAL and linked Conflux circuits (unlinked, internal
and one-hop circuits are rejected), replacing an endpoint blocked on the new network
with a reachable cached standby without downloading lists, and user-requested Telegram
round trips: repeated clicks share one check, timeout clears the pending state, and
late callbacks cannot affect a stopped/replacement session.
