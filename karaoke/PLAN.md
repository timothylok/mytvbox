# Karaoke app (TV box 192.168.3.79) - design and status

Status 2026-11-10: steps 1-6 built and tested on the box; step 7 (two-phone test) waits for a human. See `../HANDOFF.md`
for running jobs and outstanding tasks.

## Decisions
- Songs are the user's own mp3s turned into karaoke with Demucs (not downloaded KTV videos). Each karaoke mp3 is stereo:
  LEFT = instrumental (vocals removed), RIGHT = original. Layout on the USB drive (mounts at `/storage/A6D0-F59E`):
  `/Karaoke/<Language>/<Artist>/<Title>.mp3` + optional `<Title>.lrc` (lyrics) + optional `<Title>.mp4` (music video).
- Vocal on/off switches the channel; "flip channels" swaps which side is the karaoke side for one song (saved in
  `/sdcard/Karaoke/flips.txt`), for files made elsewhere with the opposite convention.
- Control from a phone browser via QR code (page served by the box on :8080). No PIN; everyone can edit everything.
- Features: queue (add end/next, remove, move up/down/top, shuffle, clear), vocal on/off, pause/resume/skip/restart,
  search + browse by language/artist, singer name per song, rescan button.
- Separate app `com.setupbox.karaoke` with a pinned tile in the launcher; a foreground service keeps it alive when the user
  presses Home.

## Architecture
Phone browser -> HTTP, 1 s polling -> Karaoke app (port 8080):
- `Server` (NanoHTTPD): phone page (`assets/index.html`) + JSON API; every POST returns the fresh state.
- `QueueManager`: now playing + ordered upcoming list, ids per entry, version counter, saved to `/sdcard/Karaoke/state.txt`.
- `Library`: scans the Karaoke folder, pairs same-name mp3 + mp4, cleans titles, `titles.txt` overrides, search that ignores
  case/accents and treats traditional and simplified Chinese alike, cache in `/sdcard/Karaoke/index.json`.
- `MainActivity`: ExoPlayer (Media3) with `VocalProcessor` (copies the chosen channel to both speakers); a music video is merged
  in and its own soundtrack is never played (the mp3 track is forced); lyrics, countdown, banners, QR/idle screen.
- `Lrc`: plain LRC and enhanced LRC (per-character times); `Backdrop`: gradient + Bing/local photos for songs without video.

## API
GET `/api/state`, `/api/browse`, `/api/library?q=&lang=&artist=&limit=`
POST `/api/queue/{add?song=&singer=&position=end|next, remove?id=, move?id=&dir=up|down|top|bottom, shuffle, clear}`
POST `/api/player/{pause,resume,skip,restart,vocal,flip}`, POST `/api/rescan`

## TV screen
Idle: QR code + address + song count. Playing: music video (fitted to its true shape, dimmed) or photos behind the lyrics, current
character highlighted, 3-2-1 dots before singing starts, "Now singing" (first 6 s) / "Next up" (last 15 s) banner, bottom strip
(Now / Next / state / lyrics shift), small QR top-right. Remote: OK = pause, hold OK or Menu = vocal on/off, Left = back 10 s,
Right = skip, Up/Down = lyrics later/earlier (saved per song).

## Steps
1. Scaffold project (Media3 + NanoHTTPD + ZXing) - done
2. Player + vocal channel processor - done
3. Library scanner + cache - done
4. Queue manager + saved state (plain-Java tests in `tests/`) - done
5. Web server, API, phone page - done
6. TV screen + idle QR screen - done
7. Install, launcher tile, two-phone test - tile done; two-phone test pending (needs people with phones)
Extras built on top: lyrics (LRC) + timing nudge, per-character highlight, forced alignment from the vocals
(`tools/scripts/align_*.py`), music-video / photo backgrounds, countdown + banners, title overrides, foreground service.

## Risks / notes
- USB 2.0 on the box: copying files to the drive while playing a music video makes the video stall (disk contention).
- H.265/VP9 videos may stutter on the Allwinner chip (all current videos are H.264).
- Phones must be on the same network; the box's ADB on :5555 is open without authentication (known).
- Microphone mixing is not covered (mics go through the amp/soundbar).
- A lone `.mp4` on the drive (no same-name mp3) is listed as a song with its own audio.
