' Push-only retry of the daily sync (task "Karaoke Sync Retry", 05:30 and 07:30): no Demucs/alignment, waits at most 20 min for an idle TV.
' Does nothing when the 03:00 run is still active (lock) or everything is already on the box. Log: D:\ai\setupbox\logs\daily_sync.log
CreateObject("WScript.Shell").Run """C:\Program Files\Python312\python.exe"" ""D:\ai\setupbox\tools\scripts\daily_sync.py"" --no-process --idle-wait 20", 0, False
