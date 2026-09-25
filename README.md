# Clean Reps Mobile

Native Android client for the Million Kick Challenge gym baseline. It owns the phone camera lifecycle, a **single** canonical contribution source, source epoch/reconnect identity, local safety recording, athlete controls, tones and Android-local TTS. It never creates kick verdicts, adjudicates a kick, or increments an official count.

## This repository is PUBLIC

This repository is **public** (github.com/nohomedirectory/clean-reps-mobile). Never commit server hosts or addresses, tailnet names, credentials, SRT connection strings, gym footage, frames, thumbnails or screenshots of a private setup, or an APK built with private settings. Tests use RFC 2606/5737 placeholders (`example.test`, `192.0.2.x`). The owner's device-check runbook, which names the private hosts, is kept outside this repository.

## Current sprint reality

The app uses [RootEncoder 2.7.0](https://github.com/pedroSG94/RootEncoder)'s Android-native camera2/H.264/AAC/SRT implementation. It previews, publishes one authenticated SRT contribution to MediaMTX, and writes an app-private local MP4 safety spool while capture is active. It does not run a second CameraX graph and it never publishes separately to a recorder, analyzer or broadcaster. The source path is fixed by contract as `million-kicks-camera`; MediaMTX performs the server-side fan-out.

The app reports `CONNECTING` until the SRT handshake succeeds and only reports `LIVE` from the transport success callback. A transport discontinuity creates a new `SourceEpoch` before retrying the same canonical MediaMTX path. Missing configuration blocks capture rather than falsely claiming a live source.

Automatic analysis runs on the Clean Reps server, never on the phone: the server's live worker analyses the one canonical stream, and only Teep is judged automatically. The phone shows the analysis status, plays tones for the server's verdicts and shows the server's counts; it never judges a kick or changes a count. For low-volume alignment only, **Save manual review marker** (overflow menu, during practice) records a bounded interval against the one canonical raw source. It deliberately supplies no invented pose visibility, so Clean Reps creates an immutable `evidence_failed` event that can be credited only through the private manual-review surface after the recording is inspected. This fallback is not suitable for official high-volume counting.

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

## Orientation

- **While the video is off**, the screen follows how the phone is held (Android `sensor` orientation): portrait or landscape, including a flip between the two landscape sides. Whether upside-down portrait is offered depends on the phone. The preview follows the screen.
- **At the Go live (or Restart video) tap the orientation locks** and stays locked until the video stops. The video is prepared for the orientation the screen has at that moment and is always sent upright: portrait sends 720x1280, landscape sends 1280x720.
- **Turning the phone while live never rotates the video.** Once the phone has been held in another orientation for about 1.5 s, a banner reads "Phone turned - video stays landscape. Stop video to switch." (or "... video stays portrait ..."). A phone lying flat never triggers it. To switch, tap **Stop video**, turn the phone, then tap **Restart video**.
- Landscape works best for kicks. While the prepared video is portrait, a dismissible hint says "Landscape works best for kicks".

## The camera screen

- **Full screen and dark.** The window runs edge to edge (also behind the camera cutout) with the system bars hidden; swipe to show them. The preview fills the screen and crops like a camera app; it is never stretched. On a 20:9 phone the preview hides about a tenth of the transmitted frame at the top and at the bottom in landscape (at the left and at the right in portrait); **Diagnostics > Frame check** shows the whole frame.
- **Control rail** (right edge in landscape, bottom in portrait):
  - the **drill chip**, for example "Teep - Right - Hanging bag" (the default drill). It opens the drill part of the setup sheet and is disabled while practising;
  - **one primary button**: Set up connection, Allow camera, Go live, Connecting..., Start practice, Pause, Resume, or Restart video (after the video stopped, or when a reconnected video could not be linked to the session; while live, Restart video is also offered on the hint line when analysis needs a new capture). A disabled button always shows a one-line reason under it. While live, the full analysis instruction appears under it too, for example "Head not visible - move the phone back or higher";
  - **Stop video** while the video runs;
  - the **gear** for the connection settings, disabled while the video runs;
  - the **overflow menu**: Audio test, Voice hints (on by default), Speak verdicts, Save manual review marker (during practice), Last session check (after a video stopped), Diagnostics.
- **Setup sheet** (gear, drill chip or Set up connection) with three parts: Drill, Connection, Audio. Only Teep can be chosen; Roundhouse and Side kick are shown as "not auto-judged yet". Side Right or Left, target Air, Standing bag or Hanging bag, and an optional target height. Connection settings can be edited only while video and practice are stopped.
- **Status, top left over the preview:**
  - the status pill: "LIVE - landscape 1280x720" (the geometry the encoder uses), "Connecting...", "Reconnecting...", "Video off", "Video error" or "Video unavailable";
  - the analysis chip: "Analysis" and a short state such as "Tracking" or "Head cut off", with a "Paused" badge while practice is paused;
  - the server chip: "Server reachable - release" and the server's release, or "Server unreachable", checked every 15 s while the screen is visible;
  - "This session N accepted - M rejected", counted by the server;
  - banners: the phone-turned banner, "Video stopped because Clean Reps left the screen. Tap Restart video.", camera and connection errors that name the failed step (with Reopen camera while the video is off, or Stop video while it runs), and "Couldn't judge that one - keep head and feet in view".
- **Framing guide**: the hint "Head and feet on screen - about 3-4 m away - phone at waist height", and, while live with a current analysis status, the analyser's area on the cropped preview, labelled "Stay in this area". Both fade out during practice.
- **Session check** card, after Stop video: what the server saw for that capture. It shows the overall verdict and the checks (Video geometry, Upright, Athlete detected, Head and feet in frame; PASS, WARN or FAIL) with the analyser's advice, how often the body, the head and the feet were visible, judged versus unavailable kicks, the app and server versions, and up to three analysed frames. It waits up to 20 s for the final report; "No analysis ran for this capture" means the server has no report. Reopen it from **Last session check**.
- **Diagnostics**: build identity and server release, the camera's sensor orientation and prepared geometry, the last 100 events (redacted) with Copy, and **Frame check**, which shows the whole transmitted frame at the encoder size, including what the preview crops, so you can confirm it is upright and undistorted.

## Gym operator path

1. Install the APK, open it and allow Camera and Microphone. If the build has no connection, tap **Set up connection** and enter the private settings. Keep Tailscale connected.
2. Place the phone the way you will film (landscape works best), about 3-4 m away at waist height, and check the framing hint.
3. Tap **Go live**. The orientation locks. The pill shows "Connecting..." until the video server accepts the stream, then "LIVE" with the geometry. Live means this phone reached the video server; it does not confirm a public broadcast or automatic judging.
4. Analysis warms up while live: the practice block for the chosen drill is created as soon as the video is live, so the analysis chip can reach "Tracking" before you start. Nothing is judged before Start practice.
5. Tap **Start practice**. There is no confirmation. When analysis is tracking you, the phone plays one short ready chirp. If tracking is lost for 2 s (or has not started 10 s after the tap), it plays two low pips and, with Voice hints on, says what to fix. Repeated cues sound at most once every 15 s.
6. Kick. An accepted kick plays a high double beep and a rejected kick one low tone. A kick that could not be judged, or waits for review, makes no sound and shows "Couldn't judge that one - keep head and feet in view".
7. Tap **Pause** before resting; the video continues. **Resume** continues the same block. Change the drill (drill chip) only while practice is paused or not started; a new drill starts a new block.
8. Tap **Stop video** when done and read the Session check card. After a dropped connection the video reconnects with a new source epoch; check the framing before resuming.
9. Keep the app in the foreground: leaving it stops the video ("Video stopped because Clean Reps left the screen. Tap Restart video."). Use a separate device for chat and broadcast controls. The screen stays awake while the app is open.

This build intentionally marks every capture as `rehearsal`; it contributes zero official credit. Official-live enrollment and trusted platform-delivery attestation require the verified server workflow. Drill metadata and target height do not establish automatic judging readiness or add cadence, reset, hold or visibility requirements.

**Save manual review marker** is a diagnostic fallback during practice. It references the recorded source interval with no invented pose evidence. It does not accept a kick. The challenge total shown is supplied by the server's `challengeOfficialAcceptedCount`, never a locally incremented number.

## Device checklist (public)

Before any gym use, run a device check with the exact APK and server versions you will use. The owner's runbook, which names the private hosts, lives outside this repository; this is the same core at a generic level, and it takes about three minutes:

1. Install the exact APK. Note its version and git SHA (**Diagnostics**) and the server release (server chip or **Diagnostics**).
2. At home, set the phone up in the orientation you will use at the gym, tap **Go live** and stream about 30 s while you move through the frame.
3. Check the pill: "LIVE - landscape 1280x720" or "LIVE - portrait 720x1280", matching how the phone is held.
4. **Diagnostics > Frame check**: the whole frame is upright and not stretched.
5. **Start practice**: the analysis chip reaches "Tracking" and the ready chirp sounds. Kick a few times and listen for the tones.
6. **Stop video** and read the Session check card: Video geometry, Upright, Athlete detected and Head and feet in frame should all be PASS; look at the analysed frames.
7. Record PASS or FAIL with the versions. Do not use the phone at the gym after a FAIL.

## Contract

The canonical schema and semantics live in `clean-reps/docs/tonight-sprint/million-kicks-launch.v1.json` version v1. This client uses:

- `POST /v1/challenge-sessions`
- `POST /v1/challenge-sessions/{sessionId}/blocks`
- `POST /v1/challenge-sessions/{sessionId}/blocks/{blockId}/reacquired`, `/pause` and `/resume`
- `POST /v1/challenge-sessions/{sessionId}/captures` with `purpose: rehearsal`
- `POST /v1/challenge-sessions/{sessionId}/analysis/kick-events` (Save manual review marker)
- `POST /v1/captures/{captureId}/health`
- `POST /v1/captures/{captureId}/client-info` (C2: build, device and encoder identity, once per capture)
- `GET /v1/challenge-sessions/{sessionId}/stream`, including `liveAnalysis` (C3) and the per-session counts
- `GET /v1/captures/{captureId}/quality-report` and its thumbnails 0 to 2 (C4, the Session check card)
- `GET /health` for the server release (C5)

All mutations send `Idempotency-Key`. `ChallengeApi` consumes the canonical `challenge-state` SSE stream, deduplicates `latestVerdict` by adjudication ID for tones, and surfaces the nested `activeCue` for safe-window TTS; neither path has count authority.
