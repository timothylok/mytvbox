# Handoff - TV box karaoke project (as of 2026-11-09, ~11 pm NZ)

Repo: https://github.com/timothylok/mytvbox (branch `main`, everything committed up to `469abc9`, except the
new `tools/scripts/align_folder.py` and this file).
Box: Android TV box 192.168.3.79 (open ADB on :5555). PC: Windows, GTX 1650 4 GB. See also the memory notes
`project_setupbox_overview`, `project_jellyfin_music_library_layout`, `project_karaoke_app`.

## What exists

**Launcher** (`launcher/`, package `com.setupbox.launcher`) - the box's home screen. Karaoke is pinned third in
`/sdcard/Launcher/config.json` (backup of the old config: `tools/config.backup.json`).

**Karaoke app** (`karaoke/`, package `com.setupbox.karaoke`, Java + Media3 ExoPlayer + NanoHTTPD + ZXing):
- Plays a queue; guests use a phone web page (QR code on the TV, `http://192.168.3.79:8080`).
- Phone page: Queue / Browse / Search tabs; add (end / next), remove, move up / down / top, shuffle, clear,
  pause / resume / skip / restart, vocal on/off, flip channels, singer name per song, Rescan button.
- Songs live on the box's USB drive: `/storage/A6D0-F59E/Karaoke/<Language>/<Artist>/<name>.mp3` (+ `.lrc`, + `.mp4`).
  The mp3 is stereo with **LEFT = instrumental, RIGHT = original** (made by Demucs). Vocal on/off switches the channel;
  "flip" swaps it per song (saved in `/sdcard/Karaoke/flips.txt`).
- A `.mp4` with the same name next to the mp3 is shown muted and dimmed behind the lyrics (merged into one player; the
  mp3 track is forced). No video -> Bing photo slideshow over a gradient (or photos in `/sdcard/Pictures/Screensaver`).
- Lyrics from `<name>.lrc`: plain LRC or enhanced LRC (per-character `<mm:ss.xx>` tags). The character being sung is
  orange + underlined, sung ones yellow. Plain lines get an estimated sweep. Up/Down on the remote nudges timing
  (saved per song in `/sdcard/Karaoke/lyric_offsets.properties`).
- Countdown dots before singing starts, "Now singing" banner (first 6 s), "Next up" banner (last 15 s).
- Remote keys: OK = pause, hold OK or Menu = vocal on/off, Left = back 10 s, Right = skip, Up/Down = lyrics later/earlier.
- State saved to `/sdcard/Karaoke/state.txt` (queue survives restarts).

