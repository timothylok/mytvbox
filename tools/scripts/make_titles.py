"""Draft clean song titles for messy YouTube file names -> titles.txt (read by the Karaoke app).

    python make_titles.py                 # all artists in D:\\ai\\Karaoke -> D:\\ai\\Karaoke\\titles.txt
    python make_titles.py --artist "Beyond"   # only that artist folder
    python make_titles.py --review        # also print the titles that still look messy (long, or still contain MV/Official/|)

Line format:  Artist/<file name without extension> = Title   (see Library.loadOverrides). Edit the result by hand if a title is
wrong; lines already in the file for a key are NOT overwritten, so hand edits survive a re-run. New songs are added.
Rules, in order: bracketed title (《》「」【】[] or quotes) -> split on " - " / "|" and drop the artist part -> strip noise words
(Official MV, Lyric Video, HD, 4K, 歌詞, ...). Everything the rules cannot get right goes in MANUAL below.
"""
import argparse, os, re, sys

ROOT = r"D:\ai\Karaoke"
OUT = os.path.join(ROOT, "titles.txt")

MANUAL = {   # stems the rules cannot get right (stem -> title)
    "《黃偉文 十年選》陳奕迅 - 反高潮 HD": "反高潮",
    "裙下之臣--陳奕迅(from solidays disc1)": "裙下之臣",
    "陳奕迅 - 人車誌 320Kbps Eason Chan": "人車誌",
    "陳奕迅Eason Chan-歲月如歌(衝上雲霄II MV)": "歲月如歌",
}

# (artist folder, regex found in the stem, title) for stems the rules cannot get right
MANUAL_RE = [
    ("Beyond", r"原諒我今天", "原諒我今天"),
    ("Janice M. Vidal", r"一場誤會", "一場誤會"),
    ("Aimyon", r"マリーゴールド", "マリーゴールド"),
    ("Aimyon", r"Ai wo tsutaetaidatoka", "愛を伝えたいだとか (Ai wo tsutaetaidatoka)"),
    ("BE_FIRST", r"夢中", "夢中"),
    ("Vaundy", r"怪獣の花唄", "怪獣の花唄"),
    ("IVE", r"Mirror Mirror", "Mirror Mirror"),
    ("N.Flying", r"우주먼지", "우주먼지 (Fading Away)"),
    ("SOYEON", r"TOESA", "I'm gonna TOESA"),
    ("Panther Chan", r"翻風", "翻風 (Breezy)"),
    ("Katy Perry", r"Dark Horse", "Dark Horse (ft. Juicy J)"),
    ("Wiz Khalifa", r"See You Again", "See You Again (ft. Charlie Puth)"),
    ("ILLIT", r"체포하겠어", "체포하겠어 (You're Under Arrest)"),
    ("SUPER JUNIOR", r"STUCK WITH U", "Stuck With U (우리의 꽃말)"),
    ("G.E.M", r"再见 \(Goodbye\)", "再见 (Goodbye)"),
    ("G.E.M", r"多远都要在一起", "多远都要在一起 (Long Distance)"),
    ("Jerry Yan", r"恆星不忘", "恆星不忘 (Forever Forever)"),
    ("Various Artists", r"Ni Yao De Ai", "你要的愛 (Ni Yao De Ai)"),
    ("Various Artists", r"一個人想著一個人", "一個人想著一個人"),
    ("Justin Bieber", r"What Do You Mean", "What Do You Mean?"),
    ("Post Malone", r"Sunflower", "Sunflower"),
    ("Andy Hui", r"昨遲人", "昨遲人"),
    ("Charmaine Fong", r"Buried you", "Buried You (All I Ever Know)"),
    ("Charmaine Fong", r"我想過一個怎樣的人生", "我想過一個怎樣的人生？"),
    ("Mayday", r"難哄", "任性"),
    ("back number", r"君の恋人になったら", "君の恋人になったら"),
    ("sakanaction", r"夜の踊り子", "夜の踊り子"),
    ("YESUNG", r"Silhouette", "Silhouette"),
    ("Various Artists", r"Pursuit of Jade", "一念"),
    ("Silence Wang", r"笨蛋", "笨蛋 (SillyYouSillyMe)"),
    ("Various Artists", r"像晴天像雨天", "像晴天像雨天 (Like Sunny Days, Like Rainy Days)"),
    ("George Au", r"出什麼風頭", "出什麼風頭"),
    ("Yorushika", r"雲を抜け風下へ私だけ", "雲を抜け風下へ私だけ"),
    ("back number", r"수평선", "水平線"),
    ("IVE", r"04\. CRUMBS", "CRUMBS"),
    ("IVE", r"06\. Glow Motion", "Glow Motion"),
    ("Beyond", r"完全的擁有", "完全的擁有"),
    ("Beyond", r"高溫派對", "高溫派對"),
    ("Twenty One Pilots", r"Heathens", "Heathens"),
    ("ROS", r"APT\.", "APT."),
    ("ILLIT", r"It's Me", "It's Me"),
    ("BE_FIRST", r"not a bad life", "It's not a bad life"),
    ("JEONGHAN", r"12 o'clock", "12 o'clock"),
    ("SUPER JUNIOR-83z", r"給你的約定", "給你的約定 (Promise)"),
    ("back number", r"행복 \(幸せ\)", "幸せ"),
]

