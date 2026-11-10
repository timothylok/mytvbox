"""Batch-make karaoke files from the music library with Demucs.

For every mp3 in <library>/<Library>/<Artist>/*.mp3 this writes
    <out>/<Language>/<Artist>/<same name>.mp3
as a stereo file with  LEFT = instrumental (vocals removed)  and  RIGHT = original song,
which is what the Karaoke app's vocal switch expects. A <name>.lrc sitting next to the source
mp3 is copied along. Existing outputs are skipped, so the script can be stopped and re-run.

usage:
  make_karaoke.py --dry-run                       # list what would be done
  make_karaoke.py --only "Eason Chan" --limit 2   # try a couple of songs
  make_karaoke.py                                 # everything (several hours)
  make_karaoke.py --push                          # also adb-push finished files to the USB drive

Needs: Demucs venv (tools\\demucs-venv), ffmpeg on PATH, an NVIDIA GPU (use --device cpu otherwise).
"""
import argparse, os, shutil, subprocess, sys, tempfile, time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from ledger import log
from normalize_karaoke import measure, apply_gain
DEFAULT_PY = os.path.join(HERE, "..", "demucs-venv", "Scripts", "python.exe")
ADB = os.path.join(HERE, "..", "sdk", "platform-tools", "adb.exe")

# library folder under GetVideo -> language folder on the karaoke drive
LANGUAGE = {
    "Cantopop": "Cantopop", "Beyond": "Cantopop", "EasonChan": "Cantopop",
    "Mandarin": "Mandarin", "JPop": "JPop", "KPop": "KPop",
    "EnglishPop": "English", "TheWeeknd": "English", "Timlok": "Other",
}

MERGE = ("[0:a]pan=mono|c0=0.5*c0+0.5*c1[l];[1:a]pan=mono|c0=0.5*c0+0.5*c1[r];"
         "[l][r]amerge=inputs=2[o]")


def find_songs(library, only):
    songs = []
    for lib, lang in LANGUAGE.items():
        libdir = os.path.join(library, lib)
        if not os.path.isdir(libdir):
            continue
        for artist in sorted(os.listdir(libdir)):
            adir = os.path.join(libdir, artist)
            if not os.path.isdir(adir) or (only and only.lower() not in artist.lower()):
                continue
            for f in sorted(os.listdir(adir)):
                if f.lower().endswith(".mp3"):
                    songs.append((lang, artist, os.path.join(adir, f)))
    return songs


def run(cmd):
    return subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--library", default=r"D:\ai\GetVideo")
    ap.add_argument("--out", default=r"D:\ai\Karaoke")
    ap.add_argument("--python", default=DEFAULT_PY, help="python with demucs installed")
    ap.add_argument("--device", default="cuda")
    ap.add_argument("--segment", default="7", help="demucs segment seconds (7 fits a 4 GB GPU)")
    ap.add_argument("--only", help="only artists whose folder name contains this text")
    ap.add_argument("--limit", type=int, help="stop after N songs")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--push", action="store_true", help="adb push finished files to the box USB drive")
    ap.add_argument("--target", type=float, default=-18.0, help="loudness target (LUFS) of the instrumental channel")
    ap.add_argument("--tolerance", type=float, default=1.5, help="no re-encode when already within this many dB of the target")
    ap.add_argument("--box", default="192.168.3.79:5555")
    ap.add_argument("--box-root", default="/storage/A6D0-F59E/Karaoke")
    a = ap.parse_args()

    songs = find_songs(a.library, a.only)
    todo = [(l, ar, p) for l, ar, p in songs
            if not os.path.exists(os.path.join(a.out, l, ar, os.path.basename(p)))]
    if a.limit:
        todo = todo[:a.limit]
    print(f"{len(songs)} songs found, {len(todo)} to process (output: {a.out})")
    if a.dry_run:
        for l, ar, p in todo:
            print(f"  {l}/{ar}/{os.path.basename(p)}")
        return

    tmp = tempfile.mkdtemp(prefix="demucs_")
    done = failed = 0
    t0 = time.time()
    try:
        for n, (lang, artist, src) in enumerate(todo, 1):
            name = os.path.basename(src)
            stem = os.path.splitext(name)[0]
            dest_dir = os.path.join(a.out, lang, artist)
            dest = os.path.join(dest_dir, name)
            print(f"[{n}/{len(todo)}] {lang}/{artist}/{name}", flush=True)
            r = run([a.python, "-m", "demucs", "--two-stems=vocals", "-d", a.device,
                     "--segment", a.segment, "-o", tmp, src])
            inst = os.path.join(tmp, "htdemucs", stem, "no_vocals.wav")
            if r.returncode != 0 or not os.path.exists(inst):
                print("   demucs failed:", r.stdout[-300:].strip())
                log("karaoke", src, dest, "failed", "demucs")
                failed += 1
                continue
            os.makedirs(dest_dir, exist_ok=True)
            part = dest + ".part.mp3"
            r = run(["ffmpeg", "-v", "error", "-y", "-i", inst, "-i", src, "-filter_complex", MERGE,
                     "-map", "[o]", "-c:a", "libmp3lame", "-q:a", "2", part])
            if r.returncode != 0 or not os.path.exists(part):
                print("   ffmpeg failed:", r.stdout[-300:].strip())
                log("karaoke", src, dest, "failed", "ffmpeg")
                failed += 1
                continue
            os.replace(part, dest)           # only a complete file ever gets the final name
            lrc = os.path.splitext(src)[0] + ".lrc"
            if os.path.exists(lrc):
                shutil.copy2(lrc, os.path.splitext(dest)[0] + ".lrc")
            shutil.rmtree(os.path.join(tmp, "htdemucs", stem), ignore_errors=True)
            # even out the loudness right away (the Demucs files differ by up to 15 dB); see normalize_karaoke.py
            lu = measure(dest)
            if lu is not None and lu < -45.0:
                print("   WARNING: instrumental channel nearly silent (%.1f LUFS) - not boosted, check the Demucs output" % lu, flush=True)
                log("normalize", dest, "", "skipped", "%.1f LUFS: instrumental channel nearly silent (check the Demucs output)" % lu)
            elif lu is not None and abs(a.target - lu) > a.tolerance:
                gain = min(20.0, a.target - lu)                     # never boost by more than 20 dB
                ok, err = apply_gain(dest, gain)
                print("   loudness %.1f LUFS -> gain %+.1f dB %s" % (lu, gain, "" if ok else "FAILED " + err), flush=True)
                log("normalize", dest, dest, "ok" if ok else "failed", "%.1f LUFS -> gain %+.1f dB (target %.1f), done by make_karaoke" % (lu, gain, a.target))
            log("karaoke", src, dest, "ok")
            done += 1
            if a.push:
                push(a, dest, lang, artist)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    print(f"done {done}, failed {failed}, {(time.time() - t0) / 60:.1f} min")


def push(a, dest, lang, artist):
    remote_dir = f"{a.box_root}/{lang}/{artist}"
    run([ADB, "-s", a.box, "shell", f"mkdir -p '{remote_dir}'"])
    for f in (dest, os.path.splitext(dest)[0] + ".lrc"):
        if os.path.exists(f):
            r = run([ADB, "-s", a.box, "push", f, f"{remote_dir}/{os.path.basename(f)}"])
            if r.returncode != 0:
                print("   push failed:", r.stdout[-200:].strip())


if __name__ == "__main__":
    main()
