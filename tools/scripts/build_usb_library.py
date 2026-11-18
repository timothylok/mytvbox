r"""
Build the song library on a USB drive for a TV box whose app can only read its OWN folder on the drive
(no system folder chooser, e.g. the Google TV box):  <drive>:\Android\data\<app id>\files\Karaoke\<Language>\<Artist>\<song>.{mp3,lrc,mp4}

    python tools\scripts\build_usb_library.py --dest F:\Android\data\com.superghosttim.karaokeking\files\Karaoke --like F:\Karaoke --dry-run
    python tools\scripts\build_usb_library.py --dest ... --like F:\Karaoke           # copy
    python tools\scripts\build_usb_library.py --dest ... --all                        # every processed song, not only those in --like

Rules (CLAUDE.md rule 2): a song is copied only when it has a PROCESSED lyric file (per-character <mm:ss.xx> tags, from align_folder.py) in D:\ai\Karaoke,
it is not held by daily_sync (logs\daily_sync_state.json), the mp3 comes from D:\ai\Karaoke (loudness-normalized), the video (same name under
D:\ai\GetVideo) is copied only when it is a real video (not in static_videos.txt). Also copies titles.txt, years.txt, crops.txt, static_videos.txt.
Files are written as .part and renamed after a size check; same-size files are skipped, so it can be re-run. Every copy is logged in processed.csv.
"""
import argparse, json, os, re, shutil, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ledger import log  # noqa: E402

TAG = re.compile(r"<\d\d:\d\d[.:]\d\d>")
EXTRAS = ["titles.txt", "years.txt", "crops.txt", "static_videos.txt"]


def processed_lrc(p):
    try:
        with open(p, encoding="utf-8", errors="replace") as fh:
            return bool(TAG.search(fh.read()))
    except OSError:
        return False


def copy_safe(src, dst):
    """Copy via .part + rename + size check. Returns 'copied', 'same' or raises."""
    size = os.path.getsize(src)
    if os.path.isfile(dst) and os.path.getsize(dst) == size:
        return "same"
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    tmp = dst + ".part"
    shutil.copyfile(src, tmp)
    if os.path.getsize(tmp) != size:
        os.remove(tmp)
        raise OSError("size mismatch after copy: " + dst)
    os.replace(tmp, dst)
    return "copied"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dest", required=True)
    ap.add_argument("--like", help="only songs that exist (same relative path) in this old tree")
    ap.add_argument("--all", action="store_true", help="every processed song")
    ap.add_argument("--karaoke", default=r"D:\ai\Karaoke")
    ap.add_argument("--library", default=r"D:\ai\GetVideo")
    ap.add_argument("--state", default=r"D:\ai\setupbox\logs\daily_sync_state.json")
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()
    if not a.like and not a.all:
        ap.error("give --like <old tree> or --all")

    held = set()
    try:
        held = set(json.load(open(a.state, encoding="utf-8")).get("held", {}).keys())
    except (OSError, ValueError):
        pass
    static = set()
    try:
        for ln in open(os.path.join(a.karaoke, "static_videos.txt"), encoding="utf-8"):
            if ln.strip() and not ln.startswith("#"):
                static.add(ln.strip())
    except OSError:
        pass
    videos = {}
    for dp, _, fns in os.walk(a.library):
        for f in fns:
            if f.lower().endswith(".mp4"):
                videos.setdefault(os.path.splitext(f)[0], os.path.join(dp, f))

    like = set()
    if a.like:
        for dp, _, fns in os.walk(a.like):
            for f in fns:
                if f.lower().endswith(".mp3"):
                    like.add(os.path.relpath(os.path.join(dp, f), a.like)[:-4].replace("\\", "/"))

    songs = []
    skipped = {"not in --like": 0, "no processed lrc": 0, "held": 0}
    for lang in sorted(os.listdir(a.karaoke)):
        ldir = os.path.join(a.karaoke, lang)
        if not os.path.isdir(ldir):
            continue
        for artist in sorted(os.listdir(ldir)):
            adir = os.path.join(ldir, artist)
            if not os.path.isdir(adir):
                continue
            for f in sorted(os.listdir(adir)):
                stem, ext = os.path.splitext(f)
                if ext.lower() != ".mp3" or ".part" in f:
                    continue
                key = "%s/%s/%s" % (lang, artist, stem)
                if not a.all and key not in like:
                    skipped["not in --like"] += 1
                    continue
                if key in held:
                    skipped["held"] += 1
                    continue
                lrc = os.path.join(adir, stem + ".lrc")
                if not processed_lrc(lrc):
                    skipped["no processed lrc"] += 1
                    continue
                vid = videos.get(stem) if stem not in static else None
                songs.append((lang, artist, stem, os.path.join(adir, f), lrc, vid))

    total = sum(os.path.getsize(s[3]) + os.path.getsize(s[4]) + (os.path.getsize(s[5]) if s[5] else 0) for s in songs)
    print("songs to copy: %d (%d with a video), %.2f GB | skipped: %s" % (len(songs), sum(1 for s in songs if s[5]), total / 1e9, skipped))
    if os.path.isdir(os.path.splitdrive(a.dest)[0] + "\\"):
        free = shutil.disk_usage(os.path.splitdrive(a.dest)[0] + "\\").free
        print("free on the destination drive: %.1f GB" % (free / 1e9))
        if free < total * 1.02 + 1e8:
            print("NOT ENOUGH SPACE")
            return 2
    if a.dry_run:
        for lang, artist, stem, mp3, lrc, vid in songs[:15]:
            print("  %s/%s/%s%s" % (lang, artist, stem[:60], "  [+video]" if vid else ""))
        if len(songs) > 15:
            print("  ... and %d more" % (len(songs) - 15))
        return 0

    copied = same = failed = 0
    for i, (lang, artist, stem, mp3, lrc, vid) in enumerate(songs, 1):
        base = os.path.join(a.dest, lang, artist, stem)
        for step, src, ext in (("push", mp3, ".mp3"), ("lrc", lrc, ".lrc"), ("video", vid, ".mp4")):
            if not src:
                continue
            try:
                r = copy_safe(src, base + ext)
                if r == "copied":
                    copied += 1
                    log(step, src, base + ext, "ok", "usb library build (processed lyrics, normalized mp3)")
                else:
                    same += 1
            except OSError as e:
                failed += 1
                print("FAILED", base + ext, e)
                log(step, src, base + ext, "failed", "usb library build: %s" % e)
        if i % 10 == 0 or i == len(songs):
            print("  %d / %d songs  (files copied %d, already there %d, failed %d)" % (i, len(songs), copied, same, failed), flush=True)
    for name in EXTRAS:
        src = os.path.join(a.karaoke, name)
        if os.path.isfile(src):
            try:
                if copy_safe(src, os.path.join(a.dest, name)) == "copied":
                    log("meta", src, os.path.join(a.dest, name), "ok", "usb library build")
            except OSError as e:
                print("FAILED extra", name, e)
    print("done: copied %d files, %d already there, %d failed" % (copied, same, failed))
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
