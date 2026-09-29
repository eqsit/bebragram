#!/usr/bin/env bash
set -euo pipefail
repo_dir=$(cd "$(dirname "$0")/../../.." && pwd)
test_dir="$repo_dir/Tools/tests/tor-jvm"
classes_dir=$(mktemp -d)
trap 'rm -rf "$classes_dir"' EXIT
javac -d "$classes_dir" "$repo_dir/TMessagesProj/src/main/java/tw/nekomimi/nekogram/tor/TorBridgeConfig.java"
"${KOTLINC:-kotlinc}" "$test_dir/stubs/"*.kt "$test_dir/LifecycleTest.kt" \
    "$repo_dir/TMessagesProj/src/main/kotlin/tw/nekomimi/nekogram/tor/TorConfig.kt" \
    "$repo_dir/TMessagesProj/src/main/kotlin/tw/nekomimi/nekogram/tor/TorHostService.kt" \
    "$repo_dir/TMessagesProj/src/main/kotlin/tw/nekomimi/nekogram/tor/TorLog.kt" \
    "$repo_dir/TMessagesProj/src/main/kotlin/tw/nekomimi/nekogram/tor/TorRemote.kt" \
    "$repo_dir/TMessagesProj/src/main/kotlin/tw/nekomimi/nekogram/tor/TorProxyHelper.kt" \
    -nowarn -classpath "$classes_dir" -include-runtime -d "$classes_dir/tests.jar"
java -cp "$classes_dir/tests.jar:$classes_dir" LifecycleTestKt
