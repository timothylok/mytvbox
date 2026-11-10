"""Find the black bars burned into music videos, so the Karaoke app can zoom them away.

Many YouTube music videos are a widescreen picture inside a 4:3 frame (or pillar-boxed inside 16:9). This samples
8 frames per video with ffmpeg cropdetect, takes the UNION of the detected picture areas (so a dark scene or a fade
never cuts into the picture) and writes one line per video that has bars worth removing:

    <video file name without extension> = x:y:w:h:W:H        (picture area x,y,w,h inside a W x H frame)

The app reads this file as /sdcard/Karaoke/crops.txt (UTF-8, '#' = comment) and ignores a line if the video's real
size is not W x H. Videos with (almost) no bars get no line.

usage:  detect_crops.py DIR [DIR ...]  [--out D:\\ai\\Karaoke\\crops.txt] [--min 4]
        --min = smallest bar, in percent of the width/height, worth removing (default 4)
Existing lines for videos that are not in DIR are kept; lines for scanned videos are replaced. Logs to processed.csv.
"""
import argparse, os, re, subprocess, sys
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ledger import log

OUT = r"D:\ai\Karaoke\crops.txt"
SAMPLES = (0.08, 0.2, 0.32, 0.44, 0.56, 0.68, 0.8, 0.92)
CROP = re.compile(r"crop=(\d+):(\d+):(\d+):(\d+)")


def probe(path):
    r = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height:format=duration",
                        "-of", "csv=p=0:s=,", path], capture_output=True, text=True)
    try:
        lines = [l for l in r.stdout.split() if l]
        w, h = [int(x) for x in lines[0].split(",")[:2]]
        return w, h, float(lines[1].split(",")[-1])
    except Exception:
        return None


def detect(path, min_pct):
    p = probe(path)
    if not p:
        return None
    W, H, dur = p
    x1, y1, x2, y2 = W, H, 0, 0
    got = 0
    for frac in SAMPLES:
        r = subprocess.run(["ffmpeg", "-v", "info", "-ss", "%.2f" % (dur * frac), "-t", "2", "-i", path,
                            "-vf", "cropdetect=24:2:0", "-an", "-f", "null", "-"], capture_output=True, text=True, encoding="utf-8", errors="replace")
        m = CROP.findall(r.stderr)
        if not m:
            continue
        w, h, x, y = [int(v) for v in m[-1]]
        if w < W * 0.3 or h < H * 0.3:          # a black frame: ignore
            continue
        x1, y1, x2, y2 = min(x1, x), min(y1, y), max(x2, x + w), max(y2, y + h)
        got += 1
    if got < 4:
        return W, H, None
    x1, y1 = x1 - x1 % 2, y1 - y1 % 2
    x2, y2 = min(W, x2 + x2 % 2), min(H, y2 + y2 % 2)
    w, h = x2 - x1, y2 - y1
    # only worth it when a bar is at least min_pct of that dimension
    if (W - w) * 100.0 / W < min_pct and (H - h) * 100.0 / H < min_pct:
        return W, H, None
    return W, H, (x1, y1, w, h)


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser()
    ap.add_argument("dirs", nargs="+")
    ap.add_argument("--out", default=OUT)
    ap.add_argument("--min", type=float, default=4.0)
    a = ap.parse_args()

    vids = {}
    for d in a.dirs:
        for dp, _, fns in os.walk(d):
            for f in fns:
                if f.lower().endswith(".mp4"):
                    vids.setdefault(os.path.splitext(f)[0], os.path.join(dp, f))
    print(len(vids), "videos")

    have = {}
    if os.path.exists(a.out):
        for ln in open(a.out, encoding="utf-8"):
            if "=" in ln and not ln.lstrip().startswith("#"):
                have[ln.split("=", 1)[0].strip()] = ln.rstrip("\n")

    def one(item):
        stem, path = item
        return stem, path, detect(path, a.min)

    with ThreadPoolExecutor(3) as ex:
        for stem, path, res in ex.map(one, sorted(vids.items())):
            have.pop(stem, None)
            if res is None:
                print("%-40s unreadable" % stem[:40]); log("crop", path, "", "failed", "unreadable"); continue
            W, H, c = res
            if c is None:
                print("%-40s %dx%d  no bars" % (stem[:40], W, H)); log("crop", path, "", "ok", "no bars"); continue
            x, y, w, h = c
            have[stem] = "%s = %d:%d:%d:%d:%d:%d" % (stem, x, y, w, h, W, H)
            print("%-40s %dx%d  picture %dx%d at %d,%d" % (stem[:40], W, H, w, h, x, y), flush=True)
            log("crop", path, a.out, "ok", "%d:%d:%d:%d in %dx%d" % (x, y, w, h, W, H))

    with open(a.out, "w", encoding="utf-8", newline="\n") as fh:
        fh.write("# Black bars to zoom away: <video name> = x:y:w:h:W:H (picture area inside a WxH frame). Made by detect_crops.py\n")
        fh.write("\n".join(sorted(have.values())) + "\n")
    print("->", a.out)


if __name__ == "__main__":
    main()
