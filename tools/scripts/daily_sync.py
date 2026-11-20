"""Daily GetVideo -> karaoke -> TV box sync (run by Task Scheduler at 03:00 through daily_sync_hidden.vbs).

TARGET (--target, default "google"): the Google_TV box. Its songs live on the SD CARD (volume 85F8-1509; the old USB drive A6D0-F59E is failing and retired) in the app's own folder
/storage/A6D0-F59E/Android/data/<app id>/files/Karaoke. The box's wireless-debugging port changes after a reboot, so it is looked up
by its mDNS name (`adb mdns services`) every run, with the last good address as a fallback. No USB drive mounted = nothing is pushed
(the box's own storage is only ~3.8 GB). The app must have that folder selected (TV Settings > Song location), otherwise the run reports
it. "--target old" = the Android 10 box (192.168.3.79:5555, internal /sdcard/Karaoke).

Same logic as the manual process (CLAUDE.md rule 2), one song at a time, every step logged to processed.csv:
  A  make_karaoke.py   Demucs L/R mp3 + loudness normalize for sources that have no karaoke mp3 yet
     align             lyrics timed to the vocals (align_folder.py + clean_aligned.py) for songs whose .lrc is still plain
     quarantine        a .lrc that does not fit the song (last line after the end / covers < 40 %) is HELD, never pushed
  B  adb to the box, remember what is already there (size compare)
  C  crops / titles / years / static_videos for the songs about to be pushed
  D  wait until the TV is idle (nothing playing or paused), then push per song: .lrc -> .mp3 -> .mp4 (.part + rename + size check)
  E  push the small txt files (md5 compare), then POST /api/rescan so the phone page sees the songs
Only songs that have a processed .lrc are ever pushed. Nothing is deleted from the box or from the PC.

Error handling: one lock (no overlapping runs), every phase and every song in try/except (a failure is logged and the run
carries on), timeouts on every subprocess/adb call, box unreachable or busy = skip the push and try again tomorrow (exit 0),
unexpected crash = exit 1 (shows in Task Scheduler), logs\\daily_sync.log (rotating), logs\\last_run.json, processed.csv rows
(step push/lrc/video/meta/quarantine/rescan/sync), logs\\daily_sync_state.json (held songs, failure counters).

usage:
  daily_sync.py --dry-run                 # show the plan, change nothing
  daily_sync.py [--only TEXT]             # run (TEXT limits everything to songs whose key contains it)
  daily_sync.py --no-process | --no-push  # only the PC side / only the box side
  daily_sync.py --list-held | --hold "Lang/Artist/stem" "reason" | --release "Lang/Artist/stem"
"""
import argparse, atexit, csv, hashlib, json, logging, os, re, shutil, subprocess, sys, tempfile, time, traceback, urllib.request
from logging.handlers import RotatingFileHandler

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import ledger
from ledger import log
from make_karaoke import find_songs
from copy_videos import q

ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
TOOLS = os.path.join(ROOT, "tools")
ADB = os.path.join(TOOLS, "sdk", "platform-tools", "adb.exe")
LOGDIR = os.path.join(ROOT, "logs")
STATE_FILE = os.path.join(LOGDIR, "daily_sync_state.json")
LOCK_FILE = os.path.join(LOGDIR, "daily_sync.lock")
LAST_RUN = os.path.join(LOGDIR, "last_run.json")
SUBLOG = os.path.join(LOGDIR, "daily_sync_sub.log")
WORK = os.path.join(TOOLS, "daily_sync")          # staging + backups of original lyric files
GETVIDEO = r"D:\ai\GetVideo"
KARAOKE = r"D:\ai\Karaoke"
PKG = "com.superghosttim.karaokeking"
TARGETS = {
    # addr = last known adb address; mdns = the box's wireless-debugging name (its serial); usb_uuid = the drive that holds the songs
    "google": {"addr": "192.168.3.101:2025", "mdns": "adb-1e5517831b69e12c", "usb_uuid": "85F8-1509"},   # SD card (the old WDC USB drive A6D0-F59E is failing: do not use)
    "old": {"addr": "192.168.3.79:5555", "mdns": None, "usb_uuid": None},
}
ADDR_FILE = os.path.join(LOGDIR, "box_address.json")
TARGET = "google"
BOX = TARGETS["google"]["addr"]
BOX_HOST = BOX.split(":")[0]
USB_UUID = TARGETS["google"]["usb_uuid"]
BOX_VOL = "/storage/" + USB_UUID                     # the volume that holds the songs (df is asked about it)
BOX_ROOT = "%s/Android/data/%s/files/Karaoke" % (BOX_VOL, PKG)
LANGCODE = {"Cantopop": "zh", "Mandarin": "zh", "JPop": "ja", "KPop": "ko", "English": "en", "Other": "zh"}   # Whisper language per library
META = ("crops.txt", "titles.txt", "years.txt", "static_videos.txt")
NOWIN = 0x08000000                                  # CREATE_NO_WINDOW: children never open a console
ENV = dict(os.environ, PYTHONIOENCODING="utf-8", PYTHONUTF8="1")
TAGS = re.compile(r"<\d+:\d+")
LINE = re.compile(r"^\s*\[(\d+):(\d+(?:\.\d+)?)\]")

lg = logging.getLogger("daily_sync")
summary = {"karaoke_made": 0, "aligned": 0, "held": 0, "pushed_songs": 0, "pushed_mb": 0.0, "failed": 0,
           "attention": [], "box": "not tried", "rescan": "no", "pending": 0, "whisper_fixed": 0, "box_extras": None, "duplicates": None}


def attention(msg):
    lg.warning("ATTENTION: %s", msg)
    summary["attention"].append(msg)


