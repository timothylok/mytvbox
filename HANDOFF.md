# Handoff - TV box karaoke project (as of 2026-11-10, evening NZ)

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
- Sleep mode: after `sleep_minutes` (default 5, file `/sdcard/Karaoke/config.properties`, re-read every 30 s, 0 = never) with no song playing, remote key,
  mouse/touch or phone action, the screen goes black with a dim QR + clock that drift. Any key/mouse/phone action wakes it (the waking key does nothing else).
  A paused song stays paused (position kept). Songs whose video is a still picture (`static_videos.txt`, made by `tools/scripts/find_static_videos.py`) play audio-only.
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

## Running right now

Nothing is running. All batch jobs are finished or stopped: karaoke batch DONE (650/650), Eason Chan alignment DONE, video copy STOPPED
at 244/587 (USB drive faults; the drive has since been repaired but is not on the box). The unattended loop is deleted.

Current box state: runs from INTERNAL storage (`/sdcard/Karaoke`), movies deleted, only the Eason Chan set (65 songs: mp3 + lrc + mp4, 40 real
videos) plus `titles.txt`, `static_videos.txt`, `config.properties`. The USB drive (F: on the PC, serial A6D0-F59E) is plugged into the PC.
Branch state: `main` = `overnight` up to f58fccc; later commits (fc602ca: make_titles.py, align_folder --model, notes; 8590d8a: TV status omits the "Next:" line when nothing is queued) are on `overnight` only.
Known quirk (user chose to leave it, 2026-11-10): "Lyrics MV" videos (e.g. Shall We Talk) are one still title card with only a small burned-in lyric changing, so they look frozen on the TV. `find_static_videos.py` scores them ~2.8 (cutoff 2.0) and does not flag them. Options if it bothers anyone: add the stem to `static_videos.txt`, or raise the cutoff and review the list.

## Outstanding tasks

0. **USB drive repaired 2026-11-10** (`chkdsk F: /f` fixed the exFAT bitmap/directory damage, 0 bad sectors, all 609 mp3s read OK, stale .part files deleted). It is NOT on the box now: the box runs from internal storage with only the Eason Chan set (movies deleted). Next: decide whether to put the drive back, and finish the video copy FROM THE PC with the drive plugged into the PC (`copy_videos.py` pushes via adb - needs rewriting to copy to F:\Karaoke directly), skipping `static_videos.txt` songs.

1. **Two-phone test** (needs the user): open the QR code on two phones, add songs from both, try shuffle, the
   up/down/top/remove buttons, search, browse. Fix whatever feels wrong. (Step 7 of the plan; the launcher tile is done.)
2. ~~**Finish the batch**~~ DONE (650/650); video copy now see item 14. Original note: re-run `copy_videos.py` to cover songs made after its first run (~594 of 658 mp3s have a
   same-name mp4, ~72 GB; USB writes run ~6 MB/s, so several hours).
3. **Aligned lyrics for the 6 Eason Chan songs** - DONE overnight and on the drive (plus K歌之王). A new quality check
   (`tools/scripts/clean_aligned.py`, now run automatically by `align_folder.py`) keeps per-character times only where they
   agree with the lyric file and are not squeezed; other lines fall back to the estimate. Result (exact / estimated lines):
   十面埋伏 25/0, 明年今日 34/0, Lonely Christmas 56/2, Shall We Talk 49/2, 人來人往 43/2, K歌之王 45/1, 人啊人 22/23
   (the aligner went astray in 人啊人 - a bigger Whisper model, e.g. `--model medium`, might do better).
4. **8 lyric files still in `C:\Users\timlo\Downloads`** with no matching mp3 in the library: King Kong, 隨意門,
   孤獨探戈, 幸災樂禍, 2001太空漫遊, 低等動物, 打回原形, 給愛麗斯. User said "leave them for now". When the mp3s exist:
   rename the `.lrc` to match the mp3's file name, put it in the source folder, then run `align_folder.py`.
