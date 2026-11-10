"""Even out the loudness of the karaoke mp3s (LEFT = instrumental, RIGHT = original).

The Demucs files come out at very different levels (one Eason Chan song was 11 dB quieter than the median). For every
<Language>/<Artist>/<name>.mp3 this measures the integrated loudness of the LEFT channel (the one that plays in karaoke mode) with
ffmpeg ebur128 and, when it is more than --tolerance dB away from --target, re-encodes the file with the same gain on both
channels (a peak limiter prevents clipping). Files already near the target are left alone, so it is safe to re-run.
The original of every changed file is first copied to --backup (default D:\\ai\\Karaoke_pre_normalize), so the change can be undone
by copying the files back. Every change is logged to processed.csv.

usage:  normalize_karaoke.py [--dir D:\\ai\\Karaoke] [--only "Eason Chan"] [--target -18] [--tolerance 1.5] [--dry-run]
Then push the changed files to the box (copy_videos.py / adb push), only for songs that already have a processed lyric file.
"""
import argparse, os, re, shutil, subprocess, sys, tempfile
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ledger import log


def measure(path):
    r = subprocess.run(["ffmpeg", "-hide_banner", "-nostats", "-i", path, "-af", "pan=mono|c0=c0,ebur128=framelog=quiet", "-f", "null", "-"],
                       capture_output=True, text=True, encoding="utf-8", errors="replace")
    m = re.findall(r"I:\s+(-?[\d.]+) LUFS", r.stderr)
    return float(m[-1]) if m else None


def apply_gain(path, gain_db):
    tmp = path + ".norm.mp3"
    r = subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", path, "-af", "volume=%.2fdB,alimiter=limit=0.95:level=disabled" % gain_db,
                        "-c:a", "libmp3lame", "-q:a", "2", tmp], capture_output=True, text=True, encoding="utf-8", errors="replace")
    if r.returncode != 0 or not os.path.exists(tmp):
        return False, r.stderr[-200:]
    os.replace(tmp, path)
    return True, ""


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", default=r"D:\ai\Karaoke")
    ap.add_argument("--only", help="only artist folders containing this text")
    ap.add_argument("--target", type=float, default=-18.0)
    ap.add_argument("--tolerance", type=float, default=1.5)
    ap.add_argument("--backup", default=r"D:\ai\Karaoke_pre_normalize", help="the original of every changed file is copied here first (same sub-folders)")
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()

    songs = []
    for lang in sorted(os.listdir(a.dir)):
        ld = os.path.join(a.dir, lang)
        if not os.path.isdir(ld):
            continue
        for artist in sorted(os.listdir(ld)):
            ad = os.path.join(ld, artist)
            if not os.path.isdir(ad) or (a.only and a.only.lower() not in artist.lower()):
                continue
            for f in sorted(os.listdir(ad)):
                if f.lower().endswith(".mp3") and ".part" not in f and ".norm" not in f:
                    songs.append(os.path.join(ad, f))
    print(len(songs), "songs; target %.1f LUFS +/- %.1f" % (a.target, a.tolerance), flush=True)

    changed = skipped = failed = 0
    with ThreadPoolExecutor(3) as ex:
        for path, lu in zip(songs, ex.map(measure, songs)):
            name = os.path.basename(path)
            if lu is None:
                print("FAIL measure", name); log("normalize", path, "", "failed", "could not measure"); failed += 1; continue
            gain = a.target - lu
            if abs(gain) <= a.tolerance:
                skipped += 1
                continue
            print("%-60s %6.1f LUFS -> gain %+5.1f dB" % (name[:60], lu, gain), flush=True)
            if a.dry_run:
                continue
            rel = os.path.relpath(path, a.dir)
            bak = os.path.join(a.backup, rel)
            if not os.path.exists(bak):                        # keep the FIRST original, never a re-normalized copy
                os.makedirs(os.path.dirname(bak), exist_ok=True)
                shutil.copy2(path, bak)
            ok, err = apply_gain(path, gain)
            if ok:
                changed += 1
                log("normalize", path, path, "ok", "%.1f LUFS -> gain %+.1f dB (target %.1f)" % (lu, gain, a.target))
            else:
                failed += 1
                log("normalize", path, "", "failed", err)
    print("changed %d, already fine %d, failed %d" % (changed, skipped, failed))


if __name__ == "__main__":
    main()
