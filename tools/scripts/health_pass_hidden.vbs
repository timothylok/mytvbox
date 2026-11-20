' Runs health_pass.py with no visible console window (used by Task Scheduler, task "Karaoke Health Pass").
' Report: D:\ai\setupbox\logs\health_pass.txt (first line OK or PROBLEMS), history: logs\health_history.log
CreateObject("WScript.Shell").Run """C:\Program Files\Python312\python.exe"" ""D:\ai\setupbox\tools\scripts\health_pass.py""", 0, False
