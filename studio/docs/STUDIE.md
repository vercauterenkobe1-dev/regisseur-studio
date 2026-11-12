# Studie: wat kunnen we leren van bestaande mods?

Datum: 10 november 2026. Minecraft 26.2, Fabric.

**Hoe te lezen.** Elke bewering heeft een herkomst:
- **[gemeten]**: zelf gemeten op deze laptop of opgevraagd bij de Modrinth-API.
- **[kennis]**: uit mijn algemene kennis van die mod. De broncode is hiervoor **niet** opnieuw nagekeken;
  controleer het in de bron vóór je erop bouwt.

**Licentieregel.** We nemen *ideeën en technieken* over en schrijven de code zelf. Code kopiëren doen we niet:
GPL-code zou onze mod GPL maken, en "All Rights Reserved" mag helemaal niet. Mods met een vrije licentie
(MIT, LGPL, Apache) mogen wel gewoon *naast* onze mod geïnstalleerd worden.

## 1. Waar staan we nu? [gemeten]

Eerste volledige opname in de testgame (demo-wachttoren, 941 blokken, 4566 terraform-blokken, 11 s):

| Criterium | Resultaat |
|---|---|
| C1 formaat 1080x1920, 60 fps CFR, BT.709, faststart | PASS |
| C2 VMAF t.o.v. master | PASS: 99,98 gemiddeld, 99,12 op de slechtste 1% |
| C3 audio -14 LUFS | PASS: -13,8 LUFS (met een testtoon als muziek) |
| C4 captions in de safe zone | PASS |
| C5 dubbele frames | PASS: 0 van 660 |

Wat nog zwak is:
- **Opnamesnelheid: 4 frames per seconde.** 11 s video kostte bijna 3 minuten. Gemeten terwijl er twee games
  tegelijk draaiden en de testgame geen focus had (ze haalde in rust ook maar 10 fps). De echte snelheid met
  één game in focus is nog niet gemeten.
- **Blokken "ploppen" in beeld.** Technisch juist, maar visueel het zwakste punt van de video.
- **Sneeuw en losse plekken** op het vlakgemaakte terrein; de rand oogt rommelig.
- **Geen kartelrand-correctie.** Minecraft heeft geen anti-aliasing; dunne randen flikkeren bij een draaiende camera.
- **Geen game-geluid** (gevolg van frame-voor-frame opnemen).
- **Geen "ongedaan maken"**: de show verandert de wereld blijvend.

Encoder voor de ruwe opname, gemeten op echte gamebeelden (240 frames):

| Instelling | Snelheid | Grootte |
|---|---|---|
| x264 superfast crf 12 (was de standaard) | 16,4 fps | 67 MB |
| x264 ultrafast crf 14 | 28,4 fps | 84 MB |
| hevc_qsv q14 | 19,4 fps | 56 MB |
| **h264_qsv q14 fast** | **36,2 fps** | 68 MB |

Besluit: `h264_qsv` wordt de standaard voor de ruwe opname (2,2x sneller, en de CPU blijft vrij voor de game).
Nog niet gemeten: hoeveel kwaliteit de ruwe opname zelf verliest tegenover de onbewerkte frames.

## 2. Bestaande mods: wat doen ze goed en wat nemen we over?

Beschikbaarheid voor 26.2 en licentie komen uit de Modrinth-API [gemeten]. De techniek erachter is [kennis].

### Opnemen en camera

| Mod | 26.2 | Licentie | Wat ze goed doen | Wat wij ermee doen |
|---|---|---|---|---|
| **ReplayMod** | ja | GPL-3 | Rendert frame voor frame met een vaste klok; wacht tot alle chunks klaar zijn vóór elk frame; camerapaden met keyframes en vloeiende curves; supersampling | Vaste klok hebben we al. **Overnemen: wachten op chunks, keyframe-camera, supersampling.** Zelf schrijven. |
| **Flashback** | ja | alle rechten voorbehouden | Neemt het spel op als "replay" en rendert achteraf; keyframes voor camera, FOV, tijd van de dag; export met anti-aliasing | Alleen ideeën: **tijd van de dag en FOV als keyframes** (zonsondergang tijdens de bouw). |
| **CineWolf AutoDirector** | ja | MPL-2 | Kiest automatisch shots (orbit, kraan, dolly) | **Overnemen als idee: een shotlijst** in plaats van één draaiende camera. |

