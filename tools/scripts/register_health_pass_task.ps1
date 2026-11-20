# Registers the "Karaoke Health Pass" task: runs health_pass.py every day at 09:00 (after the 03:00 sync), hidden,
# whether or not the user is logged on (S4U, no password stored, same as the daily sync). Needs an ADMINISTRATOR PowerShell:
#   powershell -ExecutionPolicy Bypass -File D:\ai\setupbox\tools\scripts\register_health_pass_task.ps1
$ErrorActionPreference = 'Stop'
$name = 'Karaoke Health Pass'
$user = "$env:USERDOMAIN\$env:USERNAME"
$action = New-ScheduledTaskAction -Execute 'wscript.exe' -Argument '"D:\ai\setupbox\tools\scripts\health_pass_hidden.vbs"'
$trigger = New-ScheduledTaskTrigger -Daily -At '09:00'
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -ExecutionTimeLimit (New-TimeSpan -Minutes 10) -MultipleInstances IgnoreNew
$principal = New-ScheduledTaskPrincipal -UserId $user -LogonType S4U -RunLevel Limited
Register-ScheduledTask -TaskName $name -Action $action -Trigger $trigger -Settings $settings -Principal $principal -Force | Out-Null
Get-ScheduledTask -TaskName $name | Select-Object TaskName, State, @{n='LogonType';e={$_.Principal.LogonType}} | Format-List
(Get-ScheduledTaskInfo -TaskName $name).NextRunTime
Write-Host 'Done. Test now with:  Start-ScheduledTask -TaskName "Karaoke Health Pass"  then read D:\ai\setupbox\logs\health_pass.txt' -ForegroundColor Green
