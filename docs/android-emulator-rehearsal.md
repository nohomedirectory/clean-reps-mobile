# Agent-operated APK rehearsal

This test runs the released APK unchanged in an Android emulator on the server.
The emulator supplies retained footage through its camera interface. The APK
still uses its own Camera2, RootEncoder, API, SRT, safety spool and SSE code.
The founder's phone and laptop are not test dependencies.

## Pinned input and scope

- Released APK commit: `1051ea5ffb80175f5cc3c6bd31c46026b8d533f2`.
- APK SHA-256: `5edf6afdb9c329c150f6a422ac8380b232d655c54c6c2c219458779e39450801`.
- Package: `com.vaylith.cleanrepsmobile`; version: `0.2.0-rehearsal`.
- Retained fixture SHA-256: `55e4e4d69b86ef8a96b8917f649ccbf8b612c081002d316e6c363a3c15b03d49`.
- Initial runtime: Android Emulator 37.1.11, API 35, x86_64, software
  acceleration. AX41 did not expose `/dev/kvm` at setup.
- The API 35 software runtime repeatedly lost core Android services after boot.
  API 28 x86_64 is the lighter fallback under evaluation; it is above this APK's
  minimum API 26. Neither a boot flag nor successful APK installation establishes
  a working foreground app, camera, or stream.

Use a new isolated receiver database, new test-only camera credentials and
loopback listeners. Do not read production credentials or stores, publish to
streaming platforms, or create official credits. The receiver must begin empty;
only the actual APK creates its session, capture and practice block.

## Components

1. The Android SDK emulator runs with `-camera-back videofile:ABSOLUTE_FIXTURE`,
   a private ADB server port, and owned AVD/user-data directories. Use a bounded
   supervisor and retain the fixture, APK, emulator, AVD and process identities.
2. Clean Reps `tools/android-apk-rehearsal-server.mjs` starts the actual API,
   isolated MediaMTX recorder and live analysis supervisor. It writes a private
   `connection.json` with `testOnly: true` and emulator-host addresses at
   `10.0.2.2`. It seeds no sessions and starts no substitute video publisher.
3. `tools/android-rehearsal-ui.py` drives the installed APK's actual dialog and
   buttons. Run its `configure`, `practice` and `stop` phases separately, each
   with a new output directory. It preserves an already foreground Activity;
   practice and stop refuse a lost foreground session. It retains redacted UI
   hierarchies, screenshots, action records and explicit phase results.
   The `uiautomator2` backend uses pinned `uiautomator2==3.7.0` and
   `adbutils==2.12.0` from an isolated dependency directory. Its persistent
   helper JAR is separate from the tested APK and reaches the emulator through
   the explicitly selected private ADB server, without a host HTTP listener.
   Accessibility `EditText.set_text` enters connection values through the UI;
   it does not change preferences directly. The legacy ADB backend's
   `input keycombination` is unsuitable for the API 28 fallback.
4. `tools/AndroidFeedbackObserver.java` observes the unchanged debug APK through
   an existing loopback ADB JDWP forward. It records non-suspending breakpoints
   at exact accepted, rejected and neutral tone branches. It reads no app
   values, invokes no app methods, and does not assert audible output.

## Agent sequence

1. Boot the emulator, wait for Android startup, and install the hash-verified
   APK. Grant its requested camera/microphone permissions on this test device.
   Use `adb install --no-streaming --no-incremental -g` when the streamed install
   stalls. Verify Package Manager and the actual foreground Activity remain
   available; `sys.boot_completed=1` alone is insufficient. The UI helper also
   recognizes legacy API 28 permission dialogs, scoped to Android's permission
   packages. Keep the Activity foreground throughout capture.
2. Start a new receiver. Confirm its `ready.json`, empty database and private
   connection file. Run the UI driver's `configure` phase. This enters all four
   connection fields, saves them, presses **Start video**, and waits for the
   actual SRT callback's **Video: live** state.
3. Inspect the Android-produced video geometry before fixing the analyzer's
   athlete region. The fixture's original region is `0.52,0.05,0.98,0.98`;
   rotation or cropping can invalidate it.
4. Forward an ephemeral loopback port to the app's JDWP process. Compile the
   observer with `javac --add-modules jdk.jdi`, and run it after **Start video**
   but before practice. Confirm its exact branch line table and ready event.
   Never use **Audio test** during the observation: it also calls ACCEPTED.
5. Run the UI driver's `practice` phase: select **Teep**, **Right**, **Standing
   bag**, then **Start practice**. Observe actual SRT media, server recording,
   analyzer events, database changes and app feedback separately.
6. Run `stop`. Retain and inspect both the finalized Android safety spool and
   server recording. Request receiver shutdown through its owned stop file;
   verify closed-database integrity, backup equality, zero official credit and
   owned-process cleanup. Stop the emulator through its owning supervisor.

### Persistent UI invocation

Run on AX41 after the emulator and a fresh isolated receiver are ready. Use a
new output directory for every phase; `server-apk-v2` is an example and must name
the running receiver. The connection file is private test configuration and
must not be printed. No commands are required on the founder's phone or laptop.

```bash
simulation_root=/srv/hetzner-bridge/workspace/android-simulation-20260917
python3 tools/android-rehearsal-ui.py \
  --adb "$simulation_root/sdk/platform-tools/adb" \
  --serial emulator-5580 --adb-port 5038 \
  --android-user-home "$simulation_root/sdk-home" \
  --connection "$simulation_root/server-apk-v2/connection.json" \
  --output "$simulation_root/ui-configure-api28-v1" \
  --phase configure --backend uiautomator2 \
  --python-lib "$simulation_root/python-lib" --timeout-seconds 900
```

Then run the same command with `--phase practice` and a new output directory
after the video geometry and feedback observer are ready. Use `--phase stop`
with another new directory to finalize capture. Practice and stop refuse to
relaunch a lost foreground Activity, because that would hide a stream break.

## Evidence limits

Video preview can start fixture playback before **Start video**. Current emulator
source loops footage on host wall time and may skip source frames when decoding
falls behind. Align Android-produced recording pixels to the fixture, not to a
UI click or a presumed zero timestamp. Repeated loops are reused test material,
not independent accuracy samples.

An APK **LIVE** label alone does not establish analyzer, recording or feedback
success. Server SSE observations alone do not prove an Android callback. Observer
exit zero alone does not establish branch hits. A branch hit establishes reaching
the tone call site, not sound at a physical earbud. Debugger instrumentation and
software emulation cannot certify uninstrumented phone performance or latency.

The setup checks and empty receiver smoke are verified. The exact released APK
was installed on API 35, but Android framework instability prevented a verified
app flow. Actual API 28 launch, UI, camera, publishing, practice and feedback
results remain pending and must be recorded separately. These tools and their
offline selector tests are not themselves a passing APK test.

Primary references: [emulator camera options](https://developer.android.com/studio/run/emulator-commandline),
[camera scene clock](https://android.googlesource.com/platform/external/qemu/+/refs/heads/emu-main-dev/android/android-emu/android/ver/src/Scene.cpp),
[JDI suspension controls](https://docs.oracle.com/en/java/javase/17/docs/api/jdk.jdi/com/sun/jdi/request/EventRequest.html).
