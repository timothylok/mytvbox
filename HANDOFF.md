# Handoff - TV box karaoke project (as of 2026-11-10, end of session)

Repo: https://github.com/timothylok/mytvbox (single branch `main`; everything is committed and pushed. The old
`overnight` branch was merged and deleted on 2026-11-10).
Box: Android TV box 192.168.3.79 (open ADB on :5555). PC: Windows, GTX 1650 4 GB. See also the memory notes
`project_setupbox_overview`, `project_jellyfin_music_library_layout`, `project_karaoke_app`.

## What exists

**Launcher** (`launcher/`, package `com.setupbox.launcher`) - the box's home screen. Karaoke is pinned third in
`/sdcard/Launcher/config.json` (backup of the old config: `tools/config.backup.json`).

**Karaoke app** (`karaoke/`, app id `com.superghosttim.karaokeking` (source package `com.setupbox.karaoke`), Java + Media3 ExoPlayer + NanoHTTPD + ZXing):
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

## START HERE - state at the end of the 2026-11-10 session

Everything is committed and pushed (`main`, last app/doc commits: 96979a3 and the handoff commit after it). Nothing is running; the box is idle at volume 7.

**Box (192.168.3.79, Android 10, internal storage only, no USB drive):** runs the DEBUG build of the current code (target SDK 35; the release build was verified earlier).
54 songs, all with word-timed lyrics, all loudness-normalized, 32 with a real video, `titles.txt` / `years.txt` / `crops.txt` / `static_videos.txt` in `/sdcard/Karaoke`.
The folder (Internal storage > Karaoke) was chosen once through the folder picker. Phones pair by scanning the QR code (the token changes whenever the app data is cleared:
every device-test run does that).

**Done this session (details in the sections below):** folder picker (SAF) + first-run screen with USB warning; phone-page pairing + TV settings (Menu / hold OK while idle: reset pairing);
play history (30 days); video band fit + black-bar crops; countdown dots inside the lyric block; loudness normalization (+ safeguards, `make_karaoke.py` does it for new songs);
release years (`years.txt`, `make_years.py`, shown and sorted newest-first on the phone Browse page; 381 of 651 songs have one); titles for all artists; generated random-colour
backgrounds (Bing removed); target SDK 35 + R8 release build verified; cleartext off; `release-tests/` (JVM + device, run `python karaoke\release-tests\run_tests.py --ui`); `usermanual.md`.

**Next steps, in rough order**
1. Lyrics for the 11 songs removed from the box (一絲不掛, 社交恐懼癌, 空城記, 讓我留在你身邊 have none; 任我行, 單車, 幸福摩天輪, 是但求其愛, 每一個明天, 無條件, 致明日的舞 have a `.lrc`
   that does not fit the audio: alignment kept 0 lines). Need matching `.lrc` files from the user -> `align_folder.py` -> normalize -> push (rule 2 in CLAUDE.md).
2. The other ~586 songs (all artists) have mp3s normalized and titles/years but NO processed lyrics and no videos checked: they are not on the box. Do the pipeline per CLAUDE.md rule 2
   when the user supplies lyrics. 270 songs still have no year (user is tagging the source mp3s; re-run `make_years.py`, push `years.txt`).
3. Optional polish: remove the "lyrics" label from the Queue / Now-playing cards too (removed from Browse/Search only); `JPop/YAO/YAO - For Okinawa` karaoke mp3 is silent (regenerate). (The lyric-file read is now off the main thread, 2026-11-18.)
4. Google Play: see the Play sections below (open: Android 13+ notification dialog, USB-drive picker test, second device, upload signing key, final package name, privacy policy / Data safety).

**Rules that matter** (full text in `CLAUDE.md`): test before commit/push; only copy songs to the box that have processed lyrics AND are loudness-normalized; commit/push only when asked;
never test on the box while someone is singing (the guests use it from their phones; check `/api/state` with the pairing cookie, see the pairing section; a failed check must NOT count as idle).

## Session 2026-11-14 to 11-16 (after the START HERE state above)

**Songs added to the box** (mp3 + aligned lrc + mp4, normalized; pushed to `/sdcard/Karaoke/<Language>/<Artist>/`, plus `crops/titles/years/static_videos.txt`; the app needs a Rescan; not yet checked on the TV):
- `Mandarin/JJ Lin`: 我對緣分小心翼翼 (21 lines exact / 32 estimated), 小酒窩 (all 34 lines estimated: alignment matched none). Crops added for both videos.
- `Cantopop/Gareth.T`: 緊急聯絡人 (39/5), 淺粉紅 (27/3), 用背脊唱情歌 (26/10). No bars, no static videos. No years yet in `years.txt`.
- Box now has ~59 songs.

**Not pushed: BlackGuy 跳舞街** (`D:\ai\GetVideo\BlackGuy`): the `.lrc` is for a 3:37 version, the mp4 is 2:15, so the alignment crammed 15 lines at the end (small and medium Whisper agree). Needs an `.lrc` that matches the 2:15 cut. The karaoke mp3 is made (`D:\ai\Karaoke\Cantopop\BlackGuy`, its `.lrc` there is the bad aligned one; original in `tools/align_out/orig/`). Uncommitted: `make_karaoke.py` now maps `BlackGuy` -> Cantopop (it expects `GetVideo/<lib>/<artist>/*.mp3`, so the files sit in `GetVideo/BlackGuy/BlackGuy/`; the source folder had only mp4 + lrc, the mp3 was extracted with ffmpeg).
Lesson: check that the `.lrc` length fits the mp3 and that the first aligned line is near the original's time; `clean_aligned.py` does not catch a wrong-version lyric file.

**YouTube on the box:** official YouTube TV shows "YouTube is not supported on this device" (uncertified 32-bit Allwinner box, Play services 15.1; the factory 2.07.02 shows the same). Fixed by installing **SmartTube 32.71 stable armeabi-v7a** (`org.smarttube.stable`, from github.com/yuliskov/SmartTube releases), removing YouTube TV for user 0 (`pm uninstall --user 0`; restore with `pm install-existing com.google.android.youtube.tv`), and pointing the launcher tile in `/sdcard/Launcher/config.json` at SmartTube. Backups: `tools/apk_backup/` (YouTube 5.30.340), `tools/config.pre_smarttube.json`, APK in `tools/apk_dl/`. SmartTube works without sign-in (home feed verified by screenshot; no video played yet).

**Git:** committed and pushed 2026-11-16 as 8de7290 (daily_sync job, app rename, make_karaoke BlackGuy mapping + map-row skip). BlackGuy is now HELD in the daily job (see "Daily sync job").

**Gotchas:** in Git Bash with `MSYS_NO_PATHCONV=1`, give adb.exe `D:/...` paths, not `/d/...`; `align_folder.py` takes ~4 min per song (use the background option); folder names like `Gareth.T` have no trailing dot.

## Daily sync job (2026-11-16; retargeted to the Google TV's USB drive 2026-11-18)

