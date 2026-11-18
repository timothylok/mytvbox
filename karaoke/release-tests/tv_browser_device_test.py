"""
Device test: the on-TV song browser, driven with the remote's keys (adb input keyevent), checked through /api/state ("browse", "playing", "upcoming").

Needs a box that is IDLE (nothing playing, empty queue: the test refuses otherwise), a library of at least 3 songs, a DEBUG build (for the pairing
token; or pass --token), and adb. It plays a couple of songs for a few seconds and leaves the box idle with an empty queue.

Sequence: OK on the idle screen opens the browser -> Back closes it -> OK x3 reaches the songs of the first artist -> OK adds the song, which starts
playing and closes the browser -> Back while it plays opens the browser again (the song keeps playing) -> OK x3 adds a second song to the queue ->
holding OK queues a third -> Back x3 closes the browser -> no crash in logcat.

usage:  python tv_browser_device_test.py --device 192.168.3.97:2025 [--adb PATH] [--token T]
"""
import argparse, json, os, re, subprocess, sys, time, urllib.request, urllib.error

PKG = "com.superghosttim.karaokeking"
HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_ADB = os.path.join(HERE, "..", "..", "tools", "sdk", "platform-tools", "adb.exe")
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

    def key(self, k, wait=1.5, long=False):
        self.run("shell", "input", "keyevent", *(["--longpress"] if long else []), k)
        time.sleep(wait)


def state(base, token):
    req = urllib.request.Request(base + "/api/state", headers={"Cookie": "karaoke_k=" + token})
    try:
        with urllib.request.urlopen(req, timeout=8) as r:
            return json.loads(r.read().decode("utf-8"))
    except Exception:
        return {}


def post(base, token, path):
    req = urllib.request.Request(base + path, data=b"", method="POST", headers={"Cookie": "karaoke_k=" + token})
    try:
        urllib.request.urlopen(req, timeout=8).read()
    except Exception:
        pass


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--adb", default=DEFAULT_ADB)
    ap.add_argument("--device", required=True)
    ap.add_argument("--token", default="")
    a = ap.parse_args()
    box = Box(a.adb, a.device)
    box.run("connect", a.device)
    base = "http://" + a.device.split(":")[0] + ":8080"
    token = a.token
    if not token:
        m = re.search(r'pair">([a-z0-9]+)', box.run("shell", "run-as %s cat shared_prefs/karaoke.xml" % PKG))
        token = m.group(1) if m else ""
    check("0. pairing token available", bool(token))

    st = state(base, token)
    if not check("1. the app answers", bool(st), st):
        return 1
    if st.get("playing") or st.get("paused") or st.get("current") or st.get("upcoming"):
        print("ABORT  the box is not idle (a song is playing or queued); the test will not interrupt it")
        return 2
    if st.get("songCount", 0) < 3:
        print("ABORT  the library needs at least 3 songs")
        return 2
    box.run("logcat", "-c")
    box.run("shell", "monkey -p %s -c android.intent.category.LEANBACK_LAUNCHER 1" % PKG)
    time.sleep(6)

    try:
        box.key("KEYCODE_DPAD_CENTER", 2)
        check("2. OK on the idle screen opens the browser", state(base, token).get("browse") is True, state(base, token))
        box.key("KEYCODE_BACK", 2)
        check("3. Back at the top level closes it", state(base, token).get("browse") is False)

        box.key("KEYCODE_DPAD_CENTER", 2)                       # open
        box.key("KEYCODE_DPAD_CENTER", 2)                       # first language
        box.key("KEYCODE_DPAD_CENTER", 2)                       # first artist
        st = state(base, token)
        check("4. drilling language -> artist -> songs adds nothing", st.get("browse") is True and not st.get("playing") and not st.get("upcoming"), st)

        box.key("KEYCODE_DPAD_CENTER", 5)                       # add the first song (the box is idle)
        st = state(base, token)
        check("5. adding the first song starts it and closes the browser", st.get("playing") is True and st.get("browse") is False and not st.get("upcoming"), st)

        box.key("KEYCODE_BACK", 2)
        st = state(base, token)
        check("6. Back while a song plays opens the browser, the song keeps playing", st.get("browse") is True and st.get("playing") is True, st)
        pos1 = st.get("posMs", 0)
        time.sleep(3)
        check("6b. playback goes on behind the browser", state(base, token).get("posMs", 0) > pos1 + 1500)

        box.key("KEYCODE_DPAD_CENTER", 2)                       # language
        box.key("KEYCODE_DPAD_CENTER", 2)                       # artist
        box.key("KEYCODE_DPAD_CENTER", 1.5)                     # add a song
        st = state(base, token)
        check("7. OK on a song queues it behind the current one", len(st.get("upcoming", [])) == 1 and st.get("browse") is True, st)

        box.key("KEYCODE_DPAD_CENTER", 2, long=True)            # hold OK: play next
        st = state(base, token)
        check("8. holding OK queues a song to play next", len(st.get("upcoming", [])) == 2, st)

        for _ in range(3):
            box.key("KEYCODE_BACK", 1.5)
        st = state(base, token)
        check("9. Back out of the songs, the artists and the languages closes the browser", st.get("browse") is False and st.get("playing") is True, st)

        box.key("KEYCODE_BACK", 2)                              # reopen, then OK on the first language, Back returns to the language list
        box.key("KEYCODE_DPAD_CENTER", 2)
        box.key("KEYCODE_BACK", 1.5)
        check("10. Back from the artists returns to the languages (still open)", state(base, token).get("browse") is True)
        box.key("KEYCODE_BACK", 1.5)
    finally:
        post(base, token, "/api/queue/clear")
        post(base, token, "/api/player/skip")
        time.sleep(2)
        box.key("KEYCODE_HOME", 1)
        st = state(base, token)
        check("11. cleanup: the box is idle with an empty queue", not st.get("playing") and not st.get("upcoming"), st)

    log = box.run("logcat", "-d")
    crash = [l for l in log.splitlines() if "FATAL EXCEPTION" in l or "ANR in " + PKG in l]
    check("12. no crash or ANR in logcat", not crash, crash[:2])
    print("ALL PASSED" if fails == 0 else "%d FAILED" % fails)
    return 0 if fails == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
