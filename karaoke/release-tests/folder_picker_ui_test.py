"""Release test: the first-run folder picker, driven on a real device (the TV box) over adb.

What it checks, in order:
  1. fresh state (app data cleared) -> the app asks for the Karaoke folder, with the USB warning   [/api/state: setup, setupText]
  2. the remote's OK key opens the system folder chooser (DocumentsUI)
  3. choosing the Karaoke folder + Allow -> the app returns, the library loads (song count >= --min-songs)
  4. the phone API lists songs
  5. restarting the app does NOT ask again (permission persisted) and the library is back
  6. asking for another folder (adb intent; the phone page has no such feature) shows the folder screen, and OK still opens the chooser
     even after a mouse/touch (regression: the OK key once did nothing in touch mode)
It cannot test the USB-rejection branch (needs a USB drive plugged in): that rule is covered by FolderPickerLogicTest.java.

This box's uiautomator cannot read screens ("could not get idle state"), so the app's state is read from its own API and the
system chooser is driven by screen position (ratios below, calibrated on the 1920x1080 Android 10 DocumentsUI of the TV box;
recalibrate ALLOW_ACCESS / ALLOW_DIALOG for another device).

The app's own files (history, queue, flips, offsets) are saved before the test and put back afterwards (--no-preserve to skip).
Needs: a debuggable build installed (run-as), the box reachable over adb, the Karaoke folder at /sdcard/Karaoke with songs.

usage:  python folder_picker_ui_test.py [--adb PATH] [--device 192.168.3.79:5555] [--min-songs 1] [--no-preserve]
"""
import argparse, json, os, re, subprocess, sys, tempfile, time, urllib.request

PKG = "com.setupbox.karaoke"
ACT = PKG + "/.MainActivity"
HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_ADB = os.path.join(HERE, "..", "..", "tools", "sdk", "platform-tools", "adb.exe")
PRIVATE = ["history.jsonl", "state.txt", "flips.txt", "lyric_offsets.properties"]
ALLOW_ACCESS = (0.5, 0.967)      # the blue "Allow access to 'Karaoke'" bar at the bottom of the chooser
ALLOW_DIALOG = (0.675, 0.571)    # "Allow" in the confirmation dialog
fails = 0


def check(name, ok, detail=""):
    global fails
    print(("PASS  " if ok else "FAIL  ") + name + (("  (" + str(detail) + ")") if detail and not ok else ""))
    if not ok:
        fails += 1
    return ok


class Box:
    def __init__(self, adb, dev):
        self.adb, self.dev = adb, dev
        self.w, self.h = 1920, 1080

    def run(self, *a, binary=False):
        r = subprocess.run([self.adb, "-s", self.dev] + list(a), capture_output=True, timeout=120)
        return r.stdout if binary else r.stdout.decode("utf-8", "replace") + r.stderr.decode("utf-8", "replace")

    def sh(self, cmd):
        return self.run("shell", cmd)

    def screen_size(self):
        m = re.search(r"(\d+)x(\d+)", self.sh("wm size"))
        if m:
            self.w, self.h = int(m.group(1)), int(m.group(2))

    def tap(self, ratio):
        self.sh("input tap %d %d" % (int(ratio[0] * self.w), int(ratio[1] * self.h)))

    def resumed(self):
        m = re.search(r"mResumedActivity: .*? ([\w.]+/[\w.$]+)", self.sh("dumpsys activity activities | grep mResumedActivity"))
        return m.group(1) if m else ""

    def wait_resumed(self, prefix, timeout=20):
        end = time.time() + timeout
        while time.time() < end:
            if self.resumed().startswith(prefix):
                return True
            time.sleep(1)
        return False


def api(url, post=False, timeout=8):
    req = urllib.request.Request(url, method="POST" if post else "GET", data=b"" if post else None)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read().decode("utf-8")


