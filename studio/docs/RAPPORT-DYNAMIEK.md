# Rapport: van "mooi maar saai" naar een Short die blijft plakken

Datum: 2026-11-11. Stand van de code: mod 0.8.0, pipeline met teller en teaser-hook.
Dit rapport is een werkopdracht voor een uitvoerend AI-model. Lees eerst `studio/CLAUDE.md`.
Voer **één werkpakket per sessie** uit, in de volgorde van hoofdstuk 7.

## 1. Doel

De video moet aanvoelen alsof een editor en een DJ hem met de hand hebben gemonteerd: tempo dat ademt,
een strakke teller bovenaan, echte Minecraft-geluiden bij elke actie, en muziek waarvan de "drop" valt op het
moment dat de bouw begint. Doel achter het doel: meer kijkers die tot het einde blijven en opnieuw kijken.

Vier klachten van de gebruiker, vier werkpakketten:

| # | Klacht | Werkpakket |
|---|---|---|
| WP1 | Alles loopt lineair, te weinig in- en uitlopen | Tempo en camera |
| WP2 | Teller staat te laag en is slordig geordend | Layout van de teller |
| WP3 | Geen geluidseffecten | Minecraft-geluiden op elke actie |
| WP4 | Muziek klinkt dun, niet "gemixt" op de bouw | DJ-montage en mastering |

## 2. Nulmeting (gemeten op opname `mod/run/studio/renders/20261111-183144`)

| Meting | Waarde nu | Wat het betekent |
|---|---|---|
| Mijn-tempo per 0,5 s (terraform) | 773, 716, 755, 614, 796, 950, 695, 769, 1529, 2037, 679 | Springt in één stap van 0 naar vol en blijft 4 s vlak: lineair |
| Bouw-tempo per 0,5 s | 10, 52, 75, 93, 111, 115, 117, 113, 104, 84, 55, 12 | Heeft al ease-in en ease-out, maar geen accenten op de beat |
| Camera in de ring-shot | hoek = `startAngle + orbitSpeed * t` | Constante draaisnelheid, 7,7 s lang |
| Teller-paneel | y 288 tot 748, getallen in 3 verschillende lettergroottes | Neemt 24% van de beeldhoogte, oogt ongelijk |
| Geluidseffecten | geen | Alleen muziek |
| Geluid totaal / onder 120 Hz / boven 4 kHz (RMS) | -16,7 / -20,7 / -29,3 dB | Testmuziek is een synthetische testbeat, geen echte track |
| Luidheid | -15,0 LUFS (doel -14) | Loudnorm botst op de piek, er zit geen limiter in |
| Bestaande 14 criteria (C1-C6, R1, R2, B1-B3, R4-R6) | allemaal groen | Moeten groen blijven |

## 3. WP1: tempo en camera (niet lineair)

**Probleem.** De terraform gebruikt overal lineaire tijd. De camera draait in de langste shot aan vaste snelheid.

**Ontwerp.** Eén regel: elke fase begint traag, versnelt, en landt op een maatstart. Gebruik tijd-vervorming
(een functie die voortgang 0..1 omzet naar 0..1), nooit losse vertragingen.

| Onderdeel | Nu | Wordt | Waar in de code |
|---|---|---|---|
| Sleuf rond het vierkant | `sqRingStart + sqRingDur * ringParam(dx,dz)` | `ringParam` door ease-in-out (kubisch): traag vertrekken, snel door de bochten, zacht sluiten | `ShowPlan.java` rond regel 546, en `ringHead(t)` moet dezelfde curve gebruiken (anders loopt het effect niet mee) |
| Uithollen | elke kolom zelfde `digDur` (0,30 / 0,40 s), start lineair verdeeld | starttijd per kolom door ease-in (kwadratisch): eerst een paar blokken, dan een lawine die eindigt op de maatstart | `ShowPlan.java` rond regel 558-567 |
| Gras komt omhoog | `sqRiseStart + sqRiseDur * (y - sqFloor - 1) / layers` | ease-out (kubisch): schiet omhoog en remt af tegen het oppervlak | `ShowPlan.java` regel 571, en de camera-/fx-code op regel 618-619 |
| Bouw | `easeBuild` (25% lineair + 75% smoothstep) | zelfde curve, plus een puls op elke tel: voortgang `x + a*sin(2*pi*beat)/(2*pi*N)` met `a` = 0,35 en `N` = aantal tellen in de bouw. Blijft stijgend zolang `a` < 1 | `ShowPlan.java` regel 100-110 (`easeBuild`, `easeInv`) |
| Ring-shot camera | vaste hoeksnelheid | hoek = integraal van een snelheid die per maat van 0,6x naar 1,5x en terug gaat (traag op de maatstart, snel ertussen) | `Director.java` regel 426 |
| Accent op de maatstart | geen | korte zoom-tik: FOV -3% op elke maatstart, uitsterven in 0,25 s. Alleen tijdens bouw en finale | `Director.java`, waar de FOV per frame wordt gezet |

