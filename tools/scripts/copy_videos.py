"""Copy the music videos that go with the karaoke songs onto the box's USB drive.

For every karaoke song already made in D:\\ai\\Karaoke\\<Language>\\<Artist>\\<name>.mp3, look for a video with the
same name (<name>.mp4) anywhere under D:\\ai\\GetVideo and push it next to the song on the drive. The Karaoke app
then shows that video, muted and dimmed, behind the lyrics.

Only songs whose karaoke mp3 already exists are handled, so a video never shows up alone in the song list
(a lone .mp4 on the drive is treated as a song of its own). Run it again later to catch up with new songs.
Videos already on the drive with the same size are skipped.

usage: copy_videos.py [--dry-run] [--only ARTIST] [--limit N]
"""
import argparse, os, subprocess, sys, time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from ledger import log
ADB = os.path.join(HERE, "..", "sdk", "platform-tools", "adb.exe")


def run(cmd):
    return subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--library", default=r"D:\ai\GetVideo")
    ap.add_argument("--karaoke", default=r"D:\ai\Karaoke")
    ap.add_argument("--box", default="192.168.3.79:5555")
    ap.add_argument("--box-root", default="/storage/A6D0-F59E/Karaoke")
    ap.add_argument("--only", help="only artists whose folder name contains this text")
    ap.add_argument("--limit", type=int)
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()

    # still-picture videos (find_static_videos.py) are not worth copying: the app plays those songs audio-only
    static = set()
    try:
        for ln in open(r"D:\ai\Karaoke\static_videos.txt", encoding="utf-8"):
            if ln.strip() and not ln.startswith("#"):
                static.add(ln.strip())
    except OSError:
        pass
    # every mp4 in the library, by file name without extension
    videos = {}
    for dp, _, fns in os.walk(a.library):
        for f in fns:
            if f.lower().endswith(".mp4"):
                videos.setdefault(os.path.splitext(f)[0], os.path.join(dp, f))

    todo = []
    for lang in sorted(os.listdir(a.karaoke)):
        ldir = os.path.join(a.karaoke, lang)
        if not os.path.isdir(ldir):
            continue
        for artist in sorted(os.listdir(ldir)):
            if a.only and a.only.lower() not in artist.lower():
                continue
            adir = os.path.join(ldir, artist)
            if not os.path.isdir(adir):
                continue
            for f in sorted(os.listdir(adir)):
                stem, ext = os.path.splitext(f)
                if ext.lower() == ".mp3" and ".part" not in f and stem in videos and stem not in static:
                    todo.append((lang, artist, stem, videos[stem]))
    if a.limit:
        todo = todo[:a.limit]
    total_gb = sum(os.path.getsize(v) for _, _, _, v in todo) / 1e9
    print(f"{len(todo)} videos to check ({total_gb:.1f} GB)")
    if a.dry_run:
        for lang, artist, stem, v in todo:
            print(f"  {lang}/{artist}/{stem}.mp4")
        return

    done = skipped = failed = fixed = 0
    t0 = time.time()
    for n, (lang, artist, stem, src) in enumerate(todo, 1):
        remote_dir = f"{a.box_root}/{lang}/{artist}"
        remote = f"{remote_dir}/{stem}.mp4"
        size = os.path.getsize(src)
        tmp = remote + ".part"
        r = run([ADB, "-s", a.box, "shell", f"stat -c %s {q(remote)}"])
        if r.stdout.strip() == str(size):
            skipped += 1
            log("video", src, remote, "skipped", "same size already on box")
            continue
        # a complete .part left behind by an earlier run (the rename used to fail on names with an apostrophe)
        r = run([ADB, "-s", a.box, "shell", f"stat -c %s {q(tmp)}"])
        if r.stdout.strip() == str(size) and rename(a, tmp, remote, size):
            print(f"[{n}/{len(todo)}] {lang}/{artist}/{stem}.mp4  (finished an earlier copy)", flush=True)
            log("video", src, remote, "ok", "finished earlier .part")
            fixed += 1
            continue
        print(f"[{n}/{len(todo)}] {lang}/{artist}/{stem}.mp4  ({size / 1e6:.0f} MB)", flush=True)
        run([ADB, "-s", a.box, "shell", f"mkdir -p {q(remote_dir)}"])
        r = run([ADB, "-s", a.box, "push", src, tmp])
        if r.returncode != 0:
            print("   push failed:", r.stdout[-200:].strip())
            log("video", src, remote, "failed", "push")
            failed += 1
            continue
        # only a complete video gets its final name (the app ignores *.part*)
        if not rename(a, tmp, remote, size):
            print("   rename failed:", remote)
            log("video", src, remote, "failed", "rename")
            failed += 1
            continue
        log("video", src, remote, "ok", f"{size} bytes")
        done += 1
    print(f"copied {done}, finished earlier copies {fixed}, already there {skipped}, failed {failed}, {(time.time() - t0) / 60:.1f} min")


def q(path):
    """Quote a path for the box's shell (handles apostrophes in song names)."""
    return "'" + path.replace("'", "'\\''") + "'"


def rename(a, tmp, final, size):
    run([ADB, "-s", a.box, "shell", f"mv {q(tmp)} {q(final)}"])
    r = run([ADB, "-s", a.box, "shell", f"stat -c %s {q(final)}"])
    return r.stdout.strip() == str(size)


if __name__ == "__main__":
    main()