`tools/scripts/daily_sync.py`, Task Scheduler task **Karaoke Daily Sync** (03:00, hidden through `daily_sync_hidden.vbs`, 8 h limit, no overlap).
Pipeline per song: make_karaoke (Demucs + normalize) -> align (Whisper language by library: zh/ja/ko/en; originals kept in `tools/daily_sync/orig`) -> quarantine check
-> push lrc, mp3, mp4 (`.part` + rename + size check) -> crops/titles/years/static txt -> POST `/api/rescan`. Only processed-lyric songs are pushed; the push waits for an idle TV.
Logs/state: `logs/daily_sync.log`, `logs/last_run.json`, `logs/daily_sync_state.json` (held songs, failure counters; `--list-held / --hold / --release`), every step in `processed.csv`
(steps push, lrc, video, meta, quarantine, rescan, sync). `--dry-run` shows the plan. Quarantine = lyrics longer than the song or covering < 40 %; held songs release when the `.lrc` changes.
Held by hand: BlackGuy + 7 Eason Chan songs. `make_karaoke.py` now skips sources with a ledger `map` row (Eason Chan would otherwise be re-made under clean names).
The ~235 plain-lrc songs (incl. 88 K/J-pop) align over several nights; K/J-pop alignment quality is untested. First nightly run will also push ~31 Eason Chan videos (~1.5 GB).
The task runs **whether or not the user is logged on** (S4U, no stored password): registered/updated by an ADMIN run of `tools/scripts/register_daily_sync_task.ps1` (it probes that the background session sees ffmpeg, python and adb first).
Not yet done / untested: the first unattended 03:00 run (check `logs/last_run.json`, `logs/daily_sync.log` and the last `sync` rows of `processed.csv` in the morning), Rescan after a push (the app was not running during the test push), ko/ja/en alignment quality.
Held songs so far: BlackGuy + 7 Eason Chan (by hand) + 7 caught by the length check (Theresa Fu, Daniel Chan, Mayday 你不是真正的快樂, Kvi Baba, YAO ありふれた世界の果てに, G-DRAGON CROOKED, YESUNG 墨紙): need matching `.lrc` files from the user (they release themselves when the file changes).
The app was renamed to **K歌之王** (label, notification channel, idle title) 2026-11-16 and installed on the box (debug build). Installing + launching restored the saved queue and started a song: check `/api/state` after installs.

**Target changed 2026-11-18 (commit 2e70a02): the job now pushes to the GOOGLE TV's USB DRIVE, no longer to the Android 10 box.** (The pipeline text above still applies; only the box side changed. Everything the text says about `192.168.3.79` / `/sdcard/Karaoke` is now the `--target old` setting.)
- `--target google` (default) = Google_TV box. Songs go to `/storage/A6D0-F59E/Android/data/com.superghosttim.karaokeking/files/Karaoke` (the app's own folder on the USB volume; `A6D0-F59E` is the drive's volume serial). `--target old` = the Android 10 box (`192.168.3.79:5555`, `/sdcard/Karaoke`); it needs the new app installed first (see the TODO below).
- The box's wireless-debugging PORT changes after a reboot, so every run looks the box up by its mDNS name (`adb mdns services`, name `adb-1e5517831b69e12c`) and falls back to the last good address in `logs/box_address.json`, then to `192.168.3.97:2025`. The addresses and the drive's volume serial are in the `TARGETS` table at the top of `daily_sync.py` (change them there if the box or the drive is replaced).
- The drive must be plugged in: if `/storage/A6D0-F59E` is missing the push is skipped (`box` = `usb drive not mounted` in `logs/last_run.json`, an ATTENTION line, exit 0, retried the next night); it never falls back to the box's own storage (only ~3.8 GB). Free space is checked on the drive (`df` of the volume). Wireless debugging must stay ON for the 03:00 run to connect; if the box is off or the setting was switched off the run skips the push (`box unreachable`).
- The app must have that folder selected (TV Settings > Song location > USB drive > `Karaoke/` > Use this folder, done on 2026-11-18). After a push the job compares `/api/state` `songFolder` with the target folder and reports an ATTENTION line if they differ (the pushed songs would not show). Idle gate, `.part` + rename + size check, quarantine, ledger rows and the rescan work as before.
- Dry run on 2026-11-18 (`python tools\scripts\daily_sync.py --dry-run`, exit 0): found the box, saw the drive, plan = 46 songs to push (the library on the drive has 144), plus the usual make/align work. NOT yet tested: a live push to the USB drive through the job (try `daily_sync.py --only <text of one song>` first) and the first unattended 03:00 run on the new target (check `logs/last_run.json`, `logs/daily_sync.log` and the last `sync` rows of `processed.csv` in the morning). The Task Scheduler entry did not need changing (it runs `daily_sync_hidden.vbs`, default target = google).
- `tools/scripts/build_usb_library.py` builds / extends the same USB folder by hand (it applies the same rules: processed lyrics, normalized mp3, real videos, held songs skipped).

## Session 2026-11-17 (TV layout, lyrics, crops) - latest state

**Commits pushed:** edaa959 ... 0beacd5, 5d062be (banner switch, detect_crops), 6dab49e (ledger rows of 7 realigned lyrics).
**App (K歌之王, installed on the box):**
- Lyrics sit in the lower half, two lines only, a sung line vanishes right after it is sung. Text inside ( ) / （ ） (singer turns like (合)) is shown in grey but never aligned or highlighted
  (`Lrc.java` turnMask, `align_lyrics.py` strip_turns/turn_markers; test `karaoke/tests/LrcTest.java`).
- The old top "Next" bar is gone. The bottom-right label alternates "Now:" (8 s) / "Next:" (4 s); the QR code is always on top of the video. Video is centered using `crops.txt`.
- `/sdcard/Karaoke/config.properties` (re-read every 30 s): `sleep_minutes`, `now_seconds`, `next_seconds`, `banner=off` (hides the "Now singing"/"Next up" banners; currently OFF on the box). Local copy `D:/ai/Karaoke\config.properties`.
- Phone page: Rescan is an icon chip, 40 px buttons, aligned with the language chips.
**Lyrics tools:** `clean_aligned.py` accepts a consistent 5-15 s lyric offset (needs 60 % of lines to agree). 男人最痛 fixed that way.
25-song re-alignment (medium model) finished: 7 improved and were pushed (Rude Boy, 獅虎流, 花邊細胞, 一場誤會, 魔笛, 美麗之最, 戒糖失敗); the other 18 stay estimated (most match 0 lines: backing vocals or lyric text differs from the singing). Not watched on the TV yet.
**Video crops:** `detect_crops.py` now trims static logos/text inside black bars (motion bounds, only shrinks, ignored when the moving area is < 70 % of the box). Dry run over 116 videos: 98 identical, 12 changes applied to `D:/ai/Karaoke\crops.txt` and pushed; kept the old crop for 單車 and 人來人往 (dark pictures); 幸福摩天輪 and 每一個明天 fixed by hand (not on the box yet). `crops.txt` and `config.properties` live outside git (box + D:/ai/Karaoke).
**Nightly run 2026-11-18:** started 03:00 and was still aligning at 06:45 (184 songs to align, ~8 per batch, ~5.5 h cap); no errors; the fixed `meta_refresh` had not run yet. Check `logs/last_run.json`, `logs/daily_sync.log`, last `sync` rows of `processed.csv` for the final result, push count and whether metadata refresh works.
**Still open:** ~180 songs to align (a few more nights), held songs (BlackGuy, 7 Eason Chan, 14 length-check holds) need matching `.lrc`; static videos IVE 04. CRUMBS / 06. Glow Motion not on the box; check the banner-off look and new crops on the TV over time.
**Gotchas:** don't install the app while a song is paused/playing without asking (it restarts the song); `check_centering.py`-style checks must tolerate dark vignette videos (noisy motion bounds).

## Session 2026-11-18 - latest state

- **Nightly run 2026-11-18** (03:00-09:03, 364 min): made 6 karaoke mp3s, aligned 64, pushed 64 songs (7.4 GB), 0 failed, 0 held; box rescan 123 -> 187 songs. ~120 songs still to align (a few more nights).
  11 songs got no exactly-timed line (MIRROR BRO!, Marf, Mike, Panther Chan, REGENT, Serrini x2, Tse Nga Yi, Cheng Huan, DIOR, ...): estimated lyrics only, check on the TV.
  `meta_refresh` (the `TypeError` of 2026-11-17) showed no new error, but no success line either: confirm on the next run that pushes songs. Not yet watched on the TV: the 64 new songs.
