"""One-off: run detect_crops.py over every GetVideo artist folder that has an mp4 (the nightly job only did new songs)."""
import os, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from make_karaoke import LANGUAGE

root = r"D:\ai\GetVideo"
dirs = []
for lib in LANGUAGE:
    ld = os.path.join(root, lib)
    if os.path.isdir(ld):
        for a in sorted(os.listdir(ld)):
            d = os.path.join(ld, a)
            if os.path.isdir(d) and any(f.lower().endswith(".mp4") for f in os.listdir(d)):
                dirs.append(d)
print(len(dirs), "folders")
sys.exit(subprocess.run([sys.executable, os.path.join(HERE, "detect_crops.py")] + dirs).returncode)
