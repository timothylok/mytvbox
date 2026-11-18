"""
Checks for tools/scripts/clean_aligned.py: shift_line_starts (a lyric file whose line times are early gets its line times moved next to the words).
Same fixture and numbers as the app's Lrc.alignStartsToWords test in karaoke/tests/LrcTest.java, so the pipeline and the player agree.

    python karaoke/release-tests/shift_line_starts_test.py
"""
import os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "..", "tools", "scripts"))
import clean_aligned as c  # noqa: E402

fails = 0


def check(name, ok):
    global fails
    print(("PASS  " if ok else "FAIL  ") + name)
    if not ok:
        fails += 1


def stamps(text):
    out = []
    for l in text.splitlines():
        m = re.match(r"\[(\d+):(\d+\.\d+)\]", l)
        out.append(int(m.group(1)) * 60 + float(m.group(2)) if m else None)
    return out


EARLY = ("[00:00.00]作詞:某某\n[00:15.00]<00:24.00>誰<00:24.50>於<00:25.00>街<00:26.00>\n[00:19.00]\n"
         "[00:19.20]<00:28.00>去<00:28.50>坐<00:29.00>巴<00:30.00>\n[00:24.00]估計行\n[00:30.00]<00:38.00>最<00:38.50>後<00:39.00>\n")
new, shift, changed = c.shift_line_starts(EARLY)
s = stamps(new)
check("early file: the median gap is found (8.8 s)", abs(shift - 8.8) < 0.01)
check("early file: line 1 starts 1 s before its first word (23.0)", abs(s[1] - 23.0) < 0.01)
check("early file: line 3 starts 1 s before its first word (27.0)", abs(s[3] - 27.0) < 0.01)
check("early file: the line without word times moves by the median gap (32.8)", abs(s[4] - 32.8) < 0.01)
check("early file: the header line before the first sung line stays (0)", abs(s[0]) < 0.01)
check("early file: the blank line never lands after the next line", s[2] <= s[3])
check("early file: times are in file order", all(a <= b for a, b in zip(s, s[1:])))
check("early file: the word tags are not touched", re.findall(r"<[^>]+>", new) == re.findall(r"<[^>]+>", EARLY))
check("early file: the text is not touched", re.sub(r"\[[^\]]*\]|<[^>]*>", "", new) == re.sub(r"\[[^\]]*\]|<[^>]*>", "", EARLY))
again, shift2, changed2 = c.shift_line_starts(new)
check("running it again changes nothing", changed2 == 0 and again == new)

NORMAL = "[00:10.00]<00:10.40>我<00:10.90>唱<00:11.30>\n[00:15.00]<00:15.20>得<00:15.60>好<00:16.00>\n"
n2, sh, ch = c.shift_line_starts(NORMAL)
check("a normal file (line time just before its word) comes back unchanged", ch == 0 and n2 == NORMAL)
PLAIN = "[00:10.00]我唱得好\n[00:20.00]约定至死\n"
p2, sh, ch = c.shift_line_starts(PLAIN)
check("a plain file (no word times) comes back unchanged", ch == 0 and p2 == PLAIN)
check("an empty file does not fail", c.shift_line_starts("")[2] == 0)

# the same step runs at the end of clean_text: an aligned early file leaves the pipeline with its line times next to its words
cleaned, kept, dropped = c.clean_text(EARLY)
sc = stamps(cleaned)
check("clean_text: output is shifted too and still in order", all(a <= b for a, b in zip(sc, sc[1:])) and abs(sc[1] - 23.0) < 0.3)

print("ALL PASSED" if fails == 0 else "%d FAILED" % fails)
sys.exit(0 if fails == 0 else 1)
