# Registers "Karaoke Sync Retry": push-only daily_sync.py at 05:30 and 07:30 (after the 03:00 run), hidden, S4U like the daily sync.
# Needs an ADMINISTRATOR PowerShell:
#   powershell -ExecutionPolicy Bypass -File D:\ai\setupbox\tools\scripts\register_sync_retry_task.ps1
$ErrorActionPreference = 'Stop'
$name = 'Karaoke Sync Retry'
$user = "$env:USERDOMAIN\$env:USERNAME"
$action = New-ScheduledTaskAction -Execute 'wscript.exe' -Argument '"D:\ai\setupbox\tools\scripts\daily_sync_retry_hidden.vbs"'
$triggers = @((New-ScheduledTaskTrigger -Daily -At '05:30'), (New-ScheduledTaskTrigger -Daily -At '07:30'))
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -ExecutionTimeLimit (New-TimeSpan -Hours 2) -MultipleInstances IgnoreNew
$principal = New-ScheduledTaskPrincipal -UserId $user -LogonType S4U -RunLevel Limited
Register-ScheduledTask -TaskName $name -Action $action -Trigger $triggers -Settings $settings -Principal $principal -Force | Out-Null
Get-ScheduledTask -TaskName $name | Select-Object TaskName, State, @{n='LogonType';e={$_.Principal.LogonType}} | Format-List
(Get-ScheduledTask -TaskName $name).Triggers | ForEach-Object { $_.StartBoundary }
Write-Host 'Done.' -ForegroundColor Green