**Valkuilen.**
- `easeInv` werkt met bisectie op `easeBuild`. Verander je `easeBuild`, dan moet de functie stijgend blijven.
- B2 (geen stoot) heeft een grens van 2x het gemiddelde per frame. Houd de puls-sterkte `a` laag genoeg.
- De secties eindigen op maatstarts (R1, R2). Vervorm alleen binnen een fase, verschuif nooit het begin of einde.
- De camera mag nooit in een blok zitten (R5). De clearance-arrays blijven gelden.

**Nieuwe judge-regels (toevoegen aan `pipeline/sync_check.py`).**
- **D1 terraform ademt:** verdeel de terraform in bakjes van 0,25 s. Het eerste en het laatste bakje van elke
  deelfase (sleuf, uithollen, omhoog) bevatten hoogstens 40% van het drukste bakje van die deelfase.
  Nulmeting: faalt (eerste bakje = 100%).
- **D2 camera ademt:** schrijf de camerapositie per frame in `show.json` (`camPath`). In de ring-shot is de
  variatiecoëfficiënt van de hoeksnelheid minstens 0,25. Nulmeting: 0.
- **D3 puls op de tel:** in de bouw is het gemiddelde tempo in de 100 ms na een tel minstens 1,3x het
  gemiddelde tempo in de 100 ms voor een tel. Nulmeting: ongeveer 1,0.

## 4. WP2: layout van de teller

**Probleem.** Het paneel begint op y = 288 en loopt tot 748. De getallen hebben verschillende groottes.
De volgorde loopt per rij (links, rechts, links, rechts), waardoor het oog zigzagt.

**Wat we weten over de veilige zone.** Er bestaat geen officiële maat van YouTube. Meetwebsites spreken
elkaar tegen: bovenmarge 120 tot 288 px, rechterkolom 48 tot 164 px, ondermarge 270 tot 672 px.
Keuze: bovenrand op 190 px, rechtergrens op x = 916. **De gebruiker controleert dit één keer op zijn telefoon
met een verborgen (unlisted) upload.** Zet de bovenrand als constante `TOP` bovenaan `counter.py`.

**Ontwerp (alles in `pipeline/counter.py`).**

| Eigenschap | Nu | Wordt |
|---|---|---|
| Rijen | midden op y 350, 518, 686 | midden op y 246, 366, 486 (afstand 120) |
| Icoon | 124 px | 100 px |
| Kolommen | icoon x 170 / 630, getal x 252 / 712 | icoon x 150 / 590, getal x 216 / 656 |
| Volgorde | per rij | per kolom: grootste linksboven, dan naar beneden, dan de rechterkolom |
| Lettergrootte | per getal anders | één grootte voor alle getallen, bepaald door het langste getal, maximaal 84 |
| Notatie | `8599` | `8,599` (duizendtal-komma, zoals in de hooktekst) |
| Leesbaarheid | zwarte rand | zwarte rand + zacht donker verloop achter het paneel (zwart, 0% naar 30% dekking, y 0 tot 600) getekend in `counter_panel.png` |
| Aantal iconen | altijd 6 | toon alleen blokken met minstens 1% van het totaal, minimaal 3, maximaal 6 |

Het paneel eindigt dan op y = 546 in plaats van 748: 200 px meer beeld voor de bouw.

**Nieuwe judge-regels (toevoegen aan `pipeline/judge.py`, met een nieuwe optie `--counter counter.ass --panel counter_panel.png`).**
- **U1 teller in de zone:** render `counter.ass` op zwart (zoals C4 dat doet voor de captions) en neem de
  omhullende rechthoek samen met de niet-doorzichtige pixels van de iconen. Die valt binnen x [90, 916], y [190, 560].
- **U2 één lettergrootte:** alle `\fs`-waarden in `counter.ass` zijn gelijk.
- C4 blijft alleen de captions meten.

**Testen zonder nieuwe opname.** Layout is puur pipeline. Her-render de bestaande opname:

```
cd mod/run/studio/renders/20261111-183144
python ../../../../../pipeline/render.py storyboard_t.json --out test_layout.mp4
```

## 5. WP3: geluidseffecten

**Bron.** Geen downloads nodig. De echte geluiden zitten in de game zelf en de mod kan ze uitlezen, op dezelfde
manier als de textures voor de teller. Nagekeken in de 26.2-jars:
- `state.getSoundType().getBreakSound()` en `.getPlaceSound()` geven een `SoundEvent`; `.location()` geeft de naam
  (bv. `minecraft:block.stone.break`).
- `assets/minecraft/sounds.json` (via `ResourceManager`) vertaalt die naam naar bestanden
  (bv. `dig/stone1` .. `dig/stone4` = `assets/minecraft/sounds/dig/stone1.ogg`).
