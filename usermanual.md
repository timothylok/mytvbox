# Karaoke for the TV box - user manual

## 1. First start: choose your Karaoke folder (once)

The first time the app starts it shows **"Choose your Karaoke folder"**.

1. Press **OK** on the remote.
   - On a box that has a system folder chooser (for example the Android 10 box) it opens, starting in `Karaoke`. Choose the folder that holds your song folders, for example **Internal storage > Karaoke** (the one that contains `Cantopop`, `English`, ...). Pick `Karaoke` itself, not the whole storage. Confirm with **Allow access to 'Karaoke'**, then **Allow**.
   - On a box **without** a system chooser (the Google TV box, Android 16) the app opens its **own chooser**: first the list of storages (internal, and a USB drive or SD card when one is plugged in, each with its free space), then the folders inside. Use Up / Down / OK; **Back** goes up one level.
   - If the box offers no chooser at all, the app falls back to its **own folder** on the box (the app tells you where it is) and you copy the songs there.
2. A **USB drive or SD card** works on the Google TV box. Plug it in before choosing; the app's folder on it is `Android/data/com.superghosttim.karaokeking/files`. Do **not** unplug or eject it while a song is playing: Android stops the app when its drive disappears (it restarts with your queue, but the song is lost). On boxes with a slow stick or one that disconnects, prefer the box's own storage.
   **Songs that skip or stop early** usually mean the drive is failing: files with the right size but damaged contents. Copy the songs again and compare checksums, or use another drive (an SD card in the box's reader worked well). The PC script `tools\scripts\build_usb_library.py --verify` finds such files.
   **Warning:** songs kept in the app's own folder (including `Android/data/.../files` on a USB drive) are **deleted when the app is uninstalled**, and also when it is reinstalled with a different signing key. Keep your master copy on the PC (or anywhere else), and do not uninstall the app to "fix" a problem unless you can copy the songs back.
3. To change the location later, open the TV **Settings** (Menu while nothing is playing) and use **Song location**.

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

## 3. Choose songs on the TV (no phone needed)

The TV has its own song browser, driven by the remote:

- **Open it:** press **OK** on the idle screen, or press **Back** while a song is playing (the song keeps playing behind the list).
- **Browse:** language > artist > song. Languages come in the order Cantopop, Mandarin, English, JPop, KPop, then others; artists A-Z; songs newest first (songs without a year last). Up / Down move, **OK** opens.
- **Add a song:** **OK** on a song adds it to the **end** of the queue; **hold OK** (about half a second) plays it **next**. Songs already in the queue are marked **[queued]**, and the top line shows how many are queued. If nothing was playing, the first song you add starts at once and the list closes.
- **Back** goes up one level; at the top it closes the list.
- The browser has no search or typing: use the phone page for that. Songs added from the TV have no singer name.

## 4. Phone page

- **Queue**: now playing, up next, move up / down / play next / remove, shuffle, clear, pause / resume, restart, skip, vocal on/off, flip channels.
- **Browse**: language > artist > songs. Each song shows its release year when known (`2018 · Eason Chan · Cantopop`) and the list runs newest to oldest; songs without a year follow. **Rescan** re-reads the folder after you add songs.
- **Search**: finds songs by title or artist; Traditional and Simplified Chinese match each other.
- **History**: songs played in the last 30 days (Today / 7 days / 30 days), with the singer and "skipped at ..." where it applies. **+ Add** queues a song again. Older entries are removed automatically.
- Type your name in the box at the top: it is shown on the TV as the singer.

## 5. Remote control keys

| Key | While a song plays | Nothing playing |
|---|---|---|
| OK | pause / resume | open the song browser |
| hold OK (or Menu) | switch between karaoke and original vocals | TV settings (Menu) |
| Back | open the song browser | - |
| Left | back 10 seconds | - |
| Right | skip to the next song | - |
| Up / Down | lyrics later / earlier by 0.5 s (saved for that song) | - |

Inside the song browser, Up / Down / OK / hold OK / Back work as described in section 3. A key pressed only to wake the sleeping screen does nothing else.

## 6. What you see on the TV

- Top left: **Now** (and **Next** when there is one), top right: the small QR code. Two thin bars at the bottom: the left bar shows the song and the singer, status (Playing / Paused, KARAOKE or VOCAL ON, time, lyric offset); the right bar shows the key hints.
- On the idle screen: "N songs - press OK to choose one here, or scan the code with your phone".
- Music videos are shown centred with a small margin above and below; black bars baked into a video are zoomed away when the video is listed in `crops.txt`.
- Songs without a video show an animated colour background (soft drifting colour blobs, a new random colour scheme for every song; nothing is downloaded). Lyrics come from the `.lrc` file; the character being sung turns orange. When the last line has been sung, the lyrics leave the screen.
- A countdown of three dots (3-2-1) appears in the lyric block, in the place of the line being sung, before the singing starts after a long gap, so it never overlaps the lyrics; and a "Next up" banner in the last 15 seconds of a song (switch it off with `banner=off` in `config.properties`).
- While a song plays, the box quietly reads ahead the next queued song, so changing songs is smooth.
- After 5 minutes with nothing playing the screen goes black with a small moving QR code and clock (sized to fit any screen); any remote key or phone action wakes it.

## 7. The launcher (home screen)

The box starts in the custom launcher (the Google TV launcher can be restored with `tools/scripts/home_launcher.py`). Its panels scale to the screen size. The storage panel shows the **USB drive's or SD card's** free space when one is plugged in, otherwise the box's internal storage (real numbers, not the size printed on the package).

## 8. Your Karaoke folder

```
Karaoke/
  Cantopop/<Artist>/<Song>.mp3   (+ <Song>.lrc lyrics, + <Song>.mp4 music video)
  English/ ...
  titles.txt          optional: nicer song titles   (Artist/<file name> = Title)
  years.txt           optional: release years       (Artist/<file name> = 2009; made by make_years.py from the mp3 date tags)
  static_videos.txt   optional: songs whose video is not shown (a still picture)
  crops.txt           optional: black bars to zoom away (made by detect_crops.py)
  config.properties   optional: sleep_minutes=5  (0 = never sleep), banner=off (no "Next up" banner)
```

The `.mp3` is stereo: **left = instrumental, right = original song** (made with the Demucs tool). The app's own data (queue, history, settings) is kept inside the app, not in this folder.
The settings files are read when the app rescans; use **Rescan** on the phone page after changing them.

## 9. If something looks wrong

- **Phone shows "Scan the QR code on the TV"**: scan the code again (the pairing was reset).
- **The app closed and restarted by itself**: a USB drive was unplugged or ejected while the app was using it. Reopen the app; the queue is kept.
- **The folder chooser does nothing / is missing**: the box has no system chooser; the app's own chooser or own-folder fallback is used (section 1).
- **No songs / empty list**: check that you chose the folder that contains the language folders, then press Rescan.
- **A song is much quieter or louder than the others**: the mp3 needs loudness normalization before it is copied to the box (see `CLAUDE.md`).
- **Lyrics out of time**: press Up / Down on the remote to shift them; it is remembered for that song.