5. ~~**Test the "Next up" banner**~~ DONE overnight (banner at 3:38 of 3:46, auto-advance to the next song, 16:9 video OK). (last 15 s of a song with another queued) and a normal 16:9 video background - only the
   square `K歌之王.mp4` has been tried.
6. ~~**Confirm the remote's keys**~~ DONE: the user confirmed on the real remote (2026-11-10) that holding OK toggles the
   vocal mode. Menu / V were never needed.
7. ~~**Keep the app alive**~~ DONE overnight: `KeepAliveService` (foreground service + notification) keeps the process
   alive when someone presses Home; the phone page answered while the launcher was in front. `launchMode=singleTask` so
   opening the tile again brings back the running instance instead of starting a second one (port clash).
8. **Clean-up of titles** - override file DONE overnight, and a draft for Eason Chan is on the box (item 15). File names still carry YouTube clutter and `Library.cleanTitle`
   strips obvious noise only, but any title can now be set by hand in `titles.txt` (UTF-8, `#` comments), either on the
   drive (`/storage/A6D0-F59E/Karaoke/titles.txt`) or on the box (`/sdcard/Karaoke/titles.txt`, wins). Lines:
   `<file name without extension> = Title`, or `Artist/<file name> = Title`, or `Language/Artist/<file name> = Title`.
   The old name stays searchable. Press Rescan on the phone page (or restart the app) after editing.
   Still open: nobody has actually written the overrides - a generated draft of all titles would be the next step.
9. ~~**Search nicety**~~ DONE overnight: search converts traditional to simplified (Android ICU transliterator, API 29+)
   on both sides, so 世纪末烟花 finds 世紀末煙花 and 生活无小事 finds 生活無小事.
10. **Housekeeping** DONE overnight: test files `/sdcard/test.mp3`, `/sdcard/test_lr.mp3` and `D:\ai\Karaoke\Other\Timlok` deleted;
    `Library.fromJson` now restores the video path (the cache stores `videoFile`); `karaoke/PLAN.md` rewritten to match what was built.
    The deleted Timlok karaoke file was recreated after the batch (`make_karaoke.py --only Timlok --push`, 5:01 am).
12. ~~**Skip (Right key) is slow**~~ DONE: it was the busy USB drive. On a quiet drive a skip takes ~2.5 s (`tools/scripts/skip_latency.py`).
13. **Lyrics for the other songs**: only 7 of the 65 Eason Chan songs have an .lrc (K歌之王, 十面埋伏, 明年今日, Lonely Christmas, Shall We Talk,
    人來人往 - 人啊人's file was DELETED at the user's request, they will download a new one: put it in `D:\ai\GetVideo\EasonChan\Eason Chan\` named like the mp3,
    then `align_folder.py --dir ...`, copy to `D:\ai\Karaoke\Cantopop\Eason Chan\` and push to the box). `--model medium` was tried on 人啊人: worse (0 exact lines).
    The user's other 8 .lrc files in Downloads still have no matching mp3.
14. **Full library back on the TV**: decide whether to put the USB drive back on the box. If yes, finish the video copy FROM THE PC with the drive plugged into the
    PC (copy_videos.py pushes via adb; it needs a variant that copies straight to F:\Karaoke), skipping `static_videos.txt` songs, then check for I/O errors.
    Until then the box only has the Eason Chan test set on internal storage (the app uses /sdcard/Karaoke when no USB volume has a Karaoke folder).
15. **Titles**: `D:\ai\Karaoke\titles.txt` (made by `tools/scripts/make_titles.py`, Eason Chan only) is on the box. Other artists still show the file-name title.
11. **Security (unchanged, known; user was told, no decision)**: the box's ADB on :5555 has no authentication, and the launcher's Power tile depends on it.

## Gotchas learned

- `adb` is not on PATH: use `D:\ai\setupbox\tools\sdk\platform-tools\adb.exe`. In Git Bash, `JAVA_HOME` must be a Windows path (`C:\Program Files\Java\jdk-20`); the build is easiest from PowerShell.
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
