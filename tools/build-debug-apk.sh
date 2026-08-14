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

# The private log retains normal Gradle diagnostics. Do not use --stacktrace
# here: its Java frames displace Kotlin/AAPT source errors from AX41's bounded
# result tail, while the script already emits a compact actionable summary.
gradle_args=(--no-daemon --console=plain :app:testDebugUnitTest :app:lintDebug :app:assembleDebug)
# Android's Gradle provider reads these directly from this process environment.
# Do not pass secrets as -P arguments: those can become visible in process lists.

gradle_log="$(mktemp "${TMPDIR:-/tmp}/clean-reps-mobile-gradle.XXXXXX")"
compiler_log="$(mktemp "${TMPDIR:-/tmp}/clean-reps-mobile-compile.XXXXXX")"
chmod 600 "$gradle_log"
chmod 600 "$compiler_log"
cleanup() { rm -f "$gradle_log" "$compiler_log"; }
trap cleanup EXIT

# Keep the complete log private and emit a compact, redacted failure context at
# the end. The AX41 autopilot intentionally retains only the command tail, so
# this makes compiler/AAPT causes actionable without emitting runtime config.
set +e
./gradlew "${gradle_args[@]}" >"$gradle_log" 2>&1
gradle_status=$?
set -e
if [[ $gradle_status -ne 0 ]]; then
  # A multi-task Gradle invocation can end in a large worker stack trace. Run
  # the compiler task alone without a stacktrace to capture its source-level
  # cause in a private log, then expose only the redacted compact extraction.
  set +e
  ./gradlew --no-daemon --console=plain :app:compileDebugKotlin >"$compiler_log" 2>&1
  compiler_status=$?
  set -e
  diagnostic_log="$compiler_log"
  if [[ $compiler_status -eq 0 ]]; then diagnostic_log="$gradle_log"; fi
  printf 'GRADLE_FAILURE_CONTEXT_BEGIN\n'
  # The coordination surface retains a short command tail. Keep the emitted
  # context to at most 20 redacted lines so the actual tool error survives.
  redact_gradle_log() {
    sed -E \
        -e 's#srt://[^[:space:]]+#srt://<redacted>#g' \
        -e 's#(passphrase|streamid|MEDIAMTX_SRT_PASSPHRASE|MEDIAMTX_PUBLISH_PASSWORD)[=:][^[:space:]&]+#\1=<redacted>#g' \
        -e 's#(CHALLENGE_API_BASE_URL)[=:][^[:space:]]+#\1=<redacted>#g'
  }
  # Prefer compiler/AAPT lines over Gradle's generic stack trace. These twelve
  # lines fit in the coordination tail and preserve every relevant source
  # location when Kotlin reports multiple compile errors.
  diagnostics="$(grep -E '(^e: |[[:space:]]error:|\.kt:)' "$diagnostic_log" | tail -n 12 || true)"
  if [[ -n "$diagnostics" ]]; then
    printf '%s\n' "$diagnostics" | redact_gradle_log
  else
    tail -n 12 "$diagnostic_log" | redact_gradle_log || true
  fi
  printf 'GRADLE_FAILURE_CONTEXT_END\n'
  exit "$gradle_status"
fi

apk_path="app/build/outputs/apk/debug/app-debug.apk"
digest_path="${apk_path}.sha256"
test -s "$apk_path"
sha256sum "$apk_path" > "$digest_path"
printf 'APK=%s\nSHA256=%s\n' "$repo_root/$apk_path" "$(cut -d ' ' -f 1 "$digest_path")"
