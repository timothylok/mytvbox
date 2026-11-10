# Release tests

Automated checks to run before a release (and before committing storage / folder-picker / library changes).

| File | What | Needs |
|---|---|---|
| `FolderPickerLogicTest.java` | the "internal storage only" rule (rejects USB / SD card tree ids), `Storage` listing and text reading incl. Chinese names and BOM | JDK only |
| `folder_picker_ui_test.py` | first run -> chooser screen + USB warning -> system picker -> Allow -> library loads -> persists after restart -> "change folder" shows the screen again | TV box on adb, debug build installed, songs in `/sdcard/Karaoke` |
| `run_tests.py` | runs the above (`--ui` adds the device test) | |

```
python karaoke\release-tests\run_tests.py          # JVM checks
python karaoke\release-tests\run_tests.py --ui     # + device test (clears the app's data, then restores history/queue/flips/offsets)
```

Not automatable here: choosing a USB drive in the picker (needs a USB drive plugged in) - the rule is unit-tested instead;
different Android versions / TV models (needs the emulator or another device - see the Google Play section of HANDOFF.md).

Add new release tests to this folder and list them in the table.
