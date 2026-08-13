#!/usr/bin/env bash
# Install exactly the Android SDK pieces used by the pinned AGP build. This is
# intentionally user-space: it needs neither sudo nor a preexisting SDK.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
sdk_root="${ANDROID_SDK_ROOT:-$repo_root/.android-sdk}"
tools_revision="11076708"
archive="commandlinetools-linux-${tools_revision}_latest.zip"
archive_url="https://dl.google.com/android/repository/${archive}"
archive_sha256="2d2d50857e4eb553af5a6dc3ad507a17adf43d115264b1afc116f95c92e5e258"
sdkmanager="$sdk_root/cmdline-tools/latest/bin/sdkmanager"

require_command() { command -v "$1" >/dev/null || { echo "missing required command: $1" >&2; exit 1; }; }
require_command curl
require_command unzip
require_command sha256sum

if [[ ! -x "$sdkmanager" ]]; then
  temp_dir="$(mktemp -d)"
  trap 'rm -rf "$temp_dir"' EXIT
  curl --fail --location --retry 3 --retry-delay 2 "$archive_url" --output "$temp_dir/$archive"
  echo "$archive_sha256  $temp_dir/$archive" | sha256sum --check --status
  mkdir -p "$sdk_root/cmdline-tools"
  unzip -q "$temp_dir/$archive" -d "$temp_dir/unpacked"
  rm -rf "$sdk_root/cmdline-tools/latest"
  mv "$temp_dir/unpacked/cmdline-tools" "$sdk_root/cmdline-tools/latest"
fi

export ANDROID_SDK_ROOT="$sdk_root"
export ANDROID_HOME="$sdk_root"
# sdkmanager otherwise caches repository manifests under the invoking user's
# home directory, which is often read-only in hermetic runners.
export ANDROID_USER_HOME="${ANDROID_USER_HOME:-$sdk_root/.android-user-home}"
mkdir -p "$ANDROID_USER_HOME"
# The command-line tools cache manifests under Java's user.home. In restricted
# runners that may be /root even when ANDROID_USER_HOME is set, so pin it to the
# same user-space directory without altering the shell's HOME.
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Duser.home=$ANDROID_USER_HOME"
# `yes` commonly exits with SIGPIPE after sdkmanager accepts the final license;
# retain sdkmanager's status rather than treating that expected SIGPIPE as a
# bootstrap failure under pipefail.
set +o pipefail
yes | "$sdkmanager" --licenses >/dev/null
sdkmanager_license_status=${PIPESTATUS[1]}
set -o pipefail
if [[ "$sdkmanager_license_status" -ne 0 ]]; then
  echo "sdkmanager --licenses failed with status $sdkmanager_license_status" >&2
  exit "$sdkmanager_license_status"
fi
"$sdkmanager" --install \
  "platform-tools" \
  "platforms;android-35" \
  "build-tools;35.0.0"
printf 'ANDROID_SDK_ROOT=%s\n' "$sdk_root"
