"""Check the layout preview pane of the AutoJs6 editor and its hand-over to the designer over adb (roadmap P11.11).

AutoJs6 6.8.0 shows a "Preview" switch in the editor toolbar for UI mode JavaScript scripts; the pane lays out
the first `ui.layout(...)` without running the script and, when 3-Block Compose 1.2.0 or later is installed,
offers "Open in 3-Block Compose designer". The host's own device test covers the pane in detail on a debug
build; this check walks the same entry on any installed AutoJs6 (a public release included), together with the
plugin APK it installs: it pushes a small UI script to /sdcard/Download, opens it through the host's public
EditIntentActivity, turns the preview on, waits for the laid out Button inside the pane, taps the pane's
designer action, waits for the designer outline to list `button #ok`, goes back, turns the preview off again
(so the host no longer remembers the file) and removes the pushed file.

The host must be able to read /sdcard/Download; on an emulator pass --grant-storage (never on a phone). Labels
come from the host's strings.xml in the language the host shows.

usage: verify_editor_preview.py --apk <apk> --host-strings <AutoJs6/app/src/main/res/values[-xx]/strings.xml>
                                [--serial <serial>] [--grant-storage] [--uninstall]
"""
import argparse
import re
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import verify_host_handoff as handoff  # noqa: E402
import verify_standalone_launch as launch  # noqa: E402

HOST = handoff.HOST
SCRIPT = (
    '"ui";\n'
    "\n"
    "ui.layout(\n"
    '    <vertical padding="16">\n'
    '        <text id="title" text="Preview check"/>\n'
    '        <button id="ok" text="Preview button"/>\n'
    "    </vertical>\n"
    ");\n"
)
OUTLINE_ROW = "button #ok"


def host_node(adb: launch.Adb, suffix: str, root=None):
    root = root if root is not None else launch.ui_tree(adb)
    return next((node for node in root.iter("node") if node.get("package") == HOST and (node.get("resource-id") or "").endswith(suffix)), None)


def bounds(node) -> tuple[int, int, int, int]:
    left, top, right, bottom = map(int, re.findall(r"\d+", node.get("bounds", "")))
    return left, top, right, bottom


