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
    if "--ui" in sys.argv:
        print("== folder_picker_ui_test (device)")
        extra = [a for a in sys.argv[1:] if a != "--ui"]
        ok = run([sys.executable, "-I", os.path.join(HERE, "folder_picker_ui_test.py")] + extra) == 0 and ok
    print("RELEASE TESTS PASSED" if ok else "RELEASE TESTS FAILED")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