### Terraformen en bouwen

| Mod | 26.2 | Licentie | Wat ze goed doen | Wat wij ermee doen |
|---|---|---|---|---|
| **WorldEdit** | ja | GPL-3 | `//smooth` (hoogtekaart uitvlakken), `//naturalize` (gras, 3 aarde, steen), schematics draaien en spiegelen, undo-geschiedenis | **Overnemen: uitvlakken met een hoogtekaart-filter, draaien/spiegelen, undo.** |
| **Axiom** | ja | alle rechten voorbehouden, gesloten | Boetseren met kwasten, ruis, automatische schaduwkleuren | Alleen als inspiratie voor latere "kwasten". |
| **Litematica** | ja | LGPL-3 | Het `.litematic`-formaat, plaatsing draaien/spiegelen | Formaat lezen we al. Kan ernaast geïnstalleerd worden (heeft MaLiLib nodig). |
| **Create** (Schematicannon) | n.v.t. | MIT | Schiet blokken met een boog naar hun plek | **Overnemen als idee: blokken vliegen in een boog naar hun plaats.** Dit is het sterkste beeld voor een Short. |

### Animatie van blokken

| Mod | 26.2 | Licentie | Wat ze goed doen | Wat wij ermee doen |
|---|---|---|---|---|
| **BlockMotion** | ja | MIT | Verende animatie bij plaatsen en breken | **Overnemen: verend "inveren" (overshoot) bij het landen.** |
| **Fresh / Stupid Block Animations** | ja | eigen/gesloten | Idem | Alleen idee. |
| Chunk Animator (ouder) | onbekend | onbekend | Stukken wereld rijzen uit de grond | Idee voor de terraform: grond die omhoog schuift. |

De techniek die dit mogelijk maakt zit in Minecraft zelf: **block displays**. Dat zijn entiteiten die een blok
tonen en vloeiend van positie, schaal en draaiing kunnen veranderen. De server zet een block display op de
startplek, laat het naar de eindplek bewegen en vervangt het daar door het echte blok. [kennis: bestaat sinds
1.19.4; de exacte API in 26.2 moet met javap nagekeken worden: `net.minecraft.world.entity.Display$BlockDisplay`.]

### Beeldkwaliteit en snelheid

| Mod | 26.2 | Licentie | Nut voor ons |
|---|---|---|---|
| **Sodium + Iris** | geïnstalleerd | LGPL | Snelheid en shaders. Omdat we frame voor frame opnemen, mogen shaders traag zijn. **Nog niet getest met onze opname.** |
| **Distant Horizons** | ja | LGPL-3 | Landschap tot aan de horizon. Mooi voor het openingsshot. Zwaar; eerst meten. |
| **Bobby** | ja | LGPL-3 | Onthoudt verre chunks. Lichter alternatief. |
| **Lithium, FerriteCore, ImmediatelyFast** | ja | LGPL / MIT / LGPL | Snellere server, minder geheugen, snellere tekenlaag. Veilig om erbij te zetten. |
| **C2ME** | ja | MIT | Snellere chunk-aanmaak; helpt als de camera nieuw terrein inkijkt. |

## 3. Wat doen de beste makers dat wij nog niet doen? [kennis]

1. **Wachten tot de wereld klaar is vóór elk frame.** Anders zie je chunks en blokken te laat verschijnen.
   Wij sturen blokken naar de server en nemen meteen op; bij 4 fps valt dat niet op, bij 30 fps wel.
