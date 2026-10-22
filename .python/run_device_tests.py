"""Run designer device test classes on one device over adb and print one line per test.

Roadmap P11.8 (compose link) and the regressions around it: installs the given APKs (optionally an AutoJs6 debug
build first), lets the host write the scripts directory on an emulator (`--grant-storage`, never on a phone
someone uses), runs each class with `am instrument` and prints PASS / FAIL / SKIP per test with the first line of
a failure, then one summary line per class. `--all` runs the whole test APK in one `am instrument` (release gate on
one emulator; `connectedDebugAndroidTest` would run on every connected device) and prints only FAIL / SKIP lines and
the per-class summaries. The exit code is 0 when no test failed.

usage: run_device_tests.py --serial <serial> [--apk <plugin debug apk> --test-apk <androidTest apk>]
                           [--host-apk <AutoJs6 debug apk>] [--grant-storage]
                           (--all | --class UiComposeLinkDeviceTest [--class UiPreviewDeviceTest ...]
                                    [--method <test method of a single class>])
"""
import argparse
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import verify_standalone_launch as launch  # noqa: E402

PACKAGE = launch.PACKAGE
HOST_PACKAGE = "org.autojs.autojs6"
RUNNER = f"{PACKAGE}.test/androidx.test.runner.AndroidJUnitRunner"
TEST_PACKAGE = f"{PACKAGE}.designer"


def run_tests(adb: launch.Adb, label: str, selector: str, quiet: bool) -> tuple[int, int, int]:
    output = adb.shell(f"am instrument -w -r {selector}{RUNNER}", check=False, timeout=7200)
    counts: dict[str, list[int]] = {}
    # Each status block ends with its code: 1 started, 0 passed, -3 / -4 skipped, else failed. Its keys come in
    # alphabetical order, so the stack of a failure stands before the test name.
    parts = re.split(r"INSTRUMENTATION_STATUS_CODE: (-?\d+)", output)
    for block, code_text in zip(parts[0::2], parts[1::2]):
        test = re.search(r"INSTRUMENTATION_STATUS: test=(\S+)", block)
        owner = re.search(r"INSTRUMENTATION_STATUS: class=\S*?(\w+)\s", block)
        code = int(code_text)
        if test is None or code == 1:
            continue
        simple_name, name = owner.group(1) if owner else label, test.group(1)
        count = counts.setdefault(simple_name, [0, 0, 0])
        if code == 0:
            count[0] += 1
            if not quiet:
                print(f"PASS {simple_name}#{name}")
        elif code in (-3, -4):
            count[2] += 1
            print(f"SKIP {simple_name}#{name}")
        else:
            count[1] += 1
            stack = re.search(r"INSTRUMENTATION_STATUS: stack=(.*?)(?:\nINSTRUMENTATION_STATUS|$)", block, re.S)
            lines = stack.group(1).strip().splitlines() if stack else ["see logcat"]
            at = next((line.strip() for line in lines if f"{simple_name}.kt" in line), "")
            print(f"FAIL {simple_name}#{name}: {lines[0][:300]} {at}")
    if not counts:
        counts[label] = [0, 1, 0]
        print(f"FAIL {label}: no test ran: " + output[-1500:])
    elif "INSTRUMENTATION_CODE: -1" not in output:
        # The run itself ended early (process crash, timeout): the tests that never reported are not passes.
        counts.setdefault(label, [0, 0, 0])[1] += 1
        result = " ".join(re.findall(r"INSTRUMENTATION_(?:RESULT|CODE): (.*)", output))
        print(f"FAIL {label}: the instrumentation did not finish: " + (result or output[-500:])[:500])
    for simple_name, (passed, failed, skipped) in counts.items():
        print(f"---- {simple_name}: {passed} passed, {failed} failed, {skipped} skipped")
    return tuple(sum(count[index] for count in counts.values()) for index in range(3))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    parser.add_argument("--serial", required=True)
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--test-apk", type=Path)
    parser.add_argument("--host-apk", type=Path)
    parser.add_argument("--grant-storage", action="store_true", help="emulators only: let the host write the scripts directory")
    parser.add_argument("--class", dest="classes", action="append", default=[], help="simple name of a class in the designer test package")
    parser.add_argument("--method", help="one test method (with a single --class)")
    parser.add_argument("--all", action="store_true", help="every test of the test APK in one run")
    args = parser.parse_args()
    if args.all == bool(args.classes):
        parser.error("give either --all or at least one --class")
    if args.method and len(args.classes) != 1:
        parser.error("--method needs exactly one --class")
    adb = launch.Adb(args.serial)
    for apk in [args.host_apk, args.apk, args.test_apk]:
        if apk is None:
            continue
        result = adb.install(apk)
        print(f"{'OK  ' if 'Success' in result else 'FAIL'} install {apk.name}")
        if "Success" not in result:
            return 1
    if args.grant_storage:
        # Android 11+: all files access; before it, the storage runtime permissions.
        adb.shell(f"appops set {HOST_PACKAGE} MANAGE_EXTERNAL_STORAGE allow", check=False)
        for permission in ("READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE"):
            adb.shell(f"pm grant {HOST_PACKAGE} android.permission.{permission}", check=False)
    totals = [0, 0, 0]
    runs = [("all", "", True)] if args.all else [
        (simple_name, f"-e class {TEST_PACKAGE}.{simple_name}{'#' + args.method if args.method else ''} ", False)
        for simple_name in args.classes]
    for label, selector, quiet in runs:
        for index, count in enumerate(run_tests(adb, label, selector, quiet)):
            totals[index] += count
    print(f"==== {totals[0]} passed, {totals[1]} failed, {totals[2]} skipped")
    return 1 if totals[1] else 0


if __name__ == "__main__":
    sys.exit(main())
