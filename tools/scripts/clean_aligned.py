"""Quality check for aligned (enhanced) .lrc files: drop per-character times that are clearly wrong.

The aligner sometimes goes astray (long instrumental intros, credits lines that are not sung): characters squeezed
into a fraction of a second, or a whole line timed many seconds away from where the lyric file says it is sung.
A wrong highlight is worse than the player's even-spacing estimate, so for each line this keeps the word tags only if

  * the first character starts within --tolerance seconds (default 2.0) of the line's own [mm:ss] time (after allowing
    for a song-wide shift: many lyric files show every line ~1-3 s early; the median gap is used, if under 5 s), and
  * the characters stay inside the line's slot (line time - tolerance .. next line's time + tolerance, same shift), and
  * the line is not squeezed: at least --min-per-char seconds (default 0.07) per character on average.

Otherwise the line goes back to plain "[mm:ss.xx]text" (the player then estimates the timing).
The line's [mm:ss] time stays the one from the original lyric file, EXCEPT for a song whose line times are all early (median gap
between a line's time and its first word over 3 s): then shift_line_starts() puts every line's time at "first word - 1 s" (see there).

usage: clean_aligned.py FILE.lrc [FILE.lrc ...] [--dry-run]
       (also used by align_folder.py right after aligning)
"""
import argparse, re, sys

LINE = re.compile(r"^\[(\d+):(\d+(?:\.\d+)?)\](.*)$")
WORD = re.compile(r"<(\d+):(\d+(?:\.\d+)?)>")


def sec(m, n):
    return int(m) * 60 + float(n)


LEAD = 1.0        # a line starts this long before its first sung word
BIG_GAP = 3.0     # a song-wide gap above this (seconds) means the lyric file's line times are early (1-3 s early is normal and harmless)


def stamp(t):
    t = max(0.0, t)
    return "[%02d:%05.2f]" % (int(t // 60), t - 60 * int(t // 60))


def shift_line_starts(text):
    """-> (new_text, shift_seconds, changed_lines)

    The player decides which line is current from the line's own [mm:ss] time, so that time must be close to when its first word is
    sung. Some lyric files are timed for another cut of the video (a few seconds to 15 s early): the word times are right but every line
    time is early, so each line is "current" while the previous one is still being sung. When the median gap between a line's time and its
    first word is over BIG_GAP, every timed line gets the time "first word - LEAD" (never before the end of the line before it), and
    untimed (estimated) lines from the first timed line on move by the same median gap. Files whose line times are already close to
    their words (the usual case) come back unchanged, so this can be run again safely."""
    rows = []
    for raw in text.splitlines():
        m = LINE.match(raw)
        rows.append([raw, m, sec(m.group(1), m.group(2)) if m else None])
    gaps = []
    for raw, m, t0 in rows:
        if m:
            w = WORD.findall(m.group(3))
            if w:
                gaps.append(sec(*w[0]) - t0)
    if not gaps:
        return text, 0.0, 0
    gaps.sort()
    shift = gaps[len(gaps) // 2]
    if shift <= BIG_GAP:
        return text, 0.0, 0
    first_timed = next(t0 for raw, m, t0 in rows if m and WORD.search(m.group(3)))
    news, prev_end = {}, 0.0
    for i, (raw, m, t0) in enumerate(rows):
        if not m:
            continue
        w = [sec(a, b) for a, b in WORD.findall(m.group(3))]
        if w:
            news[i] = min(w[0], max(w[0] - LEAD, prev_end))      # LEAD before the first word, but not before the previous line ends
            prev_end = w[-1]
        elif t0 >= first_timed:
            news[i] = t0 + shift
        else:
            news[i] = t0                                  # title / credit lines before the first sung line stay where they are
    idx = sorted(news)
    for a, b in zip(reversed(idx[:-1]), reversed(idx[1:])):   # keep the times in file order: a blank or estimated line never lands after the line that follows it
        news[a] = min(news[a], news[b])
    out, changed = [], 0
    for i, (raw, m, t0) in enumerate(rows):
        if m and abs(news[i] - t0) > 0.005:
            changed += 1
            out.append(stamp(news[i]) + m.group(3))
        else:
            out.append(raw)
    return "\n".join(out) + "\n", shift, changed


def shift_file(path, dry_run=False):
    text = open(path, encoding="utf-8-sig").read()
    new, shift, changed = shift_line_starts(text)
    if not dry_run and changed:
        open(path, "w", encoding="utf-8").write(new)
    return shift, changed


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
    if abs(shift) > 15:
        shift = 0.0          # a huge "shift" means the alignment itself is off; judge against the file's own times
    elif abs(shift) > 5:
        # a big shift is only believed when most lines agree on it (a lyric file that is a constant 6 s early)
        agree = sum(1 for g in gaps if abs(g - shift) <= tolerance)
        if agree < 0.6 * len(gaps):
            shift = 0.0
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
    result, _shift, _n = shift_line_starts("\n".join(out) + "\n")     # line times must sit next to their words (see shift_line_starts)
    return result, kept, dropped


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