- FFmpeg op deze pc kan Vorbis (.ogg) lezen.

Geluiden van Minecraft in een Minecraft-video gebruiken is gangbaar. De uitvoerder leest voor de zekerheid de
gebruiksrichtlijnen van Minecraft na en meldt het als daar iets anders staat.

**Mod: nieuw bestand `SfxData.java` (zelfde opzet als `CounterData.java`).** Schrijft na de opname `sfx.json`
en kopieert de .ogg-bestanden naar `sfx/` in de rendermap.

| Gebeurtenis | Geluid | Tijd uit |
|---|---|---|
| Blok gemijnd | breek-geluid van dat blok | `plan.mined` |
| Blok geplaatst | plaats-geluid van dat blok | `plan.buildTargets` |
| Gras komt omhoog | `block.grass.place` | ops van de rise-fase |
| Finale | `entity.firework_rocket.launch`, `.blast`, `.twinkle` | `plan.finaleT` |
| Teller bereikt eindstand | `entity.experience_orb.pickup` | laatste tijd per materiaal |

Uitdunnen in de mod: per geluidsgroep hoogstens één gebeurtenis per frame (1/60 s). Schrijf wel het echte aantal
mee (`n`), zodat de pipeline het volume kan schalen. 8.599 losse steen-tikken zijn onbruikbaar.

**Pipeline: nieuw bestand `pipeline/sfx.py`.**
1. Decodeer elk .ogg-bestand één keer naar 48 kHz float via een FFmpeg-pipe (`-f f32le`).
2. Meng met numpy (`pip install --user numpy`; ontbreekt het, sla SFX over met een melding, video gaat door).
3. Per gebeurtenis: willekeurige variant, toonhoogte ±8% (herbemonsteren), volume `min(1, 0,35 * sqrt(n))`.
4. Plafond van 24 tikken per seconde per groep. Daarboven: volume omhoog, niet meer tikken.
5. Tijdsverschuiving: gebruik dezelfde `shift` en `teaser` als de teller in `render.py`. De teaser krijgt de
   geluiden uit het laatste stuk van de opname.
6. Uitvoer `sfx.wav`. In `master_audio`: muziek zakt 3 dB onder de SFX (FFmpeg `sidechaincompress`,
   ratio 3, attack 5 ms, release 120 ms), daarna samen door de loudnorm.

**Nieuwe judge-regels (nieuw bestand `pipeline/sfx_check.py`, aangeroepen vanuit `PostProcess.java`).**
- **S1 geluid volgt beeld:** correlatie tussen de omhullende van `sfx.wav` (RMS per 50 ms) en de
  gebeurtenis-dichtheid uit `sfx.json` is minstens 0,7.
- **S2 balans:** `sfx.wav` is 4 tot 9 LU zachter dan de muziek-stem (meet beide met FFmpeg `ebur128`).
- **S3 op het frame:** voor 5 alleenstaande gebeurtenissen ligt de eerste piek in `sfx.wav` binnen 17 ms
  (1 frame) van de geplande tijd.
- C3 (luidheid, true peak) blijft gelden op het eindresultaat.

## 6. WP4: muziek als een DJ-set

**Eerlijk vooraf.** De pipeline kan een dunne track niet vol maken. De huidige testmuziek is een synthetische
testbeat. **Ontbrekend: 2 of 3 echte tracks**, door de gebruiker in de map `studio/music` gezet. Het model mag
geen muziek kiezen of downloaden waar auteursrecht op zit. Eisen aan een track: 120 tot 150 BPM, een duidelijke
opbouw gevolgd door een drop, minstens 30 s lang. De YouTube Audio Library is een veilige bron.

**Ontwerp: de video krijgt een muzikale boog.**

| Deel van de video | Muziek | Bewerking |
|---|---|---|
| Teaser (1 maat) | eerste maat van de drop | geen |
| Intro + sleuf + uithollen | de maten vóór de drop (opbouw) | hoogdoorlaatfilter dat opent van 600 Hz naar 20 Hz; in de laatste 2 maten een "riser" (ruis met stijgend filter, zelf gegenereerd met FFmpeg `anoisesrc`) |
| Gras komt omhoog (laatste maat voor de bouw) | 1 tel stilte vlak voor de drop | muziek weg in de laatste tel, alleen SFX |
| Bouw | de drop | lage tonen +3 dB bij 80 Hz |
| Finale | slotaccoord of harde stop op de maatstart | "impact" (sinus 50 Hz, 0,4 s, uitstervend), zelf gegenereerd |
| Outro tot de loop | uitklinken met galm | `aecho`, einde sluit aan op de teaser |

