"""Check a release APK against the AutoJs6 already installed on a phone over adb (roadmap P11.11, D78).

The release matrix keeps each phone's AutoJs6 as it is (no host is installed or removed). For one device this
check prints the host build (versionCode, last update, debuggable) and the plugin's switch in the plugin center
(read with run-as on a debuggable host), brings the host's launcher activity to the front before each step,
then runs, in order:

1. `verify_host_handoff.py` with the release APK: the editor's "More" entry hands a script to the designer;
2. `verify_editor_preview.py` with the release APK: the editor's layout preview pane and its designer action;
3. `run_device_tests.py` with the debug and androidTest APKs for the bridging classes (default
   `UiComposeLinkDeviceTest`, `UiInteractiveDeviceTest`, `DesignerTsxDeviceTest`; cases whose assumptions do
   not hold on the device, such as the Android version or a missing TypeScript Engine, are skipped by the
   tests themselves).

Afterwards it installs the release APK again (`-r -d`, the debug build carries the same build number) and
removes the test package, so the phone ends with the released plugin. It never grants storage access: the host
on the phone must already be able to write /sdcard/Download and its scripts directory. Labels are matched in the
language the device shows: pass the host's and the plugin's strings.xml for that language (for a zh-CN device,
`values-zh` of both; the plugin's defaults to English). Output keeps one line per
step (PASS / FAIL with the key values) and the class summaries; the exit code is 0 when every step passed.

usage: verify_release_on_device.py --serial <serial> --apk <release apk> --debug-apk <debug apk>
                                   --test-apk <androidTest apk> --host-strings <AutoJs6 values/strings.xml>
                                   [--strings <plugin values/strings.xml>] [--class <simple name> ...] [--skip-tests]
"""
import argparse
import os
import re
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import verify_standalone_launch as launch  # noqa: E402

HOST = "org.autojs.autojs6"
TEST_PACKAGE = f"{launch.PACKAGE}.test"
DEFAULT_CLASSES = ["UiComposeLinkDeviceTest", "UiInteractiveDeviceTest", "DesignerTsxDeviceTest"]


def host_summary(adb: launch.Adb) -> str:
    dump = adb.shell(f"dumpsys package {HOST}", check=False)
    version = re.search(r"versionCode=(\d+)", dump)
    updated = re.search(r"lastUpdateTime=([\d-]+ [\d:]+)", dump)
    debuggable = re.search(r"flags=\[[^\]]*\bDEBUGGABLE\b", dump) is not None
    state = "unknown (host not debuggable)"
    if debuggable:
        prefs = adb.shell(f"run-as {HOST} cat shared_prefs/plugin_center_enable_state.xml", check=False)
        if "<map" not in prefs:
            state = "unknown (no preferences)"
        else:
            entry = next((line.strip() for line in prefs.splitlines() if launch.PACKAGE in line), None)
            state = f"stored {entry[:160]}" if entry else "default"
    plugin = re.search(r"versionCode=(\d+)", adb.shell(f"dumpsys package {launch.PACKAGE}", check=False))
    tsx = re.search(r"versionCode=(\d+)", adb.shell("dumpsys package io.github.supermonster003.autojs6.plugin.typescript", check=False))
    api = adb.shell("getprop ro.build.version.sdk").strip()
    model = adb.shell("getprop ro.product.model").strip()
    return (
        f"device={model} api={api} host={version.group(1) if version else 'missing'} "
        f"host_updated={updated.group(1) if updated else '?'} debuggable={str(debuggable).lower()} "
        f"plugin_before={plugin.group(1) if plugin else 'none'} plugin_switch={state} "
        f"typescript_engine={tsx.group(1) if tsx else 'none'}"
    )


def host_to_front(adb: launch.Adb) -> None:
    adb.shell(f"am force-stop {HOST}", check=False)
    adb.shell("input keyevent KEYCODE_WAKEUP", check=False)
    adb.shell("wm dismiss-keyguard", check=False)
    adb.shell(f"monkey -p {HOST} -c android.intent.category.LAUNCHER 1", check=False)
    time.sleep(3)