2. **Supersampling.** Renderen op 2160x3840 en verkleinen naar 1080x1920. Dat is de enige echte oplossing
   tegen flikkerende randen. Kost 4x zoveel rekentijd per frame; kan omdat we frame-locked werken.
3. **Bewegingsonscherpte uit tussenframes.** Meerdere tussenbeelden per videoframe mengen geeft filmische
   beweging. Duur; pas doen als 1 en 2 er zijn.
4. **Een shotlijst.** Openingsshot dichtbij, kraanbeweging tijdens de bouw, langzame draai op het einde.
5. **Het einde sluit aan op het begin**, zodat de Short onzichtbaar opnieuw start (kijkers blijven hangen).
6. **Geluid achteraf opbouwen.** We kennen het tijdstip van elk blok. Daarmee kan de pipeline plaatsgeluiden
   onder de muziek leggen, strakker dan een live-opname.
7. **Licht kiezen.** Gouden uur of zonsondergang tijdens de bouw; de tijd laten verschuiven tijdens de show.

## 4. Routekaart, gerangschikt op winst per moeite

Elke stap heeft een eigen meetbare test. De bestaande judge (C1 tot C5) moet na elke stap groen blijven.

| # | Stap | Waarom | Test (naast de judge) | Moeite |
|---|---|---|---|---|
| 1 | Ruwe opname via `h264_qsv`; snelheid meten met één game in focus | Opnemen moet werkbaar zijn | Frames per seconde in `/api/status`; doel: 15 of meer | klein |
| 2 | Wachten tot chunk-updates verwerkt zijn vóór een frame telt | Geen blokken die te laat verschijnen | Op een stilstaand beeld: het frame op tijdstip t toont alle blokken met tijd kleiner dan t | middel |
| 3 | **Blokken laten invliegen** met block displays (boog + inveren) | Grootste sprong in "wow" | Tel de display-entiteiten na afloop: 0; eindtoestand van de wereld is blok voor blok gelijk aan het schematic | middel |
| 4 | Terraform netter: sneeuw en planten weg, rand uitvlakken met een hoogtekaart-filter | Schoon beeld | Geen zwevende blokken en geen sneeuwlagen binnen de schijf (telling op de server) | klein |
| 5 | Supersampling (1,5x en 2x) als kwaliteitsstand | Geen flikkerende randen | Flikkermaat: gemiddeld verschil tussen opeenvolgende frames bij stilstaande camera, lager dan zonder | middel |
| 6 | Undo: de oude blokken bewaren en kunnen terugzetten | Veilig testen in een echte wereld | Na undo is de wereld blok voor blok gelijk aan vóór de show | klein |
| 7 | Shotlijst en keyframes (camera, FOV, tijd van de dag) in het project en het studio-scherm | Regie in plaats van één orbit | Camerabaan is continu: geen sprong groter dan een drempel tussen twee frames | middel |
| 8 | Geluidslaag uit het draaiboek | Voelt als echte opname | C3 blijft groen; geluiden vallen binnen 1 frame van hun blok | middel |
| 9 | Shaders (Iris) en Distant Horizons testen met de opname | Maximale beeldkwaliteit | Opname slaagt en de judge blijft groen; anders niet gebruiken | onzeker |
| 10 | Schematic draaien/spiegelen, voorvertoning van de omtrek in de wereld | Gebruiksgemak | Gedraaid resultaat gelijk aan verwachte coördinaten | klein |

## 5. Gaten (wat we niet kunnen of weten)

- **Viraal gaan kan niet gemeten worden.** Daarvoor is toegang tot YouTube Analytics nodig (kijkduur per
  seconde). Met die cijfers kunnen hook, lengte en captions echt getest worden.
- **Shaders + onze opname**: onbekend tot de eerste test.
- **De broncode van de mods hierboven is niet opnieuw gelezen.** De technieken zijn uit kennis beschreven.
- **Muziekrechten**: de tool mastert elk bestand, maar controleert geen rechten.
