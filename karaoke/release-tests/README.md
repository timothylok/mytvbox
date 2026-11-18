# Release tests

Automated checks to run before a release (and before committing storage / folder-picker / library changes).

| File | What | Needs |
|---|---|---|
| `FolderPickerLogicTest.java` | the "internal storage only" rule (rejects USB / SD card tree ids), `Storage` listing and text reading incl. Chinese names and BOM | JDK only |
| `PairingTest.java` | the phone-page pairing token logic (token shape, constant-time compare, cookie parsing, Set-Cookie flags, QR address) | JDK only |
| `folder_picker_ui_test.py` | pairing is enforced (401 without the QR token / cookie, 303 + cookie with it), first run -> chooser screen + USB warning -> system picker -> Allow -> library loads -> persists after restart -> "change folder" shows the screen again | TV box on adb, debug build installed, songs in `/sdcard/Karaoke` |
| `AppFoldersTest.java` | path rules of the in-app song-location chooser (containment, `..`/prefix tricks, listing, parent, names) | JDK only |
| `app_folder_device_test.py` | boxes with NO system folder chooser: own-folder fallback, rescan, playback, restart, 401, and Settings > Song location driven by remote keys (non-destructive; SKIPs on boxes that have a chooser) | box on adb (`--device ip:port`), debug build, ffmpeg |
| `BrowseModelTest.java` | grouping / ordering of the on-TV song browser (language order, artist sort, songs newest first, null-safe) | JDK only |
| `tv_browser_device_test.py` | the on-TV song browser driven with remote keys: OK opens it on the idle screen, drill language > artist > song, add, Back during playback, hold OK = play next, Back closes; checked through `/api/state` (`browse`, `playing`, `upcoming`); refuses to run unless the box is idle with an empty queue | box on adb (`--device ip:port`), debug build, 3+ songs |
| `run_tests.py` | runs the above (`--ui` adds the device test) | |

```
python karaoke\release-tests\run_tests.py          # JVM checks
python karaoke\release-tests\run_tests.py --ui     # + device test (clears the app's data, then restores history/queue/flips/offsets)
```

Not automatable here: choosing a USB drive in the picker (needs a USB drive plugged in) - the rule is unit-tested instead;
different Android versions / TV models (needs the emulator or another device - see the Google Play section of HANDOFF.md).

Add new release tests to this folder and list them in the table.
