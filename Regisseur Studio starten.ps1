# Start de Regisseur Studio: opent de Minecraft Launcher, wacht tot de studio in de game bereikbaar is
# en opent hem dan in de browser. Start in de launcher zelf het profiel "Regisseur 26.2".
$url = 'http://127.0.0.1:47800/'
function Test-Studio { try { Invoke-RestMethod "${url}ping" -TimeoutSec 2 | Out-Null; return $true } catch { return $false } }

if (Test-Studio) { Start-Process $url; exit }

Add-Type -AssemblyName System.Windows.Forms
Start-Process "shell:AppsFolder\Microsoft.4297127D64EC6_8wekyb3d8bbwe!Minecraft"
Write-Host ""
Write-Host "  Regisseur Studio" -ForegroundColor Yellow
Write-Host "  1. Kies in de launcher het profiel 'Regisseur 26.2' en druk op Spelen."
Write-Host "  2. Dit venster wacht en opent de studio vanzelf in je browser zodra de game klaar is."
Write-Host ""
$deadline = (Get-Date).AddMinutes(10)
while ((Get-Date) -lt $deadline) {
  if (Test-Studio) { Write-Host "  Studio gevonden, browser wordt geopend." -ForegroundColor Green; Start-Process $url; Start-Sleep -Seconds 2; exit }
  Write-Host -NoNewline "."
  Start-Sleep -Seconds 3
}
[System.Windows.Forms.MessageBox]::Show("De studio werd niet gevonden. Start in de launcher het profiel 'Regisseur 26.2' en probeer opnieuw.", "Regisseur Studio") | Out-Null
