# Overnight report (started 2026-11-09 ~10:45 pm NZ)

Loop: every 20 min, branch `overnight` (main is untouched). Task list: HANDOFF.md.

## Job status (latest first)
- 1:15 am - round 8: batch ~310/650 (in Mandarin, 0 failures); video copy run 2 ~55/194 (0 failures).
- 12:45 am - round 7: batch ~275/650 (in Mandarin, 0 failures); video copy run 2 ~30/194 (0 failures); alignment done.
- 12:25 am - round 6: batch ~242/650 (0 failures); video copy run 1 finished 87/87 (0 failures, 90 min); started run 2 for the
  songs made since (194 videos to check, 21 GB); alignment done.
- 12:15 am - round 5: batch 202+/650 (in Cantopop/Eason Chan, 0 failures); video copy 76+/87 (0 failures); alignment done.
- 11:45 pm - round 4: batch 164/650 (in Beyond, 0 failures); video copy 37/87 (0 failures); alignment done.
- 11:30 pm - round 3: batch 132/650 (0 failures); video copy 12/87 (0 failures); alignment DONE 6/6 - every line of
  all 6 songs aligned, times all in order (checked by script). Credit lines like "作曲: 李峻一" get squeezed times
  because they are not sung - harmless.
- 11:17 pm - round 2: batch 114+/650 (0 failures); video copy 7+/87 (0 failures); alignment 4/6 done
  (人啊人 45/45, 十面埋伏 25/25, Shall We Talk 51/51, 人來人往 45/45 lines aligned, none kept as-is), song 5 running.
- 10:45 pm - round 1: batch 97/650, 0 failures; video copy 2/87 started (push of #2 in progress), 0 failures;
  alignment 1/6 done (人啊人: 45/45 lines aligned, 392 s on CPU while the other jobs run), song 2 in progress.
  All three processes alive.

## Done
- Round 8: spot-check of the aligned lyrics found bad timings in parts of 人啊人 (7 characters squeezed into 0.3 s,
  a line timed 11.5 s off). Wrote clean_aligned.py: keeps per-character times only where they agree with the lyric file
  (allowing a song-wide shift) and are not squeezed; otherwise the line falls back to the estimate. Applied to all 7 aligned
  songs, copied to D:\ai\Karaoke and pushed to the drive; align_folder.py now runs the check itself.
  Checked 十面埋伏 on the TV (muted): the aligned file loads, the right line is current, music video fills the screen.
- Round 7: task 4(f) rewrote karaoke/PLAN.md to match what was built; Library cache now stores/restores the video path
  (verified: index.json on the box has videoFile entries; 274 songs, 116 with video after rescan).
- Round 6: task 4(e) deleted /sdcard/test.mp3, /sdcard/test_lr.mp3 and D:\ai\Karaoke\Other\Timlok. The Timlok file was a real
  karaoke output (not only a test), so it must be regenerated after the batch: `make_karaoke.py --only Timlok --push` (in HANDOFF.md).
- Round 5: task 4(d) title overrides - `titles.txt` (on the drive and/or /sdcard/Karaoke) renames songs; formats in
  HANDOFF.md. Tested on the box with a temporary file (removed again): override shown in the list, old name still found
  by search, a bad line ignored, titles back to normal after deleting the file.
- Round 5: batch has reached Cantopop/Eason Chan; aligned .lrc files are being copied by the batch as it goes
  (checked: 《人啊人》 on the PC side has 45 word-timed lines).
- Round 4: task 4(c) search treats traditional and simplified Chinese the same (Android's built-in ICU
  "Traditional-Simplified" transliterator, applied to song text and to the query). Verified on the box:
  世纪末烟花 / 烟花 -> 世紀末煙花, 生活无小事 -> 生活無小事; English search unchanged.
- Round 3: task 4(b) foreground service - KeepAliveService keeps the app alive in the background (verified: pressed
  Home, the phone page still answered; reopening the tile brought back the same instance). Quick muted playback check OK.
- Round 3: alignment of the 6 Eason Chan songs finished; the aligned .lrc files replaced the originals in
  D:\ai\GetVideo\EasonChan\Eason Chan (originals in tools\align_out\orig). The batch has not reached Eason Chan yet,
  so it will copy the aligned versions itself; spot-check on the TV once they are on the drive.
- Round 2: tested a 16:9 music video on the box - fills the screen correctly (no bars, no crop).
- Round 2: found + fixed a bug: after a song with a video, the next audio-only song kept showing the last video frame
  instead of the photo backdrop (hidden the video surface when there is no picture). Verified on the box.
- Round 2: library rescan could pile up (a scan takes ~50 s while the USB drive is busy being written to); added a guard
  so only one scan runs at a time.
- Created branch `overnight`; saved `karaoke/known_good.apk` (the build currently on the box) for rollbacks.

## Failed / skipped
- Task 4(a) "Next up" banner: NOT verified yet. The 16:9 video test stalled ("Buffering", only reached 0:58 in ~6 min)
  because the USB drive is being written by two adb pushes at once (USB 2.0 shared). Retest after the batch and
  video copy finish (the loop will retry).

## Choices made
- Bad per-character times are dropped (estimate instead) rather than shown wrong. 人啊人 keeps only 22 of 45 lines exact.
- 4(a) is waiting for the USB writes to finish, so I moved on to 4(b) instead of retrying it on a busy drive.
- Muted the box (volume 0) during tests and restored it to 7 afterwards; queue empty, nothing playing at end of round.
- While the batch + video copy run, expect video songs to stutter on the TV (disk contention) - not an app bug.