# ------------------------------------------------------------------ small helpers
def setup_logging(console):
    os.makedirs(LOGDIR, exist_ok=True)
    lg.setLevel(logging.INFO)
    fh = RotatingFileHandler(os.path.join(LOGDIR, "daily_sync.log"), maxBytes=2_000_000, backupCount=5, encoding="utf-8")
    fh.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(message)s"))
    lg.addHandler(fh)
    if console:
        sh = logging.StreamHandler()
        sh.setFormatter(logging.Formatter("%(message)s"))
        lg.addHandler(sh)


def sub(cmd, timeout, label):
    """Run a child process with output appended to daily_sync_sub.log. Returns the exit code (124 = timeout, 125 = could not start)."""
    try:
        if os.path.exists(SUBLOG) and os.path.getsize(SUBLOG) > 8_000_000:
            os.replace(SUBLOG, SUBLOG + ".old")
        with open(SUBLOG, "ab") as fh:
            fh.write(("\n=== %s %s: %s\n" % (time.strftime("%Y-%m-%d %H:%M:%S"), label, cmd)).encode("utf-8", "replace"))
            fh.flush()
            return subprocess.run(cmd, stdout=fh, stderr=subprocess.STDOUT, timeout=timeout, env=ENV, creationflags=NOWIN).returncode
    except subprocess.TimeoutExpired:
        lg.error("%s timed out after %d s", label, timeout)
        return 124
    except Exception as e:
        lg.error("%s could not run: %s", label, e)
        return 125


def adb(args, timeout=60):
    try:
        r = subprocess.run([ADB, "-s", BOX] + args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8",
                           errors="replace", timeout=timeout, creationflags=NOWIN)
        return r.returncode, r.stdout
    except subprocess.TimeoutExpired:
        return 124, "timeout"
    except Exception as e:
        return 125, str(e)


def sha(path):
    try:
        with open(path, "rb") as fh:
            return hashlib.sha1(fh.read()).hexdigest()
    except OSError:
        return None


def md5(path):
    return hashlib.md5(open(path, "rb").read()).hexdigest()


def has_tags(path):
    try:
        return TAGS.search(open(path, encoding="utf-8-sig", errors="replace").read()) is not None
    except OSError:
        return False


def duration(path):
    try:
        r = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", path],
                           capture_output=True, text=True, timeout=60, creationflags=NOWIN)
        return float(r.stdout.strip())
    except Exception:
        return None


