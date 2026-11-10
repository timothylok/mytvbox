"""Draft clean song titles for messy YouTube file names -> titles.txt (read by the Karaoke app).

    python make_titles.py            # Eason Chan songs -> D:\\ai\\Karaoke\\titles.txt (existing lines for other artists are kept)

Line format:  Artist/<file name without extension> = Title   (see Library.loadOverrides). Edit the result by hand if a title is wrong;
lines already in the file for a key are NOT overwritten, so hand edits survive a re-run.
"""
import os, re, sys

ROOT = r"D:\ai\Karaoke"
ARTIST = "Eason Chan"
SRC = os.path.join(ROOT, "Cantopop", ARTIST)
OUT = os.path.join(ROOT, "titles.txt")

MANUAL = {   # stems the rules cannot get right
    "《黃偉文 十年選》陳奕迅 - 反高潮 HD": "反高潮",
    "裙下之臣--陳奕迅(from solidays disc1)": "裙下之臣",
    "陳奕迅 - 人車誌 320Kbps Eason Chan": "人車誌",
    "陳奕迅Eason Chan-歲月如歌(衝上雲霄II MV)": "歲月如歌",
}
NOISE = re.compile(r"\s*[\(\[（【][^\)\]）】]*(official|mv|lyrics?|hd|高清|首播|完整版|歌詞|from )[^\)\]）】]*[\)\]）】]", re.I)


def clean(stem):
    if stem in MANUAL:
        return MANUAL[stem]
    m = re.search(r"《([^《》]+)》(.*)", stem)
    if m:
        title, rest = m.group(1), m.group(2)
        # an English subtitle right after the Chinese one: 《人啊人》Homo Sapiens 陳奕迅 ...
        sub = re.match(r"\s*([A-Za-z][A-Za-z' ]*?)\s*(?:陳奕迅|Eason|\[|\(|-|$)", rest)
        if sub and sub.group(1).strip() and sub.group(1).strip().lower() not in ("eason", "eason chan", "official", "mv"):
            return "%s (%s)" % (title, sub.group(1).strip())
        return title
    t = NOISE.sub("", stem)
    t = re.sub(r"(陳奕迅|Eason Chan|Eason)", "", t)
    t = re.sub(r"\bHD\b|\bMV\b|\(Full Version\)|歌詞", "", t)
    t = re.sub(r"^[\s\-｜|:：]+|[\s\-｜|:：]+$", "", t)
    t = re.sub(r"\s{2,}", " ", t)
    return t.strip() or stem


def main():
    have = {}
    if os.path.exists(OUT):
        for ln in open(OUT, encoding="utf-8"):
            if "=" in ln and not ln.lstrip().startswith("#"):
                have[ln.split("=", 1)[0].strip()] = ln.rstrip("\n")
    out = ["# Song title overrides: Artist/<file name> = Title shown on the TV and phone (made by make_titles.py; hand edits kept)"]
    stems = sorted({os.path.splitext(f)[0] for f in os.listdir(SRC) if f.lower().endswith((".mp3", ".mp4"))})
    for s in stems:
        key = "%s/%s" % (ARTIST, s)
        line = have.pop(key, None) or "%s = %s" % (key, clean(s))
        out.append(line)
        print("%-60s -> %s" % (s[:60], line.split("=", 1)[1].strip()))
    out.extend(have.values())   # keep lines for other artists
    with open(OUT, "w", encoding="utf-8", newline="\n") as fh:
        fh.write("\n".join(out) + "\n")
    print(len(stems), "titles ->", OUT)


main()
