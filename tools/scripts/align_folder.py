"""Give every plain .lrc in a folder per-character timing, by listening to the song's vocals.

For each <name>.mp3 that has a <name>.lrc without word tags:
  1. Demucs separates the vocals (tools\\demucs-venv)
  2. align_lyrics.py force-aligns the lyric text to the vocals (tools\\align-venv)
  3. the original .lrc is copied to --backup, then replaced by the aligned one
Songs whose .lrc already has <mm:ss.xx> word tags are skipped, so it can be re-run safely.

usage: align_folder.py --dir "D:\\ai\\GetVideo\\EasonChan\\Eason Chan" [--backup DIR] [--device cpu|cuda] [--dry-run]
"""
import argparse, os, shutil, subprocess, sys, tempfile, time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from clean_aligned import clean_file

HERE = os.path.dirname(os.path.abspath(__file__))
TOOLS = os.path.abspath(os.path.join(HERE, ".."))
DEMUCS_PY = os.path.join(TOOLS, "demucs-venv", "Scripts", "python.exe")
ALIGN_PY = os.path.join(TOOLS, "align-venv", "Scripts", "python.exe")
ALIGN = os.path.join(HERE, "align_lyrics.py")


def run(cmd):
    return subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace")


def has_word_tags(path):
    import re
    return re.search(r"<\d+:\d+", open(path, encoding="utf-8-sig").read()) is not None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", required=True)
    ap.add_argument("--backup", default=os.path.join(TOOLS, "align_out", "orig"))
    ap.add_argument("--device", default="cpu", help="demucs device; cpu keeps the GPU free for the batch")
    ap.add_argument("--language", default="zh")
    ap.add_argument("--model", default="small", help="Whisper model for the alignment (small, medium, ...)")
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()

    todo = []
    for f in sorted(os.listdir(a.dir)):
        stem, ext = os.path.splitext(f)
        lrc = os.path.join(a.dir, stem + ".lrc")
        if ext.lower() == ".mp3" and os.path.isfile(lrc) and not has_word_tags(lrc):
            todo.append((stem, os.path.join(a.dir, f), lrc))
    print(f"{len(todo)} songs to align in {a.dir}")
    if a.dry_run:
        for s, _, _ in todo:
            print("  ", s)
        return
    os.makedirs(a.backup, exist_ok=True)

    tmp = tempfile.mkdtemp(prefix="align_")
    try:
        for n, (stem, mp3, lrc) in enumerate(todo, 1):
            t0 = time.time()
            print(f"[{n}/{len(todo)}] {stem}", flush=True)
            r = run([DEMUCS_PY, "-m", "demucs", "--two-stems=vocals", "-d", a.device, "--segment", "7", "-o", tmp, mp3])
            vocals = os.path.join(tmp, "htdemucs", stem, "vocals.wav")
            if r.returncode != 0 or not os.path.exists(vocals):
                print("   demucs failed:", r.stdout[-300:].strip())
                continue
            out = os.path.join(tmp, "aligned.lrc")
            r = run([ALIGN_PY, ALIGN, vocals, lrc, out, "--language", a.language, "--model", a.model])
            lines = [l for l in r.stdout.splitlines() if "lines aligned" in l or "kept as-is" in l or "no lyric" in l]
            for l in lines:
                print("  ", l.strip())
            if r.returncode != 0 or not os.path.exists(out):
                print("   alignment failed:", r.stdout[-300:].strip())
                continue
            shutil.copy2(lrc, os.path.join(a.backup, stem + ".lrc"))   # keep the original
            shutil.copy2(out, lrc)
            kept, dropped = clean_file(lrc)                           # drop word times that are clearly wrong
            print(f"   quality check: {kept} lines exact, {dropped} back to estimate")
            os.remove(out)
            shutil.rmtree(os.path.join(tmp, "htdemucs", stem), ignore_errors=True)
            print(f"   done in {time.time() - t0:.0f}s", flush=True)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    main()
