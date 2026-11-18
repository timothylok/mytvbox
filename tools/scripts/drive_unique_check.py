r"""
List files on an old song drive that exist NOWHERE else on the PC, and (with --rescue DIR) copy them to DIR before the drive tree is deleted.

    python tools\scripts\drive_unique_check.py --drive F:\Karaoke [--rescue D:\ai\drive_rescue]

A file counts as "on the PC" when:
  mp3 : same relative path under D:\ai\Karaoke or D:\ai\Karaoke_pre_normalize, or the same file name anywhere under D:\ai\GetVideo
  lrc : same relative path under D:\ai\Karaoke, or the same file name anywhere under D:\ai\GetVideo or D:\ai\setupbox\tools\align_out
  mp4 : the same file name anywhere under D:\ai\GetVideo
  anything else (txt, ...): reported, not rescued unless --all
"""
import argparse, os, shutil, sys


def index(root, exts):
    out = set()
    for dp, _, fns in os.walk(root):
        for f in fns:
            if f.lower().endswith(exts):
                out.add(f)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--drive", required=True)
    ap.add_argument("--rescue")
    ap.add_argument("--karaoke", default=r"D:\ai\Karaoke")
    ap.add_argument("--pre", default=r"D:\ai\Karaoke_pre_normalize")
    ap.add_argument("--library", default=r"D:\ai\GetVideo")
    ap.add_argument("--align", default=r"D:\ai\setupbox\tools\align_out")
    a = ap.parse_args()

    lib_names = index(a.library, (".mp3", ".mp4", ".lrc"))
    align_names = index(a.align, (".lrc",)) if os.path.isdir(a.align) else set()
    unique = []
    n = 0
    for dp, _, fns in os.walk(a.drive):
        for f in fns:
            n += 1
            p = os.path.join(dp, f)
            rel = os.path.relpath(p, a.drive)
            ext = os.path.splitext(f)[1].lower()
            found = False
            if ext == ".mp3":
                found = os.path.isfile(os.path.join(a.karaoke, rel)) or os.path.isfile(os.path.join(a.pre, rel)) or f in lib_names
            elif ext == ".lrc":
                found = os.path.isfile(os.path.join(a.karaoke, rel)) or f in lib_names or f in align_names
            elif ext == ".mp4":
                found = f in lib_names
            else:
                found = os.path.isfile(os.path.join(a.karaoke, rel))
            if not found:
                unique.append((rel, p))
    print("files checked: %d | only on the drive: %d" % (n, len(unique)))
    for rel, _ in unique:
        print("  ONLY HERE:", rel)
    if a.rescue and unique:
        for rel, p in unique:
            dst = os.path.join(a.rescue, rel)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            if not os.path.isfile(dst) or os.path.getsize(dst) != os.path.getsize(p):
                shutil.copy2(p, dst + ".part")
                os.replace(dst + ".part", dst)
            ok = os.path.getsize(dst) == os.path.getsize(p)
            print("  rescued" if ok else "  RESCUE FAILED", "->", dst)
            if not ok:
                return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
