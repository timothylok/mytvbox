"""Run the release tests for the Karaoke app.

    python run_tests.py            # the pure-Java checks (no device needed)
    python run_tests.py --ui       # + the folder-picker test on the TV box (needs adb and the box; see folder_picker_ui_test.py)

Exit code 0 only when everything that ran passed. Run it before every release build and before committing a change
that touches storage, the folder picker or the library scan (see CLAUDE.md rule 1).
"""
import os, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
KARAOKE = os.path.abspath(os.path.join(HERE, ".."))
JAVA = os.environ.get("JAVA_HOME", r"C:\Program Files\Java\jdk-20")
SRC = os.path.join(KARAOKE, "app", "src", "main", "java", "com", "setupbox", "karaoke")


def run(cmd, **kw):
    r = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace", **kw)
    sys.stdout.write(r.stdout + r.stderr)
    return r.returncode


def main():
    ok = True
    out = os.path.join(KARAOKE, "build", "rtest")
    os.makedirs(out, exist_ok=True)
    print("== FolderPickerLogicTest (JVM)")
    rc = run([os.path.join(JAVA, "bin", "javac"), "-encoding", "UTF-8", "-d", out,
              os.path.join(SRC, "Storage.java"), os.path.join(SRC, "FileStorage.java"),
              os.path.join(HERE, "FolderPickerLogicTest.java")])
    if rc == 0:
        rc = run([os.path.join(JAVA, "bin", "java"), "-Dfile.encoding=UTF-8", "-cp", out, "FolderPickerLogicTest"])
    ok = ok and rc == 0
    print("== YearsTest (JVM)")
    rc = run([os.path.join(JAVA, "bin", "javac"), "-encoding", "UTF-8", "-d", out, os.path.join(SRC, "Years.java"), os.path.join(HERE, "YearsTest.java")])
    if rc == 0:
        rc = run([os.path.join(JAVA, "bin", "java"), "-Dfile.encoding=UTF-8", "-cp", out, "YearsTest"])
    ok = ok and rc == 0
    print("== PairingTest (JVM)")
    rc = run([os.path.join(JAVA, "bin", "javac"), "-encoding", "UTF-8", "-d", out, os.path.join(SRC, "Pairing.java"), os.path.join(HERE, "PairingTest.java")])
    if rc == 0:
        rc = run([os.path.join(JAVA, "bin", "java"), "-Dfile.encoding=UTF-8", "-cp", out, "PairingTest"])
    ok = ok and rc == 0
    print("== PrefetcherTest (JVM)")
    rc = run([os.path.join(JAVA, "bin", "javac"), "-encoding", "UTF-8", "-d", out, os.path.join(SRC, "Storage.java"), os.path.join(SRC, "FileStorage.java"),
              os.path.join(SRC, "Lrc.java"), os.path.join(SRC, "Prefetcher.java"), os.path.join(HERE, "PrefetcherTest.java")])
    if rc == 0:
        rc = run([os.path.join(JAVA, "bin", "java"), "-Dfile.encoding=UTF-8", "-cp", out, "PrefetcherTest"])
    ok = ok and rc == 0
    print("== BrowseModelTest (JVM)")
    rc = run([os.path.join(JAVA, "bin", "javac"), "-encoding", "UTF-8", "-d", out, os.path.join(SRC, "BrowseModel.java"), os.path.join(HERE, "BrowseModelTest.java")])
    if rc == 0:
        rc = run([os.path.join(JAVA, "bin", "java"), "-Dfile.encoding=UTF-8", "-cp", out, "BrowseModelTest"])
    ok = ok and rc == 0
    print("== shift_line_starts_test (pipeline, Python)")
    ok = run([sys.executable, "-X", "utf8", "-I", os.path.join(HERE, "shift_line_starts_test.py")]) == 0 and ok
    print("== LrcTest (JVM)")
    rc = run([os.path.join(JAVA, "bin", "javac"), "-encoding", "UTF-8", "-d", out, os.path.join(SRC, "Lrc.java"), os.path.join(KARAOKE, "tests", "LrcTest.java")])
    if rc == 0:
        rc = run([os.path.join(JAVA, "bin", "java"), "-Dfile.encoding=UTF-8", "-cp", out, "LrcTest"])
    ok = ok and rc == 0
    print("== QueueManagerTest (JVM)")
    rc = run([os.path.join(JAVA, "bin", "javac"), "-encoding", "UTF-8", "-d", out, os.path.join(SRC, "QueueManager.java"), os.path.join(KARAOKE, "tests", "QueueManagerTest.java")])
    if rc == 0:
        rc = run([os.path.join(JAVA, "bin", "java"), "-Dfile.encoding=UTF-8", "-cp", out, "QueueManagerTest"])
    ok = ok and rc == 0
    print("== AppFoldersTest (JVM)")
    rc = run([os.path.join(JAVA, "bin", "javac"), "-encoding", "UTF-8", "-d", out, os.path.join(SRC, "AppFolders.java"), os.path.join(HERE, "AppFoldersTest.java")])
    if rc == 0:
        rc = run([os.path.join(JAVA, "bin", "java"), "-Dfile.encoding=UTF-8", "-cp", out, "AppFoldersTest"])
    ok = ok and rc == 0
    if "--ui" in sys.argv:
        print("== folder_picker_ui_test (device)")
        extra = [a for a in sys.argv[1:] if a != "--ui"]
        ok = run([sys.executable, "-I", os.path.join(HERE, "folder_picker_ui_test.py")] + extra) == 0 and ok
    print("RELEASE TESTS PASSED" if ok else "RELEASE TESTS FAILED")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