- **BlackGuy removed**: `GetVideo/BlackGuy` and `Karaoke/Cantopop/BlackGuy` deleted (not recoverable; needs a new source + a lyric file that fits the 2:15 cut if wanted again), mentions removed from `daily_sync.py` / `make_karaoke.py`, hold entry released. 15 other holds remain (7 Eason Chan, 8 caught by the length check): `daily_sync.py --list-held`.
- **App**: the lyric file is read and parsed on a background thread (`loadLyricsAsync` in `MainActivity.java`; playback starts first, lyrics appear a few ms later; a bad file is logged and skipped). Tested on the box: build, install while idle, lyrics visible on the TV, no crash/ANR in logcat, JVM release tests pass. Not tested: damaged `.lrc`, very fast skips.
- **Commits pushed**: 50aaa30 (script cleanup), d78b447 + 9b49811 (ledger rows), 1326b6d (background lyrics).
- Box was switched off by the user at the end of the session; the nightly push waits for it to be on (the backlog is pushed on the next night it is connected).
- **Polish still open**: "lyrics" label on the Queue / Now-playing cards; silent `JPop/YAO/YAO - For Okinawa` mp3 (regenerate with Demucs).

## SD card is now the library carrier (2026-11-18, late)

- **The WDC 2.5" USB drive (`A6D0-F59E`) is failing and retired.** It kept the right file SIZE but wrong bytes (ExoPlayer `ParserException: Invalid NAL length`, Windows disk events 153/7, SMART read errors), so songs skipped before finishing. Size-only checks were not enough: verify with MD5.
- **Library now on a 128 GB FAT32 SD card, volume `85F8-1509`** (label "NIKON D5600"), in the box's reader, same app folder path `/storage/85F8-1509/Android/data/com.superghosttim.karaokeking/files/Karaoke`. Filled on the PC (152 songs, 397 files copied, 0 failed, MD5-checked each), then hashed ON the TV: 427/427 files match the PC (`verify_on_tv.py`-style `md5sum` over adb). App lists 152 songs; 3 songs not in the earlier test played past 30 s; the earlier 10-song full-play run played every song to the end, 0 player errors. FAT32 4 GB file limit: biggest file is 652 MB.
- **`daily_sync.py` now targets the SD card** (`TARGETS["google"]["usb_uuid"] = "85F8-1509"`, commit 9110577). It already md5-checks each file after pushing. Not yet seen: the first unattended 03:00 push to the card (large backlog of make/align/push songs); check `logs/last_run.json`, `logs/daily_sync.log` and the `sync` rows of `processed.csv`. The job needs `KARAOKE_TOKEN` if the box ever runs a release build (`run-as` only works on debug).
- **`build_usb_library.py`** (commit 2a740c6): MD5 check after each copy, `--limit N`, `--include "a,b"`, `--verify` (hash existing files, delete bad ones so a rerun recopies; with `--dry-run` only reports). Use `--dest "<drive>:\Android\data\com.superghosttim.karaokeking\files\Karaoke" --all`. Copy on the PC for a full fill; the nightly job adds new songs over the network.
- **Launcher**: the Camera tile is hidden via `/sdcard/Launcher/config.json` `"hidden": ["com.android.camera2"]` (backup `tools\config.pre_hide_camera.json`).
- **Never uninstall the app while the library is in its app folder** (it deletes the songs; this wiped 144 songs on 2026-11-18). Debug builds are signed with the upload key, so `adb install -r` works both ways.
- Still open: Android 10 box not reinstalled/checked (rule 5); back up `D:\ai\keys\` off the PC; Play listing items (privacy-policy placeholders and hosting, banner 1280x720, TV screenshots, first-run help, licences screen); scratch files in `tools\` that can go (`realign_one*`, `realign_work`, `usb_rebuild.log`, `usb_verify.log`, `full_play.log`, `sd_fill.log`, `sd_verify.log`).

## Session 2026-11-19 - latest state

- **First unattended 03:00 run on the SD card worked** (03:00-09:18, 379 min, exit 0): made 4, aligned 61, pushed 99 songs (11.2 GB), 0 failed, 0 held; rescan 152 -> **248 songs**; 62 songs left to align (time limit), about one or two more nights. Card has ~94.6 GB free.
- **24 songs got no exactly-timed line** (estimated lyrics only): DIOR, Eric Chou, LuHan, Ma Shifu, Mayday x3, Meng Ran, Patrick Brasca, Ren Ran, Silence Wang x2, Tian Yuan, Tr33, WOLF(S), Wang Heye, Xiao A Qi, Yao Xiaotang, Yu Dongran, Zhang Yuan x3, Mrs. GREEN APPLE, Vaundy. All 24 played 50 s on the TV with 0 player errors and the lyric display/highlight looks right in screenshots; **timing not judged by ear: the user should listen** (nudge with the `Lyrics +/-` keys). The other new songs were pushed and md5-checked but not played.
- **Test gotcha:** `adb exec-out screencap` captures whatever is in front; bring the app up first (`am start -n com.superghosttim.karaokeking/com.setupbox.karaoke.MainActivity`) or you get the launcher/screensaver.
- **Android 10 box check is parked by the user until further notice** (rule 5 still applies: say it was not checked).
- Open: listen to the 24 songs; back up `D:\ai\keys\`; Play listing items; delete scratch files in `tools\`; `processed.csv` is rewritten by every nightly run (commit it when convenient).

## Google TV box (added 2026-11-18)

Second box `Google_TV`: Android 16, armeabi-v7a, adb over wireless debugging (`adb mdns services`; port changes, was 192.168.3.97:2025). The Android 10 box is 192.168.3.79:5555.
- **Storage is NOT 128 GB** (Android/launcher report 128 GB): `df` shows the data partition is ~3.7 GB with ~2 GB free. Do not push the library here; only 2 test songs (心血, 男人最痛 + video) are on it.
- **No folder chooser** (no DocumentsUI): the app detects this at start-up (manifest `<queries>`, `chooserAvailable()`) and uses its own folder `/sdcard/Android/data/com.superghosttim.karaokeking/files/Karaoke` (`useAppFolder()`; no permission needed, fill it with `adb push`). Test: `python karaokeelease-testspp_folder_device_test.py --device <ip:port>` (non-destructive, 13 checks pass). `folder_picker_ui_test.py` cannot run on this box.
- **In-app song-location chooser (2026-11-18):** on a device with no system chooser, TV Settings (Menu key / hold OK while idle) has a **Song location** button -> `FolderChooserActivity` (D-pad list: storage = internal / each USB drive or SD card, then folders; "Use this folder", "+ New folder Karaoke"; Back goes up / cancels). It only offers the app's own folders (`getExternalFilesDirs`), so it needs no permission and works on Android 10 and 16 and stays Play-compatible; browsing arbitrary public folders would need all-files access (restricted by Play), so it is NOT offered. The choice is kept in prefs `appfolder_path` and re-validated with `AppFolders.isAllowed` (JVM test `AppFoldersTest`, 20 checks). `/api/state` has `songFolder` in this mode. Device test steps 12-16 drive it with the remote keys. Not tested: USB drive / SD card volumes (none available), the Android 10 box.
- **Lyric rendering on Android 16:** `AbsoluteSizeSpan` px + `setText(sb)` ignored size and colour spans (no highlight, 14sp text). Fixed: base size on the view from the screen height (`heightPixels * 0.07`), `RelativeSizeSpan(0.68)` for dim lines, `BufferType.SPANNABLE`. Bottom status bars are 18sp with one fixed height (36 dp) so left and right line up.
- **Launcher is the home app on this box:** the HOME key starts Google's `launcherx` by explicit component, so `tools/scripts/home_launcher.py ours|google --device <ip:port>` disables/enables it (reversible, nothing uninstalled) and sets the home role. The launcher needs `pm grant` of READ/WRITE_EXTERNAL_STORAGE and `/sdcard/Launcher/config.json` (pinned: Karaoke, YouTube, Netflix, Settings). Layout now scales from the real screen (designed for 1280x720 dp, `uiScale()`), so panels no longer overflow; weather shows after the first fetch (~15 s on a fresh install).
- Not tested: the new fonts / launcher scaling on the Android 10 box; Android 13+ notification dialog; USB drive picker.
- **Real storage:** the chip is ~7.3 GB, user data ~3.8 GB (2.4 GB free); the framework's 128 GB is a hard-coded fake. The launcher storage bar now reads `df -k /data` (background thread, StatFs fallback, logs `storage: kernel says ... framework says ...`) and shows `2.4 GB free of 3.8 GB`.
- **File managers do not help:** Material Files 1.7.5 (installed, then removed), the built-in `com.droidlogic.FileBrower` and JMGO `File Management` (`com.jmgo.browser`, Play Store, removed) handle at most `GET_CONTENT`/`OPEN_DOCUMENT`/`VIEW`; none provides `OPEN_DOCUMENT_TREE`, so the in-app chooser above is the answer on boxes like this.
- **App id renamed to `com.superghosttim.karaokeking`** (badfd5a): source package/namespace unchanged, so adb needs `com.superghosttim.karaokeking/com.setupbox.karaoke.MainActivity`. New app on every box: pairing, queue, history, folder grant reset. Google TV done; the Android 10 box still runs the old app, see the TODO below. The id is permanent once uploaded to Play.
- **CLAUDE.md rule 5 (new): all code must work on Android 10 AND Android 16**; check on both boxes or say which one could not be checked. Everything from today on the TV side (lyric sizes/highlight, bottom bars, launcher scaling, storage bar, chooser) is verified on the Google TV only.
- **Commits today:** 1326b6d background lyrics, 984e62a Google TV support, ae0bdca rule 5, e09ec2c in-app chooser, badfd5a rename, a5ba518 launcher storage bar (+ ledger / HANDOFF commits). Weather on a fresh install stays empty ~15 s until the first fetch finishes (cached afterwards).
- **USB drive on the Google TV (tested 2026-11-18):** WD 500 GB USB disk (exFAT, label `timlok-bak`, serial A6D0-F59E) on the box's USB 3 port (5000 Mbit/s). The old `F:\Karaoke` tree (609 mp3, only 7 lyric files, un-normalized audio) was DELETED; the library is now built by `tools/scripts/build_usb_library.py` into `<drive>:\Android\data\com.superghosttim.karaokeking\files\Karaoke`: 144 songs (144 mp3 normalized + 144 character-timed lrc + 115 real videos, 13.71 GB) + titles/years/crops/static txt, every file logged in `processed.csv` (re-run it with `--all` to add more processed songs; `--dry-run` first). `tools/scripts/drive_unique_check.py` lists files that exist nowhere else on the PC before an old tree is deleted (it found one, a renamed 心血 video, copied to `D:\ai\drive_rescue`). The app only reads its OWN folder on a USB volume (no permission needed): in TV Settings > Song location choose `USB drive / SD card` > `Karaoke/` > `[ Use this folder ]` (done on the box; library of 144 songs loaded in ~9 s). Results: full read of the library through the box 13.7 GB in 138 s = 99 MB/s, 0 new errors; playback of a video song and an audio-only song OK. The kernel logged ONE transient read error early on (`I/O error, dev sda, sector 58740192`, `Unaligned partial completion resid=4`: a USB-bridge short transfer, not a bad sector; the PC chkdsk found 0 bad sectors and every mp3 reads clean); it did not repeat. To eject on the PC the disk is `Disk 2`, reported as Fixed, so use `Set-Disk -Number 2 -IsOffline $true` from an admin PowerShell (the Eject verb does nothing).
- **Next-song preparation (`Prefetcher.java`, kept by decision):** while a song plays (after 8 s) the next queued song's mp3 and the head/tail of its video are read ahead and its lyrics are parsed, so the start finds them cached (checked every 2 s in the tick; unit test `PrefetcherTest`, 16 checks; log line `prefetch hit/miss`). Measured on the box it did NOT shorten song starts (with warm-up 2.8 / 2.4 / 1.9 s vs cold 2.4 / 2.4 / 1.9 s): the ~2 s left is player start-up, and the earlier slow starts (4.5-6.8 s) were first access after the disk was idle. It may help slower USB sticks / SD cards. A restart lists the 144 songs after ~10.8 s (includes the app's cold start).
- **"Now singing" banner removed** (the bottom bars show the song and the singer); the "Next up" banner (last 15 s) stays and `banner=off` still hides it. Verified on the TV at 0:04 of a song.
- **Apps on the Google TV cleaned (2026-11-18):** AirScreen: the factory 2.8.1 (no installer) showed "You may be a victim of software counterfeiting" (it checks its installer, the signing certificate is the same as the Play build); it was uninstalled (APK in `tools/apk_backup/google_tv_airscreen/`) and the official 2.18.0 installed from the Play Store (its welcome screen asks consent to collect installed-app data: NOT accepted yet; it listens on port 7000 = AirPlay, open to the LAN while it runs). Removed: Xuper (`com.android.mgstv`, an IPTV app hiding under the `com.android.*` name) and Netflix 9.23 (the Play Store says it is incompatible with this device: only the saved APK can bring it back), both with APKs in `tools/apk_backup/google_tv_removed/`; system apps removed for user 0 only (restore with `pm install-existing --user 0 <pkg>`): `com.cghs.stresstest`, `com.ftest`, `com.xhsx.agingcheckservice`, `com.example.urlindexshop` (a fake "Netflix"), `com.andoridxh.store`, `com.liajt.appstore` (list + commands in `tools/apk_backup/google_tv_removed/RESTORE.txt`). YouTube TV was updated by the Play Store (5.02 -> 5.30). STILL OPEN at that time (see the "Later on 2026-11-18" bullet for what changed afterwards): Aptoide TV (alt app store with install rights), the vendor OTA updater (`com.liajt.otaapk`), BThelp, TvCustomizer, SetupCustomization, Dynamic UI, WifiDisplay, Media Center; old no-installer Chrome 89 and Amazon Video; unreferenced Material Files / JMGO File Management were tried and removed (no folder chooser in any of them).
- **Later on 2026-11-18 (Google TV):** Aptoide TV 5.1.2 and Chrome 89 REMOVED (APKs in `tools/apk_backup/google_tv_removed/`; the Play Store calls Chrome incompatible with this box, so only the saved APK can bring it back; the user installed TV Bro 3.0.5 from the Play Store as the browser). Vendor system apps DISABLED for user 0 (`pm disable-user`, restore with `pm enable --user 0 <pkg>`, list in `RESTORE.txt`): OTA Update `com.liajt.otaapk`, Media Center `com.rockchips.mediacenter` + `com.rockchip.mediacenter`, WifiDisplay `com.rockchip.wfd`, CleanUp `com.charon.rocketfly`, Dynamic UI `com.xhsx.launcherstart`, TvCustomizer `com.sv.tvcustomizer`, SetupCustomization `com.yui.setupcustomization`; kept enabled because they may serve the remote / Bluetooth: BThelp `com.swe.myapplication`, "Apps" `com.liajt.setkey` (and the harmless `com.droidlogic.FileBrower`). After disabling: home screen, the Karaoke app and the network were fine; the remote's keys and Bluetooth were NOT tested at that time. UPDATE: the user did the remote check afterwards (told me it is done, 2026-11-18; no problem was reported to me, details not recorded); if a key ever stops working, re-enable BThelp/setkey-type apps. Remaining user-installed apps: Karaoke, launcher, YouTube TV, AirScreen, TV Bro, Amazon Video (old 6.23.14, no Play installer).
- **Security picture (checked 2026-11-18):** the Google TV image is a custom `userdebug` build (`ro.debuggable=1`), security patch 2025-01-01, system WebView 113 (2023, used by TV Bro); listening ports 2025 (wireless debugging, key-paired, needed by the nightly job), 6466/6467 (Google remote), 7000 (AirScreen / AirPlay, open to the LAN; its welcome screen asks consent to collect installed-app data: NOT accepted) and 8080 (our app, pairing cookie, plain HTTP on the LAN). The OLD Android 10 box has ADB open on :5555 with NO authentication (anyone on the LAN can install apps / run commands); it has not been scanned for apps. Options not done yet: disable or password-protect AirScreen, close the old box's :5555, a guest Wi-Fi for the boxes.
- **Launcher storage panel** now shows the USB drive when one is mounted ("USB 430.7 GB free of 444 GB") and the internal storage otherwise ("2.6 GB free of 3.8 GB"); `StorageInfo.java` parses `df -k` (kernel numbers; `/storage/<id>` and Android-10 `/mnt/media_rw/<id>` mounts, `/storage/emulated` never counts); unit test `launcher/tests/StorageInfoTest.java` (11 checks, command in its header). Verified on the Google TV including a software unmount (`sm unmount public:8,5`) for the fallback. FINDING: unmounting the USB volume (and so very likely pulling it) KILLS the Karaoke app (Android stops apps with files open on that volume); the app restarts with its saved queue but must be reopened. Never unmount while someone sings.
- **On-TV song browser (commit fe762e9; `BrowseModel.java` + overlay code in `MainActivity.java`):** the box can now be used without a phone. OK on the idle screen, or Back while a song plays, opens a full-screen list language > artist > song (languages in the order Cantopop, Mandarin, English, JPop, KPop then others; artists A-Z ignoring case; songs newest year first, unknown year last). OK drills down, OK on a song adds it to the END of the queue, holding OK (about 600 ms) plays it NEXT; Back goes up a level, then closes. Songs already queued show a "[queued]" prefix and the header shows the queue size. Adding the first song while idle closes the browser and starts playback; otherwise playback carries on behind the overlay. A key press that only wakes the screensaver does not also act (`wokeKey`). `/api/state` has a new `"browse"` flag. TV-added songs have an empty singer name. Not done: search / typing (D-pad typing is painful, the phone page still has it), very long artist lists scroll by holding Down. Tests: `release-tests/BrowseModelTest.java` (14 checks, in `run_tests.py`) and `release-tests/tv_browser_device_test.py --device <addr>` (14 checks with real remote keys via `adb input keyevent`, box must be idle; passes on the Google TV, leaves the box idle). NOT yet checked on the Android 10 box (rule 5).
- **Screensaver (sleep) clock** no longer wraps: the QR is 15 % of the screen height, the clock text is shrunk until "88:88" fits under it, and the drift positions follow the real screen size (it was hard-coded for 1920x1080 px). The phone page no longer shows the "lyrics" badge on the Now-playing card / Queue rows, and the song-location chooser shows GiB like the launcher.
- **App icon (commit adac185):** the user's ghost-with-microphone mascot (source `C:\Users\timlo\Downloads\Designer.png`, 1254 px, not in git) as `drawable-{mdpi..xxxhdpi}/ic_launcher.png` (48-192 px, transparent rounded corners cut 6 px inside the artwork because the plain 0 px cut left a light anti-alias ring on dark backgrounds), an Android TV banner (`drawable-xhdpi/banner.png`, 640x360) and Play assets in `karaoke/store/` (`store_icon_512.png`, `tv_banner_640x360.png`). Made with ffmpeg only (no Pillow): the recipe is a `crop` of the 1218 px plate + a `geq` alpha mask, sizes with lanczos; if the artwork changes, redo that, then check the edge on black and white backgrounds.
- **API 36 + release signing (commit eaa3806):** the app now builds with `compileSdk 36` / `targetSdk 36` (minSdk 21): AGP 8.9.1, Gradle 8.11.1 (`tools\gradle-8.11.1\bin\gradle.bat`; 8.7 is kept but too old for AGP 8.9), SDK platform 36 + build-tools 36.0.0 installed. Build all: `assembleDebug assembleRelease bundleRelease` in `karaoke/` (release APK 1.0 MB, `.aab` 1.8 MB, R8 on). Upload key: `D:\ai\keys\karaokeking-upload.jks` (RSA 4096, 10000 days, CN=Karaoke King) with its passwords in the git-ignored `karaoke/keystore.properties` (copy: `D:\ai\keys\keystore.properties.backup`); **back `D:\ai\keys\` up off this PC** (use Play App Signing so a lost upload key can be reset). `build.gradle` signs release with it when the file exists (unsigned otherwise) and signs DEBUG with the same key, so debug and release can be installed over each other with `adb install -r` (checked: identical certificate SHA-256 `77dacf87...5cf3`). Manifest has `enableOnBackInvokedCallback="false"`: at target 36 predictive back is on by default and (per the Android 16 notes, not re-read from the official page) stops delivering KEYCODE_BACK; the browser / chooser use the Back key event. Verified on the Google TV (Android 16): release build runs, 152 songs, web server and QR fine under R8, no crash or R8 class errors, `tv_browser_device_test.py` 14/14 on the debug build at target 36. Not checked on the Android 10 box.
- **INCIDENT 2026-11-18 (Claude, disclosed): uninstalling the app wiped the song library on the Google TV USB drive.** The library lives in `/storage/A6D0-F59E/Android/data/com.superghosttim.karaokeking/files/Karaoke` (drive label `timlok-bak`, exFAT, serial A6D0-F59E) and Android deletes that folder on uninstall. I uninstalled the (debug-signed) app to test the release-signed one (a different key cannot be installed over it) and lost 144 songs (~13 GB), the queue and the phone pairing. The PC master library (`D:\AI\Karaoke`) was untouched. Fixed: drive put into the PC and `python tools\scripts\build_usb_library.py --dest "F:\Android\data\com.superghosttim.karaokeking\files\Karaoke" --all` (152 songs, 14.2 GB, 427 files, no failures; the 23 corrected lyric files copied separately because same-size files are skipped), then the drive was moved back and **Song location** set to it with the in-app chooser. Rules now: never `adb uninstall` on a box whose library is in the app folder (memory note `feedback_never_uninstall_app_with_usb_library.md`); same key for debug and release (above) so `install -r` is always possible; the user manual and the privacy policy warn that uninstalling deletes songs kept in the app folder.
- **Release build vs the nightly job:** a release build is not debuggable, so `daily_sync.py` cannot read the pairing token with `run-as` (it then skips the idle check / push and asks for the `KARAOKE_TOKEN` environment variable). The box currently runs the DEBUG build signed with the upload key (token readable, nightly job works). For a store-like release on the box, set `KARAOKE_TOKEN` (the code in the phone-page URL / QR) for the job. The first live unattended 03:00 push to the USB target is still untested.
- **Lyric line times (commit 5fc8cd0):** the player picks the current line from the line's own `[mm:ss]`, but the aligner keeps the source file's line times and only times the words. 23 library songs had every line 3-15 s EARLY (lyrics timed for another cut of the video; e.g. 無名氏 +8.5 s, Kimi +14.8 s, Anson Lo Rude Boy +13.5 s, Tiger Yau +12 s), so the highlight jumped to the next line while the previous one was still sung. Fix in two places with the same rule: when the MEDIAN gap between a line's time and its first word is over 3 s, each timed line starts 1 s before its first word (never before the previous line's end), untimed lines after the first timed line move by the median gap, titles before the first sung line stay, and times stay in file order. App: `Lrc.alignStartsToWords` (covers files already on a box). Pipeline: `clean_aligned.shift_line_starts`, called at the end of `clean_text` (so `align_folder.py` and the 03:00 job give correct lines for new songs; re-running changes nothing). The 23 files in `D:\AI\Karaoke` and their `D:\ai\GetVideo` copies were rewritten (word tags untouched), originals in `tools\align_out\prev_line_starts\<same path>.<timestamp>`; the threshold is 3 s because 1-3 s early is normal and harmless (9 more files at 1.5-3 s were left as they were). Tests: 10 new checks in `karaoke/tests/LrcTest.java`, `karaoke/release-tests/shift_line_starts_test.py` (14 checks, same fixture and numbers), both in `run_tests.py`. Checked on the TV: 無名氏 highlight follows the singing at 0:29 and 0:43. A re-alignment of 無名氏 with Whisper `medium` (the job's own method: SOURCE mp3 + the original plain lyrics from `tools\daily_sync\orig`) gave the same word times as the current file (33 vs 36 exact lines), so it was NOT applied. Scratch folders that can be deleted: `tools\realign_one`, `tools\realign_one_orig`, `tools\realign_work`, `tools\realign_one.log`, `tools\usb_rebuild.log`.
- **Google Play paperwork (drafts, commit 18b8209):** `karaoke/store/privacy-policy.md` (fill in developer name, contact email and the public https URL, then host it) and `karaoke/store/data-safety-answers.md` (suggested answers: no data collected/shared, no ads; app-access note for reviewers; foreground-service justification; judgement call on whether the phone page's optional singer name counts as collected). Facts they rest on: permissions INTERNET (local phone page), FOREGROUND_SERVICE(+MEDIA_PLAYBACK), POST_NOTIFICATIONS; no analytics/ads/crash SDK; no outgoing network address in code or phone page. Personal Play accounts need a closed test with 12 testers for 14 days before production (organisation accounts do not; confirm in Play Console). Still to do for Play: TV banner 1280x720 and on-TV screenshots, first-run help screen, licences screen (ExoPlayer, NanoHTTPD, ZXing), listing text, content/copyright wording (the app ships no songs).
- **Project hook + rule 6 (commit bde328a):** `.claude/settings.json` + `.claude/hooks/manual_reminder.py` remind (once per session) to update `usermanual.md` when app / launcher / phone-page sources are edited; CLAUDE.md rule 6 says the same. `usermanual.md` was rewritten for the browser, the own chooser / USB, the key table, bars, launcher and troubleshooting.
- **Gotchas found today:** `python -I` ignores `PYTHONUTF8` / `PYTHONIOENCODING`, so scripts that print Chinese file names crash on the Windows console: use `python -X utf8 -I ...`. Do not write JSON with backslash paths through a bash heredoc (use the Write tool). The daily job aligns the SOURCE mp3 in `D:\ai\GetVideo` with the source `.lrc` and keeps the plain original in `tools\daily_sync\orig`; do not re-align from the karaoke stereo mp3.
- **STILL OPEN after today:** Android 10 box not reinstalled / not checked with any of this session's changes (rule 5; the steps are in the TODO below); live test of the 03:00 push; AirScreen consent / open port; old box :5555; silent "For Okinawa" source; Play release items above; back up `D:\ai\keys\`.
- Memory notes: `reference_tv_box_addresses.md` (both boxes' IPs and adb details), `project_google_tv_android16_gotchas.md`, `project_karaoke_session_state_2026-11-18.md`.
- Polish item 3 (lyric file read off the main thread) is DONE; still open: "lyrics" label on the Queue / Now-playing cards, silent `JPop/YAO/YAO - For Okinawa` mp3.
- Also today: lyric file is read and parsed off the main thread (1326b6d); BlackGuy removed (folders, script mentions, hold); nightly run 03:00-09:03 pushed 64 songs, 187 songs on the Android 10 box, `meta_refresh` fix still unconfirmed.

### TODO: move the Android 10 box (192.168.3.79:5555) to the renamed app (app id `com.superghosttim.karaokeking`)

The app id changed on 2026-11-18, so the old box still runs the OLD app (`com.setupbox.karaoke`) and the nightly `daily_sync.py` cannot read its pairing token (`PKG` already points to the new id) until this is done. Do it when nobody is singing (check `/api/state` first):
1. Power the box on; `adb connect 192.168.3.79:5555`. Back up its app data first if you want to keep history / queue (`adb shell run-as com.setupbox.karaoke ls files`; a debug build allows `run-as`).
2. Build: `JAVA_HOME=C:\Program Files\Java\jdk-20`, `ANDROID_HOME=D:\ai\setupbox\tools\sdk`, `tools\gradle-8.7\bin\gradle.bat assembleDebug` in `karaoke/`.
3. `adb uninstall com.setupbox.karaoke` (two copies would fight over port 8080), then `adb install -r karaoke\app\build\outputs\apk\debug\app-debug.apk`.
4. Repoint the launcher tile: in `/sdcard/Launcher/config.json` change `"com.setupbox.karaoke"` to `"com.superghosttim.karaokeking"` (`adb pull`, edit, `adb push`), then restart the launcher (`am force-stop com.setupbox.launcher`) so the tile reloads.
5. Start it: `adb shell am start -n com.superghosttim.karaokeking/com.setupbox.karaoke.MainActivity`. It is a NEW app: the first-run screen asks for the folder: press OK, choose Internal storage > Karaoke (the existing songs stay where they are; pick `Karaoke` itself, not the storage root). History, queue, flips and lyric offsets start empty.
6. Phones must scan the new QR code once (new pairing token).
7. Verify: `/api/state` with the new token shows the song count (~187), play one song with lyrics on the TV, then `python karaoke\release-tests\run_tests.py --ui` (clears the app's data; the box must be idle).
8. Check the nightly job picks it up: `python tools\scripts\daily_sync.py --dry-run` should find the box and the token. Then re-check on this box: the lyric size / highlight, the bottom bars and the launcher scaling (all verified only on the Google TV so far, rule 5 in CLAUDE.md), and note that the launcher's storage bar now reads `df` (should match the old numbers there).

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

## Loudness normalization (2026-11-10, user request: a song was too quiet)

`tools/scripts/normalize_karaoke.py` measures the LEFT (instrumental) channel with ebur128 and re-encodes files that are more than 1.5 dB from -18 LUFS
(same gain both channels, peak limiter); originals go to `D:\ai\Karaoke_pre_normalize` first; logged in `processed.csv`. Eason Chan set done: 32 of 65 changed
(median -18.0, range -19.4..-16.6 after; before: median -18.7, range -29.7..-14.9). MUST run before copying songs to the box (CLAUDE.md rule 2);
`make_karaoke.py` now normalizes every new file right after Demucs (`--target`, `--tolerance`). The whole library (651 songs) was run through `normalize_karaoke.py` on 2026-11-10 (log `tools/normalize_all.log`, originals in `D:\ai\Karaoke_pre_normalize`).
For the Play release a per-song gain in the app (VocalProcessor) would be the cleaner fix.

## Release years (2026-11-10)

`years.txt` in the Karaoke folder (`Artist/<file name> = 2009`, same keys as titles.txt, parsed by `Years.java`) -> `year` in `/api/library`; the phone page
shows it in Browse/Search rows and sorts a Browse artist list newest first (songs without a year after the dated ones). `tools/scripts/make_years.py` builds it from
the ID3 `date` tag of the SOURCE mp3s in D:\ai\GetVideo (the Demucs karaoke mp3s carry no tags; matched by file name, Eason Chan via titles.txt + 3 manual pairs).
Status: 381 of 651 songs have a year (270 source mp3s have no date tag yet - tag them, then re-run `make_years.py`; lines already in years.txt are kept, push the file to the box root).
Release tests: `YearsTest` (JVM) + a device check that a pushed years.txt reaches `/api/library` (the real years.txt is preserved).

## Countdown dots (2026-11-10)

The 3-2-1 dots used to be a separate overlay at a fixed height and overlapped the lyrics. They are now drawn INSIDE the lyric block, in the big "current line" slot
(previous line dim, dots, next three lines dim), so they cannot overlap (`showLyrics`, `updateCountdown`).

## Normalizer safeguards (2026-11-10)

`normalize_karaoke.py` skips files whose instrumental channel is below -45 LUFS (nearly empty: boosting only adds noise and overdrives the original-vocal channel), caps the
boost at +20 dB and limits peaks at 0.89. Two songs had been over-boosted before this was added (Beyond - Myth, IU - Dear my crazy soulmate): restored from
`D:\ai\Karaoke_pre_normalize`. Known bad source: `JPop/YAO/YAO - For Okinawa` is digital silence in both channels (regenerate with Demucs). Not on the box.
Note: each device test run does `pm clear`, which makes a NEW pairing token: phones must rescan afterwards.

## Folder picker refactor (done 2026-11-10, merged to `main` as 8b4e329; the `saf-picker` branch is deleted)

All song-folder access now goes through `Storage` (`SafStorage` = the folder the user picks once; `FileStorage` = tests/debug).
- First run (or lost permission): the TV shows "Choose your Karaoke folder" with a warning NOT to pick a USB drive / SD card;
  OK opens the system chooser (starts in /sdcard/Karaoke); a non-`primary:` folder is rejected with a message. The grant is
  persisted (`takePersistableUriPermission`); the choice is in SharedPreferences `karaoke`/`tree`. The remote's OK key works on that
  screen whatever has focus (regression: it did nothing in touch mode). The phone page has NO folder feature (user's decision);
  only `adb shell am start -n com.superghosttim.karaokeking/com.setupbox.karaoke.MainActivity --ez pickfolder true` re-asks (maintenance/test hook).
- The user's folder is read-only for the app: songs, `.lrc`, `titles.txt`, `static_videos.txt`, `crops.txt`, `config.properties` are read
  from it. The app's own files (queue `state.txt`, `history.jsonl`, `flips.txt`, `lyric_offsets.properties` (now keyed by song id),
  `index.json` cache) are in private storage (`getFilesDir()`); migrated once from /sdcard/Karaoke on first start. Photos folder:
  app external files `Screensaver`.
- Library: `Library.scan(Storage, extraTitles, extraStatic)`; songs carry storage handles (`audioH`, `videoH`, `lrcH`), ids are unchanged
  (md5 of the relative path), so queue/history/flips survive. Cache `index.json` without handles is ignored (rescan).
- Release tests: `karaoke/release-tests/` (`run_tests.py`, `--ui` drives the box: 13 checks incl. persistence and touch-mode OK key).
  The box's `uiautomator` does not work ("could not get idle state"), so the test reads the app's `/api/state` (`setup`, `setupText`)
  and taps the system chooser by screen ratio (calibrated 1920x1080, Android 10 DocumentsUI).
- Still to do: remove `requestLegacyExternalStorage` + READ/WRITE_EXTERNAL_STORAGE from the manifest AFTER the target-SDK bump (they are
  kept now only for the one-time migration); lyric file is read on the main thread (small, but move it to the player thread/background);
  the USB-rejection branch is only unit-tested (needs a USB drive for a device test); stale old files in /sdcard/Karaoke
  (`index.json`, `state.txt`, `history.jsonl`, `flips.txt`, `lyric_offsets.properties`) can be deleted; test on a second/newer Android device.

## Play prep, step 1 (done 2026-11-10, merged to main; branch deleted)

Done on the branch: AGP 8.6.1, compileSdk/targetSdk 35 (SDK platform 35 + build-tools 35 installed in tools/sdk), minSdk stays 21;
removed `requestLegacyExternalStorage` and READ/WRITE_EXTERNAL_STORAGE (the one-time migration just finds nothing now);
added FOREGROUND_SERVICE_MEDIA_PLAYBACK + POST_NOTIFICATIONS (runtime request on Android 13+), the service passes the media-playback type;
release build has R8 (`minifyEnabled`, `shrinkResources`, `proguard-rules.pro`). Both `assembleDebug` and `assembleRelease` compile.
Tested on the box (Android 10): debug build, target 35 -> release-tests 19 JVM + 13 device checks pass.
The signed RELEASE build (R8, debug keystore, zipalign+apksigner from build-tools 35) was installed on the box: library, playback, lyrics, video, history, phone API,
foreground service and all 13 device checks pass (run the UI test with --no-preserve: a release build has no run-as). Still to do: POST_NOTIFICATIONS dialog is untested (needs an Android 13+ device/emulator);
USB drive in the picker (rejection + speed) needs the drive plugged into the box; second/newer Android device; cleartext HTTP is now OFF in the manifest (the app's only outgoing request is the https Bing photo download; the phone page is a SERVER, so the setting does not affect it). Do NOT test while someone is singing: check `/api/state` `playing` first.

## Future: publish the Karaoke app on Google Play (user's goal, 2026-11-10)

The app is built for one box today. Before it is "production grade" for Google Play, plan and work through this list
(check current Play policy at the time; requirements change every year). Today: `targetSdk 29`, `minSdk 21`,
`compileSdk 34`, `requestLegacyExternalStorage`, cleartext HTTP, APK debug builds, package `com.superghosttim.karaokeking` (renamed 2026-11-18).

**Blockers (Play will reject the app or it will not work on other devices)**
1. **Target SDK**: Play requires a recent target API (API 35+ by late 2026). Raising it from 29 breaks today's file access
   (scoped storage), needs foreground-service types/permissions (`FOREGROUND_SERVICE_MEDIA_PLAYBACK`), the
   notification permission (API 33+), and re-testing everything on Android 10 *and* a current Android TV / Google TV device.
2. **Storage access**: `/sdcard/Karaoke` and raw USB paths will not be readable. Switch to the Storage Access Framework
   (user picks the Karaoke folder / USB drive once, keep the persisted URI permission) or to MediaStore + the app's own folder.
   Do not use `MANAGE_EXTERNAL_STORAGE` (Play restricts it). Settings/queue/history/flips move to app-private storage.
3. **Content and copyright**: the app must ship with NO songs, videos or lyrics and must not download from YouTube or
   similar. It plays the user's own files. The listing, screenshots and demo content must use material we have rights to.
   The PC pipeline (Demucs, Whisper alignment, video copy) stays a separate desktop tool, or becomes an optional
   companion app; decide how users get L/R karaoke files (docs for the desktop tool, or on-device separation - too heavy for most TV boxes).
4. ~~**Photo backdrop**: Bing daily images~~ DONE 2026-11-10: the Bing download is removed; songs without a video get a generated animated colour background (`Backdrop.java`, soft drifting blobs, a NEW RANDOM palette per song, no network, nothing bundled). Pictures the user puts in the app's `Screensaver` folder still show as a slideshow over it (optional; moving that folder into the picked Karaoke folder, e.g. `Backdrop/`, is a possible later step).
5. **Release build**: Android App Bundle (`.aab`), release signing with Play App Signing (keep the upload key safe and out of git),
   R8/minify with keep rules for NanoHTTPD/ZXing/Media3, `versionCode` bumped on every upload, a permanent package name
   (pick a domain we own before the first upload - it can never change).

**Required for the listing**
6. **Android TV quality**: banner (320x180) and TV icon, `LEANBACK_LAUNCHER` (done), full D-pad navigation, no touch-only
   actions, overscan-safe layout, works without the phone page (on-TV song picker), meets the TV app quality checklist.
7. **Phone web page security**: today it is open to anyone on the LAN. Add a pairing PIN / token in the QR code, rate-limit,
   validate every parameter, keep it LAN-only, no cleartext outside the LAN (network security config limited to local addresses).
8. **Policies and forms**: privacy policy URL (the app collects no personal data: say so; play history stays on the device),
   Data safety form, content rating questionnaire, target audience, ads declaration (none), open-source licences screen
   (Media3 Apache-2.0, ZXing Apache-2.0, NanoHTTPD BSD).
9. **Testing track**: a new personal developer account must run a closed test (currently 12+ testers for 14 days) before
   production. Plan a group of testers with Android TV / Google TV devices.

**Production quality**
10. Crash and ANR reporting (Play Console vitals; optional Crashlytics - then update the privacy policy and Data safety).
11. Settings screen on the TV (sleep minutes, video margins, lyric size, language), not only `config.properties`.
12. Localisation: English + Traditional/Simplified Chinese strings; no hard-coded text.
13. Automated tests: keep logic Android-free (queue, lrc, history, crops) with JUnit; add instrumented UI tests for the TV flow;
    CI build of the release bundle.
14. Performance budget on low-end boxes (32-bit ARM, 2 GB): cold start, memory, frame drops with 1080p video + lyrics.
15. Accessibility: readable font sizes, contrast, content descriptions for the TV screen and the phone page.
16. The **launcher** (`launcher/`) is a HOME replacement for this box only - not part of the Play release.

**Does a Play-ready build (target API 35+) still run on this box (Android 10 / API 29)? Yes.** `targetSdk` only selects
compatibility behaviour for newer Android versions; it does not stop installing on older ones (`minSdk` stays 21). The box
stays the Android 10 test device. Things to handle for both: (a) storage moves to the folder picker (SAF), which works on
Android 10 and on current devices incl. USB drives; legacy `/sdcard` access cannot be relied on with a high target;
(b) notification / `FOREGROUND_SERVICE_MEDIA_PLAYBACK` permissions are ignored on Android 10 - declare them, version-check before asking;
(c) `compileSdk`/AGP upgrade is toolchain only; (d) guard any API newer than API 29.

**Size of the change (target API + storage):** 1) raise compile/target SDK, plugin, service permissions: small (1-2 h);
2) storage via the folder picker: medium-large, the bulk (about a dozen path uses in `Library.java` / `MainActivity.java`:
scan, lrc, video lookup, history, queue, config, flips, crops, titles, index cache); 3) test on the box: small; 4) test on a
newer Android TV/emulator: small but needs a device. Main risk is SPEED: listing a USB drive through SAF (DocumentFile) is
far slower than java.io.File - 650 songs could take minutes, so use direct `ContentResolver`/`DocumentsContract` child-document
queries plus the existing `index.json` cache from the start. Keep the app's own small files (queue, history, settings, crops)
in app-private storage; only songs/lyrics/videos use the picker. Put file access behind one small interface so the box keeps
working while the picker version is built. Estimate: one session to write, one to test/fix on the box.

**Decision (2026-11-10): the box has no USB drive and runs from internal storage `/sdcard/Karaoke`.** When the folder picker is
added the user picks the SAME folder (Internal storage -> Karaoke; pick `Karaoke` itself, not the storage root - newer Android
blocks the root). Files stay where they are. On first launch migrate the app's own files that live there today (queue
`state.txt`, `history.jsonl`, `flips.txt`, `lyric_offsets.properties`, `config.properties`, `crops.txt`, `index.json`) to
app-private storage so history and settings carry over. Keep the old `/sdcard/Karaoke` path working on this box as a fallback
while the picker is built. Test the USB-drive case later with the repaired drive (slow SAF listing), and check early that the
box's system file picker exists and works with the remote.

Suggested order: 5 (package name) -> 2 + 1 (storage + target SDK, the big refactor) -> 3/4 (content) -> 7 -> 6 -> 8 -> 9 -> 10-15.

## Phone-page pairing (done 2026-11-10)

The QR code on the TV opens `http://ip:8080/?k=TOKEN`; the server answers 303 + a cookie `karaoke_k` (HttpOnly, SameSite=Lax, 1 year) and from then on that phone
works with no typing. Without the cookie every page/API call gets 401 ("scan the QR code on the TV"). The token (12 chars, `Pairing.java`) is made once and kept in the app's
SharedPreferences (`pair`), so phones stay paired across restarts and updates. A phone that has not paired yet (e.g. after this update) must scan the code once.
The idle screen no longer prints the address (typing it would not work). Scripts/curl need the cookie:
`T=$(adb shell run-as com.superghosttim.karaokeking cat shared_prefs/karaoke.xml | grep -oE 'pair">[a-z0-9]+' | cut -d'>' -f2)` then `curl -b karaoke_k=$T http://192.168.3.79:8080/api/state`
(debug build only; the release-tests do this, `--token` for a release build). TV settings: Menu key (or hold OK) while nothing plays opens a Settings screen; "Reset phone pairing" (two OK presses) makes a new token + QR code, all phones must rescan (done 2026-11-10, release-tested).

