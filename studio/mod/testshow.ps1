# Automatische testopname in de dev-testgame (poort 47801).
# Gebruik:  .\testshow.ps1 [-Tp "x y z"] [-Intro 1] [-Terra 3] [-Build 5] [-Outro 2] [-Schematic demo_wachttoren.nbt]
# Start eerst de testgame:  .\gradlew.bat runClient   (op de achtergrond)
param([string]$Tp = "", [double]$Intro = 1, [double]$Terra = 3, [double]$Build = 5, [double]$Outro = 2,
      [string]$Schematic = "demo_wachttoren.nbt", [int]$Port = 47801, [int]$WaitMin = 12)
$u = "http://127.0.0.1:$Port"
$deadline = (Get-Date).AddMinutes(8); $r = $null
while ((Get-Date) -lt $deadline) { try { $r = Invoke-RestMethod "$u/ping" -TimeoutSec 3; if ($r.inWorld) { break } } catch {}; Start-Sleep -Seconds 4 }
if (-not $r -or -not $r.inWorld) { "testgame niet bereikbaar"; exit 1 }
if ($Tp) { Invoke-RestMethod "$u/cmd" -Method Post -Body "tp @s $Tp" | Out-Null; Start-Sleep -Seconds 12 }
"start op: " + ((Invoke-RestMethod "$u/state") | ConvertTo-Json -Compress)
$p = Invoke-RestMethod "$u/api/project"
$p.schematic = $Schematic; $p.timing.intro = $Intro; $p.timing.terraform = $Terra; $p.timing.build = $Build; $p.timing.outro = $Outro
$p.music.file = 'testtoon.wav'
$p.captions = @(@{start = 0.2; end = 1.6; text = 'Kijk wat hier gebeurt' }, @{start = 4.5; end = 8; text = 'Blok voor blok omhoog' })
$sw = [Diagnostics.Stopwatch]::StartNew()
(Invoke-RestMethod "$u/api/show/start" -Method Post -Body ([Text.Encoding]::UTF8.GetBytes(($p | ConvertTo-Json -Depth 6)))) | ConvertTo-Json -Compress
$deadline = (Get-Date).AddMinutes($WaitMin); $lastPhase = ''; $recSec = 0; $n = 0
while ((Get-Date) -lt $deadline) {
  Start-Sleep -Seconds 3
  try { $s = Invoke-RestMethod "$u/api/status" -TimeoutSec 5 } catch { "status fout: $($_.Exception.Message)"; break }
  if ($s.phase -eq 'RECORDING') { $recSec = $sw.Elapsed.TotalSeconds }
  $n++
  if ($s.phase -ne $lastPhase -or $n % 10 -eq 0) { "{0,-10} {1}/{2} opnamefps={3:N1} gamefps={4} wacht={5} | {6}" -f $s.phase, $s.frame, $s.totalFrames, $s.fps, $s.gameFps, $s.heldFrames, $s.message; $lastPhase = $s.phase }
  if ($s.phase -in 'DONE', 'ERROR') { break }
}
"--- opnameduur: {0:N0} s voor {1} frames = {2:N1} fps gemiddeld" -f $recSec, $s.totalFrames, ($s.totalFrames / [Math]::Max(1, $recSec))
"--- controle: $($s.verify)"
"--- output: $($s.output)"
$s.judge
