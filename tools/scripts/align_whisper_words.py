"""Time a song's lyrics from what Whisper HEARS in the vocals (use when align_folder.py keeps most lines as estimates,
e.g. Cantonese singing, where Whisper's forced alignment rejects the lyric text).

  1. Demucs separates the vocals from the ORIGINAL mp3 (tools\\demucs-venv; skipped with --vocals)
  2. Whisper (default medium, zh) transcribes the vocals with per-character times (tools\\align-venv)
  3. the heard characters are matched (difflib) to the lyric characters you supply; matched characters take Whisper's
     time, the gaps inside a line are interpolated, a line's unmatched edges are extrapolated at CH s/character
  4. writes an enhanced LRC:  [mm:ss.xx]<mm:ss.xx>c<mm:ss.xx>c...<end>   (line time = first character - 1 s)

The lyrics must be the text actually sung (one line per row, no credits). Credit lines / timestamps are not written.
Lines with fewer than 30 % of their characters heard are written as plain "[mm:ss.xx]text" at the previous line's end.
It does NOT run clean_aligned.py (that compares with the old lyric file's line times, which are the very thing that is
wrong); instead it reports the first-vocal onset so you can sanity-check line 1.

usage: align_whisper_words.py --mp3 SONG.mp3 --lyrics lyrics.txt --out new.lrc [--install DEST.lrc] [--model medium]
                              [--language zh] [--vocals vocals.wav] [--device cpu] [--keep-dir DIR]
   --install DEST.lrc  copies the old DEST.lrc to tools\\align_out\\orig\\<name> (prev aligned).lrc, then replaces it,
                       and logs the step in processed.csv
"""
import argparse, difflib, json, os, re, shutil, subprocess, sys, tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
TOOLS = os.path.abspath(os.path.join(HERE, ".."))
DEMUCS_PY = os.path.join(TOOLS, "demucs-venv", "Scripts", "python.exe")
ALIGN_PY = os.path.join(TOOLS, "align-venv", "Scripts", "python.exe")
CH = 0.35        # seconds per character for unmatched edges
LEAD = 1.0       # a line starts this long before its first sung character
MIN_SHARE = 0.3  # a line needs this share of its characters heard

STRIP = re.compile(r"[\s?？!！,，.。、;；:：~～\"'“”‘’…-]")
TURN = re.compile(r"[(（][^)）]*[)）]")


def clean(line):
    return STRIP.sub("", TURN.sub("", line))


