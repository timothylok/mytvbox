"""Find MP4s that are really a still picture (YouTube "audio + album art" uploads).

Samples 5 frames spread over the video (tiny grayscale), and calls the video static when the frames hardly differ.
Only videos whose stem matches a song in D:\\ai\\Karaoke are checked. Writes the stems to D:\\ai\\Karaoke\\static_videos.txt
(one per line, UTF-8, '#' = comment) - the karaoke app treats those songs as audio-only; copy_videos.py skips them.

    python find_static_videos.py            # scan all, write the list
"""
import os, subprocess, sys, json
from concurrent.futures import ThreadPoolExecutor

KARAOKE = r"D:\ai\Karaoke"
GETVIDEO = r"D:\ai\GetVideo"
OUT = os.path.join(KARAOKE, "static_videos.txt")
THRESH = 2.0   # mean absolute pixel difference (0-255) between frames; static pictures are ~0
SIZE = 48


def frames(path):
    try:
        dur = float(subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", path],
                                   capture_output=True, text=True, timeout=60).stdout.strip())
    except Exception:
        return None
    out = []
    for frac in (0.15, 0.3, 0.5, 0.7, 0.85):
        r = subprocess.run(["ffmpeg", "-v", "error", "-ss", "%.2f" % (dur * frac), "-i", path, "-frames:v", "1",
                            "-vf", "scale=%d:%d,format=gray" % (SIZE, SIZE), "-f", "rawvideo", "-"],
                           capture_output=True, timeout=120)
        if len(r.stdout) == SIZE * SIZE:
            out.append(r.stdout)
    return out


def check(item):
    stem, path = item
    f = frames(path)
    if not f or len(f) < 3:
        return stem, None, path
    worst = max(sum(abs(a - b) for a, b in zip(f[i], f[j])) / (SIZE * SIZE)
                for i in range(len(f)) for j in range(i + 1, len(f)))
    return stem, worst, path


def main():
    stems = set()
    for r, _, fs in os.walk(KARAOKE):
        for f in fs:
            if f.lower().endswith(".mp3"):
                stems.add(os.path.splitext(f)[0])
    vids = {}
    for r, _, fs in os.walk(GETVIDEO):
        for f in fs:
            s, e = os.path.splitext(f)
            if e.lower() == ".mp4" and s in stems:
                vids.setdefault(s, os.path.join(r, f))
    print(len(stems), "songs,", len(vids), "with a video", flush=True)
    static, unknown = [], []
    with ThreadPoolExecutor(4) as ex:
        for i, (stem, worst, path) in enumerate(ex.map(check, sorted(vids.items())), 1):
            if worst is None:
                unknown.append(stem)
            elif worst < THRESH:
                static.append((stem, worst))
                print("STATIC %.2f  %s" % (worst, stem), flush=True)
            if i % 50 == 0:
                print(i, "checked", flush=True)
    with open(OUT, "w", encoding="utf-8") as fh:
        fh.write("# videos that are only a still picture - treated as audio-only (made by find_static_videos.py)\n")
        for stem, _ in sorted(static):
            fh.write(stem + "\n")
    print("static:", len(static), "unreadable:", len(unknown), "->", OUT)
    for u in unknown:
        print("could not read:", u)


main()
