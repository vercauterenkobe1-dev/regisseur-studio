# Regisseur Studio

Een eigen Minecraft-mod (Fabric, Minecraft 26.2) plus een Python-pipeline die automatisch YouTube Shorts maakt:
terraform, een bouwwerk dat blok per blok verschijnt, filmische camerashots, een materiaalteller, echte
Minecraft-geluiden en muziek die op het ritme van de bouw gemonteerd is. Alles wordt frame-exact opgenomen
in 1080x1920, 60 fps, en afgewerkt tot een MP4 die overal afspeelt.

## Wat zit waar

| Map | Inhoud |
|---|---|
| `studio/mod/` | De Fabric-mod (Java 25, Loom). Regisseur, opname, terraform, bouw, studio-webapp (`src/client/resources/assets/regisseur/ui`). |
| `studio/pipeline/` | Python: eindrender (`render.py`), teller (`counter.py`), geluidseffecten (`sfx.py`), Regisseur Beat (`synth.py`), tempodetectie (`beats.py`) en de controles (`judge.py`, `sync_check.py`, `audio_check.py`). |
| `studio/docs/` | Onderzoek (`STUDIE.md`) en het verbeterrapport (`RAPPORT-DYNAMIEK.md`). |
| `studio/examples/` | Voorbeeld-schematic (wachttoren). |
| `studio/CLAUDE.md` | Technische werknotities per sessie. |
| `Regisseur Studio starten.ps1` | Start de launcher en opent de studio in de browser zodra de game klaar is. |

## Nodig

- Minecraft 26.2 met Fabric Loader, plus Iris en Sodium (optioneel, voor shaders)
- Java 25 (JDK) om de mod te bouwen
- Python 3.12 met `numpy` en `pillow` (`python -m pip install --user numpy pillow`)
- FFmpeg (met `h264_qsv` voor Intel QuickSync, of gebruik de encoder `x264`)

## Bouwen

```
cd studio/mod
gradlew.bat build
```

De mod staat daarna in `studio/mod/build/libs/`. Zet die in de `mods`-map van je Minecraft-profiel en kopieer
`studio/pipeline` naar `<profiel>/regisseur/studio/pipeline`.

## Gebruik

Start het profiel, open `http://127.0.0.1:47800/` (of de snelkoppeling), kies een schematic en muziek,
en druk op opnemen. De video komt in de exportmap uit `config.json`.

## Controles

Elke opname wordt automatisch gecontroleerd: beeldkwaliteit (VMAF), luidheid, captions en teller in de veilige zone,
afspeelbaarheid, ritme (cuts en finale op de maat, laag per laag, geen stoten, ease), camera nooit in een blok,
en geluid (geluid volgt beeld, drop op de bouw, geen klikken).
