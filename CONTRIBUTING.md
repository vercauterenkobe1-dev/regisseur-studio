# Meewerken aan Regisseur Studio

Welkom! Deze pagina zegt hoe je het project op je eigen pc aan de praat krijgt en hoe je een wijziging indient.
Lees eerst [studio/WEGWIJZER.md](studio/WEGWIJZER.md): daar staat wat elk bestand doet.

## Wat je nodig hebt

| Wat | Versie | Waarvoor |
|---|---|---|
| Java JDK | 25 | de mod bouwen |
| Minecraft | 26.2 met Fabric Loader | de mod draaien (de testgame start ook zonder launcher, zie hieronder) |
| Python | 3.12 | de pipeline (eindrender en controles) |
| Python-pakketten | `numpy`, `pillow` | `python -m pip install --user numpy pillow` |
| FFmpeg | 7 of nieuwer, met libass | video maken; met Intel QuickSync (`h264_qsv`) of anders encoder `x264` |

## Bouwen en testen

```
cd studio/mod
gradlew.bat build            # de mod: studio/mod/build/libs/regisseur-<versie>.jar
gradlew.bat runClient        # testgame (eigen wereld, poort 47801), studio op http://127.0.0.1:47801/
```

De testgame heeft een eigen map `studio/mod/run/` (staat niet in git). Zet daar in `run/studio/schematics/` een
bouwwerk (bv. `studio/examples/demo_wachttoren.nbt`) en in `run/studio/music/` een muziekbestand
(`python studio/pipeline/synth.py --plain "run/studio/music/Regisseur Beat.wav"` maakt er een).

Volledige proefopname met alle controles (ongeveer 15 minuten):

```
cd studio/mod
.\quicktest.ps1 -Sheet mijntest
```

Een eigen video maken met een projectbestand (zie `studio/examples/projecten/`):

```
.\maakvideo.ps1 -Project ..\examples\projecten\kathedraal.json -Naam "Mijn video" -Sites "x y z","x y z"
```

Een gedownload bouwwerk met ondergrond en rommel eromheen? Knip het eerst bij:

```
python studio/pipeline/schem_trim.py download.schem alleen_het_gebouw.schem
```

## De controles (heel belangrijk)

Na elke opname draaien drie controles automatisch. Een wijziging is pas klaar als alles groen is:

- `pipeline/judge.py`: beeld en geluid (formaat, kwaliteit/VMAF, luidheid, captions en teller in de veilige zone)
- `pipeline/sync_check.py`: ritme en camera (cuts op de maat, laag per laag, tempo, geen glitches)
- `pipeline/audio_check.py`: geluid (geluid volgt beeld, drop op de bouw, geen klikken)

**Pas nooit een grens aan omdat een test faalt.** Vind de oorzaak en los die op. Wil je een regel echt wijzigen
(omdat hij iets verkeerd meet), leg dan in je pull request uit waarom, met metingen.

## Een wijziging indienen

1. Maak een eigen branch: `git checkout -b korte-beschrijving`.
2. Hou een wijziging klein en over één onderwerp.
3. Draai een proefopname (`quicktest.ps1`) en zet de uitvoer van de controles in je pull request.
4. Beschrijf in gewone woorden wat er voor de kijker verandert, en voeg een paar beelden toe.
5. Werk `studio/WEGWIJZER.md` bij als je bestanden toevoegt of verplaatst.

## Afspraken in de code

- Commentaar en meldingen in het Nederlands, kort en zonder jargon. Captions in de video zijn Engels.
- Alles in de video hangt af van **videotijd** (frame / 60), nooit van de klok: zo blijft de opname frame-exact.
- De opname mag trager, de video mag nooit glitchen: liever wachten dan een onaf frame opnemen.
- Minecraft-API onbekend? Kijk het na met `javap` (uitleg in `studio/CLAUDE.md`), niet gokken.

## Bekende open punten

- Heel grote bouwwerken (zoals de kathedraal, 546.000 blokken): het landschap rondom vooraf aanmaken gaat nog
  traag (één chunk tegelijk). Parallel maken via `getChunkFuture` is de volgende stap (`show/Landschap.java`).
- De glitch-teller G1 telt ook verre chunks die aan de rand van de zichtafstand inladen; die zijn niet altijd zichtbaar.
