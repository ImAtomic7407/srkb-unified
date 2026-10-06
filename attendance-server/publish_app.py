"""Publish a new build of the Unified Android app, in one step, with nothing left to remember.

WHY THIS EXISTS. On 2026-08-13 a build went out with real fixes in it and no device ever
offered the update. The code was correct and the APK was correct; two bookkeeping steps had
simply been missed:

    attendease_new/app/build.gradle.kts   versionCode  - what the INSTALLED app reports
    Server file/app_release.json          latest_version_code - what the SERVER considers newest

/api/app/version compares those two. Leave either at the old number and the server answers
`update_available: false`, so a phone that is genuinely out of date is told it is current.
Nothing errors, nothing logs, and the only symptom is that the fixes never reach anyone.

`build.gradle.kts` already carried a comment saying "BUMP versionCode ON EVERY PUBLISHED
BUILD". A comment is not a mechanism - it only works while someone is reading it. This script
is the mechanism: it does both edits, keeps the two numbers in lockstep by construction, and
refuses to finish unless the running server actually offers the update to the previous build.

USAGE
    python publish_app.py --check                 # report state, change nothing
    python publish_app.py --notes "line one" ...  # bump, build, publish, verify
    python publish_app.py --notes-file notes.txt  # same, one note per line
    python publish_app.py ... --force-minimum     # ALSO make this build mandatory

Add --skip-build only if you have just built and know the APK on disk is current; it exists
for re-running the publish half after a failure, not as a shortcut.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import urllib.request
from datetime import date

ROOT = os.path.dirname(os.path.abspath(__file__))
APP_DIR = os.path.join(ROOT, "attendease_new")
GRADLE = os.path.join(APP_DIR, "app", "build.gradle.kts")
MANIFEST = os.path.join(ROOT, "Server file", "app_release.json")
APK_DIR = os.path.join(ROOT, "apk")
# The SIDELOAD flavour, always. See SIDELOAD_APPLICATION_ID below for why this is not a
# preference. Flavours were added 2026-08-18; before that there was one debug variant and this
# path was .../apk/debug/app-debug.apk.
BUILT_APK = os.path.join(APP_DIR, "app", "build", "outputs", "apk", "sideload", "debug",
                         "app-sideload-debug.apk")
SIDELOAD_APPLICATION_ID = "com.example"
LATEST_APK = os.path.join(APK_DIR, "SRKB-Unified-latest.apk")
VERSION_URL = "http://127.0.0.1:6061/api/app/version?version_code={code}"

# The distributed APK is the SIDELOAD DEBUG build, signed with the checked-in debug.keystore.
#
# Both halves of that are load-bearing and neither is a leftover:
#
#   sideload - Android keys an installed app by applicationId. Every phone in the field is
#              running com.example (verified 2026-08-18 off the shipped v31 APK). The `play`
#              flavour is in.bestsolarwala.srkb, so publishing it here would install a SECOND
#              copy of the app beside the real one on every device rather than updating it.
#
#   debug    - it is also keyed by SIGNING CERTIFICATE. v31 is signed CN=Android Debug,
#              SHA-256 d02c3e63..., which is the checked-in debug.keystore. An APK signed with
#              the Play upload key would be REFUSED at install with a signature mismatch.
#              A release key now exists (my-upload-key.jks, added 2026-08-18 for the Play AAB)
#              so the old note that one "cannot be produced here" no longer holds - which is
#              exactly why the flavour and the id are asserted below instead of assumed.
#
# Change the gradle task, the APK path and SIDELOAD_APPLICATION_ID together, never one alone.
GRADLE_TASKS = ["testSideloadDebugUnitTest", "assembleSideloadDebug"]


def fail(msg: str) -> "NoReturn":  # type: ignore[valid-type]
    print(f"\nABORTED: {msg}")
    sys.exit(1)


def read_gradle_version() -> tuple[int, str, str]:
    text = open(GRADLE, encoding="utf-8").read()
    code = re.search(r"^\s*versionCode\s*=\s*(\d+)", text, re.M)
    name = re.search(r'^\s*versionName\s*=\s*"([^"]+)"', text, re.M)
    if not code or not name:
        fail(f"could not find versionCode/versionName in {GRADLE}")
    return int(code.group(1)), name.group(1), text


def bump_version_name(name: str) -> str:
    """2.8 -> 2.9 -> 2.10. Minor only; major is a deliberate human decision."""
    m = re.fullmatch(r"(\d+)\.(\d+)", name)
    if not m:
        return name
    return f"{m.group(1)}.{int(m.group(2)) + 1}"


def load_manifest() -> dict:
    with open(MANIFEST, encoding="utf-8") as fh:
        return json.load(fh)


def show_state() -> tuple[int, str, dict]:
    code, name, _ = read_gradle_version()
    man = load_manifest()
    print(f"  build.gradle.kts   versionCode={code}  versionName={name}")
    print(f"  app_release.json   latest={man['latest_version_code']} "
          f"({man['latest_version_name']})  minimum={man['minimum_version_code']}")
    if code != man["latest_version_code"]:
        print("  !! OUT OF STEP - the app and the server disagree about what the newest build is.")
    return code, name, man


def server_says(code: int) -> dict:
    with urllib.request.urlopen(VERSION_URL.format(code=code), timeout=10) as r:
        return json.loads(r.read().decode("utf-8"))


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true", help="report state and exit")
    ap.add_argument("--notes", nargs="*", default=[], help="changelog lines, newest first")
    ap.add_argument("--notes-file", help="file with one changelog line per line")
    ap.add_argument("--label", default="release", help="goes in the archived APK filename")
    ap.add_argument("--skip-build", action="store_true")
    ap.add_argument("--force-minimum", action="store_true",
                    help="also raise minimum_version_code so this build is MANDATORY")
    args = ap.parse_args()

    print("current state:")
    old_code, old_name, man = show_state()
    if args.check:
        return

    notes = list(args.notes)
    if args.notes_file:
        notes += [ln.strip() for ln in open(args.notes_file, encoding="utf-8") if ln.strip()]
    if not notes:
        fail("refusing to publish with no release notes - they are shown on /app and in the app. "
             "Use --notes or --notes-file.")

    new_code = max(old_code, man["latest_version_code"]) + 1
    new_name = bump_version_name(old_name)
    print(f"\npublishing versionCode {new_code} ({new_name})")

    # 1. bump the app itself
    _, _, text = read_gradle_version()
    text = re.sub(r"^(\s*versionCode\s*=\s*)\d+", rf"\g<1>{new_code}", text, count=1, flags=re.M)
    text = re.sub(r'^(\s*versionName\s*=\s*)"[^"]+"', rf'\g<1>"{new_name}"', text, count=1, flags=re.M)
    open(GRADLE, "w", encoding="utf-8", newline="").write(text)
    print("  bumped build.gradle.kts")

    # 2. build (tests included - a build that fails its tests must never be published)
    if not args.skip_build:
        print(f"  running gradlew {' '.join(GRADLE_TASKS)} ...")
        gradlew = os.path.join(APP_DIR, "gradlew.bat" if os.name == "nt" else "gradlew")
        r = subprocess.run([gradlew, *GRADLE_TASKS, "--console=plain"], cwd=APP_DIR)
        if r.returncode != 0:
            fail("the build (or its unit tests) failed - nothing was published. "
                 "build.gradle.kts is already bumped; fix the failure and re-run.")
        print("  build + unit tests OK")
    if not os.path.exists(BUILT_APK):
        fail(f"no APK at {BUILT_APK}")

    # 3. confirm the APK really carries the new versionCode, rather than trusting the edit
    meta_path = os.path.join(os.path.dirname(BUILT_APK), "output-metadata.json")
    meta = json.load(open(meta_path, encoding="utf-8"))
    built = meta["elements"][0]["versionCode"]
    if built != new_code:
        fail(f"the built APK reports versionCode {built}, not {new_code}. Stale build output - "
             f"re-run without --skip-build.")
    # Read the applicationId back out of the build rather than trusting the path. The path can
    # be right while the id is wrong (someone edits the flavour, or defaultConfig regains an
    # applicationId that overrides it), and the consequence is not a failed publish - it is a
    # second app icon appearing on every phone in the field, which cannot be undone remotely.
    # A path that merely 404s is the good failure; this is the bad one, so it gets its own check.
    built_id = meta.get("applicationId")
    if built_id != SIDELOAD_APPLICATION_ID:
        fail(f"the built APK reports applicationId {built_id!r}, not "
             f"{SIDELOAD_APPLICATION_ID!r}. This is the PLAY build, or the flavour config "
             f"changed. Publishing it would install a second copy of the app on every device "
             f"instead of updating the one they have. Nothing was copied.")
    print(f"  APK reports versionCode {built}, applicationId {built_id}")

    # 4. publish: archived copy + the file the server serves
    archived = os.path.join(APK_DIR, f"SRKB-Unified-v{new_code}-{args.label}-{date.today():%Y%m%d}.apk")
    shutil.copy2(BUILT_APK, archived)
    shutil.copy2(BUILT_APK, LATEST_APK)
    print(f"  wrote {os.path.basename(archived)}")
    print(f"  wrote {os.path.basename(LATEST_APK)}")

    # 5. tell the server. Read on every request, so no restart is needed.
    man["latest_version_code"] = new_code
    man["latest_version_name"] = new_name
    if args.force_minimum:
        man["minimum_version_code"] = new_code
        print("  minimum_version_code raised - THIS BUILD IS NOW MANDATORY")
    man["notes"] = notes + man.get("notes", [])
    with open(MANIFEST, "w", encoding="utf-8") as fh:
        json.dump(man, fh, indent=2, ensure_ascii=False)
    print("  updated app_release.json")

    # 6. the check that would have caught the 2026-08-13 miss: ask the running server.
    try:
        before, after = server_says(old_code), server_says(new_code)
    except Exception as exc:  # noqa: BLE001
        fail(f"published, but could not reach {VERSION_URL.format(code=old_code)} to verify "
             f"({exc}). Start attendance-backend and re-check by hand.")
    print("\nverifying against the running server:")
    print(f"  a v{old_code} device is offered the update : {before['update_available']}")
    print(f"  a v{new_code} device is left alone          : {not after['update_available']}")
    if not before["update_available"]:
        fail("the server is NOT offering the update to the previous build - this is exactly the "
             "failure this script exists to prevent. Check app_release.json.")
    if after["update_available"]:
        fail("the server would prompt the NEW build to update itself - an update loop.")
    print("\nPublished. Devices will be offered the update on their next version check.")


if __name__ == "__main__":
    main()
