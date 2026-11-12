# Meet de opnamesnelheid in de dev-testgame (poort 47801) met een gekozen encoder voor de ruwe opname.
# Gebruik:  .\speedtest.ps1 -Encoder qsv|x264 -Tp "x y z"
# Sluit eerst elke andere Minecraft: twee games delen de grafische chip en maken de meting waardeloos.
param([string]$Encoder = "qsv", [string]$Tp = "", [int]$Port = 47801)
$u = "http://127.0.0.1:$Port"
$cfgPath = "$PSScriptRoot\run\studio\config.json"
$j = Get-Content $cfgPath -Raw | ConvertFrom-Json; $j.takeEncoder = $Encoder
[IO.File]::WriteAllText($cfgPath, ($j | ConvertTo-Json), (New-Object Text.UTF8Encoding $false))
$deadline = (Get-Date).AddMinutes(8); $r = $null
while ((Get-Date) -lt $deadline) { try { $r = Invoke-RestMethod "$u/ping" -TimeoutSec 3; if ($r.inWorld) { break } } catch {}; Start-Sleep -Seconds 4 }
if (-not $r -or -not $r.inWorld) { "testgame niet bereikbaar"; exit 1 }
if ($Tp) { Invoke-RestMethod "$u/cmd" -Method Post -Body "tp @s $Tp" | Out-Null; Start-Sleep -Seconds 15 }
"rust-fps van de game: " + (Invoke-RestMethod "$u/ping").fps
$p = Invoke-RestMethod "$u/api/project"
$p.schematic = 'demo_wachttoren.nbt'; $p.timing.intro = 1; $p.timing.terraform = 3; $p.timing.build = 5; $p.timing.outro = 2; $p.music.file = 'testtoon.wav'
$sw = [Diagnostics.Stopwatch]::StartNew(); $t0 = $null; $t1 = $null
Invoke-RestMethod "$u/api/show/start" -Method Post -Body ([Text.Encoding]::UTF8.GetBytes(($p | ConvertTo-Json -Depth 6))) | Out-Null
do {
  Start-Sleep -Milliseconds 500; $s = Invoke-RestMethod "$u/api/status"
  if ($s.phase -eq 'RECORDING' -and $s.frame -gt 0 -and -not $t0) { $t0 = $sw.Elapsed.TotalSeconds; $f0 = $s.frame }
  if ($s.phase -eq 'RECORDING') { $t1 = $sw.Elapsed.TotalSeconds; $f1 = $s.frame; $last = $s }
} while ($s.phase -in 'PREPARING', 'RECORDING' -and $sw.Elapsed.TotalMinutes -lt 8)
"encoder $Encoder -> fase $($s.phase) | $($s.message)"
if ($t0 -and $t1 -gt $t0) {
  "opname: {0:N1} fps ({1} frames in {2:N0} s)" -f (($f1 - $f0) / ($t1 - $t0)), ($f1 - $f0), ($t1 - $t0)
  "per frame: uitlezen {0:N1} ms, versturen {1:N1} ms | wachtframes: buffer vol {2}, chunks {3}" -f ($last.copyMs / [Math]::Max(1, $f1)), ($last.writeMs / [Math]::Max(1, $f1)), $last.holdBusy, $last.holdSections
}
# wachten tot de nabewerking klaar is, zodat een volgende test kan starten
$dl = (Get-Date).AddMinutes(8); do { Start-Sleep -Seconds 4; $s = Invoke-RestMethod "$u/api/status" } while ($s.phase -in 'FINISHING', 'RENDERING' -and (Get-Date) -lt $dl)
"einde: $($s.phase) | $($s.message) | $($s.verify)"
($s.judge -split "`n") | Select-String 'C2|RESULTAAT'
