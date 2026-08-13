# Clean Reps Mobile

Native Android client for the Million Kick Challenge gym baseline. It owns the phone camera lifecycle, a **single** canonical contribution source, source epoch/reconnect identity, local safety recording, athlete controls, tones and Android-local TTS. It never creates kick verdicts, adjudicates a kick, or increments an official count.

## Current sprint reality

The app is a buildable CameraX/Compose client. It previews and records an app-private device-local safety clip while the Activity is running; it can create sessions, blocks and capture attachments against the Clean Reps v1 endpoints after configuration. `CanonicalSourcePublisher` intentionally has no SRT implementation yet: Android does not ship an SRT encoder/publisher, and reporting a fake source would undermine the challenge. Until a real vetted SRT/WebRTC publisher is supplied, readiness remains **BLOCKED: source publisher unavailable** and the app will not claim it is contributing to MediaMTX.

That means this commit is useful for camera framing, block/session contract testing, local audio and API wiring, but **does not clear the gym acceptance gate for canonical server video**. The source path is fixed by contract as `million-kicks-camera`.

## Build

Install Android Studio (JDK 17) and Android SDK platform 35, then from this repository:

```bash
gradle :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If Gradle is not installed, use Android Studio’s Gradle wrapper generation once (`gradle wrapper --gradle-version 8.11.1`) and then `./gradlew :app:assembleDebug`. The expected APK path is `app/build/outputs/apk/debug/app-debug.apk`.

## Launch config

Do not commit these values. Provide them in `~/.gradle/gradle.properties` and replace the empty debug BuildConfig fields via a local build flavor or CI secret injection:

- `CHALLENGE_API_BASE_URL`: private/Tailscale Clean Reps API URL, no trailing slash.
- `MEDIAMTX_SRT_HOST` and `MEDIAMTX_SRT_PASSPHRASE`: supplied only when a real publisher is integrated.
- path is contractually `million-kicks-camera`.

The app intentionally blocks source readiness without all required configuration **and** a real publisher. Session/block controls still make contract calls with an idempotency key.

## Gym operator path (once publisher is integrated)

1. Open the app, grant Camera and Microphone.
2. On tripod, choose **Side Kick / Right** and verify both feet are visible in the preview.
3. Create/attach the session and wait for `SOURCE LIVE`; do not start official counting while blocked.
4. Start capture. A reconnect creates a new `sourceEpoch`; it never creates a second concurrent source.
5. Connect an earbud, run **Audio test**, then enable Debug verdict speech only for alignment testing.
6. For left-side work, stop kicking, select **Side Kick / Left**, wait for reacquisition/visibility from the server, then continue.

## Contract

The canonical schema and semantics live in `clean-reps/docs/tonight-sprint/million-kicks-launch.v1.json` version v1. This client uses:

- `POST /v1/challenge-sessions`
- `POST /v1/challenge-sessions/{sessionId}/blocks`
- `POST /v1/challenge-sessions/{sessionId}/captures`
- `POST /v1/captures/{captureId}/health`
- `GET /v1/challenge-sessions/{sessionId}/stream`

All mutations send `Idempotency-Key`. Verdict/cue event parsing is deliberately isolated in `ChallengeApi` for replacement by an SSE client when the server event envelope is finalized.
