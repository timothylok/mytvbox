"""Append-only history of what the tool scripts processed: D:\\ai\\setupbox\\processed.csv (committed to git).

    from ledger import log
    log("karaoke", source_path, output_path, "ok", "optional note")

Columns: when (local time), step, source, output, status, note.
Steps used: karaoke (Demucs L/R mp3), align (lyrics timed to the vocals), video (mp4 pushed to the box),
lrc (lyric file pushed to the box), map (GetVideo clean name <-> Karaoke long name), note (free text).
Status: ok / failed / skipped. Logging never raises: a ledger problem must not break a long batch.

    python ledger.py note "what happened"      # add a manual entry
    python ledger.py show [N]                  # last N rows (default 20)
"""
import csv, os, sys, time

LEDGER = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "processed.csv"))
FIELDS = ["when", "step", "source", "output", "status", "note"]


def log(step, source="", output="", status="ok", note=""):
    try:
        new = not os.path.exists(LEDGER)
        with open(LEDGER, "a", encoding="utf-8-sig" if new else "utf-8", newline="") as fh:
            w = csv.writer(fh)
            if new:
                w.writerow(FIELDS)
            w.writerow([time.strftime("%Y-%m-%d %H:%M:%S"), step, source, output, status, note])
    except Exception:
        pass


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    if len(sys.argv) >= 3 and sys.argv[1] == "note":
        log("note", note=" ".join(sys.argv[2:]))
    elif len(sys.argv) >= 2 and sys.argv[1] == "show":
        n = int(sys.argv[2]) if len(sys.argv) > 2 else 20
        for row in list(csv.reader(open(LEDGER, encoding="utf-8-sig")))[-n:]:
            print(" | ".join(row))
    else:
        print(__doc__)
