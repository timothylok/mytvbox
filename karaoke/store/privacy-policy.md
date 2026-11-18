# Privacy Policy - K歌之王 (Karaoke King)

*Draft. Fill in the three [bracketed] items before publishing, host this page at a public https address and put that address in Play Console.*

**Effective date:** [date]
**Developer:** [your name or business name]
**Contact:** [a contact email you are happy to publish]

## The short version

K歌之王 does not collect, upload or share any personal information. It has no accounts, no advertising, no analytics and no tracking. Everything the app remembers stays on your own TV or TV box.

## What the app does

K歌之王 plays karaoke songs (audio, lyrics and music videos) from a folder **you** provide on your TV box or on a USB drive plugged into it. Singers choose songs from the TV screen with the remote, or from a phone on the same Wi-Fi by scanning a QR code shown on the TV.

## Information the app stores (on your device only)

The app keeps these items in its private storage on your TV box. They are never sent to the developer or to any other company:

| Item | Why | Where it stays |
|---|---|---|
| The play queue | so the queue survives a restart | the TV box |
| Play history (last 30 days: song, time, and the singer name if someone typed one) | the History list on the phone page | the TV box, older entries are deleted automatically |
| Lyric timing adjustments and "flip channel" choices per song | so a song you adjusted stays adjusted | the TV box |
| The chosen song folder | so you are not asked again | the TV box |
| A random pairing code | so only phones that scanned your QR code can control the TV | the TV box and the phones that scanned it |

The "singer name" is whatever a person chooses to type on the phone page. It is optional and is shown on the TV screen only.

You can erase all of this at any time: Android Settings > Apps > K歌之王 > Storage > Clear data, or uninstall the app. **Uninstalling also deletes song files that you stored in the app's own folder.**

## The phone page and your network

To let a phone choose songs, the app runs a small web server **inside your home network**. It is reachable only from devices on the same Wi-Fi/LAN, it requires the pairing code from the QR code, and it is not connected to the internet by the app. The app does not open any connection to the internet and contains no code that contacts a server run by the developer or by third parties. (The internet permission is used only for this local server.)

The page is delivered over plain `http` on your local network. Anyone who can already join your Wi-Fi and has scanned the QR code can control the queue, so treat the QR code like a house key. "Reset phone pairing" in the TV's settings creates a new code and locks out every phone that used the old one.

## Permissions

- **Internet / network access:** to run the local phone page described above.
- **Foreground service (media playback) and notifications:** to keep the music playing while the screen is busy or the app is in the background, and to show the playback notification.
- **Storage:** the app does not request broad storage access. It reads the folder you choose (or its own folder).

The app does not use your camera, microphone, contacts, location or accounts. (The microphone for singing is yours, plugged into your TV or amplifier, not the app.)

## Children

The app is a general-purpose karaoke player and is not directed at children. It collects no personal information from anyone, including children.

## Third-party code

The app includes open-source libraries (Google Media3/ExoPlayer for playback, NanoHTTPD for the local web server and ZXing for the QR code). They run inside the app and do not send data anywhere.

## Your content

The app plays only files you supply. It does not include, download or share songs, lyrics or videos. You are responsible for having the right to use the files you play.

## Changes

If this policy changes, the new version will be posted at this address with a new effective date.

## Contact

Questions: [contact email].
