r"""Run align_whisper_words.py over many songs whose karaoke .lrc has no exactly-timed line.

The lyric TEXT is taken from the song's current .lrc in D:\ai\Karaoke (tags stripped, credit lines dropped); the audio is the
ORIGINAL mp3 in D:\ai\GetVideo (same <Language>\<Artist>\<name>). A result is installed only when >= --min-timed of the
lines were heard (default 0.7); the old .lrc is backed up to tools\align_out\orig. Pushing is left to daily_sync.py
(it sends changed .lrc files when the TV is idle).

usage: align_whisper_words_batch.py --artists "DIOR,Eric Chou" [--prepare] [--min-timed 0.7] [--model medium]
   --prepare  only write the cleaned lyric files and print what was dropped (no audio work); review before running
"""
import argparse, os, re, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
KARAOKE, GETVIDEO = r"D:\ai\Karaoke", r"D:\ai\GetVideo"
WORK = os.path.join(HERE, "..", "wwords_batch")
LANG = {"Mandarin": "zh", "Cantopop": "zh", "JPop": "ja", "KPop": "ko"}
SINGER = re.compile(r"^(汪|BY2|男|女|合|Male|Female)\s*[:：]\s*")   # duet markers: keep the words, drop the prefix
CREDIT = re.compile(r"酷狗|[:：]|ISRC|^\(?P\)|^©|版權|未經|不得翻唱|Studio|錄音|混音|母帶|製作|企劃|統籌|OP[ :：]|SP[ :：]|Lyrics\s*by|Composer|www\.|\.com", re.I)


def lyric_lines(lrc):
    out, dropped = [], []
    for raw in open(lrc, encoding="utf-8-sig", errors="replace").read().splitlines():
        m = re.match(r"\[\d+:\d+(?:\.\d+)?\](.*)$", raw.strip())
        if not m:
            continue
        t = re.sub(r"<\d+:\d+(?:\.\d+)?>", "", m.group(1)).strip()
        if not t:
            continue
        t = SINGER.sub("", t)
        (dropped if CREDIT.search(t) else out).append(t)
    # title line like "善後-馬師傅" right at the top (no spaces, has a dash, short)
    while out and re.fullmatch(r"[^\s]{2,30}\s?[-－–]\s?[^\s]{1,20}", out[0]):
        dropped.append(out.pop(0))
    return out, dropped


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--artists", required=True, help="comma-separated artist folder names")
    ap.add_argument("--prepare", action="store_true")
    ap.add_argument("--min-timed", type=float, default=0.7)
    ap.add_argument("--model", default="medium")
    a = ap.parse_args()
    arts = {x.strip().lower() for x in a.artists.split(",") if x.strip()}
    os.makedirs(WORK, exist_ok=True)
    todo = []
    for lang in LANG:
        for art in sorted(os.listdir(os.path.join(KARAOKE, lang))) if os.path.isdir(os.path.join(KARAOKE, lang)) else []:
            if art.lower() not in arts:
                continue
            d = os.path.join(KARAOKE, lang, art)
            for f in sorted(os.listdir(d)):
                if f.lower().endswith(".lrc") and not re.search(r"<\d+:", open(os.path.join(d, f), encoding="utf-8-sig", errors="replace").read()):
                    todo.append((lang, art, f[:-4]))
    print(len(todo), "songs")
    for n, (lang, art, stem) in enumerate(todo, 1):
        lrc = os.path.join(KARAOKE, lang, art, stem + ".lrc")
        mp3 = os.path.join(GETVIDEO, lang, art, stem + ".mp3")
        lines, dropped = lyric_lines(lrc)
        txt = os.path.join(WORK, f"{n:02d}.txt")
        open(txt, "w", encoding="utf-8").write("\n".join(lines) + "\n")
        print(f"[{n}/{len(todo)}] {art} / {stem[:50]}  ({len(lines)} lyric lines, {len(dropped)} dropped)")
        if a.prepare:
            print("   first:", " | ".join(lines[:2])[:90]); print("   last: ", " | ".join(lines[-2:])[:90])
            print("   dropped:", " | ".join(x[:22] for x in dropped)[:200])
            continue
        if not os.path.isfile(mp3):
            print("   original mp3 missing:", mp3); continue
        cmd = [sys.executable, "-X", "utf8", os.path.join(HERE, "align_whisper_words.py"), "--mp3", mp3, "--lyrics", txt,
               "--out", os.path.join(WORK, f"{n:02d}.lrc"), "--install", lrc, "--min-timed", str(a.min_timed),
               "--language", LANG[lang], "--model", a.model]
        r = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace")
        for l in r.stdout.splitlines():
            if re.search(r"matched|lines timed|NOT installed|installed|CHECK|first vocal|failed", l):
                print("  ", l)
        sys.stdout.flush()


if __name__ == "__main__":
    main()
