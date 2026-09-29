# Tor lifecycle regression harness

Run `Tools/tests/tor-jvm/run.sh` with JDK 21 and Kotlin 2.2.20 or later (`KOTLINC`
can point to the compiler). It compiles the actual Tor helper/config/remote/service
sources and runs them with small JVM doubles for Android, IPtProxy and Telegram.

Checks: stop before service connection; restart only after Binder death; duplicate
old death and stale error broadcasts; no synchronous Binder IPC on UI; cancellation
of a queued restart; control exceptions; unexpected process death; VPN pause/resume;
default autostart and explicit opt-out. Android/device behavior still needs an APK
smoke test; this harness does not simulate Android's process manager or native Tor.
