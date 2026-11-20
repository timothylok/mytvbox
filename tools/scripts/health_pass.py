r"""Karaoke system health pass (read-only): run daily by the Windows task "Karaoke Health Pass".

Checks (never pushes, installs or changes anything; only talks to the box with `adb ... df`):
  1. the 03:00 sync ("Karaoke Daily Sync"): ran today, finished, exit code, failed/held, ATTENTION lines, rescan result
  2. Task Scheduler: last result and next run of both tasks
  3. the Google TV: reachable by adb (mDNS name, last good address, fallback) and free space on the library volume
  4. the lyric batch log (logs/whisper_words_batch.log): installed / NOT installed counts, if it exists
  5. PC disk space (D:) and uncommitted git work

Writes logs\health_pass.txt (latest report, first line "OK" or "PROBLEMS") and appends one line to logs\health_history.log.
Exit code 0 = OK, 1 = problems.   usage: python health_pass.py [--no-box]
"""
import datetime, json, os, re, shutil, subprocess, sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
LOGS = os.path.join(ROOT, "logs")
ADB = os.path.join(ROOT, "tools", "sdk", "platform-tools", "adb.exe")
MDNS, FALLBACK, VOLUME = "adb-1e5517831b69e12c", "192.168.3.101:2025", "85F8-1509"   # same as TARGETS["google"] in daily_sync.py
NOWIN = 0x08000000
problems, notes = [], []


def run(cmd, timeout=40):
    try:
        r = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8",
                           errors="replace", timeout=timeout, creationflags=NOWIN)
        return r.returncode, r.stdout
    except Exception as e:
        return 99, str(e)


def check_sync():
    p = os.path.join(LOGS, "last_run.json")
    if not os.path.isfile(p):
        problems.append("logs/last_run.json is missing: the daily sync has never finished a run")
        return
    d = json.load(open(p, encoding="utf-8"))
    fin = d.get("finished", "")
    try:
        age = datetime.datetime.now() - datetime.datetime.strptime(fin, "%Y-%m-%d %H:%M:%S")
    except ValueError:
        age = None
    if age is None or age > datetime.timedelta(hours=30):
        problems.append(f"daily sync has not finished a run in the last 30 h (last finished: {fin or 'never'})")
    if d.get("exit_code") not in (0, None):
        problems.append(f"daily sync exit code {d.get('exit_code')}")
    if d.get("failed"):
        problems.append(f"daily sync: {d['failed']} failed step(s)")
    for a in d.get("attention", []):
        problems.append("daily sync ATTENTION: " + a)
    if d.get("pending"):
        problems.append(f"{d['pending']} song(s) still waiting to be pushed after {d.get('retries', 0)} retry run(s) (TV busy/off?)")
    try:
        st = json.load(open(os.path.join(LOGS, "daily_sync_state.json"), encoding="utf-8"))
        streak = [k for k, v in st.get("fails", {}).items() if v >= 3]
        if streak:
            problems.append(f"{len(streak)} song(s) failed 3+ nights in a row: " + "; ".join(streak[:3]))
        held = st.get("held", {})
        est = [k for k, v in held.items() if v.get("reason", "").startswith("estimated timing")]
        notes.append(f"held songs: {len(held)} ({len(est)} for estimated timing; list: daily_sync.py --list-held)")
    except Exception:
        pass
    if d.get("box_extras") or d.get("duplicates"):
        notes.append(f"library report (logs/library_report.txt): {d.get('box_extras')} file(s) on the box not in the library, {d.get('duplicates')} duplicate title(s)")
    notes.append(f"last sync {fin}: made {d.get('karaoke_made')}, aligned {d.get('aligned')}, pushed {d.get('pushed_songs')} "
                 f"({d.get('pushed_mb')} MB), held {d.get('held')}, box {d.get('box')}, rescan {d.get('rescan')}")


