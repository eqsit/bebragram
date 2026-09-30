# Tor lifecycle regression harness

Run `Tools/tests/tor-jvm/run.sh` with JDK 17+ and Kotlin 2.2.20 or later (`KOTLINC`
can point to the compiler; the Android app itself still requires JDK 21). It compiles the actual Tor helper/config/remote/service
sources and runs them with small JVM doubles for Android, IPtProxy and Telegram.

Checks: stop before service connection; restart only after Binder death; duplicate
old death and stale error broadcasts; no synchronous Binder IPC on UI; cancellation
of a queued restart; control exceptions; unexpected process death; VPN pause/resume;
default autostart and explicit opt-out; bridge downloads preserve an established
connection; manual refresh retries an unfinished bootstrap; network changes reuse
the connected session's bridges; automatic bootstrap stalls retry after 45 seconds.
HTTPS requests and the monotonic clock are stubbed locally. Android/device behavior still needs an APK
smoke test; this harness does not simulate Android's process manager or native Tor.

Bridge regressions also cover malformed addresses/ports/fingerprints/URLs/certificate
hashes, directive injection, comments and whitespace, duplicate lines, retired
Snowflake preferences, one deadline for a batch of stalled endpoints, fast results
behind slow requests, coalesced manual refresh, cancellation without waiting for
the network, and null service bindings. Network stubs intentionally do not report
Android VALIDATED, to check that blocked connectivity probes do not prevent Tor.
