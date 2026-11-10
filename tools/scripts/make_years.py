"""Collect the release year of every song into D:\\ai\\Karaoke\\years.txt (read by the Karaoke app).

The year is read from the ID3 "date" tag of the SOURCE mp3s in D:\\ai\\GetVideo (the Demucs karaoke files carry no tags).
For every karaoke song  D:\\ai\\Karaoke\\<Language>\\<Artist>\\<name>.mp3  the source mp3 is found by the same file name anywhere under
GetVideo; the Eason Chan files were renamed in GetVideo, so those are matched through titles.txt (plus the few MANUAL pairs below).

    python make_years.py            # write years.txt (lines already in the file are kept, so hand edits survive)
    python make_years.py --report   # also list the songs that have no year

Line format:  Artist/<karaoke file name without extension> = 2009   (see Years.java). Logged to processed.csv.
"""
import argparse, json, os, re, subprocess, sys
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ledger import log

KARAOKE = r"D:\ai\Karaoke"
GETVIDEO = r"D:\ai\GetVideo"
OUT = os.path.join(KARAOKE, "years.txt")
MANUAL = {   # karaoke stem -> GetVideo stem, for the renamed files the title rule cannot match
    "《擺渡人》 主題曲 MV 《讓我留在你身邊》 ： 主唱 - 陳奕迅 Eason Chan (In Cinemas 23 December)": "讓我留在你身邊",
    "陳奕迅 - 淘汰 HD": "淘汰(國)",
    "陳奕迅 Eason Chan - 阿牛 Aa  Ngau": "阿牛",
}
YEAR_TAGS = ("date", "year", "originalyear", "originaldate", "tyer", "tdrc", "tory", "tdor")


def tags(path):
    r = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format_tags", "-of", "json", path],
                       capture_output=True, text=True, encoding="utf-8", errors="replace")
    try:
        return {k.lower(): v for k, v in json.loads(r.stdout).get("format", {}).get("tags", {}).items()}
    except Exception:
        return {}


def year_of(t):
    for k in YEAR_TAGS:
        v = t.get(k)
        if v:
            m = re.search(r"(?<!\d)(19\d\d|20\d\d)(?!\d)", str(v))
            if m:
                return int(m.group(1))
    return 0


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser()
    ap.add_argument("--report", action="store_true")
    a = ap.parse_args()

    # source mp3s by file stem (first one wins)
    src = {}
    for dp, _, fns in os.walk(GETVIDEO):
        for f in fns:
            if f.lower().endswith(".mp3"):
                src.setdefault(os.path.splitext(f)[0], os.path.join(dp, f))
    titles = {}
    try:
        for ln in open(os.path.join(KARAOKE, "titles.txt"), encoding="utf-8"):
            if "=" in ln and not ln.lstrip().startswith("#"):
                k, v = ln.split("=", 1)
                titles[k.strip()] = v.strip()
    except OSError:
        pass

    songs = []       # (artist, karaoke stem, source path or None)
    for lang in sorted(os.listdir(KARAOKE)):
        ld = os.path.join(KARAOKE, lang)
        if not os.path.isdir(ld):
            continue
        for artist in sorted(os.listdir(ld)):
            ad = os.path.join(ld, artist)
            if not os.path.isdir(ad):
                continue
            for f in sorted(os.listdir(ad)):
                if not f.lower().endswith(".mp3") or ".part" in f or ".norm" in f:
                    continue
                stem = os.path.splitext(f)[0]
                p = src.get(stem)
                if p is None and stem in MANUAL:
                    p = src.get(MANUAL[stem])
                if p is None:
                    t = titles.get("%s/%s" % (artist, stem))
                    if t:
                        p = src.get(t) or src.get(re.sub(r"\s*\(.*\)$", "", t).strip())
                songs.append((artist, stem, p))

    have = {}
    if os.path.exists(OUT):
        for ln in open(OUT, encoding="utf-8"):
            if "=" in ln and not ln.lstrip().startswith("#"):
                have[ln.split("=", 1)[0].strip()] = ln.rstrip("\n")

    def one(item):
        artist, stem, p = item
        return artist, stem, p, (year_of(tags(p)) if p else 0)

    out = ["# Release years: Artist/<file name> = year (made by make_years.py from the ID3 date tag of the source mp3s; hand edits kept)"]
    n_year = n_new = n_nosrc = 0
    missing = []
    with ThreadPoolExecutor(4) as ex:
        for artist, stem, p, y in ex.map(one, songs):
            key = "%s/%s" % (artist, stem)
            line = have.pop(key, None)
            if line is None and y:
                line = "%s = %d" % (key, y)
                n_new += 1
                log("years", p, OUT, "ok", "%s -> %d" % (key, y))
            if line:
                out.append(line)
                n_year += 1
            else:
                missing.append((artist, stem, "no source mp3" if p is None else "no year tag"))
                n_nosrc += p is None
    out.extend(have.values())
    with open(OUT, "w", encoding="utf-8", newline="\n") as fh:
        fh.write("\n".join(out) + "\n")
    print("%d songs: %d with a year (%d new), %d without (%d have no source mp3) -> %s" % (len(songs), n_year, n_new, len(missing), n_nosrc, OUT))
    if a.report:
        for artist, stem, why in missing:
            print("  %s / %s  [%s]" % (artist, stem[:60], why))


if __name__ == "__main__":
    main()
