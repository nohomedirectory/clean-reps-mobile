# Clean Reps Mobile

Native Android client for the Million Kick Challenge gym baseline. It owns the phone camera lifecycle, a **single** canonical contribution source, source epoch/reconnect identity, local safety recording, athlete controls, tones and Android-local TTS. It never creates kick verdicts, adjudicates a kick, or increments an official count.

## Current sprint reality

The app uses [RootEncoder 2.7.0](https://github.com/pedroSG94/RootEncoder)'s Android-native camera2/H.264/AAC/SRT implementation. It previews, publishes one authenticated SRT contribution to MediaMTX, and writes an app-private local MP4 safety spool while capture is active. It does not run a second CameraX graph and it never publishes separately to a recorder, analyzer or broadcaster. The source path is fixed by contract as `million-kicks-camera`; MediaMTX performs the server-side fan-out.

The app reports `CONNECTING` until the SRT handshake succeeds and only reports `LIVE` from the transport success callback. A transport discontinuity creates a new `SourceEpoch` before retrying the same canonical MediaMTX path. Missing configuration blocks capture rather than falsely claiming a live source.

The deployed baseline does not yet decode the SRT source into live pose observations. For low-volume alignment only, **Log manual attempt** records a bounded interval against the one canonical raw source. It deliberately supplies no invented pose visibility, so Clean Reps creates an immutable `evidence_failed` event that can be credited only through the private manual-review surface after the recording is inspected. This fallback is not suitable for official high-volume counting.

## Build

The repository has a pinned Gradle wrapper (8.11.1), AGP 8.7.3, JDK 17, and a GitHub Actions workflow that builds a downloadable `clean-reps-mobile-debug-apk` artifact on every sprint-branch push. GitHub Actions is presently account-billing locked; use the deterministic user-space bootstrap/build scripts on the AX41 or another Linux build host instead:

```bash
tools/build-debug-apk.sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`tools/bootstrap-android-sdk.sh` downloads the exact Android command-line tools revision, verifies its SHA-256, accepts licenses and installs only platform-tools, API 35 and build-tools 35.0.0. `tools/build-debug-apk.sh` runs unit tests, lint and `assembleDebug`, then writes `app/build/outputs/apk/debug/app-debug.apk.sha256`. The expected APK path is `app/build/outputs/apk/debug/app-debug.apk`.

## Launch config

Do not commit these values. Provide them as process environment variables to `tools/build-debug-apk.sh` (or as matching untracked Gradle properties):

- `CHALLENGE_API_BASE_URL`: private/Tailscale Clean Reps API URL, no trailing slash.
- `MEDIAMTX_SRT_HOST`: AX41 tailnet hostname/IP, optionally followed by `:8890`.
- `MEDIAMTX_SRT_PASSPHRASE` and `MEDIAMTX_PUBLISH_PASSWORD`: existing Cloud OBS SRT transport and publisher credentials.
- path is contractually `million-kicks-camera`.

The app intentionally blocks source readiness without all required configuration. Session/block controls make contract calls with an idempotency key. Do not put those values in the repository or in an APK intended for distribution beyond the private gym device.

## Gym operator path

1. Open the app, grant Camera and Microphone.
2. On tripod, choose **Side Kick / Right** and verify both feet are visible in the preview.
3. Choose **Create / switch block**, hold still, then choose **Confirm framing ready**.
4. Start capture and wait for `SOURCE LIVE`; do not count while blocked or reconnecting.
5. For each low-volume alignment rep, choose **Log manual attempt** after returning to stable stance. The event remains evidence-failed until reviewed in Clean Reps; the button never grants credit.
6. Connect an earbud, run **Audio test**, then enable Debug verdict speech only for alignment testing.
7. For left-side work, stop kicking, select **Side Kick / Left**, create the new block, hold still, confirm framing again, then continue. A reconnect creates a new `sourceEpoch`; it never creates a second concurrent source.

## Contract

The canonical schema and semantics live in `clean-reps/docs/tonight-sprint/million-kicks-launch.v1.json` version v1. This client uses:

- `POST /v1/challenge-sessions`
- `POST /v1/challenge-sessions/{sessionId}/blocks`
- `POST /v1/challenge-sessions/{sessionId}/captures`
- `POST /v1/captures/{captureId}/health`
- `GET /v1/challenge-sessions/{sessionId}/stream`

All mutations send `Idempotency-Key`. `ChallengeApi` consumes the canonical `challenge-state` SSE stream, deduplicates `latestVerdict` by adjudication ID for tones, and surfaces the nested `activeCue` for safe-window TTS; neither path has count authority.