def check_tasks():
    for name in ("Karaoke Daily Sync", "Karaoke Sync Retry", "Karaoke Health Pass"):
        c, o = run(["powershell", "-NoProfile", "-Command",
                    f"$i=Get-ScheduledTaskInfo -TaskName '{name}' -ErrorAction Stop; '{{0}}|{{1}}' -f $i.LastTaskResult,$i.NextRunTime"])
        if c != 0 or "|" not in o:
            problems.append(f"scheduled task '{name}' not found / unreadable")
            continue
        res, nxt = o.strip().splitlines()[-1].split("|", 1)
        if name != "Karaoke Health Pass" and res.strip() not in ("0", "267009", "267011"):   # 0 ok, 267009 running, 267011 never run
            problems.append(f"task '{name}' last result {res.strip()} (0 = success)")
        notes.append(f"task {name}: last result {res.strip()}, next run {nxt.strip()}")


def find_box():
    c, o = run([ADB, "mdns", "services"], 20)
    m = re.search(re.escape(MDNS) + r"\S*\s+\S+\s+(\d+\.\d+\.\d+\.\d+:\d+)", o) if c == 0 else None
    cands = [m.group(1)] if m else []
    try:
        cands.append(json.load(open(os.path.join(LOGS, "box_address.json"), encoding="utf-8")).get("addr", ""))
    except Exception:
        pass
    cands.append(FALLBACK)
    for addr in [x for x in cands if x]:
        run([ADB, "connect", addr], 15)
        c, o = run([ADB, "-s", addr, "shell", "echo", "ok"], 15)
        if c == 0 and "ok" in o:
            return addr
    return None


def check_box():
    addr = find_box()
    if not addr:
        problems.append("Google TV not reachable by adb (is it on, wireless debugging enabled?)")
        return
    c, o = run([ADB, "-s", addr, "shell", "df", "-k", "/storage/" + VOLUME], 20)
    m = re.search(r"\s(\d+)\s+(\d+)\s+(\d+)\s+(\d+)%", o)
    if not m:
        problems.append(f"library volume /storage/{VOLUME} not mounted on the Google TV (SD card missing?)")
        return
    free_gb = int(m.group(3)) / 1024 / 1024
    notes.append(f"Google TV {addr}: library volume {VOLUME} {free_gb:.1f} GB free ({m.group(4)}% used)")
    if free_gb < 5:
        problems.append(f"library volume only {free_gb:.1f} GB free")


def check_batch():
    p = os.path.join(LOGS, "whisper_words_batch.log")
    if not os.path.isfile(p):
        return
    t = open(p, encoding="utf-8", errors="replace").read()
    ok, bad = len(re.findall(r"^\s+installed ", t, re.M)), len(re.findall(r"NOT installed", t))
    notes.append(f"lyric batch: {ok} installed, {bad} NOT installed (log {datetime.datetime.fromtimestamp(os.path.getmtime(p)):%m-%d %H:%M})")
    # songs the batch could not time are held by daily_sync (see "held songs"), so they are not a problem by themselves


def check_pc():
    free = shutil.disk_usage("D:\\").free / 1e9
    notes.append(f"PC D: {free:.0f} GB free")
    if free < 20:
        problems.append(f"PC drive D: only {free:.0f} GB free")
    c, o = run(["git", "-C", ROOT, "status", "--short"], 20)
    dirty = [l for l in o.splitlines() if l.strip() and not l.strip().endswith("processed.csv")]
    if dirty:
        notes.append("uncommitted: " + ", ".join(l.strip() for l in dirty[:8]))


def main():
    os.makedirs(LOGS, exist_ok=True)
    for f in (check_sync, check_tasks, check_batch, check_pc) + (() if "--no-box" in sys.argv else (check_box,)):
        try:
            f()
        except Exception as e:
            problems.append(f"{f.__name__} crashed: {e}")
    now = datetime.datetime.now().strftime("%Y-%m-%d %H:%M")
    head = "OK" if not problems else "PROBLEMS"
    body = [f"{head}  ({now})"] + ["PROBLEM: " + p for p in problems] + ["  " + n for n in notes]
    open(os.path.join(LOGS, "health_pass.txt"), "w", encoding="utf-8").write("\n".join(body) + "\n")
    open(os.path.join(LOGS, "health_history.log"), "a", encoding="utf-8").write(f"{now} {head} {len(problems)} problem(s)\n")
    print("\n".join(body))
    sys.exit(1 if problems else 0)


if __name__ == "__main__":
    main()
