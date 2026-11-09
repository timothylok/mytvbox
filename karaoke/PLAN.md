# Karaoke app plan (TV box 192.168.3.79)

## Decisions
- Songs: KTV MP4s copied to the WD USB drive: `/Karaoke/<Language>/<Artist>/<Title>.mp4`
- Vocals: original vocal on one stereo channel, backing on the other. Default side + per-song flip (remembered per file).
- Remote: phone browser via QR code, page served by the box. No PIN; everyone can edit everything.
- v1: queue (add end/next, delete, shuffle, move up/down/top), vocal on/off, pause/skip/restart, search + browse, singer name per song.
- Form: separate app `com.setupbox.karaoke` with a tile in the launcher; serves the web page while open.

## Architecture
Phone browser -> HTTP + 1s polling -> Karaoke app (port 8080): web server (NanoHTTPD) + queue manager (saved to /sdcard/Karaoke/state.json) + library scanner (indexes /Karaoke) + ExoPlayer (Media3) with a custom audio processor (backing channel copied to both speakers).

## API
GET /api/library?q=&lang=&artist= | GET /api/state | POST /api/queue/{add,remove,move,shuffle} | POST /api/player/{pause,resume,skip,restart,vocal,flip}
Each queued item has its own id; every change bumps a version number.

## TV screen
Full-screen video, bottom strip (Now / Next / vocal badge), idle screen with QR + URL. Remote keys: OK=pause, right=skip, down=vocal toggle.

## Steps
1. Scaffold project (Media3 + NanoHTTPD), builds offline  <- in progress
2. Player + vocal channel processor; test on a real KTV file
3. Library scanner + index cache
4. Queue manager + saved state
5. Web server, API, phone page
6. TV screen + idle QR screen
7. Install, add launcher tile, test with two phones

## Risks
H.265/VP9 may stutter on the Allwinner chip (check with ffprobe); phones must be on the same network; open ADB on the box; mic mixing not covered; USB drive may be unmounted (show a message).

## Open items
Sample KTV files (format + default vocal side); USB mount path (check with `df`); optional volume slider.