def state(base, wait=0, until=None):
    """/api/state as a dict, optionally polling until until(state) is true."""
    end = time.time() + wait
    st = {}
    while True:
        try:
            st = json.loads(api(base + "/api/state"))
            if until is None or until(st):
                return st
        except Exception:
            pass
        if time.time() >= end:
            return st
        time.sleep(1.5)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--adb", default=DEFAULT_ADB)
    ap.add_argument("--device", default="192.168.3.79:5555")
    ap.add_argument("--min-songs", type=int, default=1)
    ap.add_argument("--no-preserve", action="store_true")
    a = ap.parse_args()
    box = Box(a.adb, a.device)
    base = "http://" + a.device.split(":")[0] + ":8080"
    print(box.run("connect", a.device).strip())
    if PKG not in box.sh("pm list packages " + PKG):
        print("The app is not installed on the device."); return 2
    box.screen_size()

    saved = {}
    tmp = tempfile.mkdtemp(prefix="pickertest_")
    if not a.no_preserve:
        box.sh("am force-stop " + PKG)
        for f in PRIVATE:
            data = box.run("exec-out", "run-as", PKG, "cat", "files/" + f, binary=True)
            if data and b"run-as:" not in data[:20]:
                saved[f] = data
    try:
        # 1. fresh state: clear the app's data (this also drops the saved folder permission)
        box.sh("pm clear " + PKG)
        box.sh("am start -n " + ACT)
        st = state(base, wait=30, until=lambda s: s.get("setup") is True)
        check("1. first run asks for the Karaoke folder", st.get("setup") is True, st)
        txt = st.get("setupText", "")
        check("1b. the screen warns against USB drives", "Do NOT choose a USB drive" in txt, txt[:80])
        check("1c. no songs before a folder is chosen", st.get("songCount", -1) == 0, st.get("songCount"))

        # 2. the remote's OK key opens the system chooser
        box.sh("input keyevent KEYCODE_DPAD_CENTER")
        check("2. OK opens the system folder chooser", box.wait_resumed("com.android.documentsui"), box.resumed())
        time.sleep(2)

        # 3. allow access to the folder it opened in (the chooser starts in /sdcard/Karaoke)
        box.tap(ALLOW_ACCESS)
        time.sleep(2)
        box.tap(ALLOW_DIALOG)
        check("3a. back in the app after Allow", box.wait_resumed(PKG, 15), box.resumed())
        st = state(base, wait=40, until=lambda s: s.get("songCount", 0) >= a.min_songs)
        n = st.get("songCount", 0)
        check("3b. library loaded (%d songs, need >= %d)" % (n, a.min_songs), n >= a.min_songs)
        check("3c. the app no longer asks for the folder", st.get("setup") is False, st)

        # 4. phone API lists songs
        try:
            check("4. /api/library returns songs", '"songs":[{' in api(base + "/api/library?limit=3"))
        except Exception as e:
            check("4. /api/library returns songs", False, e)

        # 5. restart: no prompt, library back
        box.sh("am force-stop " + PKG)
        box.sh("am start -n " + ACT)
        st = state(base, wait=40, until=lambda s: s.get("songCount", 0) >= a.min_songs)
        check("5a. after a restart the library is back (%d songs)" % st.get("songCount", 0), st.get("songCount", 0) >= a.min_songs)
        check("5b. after a restart the app does NOT ask again", st.get("setup") is False, st)

        # 6. folder change from the phone page; OK must work even after a touch (touch mode)
        box.sh("am start -n " + ACT + " --ez pickfolder true")        # maintenance hook, not reachable from the phone/network
        st = state(base, wait=10, until=lambda s: s.get("setup") is True)
        check("6a. asking for another folder shows the folder screen", st.get("setup") is True, st)
        box.tap((0.5, 0.2))                                        # a touch somewhere: the screen goes into touch mode
        time.sleep(1)
        box.sh("input keyevent KEYCODE_DPAD_CENTER")
        check("6b. OK opens the chooser after a touch", box.wait_resumed("com.android.documentsui"), box.resumed())
        for _ in range(8):                                         # BACK climbs out of the chooser (it goes up a folder first)
            if box.resumed().startswith(PKG):
                break
            box.sh("input keyevent KEYCODE_BACK")
            time.sleep(1.5)
        st = state(base, wait=10, until=lambda s: s.get("setup") is True)
        check("6c. cancelling returns to the folder screen", box.resumed().startswith(PKG) and st.get("setup") is True, st)
    finally:
        box.sh("am force-stop " + PKG)
        for f, data in saved.items():
            p = os.path.join(tmp, f)
            open(p, "wb").write(data)
            box.run("push", p, "/data/local/tmp/" + f)
            box.sh("run-as %s cp /data/local/tmp/%s files/%s; rm /data/local/tmp/%s" % (PKG, f, f, f))
        if saved:
            print("restored", ", ".join(saved))
        box.sh("am start -n " + ACT)
        print("note: the folder permission was granted again in step 3, so the app is usable after the test")

    print("ALL PASSED" if fails == 0 else "%d FAILED" % fails)
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
