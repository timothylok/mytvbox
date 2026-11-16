# Re-registers the "Karaoke Daily Sync" task to run whether or not the user is logged on (S4U: no password stored).
# Needs an ADMINISTRATOR PowerShell. Run:  powershell -ExecutionPolicy Bypass -File D:\ai\setupbox\tools\scripts\register_daily_sync_task.ps1
$ErrorActionPreference = 'Stop'
$name = 'Karaoke Daily Sync'
$user = "$env:USERDOMAIN\$env:USERNAME"
$probe = 'D:\ai\setupbox\logs\s4u_probe.txt'

# 1. probe: does the non-interactive session see ffmpeg, python and the box?
Remove-Item $probe -ErrorAction SilentlyContinue
$cmd = '/c (whoami & where ffmpeg & where ffprobe & "C:\Program Files\Python312\python.exe" --version & D:\ai\setupbox\tools\sdk\platform-tools\adb.exe connect 192.168.3.79:5555 & D:\ai\setupbox\tools\sdk\platform-tools\adb.exe -s 192.168.3.79:5555 shell echo adb-ok) > ' + $probe + ' 2>&1'
$p = New-ScheduledTaskPrincipal -UserId $user -LogonType S4U -RunLevel Limited
Register-ScheduledTask -TaskName 'zz_s4u_probe' -Action (New-ScheduledTaskAction -Execute 'cmd.exe' -Argument $cmd) -Principal $p | Out-Null
Start-ScheduledTask -TaskName 'zz_s4u_probe'
Start-Sleep 15
Unregister-ScheduledTask -TaskName 'zz_s4u_probe' -Confirm:$false
Get-Content $probe
$txt = Get-Content $probe -Raw
if ($txt -notmatch 'ffmpeg' -or $txt -notmatch 'ffprobe' -or $txt -notmatch 'Python 3' -or $txt -notmatch 'adb-ok') {
    Write-Host 'PROBE FAILED: the background session is missing something (see above). The existing task was NOT changed.' -ForegroundColor Red
    exit 1
}

# 2. switch the real task to S4U, keeping its action, trigger and settings
$t = Get-ScheduledTask -TaskName $name
Set-ScheduledTask -TaskName $name -Principal $p | Out-Null
Get-ScheduledTask -TaskName $name | Select-Object TaskName, State, @{n='LogonType';e={$_.Principal.LogonType}} | Format-List
(Get-ScheduledTaskInfo -TaskName $name).NextRunTime
Write-Host 'Done: the task now runs at 03:00 whether or not you are logged on.' -ForegroundColor Green
