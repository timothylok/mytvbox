# Google Play - Data safety form: draft answers for K歌之王

Draft prepared from the code (checked 2026-11-18): permissions are INTERNET, FOREGROUND_SERVICE(+MEDIA_PLAYBACK), POST_NOTIFICATIONS; no analytics / ads / crash-reporting libraries (dependencies: Media3 ExoPlayer, NanoHTTPD, ZXing); no outgoing network address in the code or the phone page; all data in the app's private storage. **Answer the console's questions yourself; this is only the suggested set. If you add any SDK or network feature, redo it.**

## Data collection and sharing

| Console question | Suggested answer | Reason |
|---|---|---|
| Does your app collect or share any of the required user data types? | **No** | Nothing leaves the device for the developer or any third party. Data kept on the TV box (queue, history, singer name, folder choice, pairing code) is processed on the device only. |
| Is all of the user data collected by your app encrypted in transit? | not asked when nothing is collected (if asked: **No**, the local phone page is plain http on the LAN) | |
| Do you provide a way for users to request data deletion? | not asked when nothing is collected (in the policy anyway: Clear data / uninstall) | |

**Judgement call to double-check in the console's definition of "collect":** Google counts data as collected when it is *transmitted off the user's device*. The phone page sends the optional singer name from the user's own phone to the user's own TV box over their Wi-Fi; it never reaches the developer. I read that as *not collected*. If you prefer to be conservative, declare "Name" or "User IDs" as collected, optional, for app functionality, not shared, and not encrypted in transit - that is also truthful.

## Security practices (shown on the listing)

- Data encrypted in transit: **No** (LAN only, plain http, pairing code required)
- You can request that data be deleted: **Yes** (Clear data / uninstall; no server-side data exists)
- Committed to follow the Play Families policy: **No** (not a children's app)
- Independent security review: **No**

## Other App content declarations

| Section | Suggested answer |
|---|---|
| Privacy policy URL | the hosted `privacy-policy.md` (required) |
| Ads | **No ads** |
| App access (restricted access) | The app needs a song folder and phone pairing. Give the reviewers instructions: "On first start press OK, choose a folder with songs; the TV shows a QR code for the phone page; the on-TV browser (OK / Back on the remote) works without a phone." Provide a small set of **royalty-free / your own** sample songs or a short test video, since the app ships with none. |
| Target audience | **18+ or 13+** (not for children) |
| Content rating questionnaire | answer for a media player with no user-generated content shared with other users; expect a low rating |
| Government / financial / health / news app | No |
| Permissions declarations | Foreground service type **mediaPlayback**: used to keep karaoke audio playing; give a one-line justification and a short video if asked |
| Advertising ID | **No** (no ad SDK; if the form asks, answer that the app does not use it) |
| Data safety "data types" list | leave all unchecked (nothing collected) |

## Things the policy depends on (keep true)

1. No analytics / crash SDK (e.g. Firebase) is added. If one is, the policy and this form must change before release.
2. The phone page stays LAN-only, pairing-protected and makes no external requests (no CDN fonts or scripts).
3. Weather, news or stock features exist only in the separate **launcher** app (`launcher/`), which is not the Play app; the karaoke app has none of it.
4. Uninstall deletes songs kept in the app's own folder (stated in the policy and in the user manual).

## Not covered by this form (separate Play issues)

- **Music / lyrics / video copyright.** The app ships no content and plays the user's own files, but the store listing must not suggest it provides songs, and the lyric-timing tools in `tools/` are PC-side and not part of the app.
- **TV listing:** Android TV requirements (banner 320x180 dp / 1280x720 px, D-pad navigation, leanback launcher intent) and Play's TV quality checks.
- **Target API 36** by 2026-08-31 (done in the build, see HANDOFF).
