#!/usr/bin/env python3
"""Drive the unchanged Clean Reps APK through its real Android UI.

This operates only an emulator and a testOnly connection targeting 10.0.2.2.
It does not install/rebuild the APK, create API records, change app preferences,
or infer server/media/analyzer success from the app's LIVE indicator.
"""

import argparse
import html
import json
import logging
import os
from pathlib import Path
import re
import shlex
import signal
import subprocess
import sys
import time
import urllib.parse
import xml.etree.ElementTree as ET


PACKAGE = "com.vaylith.cleanrepsmobile"
ACTIVITY = PACKAGE + "/.MainActivity"
BOUNDS = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")
PERMISSION_PACKAGES = frozenset({
    "com.android.packageinstaller", "com.google.android.packageinstaller",
    "com.android.permissioncontroller", "com.google.android.permissioncontroller",
})
PERMISSION_ALLOW_IDS = frozenset(
    package + ":id/" + button
    for package in PERMISSION_PACKAGES
    for button in (
        "permission_allow_button",  # Android 9/API 28 and earlier.
        "permission_allow_foreground_only_button",
        "permission_allow_one_time_button",
    )
)


def requested_permission_allow_button(tree):
    """Select only an enabled Android permission-controller allow button.

    Some Google system images use a Google package with Android resource IDs,
    so both fields must be known permission namespaces without requiring them
    to be identical. App-owned or merely suffix-matching controls are refused.
    """
    return next((node for node in tree.iter("node")
                 if node.get("package") in PERMISSION_PACKAGES
                 and node.get("resource-id") in PERMISSION_ALLOW_IDS
                 and node.get("enabled") == "true"), None)


class DriverError(RuntimeError):
    pass


def connection_values(path):
    path = Path(path)
    if path.stat().st_size > 32_768:
        raise DriverError("Connection file exceeds the test configuration limit")
    config = json.loads(path.read_text())
    if config.get("testOnly") is not True:
        raise DriverError("Connection must explicitly declare testOnly:true")

    def value(*names):
        found = next((config[n] for n in names if n in config), None)
        if not isinstance(found, str) or not found:
            raise DriverError("A required isolated connection field is missing")
        return found

    api = value("api_origin", "apiOrigin", "apiBaseUrl")
    video = value("video_host_port", "videoHostPort", "srtHost")
    passphrase = value("srt_passphrase", "srtPassphrase", "passphrase", "video_passphrase")
    password = value("publish_password", "publishPassword")
    parsed = urllib.parse.urlsplit(api)
    media = urllib.parse.urlsplit("srt://" + video)
    for address in (parsed, media):
        if (address.hostname != "10.0.2.2" or address.username or address.password
                or address.query or address.fragment or address.path not in ("", "/")
                or address.port is None or not 1024 <= address.port <= 65535):
            raise DriverError("Only explicit 10.0.2.2 test endpoints on unprivileged ports are allowed")
    if parsed.scheme != "http" or media.path:
        raise DriverError("Expected isolated HTTP API and a host:port video endpoint")
    # ADB input text interprets percent-space escapes. Constrain newly generated
    # test credentials rather than risk silently entering a different value.
    if not 10 <= len(passphrase) <= 79 or not 1 <= len(password) <= 128:
        raise DriverError("Test credentials have invalid lengths")
    if any(not re.fullmatch(r"[A-Za-z0-9._~-]+", secret) for secret in (passphrase, password)):
        raise DriverError("Generate isolated test credentials using URL-safe ASCII without percent escapes")
    return {
        "Clean Reps address": api.rstrip("/"),
        "Video host and port": video,
        "Video passphrase": passphrase,
        "Publish password": password,
    }


