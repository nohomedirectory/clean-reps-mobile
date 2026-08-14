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

gradle_log="$(mktemp "${TMPDIR:-/tmp}/clean-reps-mobile-gradle.XXXXXX")"
chmod 600 "$gradle_log"
cleanup() { rm -f "$gradle_log"; }
trap cleanup EXIT

# Keep the complete log private and emit a compact, redacted failure context at
# the end. The AX41 autopilot intentionally retains only the command tail, so
# this makes compiler/AAPT causes actionable without emitting runtime config.
set +e
./gradlew "${gradle_args[@]}" >"$gradle_log" 2>&1
gradle_status=$?
set -e
if [[ $gradle_status -ne 0 ]]; then
  printf 'GRADLE_FAILURE_CONTEXT_BEGIN\n'
  grep -E -B 12 -A 80 '(^FAILURE:|^\* What went wrong:|^e: |^ERROR:|^Caused by:|^> Task )' "$gradle_log" \
    | tail -n 200 \
    | sed -E \
        -e 's#srt://[^[:space:]]+#srt://<redacted>#g' \
        -e 's#(passphrase|streamid|MEDIAMTX_SRT_PASSPHRASE|MEDIAMTX_PUBLISH_PASSWORD)[=:][^[:space:]&]+#\1=<redacted>#g' \
        -e 's#(CHALLENGE_API_BASE_URL)[=:][^[:space:]]+#\1=<redacted>#g' \
    || true
  printf 'GRADLE_FAILURE_CONTEXT_END\n'
  exit "$gradle_status"
fi

apk_path="app/build/outputs/apk/debug/app-debug.apk"
digest_path="${apk_path}.sha256"
test -s "$apk_path"
sha256sum "$apk_path" > "$digest_path"
printf 'APK=%s\nSHA256=%s\n' "$repo_root/$apk_path" "$(cut -d ' ' -f 1 "$digest_path")"
