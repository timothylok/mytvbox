"""Give every loose mp3 in the Jellyfin music library roots its own artist folder + album tags.

Jellyfin only builds an album from a FOLDER, and the Android TV app lists an artist's songs
only through their albums. So for each mp3 sitting directly in a library root:
  1. copy the original to a backup folder          (skipped in --dry-run)
  2. add TALB ("Singles") and TPE2 (album artist) if missing   (existing values never overwritten)
  3. move it into <root>/<lead artist>/
A manifest (JSON) of every move is written so it can be undone with --undo.

usage: organize_music.py --roots R1 R2 ... [--album Singles] [--backup DIR] [--manifest FILE] [--dry-run]
       organize_music.py --undo --manifest FILE
"""
import argparse, json, os, re, shutil, sys
from collections import Counter, defaultdict
from mutagen.id3 import ID3, TALB, TPE2, ID3NoHeaderError

SPLIT = re.compile(r"[,;|]")           # '/' is NOT used for the lead artist (K/DA, HUNTR/X)
BAD = re.compile(r'[<>:"/\\|?*\x00-\x1f]')


def safe(name):
    name = BAD.sub("_", name).strip().rstrip(". ")
    return name[:80] or "Unknown"


def artists_of(tags):
    if "TPE1" not in tags:
        return []
    vals = []
    for v in tags["TPE1"].text:
        vals += [p.strip() for p in SPLIT.split(str(v)) if p.strip()]
    return vals


def plan(roots):
    items, skipped = [], defaultdict(list)
    for ri, root in enumerate(roots):
        for fn in sorted(os.listdir(root)):
            p = os.path.join(root, fn)
            if not os.path.isfile(p):
                continue
            if not fn.lower().endswith(".mp3"):
                skipped["not-mp3"].append(p)
                continue
            try:
                tags = ID3(p)
            except ID3NoHeaderError:
                skipped["no-tag"].append(p)
                continue
            arts = artists_of(tags)
            if not arts:
                skipped["no-artist"].append(p)
                continue
            lead = safe(arts[0])
            dest = os.path.join(root, lead, fn)
            if os.path.exists(dest):
                skipped["dest-exists"].append(p)
                continue
            items.append({"root_index": ri, "src": p, "dest": dest, "lead": lead, "albumartist": ", ".join(arts)})
    return items, skipped


def undo(manifest):
    data = json.load(open(manifest, encoding="utf-8"))
    ok = bad = 0
    for e in data["moved"]:
        try:
            if os.path.exists(e["dest"]) and not os.path.exists(e["src"]):
                shutil.move(e["dest"], e["src"])
            if e.get("backup") and os.path.exists(e["backup"]):
                shutil.copy2(e["backup"], e["src"])      # restores the original tags too
            ok += 1
        except Exception as ex:
            bad += 1
            print("undo failed:", e["src"], ex)
    for d in {os.path.dirname(e["dest"]) for e in data["moved"]}:
        try:
            os.rmdir(d)
        except OSError:
            pass
    print("undo: restored=%d failed=%d" % (ok, bad))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--roots", nargs="*", default=[])
    ap.add_argument("--album", default="Singles")
    ap.add_argument("--backup")
    ap.add_argument("--manifest")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--undo", action="store_true")
    a = ap.parse_args()
    if a.undo:
        return undo(a.manifest)

    items, skipped = plan(a.roots)
    per_root = Counter(os.path.basename(a.roots[i["root_index"]]) for i in items)
    folders = {(i["root_index"], i["lead"]) for i in items}
    print("files to organise: %d   artist folders to create: %d" % (len(items), len(folders)))
    for r, n in per_root.items():
        print("  %-12s %d files" % (r, n))
    for why, lst in skipped.items():
        print("skipped (%s): %d" % (why, len(lst)))
        for p in lst[:8]:
            print("    ", os.path.basename(p))
    if a.dry_run:
        by = Counter(i["lead"] for i in items)
        print("largest folders:", ", ".join("%s(%d)" % kv for kv in by.most_common(8)))
        return

    os.makedirs(a.backup, exist_ok=True)
    moved, failed = [], []
    for n, it in enumerate(items, 1):
        bk = os.path.join(a.backup, "r%d" % it["root_index"], os.path.basename(it["src"]))
        try:
            os.makedirs(os.path.dirname(bk), exist_ok=True)
            shutil.copy2(it["src"], bk)                                  # 1. backup
            tags = ID3(it["src"])
            changed = False
            if "TALB" not in tags or not str(tags["TALB"]).strip():       # 2. tags
                tags.add(TALB(encoding=3, text=a.album)); changed = True
            if "TPE2" not in tags or not str(tags["TPE2"]).strip():
                tags.add(TPE2(encoding=3, text=it["albumartist"])); changed = True
            if changed:
                tags.save(it["src"], v2_version=3)
            os.makedirs(os.path.dirname(it["dest"]), exist_ok=True)      # 3. move
            shutil.move(it["src"], it["dest"])
            moved.append({"src": it["src"], "dest": it["dest"], "backup": bk})
        except Exception as ex:
            failed.append({"src": it["src"], "error": str(ex)})
            print("FAILED:", os.path.basename(it["src"]), "->", ex)
        if n % 50 == 0:
            print("  ...%d/%d" % (n, len(items)))
    json.dump({"moved": moved, "failed": failed, "skipped": {k: v for k, v in skipped.items()}},
              open(a.manifest, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print("DONE moved=%d failed=%d manifest=%s" % (len(moved), len(failed), a.manifest))


main()