class Driver:
    def __init__(self, args, values):
        self.args = args
        self.values = values
        self.secrets = [values["Video passphrase"], values["Publish password"]]
        self.output = Path(args.output).absolute()
        self.output.mkdir(mode=0o700, parents=False, exist_ok=False)
        self.deadline = time.monotonic() + args.timeout_seconds
        self.started = time.time()
        self.events = []
        self.dump_number = 0
        self.last_tree = None
        self.last_xml = None
        self.remote_dump = "/sdcard/clean-reps-ui-" + str(os.getpid()) + ".xml"
        self.env = {
            "PATH": "/usr/bin:/bin",
            "LANG": "C.UTF-8",
            "ADB_SERVER_SOCKET": "tcp:127.0.0.1:" + str(args.adb_port),
        }
        if args.android_user_home:
            self.env["ANDROID_USER_HOME"] = str(Path(args.android_user_home).absolute())
        self.base = [args.adb, "-H", "127.0.0.1", "-P", str(args.adb_port), "-s", args.serial]

    def redact(self, value):
        for secret in sorted(self.secrets, key=len, reverse=True):
            for representation in (secret, html.escape(secret), urllib.parse.quote(secret, safe="")):
                value = value.replace(representation, "[REDACTED_TEST_CREDENTIAL]")
        return value

    def event(self, kind, **data):
        entry = {"elapsedSeconds": round(time.time() - self.started, 3), "event": kind, **data}
        self.events.append(entry)
        (self.output / "events.json").write_text(self.redact(json.dumps(self.events, indent=2)) + "\n")

    def remaining(self, limit=45):
        remaining = self.deadline - time.monotonic()
        if remaining <= 0:
            raise DriverError("UI phase deadline exceeded")
        return max(0.1, min(limit, remaining))

    def adb(self, argv, label, limit=45, binary=False):
        try:
            result = subprocess.run(self.base + argv, env=self.env, stdin=subprocess.DEVNULL,
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                    timeout=self.remaining(limit), check=False)
        except subprocess.TimeoutExpired:
            raise DriverError(label + " timed out") from None
        if result.returncode:
            # Never serialize argv: input text commands contain test credentials.
            detail = self.redact(result.stderr.decode("utf-8", "replace")[-1000:])
            raise DriverError(label + " failed (exit " + str(result.returncode) + "): " + detail)
        return result.stdout if binary else result.stdout.decode("utf-8", "replace")

    def shell(self, argv, label, limit=45):
        return self.adb(["shell", " ".join(shlex.quote(str(a)) for a in argv)], label, limit)

    def pause(self, seconds=0.5):
        time.sleep(min(seconds, self.remaining(seconds)))

    def dump(self, label):
        for attempt in range(3):
            response = self.shell(["uiautomator", "dump", self.remote_dump], "UI hierarchy dump", 60)
            if "ERROR" not in response.upper():
                xml = self.shell(["cat", self.remote_dump], "Read owned UI hierarchy")
                start = xml.find("<?xml")
                if start < 0:
                    start = xml.find("<hierarchy")
                try:
                    tree = ET.fromstring(xml[start:])
                except ET.ParseError:
                    tree = None
                if tree is not None:
                    self.dump_number += 1
                    safe_label = re.sub(r"[^a-z0-9-]", "-", label.lower())[:50]
                    name = f"{self.dump_number:03d}-{safe_label}.xml"
                    (self.output / name).write_text(self.redact(xml[start:]))
                    self.last_tree, self.last_xml = tree, xml[start:]
                    return tree
            if attempt < 2:
                self.pause(1)
        raise DriverError("Could not obtain a valid UI hierarchy after three attempts")

    @staticmethod
    def bounds(node):
        match = BOUNDS.fullmatch(node.get("bounds", ""))
        if not match:
            return None
        x1, y1, x2, y2 = map(int, match.groups())
        return (x1, y1, x2, y2) if x2 > x1 and y2 > y1 else None

    @staticmethod
    def matches(node, text):
        return text in (node.get("text"), node.get("content-desc"))

    def find(self, tree, text):
        return next((node for node in tree.iter("node")
                     if self.matches(node, text) and self.bounds(node)), None)

    def clickable(self, tree, node):
        parents = {child: parent for parent in tree.iter() for child in parent}
        while node is not None:
            if node.get("clickable") == "true":
                return node if node.get("enabled") == "true" and self.bounds(node) else None
            node = parents.get(node)
        return None

    def tap(self, node, label):
        bounds = self.bounds(node)
        if not bounds:
            raise DriverError("Target has no visible bounds: " + label)
        x1, y1, x2, y2 = bounds
        self.shell(["input", "tap", (x1 + x2) // 2, (y1 + y2) // 2], "Tap " + label)
        self.event("tap", target=label)
        self.pause()

    def hide_keyboard(self):
        state = self.shell(["dumpsys", "input_method"], "Inspect keyboard visibility")
        # Do not press BACK if no software keyboard is shown: that would close
        # the connection dialog, or background the app and stop its camera.
        if re.search(r"(?:mInputShown|mIsInputViewShown|isInputViewShown)=true", state):
            self.shell(["input", "keyevent", "KEYCODE_BACK"], "Hide keyboard")
            self.pause()

    def scroll(self, tree, direction):
        nodes = [n for n in tree.iter("node") if n.get("scrollable") == "true" and self.bounds(n)]
        if not nodes:
            raise DriverError("No visible scroll container while searching the app UI")
        # The dialog's inner column is the smallest visible scroll container.
        container = min(nodes, key=lambda n: (self.bounds(n)[2] - self.bounds(n)[0]) *
                        (self.bounds(n)[3] - self.bounds(n)[1]))
        x1, y1, x2, y2 = self.bounds(container)
        x = (x1 + x2) // 2
        high, low = y1 + (y2 - y1) // 4, y1 + (y2 - y1) * 3 // 4
        start, end = (low, high) if direction == "down" else (high, low)
        self.shell(["input", "swipe", x, start, x, end, 450], "Scroll " + direction)
        self.pause()

    def locate(self, text, direction="down", must_clickable=True, max_scrolls=7):
        for index in range(max_scrolls + 1):
            tree = self.dump("find-" + text)
            node = self.find(tree, text)
            if node is not None:
                target = self.clickable(tree, node) if must_clickable else node
                if target is not None:
                    return tree, target
                if must_clickable:
                    # A disabled target is not a navigation failure. Give an
                    # in-flight request a bounded opportunity to complete.
                    self.pause(1)
                    continue
            if index < max_scrolls:
                self.scroll(tree, direction)
        raise DriverError("UI target not available/enabled: " + text)

    def click(self, text, direction="down"):
        tree, node = self.locate(text, direction=direction)
        self.tap(node, text)
        return tree

    def field(self, label):
        for _ in range(8):
            tree = self.dump("field-" + label)
            edits = [n for n in tree.iter("node")
                     if n.get("class") == "android.widget.EditText" and self.bounds(n)]
            candidates = [n for n in edits if any(self.matches(child, label) for child in n.iter())]
            label_node = self.find(tree, label)
            if not candidates and label_node is not None:
                parents = {child: parent for parent in tree.iter() for child in parent}
                ancestor = label_node
                while ancestor is not None:
                    scoped = [n for n in ancestor.iter("node") if n in edits]
                    if len(scoped) == 1:
                        candidates = scoped
                        break
                    if len(scoped) > 1:
                        break
                    ancestor = parents.get(ancestor)
                if not candidates:
                    lb = self.bounds(label_node)
                    candidates = [n for n in edits if self.bounds(n)[1] <= lb[3]
                                  and self.bounds(n)[3] >= lb[1]]
            if len(candidates) == 1:
                return candidates[0]
            self.scroll(tree, "down")
        raise DriverError("Could not unambiguously locate connection field: " + label)

    def enter(self, label, value):
        self.tap(self.field(label), label)
        self.shell(["input", "keycombination", "KEYCODE_CTRL_LEFT", "KEYCODE_A"], "Select field contents")
        self.shell(["input", "keyevent", "KEYCODE_DEL"], "Clear field contents")
        self.shell(["input", "text", value], "Enter " + label)
        self.hide_keyboard()
        self.event("field_entered", field=label, secret=label in ("Video passphrase", "Publish password"))

    def visible_texts(self, tree):
        return [n.get("text", "") for n in tree.iter("node") if n.get("text")]

    def wait_text(self, expected, timeout=180, prefix=False, direction=None):
        until = min(self.deadline, time.monotonic() + timeout)
        while time.monotonic() < until:
            tree = self.dump("wait-" + expected)
            texts = self.visible_texts(tree)
            match = next((t for t in texts if (t.startswith(expected) if prefix else t == expected)), None)
            if match is not None:
                self.event("observed", text=match)
                return tree
            fatal = [t for t in texts if t.startswith(("Video: error", "Video: publisher unavailable",
                                                       "Could not start video.", "Could not start practice."))]
            if fatal:
                raise DriverError("App reported: " + "; ".join(fatal))
            if direction:
                self.scroll(tree, direction)
                direction = None
            self.pause(1)
        raise DriverError("App did not display expected state: " + expected)

    def screenshot(self, label):
        tree = self.dump("screenshot-" + label)
        if self.find(tree, "Private connection") is not None:
            self.event("screenshot_omitted", reason="Connection dialog may show entered credentials")
            return
        data = self.adb(["exec-out", "screencap", "-p"], "Capture app screenshot", binary=True)
        if not data.startswith(b"\x89PNG\r\n\x1a\n"):
            raise DriverError("Screenshot was not a PNG")
        (self.output / (label + ".png")).write_bytes(data)

    def permission_dialogs(self):
        for _ in range(3):
            tree = self.dump("permission-check")
            allow = requested_permission_allow_button(tree)
            if allow is None:
                return
            self.tap(allow, "Allow requested camera/microphone while using the app")

    def configure(self):
        self.permission_dialogs()
        tree = self.dump("before-configuration")
        label = "Set up connection" if self.find(tree, "Set up connection") is not None else "Connection setup"
        self.click(label, direction="up")
        self.wait_text("Private connection", timeout=60)
        for label, value in self.values.items():
            self.enter(label, value)
        self.click("Save connection")
        # Recreating the publisher can immediately replace the transient
        # "Connection saved" detail with its camera-preview callback text.
        # The configured button plus the dismissed dialog are stable UI proof.
        saved = self.wait_text("Connection setup", timeout=90)
        if self.find(saved, "Private connection") is not None:
            raise DriverError("Connection dialog remained open after Save connection")
        self.event("observed", text="Connection dialog saved and dismissed")
        self.screenshot("connection-saved")
        self.click("Start video")
        self.wait_text("Video: live", timeout=240)
        self.screenshot("video-live")

    def practice(self):
        # Observe the transport callback's UI result before beginning practice.
        self.locate("Video: live", direction="up", must_clickable=False)
        for label in ("Teep", "Right", "Standing bag"):
            tree, node = self.locate(label)
            if node.get("selected") != "true" and node.get("checked") != "true":
                self.tap(node, label)
            self.event("drill_option", option=label)
        self.click("Start practice")
        self.wait_text("Practice active.", timeout=180, prefix=True, direction="up")
        self.screenshot("practice-active")

    def stop(self):
        self.click("Stop video", direction="up")
        self.wait_text("Video: stopped", timeout=120)
        self.screenshot("video-stopped")

    def run(self):
        self.event("phase_started", phase=self.args.phase, serial=self.args.serial,
                   testOnly=True, appPackage=PACKAGE, apkModified=False,
                   apiOrigin=self.values["Clean Reps address"], videoHostPort=self.values["Video host and port"])
        activity_state = self.shell(["dumpsys", "activity", "activities"], "Inspect foreground activity")
        foreground = any(
            re.match(r"\s*(?:mResumedActivity|topResumedActivity)\s*[:=]", line)
            and PACKAGE + "/" in line
            for line in activity_state.splitlines()
        )
        if foreground:
            self.event("foreground_preserved", appPackage=PACKAGE)
        elif self.args.phase == "configure":
            self.shell(["am", "start", "-W", "-n", ACTIVITY], "Open installed app for configuration", 90)
            self.event("configuration_activity_opened", appPackage=PACKAGE)
        else:
            raise DriverError(
                "App is not the resumed foreground activity; its ON_STOP handler stops video. "
                "Refusing to relaunch and imply stream continuity for the " + self.args.phase + " phase"
            )
        getattr(self, self.args.phase)()

    def finish(self, success, error=None):
        result = {
            "format": "clean-reps-android-ui-rehearsal-v1", "phase": self.args.phase,
            "uiBackend": self.args.backend,
            "success": success, "testOnly": True, "apkModified": False,
            "usedActualUi": True, "createdApiRecordsDirectly": False,
            "mutatedAppPreferencesDirectly": False,
            "elapsedSeconds": round(time.time() - self.started, 3),
            "observedUiStates": [e["text"] for e in self.events if e["event"] == "observed"],
            "limitations": ["UI evidence does not establish server media, analyzer, recording, or SSE delivery.",
                            "Emulator evidence does not establish physical-phone camera, radio, or audio behavior."],
        }
        if error:
            result["error"] = self.redact(error)
        safe = self.redact(json.dumps(result, indent=2))
        (self.output / "result.json").write_text(safe + "\n")
        print(safe)
        # Retain the exact owned on-device dump alongside local redacted dumps;
        # no app data, recordings, or UI artifacts are deleted by this driver.


class UiAutomator2Driver(Driver):
    """Persistent UiAutomator RPC; unchanged APK and the same UI state checks.

    Audited against openatx/uiautomator2 3.7.0 and adbutils 2.12.0. Its HTTP
    connection uses the explicit AdbDevice transport, with no host HTTP listener
    or adb forward. The helper JAR is separate from the application under test.
    """

    def __init__(self, args, values):
        super().__init__(args, values)
        self._u2 = None

    def u2(self):
        if self._u2 is None:
            sys.path.insert(0, str(Path(self.args.python_lib).absolute()))
            # Library exceptions can include RPC arguments. Suppress dependency
            # diagnostics and report only exception types through our wrapper.
            logging.getLogger("uiautomator2").setLevel(logging.CRITICAL)
            logging.getLogger("adbutils").setLevel(logging.CRITICAL)
            try:
                import importlib.metadata
                import adbutils
                import uiautomator2
                if (importlib.metadata.version("uiautomator2") != "3.7.0"
                        or importlib.metadata.version("adbutils") != "2.12.0"):
                    raise DriverError("Persistent UI backend versions differ from the audited pins")
                driver = self

                class SoftwareEmulatorDevice(uiautomator2.Device):
                    def _wait_ready(self, launch_timeout=30):
                        return super()._wait_ready(launch_timeout=driver.remaining(240))

                    def jsonrpc_call(self, method, params=None, timeout=10):
                        return super().jsonrpc_call(method, params, timeout=driver.remaining(60))

                client = adbutils.AdbClient(host="127.0.0.1", port=self.args.adb_port, socket_timeout=60)
                device = client.device(serial=self.args.serial)
                # Construction starts only the separate UiAutomator helper.
                # This function is invoked only when the operator runs a phase.
                self._u2 = SoftwareEmulatorDevice(device, port=9008)
                self._u2.debug = False
                self._u2.settings["wait_timeout"] = 10
                self.event("persistent_ui_connected", adbHost="127.0.0.1", adbPort=self.args.adb_port,
                           serial=self.args.serial, deviceRpcPort=9008, hostHttpListener=False,
                           uiautomator2Version="3.7.0", adbutilsVersion="2.12.0")
            except DriverError:
                raise
            except Exception as exc:
                raise DriverError("Persistent UI helper startup failed: " + type(exc).__name__) from None
        return self._u2

    def rpc_operation(self, label, action):
        self.remaining()
        try:
            return action(self.u2())
        except DriverError:
            raise
        except Exception as exc:
            raise DriverError(label + " failed: " + type(exc).__name__) from None

    def shell(self, argv, label, limit=45):
        # Replace only the expensive fresh-JVM input invocations. Native ADB
        # read-only dumpsys/screencap and initial Activity launch remain intact.
        if argv[:2] == ["input", "tap"]:
            self.rpc_operation(label, lambda d: d.jsonrpc.click(int(argv[2]), int(argv[3])))
            return ""
        if argv[:2] == ["input", "swipe"]:
            self.rpc_operation(label, lambda d: d.jsonrpc.swipe(
                *[int(v) for v in argv[2:6]], max(2, int(argv[6]) // 5)))
            return ""
        if argv == ["input", "keyevent", "KEYCODE_BACK"]:
            self.rpc_operation(label, lambda d: d.press("back"))
            return ""
        return super().shell(argv, label, limit)

    def dump(self, label):
        xml = self.rpc_operation("Persistent UI hierarchy dump",
                                 lambda d: d.dump_hierarchy(compressed=False, max_depth=50))
        try:
            tree = ET.fromstring(xml)
        except ET.ParseError:
            raise DriverError("Persistent UI hierarchy was not valid XML") from None
        if tree.find("node") is None:
            raise DriverError("Persistent UI hierarchy was empty")
        self.dump_number += 1
        safe_label = re.sub(r"[^a-z0-9-]", "-", label.lower())[:50]
        (self.output / f"{self.dump_number:03d}-{safe_label}.xml").write_text(self.redact(xml))
        self.last_tree, self.last_xml = tree, xml
        return tree

    def enter(self, label, value):
        self.tap(self.field(label), label)

        def set_focused_text(device):
            target = device(packageName=PACKAGE, className="android.widget.EditText", focused=True)
            if target.count != 1:
                raise DriverError("Connection input does not have one focused app EditText")
            # UiObject.set_text is Android UI accessibility text input. It does
            # not use clipboard, custom IME, preferences, or direct server APIs.
            target.set_text(value, timeout=self.remaining(10))

        self.rpc_operation("Enter " + label, set_focused_text)
        self.hide_keyboard()
        self.event("field_entered", field=label, secret=label in ("Video passphrase", "Publish password"),
                   inputMethod="UiAutomator EditText.set_text")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", default="emulator-5580")
    parser.add_argument("--connection", required=True)
    parser.add_argument("--output", required=True, help="A new directory; parent must already exist")
    parser.add_argument("--adb-port", type=int, default=5038)
    parser.add_argument("--android-user-home")
    parser.add_argument("--backend", choices=("adb", "uiautomator2"), default="adb")
    parser.add_argument("--python-lib", help="Isolated dependency directory for the pinned uiautomator2 backend")
    parser.add_argument("--phase", choices=("configure", "practice", "stop"), default="configure")
    parser.add_argument("--timeout-seconds", type=int, default=900)
    args = parser.parse_args()
    if (not Path(args.adb).is_absolute() or not Path(args.adb).is_file()
            or not re.fullmatch(r"emulator-\d{4,5}", args.serial)
            or not 1024 <= args.adb_port <= 65535 or not 60 <= args.timeout_seconds <= 1200):
        parser.error("Use an absolute existing adb, an emulator serial, an unprivileged port, and a 60–1200 second timeout")
    if args.backend == "uiautomator2" and (not args.python_lib or not Path(args.python_lib).is_absolute()
                                          or not Path(args.python_lib).is_dir()):
        parser.error("The persistent UI backend requires an absolute existing --python-lib directory")
    driver = None
    try:
        values = connection_values(args.connection)
        driver = (UiAutomator2Driver if args.backend == "uiautomator2" else Driver)(args, values)
        # Also bound Python dependency startup/retries, beyond ADB subprocess
        # and individual RPC deadlines. This standalone Linux driver is run in
        # its own process, so no caller timers or threads are repurposed.
        def hard_deadline(signum, frame):
            raise DriverError("UI phase hard deadline exceeded")
        signal.signal(signal.SIGALRM, hard_deadline)
        signal.setitimer(signal.ITIMER_REAL, args.timeout_seconds)
        driver.run()
        signal.setitimer(signal.ITIMER_REAL, 0)
        driver.finish(True)
        return 0
    except (DriverError, OSError, ValueError, KeyError) as exc:
        signal.setitimer(signal.ITIMER_REAL, 0)
        if driver is not None:
            driver.event("phase_failed", error=driver.redact(str(exc)))
            # Connection dialog screenshots are intentionally never retained.
            if time.monotonic() + 10 < driver.deadline:
                try:
                    driver.screenshot("failure")
                except DriverError:
                    pass
            driver.finish(False, str(exc))
        else:
            # Invalid configuration diagnostics intentionally omit raw values.
            print(json.dumps({"success": False, "error": "Invalid isolated test configuration or output path"}))
        return 1


if __name__ == "__main__":
    sys.exit(main())