def lrc_problem(lrc, dur):
    """Why this lyric file cannot belong to this song, or None. Example: a 3:37 lyric file on a 2:15 cut."""
    try:
        times = [int(m.group(1)) * 60 + float(m.group(2)) for m in
                 (LINE.match(l) for l in open(lrc, encoding="utf-8-sig", errors="replace")) if m]
    except OSError as e:
        return "cannot read lyric file: %s" % e
    if len(times) < 3:
        return "lyric file has fewer than 3 timed lines"
    last = max(times)
    if dur:
        if last > dur + 8:
            return "lyrics run past the end of the song (last line %d:%02d, song %d:%02d): wrong version" % (last // 60, last % 60, dur // 60, dur % 60)
        if dur > 90 and last < 0.4 * dur:
            return "lyrics cover only %d%% of the song (last line %d:%02d of %d:%02d)" % (100 * last / dur, last // 60, last % 60, dur // 60, dur % 60)
    return None


def line_counts(lrc):
    exact = est = 0
    for l in open(lrc, encoding="utf-8-sig", errors="replace"):
        if LINE.match(l):
            if TAGS.search(l):
                exact += 1
            else:
                est += 1
    return exact, est


def est_problem(lrc):
    """Why this lyric file has no usable timing (mostly estimated lines), or None. Such songs are held, not pushed."""
    try:
        exact, est = line_counts(lrc)
    except OSError as e:
        return "cannot read lyric file: %s" % e
    if exact + est >= 8 and exact < 0.5 * (exact + est):
        return "estimated timing: only %d of %d lines timed exactly" % (exact, exact + est)
    return None


# ------------------------------------------------------------------ state, lock
def load_state():
    try:
        with open(STATE_FILE, encoding="utf-8") as fh:
            st = json.load(fh)
        for k in ("held", "aligned", "fails"):
            st.setdefault(k, {})
        return st
    except FileNotFoundError:
        return {"held": {}, "aligned": {}, "fails": {}}
    except Exception as e:
        lg.error("state file unreadable (%s): kept as .bad, starting empty", e)
        try:
            os.replace(STATE_FILE, STATE_FILE + ".bad")
        except OSError:
            pass
        return {"held": {}, "aligned": {}, "fails": {}}


def save_state(st):
    try:
        tmp = STATE_FILE + ".tmp"
        with open(tmp, "w", encoding="utf-8") as fh:
            json.dump(st, fh, ensure_ascii=False, indent=1)
        os.replace(tmp, STATE_FILE)
    except Exception as e:
        lg.error("cannot save state: %s", e)


def take_lock():
    os.makedirs(LOGDIR, exist_ok=True)
    for _ in range(2):
        try:
            fd = os.open(LOCK_FILE, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
            os.write(fd, ("%d %d" % (os.getpid(), time.time())).encode())
            os.close(fd)
            atexit.register(lambda: os.path.exists(LOCK_FILE) and os.remove(LOCK_FILE))
            return True
        except FileExistsError:
            try:
                pid, t = open(LOCK_FILE).read().split()
                out = subprocess.run(["tasklist", "/FI", "PID eq %s" % pid, "/NH"], capture_output=True, text=True, creationflags=NOWIN).stdout
                alive = pid in out
                if alive and time.time() - float(t) < 14 * 3600:
                    return False
            except Exception:
                pass
            lg.warning("stale lock removed")
            try:
                os.remove(LOCK_FILE)
            except OSError:
                pass
    return False


# ------------------------------------------------------------------ songs
class Song:
    pass


def discover(only):
    renamed = ledger.mapped()
    videos = {}
    for dp, _, fns in os.walk(GETVIDEO):
        for f in fns:
            if f.lower().endswith(".mp4") and ".part" not in f:
                videos.setdefault(os.path.splitext(f)[0], os.path.join(dp, f))
    songs = []
    for lang, artist, src in find_songs(GETVIDEO, None):
        s = Song()
        s.lang, s.artist, s.src = lang, artist, src
        s.kmp3 = renamed.get(src.lower()) or os.path.join(KARAOKE, lang, artist, os.path.basename(src))
        s.kdir = os.path.dirname(s.kmp3)
        s.kstem = os.path.splitext(os.path.basename(s.kmp3))[0]
        s.sstem = os.path.splitext(os.path.basename(src))[0]
        s.key = "/".join(os.path.relpath(s.kmp3, KARAOKE).replace("\\", "/").rsplit(".", 1)[0:1])
        s.src_lrc = os.path.splitext(src)[0] + ".lrc"
        s.k_lrc = os.path.splitext(s.kmp3)[0] + ".lrc"
        s.video = videos.get(s.kstem) or videos.get(s.sstem)
        s.rel = os.path.relpath(s.kdir, KARAOKE).replace("\\", "/")
        if only and only.lower() not in s.key.lower():
            continue
        songs.append(s)
    return songs


def ledger_aligned():
    """{lyric path lower: epoch of its last successful 'align' row}, for songs aligned before this job existed."""
    out = {}
    for r in ledger.rows():
        if r.get("step") == "align" and r.get("status") == "ok" and r.get("output"):
            try:
                out[r["output"].lower()] = time.mktime(time.strptime(r["when"][:19], "%Y-%m-%d %H:%M:%S"))
            except Exception:
                pass
    return out


def lrc_state(s, st, led):
    """-> (lrc path to use or None, processed?, fresh_source?)"""
    k, src = os.path.isfile(s.k_lrc), os.path.isfile(s.src_lrc)
    fresh = src and (not k or os.path.getmtime(s.src_lrc) > os.path.getmtime(s.k_lrc) + 2)
    lrc = s.src_lrc if (fresh or not k) and src else (s.k_lrc if k else None)
    if lrc is None:
        return None, False, False
    done = has_tags(lrc) or st["aligned"].get(s.key) == sha(lrc) or \
        (lrc.lower() in led and os.path.getmtime(lrc) <= led[lrc.lower()] + 120)
    return lrc, done, fresh


def held_active(s, st):
    h = st["held"].get(s.key)
    if not h:
        return None
    newest = max([os.path.getmtime(p) for p in (s.src_lrc, s.k_lrc) if os.path.isfile(p)] or [0])
    if newest > h["at"] + 2:
        lg.info("released (lyric file changed since it was held): %s", s.key)
        del st["held"][s.key]
        return None
    return h


def hold(s, st, reason, mp3=""):
    st["held"][s.key] = {"reason": reason, "at": time.time()}
    summary["held"] += 1
    attention("HELD %s: %s" % (s.key, reason))
    log("quarantine", s.src_lrc if os.path.isfile(s.src_lrc) else s.k_lrc, "", "held", reason)
    save_state(st)


# ------------------------------------------------------------------ phase A: make, normalize, align
def phase_a(songs, st, a, deadline):
    pending = [s for s in songs if not os.path.isfile(s.kmp3)]
    lg.info("A: %d songs without a karaoke mp3", len(pending))
    if pending and not a.dry_run:
        cmd = [sys.executable, os.path.join(HERE, "make_karaoke.py")]
        if a.only:
            cmd += ["--only", a.only]
        rc = sub(cmd, max(600, deadline - time.time()), "make_karaoke")
        made = [s for s in pending if os.path.isfile(s.kmp3)]
        summary["karaoke_made"] = len(made)
        for s in pending:
            if s in made:
                st["fails"].pop(s.key, None)
            else:
                st["fails"][s.key] = st["fails"].get(s.key, 0) + 1
                summary["failed"] += 1
                if st["fails"][s.key] >= 3:
                    attention("Demucs has failed %d days in a row: %s" % (st["fails"][s.key], s.src))
        if rc not in (0,):
            lg.error("make_karaoke exit code %s", rc)
            log("sync", "make_karaoke.py", "", "failed", "exit code %s" % rc)
        save_state(st)
        for artist in sorted({s.artist for s in made}):          # belt and braces: skips files already within tolerance
            sub([sys.executable, os.path.join(HERE, "normalize_karaoke.py"), "--only", artist], 3600, "normalize " + artist)
    elif pending:
        for s in pending:
            print("  would make:", s.key)

    led = ledger_aligned()
    stage = []
    for s in songs:
        try:
            if not os.path.isfile(s.kmp3) or held_active(s, st):
                continue
            lrc, done, fresh = lrc_state(s, st, led)
            if lrc is None or done:
                continue
            dur = duration(s.src) or duration(s.kmp3)
            why = lrc_problem(lrc, dur)
            if why:
                if a.dry_run:
                    print("  would HOLD:", s.key, "-", why)
                else:
                    hold(s, st, why)
                continue
            stage.append((s, lrc))
        except Exception:
            lg.error("lyric check failed for %s\n%s", s.key, traceback.format_exc())
    lg.info("A: %d songs need lyric alignment", len(stage))
    if a.dry_run:
        for s, lrc in stage:
            print("  would align:", s.key)
        return
    BATCH = 8
    groups = {}
    for s, lrc in stage:                                   # one Whisper language per batch
        groups.setdefault(LANGCODE.get(s.lang, "zh"), []).append((s, lrc))
    left = len(stage)
    for code, items in groups.items():
        for i in range(0, len(items), BATCH):
            if time.time() > deadline:
                attention("processing time limit reached: %d songs left to align for the next nights" % left)
                return
            batch = items[i:i + BATCH]
            align_batch(batch, st, code)
            left -= len(batch)


def whisper_words_fallback(s, st):
    """Songs align_folder could barely time: re-time them with align_whisper_words.py (Whisper word times on Demucs vocals),
    using the lyric text the song already has. Installed only when that script's own gates pass (>= 70 % of lines heard,
    first character near the first vocal). -> True when installed."""
    try:
        from align_whisper_words_batch import lyric_lines
        lines, _ = lyric_lines(s.k_lrc)
        if len(lines) < 8:
            return False
        txt, out = os.path.join(WORK, "wwords_lyrics.txt"), os.path.join(WORK, "wwords_out.lrc")
        with open(txt, "w", encoding="utf-8") as fh:
            fh.write("\n".join(lines) + "\n")
        rc = sub([sys.executable, "-X", "utf8", os.path.join(HERE, "align_whisper_words.py"), "--mp3", s.src, "--lyrics", txt,
                  "--out", out, "--install", s.k_lrc, "--min-timed", "0.7", "--language", LANGCODE.get(s.lang, "zh")],
                 3600, "whisper-words " + s.key)
        if rc != 0:
            lg.info("whisper-words gave no usable result for %s (exit %s)", s.key, rc)
            return False
        if os.path.isfile(s.src_lrc):
            shutil.copy2(s.k_lrc, s.src_lrc)
        st["aligned"][s.key] = sha(s.k_lrc)
        summary["whisper_fixed"] += 1
        return True
    except Exception:
        lg.error("whisper-words fallback failed for %s\n%s", s.key, traceback.format_exc())
        return False


def align_batch(batch, st, language):
    work = os.path.join(WORK, "stage")
    shutil.rmtree(work, ignore_errors=True)
    orig = os.path.join(WORK, "stage_orig")
    shutil.rmtree(orig, ignore_errors=True)
    os.makedirs(work)
    names = {}
    for n, (s, lrc) in enumerate(batch):
        try:
            shutil.copy2(s.src, os.path.join(work, "s%03d.mp3" % n))
            shutil.copy2(lrc, os.path.join(work, "s%03d.lrc" % n))
            names[n] = (s, lrc)
        except Exception as e:
            lg.error("cannot stage %s: %s", s.key, e)
    if not names:
        return
    sub([sys.executable, os.path.join(HERE, "align_folder.py"), "--dir", work, "--backup", orig, "--language", language],
        1500 * len(names) + 600, "align batch " + language)
    for n, (s, lrc) in names.items():
        try:
            staged = os.path.join(work, "s%03d.lrc" % n)
            if not os.path.isfile(os.path.join(orig, "s%03d.lrc" % n)):      # align_folder saves the original only on success
                st["fails"][s.key] = st["fails"].get(s.key, 0) + 1
                summary["failed"] += 1
                log("align", s.src, s.k_lrc, "failed", "align_folder gave no result (day %d)" % st["fails"][s.key])
                if st["fails"][s.key] >= 3:
                    attention("alignment has failed %d days in a row: %s" % (st["fails"][s.key], s.key))
                continue
            exact, est = line_counts(staged)
            bak = os.path.join(WORK, "orig", s.rel.replace("/", os.sep))
            os.makedirs(bak, exist_ok=True)
            shutil.copy2(lrc, os.path.join(bak, s.kstem[:80] + time.strftime(".%Y%m%d-%H%M%S.lrc")))
            os.makedirs(s.kdir, exist_ok=True)
            tmp = s.k_lrc + ".part"
            shutil.copy2(staged, tmp)
            os.replace(tmp, s.k_lrc)
            if os.path.isfile(s.src_lrc) or lrc == s.src_lrc:
                shutil.copy2(s.k_lrc, s.src_lrc)             # same mtime on both: neither counts as "fresh" next time
            st["aligned"][s.key] = sha(s.k_lrc)
            st["fails"].pop(s.key, None)
            summary["aligned"] += 1
            log("align", s.src, s.k_lrc, "ok", "%d lines exact, %d estimated (daily_sync; original kept in %s)" % (exact, est, bak))
            if exact + est >= 8 and exact < 0.5 * (exact + est):      # quality gate: mostly estimated -> Whisper-words method, else hold
                if not whisper_words_fallback(s, st):
                    hold(s, st, "estimated timing: only %d of %d lines timed exactly and the Whisper-words method could not time it" % (exact, exact + est))
            save_state(st)
        except Exception:
            lg.error("finishing alignment failed for %s\n%s", s.key, traceback.format_exc())
            summary["failed"] += 1


# ------------------------------------------------------------------ box
def use_target(name):
    global TARGET, BOX, BOX_HOST, USB_UUID, BOX_VOL, BOX_ROOT
    t = TARGETS[name]
    TARGET, BOX, BOX_HOST, USB_UUID = name, t["addr"], t["addr"].split(":")[0], t["usb_uuid"]
    if USB_UUID:
        BOX_VOL = "/storage/" + USB_UUID
        BOX_ROOT = "%s/Android/data/%s/files/Karaoke" % (BOX_VOL, PKG)
    else:
        BOX_VOL, BOX_ROOT = "/sdcard", "/sdcard/Karaoke"


def find_box_address():
    """The wireless-debugging port changes after a reboot: look the box up by its mDNS name, else use the last good / configured address."""
    name = TARGETS[TARGET].get("mdns")
    if not name:
        return BOX
    try:
        r = subprocess.run([ADB, "mdns", "services"], capture_output=True, text=True, timeout=25, creationflags=NOWIN,
                           encoding="utf-8", errors="replace")
        for ln in r.stdout.splitlines():
            parts = ln.split()
            if len(parts) >= 3 and parts[0].startswith(name) and parts[1].startswith("_adb") and "pairing" not in parts[1] \
                    and re.fullmatch(r"\d+\.\d+\.\d+\.\d+:\d+", parts[2]):
                return parts[2]
    except Exception as e:
        lg.warning("adb mdns services: %s", e)
    try:
        with open(ADDR_FILE, encoding="utf-8") as fh:
            last = json.load(fh).get(TARGET)
            if last:
                return last
    except (OSError, ValueError):
        pass
    return BOX


def ensure_root(create=True):
    """True when the song folder can be used: for the USB target the drive must be mounted; the folder is created when missing."""
    if USB_UUID:
        rc, out = adb(["shell", "[ -d /storage/%s ] && echo yes || echo no" % USB_UUID], 20)
        if rc != 0 or "yes" not in out:
            return False
    if create:
        adb(["shell", "mkdir -p " + q(BOX_ROOT)], 30)
    rc, out = adb(["shell", "[ -d %s ] && echo yes || echo no" % q(BOX_ROOT)], 20)
    return rc == 0 and "yes" in out


def connect():
    global BOX, BOX_HOST
    addr = find_box_address()
    if addr != BOX:
        lg.info("box address %s -> %s", BOX, addr)
        BOX, BOX_HOST = addr, addr.split(":")[0]
    for i in range(3):
        try:
            subprocess.run([ADB, "connect", BOX], capture_output=True, timeout=25, creationflags=NOWIN)
        except Exception as e:
            lg.warning("adb connect: %s", e)
        rc, out = adb(["shell", "echo ok"], 20)
        if rc == 0 and "ok" in out:
            try:                                             # remember the address that worked (the fallback of find_box_address)
                last = {}
                if os.path.isfile(ADDR_FILE):
                    last = json.load(open(ADDR_FILE, encoding="utf-8"))
                if last.get(TARGET) != BOX:
                    last[TARGET] = BOX
                    with open(ADDR_FILE, "w", encoding="utf-8") as fh:
                        json.dump(last, fh)
            except (OSError, ValueError):
                pass
            return True
        lg.warning("box not reachable (try %d): %s", i + 1, out.strip()[:100])
        time.sleep(20)
    return False


def token():
    t = os.environ.get("KARAOKE_TOKEN")
    if t:
        return t
    rc, out = adb(["shell", "run-as %s cat shared_prefs/karaoke.xml" % PKG], 20)
    m = re.search(r'pair">([a-z0-9]+)', out)
    return m.group(1) if m else None


def api(path, tok, post=False):
    req = urllib.request.Request("http://%s:8080%s" % (BOX_HOST, path), method="POST" if post else "GET",
                                 data=b"" if post else None, headers={"Cookie": "karaoke_k=" + tok})
    with urllib.request.urlopen(req, timeout=15) as r:
        return json.loads(r.read().decode("utf-8") or "null") if not post else r.status


def app_running():
    """True / False, or None when the box did not give a clear answer."""
    rc, out = adb(["shell", "pidof %s || echo none" % PKG], 20)
    out = out.strip()
    if rc == 0 and out == "none":
        return False
    if rc == 0 and re.fullmatch(r"[\d ]+", out):
        return True
    return None


def idle():
    """(True/False, reason). A failed check is NEVER idle - except when the app is not running at all."""
    try:
        running = app_running()
        if running is False:
            return True, "karaoke app not running"
        if running is None:
            return False, "cannot ask the box whether the app is running"
        tok = token()
        if not tok:
            return False, "cannot read the pairing token (release build?): set KARAOKE_TOKEN"
        s = api("/api/state", tok)
        if s.get("current") is None and not s.get("playing"):
            return True, "idle (%s songs)" % s.get("songCount")
        return False, "a song is playing or paused"
    except Exception as e:
        return False, "idle check failed: %s" % e


def wait_idle(minutes):
    end = time.time() + minutes * 60
    while True:
        ok, why = idle()
        if ok:
            return True
        lg.info("TV not idle: %s", why)
        if time.time() + 300 > end:
            attention("TV not idle (%s): nothing pushed; will retry at the next trigger" % why)
            return False
        time.sleep(300)


def remote_list():
    rc, out = adb(["shell", "find %s -type f -exec stat -c '%%s %%n' {} +" % BOX_ROOT], 180)
    if rc != 0:
        return None
    d = {}
    for ln in out.splitlines():
        sz, _, p = ln.partition(" ")
        if sz.isdigit():
            d[p] = int(sz)
    return d


def free_bytes():
    rc, out = adb(["shell", "df %s | tail -1" % q(BOX_VOL)], 30)
    try:
        return int(out.split()[3]) * 1024
    except Exception:
        return None


RMD5 = {}        # remote .lrc path -> md5 (a realigned lyric file can have the same size as the old one)


def remote_md5():
    rc, out = adb(["shell", "find %s -type f -name '*.lrc' -exec md5sum {} +" % q(BOX_ROOT)], 180)
    d = {}
    if rc == 0:
        for ln in out.splitlines():
            h, _, p = ln.partition("  ")
            if len(h) == 32:
                d[p.strip()] = h
    return d


def same_on_box(local, remote, step, rem):
    if rem.get(remote) != os.path.getsize(local):
        return False
    if step == "lrc" and remote in RMD5:
        return RMD5[remote] == md5(local)
    return True


def push_file(local, remote, step, rem):
    """One file: skip when the same size (and for .lrc the same md5) is already there, else .part + rename + size check, one retry. -> 'ok'/'skipped'/'failed'."""
    size = os.path.getsize(local)
    if same_on_box(local, remote, step, rem):
        return "skipped"
    for attempt in (1, 2):
        rdir = remote.rsplit("/", 1)[0]
        adb(["shell", "mkdir -p " + q(rdir)], 30)
        part = remote + ".part"
        rc, out = adb(["push", local, part], max(180, int(size / 1e6 * 5)))
        ok = False
        if rc == 0:
            adb(["shell", "mv %s %s" % (q(part), q(remote))], 30)
            rc2, out2 = adb(["shell", "stat -c %s " + q(remote)], 30)
            ok = out2.strip() == str(size)
        if ok:
            rem[remote] = size
            if step == "lrc":
                RMD5[remote] = md5(local)
            log(step, local, remote, "ok", "%d bytes" % size)
            return "ok"
        lg.warning("push failed (try %d) %s: %s", attempt, remote, out.strip()[-150:])
        if attempt == 1:
            connect()
    adb(["shell", "rm -f " + q(remote + ".part")], 30)
    log(step, local, remote, "failed", "push or size check failed")
    return "failed"


def files_for(s, static):
    """(local, remote, step) in push order: lyrics, mp3, then the video (a lone video would show up as a song of its own)."""
    base = "%s/%s" % (BOX_ROOT, s.rel)
    f = [(s.k_lrc, "%s/%s.lrc" % (base, s.kstem), "lrc"), (s.kmp3, "%s/%s.mp3" % (base, s.kstem), "push")]
    if s.video and s.kstem not in static and os.path.isfile(s.video):
        f.append((s.video, "%s/%s.mp4" % (base, s.kstem), "video"))
    return f


def missing(files, rem):
    return [(l, r, step) for l, r, step in files if not same_on_box(l, r, step, rem)]


def norm_title(stem):
    t = re.sub(r"official|music video|lyric video|lyrics|\bmv\b|\bm/v\b|4k|hd|官方|完整版|動態歌詞|动态歌词|歌詞付き|フル", "", stem.lower())
    return re.sub(r"[\W_]+", "", t)


def library_report(rem):
    """Read-only: files on the box that are no longer in the Karaoke library (deleted or renamed on the PC) and duplicate titles. Nothing is deleted."""
    extras = []
    root = BOX_ROOT + "/"
    for p in sorted(rem):
        if p.startswith(root) and p.lower().endswith((".mp3", ".lrc", ".mp4")):
            rel = p[len(root):].replace("/", os.sep)
            if p.lower().endswith(".mp4"):                   # videos come from GetVideo: judge them by their karaoke mp3
                rel = rel[:-4] + ".mp3"
            if not os.path.isfile(os.path.join(KARAOKE, rel)):
                extras.append(p[len(root):])
    groups = {}
    for dp, _, fns in os.walk(KARAOKE):
        for f in fns:
            if f.lower().endswith(".mp3"):
                key = (os.path.relpath(dp, KARAOKE), norm_title(os.path.splitext(f)[0]))
                groups.setdefault(key, []).append(f)
    dups = [v for k, v in groups.items() if len(v) > 1 and k[1]]
    summary["box_extras"], summary["duplicates"] = len(extras), len(dups)
    with open(os.path.join(LOGDIR, "library_report.txt"), "w", encoding="utf-8") as fh:
        fh.write("On the box but not in D:\\ai\\Karaoke (%d; nothing is deleted automatically):\n" % len(extras))
        fh.writelines("  %s\n" % x for x in extras)
        fh.write("Duplicate titles in D:\\ai\\Karaoke (%d):\n" % len(dups))
        fh.writelines("  %s\n" % " | ".join(v) for v in dups)


def phase_b_to_e(songs, st, a):
    if not connect():
        summary["box"] = "unreachable"
        lg.warning("box unreachable: push skipped, will retry at the next trigger")
        log("sync", BOX, "", "skipped", "box unreachable")
        return
    summary["box"] = "connected"
    if not ensure_root(create=not a.dry_run):
        summary["box"] = "usb drive not mounted"
        attention("the song folder %s is not available (USB drive %s not plugged in / not mounted): nothing pushed; will retry at the next trigger" % (BOX_ROOT, USB_UUID))
        log("sync", BOX, "", "skipped", "song folder / USB drive not available")
        return
    rem = remote_list()
    if rem is None:
        attention("cannot list the box's Karaoke folder; push skipped")
        return
    stale = [p for p in rem if p.endswith(".part")]
    if stale and not a.dry_run:                             # left by an interrupted push (this run holds the lock)
        for p in stale:
            adb(["shell", "rm -f " + q(p)], 30)
            rem.pop(p, None)
        log("sync", BOX, "", "ok", "removed %d stale .part file(s) from the box" % len(stale))
    RMD5.clear()
    RMD5.update(remote_md5())
    if not a.only:
        try:
            library_report(rem)
        except Exception:
            lg.error("library report failed: %s", traceback.format_exc())
    led = ledger_aligned()
    static = read_list(os.path.join(KARAOKE, "static_videos.txt"))
    cand = []
    for s in songs:
        try:
            if not os.path.isfile(s.kmp3) or held_active(s, st):
                continue
            lrc, done, fresh = lrc_state(s, st, led)
            if lrc is None or not done:
                continue                                     # no lyrics or unprocessed lyrics: never pushed (rule 2)
            if lrc != s.k_lrc:                               # processed source lyrics -> next to the karaoke mp3
                shutil.copy2(lrc, s.k_lrc)
            full = files_for(s, static)
            miss = missing(full, rem)
            if not miss:
                continue
            if any(step in ("lrc", "push") for _, _, step in miss):          # a song new to the box: quarantine check first
                why = lrc_problem(s.k_lrc, duration(s.kmp3)) or est_problem(s.k_lrc)
                if why:
                    if a.dry_run:
                        print("  would HOLD:", s.key, "-", why)
                    else:
                        hold(s, st, why)
                    continue
            cand.append(s)
        except Exception:
            lg.error("candidate check failed for %s: %s", s.key, traceback.format_exc())
    if cand and not a.dry_run:                               # C: crops / titles / years / static list for what is about to be pushed
        try:
            new_vid = [s for s in cand if s.video and any(step == "video" for _, _, step in missing(files_for(s, static), rem))]
            meta_refresh(cand, new_vid)
        except Exception:
            lg.error("metadata refresh failed: %s", traceback.format_exc())
        static = read_list(os.path.join(KARAOKE, "static_videos.txt"))
    todo = [(s, files_for(s, static)) for s in cand]
    todo = [(s, f) for s, f in todo if missing(f, rem)]
    summary["pending"] = len(todo)
    lg.info("D: %d songs to push", len(todo))
    if a.dry_run:
        for s, full in todo:
            print("  would push:", s.key, [step for _, _, step in missing(full, rem)])
        return
    if not todo and not meta_differs():
        lg.info("nothing to push")
        return
    if not wait_idle(a.idle_wait):
        return
    need_bytes = sum(os.path.getsize(l) for _, full in todo for l, r, _ in missing(full, rem))
    fb = free_bytes()
    if fb is not None and fb < need_bytes + 1.5e9:
        attention("box storage low: %.1f GB free, %.1f GB to push; push skipped" % (fb / 1e9, need_bytes / 1e9))
        return
    pushed_any = False
    for s, full in todo:
        try:
            ok, why = idle()
            if not ok and not wait_idle(a.idle_wait):
                break
            res = []
            for local, remote, step in full:
                r = push_file(local, remote, step, rem)
                res.append(r)
                if r == "failed":
                    break                                     # lrc -> mp3 -> mp4: never push the video of a song that has no mp3
                if r == "ok":
                    pushed_any = True
                    summary["pushed_mb"] += os.path.getsize(local) / 1e6
            if "failed" in res:
                st["fails"]["push:" + s.key] = st["fails"].get("push:" + s.key, 0) + 1
                summary["failed"] += 1
                if st["fails"]["push:" + s.key] >= 3:
                    attention("push has failed %d days in a row: %s" % (st["fails"]["push:" + s.key], s.key))
            else:
                st["fails"].pop("push:" + s.key, None)
                if "ok" in res:
                    summary["pushed_songs"] += 1
            save_state(st)
        except Exception:
            lg.error("push failed for %s\n%s", s.key, traceback.format_exc())
            summary["failed"] += 1
    if push_meta():
        pushed_any = True
    summary["pending"] = len([1 for s, full in todo if missing(full, rem)])
    if pushed_any:
        rescan()


def read_list(path):
    out = set()
    try:
        for ln in open(path, encoding="utf-8-sig"):
            if ln.strip() and not ln.startswith("#"):
                out.add(ln.strip())
    except OSError:
        pass
    return out


def meta_refresh(cand, new_vid):
    py = sys.executable
    if new_vid:
        dirs = sorted({os.path.dirname(s.video) for s in new_vid})
        sub([py, os.path.join(HERE, "detect_crops.py")] + dirs, 3600, "detect_crops")
        sub([py, os.path.join(HERE, "find_static_videos.py")], 3600, "find_static_videos")
    for artist in sorted({s.artist for s in cand}):
        sub([py, os.path.join(HERE, "make_titles.py"), "--artist", artist], 600, "make_titles " + artist)
    sub([py, os.path.join(HERE, "make_years.py")], 1800, "make_years")


def meta_differs():
    out = []
    for f in META:
        p = os.path.join(KARAOKE, f)
        if os.path.isfile(p):
            rc, o = adb(["shell", "md5sum " + q("%s/%s" % (BOX_ROOT, f))], 30)
            if not o.strip().startswith(md5(p)):
                out.append(f)
    return out


def push_meta():
    any_ = False
    for f in meta_differs():
        p = os.path.join(KARAOKE, f)
        remote = "%s/%s" % (BOX_ROOT, f)
        rc, out = adb(["push", p, remote + ".part"], 120)
        rc2, o2 = adb(["shell", "mv %s %s && md5sum %s" % (q(remote + ".part"), q(remote), q(remote))], 30) if rc == 0 else (1, "")
        ok = rc == 0 and rc2 == 0 and o2.strip().startswith(md5(p))
        log("meta", p, remote, "ok" if ok else "failed", "" if ok else out.strip()[-120:])
        if ok:
            any_ = True
        else:
            summary["failed"] += 1
            attention("could not push " + f)
    return any_


def rescan():
    try:
        ok, why = idle()
        if app_running() is False:
            summary["rescan"] = "app not running (it scans at start)"
            log("rescan", "", "", "skipped", "app not running")
            return
        tok = token()
        if tok and USB_UUID:
            try:
                sf = api("/api/state", tok).get("songFolder")
                if sf != BOX_ROOT:
                    attention("the app reads %r, not %s: choose it in TV Settings > Song location, or the pushed songs will not show" % (sf, BOX_ROOT))
            except Exception as e:
                lg.warning("could not check the app's song folder: %s", e)
        before = api("/api/state", tok).get("songCount")
        if not ok:
            summary["rescan"] = "skipped: TV busy"
            attention("a song started during the push: press Rescan on the phone page later")
            log("rescan", "", "", "skipped", "TV busy")
            return
        api("/api/rescan", tok, post=True)
        time.sleep(30)
        after = api("/api/state", tok).get("songCount")
        summary["rescan"] = "ok (%s -> %s songs)" % (before, after)
        log("rescan", "", "", "ok", "songCount %s -> %s" % (before, after))
    except Exception as e:
        summary["rescan"] = "failed: %s" % e
        attention("rescan failed (%s): press Rescan on the phone page" % e)
        log("rescan", "", "", "failed", str(e))


def merge_previous(sm):
    """A push-only retry run (--no-process) adds to the report of the 03:00 run it follows instead of replacing it."""
    try:
        old = json.load(open(LAST_RUN, encoding="utf-8"))
        fin = time.mktime(time.strptime(old["finished"], "%Y-%m-%d %H:%M:%S"))
    except Exception:
        return sm
    if time.time() - fin > 12 * 3600:
        return sm
    for k in ("karaoke_made", "aligned", "held", "pushed_songs", "whisper_fixed"):
        sm[k] = old.get(k, 0) + sm.get(k, 0)
    sm["pushed_mb"] = round(old.get("pushed_mb", 0) + sm["pushed_mb"], 3)
    sm["minutes"] = round(old.get("minutes", 0) + sm.get("minutes", 0), 1)
    sm["started"] = old.get("started", sm["started"])
    sm["retries"] = old.get("retries", 0) + 1
    if sm["box"] == "connected":                             # the retry's push result replaces the earlier push problems
        keep = [x for x in old.get("attention", []) if not re.search(r"TV not idle|not available|press Rescan|storage low|cannot list", x)]
        sm["attention"] = keep + [x for x in sm["attention"] if x not in keep]
    else:
        sm["failed"] += old.get("failed", 0)
        sm["box"], sm["rescan"], sm["pending"] = old.get("box", sm["box"]), old.get("rescan", sm["rescan"]), old.get("pending", sm["pending"])
        sm["attention"] = old.get("attention", []) + sm["attention"]
    if sm["rescan"] == "no":
        sm["rescan"] = old.get("rescan", "no")
    return sm


# ------------------------------------------------------------------ main
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--only")
    ap.add_argument("--target", choices=sorted(TARGETS), default="google", help="google = Google_TV box, songs on its USB drive (default); old = Android 10 box")
    ap.add_argument("--no-process", action="store_true")
    ap.add_argument("--no-push", action="store_true")
    ap.add_argument("--max-hours", type=float, default=5.0, help="stop starting new Demucs/alignment work after this long")
    ap.add_argument("--idle-wait", type=int, default=120, help="minutes to wait for an idle TV before giving up")
    ap.add_argument("--list-held", action="store_true")
    ap.add_argument("--hold", nargs=2, metavar=("KEY", "REASON"))
    ap.add_argument("--release", metavar="KEY")
    a = ap.parse_args()
    use_target(a.target)
    sys.stdout.reconfigure(encoding="utf-8")
    setup_logging(console=sys.stdout.isatty())

    if a.list_held or a.hold or a.release:
        st = load_state()
        keys = [s.key for s in discover(None)]

        def resolve(text):      # a full key, or a fragment of a name that matches exactly one song
            hit = [k for k in keys if k == text] or [k for k in keys if text.lower() in k.lower()] or \
                  [k for k in st["held"] if text.lower() in k.lower()]
            if len(hit) != 1:
                print("no single match for %r: %s" % (text, hit[:5]))
                return None
            return hit[0]
        if a.hold and resolve(a.hold[0]):
            k = resolve(a.hold[0])
            st["held"][k] = {"reason": a.hold[1], "at": time.time()}
            log("quarantine", k, "", "held", a.hold[1] + " (by hand)")
        if a.release and resolve(a.release):
            k = resolve(a.release)
            st["held"].pop(k, None)
            log("quarantine", k, "", "ok", "released by hand")
        save_state(st)
        for k, v in st["held"].items():
            print("%s | %s | %s" % (k, time.strftime("%Y-%m-%d", time.localtime(v["at"])), v["reason"]))
        return 0

    if not a.dry_run and not take_lock():
        lg.warning("another daily_sync run is active: exiting")
        log("sync", "", "", "skipped", "another run is active")
        return 2
    t0 = time.time()
    summary["started"] = time.strftime("%Y-%m-%d %H:%M:%S")
    code = 0
    if not a.dry_run:
        log("sync", "", "", "ok", "run started")
    st = load_state()
    try:
        songs = discover(a.only)
        lg.info("%d source songs", len(songs))
        if not a.no_process:
            try:
                phase_a(songs, st, a, t0 + a.max_hours * 3600)
            except Exception:
                code = 1
                lg.error("phase A crashed\n%s", traceback.format_exc())
                log("sync", "", "", "failed", "phase A crashed: see logs\\daily_sync.log")
        if not a.no_push:
            try:
                phase_b_to_e(songs, st, a)
            except Exception:
                code = 1
                lg.error("push phase crashed\n%s", traceback.format_exc())
                log("sync", "", "", "failed", "push phase crashed: see logs\\daily_sync.log")
    except Exception:
        code = 1
        lg.error("run crashed\n%s", traceback.format_exc())
        log("sync", "", "", "failed", "crashed: see logs\\daily_sync.log")
    finally:
        if not a.dry_run:
            save_state(st)
            summary["finished"] = time.strftime("%Y-%m-%d %H:%M:%S")
            summary["minutes"] = round((time.time() - t0) / 60, 1)
            summary["exit_code"] = code
            summary["held_total"] = len(st["held"])
            if a.no_process:
                merge_previous(summary)
            try:
                with open(LAST_RUN, "w", encoding="utf-8") as fh:
                    json.dump(summary, fh, ensure_ascii=False, indent=1)
            except OSError:
                pass
            note = "made %d, aligned %d, held %d, pushed %d songs (%.0f MB), failed %d, box %s, rescan %s, %.0f min" % (
                summary["karaoke_made"], summary["aligned"], summary["held"], summary["pushed_songs"], summary["pushed_mb"],
                summary["failed"], summary["box"], summary["rescan"], summary["minutes"])
            lg.info("run finished: %s", note)
            log("sync", "", "", "ok" if code == 0 else "failed", "run finished: " + note + (
                "; ATTENTION: " + " | ".join(summary["attention"]) if summary["attention"] else ""))
    return code


if __name__ == "__main__":
    sys.exit(main())
