"""
Device test: the app's own-folder fallback for TV boxes that have NO folder chooser (e.g. some Google TV images).

Runs on a box that really lacks a chooser (no activity handles ACTION_OPEN_DOCUMENT_TREE). On a box that has one it
prints SKIP and exits 0 (the folder-picker test covers that case).

Non-destructive: nothing is cleared; a throwaway song (1 s of silence + a 2-line .lrc) is put in a test folder
`ZZ Fallback Test/Test Artist/` inside the app's own folder and removed again at the end. The box must be idle
(nothing playing) - the test refuses to run otherwise. Needs ffmpeg on PATH, adb, a DEBUG build installed
(for the pairing token; or pass --token).

Checks: no chooser exists -> the app starts without the setup screen (no click needed) -> its own folder exists ->
a song copied there is found by a rescan (with its lyric file) -> it plays -> the library survives killing the app
-> phones still need the pairing token (401 without it) -> the OK key on the TV never leaves the app on a dead
"choose folder" screen -> no crash in logcat.

usage:  python app_folder_device_test.py --device 192.168.3.97:2025 [--adb PATH] [--token T]
"""
import argparse, json, os, re, subprocess, sys, tempfile, time, urllib.request, urllib.error

PKG = "com.setupbox.karaoke"
ACT = PKG + "/.MainActivity"
HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_ADB = os.path.join(HERE, "..", "..", "tools", "sdk", "platform-tools", "adb.exe")
TEST_DIR = "ZZ Fallback Test/Test Artist"
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

    def run(self, *a):
        r = subprocess.run([self.adb, "-s", self.dev] + list(a), capture_output=True, timeout=120)
        return r.stdout.decode("utf-8", "replace") + r.stderr.decode("utf-8", "replace")

    def sh(self, cmd):
        return self.run("shell", cmd)


def http(base, path, token, method="GET", data=None):
    req = urllib.request.Request(base + path, data=data, method=method)
    if token:
        req.add_header("Cookie", "karaoke_k=" + token)
    try:
        with urllib.request.urlopen(req, timeout=8) as r:
            return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, ""
    except Exception as e:
        return 0, str(e)


