# Bebragram

Telegram Android fork based on [NagramX](https://github.com/risin42/NagramX) with an
embedded Tor client, GitHub bridge lists and a customizable main menu.

## features

- **embedded Tor** — [tor-android](https://github.com/guardianproject/tor-android) + [IPtProxy](https://github.com/torproject/IPtProxy):
  WebTunnel and Snowflake modes, manual bridge lines, per-mode bridge memory, in-app Tor log
- **bridge auto-fetch from GitHub** — candidates are ping/HTTPS-checked, the fastest alive
  ones are used, dead hosts are remembered, and a failed bootstrap rotates bridges and restarts Tor
- **bridge auto-refresh** — off / 6 / 12 / 24 / 48 / 72 h / custom interval
- **VPN-aware proxy** — the configured proxy (Tor included) is disabled while a VPN is active
- **main menu editor** — show/hide, reorder (drag) and dividers for the “⋮” menu, opened right
  from the menu itself or from settings
- stock-like defaults: one-tap voice/round-video toggle, double-tap sends a quick reaction
- NagramX feature set (themes, ghost mode, translator, monet, etc.) is kept

## build

```sh
# local.properties must exist with sdk.dir and keystore credentials:
#   sdk.dir=/path/to/Android/Sdk
#   KEYSTORE_PASS=...
#   ALIAS_NAME=...
#   ALIAS_PASS=...

NATIVE_TARGET=universal ./gradlew :TMessagesProj:assembleRelease   # all ABIs
NATIVE_TARGET=arm64-v8a ./gradlew :TMessagesProj:assembleRelease   # arm64 only
```

Sign the release build with your own keystore placed at `TMessagesProj/release.keystore`.
APKs are written to `TMessagesProj/build/outputs/apk/release/`.

## credits

- [Telegram Android](https://github.com/DrKLO/Telegram) — the upstream client
- [NagramX](https://github.com/risin42/NagramX) — the base fork this project is built on
- [inugram](https://github.com/teidesu/inugram) — the Tor integration this fork ports
- bridge lists: [Delta-Kronecker/Tor-Bridges-Collector](https://github.com/Delta-Kronecker/Tor-Bridges-Collector)

## license

GPLv3 (see `LICENSE`). This is an unofficial fork, not affiliated with Telegram.
