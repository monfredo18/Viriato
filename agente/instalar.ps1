<#
  Instala o agente do Viriato neste computador (Windows) para o usuário atual e o deixa iniciar sozinho ao entrar no Windows.
  Uso (PowerShell, sem precisar de administrador):
    .\instalar.ps1 -Servidor https://viriato.suaempresa.com.br -Codigo ABCD-2345
  O código é gerado no Viriato, em "Certificados e agente" > "Ligar este computador" (vale por 10 minutos e só funciona uma vez).
  Requisito: Java 21 ou mais novo instalado (comando "java -version").
#>
param(
  [Parameter(Mandatory = $true)][string]$Servidor,
  [Parameter(Mandatory = $true)][string]$Codigo,
  [string]$Jar = (Join-Path $PSScriptRoot 'viriato-agente.jar')
)
$ErrorActionPreference = 'Stop'

$java = Get-Command java.exe -ErrorAction SilentlyContinue
$javaw = Get-Command javaw.exe -ErrorAction SilentlyContinue
if (-not $java -or -not $javaw) { throw 'Java não encontrado. Instale o Java 21 (ou mais novo) e tente de novo.' }
$versao = (& $java.Source -version 2>&1 | Select-Object -First 1) -replace '.*"(\d+).*', '$1'
if ([int]$versao -lt 21) { throw "O agente precisa de Java 21 ou mais novo (encontrado: $versao)." }
if (-not (Test-Path $Jar)) { throw "Arquivo não encontrado: $Jar" }

$pasta = Join-Path $env:LOCALAPPDATA 'ViriatoAgente'
New-Item -ItemType Directory -Force $pasta | Out-Null
$destino = Join-Path $pasta 'viriato-agente.jar'
Copy-Item $Jar $destino -Force

Write-Host 'Ligando este computador ao Viriato...'
& $java.Source -jar $destino parear $Servidor $Codigo
if ($LASTEXITCODE -ne 0) { throw 'Não foi possível ligar ao Viriato. Gere um novo código e tente de novo.' }

$acao = New-ScheduledTaskAction -Execute $javaw.Source -Argument "-jar `"$destino`" executar"
$gatilho = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
$config = New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 5 -RestartInterval (New-TimeSpan -Minutes 1) -StartWhenAvailable -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
Register-ScheduledTask -TaskName 'Agente do Viriato' -Action $acao -Trigger $gatilho -Settings $config -Force | Out-Null
Start-ScheduledTask -TaskName 'Agente do Viriato'
Write-Host 'Agente instalado e em execução. Para ver o que ele enxerga: java -jar "' $destino '" certificados'
