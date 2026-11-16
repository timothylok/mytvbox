' Runs daily_sync.py with no visible console window (used by Task Scheduler, task "Karaoke Daily Sync").
' Window style 0 = hidden; the last argument False = do not wait. All output goes to D:\ai\setupbox\logs\.
CreateObject("WScript.Shell").Run """C:\Program Files\Python312\python.exe"" ""D:\ai\setupbox\tools\scripts\daily_sync.py""", 0, False
