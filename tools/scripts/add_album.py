"""Add an album tag (TALB) and album-artist tag (TPE2) to mp3 files that lack them.

usage: add_album.py <album name> <file> [<file> ...]
Existing TALB/TPE2 values are never overwritten. The album artist is copied from the
track's own artist tag (TPE1). Only the tag is touched; the audio is not re-encoded.
"""
import sys
from mutagen.id3 import ID3, TALB, TPE2, ID3NoHeaderError

album = sys.argv[1]
changed = skipped = 0
for path in sys.argv[2:]:
    try:
        tags = ID3(path)
    except ID3NoHeaderError:
        print("no ID3 tag, skipped:", path)
        skipped += 1
        continue
    todo = []
    if "TALB" not in tags or not str(tags["TALB"]).strip():
        tags.add(TALB(encoding=3, text=album))
        todo.append("album")
    if "TPE2" not in tags or not str(tags["TPE2"]).strip():
        artist = str(tags["TPE1"]).strip() if "TPE1" in tags else ""
        if artist:
            tags.add(TPE2(encoding=3, text=artist))
            todo.append("album-artist")
    if todo:
        tags.save(path, v2_version=3)
        changed += 1
        print("tagged (%s): %s" % (", ".join(todo), path.rsplit("\\", 1)[-1]))
    else:
        skipped += 1
print("changed=%d skipped=%d" % (changed, skipped))