NOISE_WORDS = r"official|music video|\bmv\b|m/v|lyrics?|lyric video|audio|visualizer|\bhd\b|\bhq\b|4k|1080p|720p|full|live|歌詞|歌词|官方|完整版|動態|动态|高清|首播|正式|版mv|mv版|special movie|from |ktv|字幕|繁體"
BRACKETS = r"[\(\[（【「『《][^\)\]）】」』》]*[\)\]）】」』》]"


def norm(s):
    s = s.replace("⧸", "/").replace("｜", "|").replace("’", "'").replace("‘", "'").replace("“", '"').replace("”", '"').replace("＂", '"').replace("~", " ")
    return re.sub(r"\s+", " ", s).strip()


def strip_noise(t):
    def drop(m):
        return " " if re.search(NOISE_WORDS, m.group(0), re.I) else m.group(0)
    t = re.sub(BRACKETS, drop, t)
    t = re.sub(r"\b(official (music )?video|official lyric video|official mv|official audio|official lyrics?( mv)?|music video|lyric video|m/v|mv)\b", " ", t, flags=re.I)
    t = re.sub(r"\(?\bfull\b\)?$", "", t, flags=re.I)
    t = re.sub(r"\s*\(\d+(\.\d+)?x\)", "", t)                       # (1.1x) speed tags
    t = re.sub(r'^"(.+)"$', r"\1", t)
    t = re.sub(r"[\s\-–—|:：,]+$", "", t)
    t = re.sub(r"^[\s\-–—|:：,]+", "", t)
    return re.sub(r"\s{2,}", " ", t).strip()


def artist_tokens(artist):
    toks = {artist.lower()}
    toks.update(w.lower() for w in re.split(r"[\s.&_,]+", artist) if len(w) >= 3)
    return toks


def is_artist_part(part, artist):
    """True when a piece of the name is just the artist (in English or with the Chinese/Korean name next to it)."""
    p = re.sub(r"[^\w]+", " ", part.lower()).strip()
    a = re.sub(r"[^\w]+", " ", artist.lower()).strip()
    if not p:
        return True
    if p == a or p.startswith(a + " ") or p.endswith(" " + a) or a in p.split(","):
        rest = p.replace(a, " ").strip()
        return not re.search(r"[a-z]{3,}", rest) or len(rest) <= 12
    words = [w for w in re.split(r"[\s,&]+", p) if w]
    atoks = artist_tokens(artist)
    return bool(words) and all(w in atoks or not re.search(r"[a-z]", w) and len(w) <= 4 for w in words) and any(w in atoks for w in words)