def step(name: str, command: list[str], ok_marker: str | None) -> bool:
    # The child scripts print host labels (Chinese on a zh host); have them write UTF-8 whatever the console uses.
    env = {**os.environ, "PYTHONIOENCODING": "utf-8"}
    result = subprocess.run([sys.executable, *command], capture_output=True, text=True, encoding="utf-8", errors="replace", env=env)
    output = (result.stdout + result.stderr).strip()
    if ok_marker is not None:
        line = next((line for line in output.splitlines() if line.startswith(ok_marker)), "")
        passed = result.returncode == 0 and bool(line)
        detail = line if passed else (output.splitlines()[-1] if output else f"exit {result.returncode}")
        print(f"{'PASS' if passed else 'FAIL'} {name}: {detail[:400]}")
        return passed
    summary = [line for line in output.splitlines() if line.startswith(("FAIL", "SKIP", "----", "===="))]
    print(f"{'PASS' if result.returncode == 0 else 'FAIL'} {name}")
    for line in summary:
        print(f"    {line[:400]}")
    return result.returncode == 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--apk", type=Path, required=True, help="the release APK to leave on the device")
    parser.add_argument("--debug-apk", type=Path, required=True)
    parser.add_argument("--test-apk", type=Path, required=True)
    parser.add_argument("--host-strings", type=Path, required=True)
    parser.add_argument("--strings", type=Path, help="the plugin's values[-xx]/strings.xml in the device language (default English)")
    parser.add_argument("--class", dest="classes", action="append", default=[], help="override the default bridging classes")
    parser.add_argument("--skip-tests", action="store_true", help="only the two release APK walks")
    args = parser.parse_args()
    # A console that cannot show a character (a GBK one, say) gets a replacement instead of a traceback.
    sys.stdout.reconfigure(errors="replace")
    adb = launch.Adb(args.serial)
    if "package:" not in adb.shell(f"pm path {HOST}", check=False):
        print(f"FAIL host: {HOST} is not installed")
        return 1
    print("INFO " + host_summary(adb))
    serial = ["--serial", args.serial]
    results = []
    try:
        # Install the release first: the host reacts to a plugin upgrade, and a cold host on an old phone can then
        # miss the first editor wait (seen on G8441 going from build 144 to 224).
        install = adb.run("install", "-r", "-d", str(args.apk), check=False, timeout=300)
        if "Success" not in install:
            print(f"FAIL install: {install.strip()[-200:]}")
            return 1
        host_to_front(adb)
        plugin_strings = ["--strings", str(args.strings)] if args.strings else []
        results.append(step("editor More entry (release)", [str(HERE / "verify_host_handoff.py"), *serial, "--apk", str(args.apk), "--host-strings", str(args.host_strings), *plugin_strings], "HOST_HANDOFF_OK"))
        host_to_front(adb)
        results.append(step("editor preview pane (release)", [str(HERE / "verify_editor_preview.py"), *serial, "--apk", str(args.apk), "--host-strings", str(args.host_strings)], "EDITOR_PREVIEW_OK"))
        if not args.skip_tests:
            host_to_front(adb)
            classes = [item for name in (args.classes or DEFAULT_CLASSES) for item in ("--class", name)]
            results.append(step("bridging device tests (debug)", [str(HERE / "run_device_tests.py"), *serial, "--apk", str(args.debug_apk), "--test-apk", str(args.test_apk), *classes], None))
    finally:
        restore = adb.run("install", "-r", "-d", str(args.apk), check=False, timeout=300)
        adb.run("uninstall", TEST_PACKAGE, check=False)
        adb.shell(f"am force-stop {launch.PACKAGE}", check=False)
        plugin = re.search(r"versionCode=(\d+)", adb.shell(f"dumpsys package {launch.PACKAGE}", check=False))
        left = "present" if "package:" in adb.shell(f"pm path {TEST_PACKAGE}", check=False) else "removed"
        print(f"INFO restore: {'Success' if 'Success' in restore else restore.strip()[-200:]} plugin_after={plugin.group(1) if plugin else 'none'} test_package={left}")
    passed = sum(results)
    print(f"==== {passed}/{len(results)} steps passed on {args.serial}")
    return 0 if results and all(results) else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except subprocess.TimeoutExpired as error:
        sys.exit(f"adb timed out: {error}")
