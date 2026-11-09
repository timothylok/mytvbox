"""Quality check for aligned (enhanced) .lrc files: drop per-character times that are clearly wrong.

The aligner sometimes goes astray (long instrumental intros, credits lines that are not sung): characters squeezed
into a fraction of a second, or a whole line timed many seconds away from where the lyric file says it is sung.
A wrong highlight is worse than the player's even-spacing estimate, so for each line this keeps the word tags only if

  * the first character starts within --tolerance seconds (default 2.0) of the line's own [mm:ss] time (after allowing
    for a song-wide shift: many lyric files show every line ~1-3 s early; the median gap is used, if under 5 s), and
  * the characters stay inside the line's slot (line time - tolerance .. next line's time + tolerance, same shift), and
  * the line is not squeezed: at least --min-per-char seconds (default 0.07) per character on average.

Otherwise the line goes back to plain "[mm:ss.xx]text" (the player then estimates the timing).
The line's [mm:ss] time itself always stays the one from the original lyric file.

usage: clean_aligned.py FILE.lrc [FILE.lrc ...] [--dry-run]
       (also used by align_folder.py right after aligning)
"""
import argparse, re, sys

LINE = re.compile(r"^\[(\d+):(\d+(?:\.\d+)?)\](.*)$")
WORD = re.compile(r"<(\d+):(\d+(?:\.\d+)?)>")


def sec(m, n):
    return int(m) * 60 + float(n)


def clean_text(text, tolerance=2.0, min_per_char=0.07):
    """-> (new_text, kept_lines, dropped_lines)"""
    rows = []
    for raw in text.splitlines():
        m = LINE.match(raw)
        rows.append([raw, m, sec(m.group(1), m.group(2)) if m else None])
    times = [r[2] for r in rows if r[2] is not None]
    # Many lyric files show every line a fixed amount early (or late). Measure that song-wide shift as the median gap
    # between a line's time and its first aligned character, and judge each line relative to it.
    gaps = []
    for raw, m, t0 in rows:
        if m and "<" in m.group(3):
            w = WORD.search(m.group(3))
            if w:
                gaps.append(sec(w.group(1), w.group(2)) - t0)
    gaps.sort()
    shift = gaps[len(gaps) // 2] if gaps else 0.0
    if abs(shift) > 5:
        shift = 0.0          # a huge "shift" means the alignment itself is off; judge against the file's own times
    out, kept, dropped = [], 0, 0
    for idx, (raw, m, t0) in enumerate(rows):
        if not m or "<" not in m.group(3):
            out.append(raw)
            continue
        body = m.group(3)
        words = [sec(a, b) for a, b in WORD.findall(body)]
        plain = WORD.sub("", body)
        nxt = next((t for t in times if t > t0), t0 + 30)
        chars = len(plain.replace(" ", ""))
        span = (words[-1] - words[0]) if len(words) > 1 else 0
        ok = (bool(words) and abs(words[0] - (t0 + shift)) <= tolerance
              and min(words) >= t0 + shift - tolerance and max(words) <= nxt + shift + tolerance
              and chars > 0 and span / chars >= min_per_char)
        if ok:
            out.append(raw)
            kept += 1
        else:
            out.append(f"[{m.group(1)}:{m.group(2)}]{plain}")
            dropped += 1
    return "\n".join(out) + "\n", kept, dropped


def clean_file(path, dry_run=False, **kw):
    text = open(path, encoding="utf-8-sig").read()
    new, kept, dropped = clean_text(text, **kw)
    if not dry_run and new != text:
        open(path, "w", encoding="utf-8").write(new)
    return kept, dropped


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("files", nargs="+")
    ap.add_argument("--tolerance", type=float, default=2.0)
    ap.add_argument("--min-per-char", type=float, default=0.07)
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()
    for f in a.files:
        kept, dropped = clean_file(f, a.dry_run, tolerance=a.tolerance, min_per_char=a.min_per_char)
        print(f"{kept:3d} lines kept exact, {dropped:3d} back to estimate  {f}")


if __name__ == "__main__":
    main()
