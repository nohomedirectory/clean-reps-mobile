#!/usr/bin/env bash
# Reproducible non-Actions debug build. Configuration is injected only from the
# caller's environment and is never printed, committed, or copied into logs.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

if [[ -z "${ANDROID_SDK_ROOT:-}" || ! -x "${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin/sdkmanager" ]]; then
  "$repo_root/tools/bootstrap-android-sdk.sh"
  export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$repo_root/.android-sdk}"
fi
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$repo_root/.gradle-user-home}"

gradle_args=(--no-daemon --stacktrace :app:testDebugUnitTest :app:lintDebug :app:assembleDebug)
# Android's Gradle provider reads these directly from this process environment.
# Do not pass secrets as -P arguments: those can become visible in process lists.

./gradlew "${gradle_args[@]}"
apk_path="app/build/outputs/apk/debug/app-debug.apk"
digest_path="${apk_path}.sha256"
test -s "$apk_path"
sha256sum "$apk_path" > "$digest_path"
printf 'APK=%s\nSHA256=%s\n' "$repo_root/$apk_path" "$(cut -d ' ' -f 1 "$digest_path")"
