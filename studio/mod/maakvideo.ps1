# Maakt een echte video in de testgame met een projectbestand, en kopieert het resultaat naar het bureaublad.
# Gebruik:  .\maakvideo.ps1 -Project run\studio\projects\kathedraal.json -Sites "x y z","x y z" -Naam "Kathedraal"
# Probeert de plekken na elkaar tot er een geschikt is (geen water, niet te hoog). Laat het testgame-venster open!
param([Parameter(Mandatory)][string]$Project, [string[]]$Sites, [string]$Naam = "video", [int]$WaitMin = 240)
$env:Path = [Environment]::GetEnvironmentVariable('Path','Machine') + ';' + [Environment]::GetEnvironmentVariable('Path','User')
$u = 'http://127.0.0.1:47801'
$r = $null; try { $r = Invoke-RestMethod "$u/ping" -TimeoutSec 3 } catch {}
$mine = @()
if (-not $r.inWorld) { # testgame starten (alleen als er nog geen draait)
  Start-Process -FilePath "$PSScriptRoot\gradlew.bat" -ArgumentList "runClient", "--console=plain" -WorkingDirectory $PSScriptRoot `
    -RedirectStandardOutput "$PSScriptRoot\run\devclient.log" -RedirectStandardError "$PSScriptRoot\run\devclient.err" -WindowStyle Hidden | Out-Null
  $deadline = (Get-Date).AddMinutes(9)
  while ((Get-Date) -lt $deadline) { try { $r = Invoke-RestMethod "$u/ping" -TimeoutSec 3; if ($r.inWorld) { break } } catch {}; Start-Sleep -Seconds 5 }
  if (-not $r.inWorld) { "testgame niet klaar"; exit 1 }
  $mine = Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -match 'regisseur\.port=47801' } | Select-Object -ExpandProperty ProcessId
}
"testgame $($r.version)"
$body = [IO.File]::ReadAllBytes((Resolve-Path $Project))
$ok = $false
foreach ($site in $Sites) {
  Invoke-RestMethod "$u/cmd" -Method Post -Body "tp @s $site 0 20" | Out-Null; Start-Sleep -Seconds 20
  Invoke-RestMethod "$u/api/show/start" -Method Post -Body $body | Out-Null
  do { Start-Sleep -Seconds 3; $s = Invoke-RestMethod "$u/api/status" } while ($s.phase -eq 'PREPARING')
  "plek ${site}: $($s.phase) | $($s.message)"
  if ($s.phase -eq 'RECORDING') { $ok = $true; break }
}
if (-not $ok) { "geen geschikte plek gevonden"; exit 1 }
$sw = [Diagnostics.Stopwatch]::StartNew(); $last = ''
do {
  Start-Sleep -Seconds 20
  try { $s = Invoke-RestMethod "$u/api/status" -TimeoutSec 10 } catch { "testgame niet bereikbaar"; exit 1 }
  $line = "{0,4:N0} min | {1} | frame {2}/{3} | nog ~{4} min | onaf {5} | wacht server/beeld/chunks {6}/{7}/{8} | {9}" -f $sw.Elapsed.TotalMinutes, $s.phase, $s.frame, $s.totalFrames, [math]::Round($s.etaSec / 60), $s.unsettledFrames, $s.holdServer, $s.holdVisible, $s.holdSections, $s.waitReason
  if ($sw.Elapsed.TotalMinutes -ge 0 -and ($line.Substring(10) -ne $last)) { $line; $last = $line.Substring(10) }
} while ($s.phase -in 'RECORDING', 'FINISHING', 'RENDERING' -and $sw.Elapsed.TotalMinutes -lt $WaitMin)
"klaar na {0:N0} min: $($s.phase) | $($s.message)" -f $sw.Elapsed.TotalMinutes
"controle: $($s.verify)"
($s.judge -split "`n") | Where-Object { $_ -match 'PASS|FAIL|SKIP|RESULTAAT|RITME|GELUID' }
if ($s.output -and (Test-Path $s.output)) {
  $dst = Join-Path ([Environment]::GetFolderPath('Desktop')) "$Naam.mp4"
  Copy-Item $s.output $dst -Force; "op het bureaublad: $dst"
}
foreach ($id in $mine) { Stop-Process -Id $id -Force -Confirm:$false -ErrorAction SilentlyContinue }
