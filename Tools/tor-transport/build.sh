#!/usr/bin/env bash
set -euo pipefail
transport_dir=$(cd "$(dirname "$0")" && pwd)
output_file=${1:?Usage: build.sh OUTPUT.aar}
mkdir -p "$(dirname "$output_file")"
output_file=$(cd "$(dirname "$output_file")" && pwd)/$(basename "$output_file")
: "${ANDROID_HOME:=${ANDROID_SDK_ROOT:-}}"
: "${ANDROID_NDK_HOME:=$ANDROID_HOME/ndk/28.2.13676358}"
export ANDROID_HOME ANDROID_NDK_HOME
if [[ ! -f "$ANDROID_NDK_HOME/source.properties" ]]; then
    echo 'Install Android NDK 28.2.13676358 or set ANDROID_NDK_HOME.' >&2
    exit 1
fi
cd "$transport_dir/IPtProxy.go"
export GOTOOLCHAIN=go1.26.4
# Keep the mobile binding generator identical to the runtime included in the AAR.
mobile_version=v0.0.0-20260611195102-4dd8f1dbf5d2
export GOBIN="$(dirname "$output_file")/gomobile-bin"
export PATH="$GOBIN:$PATH"
mkdir -p "$GOBIN"
go mod download
go install "golang.org/x/mobile/cmd/gobind@$mobile_version"
go run "golang.org/x/mobile/cmd/gomobile@$mobile_version" init
go run "golang.org/x/mobile/cmd/gomobile@$mobile_version" bind \
    -target=android -androidapi=24 -ldflags='-s -w -checklinkname=0' \
    -tags=netcgo -trimpath -o "$output_file"
