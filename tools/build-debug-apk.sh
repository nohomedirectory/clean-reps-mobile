#!/usr/bin/env bash
# Reproducible non-Actions debug build. Configuration is injected only from the
# caller's environment and is never printed, committed, or copied into logs.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

config_names=(
  CHALLENGE_API_BASE_URL
  MEDIAMTX_SRT_HOST
  MEDIAMTX_SRT_PASSPHRASE
  MEDIAMTX_PUBLISH_PASSWORD
)

config_error() {
  printf 'CONFIGURATION_ERROR: %s\n' "$1" >&2
  exit 2
}

valid_host_port() {
  local input="$1" host port='' label
  local -a host_labels
  if [[ "$input" =~ ^\[([0-9A-Fa-f:.]+)\](:([0-9]{1,5}))?$ ]]; then
    host="${BASH_REMATCH[1]}"
    port="${BASH_REMATCH[3]-}"
    [[ "$host" == *:* && "$host" != *:::* ]] || return 1
  else
    # Unbracketed colons are reserved for the one optional port separator.
    [[ "$input" != *:*:* ]] || return 1
    if [[ "$input" == *:* ]]; then
      host="${input%:*}"
      port="${input##*:}"
    else
      host="$input"
    fi
    (( ${#host} >= 1 && ${#host} <= 253 )) || return 1
    [[ "$host" != .* && "$host" != *. && "$host" != *..* ]] || return 1
    IFS=. read -r -a host_labels <<<"$host"
    for label in "${host_labels[@]}"; do
      (( ${#label} <= 63 )) || return 1
      [[ "$label" =~ ^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?$ ]] || return 1
    done
  fi
  if [[ -n "$port" ]]; then
    [[ "$port" =~ ^[0-9]{1,5}$ ]] || return 1
    (( 10#$port >= 1 && 10#$port <= 65535 )) || return 1
  fi
}

# Decide the artifact mode before invoking Gradle. A build is either entirely
# generic or entirely configured; accepting a partial environment silently
# creates an APK that can never become ready on the gym device.
require_config="${CHALLENGE_BUILD_REQUIRE_CONFIG:-0}"
if [[ "$require_config" != 0 && "$require_config" != 1 ]]; then
  config_error 'CHALLENGE_BUILD_REQUIRE_CONFIG must be 0 or 1 (values were not printed).'
fi

set_count=0
for config_name in "${config_names[@]}"; do
  [[ -n "${!config_name-}" ]] && ((set_count += 1))
done

if (( set_count != 0 && set_count != ${#config_names[@]} )); then
  config_error 'configuration is partial; set all four required variables or unset all four (values were not printed).'
fi
if [[ "$require_config" == 1 && $set_count -ne ${#config_names[@]} ]]; then
  config_error 'configured build mode requires all four configuration variables (values were not printed).'
fi
if [[ "$require_config" != 1 && $set_count -ne 0 ]]; then
  config_error 'private settings require explicit CHALLENGE_BUILD_REQUIRE_CONFIG=1; ordinary distributable builds must contain no private settings (values were not printed).'
fi

configured=false
private_artifact=false
if (( set_count == ${#config_names[@]} )); then
  configured=true
  private_artifact=true

  api_url="$CHALLENGE_API_BASE_URL"
  if [[ ! "$api_url" =~ ^https?://[^/?#[:space:]]+(/[^?#[:space:]]*)?$ || "$api_url" == */ ]]; then
    config_error 'CHALLENGE_API_BASE_URL must be an http(s) URL without whitespace, query/fragment, or trailing slash (value was not printed).'
  fi
  api_authority="${api_url#*://}"
  api_authority="${api_authority%%/*}"
  if ! valid_host_port "$api_authority"; then
    config_error 'CHALLENGE_API_BASE_URL must contain a valid hostname/IP and optional port (value was not printed).'
  fi

  srt_host="$MEDIAMTX_SRT_HOST"
  if ! valid_host_port "$srt_host"; then
    config_error 'MEDIAMTX_SRT_HOST must be a valid hostname/IP with an optional port from 1 to 65535 (value was not printed).'
  fi

  passphrase_length="$(LC_ALL=C printf '%s' "$MEDIAMTX_SRT_PASSPHRASE" | wc -c)"
  if (( passphrase_length < 10 || passphrase_length > 79 )) ||
      [[ "$MEDIAMTX_SRT_PASSPHRASE" =~ [[:cntrl:]] ]]; then
    config_error 'MEDIAMTX_SRT_PASSPHRASE must be 10-79 bytes without control characters (value was not printed).'
  fi
  if [[ "$MEDIAMTX_PUBLISH_PASSWORD" =~ [[:cntrl:]] ]]; then
    config_error 'MEDIAMTX_PUBLISH_PASSWORD must not contain control characters (value was not printed).'
  fi
fi

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
# A previous lint failure must not replace a fresh test/compile failure's diagnostics.
lint_report="app/build/intermediates/lint_intermediate_text_report/debug/lintReportDebug/lint-results-debug.txt"
rm -f "$lint_report"
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
  # Lint writes a concise text report even when the aggregate build fails.
  # Prefer it over Gradle's generic task summary when it is present.
  if [[ $compiler_status -eq 0 && -s "$lint_report" ]] && grep -Eq "^[0-9]*[1-9][0-9]* errors?[, ]" "$lint_report"; then diagnostic_log="$lint_report"; fi
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
  diagnostics="$(grep -Ei '(^e: |[[:space:]]error:|\.kt:)' "$diagnostic_log" | tail -n 12 || true)"
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
manifest_path="${apk_path}.manifest.json"
test -s "$apk_path"
sha256sum "$apk_path" > "$digest_path"
apk_sha256="$(cut -d ' ' -f 1 "$digest_path")"
cat >"$manifest_path" <<EOF
{
  "schemaVersion": 1,
  "configured": $configured,
  "privateArtifact": $private_artifact,
  "configurationRequired": $([[ "$require_config" == 1 ]] && printf true || printf false),
  "challengeApiBaseUrlSet": $([[ -n "${CHALLENGE_API_BASE_URL:-}" ]] && printf true || printf false),
  "mediaMtxSrtHostSet": $([[ -n "${MEDIAMTX_SRT_HOST:-}" ]] && printf true || printf false),
  "mediaMtxSrtPassphraseSet": $([[ -n "${MEDIAMTX_SRT_PASSPHRASE:-}" ]] && printf true || printf false),
  "mediaMtxPublishPasswordSet": $([[ -n "${MEDIAMTX_PUBLISH_PASSWORD:-}" ]] && printf true || printf false),
  "apkSha256": "$apk_sha256"
}
EOF
if [[ "$private_artifact" == true ]]; then
  printf 'ARTIFACT_CLASSIFICATION=configured-private\n'
else
  printf 'ARTIFACT_CLASSIFICATION=generic-unconfigured\n'
fi
printf 'APK=%s\nSHA256=%s\nMANIFEST=%s\n' \
  "$repo_root/$apk_path" "$apk_sha256" "$repo_root/$manifest_path"