def state(base, token, wait=15, until=None):
    end = time.time() + wait
    last = {}
    while time.time() < end:
        code, body = http(base, "/api/state", token)
        if code == 200:
            try:
                last = json.loads(body)
                if until is None or until(last):
                    return last
            except ValueError:
                pass
        time.sleep(1)
    return last


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--adb", default=DEFAULT_ADB)
    ap.add_argument("--device", required=True)
    ap.add_argument("--token", default="")
    a = ap.parse_args()
    box = Box(a.adb, a.device)
    box.run("connect", a.device)
    base = "http://" + a.device.split(":")[0] + ":8080"

    # 0. is this a box without a chooser?
    out = box.sh("cmd package query-activities -a android.intent.action.OPEN_DOCUMENT_TREE")
    if "No activities found" not in out:
        print("SKIP  this box has a folder chooser; use folder_picker_ui_test.py")
        return 0
    check("0. this box has no folder chooser", True)

    token = a.token
    if not token:
        m = re.search(r'pair">([a-z0-9]+)', box.sh("run-as %s cat shared_prefs/karaoke.xml" % PKG))
        token = m.group(1) if m else ""
    check("0b. pairing token available (debug build or --token)", bool(token))

    box.sh("am start -n " + ACT)
    st = state(base, token, wait=20)
    if not check("1. the app runs and the phone page answers", bool(st), st):
        return 1
    if st.get("playing") or st.get("paused"):
        print("ABORT  the box is not idle (a song is playing); the test will not interrupt it")
        return 2
    base_count = st.get("songCount", 0)
    check("2. no setup screen: the app started without asking for a folder", st.get("setup") is False, st)

    folder = "/sdcard/Android/data/%s/files/Karaoke" % PKG
    check("3. the app made its own Karaoke folder", "No such file" not in box.sh("ls -d '%s'" % folder))

    # 4. put a throwaway song in
    tmp = tempfile.mkdtemp(prefix="fbtest_")
    mp3, lrc = os.path.join(tmp, "Fallback Test Song.mp3"), os.path.join(tmp, "Fallback Test Song.lrc")
    r = subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-f", "lavfi", "-i", "anullsrc=r=44100:cl=stereo", "-t", "8",
                        "-c:a", "libmp3lame", "-b:a", "64k", mp3], capture_output=True)
    if r.returncode != 0:
        check("4. make a test mp3 with ffmpeg", False, r.stderr.decode("utf-8", "replace"))
        return 1
    with open(lrc, "w", encoding="utf-8") as f:
        f.write("[00:01.00]first test line\n[00:04.00]second test line\n")
    dest = folder + "/" + TEST_DIR
    box.sh("mkdir -p '%s'" % dest)
    box.run("push", mp3, dest + "/Fallback Test Song.mp3.part")
    box.run("push", lrc, dest + "/Fallback Test Song.lrc")
    box.sh("mv '%s/Fallback Test Song.mp3.part' '%s/Fallback Test Song.mp3'" % (dest, dest))

    song_id = ""
    try:
        http(base, "/api/rescan", token, "POST", b"")
        st = state(base, token, wait=20, until=lambda s: s.get("songCount", 0) > base_count)
        check("4. a rescan finds the copied song", st.get("songCount", 0) == base_count + 1, st.get("songCount"))
        code, body = http(base, "/api/library?q=Fallback", token)
        songs = []
        try:
            d = json.loads(body)
            songs = d["songs"] if isinstance(d, dict) else d
        except ValueError:
            pass
        mine = [s for s in songs if "Fallback Test Song" in s.get("file", "")]
        check("5. the library lists it, with its lyric file", len(mine) == 1 and mine[0].get("lrc") is True, songs[:2])
        if mine:
            song_id = mine[0]["id"]

            # 6. plays
            http(base, "/api/queue/add", token, "POST", ("song=%s&singer=test" % song_id).encode())
            st = state(base, token, wait=15, until=lambda s: s.get("playing") and s.get("posMs", 0) > 500)
            check("6. the song plays from the app folder", bool(st.get("playing")) and st.get("posMs", 0) > 500, st)
            http(base, "/api/player/skip", token, "POST", b"")
            state(base, token, wait=8, until=lambda s: not s.get("playing"))

            # 7. process death: the library comes back without asking
            box.sh("am force-stop " + PKG)
            st = {}
            for _ in range(3):                      # the box's home app can win the first start: try again
                time.sleep(2)
                box.sh("am start -n " + ACT)
                st = state(base, token, wait=12, until=lambda s: s.get("songCount", 0) > base_count)
                if st.get("songCount", 0) > base_count:
                    break
            check("7. after killing the app the library is back, no setup screen",
                  st.get("songCount", 0) == base_count + 1 and st.get("setup") is False, st)

        # 8. pairing still enforced
        code, _ = http(base, "/api/state", "")
        check("8. without the pairing token the phone API says 401", code == 401, code)

        # 9. the OK key must not leave a dead "choose folder" screen
        box.sh("input keyevent KEYCODE_DPAD_CENTER")
        time.sleep(2)
        st = state(base, token, wait=5)
        check("9. the OK key on the TV does not bring up a setup screen", st.get("setup") is False, st)
    finally:
        box.sh("rm -rf '%s'" % (folder + "/ZZ Fallback Test"))
        http(base, "/api/rescan", token, "POST", b"")
        st = state(base, token, wait=20, until=lambda s: s.get("songCount", 0) == base_count)
        check("10. cleanup: the test song is gone again", st.get("songCount", -1) == base_count, st.get("songCount"))
        for f in (mp3, lrc):
            try:
                os.remove(f)
            except OSError:
                pass
        try:
            os.rmdir(tmp)
        except OSError:
            pass

    log = box.run("logcat", "-d")
    crash = [l for l in log.splitlines() if "FATAL EXCEPTION" in l or "ANR in " + PKG in l]
    check("11. no crash or ANR in logcat", not crash, crash[:2])
    print("ALL PASSED" if fails == 0 else "%d FAILED" % fails)
    return 0 if fails == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
