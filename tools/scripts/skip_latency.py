"""Press the remote's Right key (skip) on the box and time how long until the next song is audible."""
import json, subprocess, time, urllib.request

ADB = r"D:\ai\setupbox\tools\sdk\platform-tools\adb.exe"
BASE = "http://192.168.3.79:8080"


def state():
    return json.load(urllib.request.urlopen(BASE + "/api/state", timeout=3))


def post(path):
    urllib.request.urlopen(urllib.request.Request(BASE + path, method="POST"), timeout=5).read()


def wait_playing(min_pos, timeout=30):
    t = time.time()
    while time.time() - t < timeout:
        d = state()
        if d["current"] and d["playing"] and d["posMs"] > min_pos:
            return d
        time.sleep(0.2)
    return None


def skip_and_time():
    before = state()["current"]["id"]
    t0 = time.time()
    subprocess.run([ADB, "-s", "192.168.3.79:5555", "shell", "input", "keyevent", "KEYCODE_DPAD_RIGHT"], capture_output=True)
    t_key = time.time() - t0
    new_current = None
    while time.time() - t0 < 40:
        d = state()
        c = d["current"]
        if c and c["id"] != before and new_current is None:
            new_current = time.time() - t0
        if c and c["id"] != before and d["playing"] and d["posMs"] > 300:
            return t_key, new_current, time.time() - t0, c["title"][:20]
        time.sleep(0.1)
    return t_key, new_current, None, "timeout"


def main():
    d = json.load(urllib.request.urlopen(BASE + "/api/library?lang=Cantopop&limit=300"))
    video = [s for s in d["songs"] if s["video"]]
    audio = [s for s in d["songs"] if not s["video"]]
    q = lambda s: post("/api/queue/add?song=%s&singer=T&position=end" % s["id"])
    q(video[3])
    wait_playing(2000)
    q(audio[0]); q(video[5]); q(audio[1]); q(video[7])
    time.sleep(3)
    for label in ("video -> audio-only", "audio-only -> video", "video -> audio-only", "audio-only -> video"):
        key, newc, aud, title = skip_and_time()
        print("%-22s adb key %.2fs | queue moved after %s | audible after %s  (%s)" % (
            label, key, "%.2fs" % newc if newc else "-", "%.2fs" % aud if aud else "-", title))
        time.sleep(4)
    post("/api/queue/clear")
    post("/api/player/skip")


main()
