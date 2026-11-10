# CLAUDE.md - TV box karaoke project

Read `HANDOFF.md` first (state, outstanding tasks, gotchas). Tools live in `tools/scripts/`; every script step is logged to `processed.csv` (see `tools/scripts/ledger.py`).

## Project rules (from the user)

1. **Test before you commit and push.** Run the checks that apply and look at the result before `git commit`:
   - plain-Java tests in `karaoke/tests/` (commands are in each file's header) when the code they cover changed;
   - Python scripts: `python -I -m py_compile` and a dry run (`--dry-run`) or a one-song run;
   - app changes: build, install on the box, and check the behaviour on the TV (screenshot / `/api/state`), not only that it compiles.
   - changes to storage, the folder picker or the library scan: `python karaokeelease-testsun_tests.py --ui` (see `karaoke/release-tests/README.md`; the box must be idle: it clears the app's data and restores history/queue).
   Commit and push only when asked ("commit and push"); never push untested work. Say what was tested (and what could not be).
2. **Only copy songs to the TV box that have a lyric file, and only after the lyric file has been processed.**
   "Processed" = `align_folder.py` has given the `.lrc` per-character `<mm:ss.xx>` tags and the quality check (`clean_aligned.py`) has run.
   A song with no `.lrc`, or with an unprocessed (plain) `.lrc`, is not pushed to the box (mp3, lrc or mp4). Push the aligned `.lrc`
   together with the mp3 (and the mp4, if it is a real video). Log each push in `processed.csv`.
3. **Android best practices (top 10) - follow them when changing the app in `karaoke/` and `launcher/`:**
   1. **Never block the main thread.** Disk, network, scans and decoding run on background threads; post results back with `runOnUiThread`/`Handler`.
   2. **Release what you hold.** ExoPlayer, surfaces, the web server, handlers and threads are released/stopped in `onDestroy` (and `Handler` callbacks removed) so nothing leaks or restarts twice.
   3. **Survive lifecycle and process death.** Keep state in files (queue `state.txt`, history, flips), not only in memory; tolerate `onCreate` running again; keep the foreground service for background playback.
   4. **Fail soft.** Catch and log errors around files, parsing and network; a broken song, line or JSON entry must never stop the party (skip it, carry on).
   5. **Small, safe file I/O.** Write to a temp file then rename; ignore `*.part`; tolerate missing/damaged files; check `isFile()`/size before use; keep paths and names UTF-8 safe.
   6. **Design for the TV.** D-pad/remote first (focus, no touch-only actions), readable text at 10 feet, safe margins, no burn-in of static bright content, sleep mode for idle screens.
   7. **Mind the weak hardware** (32-bit ARM, 2 GB-class box, USB 2.0): no large allocations or bitmaps without downsampling, avoid per-frame work, prefer hardware decoding, keep the UI refresh cheap.
   8. **Keep the network surface small.** The phone server is LAN-only and unauthenticated: no secrets, no destructive endpoints beyond the queue, validate every parameter, never expose it to the internet.
   9. **Honour Android limits.** Use scoped permissions correctly, declare services/permissions in the manifest, keep the box (Android 10 / API 29) working, guard APIs newer than the minSdk, and write new code so it will also work under a current targetSdk (scoped storage, foreground-service types), since Play needs a recent target.
   10. **Test and log on a real device.** Use `Log` tags (no secrets/personal data in logs), check `logcat` for crashes/ANRs after every install, and keep unit-testable logic (queue, lrc, history, crops) free of Android classes.

4. **Plan for Google Play.** The app will be published on Google Play once it is production grade. New work should not add
   anything that blocks that (raw /sdcard paths, bundled songs, unlicensed images, new open endpoints); see the Google Play
   section in `HANDOFF.md`.

## Working notes

- `adb` is not on PATH: `D:\ai\setupbox\tools\sdk\platform-tools\adb.exe` (box at 192.168.3.79:5555). Git Bash needs `MSYS_NO_PATHCONV=1`.
- Build from PowerShell: `JAVA_HOME=C:\Program Files\Java\jdk-20`, `ANDROID_HOME=D:\ai\setupbox\tools\sdk`, `tools\gradle-8.7\bin\gradle.bat assembleDebug` in `karaoke/`.
- Song, video and audio files are git-ignored. Do not write Java/regex backslashes through bash heredocs; use the Edit/Write tools.
- Do not interrupt a song that is playing on the TV (installing the app restarts it): wait for the song to end or ask.
