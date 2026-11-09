# Overnight report (started 2026-11-09 ~10:45 pm NZ)

Loop: every 20 min, branch `overnight` (main is untouched). Task list: HANDOFF.md.

## Job status (latest first)
- 11:17 pm - round 2: batch 114+/650 (0 failures); video copy 7+/87 (0 failures); alignment 4/6 done
  (人啊人 45/45, 十面埋伏 25/25, Shall We Talk 51/51, 人來人往 45/45 lines aligned, none kept as-is), song 5 running.
- 10:45 pm - round 1: batch 97/650, 0 failures; video copy 2/87 started (push of #2 in progress), 0 failures;
  alignment 1/6 done (人啊人: 45/45 lines aligned, 392 s on CPU while the other jobs run), song 2 in progress.
  All three processes alive.

## Done
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
- Muted the box (volume 0) during tests and restored it to 7 afterwards; queue empty, nothing playing at end of round.
- While the batch + video copy run, expect video songs to stutter on the TV (disk contention) - not an app bug.