**Tools** (`tools/scripts/`, big stuff in `tools/` is git-ignored):
- `make_karaoke.py` - Demucs batch: GetVideo mp3 -> `D:\ai\Karaoke\<Language>\<Artist>\` (L/R file + copies `.lrc`);
  `--push` also adb-pushes to the drive. Resumable (skips finished songs).
- `copy_videos.py` - pushes the matching `.mp4` (same name, found anywhere under GetVideo) next to each finished karaoke
  song. Only for songs whose karaoke mp3 already exists. Re-runnable (skips same-size files).
- `align_lyrics.py` / `align_folder.py` - listen to the Demucs vocals (stable-ts / Whisper "small", CPU) and write
  enhanced-LRC with per-character times. `align_folder.py` does a whole folder and backs the originals up to
  `tools/align_out/orig/`.
- Python envs: `tools/demucs-venv` (torch cu121 + demucs), `tools/align-venv` (CPU torch + stable-ts). **Do not modify
  `demucs-venv` while the batch runs.**
- Tests (plain Java, no Android): `karaoke/tests/QueueManagerTest.java`, `LrcTest.java` (21 + 23 checks, all pass).
  Commands are in each file's header.

## Running right now (check these first)

| Job | Log | Status at handoff |
|---|---|---|
| Karaoke batch (`make_karaoke.py --push`) | `tools/make_karaoke.log` | 2:01 am: 400 / 650 songs, 0 failures |
| Video copy (`copy_videos.py`) | `tools/copy_videos.log` (run 1: 87/87 done), `copy_videos2.log` (run 2, 194 videos) | 2:01 am: run 2 at 174 / 194, 0 failures |
| Lyric alignment (`align_folder.py` on `EasonChan\Eason Chan`) | `tools/align_folder.log` | DONE 6 / 6, every line aligned |

Overnight loop (branch `overnight`, report `MORNING_REPORT.md`) updates this table each round.

If a job died (PC restarted etc.): just re-run the same command - all three skip finished work.
Commands: `python -u make_karaoke.py --push`, `python -u copy_videos.py`,
`python -u align_folder.py --dir "D:\ai\GetVideo\EasonChan\Eason Chan"` (run from `tools/scripts`, set
`PYTHONIOENCODING=utf-8`). The box must be on and reachable by ADB for `--push` / `copy_videos.py`.

## Outstanding tasks

1. **Two-phone test** (needs the user): open the QR code on two phones, add songs from both, try shuffle, the
   up/down/top/remove buttons, search, browse. Fix whatever feels wrong. (Step 7 of the plan; the launcher tile is done.)
2. **Finish the batch**, then re-run `copy_videos.py` to cover songs made after its first run (~594 of 658 mp3s have a
   same-name mp4, ~72 GB; USB writes run ~6 MB/s, so several hours).
3. **Aligned lyrics for the 6 Eason Chan songs** - DONE overnight and on the drive (plus K歌之王). A new quality check
   (`tools/scripts/clean_aligned.py`, now run automatically by `align_folder.py`) keeps per-character times only where they
   agree with the lyric file and are not squeezed; other lines fall back to the estimate. Result (exact / estimated lines):
   十面埋伏 25/0, 明年今日 34/0, Lonely Christmas 56/2, Shall We Talk 49/2, 人來人往 43/2, K歌之王 45/1, 人啊人 22/23
   (the aligner went astray in 人啊人 - a bigger Whisper model, e.g. `--model medium`, might do better).
4. **8 lyric files still in `C:\Users\timlo\Downloads`** with no matching mp3 in the library: King Kong, 隨意門,
   孤獨探戈, 幸災樂禍, 2001太空漫遊, 低等動物, 打回原形, 給愛麗斯. User said "leave them for now". When the mp3s exist:
   rename the `.lrc` to match the mp3's file name, put it in the source folder, then run `align_folder.py`.
5. **Test the "Next up" banner** (last 15 s of a song with another queued) and a normal 16:9 video background - only the
   square `K歌之王.mp4` has been tried.
6. **Confirm the remote's keys**: hold OK is the vocal toggle (tested via adb); Menu / V are untested. Ask the user if
   their remote has a Menu button; pick another key if not.
7. ~~**Keep the app alive**~~ DONE overnight: `KeepAliveService` (foreground service + notification) keeps the process
   alive when someone presses Home; the phone page answered while the launcher was in front. `launchMode=singleTask` so
   opening the tile again brings back the running instance instead of starting a second one (port clash).
8. **Clean-up of titles** - override file DONE overnight. File names still carry YouTube clutter and `Library.cleanTitle`
   strips obvious noise only, but any title can now be set by hand in `titles.txt` (UTF-8, `#` comments), either on the
   drive (`/storage/A6D0-F59E/Karaoke/titles.txt`) or on the box (`/sdcard/Karaoke/titles.txt`, wins). Lines:
   `<file name without extension> = Title`, or `Artist/<file name> = Title`, or `Language/Artist/<file name> = Title`.
   The old name stays searchable. Press Rescan on the phone page (or restart the app) after editing.
   Still open: nobody has actually written the overrides - a generated draft of all titles would be the next step.
9. ~~**Search nicety**~~ DONE overnight: search converts traditional to simplified (Android ICU transliterator, API 29+)
   on both sides, so 世纪末烟花 finds 世紀末煙花 and 生活无小事 finds 生活無小事.
10. **Housekeeping** DONE overnight: test files `/sdcard/test.mp3`, `/sdcard/test_lr.mp3` and `D:\ai\Karaoke\Other\Timlok` deleted;
    `Library.fromJson` now restores the video path (the cache stores `videoFile`); `karaoke/PLAN.md` rewritten to match what was built.
    STILL TO DO: the deleted Timlok file was the real karaoke output of `Timlok/Timlok/Neon Nights 1980 Flowing.mp3`, so after
    the main batch finishes run `python -u make_karaoke.py --only Timlok --push` (and `copy_videos.py`) to recreate it.
11. **Security (unchanged, known)**: the box's ADB on :5555 has no authentication, and the launcher's Power tile depends on it.

## Gotchas learned

- ADB from Git Bash: set `MSYS_NO_PATHCONV=1`, and give `adb push/pull` Windows paths (`D:\\...`); `/d/...` paths fail.
- Do not write Java/regex with backslashes through a bash heredoc or `python - <<EOF` replace: backslashes get collapsed.
  Use the Write / Edit tools for source files.
- Starting the app: `adb shell am start -n com.setupbox.karaoke/.MainActivity` (add `--es path /file.mp3` to play one
  file with no queue). Build: `JAVA_HOME=C:\Program Files\Java\jdk-20 ANDROID_HOME=D:\ai\setupbox\tools\sdk
  tools\gradle-8.7\bin\gradle.bat assembleDebug` in `karaoke/`, then `adb install -r`.
- A lone `.mp4` on the drive (no same-name mp3) shows up as a song of its own with the original vocals. That's why
  `copy_videos.py` only copies videos for songs whose karaoke mp3 already exists.
- `*.part` files are ignored by the app; the scripts write to `.part` and rename when complete.
- The user asks for commits explicitly ("commit and push"); don't commit unasked.
