# Volledige testronde in de dev-testgame: starten, plek op het land zoeken, show opnemen, judges, beeldstrook.
# Gebruik:  .\quicktest.ps1 [-Style square|quarry|flatten] [-Sites "x y z","x y z"] [-Sheet naam]
# Uitvoer: status + judges in de console, beeldstrook in ..\work\frames\<naam>.png
param([string]$Style = "square", [string[]]$Sites = @("-1600 120 1300", "-1900 120 900", "-800 120 1500", "-1200 120 1700"),
      [string]$Sheet = "test", [double]$Terra = 7, [double]$Build = 6, [string]$Schem = "demo_wachttoren.nbt", [int]$WaitMin = 14, [string]$Music = "Regisseur Beat.wav")
$env:Path = [Environment]::GetEnvironmentVariable('Path','Machine') + ';' + [Environment]::GetEnvironmentVariable('Path','User')
$u = 'http://127.0.0.1:47801'
Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -match 'regisseur\.port=47801' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -Confirm:$false }
Start-Process -FilePath "$PSScriptRoot\gradlew.bat" -ArgumentList "runClient", "--console=plain" -WorkingDirectory $PSScriptRoot `
  -RedirectStandardOutput "$PSScriptRoot\run\devclient.log" -RedirectStandardError "$PSScriptRoot\run\devclient.err" -WindowStyle Hidden | Out-Null
$r = $null; $deadline = (Get-Date).AddMinutes(9)
while ((Get-Date) -lt $deadline) { try { $r = Invoke-RestMethod "$u/ping" -TimeoutSec 3; if ($r.inWorld) { break } } catch {}; Start-Sleep -Seconds 5 }
if (-not $r.inWorld) { "testgame niet klaar"; exit 1 }
"testgame $($r.version)"
$p = Invoke-RestMethod "$u/api/project"
$p.schematic = $Schem; $p.timing.intro = 1.5; $p.timing.terraform = $Terra; $p.timing.build = $Build; $p.timing.outro = 3
$p.music.file = $Music
$p | Add-Member -NotePropertyName terraform -NotePropertyValue ([pscustomobject]@{ style = $Style; depth = 24; length = 2.5 }) -Force
$p.captions = @(@{start = 0.2; end = 1.5; text = 'Watch this spot' }, @{start = 9; end = 12; text = 'Block by block' }, @{start = 19; end = 22; text = 'Would you build this?' })
$body = [Text.Encoding]::UTF8.GetBytes(($p | ConvertTo-Json -Depth 6))
foreach ($site in $Sites) {
  Invoke-RestMethod "$u/cmd" -Method Post -Body "tp @s $site 0 20" | Out-Null; Start-Sleep -Seconds 15
  Invoke-RestMethod "$u/api/show/start" -Method Post -Body $body | Out-Null
  do { Start-Sleep -Seconds 2; $s = Invoke-RestMethod "$u/api/status" } while ($s.phase -eq 'PREPARING')
  "plek ${site}: $($s.phase) | $($s.message)"
  if ($s.phase -eq 'RECORDING') { break }
}
$sw = [Diagnostics.Stopwatch]::StartNew()
do { Start-Sleep -Seconds 5; $s = Invoke-RestMethod "$u/api/status" } while ($s.phase -in 'RECORDING', 'FINISHING', 'RENDERING' -and $sw.Elapsed.TotalMinutes -lt $WaitMin)
"klaar na {0:N0} s: $($s.phase) | $($s.message)" -f $sw.Elapsed.TotalSeconds
"controle: $($s.verify)"
($s.judge -split "`n") | Where-Object { $_ -match 'PASS|FAIL|SKIP|RESULTAAT|RITME|GELUID' }
$d = (Get-ChildItem "$PSScriptRoot\run\studio\renders" -Directory | Sort-Object Name | Select-Object -Last 1).FullName
$sj = Get-Content "$d\show.json" -Raw | ConvertFrom-Json
"shots: " + (($sj.shots | ForEach-Object { "$($_.type) $([math]::Round($_.start,1))-$([math]::Round($_.end,1))" }) -join ' | ')
$total = $sj.total; $n = 14; $fr = "$PSScriptRoot\..\work\frames"; New-Item -ItemType Directory -Force $fr | Out-Null
for ($i = 0; $i -lt $n; $i++) { $t = [math]::Round(0.3 + ($total - 0.6) * $i / ($n - 1), 2); ffmpeg -hide_banner -loglevel error -y -ss $t -i "$d\take.mp4" -frames:v 1 -vf scale=200:-1 "$fr\s_$i.png" }
$in = (0..($n - 1) | ForEach-Object { "-i"; "$fr\s_$_.png" })
ffmpeg -hide_banner -loglevel error -y @in -filter_complex "hstack=inputs=$n" "$fr\$Sheet.png"
"beeldstrook: $fr\$Sheet.png (tijden 0,3 s tot $([math]::Round($total-0.3,1)) s)"
Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -match 'regisseur\.port=47801' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -Confirm:$false }