**Stappen.**
1. `beats.py` uitbreiden: energie per maat (totaal en onder 120 Hz). De drop = de maatstart met de grootste sprong
   in lage energie tegenover het gemiddelde van de 4 maten ervoor, minstens +6 dB. Schrijf `drop` in de uitvoer.
   Geen sprong van 6 dB gevonden: meld "geen drop" en val terug op het huidige gedrag.
2. `MusicSync.java`: kies `in` zo dat de drop op de maatstart van de bouw valt (nu: `sections.terraEnd`).
   Is er te weinig muziek vóór de drop, herhaal dan de laatste 4 maten van de opbouw.
3. `render.py` `master_audio`: knippen op maatstarts met een kruisfade van 10 ms, de filters uit de tabel,
   en als laatste `alimiter` (limit -1 dBTP) zodat -14 LUFS haalbaar wordt.

**Nieuwe judge-regels (in `sync_check.py` en `judge.py`).**
- **M1 drop op de bouw:** tijd van de drop in de eindmix ligt binnen 17 ms van de maatstart waarop de bouw begint.
- **M2 luidheid:** -14,0 ±0,5 LUFS en true peak hoogstens -1,0 dBTP. Nulmeting: -15,0 (faalt).
- **M3 boog:** energie onder 120 Hz is in de bouw minstens 6 dB hoger dan in de terraform.
- **M4 geen klikken:** op elke knip is de sprong tussen twee opeenvolgende samples kleiner dan 0,1 (volle schaal = 1).
- R1 en R2 blijven gelden.

## 7. Volgorde en werkwijze

| Stap | Werkpakket | Waarom deze plek | Nieuwe opname nodig? |
|---|---|---|---|
| 1 | WP2 layout | Klein, alleen pipeline, snel zichtbaar | Nee, her-render |
| 2 | WP3 geluidseffecten | Grootste winst voor het gevoel | Eén opname (mod schrijft `sfx.json`) |
| 3 | WP4 muziek | Bouwt voort op de mix van WP3 | Eén opname per track |
| 4 | WP1 tempo | Raakt de meeste bestaande regels, dus als laatste | Meerdere opnames |

Per werkpakket:
1. Schrijf eerst de judge-regels en draai ze op de bestaande opname. Noteer de nulmeting.
2. Verander één ding, bouw (`mod/gradlew.bat build`), test met `mod/quicktest.ps1 -Sheet <naam>` (duurt 12 tot 14 min, in de achtergrond draaien).
3. Alle oude 14 regels plus de nieuwe moeten groen zijn. Niet groen: herstellen of terugdraaien. Nooit een grens verleggen.
4. Bekijk zelf beelden uit `final.mp4` (niet uit `take.mp4`, daar staan teller en captions niet op).
5. Pas daarna installeren in het profiel van de gebruiker (jar + pipeline), zoals beschreven in `CLAUDE.md`.
6. Werk `CLAUDE.md` bij met een korte sessienotitie.

Vaste afspraken: het spel van de gebruiker nooit zelf afsluiten, alleen de testwereld gebruiken, captions in het
Engels, uitleg aan de gebruiker in het Nederlands zonder jargon, video's naar het bureaublad.

## 8. Wat klaar betekent

Eén opname van de wachttoren en één van het Colosseum waarop alle regels groen zijn:
de 14 bestaande plus D1, D2, D3, U1, U2, S1, S2, S3, M1, M2, M3, M4 (26 in totaal).
Budget: hoogstens 4 opnames per werkpakket. Stopregel: twee opnames na elkaar zonder vooruitgang op de
falende regel = stoppen en de metingen rapporteren.

## 9. Wat dit rapport niet kan beloven

- **Views.** Geen enkele regel hierboven meet kijkersbehoud. De echte jury is YouTube Studio
  ("Viewed vs. swiped away" en de retentiegrafiek). Toegang daartoe ontbreekt. Volgende trede: na 5 uploads de
  retentiegrafieken naast de tijdlijn van `show.json` leggen en zien waar kijkers afhaken.
- **De veilige zone.** Die is geschat, niet officieel. Eén controle op de telefoon lost dat op.
- **De muziek.** Zonder echte tracks is WP4 alleen te testen op de testbeat, en die heeft geen drop.
- **Niet nagekeken in code:** de exacte plek waar de FOV per frame wordt gezet, en of `sounds.json` voor elk
  blok een `break`- en `place`-ingang heeft. De uitvoerder controleert dat eerst.

## Bronnen

- Veilige zones (schattingen van derden): https://tool-nudge.pages.dev/youtube-shorts-safe-zone ,
  https://adkit.so/tools/safe-zones/youtube , https://postplanify.com/tools/youtube-shorts-safe-zone-checker ,
  https://adaptlypost.com/blog/social-media-safe-zones-2026-complete-guide
- Hooks en retentie: zie de bronnenlijst in de sessienotitie 6b van `CLAUDE.md`.
