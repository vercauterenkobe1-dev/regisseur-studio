# Meet de opnamesnelheid met en zonder shader in de dev-testgame (met Iris + Sodium uit run\mods).
# Gebruik:  .\shadertest.ps1 -Pack "" | "MakeUp-UltraFast-9.6.zip" | "ComplementaryReimagined_r5.9.3.zip"  -Site "x y z" -Name label
param([string]$Pack = "", [string]$Site = "-2200 120 1300", [string]$Name = "geen")
$env:Path = [Environment]::GetEnvironmentVariable('Path','Machine') + ';' + [Environment]::GetEnvironmentVariable('Path','User')
$u = 'http://127.0.0.1:47801'
Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -match 'regisseur\.port=47801' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -Confirm:$false }
$props = if ($Pack) { "enableShaders=true`nshaderPack=$Pack`n" } else { "enableShaders=false`n" }
[IO.File]::WriteAllText("$PSScriptRoot\run\config\iris.properties", $props)
Start-Process -FilePath "$PSScriptRoot\gradlew.bat" -ArgumentList "runClient", "--console=plain" -WorkingDirectory $PSScriptRoot `
  -RedirectStandardOutput "$PSScriptRoot\run\devclient.log" -RedirectStandardError "$PSScriptRoot\run\devclient.err" -WindowStyle Hidden | Out-Null
$r = $null; $deadline = (Get-Date).AddMinutes(9)
while ((Get-Date) -lt $deadline) { try { $r = Invoke-RestMethod "$u/ping" -TimeoutSec 3; if ($r.inWorld) { break } } catch {}; Start-Sleep -Seconds 5 }
if (-not $r.inWorld) { "testgame niet klaar"; exit 1 }
Invoke-RestMethod "$u/cmd" -Method Post -Body "tp @s $Site 0 20" | Out-Null; Start-Sleep -Seconds 20
$idle = (Invoke-RestMethod "$u/ping").fps
$p = Invoke-RestMethod "$u/api/project"
$p.schematic = 'demo_wachttoren.nbt'; $p.timing.intro = 1; $p.timing.terraform = 3; $p.timing.build = 4; $p.timing.outro = 2; $p.music.file = ''
$p | Add-Member -NotePropertyName terraform -NotePropertyValue ([pscustomobject]@{ style = 'square'; depth = 16; length = 2.5 }) -Force
Invoke-RestMethod "$u/api/show/start" -Method Post -Body ([Text.Encoding]::UTF8.GetBytes(($p | ConvertTo-Json -Depth 6))) | Out-Null
$sw = [Diagnostics.Stopwatch]::StartNew(); $t0 = $null
do { Start-Sleep -Milliseconds 700; try { $s = Invoke-RestMethod "$u/api/status" -TimeoutSec 5 } catch { continue }
  if ($s.phase -eq 'RECORDING' -and $s.frame -gt 0 -and -not $t0) { $t0 = $sw.Elapsed.TotalSeconds; $f0 = $s.frame }
  if ($s.phase -eq 'RECORDING') { $t1 = $sw.Elapsed.TotalSeconds; $f1 = $s.frame } } while ($s.phase -in 'PREPARING', 'RECORDING' -and $sw.Elapsed.TotalMinutes -lt 12)
$fps = ($f1 - $f0) / [Math]::Max(1, $t1 - $t0)
"[{0}] rust {1} fps | opname {2:N1} fps ({3} frames in {4:N0} s) | {5} | camInBlock {6}" -f $Name, $idle, $fps, ($f1 - $f0), ($t1 - $t0), $s.phase, $s.camInBlock
Start-Sleep -Seconds 10
$d = (Get-ChildItem "$PSScriptRoot\run\studio\renders" -Directory | Sort-Object Name | Select-Object -Last 1).FullName
$fr = "$PSScriptRoot\..\work\frames"
$i = 0; foreach ($t in 1.5, 4.5, 8.5) { $i++; ffmpeg -hide_banner -loglevel error -y -ss $t -i "$d\take.mp4" -frames:v 1 -vf scale=240:-1 "$fr\sh_${Name}_$i.png" }
ffmpeg -hide_banner -loglevel error -y -i "$fr\sh_${Name}_1.png" -i "$fr\sh_${Name}_2.png" -i "$fr\sh_${Name}_3.png" -filter_complex hstack=inputs=3 "$fr\shader_$Name.png"
Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -match 'regisseur\.port=47801' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -Confirm:$false }