def clean(stem, artist):
    if stem in MANUAL:
        return MANUAL[stem]
    for art, rx, title in MANUAL_RE:
        if art == artist and re.search(rx, stem):
            return title
    s = norm(stem)
    # a parenthetical that names a film / series ("(電影《金多寶》片尾曲)") is not the title: drop it first
    s2 = re.sub(r"\s*[（(][^()（）]*《[^《》]*》[^()（）]*[）)]", " ", s).strip()
    if s2 and re.search(r"[^\W_]", s2):
        s = re.sub(r"\s{2,}", " ", s2)
    # 1. a bracketed title: 《Title》 「Title」 【Title】 [Title] or 'Title'
    m = re.search(r"《([^《》]+)》(\s*[（(]([A-Za-z][^)）]*)[)）])?", s) or re.search(r"[「『]([^「」『』]+)[」』]", s) or re.search(r"[【\[]\s*([^【】\[\]]{2,})\s*[】\]]", s)
    if m and not re.search(NOISE_WORDS, m.group(1), re.I):
        t = m.group(1).strip()
        if m.lastindex and m.lastindex >= 3 and m.group(3) and not re.search(NOISE_WORDS, m.group(3), re.I):
            t += " (" + m.group(3).strip() + ")"
        return strip_noise(t) or t
    # quotes: ILLIT (아일릿) 'Magnetic' Official MV
    m = re.search(r"(?<![\w])'([^']{2,60})'(?![\w])", s)
    if m:
        return m.group(1).strip()
    # 2. split into parts and drop the artist
    parts = [p.strip() for p in re.split(r"\s+[-–—]\s+|\s*\|\s*|\s+-(?=[^\s])|(?<=\S)-\s+", s) if p.strip()]
    keep = [p for p in parts if not is_artist_part(strip_noise(p), artist) and strip_noise(p)]
    # "<short Chinese name> - Title": the first part is the artist's own Chinese name, not a title
    if len(keep) >= 2 and keep[0] == parts[0] and re.fullmatch(r"[一-鿿]{1,4}", strip_noise(keep[0])):
        keep = keep[1:]
    if keep:
        t = re.sub(r"^" + re.escape(artist) + r"\s*[:：]\s*", "", strip_noise(keep[0]), flags=re.I)   # "artist： Title"
        if t:
            return t
    # 3. leading "Artist Title" with no separator
    t = strip_noise(s)
    t = re.sub(r"^" + re.escape(artist) + r"\s*[:：]\s*", "", t, flags=re.I)        # "artist： Title"
    low = t.lower()
    if low.startswith(artist.lower() + " "):
        t2 = t[len(artist):].strip()
        if t2:
            return t2
    return t or stem


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser()
    ap.add_argument("--artist")
    ap.add_argument("--review", action="store_true")
    a = ap.parse_args()

    have = {}
    if os.path.exists(OUT):
        for ln in open(OUT, encoding="utf-8"):
            if "=" in ln and not ln.lstrip().startswith("#"):
                have[ln.split("=", 1)[0].strip()] = ln.rstrip("\n")
    out = ["# Song title overrides: Artist/<file name> = Title shown on the TV and phone (made by make_titles.py; hand edits kept)"]
    made = new = 0
    odd = []
    for lang in sorted(os.listdir(ROOT)):
        ldir = os.path.join(ROOT, lang)
        if not os.path.isdir(ldir):
            continue
        for artist in sorted(os.listdir(ldir)):
            adir = os.path.join(ldir, artist)
            if not os.path.isdir(adir) or (a.artist and a.artist.lower() != artist.lower()):
                continue
            for stem in sorted({os.path.splitext(f)[0] for f in os.listdir(adir) if f.lower().endswith((".mp3", ".mp4"))}):
                key = "%s/%s" % (artist, stem)
                line = have.pop(key, None)
                if line is None:
                    title = clean(stem, artist)
                    line = "%s = %s" % (key, title)
                    new += 1
                    if len(title) > 40 or re.search(r"official|\bmv\b|\|", title, re.I) or title == stem and len(stem) > 25 \
                            or (len(title) <= 3 and len(stem) > 14) or title.lower() == artist.lower():
                        odd.append((artist, stem, title))
                out.append(line)
                made += 1
    out.extend(have.values())   # keep any other lines (songs no longer on disk, hand-written)
    if not a.artist or True:
        with open(OUT, "w", encoding="utf-8", newline="\n") as fh:
            fh.write("\n".join(out) + "\n")
    print(made, "titles,", new, "new ->", OUT)
    if a.review:
        print(len(odd), "look messy:")
        for artist, stem, title in odd:
            print("  %s | %s\n      -> %s" % (artist, stem, title))


if __name__ == "__main__":
    main()
