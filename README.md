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

## Connection setup and private builds

A generic APK contains no server credentials. Open **Set up connection** and supply the existing private Clean Reps address, video host/port, SRT passphrase and publish password. Passwords are masked; values are saved in app-private preferences with Android backup disabled. Settings can be changed only while video and practice are stopped. Keep Tailscale connected.

HTTPS API addresses are accepted. HTTP is accepted only for RFC1918 addresses, Tailscale's 100.64.0.0/10 range, or a `.ts.net` hostname. Redirects are not followed. The manifest permits cleartext for this validated private API transport; the video contribution still uses authenticated SRT.

Private operator builds may still inject these four environment variables:

- `CHALLENGE_API_BASE_URL`: private/Tailscale Clean Reps API origin.
- `MEDIAMTX_SRT_HOST`: AX41 tailnet hostname/IP, optionally followed by `:8890`.
- `MEDIAMTX_SRT_PASSPHRASE` and `MEDIAMTX_PUBLISH_PASSWORD`: existing transport and publisher credentials.

The stream path remains `million-kicks-camera`. An APK containing private settings must never be published as a public GitHub release asset. `CHALLENGE_BUILD_REQUIRE_CONFIG=1` on the build script requests a fail-closed configured build and an adjacent manifest reports configuration status without values. Ordinary generic builds can be provisioned on the device later.

## Gym operator path

1. Install the APK, open it, grant Camera and Microphone, and set up the private connection if the build was not configured.
2. Choose **Start video**. This creates a private rehearsal session and capture independently of practice. Only a successful SRT handshake displays video live. It does not confirm any public platform or analyzer is connected.
3. Check the preview. Select Teep, Roundhouse or Side kick, Right or Left, Air/Standing bag/Hanging bag, and optionally Low/Middle/High target height.
4. Choose **Start practice**. The server stores and activates that block; the app never judges or increments a count.
5. Choose **Pause practice** before resting or changing the drill. The camera and broadcast contribution continue. Choose **Resume practice**, or change the selection and **Start practice** for a new block.
6. Choose **Stop video** to stop the contribution and finalize its local safety recording. Restarting or reconnecting attaches a new source epoch. Check framing and resume practice after a reconnect.
7. Keep the app open on the Moto while contributing video; leaving the foreground stops capture. Use a separate device for chat and broadcast controls. The screen stays awake while this Activity is open.

This build intentionally marks every capture as `rehearsal`; it contributes zero official credit. Official-live enrollment and trusted platform-delivery attestation require the verified server workflow. Drill metadata and target height do not establish automatic judging readiness or add cadence, reset, hold or visibility requirements.

**Save manual review marker** is a diagnostic fallback during practice. It references the recorded source interval with no invented pose evidence. It does not accept a kick. The challenge total shown is supplied by the server's `challengeOfficialAcceptedCount`, never a locally incremented number.

## Contract

The canonical schema and semantics live in `clean-reps/docs/tonight-sprint/million-kicks-launch.v1.json` version v1. This client uses:

- `POST /v1/challenge-sessions`
- `POST /v1/challenge-sessions/{sessionId}/blocks`
- `POST /v1/challenge-sessions/{sessionId}/blocks/{blockId}/pause` and `/resume`
- `POST /v1/challenge-sessions/{sessionId}/captures` with `purpose: rehearsal`
- `POST /v1/captures/{captureId}/health`
- `GET /v1/challenge-sessions/{sessionId}/stream`

All mutations send `Idempotency-Key`. `ChallengeApi` consumes the canonical `challenge-state` SSE stream, deduplicates `latestVerdict` by adjudication ID for tones, and surfaces the nested `activeCue` for safe-window TTS; neither path has count authority.