def stamp(t, bracket):
    t = max(0.0, t)
    m = int(t // 60)
    return ("[%02d:%05.2f]" if bracket else "<%02d:%05.2f>") % (m, t - 60 * m)


# ---- stage 2 (runs inside align-venv) -------------------------------------------------------------------------------
def transcribe(vocals, out_json, model_name, language, device):
    import stable_whisper
    m = stable_whisper.load_model(model_name, device=device)
    r = m.transcribe(vocals, language=language, vad=False, regroup=False,
                     condition_on_previous_text=False, word_timestamps=True)
    words = [(w.start, w.end, w.word) for w in r.all_words()]
    json.dump(words, open(out_json, "w", encoding="utf-8"), ensure_ascii=False)
    print(f"{len(words)} words heard", file=sys.stderr)


# ---- stage 3/4 (plain python) ---------------------------------------------------------------------------------------
def build(words, lyric_lines):
    heard = []                                   # (char, start, end); multi-char words are split evenly
    for s, e, t in words:
        t = t.strip()
        for k, c in enumerate(t):
            heard.append((c, s + (e - s) * k / len(t), s + (e - s) * (k + 1) / len(t)))
    lines = [clean(l) for l in lyric_lines]
    flat = [(i, c) for i, l in enumerate(lines) for c in l]
    a = "".join(h[0] for h in heard)
    b = "".join(c for _, c in flat)
    anchor = {}
    for blk in difflib.SequenceMatcher(None, a, b, autojunk=False).get_matching_blocks():
        for k in range(blk.size):
            anchor[blk.b + k] = heard[blk.a + k][1:]
    print(f"matched {len(anchor)} of {len(b)} lyric characters ({len(heard)} heard)", file=sys.stderr)

    out, base, prev_end, plain = [], 0, 0.0, 0
    for i, line in enumerate(lines):
        n = len(line)
        an = {k - base: anchor[k] for k in range(base, base + n) if k in anchor}
        base += n
        if not n:
            continue
        if len(an) < max(2, MIN_SHARE * n):
            out.append(stamp(prev_end, True) + lyric_lines[i].strip())
            plain += 1
            print(f"  line {i + 1} not heard ({len(an)}/{n}), left plain: {lyric_lines[i].strip()}", file=sys.stderr)
            continue
        ks = sorted(an)
        st = [0.0] * (n + 1)
        for k in ks:
            st[k] = an[k][0]
        for k in range(ks[0] - 1, -1, -1):
            st[k] = st[k + 1] - CH
        for p, q in zip(ks, ks[1:]):
            for k in range(p + 1, q):
                st[k] = st[p] + (st[q] - st[p]) * (k - p) / (q - p)
        for k in range(ks[-1] + 1, n):
            st[k] = st[k - 1] + CH
        st[n] = an[ks[-1]][1] if ks[-1] == n - 1 else st[n - 1] + CH
        for k in range(1, n + 1):
            st[k] = max(st[k], st[k - 1] + 0.02)
        prev_end = st[n]
        out.append(stamp(st[0] - LEAD, True) + "".join(stamp(st[k], False) + line[k] for k in range(n)) + stamp(st[n], False))
    return out, plain


def vocal_onset(vocals):
    try:
        import numpy as np
        x = np.frombuffer(subprocess.run(["ffmpeg", "-loglevel", "error", "-i", vocals, "-ac", "1", "-ar", "8000",
                                          "-f", "f32le", "-"], stdout=subprocess.PIPE).stdout, np.float32)
        for k in range(0, len(x) - 4000, 2000):
            if (x[k:k + 4000] ** 2).mean() ** 0.5 > 0.02:
                return k / 8000
    except Exception:
        pass
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mp3")
    ap.add_argument("--lyrics", required=True, help="text file, one sung line per row (UTF-8)")
    ap.add_argument("--out", required=True)
    ap.add_argument("--install", help="replace this .lrc with the result (old one is backed up, step logged)")
    ap.add_argument("--vocals", help="existing vocals.wav (skips Demucs)")
    ap.add_argument("--words", help="existing whisper words .json (skips Demucs + Whisper)")
    ap.add_argument("--model", default="medium")
    ap.add_argument("--language", default="zh")
    ap.add_argument("--device", default="cpu")
    ap.add_argument("--min-timed", type=float, default=0.0,
                    help="with --install: only install when at least this share of lines was timed (e.g. 0.7)")
    ap.add_argument("--keep-dir", help="keep vocals.wav and words.json here")
    ap.add_argument("--stage", help=argparse.SUPPRESS)
    a = ap.parse_args()

    if a.stage == "transcribe":                  # re-entered inside align-venv
        transcribe(a.vocals, a.out, a.model, a.language, a.device)
        return

    lyric_lines = [l for l in open(a.lyrics, encoding="utf-8-sig").read().splitlines() if clean(l)]
    work = a.keep_dir or tempfile.mkdtemp(prefix="wwords_")
    os.makedirs(work, exist_ok=True)
    try:
        words_json = a.words
        vocals = a.vocals
        if not words_json:
            if not vocals:
                if not a.mp3:
                    sys.exit("need --mp3 (or --vocals / --words)")
                src = os.path.join(work, "song.mp3")             # ASCII path: Demucs fails silently on some names
                shutil.copy2(a.mp3, src)
                r = subprocess.run([DEMUCS_PY, "-X", "utf8", "-m", "demucs", "--two-stems=vocals", "-d", a.device,
                                    "--segment", "7", "-o", work, src], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                   text=True, encoding="utf-8", errors="replace")
                vocals = os.path.join(work, "htdemucs", "song", "vocals.wav")
                if not os.path.isfile(vocals):
                    sys.exit("demucs failed:\n" + r.stdout[-500:])
            words_json = os.path.join(work, "words.json")
            r = subprocess.run([ALIGN_PY, "-X", "utf8", os.path.abspath(__file__), "--stage", "transcribe", "--vocals",
                                vocals, "--out", words_json, "--lyrics", a.lyrics, "--model", a.model,
                                "--language", a.language, "--device", a.device],
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace")
            if not os.path.isfile(words_json):
                sys.exit("whisper failed:\n" + r.stdout[-500:])
        words = json.load(open(words_json, encoding="utf-8"))
        out, plain = build(words, lyric_lines)
        open(a.out, "w", encoding="utf-8").write("\n".join(out) + "\n")
        if vocals and os.path.isfile(vocals):
            on = vocal_onset(vocals)
            first = re.search(r"<(\d+):(\d+\.\d+)>", out[0]) if out else None
            if on is not None and first:
                ft = int(first.group(1)) * 60 + float(first.group(2))
                if a.install and abs(ft - on) > 3.0:
                    print(f"NOT installed: first timed character {ft:.1f}s vs first vocal {on:.1f}s (Whisper missed the opening: lines would be mistimed)", file=sys.stderr)
                    sys.exit(4)
                print(f"first vocal energy at {on:.2f}s, first timed character at {ft:.2f}s"
                      + ("  <-- CHECK line 1 (>1.5 s apart)" if abs(ft - on) > 1.5 else ""), file=sys.stderr)
        print(f"{len(out) - plain}/{len(out)} lines timed from heard characters -> {a.out}", file=sys.stderr)
        if a.install and (len(out) - plain) < a.min_timed * len(out):
            print(f"NOT installed: only {len(out) - plain}/{len(out)} lines timed (< {a.min_timed:.0%})", file=sys.stderr)
            sys.exit(3)
        if a.install:
            sys.path.insert(0, HERE)
            from ledger import log
            if os.path.isfile(a.install):
                bdir = os.path.join(TOOLS, "align_out", "orig")
                os.makedirs(bdir, exist_ok=True)
                stem = os.path.splitext(os.path.basename(a.install))[0]
                shutil.copy2(a.install, os.path.join(bdir, stem + " (prev aligned).lrc"))
            shutil.copy2(a.out, a.install)
            log("align", a.mp3 or "", a.install, "ok",
                f"whisper-words method: {len(out) - plain} lines timed, {plain} plain; previous in tools/align_out/orig")
            print("installed", a.install, file=sys.stderr)
    finally:
        if not a.keep_dir:
            shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    main()
