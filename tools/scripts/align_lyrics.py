"""Give an .lrc file per-character timing by listening to the vocals-only track.

  align_lyrics.py VOCALS.wav SONG.lrc OUT.lrc [--model small] [--language zh] [--device cpu]

The text of SONG.lrc is force-aligned to the vocals with stable-ts (Whisper). Each lyric line keeps its
original [mm:ss.xx] start and gets word tags:  [00:13.68]<00:13.90>我<00:14.30>唱<00:14.70>得<00:15.10>
The last tag marks the end of the line. A line that cannot be matched back to its text stays as it was.
Run it with tools\\align-venv\\Scripts\\python.exe (needs ffmpeg on PATH).
"""
import argparse, re, sys

TAG = re.compile(r"\[(\d+):(\d+(?:\.\d+)?)\]")


def parse_lrc(path):
    """-> list of (seconds, text) for every timed line (blank lines included)."""
    out = []
    text = open(path, encoding="utf-8-sig").read()
    for raw in text.splitlines():
        m = TAG.match(raw)
        if m:
            out.append((int(m.group(1)) * 60 + float(m.group(2)), raw[m.end():].strip()))
    return out


TURN = re.compile(r"[(（][^)）]*[)）]\s*")


def strip_turns(text):
    """Drop "(合)" / "（男）" style markers (round brackets, ASCII or full-width, with their content): they only say
    whose turn it is to sing, so they are not aligned. They stay in the output .lrc text (shown, never highlighted)."""
    return TURN.sub("", text).strip()


def turn_markers(text):
    """-> [(number of lyric characters before the marker, marker text)] for every marker in the line."""
    out, done = [], 0
    for m in TURN.finditer(text):
        before = len(re.sub(r"\s", "", TURN.sub("", text[:m.start()])))
        out.append((before, m.group(0).strip()))
    return out


def stamp(sec, bracket):
    sec = max(0.0, sec)
    m = int(sec // 60)
    s = sec - 60 * m
    t = f"{m:02d}:{s:05.2f}"
    return f"[{t}]" if bracket else f"<{t}>"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("vocals")
    ap.add_argument("lrc")
    ap.add_argument("out")
    ap.add_argument("--model", default="small")
    ap.add_argument("--language", default="zh")
    ap.add_argument("--device", default="cpu")
    a = ap.parse_args()

    lines = parse_lrc(a.lrc)
    sung = [(i, strip_turns(t)) for i, (_, t) in enumerate(lines) if strip_turns(t)]
    if not sung:
        sys.exit("no lyric lines found in " + a.lrc)

    import stable_whisper
    model = stable_whisper.load_model(a.model, device=a.device)
    result = model.align(a.vocals, "\n".join(t for _, t in sung), language=a.language,
                         original_split=True, fast_mode=False)

    segs = [s for s in result.segments]
    print(f"{len(sung)} lyric lines -> {len(segs)} aligned segments", file=sys.stderr)

    out_lines = []
    si = 0
    matched = 0
    for i, (t0, text) in enumerate(lines):
        clean = strip_turns(text)
        if not clean or si >= len(sung) or sung[si][0] != i or si >= len(segs):
            out_lines.append(stamp(t0, True) + text)
            if clean:
                si += 1
            continue
        seg = segs[si]
        si += 1
        words = [w for w in seg.words if w.word.strip()]
        # the aligned words must spell the same text as the lyric line, otherwise leave the line alone
        spelled = "".join(w.word.strip() for w in words)
        if spelled.replace(" ", "") != clean.replace(" ", "") or not words:
            out_lines.append(stamp(t0, True) + text)
            print(f"  kept as-is (text mismatch): {text}", file=sys.stderr)
            continue
        parts = [stamp(min(t0, words[0].start), True)]
        marks = turn_markers(text)          # "(合)" etc: kept in the text so they show, but they carry no time (no highlight)
        cum = 0
        for w in words:
            while marks and marks[0][0] <= cum:
                parts.append(marks.pop(0)[1] + " ")
            parts.append(stamp(w.start, False) + w.word.strip())
            cum += len(re.sub(r"\s", "", w.word))
        parts.append(stamp(words[-1].end, False))
        parts.extend(m[1] for m in marks)
        out_lines.append("".join(parts))
        matched += 1
    open(a.out, "w", encoding="utf-8").write("\n".join(out_lines) + "\n")
    print(f"{matched}/{len(sung)} lines aligned -> {a.out}", file=sys.stderr)


if __name__ == "__main__":
    main()