def laid_out_button(adb: launch.Adb):
    root = launch.ui_tree(adb)
    pane = host_node(adb, ":id/editor_ui_preview_host", root)
    if pane is None:
        return None
    # The Material button theme shows the text in capitals, and the accessibility node reports what is shown.
    button = next((node for node in pane.iter("node") if (node.get("text") or "").strip().casefold() == "preview button"), None)
    return (root, pane, button) if button is not None else None


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--host-strings", type=Path, required=True)
    parser.add_argument("--serial")
    parser.add_argument("--grant-storage", action="store_true", help="emulators only: let the host read /sdcard/Download")
    parser.add_argument("--uninstall", action="store_true", help="remove the plugin from the device afterwards")
    args = parser.parse_args()
    adb = launch.Adb(args.serial)
    open_in_designer = launch.resource_string(args.host_strings, "text_open_in_three_block_compose_designer")
    host_editor = f"{HOST}/{handoff.EDITOR_CLASS}"

    if "package:" not in adb.shell(f"pm path {HOST}", check=False):
        raise SystemExit(f"{HOST} is not installed")
    api = adb.shell("getprop ro.build.version.sdk").strip()
    host_version = re.search(r"versionCode=(\d+)", adb.shell(f"dumpsys package {HOST}", check=False))
    if args.grant_storage:
        adb.shell(f"appops set {HOST} MANAGE_EXTERNAL_STORAGE allow", check=False)
        for permission in ("READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE"):
            adb.shell(f"pm grant {HOST} android.permission.{permission}", check=False)
    install = adb.install(args.apk)
    if "Success" not in install:
        raise SystemExit(f"install failed:\n{install}")
    adb.shell(f"am force-stop {launch.PACKAGE}", check=False)
    adb.shell("input keyevent KEYCODE_WAKEUP", check=False)
    adb.shell("wm dismiss-keyguard", check=False)

    remote = f"/sdcard/Download/three-block-compose-editor-preview-{int(time.time())}.js"
    local = Path(__file__).resolve().parent / ".editor-preview.js"
    local.write_text(SCRIPT, encoding="utf-8", newline="\n")
    try:
        adb.run("push", str(local), remote)
    finally:
        local.unlink(missing_ok=True)
    adb.run("logcat", "-c", check=False)
    preview_on = False
    try:
        start = adb.shell(f"am start -W -a android.intent.action.EDIT -d file://{remote} -t application/x-javascript -n {HOST}/{handoff.EDIT_INTENT_CLASS}")
        if "Error" in start:
            raise SystemExit(f"the AutoJs6 editor could not be opened:\n{start}")
        launch.wait_for("the AutoJs6 editor", lambda: launch.expand(host_editor) == launch.expand(handoff.host_top(adb) or ""))
        launch.wait_for("the script in the AutoJs6 editor", lambda: "Preview check" in handoff.editor_text(adb))
        switch = launch.wait_for("the Preview switch in the editor toolbar", lambda: host_node(adb, ":id/ui_preview"), timeout=15.0)
        if host_node(adb, ":id/editor_ui_preview_pane") is None:
            launch.tap(adb, switch)
        preview_on = True
        root, pane, _ = launch.wait_for("the laid out Button in the preview pane", lambda: laid_out_button(adb), timeout=30.0)
        message = host_node(adb, ":id/editor_ui_preview_message", root)
        if message is not None and (message.get("text") or "").strip():
            raise SystemExit(f"the preview pane reports a problem: {(message.get('text') or '').strip()[:120]}")
        screen = adb.shell("wm size").strip().split()[-1]
        width, height = map(int, screen.split("x"))
        left, top, _, _ = bounds(pane)
        place = "lower" if top >= height * 0.35 else "right" if left >= width * 0.35 else "other"
        action = host_node(adb, ":id/editor_ui_preview_open_in_designer", root)
        if action is None or (action.get("text") or "").strip() != open_in_designer:
            raise SystemExit("the preview pane does not offer the designer action")
        launch.tap(adb, action)
        launch.wait_for("the designer editor", lambda: (lambda t: t if t and launch.expand(t[0]) == launch.expand(launch.DESIGNER_ACTIVITY) else None)(launch.top_activity(adb)))
        launch.find_node(adb, f"the outline row {OUTLINE_ROW!r} in the designer", lambda n: OUTLINE_ROW in (n.get("text") or ""))
        adb.shell("input keyevent KEYCODE_BACK")
        launch.wait_for("the AutoJs6 editor after the designer", lambda: launch.expand(host_editor) == launch.expand(handoff.host_top(adb) or ""))
        launch.wait_for("the preview pane after the designer", lambda: laid_out_button(adb), timeout=20.0)
        launch.tap(adb, launch.wait_for("the Preview switch", lambda: host_node(adb, ":id/ui_preview"), timeout=15.0))
        launch.wait_for("the preview pane to close", lambda: host_node(adb, ":id/editor_ui_preview_pane") is None, timeout=15.0)
        preview_on = False
        crashes = launch.crash_lines(adb)
    except SystemExit as failure:
        crashes = launch.crash_lines(adb)
        detail = ("\nthe plugin process crashed:\n" + "\n".join(crashes)) if crashes else ""
        raise SystemExit(f"{failure}{detail}") from None
    finally:
        adb.shell(f"am force-stop {launch.PACKAGE}", check=False)
        if preview_on and launch.expand(host_editor) == launch.expand(handoff.host_top(adb) or ""):
            switch = host_node(adb, ":id/ui_preview")
            if switch is not None and host_node(adb, ":id/editor_ui_preview_pane") is not None:
                launch.tap(adb, switch)
        if launch.expand(host_editor) == launch.expand(handoff.host_top(adb) or ""):
            adb.shell("input keyevent KEYCODE_BACK", check=False)
        adb.shell(f"rm -f {remote} {launch.UI_DUMP}", check=False)
        if args.uninstall:
            adb.run("uninstall", launch.PACKAGE, check=False)
    if crashes:
        raise SystemExit("the plugin process crashed:\n" + "\n".join(crashes))
    print(f"EDITOR_PREVIEW_OK api={api} host={host_version.group(1) if host_version else '?'} pane={place} designer=opened apk={args.apk.name}")


if __name__ == "__main__":
    try:
        main()
    except subprocess.TimeoutExpired as error:
        sys.exit(f"adb timed out: {error}")