## Gotchas learned

- `adb` is not on PATH: use `D:\ai\setupbox\tools\sdk\platform-tools\adb.exe`. In Git Bash, `JAVA_HOME` must be a Windows path (`C:\Program Files\Java\jdk-20`); the build is easiest from PowerShell.
- ADB from Git Bash: set `MSYS_NO_PATHCONV=1`, and give `adb push/pull` Windows paths (`D:\\...`); `/d/...` paths fail.
- Do not write Java/regex with backslashes through a bash heredoc or `python - <<EOF` replace: backslashes get collapsed.
  Use the Write / Edit tools for source files.
- Starting the app: `adb shell am start -n com.superghosttim.karaokeking/com.setupbox.karaoke.MainActivity` (add `--es path /file.mp3` to play one
  file with no queue). Build: `JAVA_HOME=C:\Program Files\Java\jdk-20 ANDROID_HOME=D:\ai\setupbox\tools\sdk
  tools\gradle-8.7\bin\gradle.bat assembleDebug` in `karaoke/`, then `adb install -r`.
- A lone `.mp4` on the drive (no same-name mp3) shows up as a song of its own with the original vocals. That's why
  `copy_videos.py` only copies videos for songs whose karaoke mp3 already exists.
- `*.part` files are ignored by the app; the scripts write to `.part` and rename when complete.
- The user asks for commits explicitly ("commit and push"); don't commit unasked.
