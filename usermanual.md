# Karaoke for the TV box - user manual

## 1. First start: choose your Karaoke folder (once)

The first time the app starts it shows **"Choose your Karaoke folder"**.

1. Press **OK** on the remote. The system folder chooser opens (it starts in `Karaoke`).
2. Choose the folder that holds your song folders, for example **Internal storage > Karaoke** (the one that contains `Cantopop`, `English`, ...). Pick `Karaoke` itself, not the whole storage.
3. Confirm with **Allow access to 'Karaoke'**, then **Allow**.

**Do NOT choose a USB drive or SD card.** They are slow to read and can disconnect in the middle of a song. Copy your songs to the box's own storage first. If you pick one by mistake, the app tells you and asks again.

### Does it ask again?
No. You choose the folder once; the app remembers it across restarts, reboots, Wi-Fi changes and app updates.
It asks again only if Android loses the permission:

- the app was uninstalled and reinstalled, or its data was cleared ("Clear data");
- you removed the app's access to the folder yourself, or Android removed it because the app was unused for a very long time.

If the folder is only moved or deleted, the app does not show the chooser: it shows "No songs found in the chosen folder" and waits. It asks again only when the permission itself is gone ("The Karaoke folder is not available any more").

When the app data is cleared or the app is reinstalled, the phone pairing (section 2) is also reset: every phone has to scan the QR code once more.

## 2. Connect a phone (scan once)

The TV shows a QR code. Scan it with the phone's **camera** (no app needed). The phone opens the song page and is paired: it will not ask again, even after the TV restarts.
Anyone on your Wi-Fi who has not scanned the code sees only "Scan the QR code on the TV" and cannot see or control anything.
(After an update that adds pairing, or after the app's data is cleared, scan the code once more.)

### Reset phone pairing (TV settings)
If a phone should no longer have access (lost, borrowed), reset the pairing on the TV:
1. While **nothing is playing**, press **Menu** (or hold **OK**). The **Settings** screen opens.
2. Press **OK** on **Reset phone pairing**; the button changes to **Press OK again to reset**. Press **OK** once more.
3. The TV makes a new QR code. Every phone that scanned the old code stops working until it scans the new one. (**Back** or **Close** leaves without changing anything.)

## 3. Phone page

- **Queue**: now playing, up next, move up / down / play next / remove, shuffle, clear, pause / resume, restart, skip, vocal on/off, flip channels.
- **Browse**: language > artist > songs. Each song shows its release year when known (`2018 · Eason Chan · Cantopop`) and the list runs newest to oldest; songs without a year follow. **Rescan** re-reads the folder after you add songs.
- **Search**: finds songs by title or artist; Traditional and Simplified Chinese match each other.
- **History**: songs played in the last 30 days (Today / 7 days / 30 days), with the singer and "skipped at ..." where it applies. **+ Add** queues a song again. Older entries are removed automatically.
- Type your name in the box at the top: it is shown on the TV as the singer.

## 4. Remote control keys

| Key | Action |
|---|---|
| OK | pause / resume |
| hold OK (or Menu) | switch between karaoke and original vocals |
| Left | back 10 seconds |
| Right | skip to the next song |
| Up / Down | lyrics later / earlier (saved for that song) |
| Menu, or hold OK, while nothing is playing | TV settings (see below) |

## 5. What you see on the TV

- Top left: **Now** (and **Next** when there is one), top right: the small QR code, bottom left: status (Playing / Paused, KARAOKE or VOCAL ON, time, lyric offset).
- Music videos are shown centred with a small margin above and below; black bars baked into a video are zoomed away when the video is listed in `crops.txt`.
- Songs without a video show an animated colour background (soft drifting colour blobs, a new random colour scheme for every song; nothing is downloaded). Lyrics come from the `.lrc` file; the character being sung turns orange.
- A countdown of three dots (3-2-1) appears in the lyric block, in the place of the line being sung, before the singing starts after a long gap, so it never overlaps the lyrics; and a "Now singing" / "Next up" banner at the start and end of a song.
- After 5 minutes with nothing playing the screen goes black with a small moving QR code and clock; any remote key or phone action wakes it.

## 6. Your Karaoke folder

```
Karaoke/
  Cantopop/<Artist>/<Song>.mp3   (+ <Song>.lrc lyrics, + <Song>.mp4 music video)
  English/ ...
  titles.txt          optional: nicer song titles   (Artist/<file name> = Title)
  years.txt           optional: release years       (Artist/<file name> = 2009; made by make_years.py from the mp3 date tags)
  static_videos.txt   optional: songs whose video is not shown (a still picture)
  crops.txt           optional: black bars to zoom away (made by detect_crops.py)
  config.properties   optional: sleep_minutes=5  (0 = never sleep)
```

The `.mp3` is stereo: **left = instrumental, right = original song** (made with the Demucs tool). The app's own data (queue, history, settings) is kept inside the app, not in this folder.
The settings files are read when the app rescans; use **Rescan** on the phone page after changing them.

## 7. If something looks wrong

- **Phone shows "Scan the QR code on the TV"**: scan the code again (the pairing was reset).
- **No songs / empty list**: check that you chose the folder that contains the language folders, then press Rescan.
- **A song is much quieter or louder than the others**: the mp3 needs loudness normalization before it is copied to the box (see `CLAUDE.md`).
- **Lyrics out of time**: press Up / Down on the remote to shift them; it is remembered for that song.
