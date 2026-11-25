<# Remove o agente deste computador. Depois, desligue-o também no Viriato (Certificados e agente > Desligar). #>
$ErrorActionPreference = 'SilentlyContinue'
Stop-ScheduledTask -TaskName 'Agente do Viriato'
Unregister-ScheduledTask -TaskName 'Agente do Viriato' -Confirm:$false
Get-CimInstance Win32_Process -Filter "Name = 'javaw.exe'" | Where-Object { $_.CommandLine -like '*viriato-agente.jar*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
Remove-Item -Recurse -Force (Join-Path $env:LOCALAPPDATA 'ViriatoAgente')
Remove-Item -Recurse -Force (Join-Path $env:APPDATA 'ViriatoAgente')
Write-Host 'Agente removido deste computador.'
