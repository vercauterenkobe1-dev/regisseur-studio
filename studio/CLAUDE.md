# Regisseur Studio: projectnotities voor Claude

Doel: een eigen tool waarmee de gebruiker (Nederlandstalig, geen programmeur) Minecraft-timelapses als
YouTube Shorts maakt: terrein terraformen, een schematic laten bouwen, alles frame-exact opnemen in
1080x1920 en afwerken met captions en muziek. Kwaliteit boven alles, maar de hardware is zwak
(i7-1255U, 16 GB, Intel Iris Xe, Windows 11). Antwoord altijd in het Nederlands, zonder jargon.

## Mappen

| Pad | Wat |
|---|---|
| `mod/` | Fabric-mod "regisseur" (Java 25, Minecraft 26.2, Loom, Mojang-namen, geen remapping) |
| `mod/src/client/java/be/studio/regisseur/` | alle code, per pakket: `show/`, `draaiboek/`, `opname/`, `nabewerking/`, `mixin/` (zie `WEGWIJZER.md`) |
| `mod/src/client/resources/assets/regisseur/ui/index.html` | het studio-scherm (één HTML-bestand) |
| `mod/run/` | eigen testgame (dev-client); `run/studio/` is daar de studiomap |
| `pipeline/render.py` | storyboard.json -> final.mp4 (captions, muziek, encode) |
| `pipeline/judge.py` | de judge: meet een export tegen vaste criteria |
| `work/` | testmateriaal; `work/demo/make_demo.py` maakt de demo-wachttoren (.nbt) |
| `docs/STUDIE.md` | studie van bestaande mods en de routekaart |

Bij de gebruiker geïnstalleerd: launcher-profiel "Regisseur 26.2" met gameDir
`%APPDATA%\.minecraft\regisseur`. Daarin `mods\regisseur-<versie>.jar` en `studio\`
(`config.json`, `pipeline\`, `schematics\`, `music\`, `projects\current.json`, `renders\`).

## Architectuur van de mod

- `RegisseurClient`: HTTP-server op 127.0.0.1:47800 (dev: 47801 via `-Dregisseur.port`).
  Routes: `/` (UI), `/ping`, `/state`, `/cmd` (POST, commando zonder slash), `/api/status`,
  `/api/project` (GET/POST), `/api/files`, `/api/upload?kind=schematics|music&name=`,
  `/api/show/start` (POST, optioneel project als body), `/api/show/stop`, `/media?f=` (Range).
  Verzoeken met een vreemde `Origin`-header worden geweigerd.
- `Director`: de toestandsmachine IDLE -> PREPARING -> RECORDING -> FINISHING -> RENDERING -> DONE/ERROR.
  `frameStart()` zet per frame de camera en verstuurt blokwijzigingen; `capture()` legt het frame vast.
- `ShowPlan`: berekent vooraf alle blokwijzigingen met hun tijdstip in VIDEO-tijd (terraform + bouw).
- `Schematic`: laadt `.schem` (Sponge v2/v3), `.nbt` (structure), `.litematic`.
- `Recorder`: zet de renderresolutie op 1080x1920, leest frames van de GPU, pijpt ruwe RGBA naar FFmpeg.
- `PostProcess`: schrijft storyboard.json, draait `render.py` en `judge.py`.
- `mixin/GameRendererMixin`: twee haakjes, zie hieronder.
- `Studio`: paden, `config.json`, projectbestand, JSON-hulpjes.

### Het kernidee: frame-locked
Videotijd = frame / 60. Camera, blokken en effecten hangen alleen van de videotijd af, niet van de klok.
Rendert de laptop maar 4 fps, dan duurt de opname langer maar de video is perfect 60 fps.
De server-tickrate wordt meegeschaald (`20 * echteFps / 60`) zodat water en deeltjes in de pas lopen.
Gevolg: zware beeldkwaliteit (shaders, supersampling) kost alleen wachttijd, geen haperingen.
Nadeel: er is geen game-geluid in de opname.

## Geverifieerde feiten over Minecraft 26.2 (uit `javap` en een geslaagde opname)

- 26.x is niet meer geobfusceerd: Mojang-namen, Loom-plugin `net.fabricmc.fabric-loom`, geen mappings-regel.
- Frame-volgorde in `Minecraft.renderFrame`: `gameRenderer.update(delta)` -> `extract` -> `render(delta, bool)`
  -> `GpuSurface.blitFromTexture` naar het venster.
- In `GameRenderer.render`: `renderLevel` -> post-effecten -> `GuiRenderer.render()`.
  Mixin 1: `@At("HEAD")` op `update(Lnet/minecraft/client/DeltaTracker;)V`.
  Mixin 2: `@At(INVOKE GuiRenderer.render()V)` in `render(...)`: beeld is dan klaar maar nog zonder HUD.
- Renderresolutie los van het venster: `window.setWidth(w); window.setHeight(h);
  gameRenderer.mainRenderTarget().resize(w, h)` (zelfde truc als `grabPanoramixScreenshot`). Werkt.
  Het venster toont het beeld dan uitgerekt; dat is onschadelijk.
- Frame uitlezen: `Screenshot.takeScreenshot(RenderTarget, Consumer<NativeImage>)`, asynchroon.
  `NativeImage.getPointer()` wijst naar RGBA-bytes, beeld staat rechtop. Altijd `close()`.
- `Options.hideGui` bestaat niet meer; daarom vangen we vóór de HUD.
- Schermen: `mc.gui.screen()`, `mc.gui.setScreen(null)`. `mc.options.pauseOnLostFocus` bestaat.
- Camera = de speler in spectator, elk frame verplaatst. Zet ook `xo/yo/zo`, `xOld..`, `yRotO/xRotO`,
  `yHeadRot(O)`, `yBodyRot(O)` gelijk, anders interpoleert de game en trilt het beeld.
- NBT-API geeft `Optional` terug: gebruik `getIntOr`, `getCompoundOrEmpty`, `getListOrEmpty`, `getShort(k).orElse`.
- Blokken parsen: `BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, tekst, false).blockState()` en
  `NbtUtils.readBlockState(BuiltInRegistries.BLOCK, tag)`.
- Server: `mc.getSingleplayerServer()`, `server.execute(...)`, `server.tickRateManager().setTickRate(f)`,
  `ServerPlayer.setGameMode`, `sp.gameMode.getGameModeForPlayer()`, `sp.teleportTo(x,y,z)`, `level.getMinY()`.
- Blokken zetten: `level.setBlock(pos, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE)`
  (geen buur-updates: snel en het schematic blijft exact).
- Deeltjes ver zichtbaar: `level.sendParticles(type, true, true, x,y,z, count, dx,dy,dz, speed)`.
  Breekdeeltjes: `level.levelEvent(null, 2001, pos, Block.getId(state))`.
- Tijd zetten gaat via het commando `time set <ticks>` (26.x heeft "world clocks"; `time set sunset` bestaat niet).
- Onbekende API? Kijk zelf na met javap:
  `"C:\Program Files\Java\jdk-25.0.5.0.2\bin\javap.exe" -p -cp "<clientjar>;<commonjar>" <klasse>`
  met de jars in `%USERPROFILE%\.gradle\caches\fabric-loom\minecraftMaven\net\minecraft\`
  (`minecraft-clientonly-deobf\26.2\...jar` en `minecraft-common-deobf\26.2\...jar`). `-c` toont de aanroepen.

## De judge (niet aanpassen na het zien van resultaten)

`python pipeline/judge.py final.mp4 --master master.mkv --captions captions.ass`

- C1 1080x1920, 60 fps echte CFR, yuv420p, BT.709-tags, faststart
- C2 VMAF t.o.v. lossless master: gemiddeld >= 95, 1%-low >= 90, zelfde aantal frames
- C3 AAC 48 kHz stereo, -14 +/-1 LUFS, true peak <= -1 dBTP
- C4 caption-pixels (met outline en schaduw) binnen x[90,990] y[260,1460]
- C5 minder dan 1% dubbele frames

Laatste meting op echte gamebeelden (dev-client, demo-toren, 11 s): alles PASS, VMAF 99,98 / 99,12.

## Testlus zonder de gebruiker

```
cd mod
.\gradlew.bat build                      # compileren
.\gradlew.bat runClient                  # start testgame in wereld "regietest", poort 47801 (achtergrond)
# wachten tot http://127.0.0.1:47801/ping  inWorld=true geeft
# POST /api/show/start met een project (schematic demo_wachttoren.nbt, music testtoon.wav)
# pollen op /api/status tot DONE of ERROR; judge-uitvoer staat in status.judge
```
Log van de testgame: `mod/run/devclient.log`, spelversie-log `mod/run/logs/latest.log`.
De testgame is een offline dev-account; de 401-fouten over "profile key pair" en Realms zijn normaal.

## Installeren bij de gebruiker

Minecraft moet DICHT zijn (de jar is anders vergrendeld). Daarna:
oude `regisseur-*.jar` uit `%APPDATA%\.minecraft\regisseur\mods` halen, nieuwe uit `mod\build\libs` erin,
`pipeline\*.py` naar `studio\pipeline\` kopiëren. Sluit de game van de gebruiker nooit zelf af.

## Valkuilen (allemaal al eens fout gegaan)

- PowerShell 5.1: geen `&&`; `2>&1` op exe's geeft valse fouten. `cd` in Bash verplaatst de werkmap blijvend.
- `ffmpeg`/`node` staan pas in PATH na:
  `$env:Path = [Environment]::GetEnvironmentVariable('Path','Machine') + ';' + [Environment]::GetEnvironmentVariable('Path','User')`
- VMAF: mkv (1 ms tijdbasis) en mp4 lijnen niet uit op tijdstempel. Altijd `settb=AVTB,setpts=N/60/TB`.
- QSV schrijft geen kleurtags: `-bsf:v hevc_metadata=colour_primaries=1:transfer_characteristics=1:matrix_coefficients=1`.
- Na `/cmd tp` niet meteen `/state` vertrouwen; gebruik vaste coördinaten, geen `~`.
- De gewone `.minecraft\mods` van de gebruiker bevat Litematica voor 26.1.2 zonder MaLiLib: niet gebruiken.
- De show verandert de wereld echt en er is nog geen "ongedaan maken".

## Bekende open punten

Zie `docs/STUDIE.md`, hoofdstuk "Routekaart". Kort: opnamesnelheid meten met één game in focus,
blokken laten invliegen (block displays), supersampling, undo, shaders met Iris testen.

## Stand na sessie 2 (10 nov 2026, laat)

Gebouwd en IN DE TESTGAME GEMETEN (judge groen, "bouw: 941/941 blokken juist, 0 displays over"):
- `FlyIn`: blokken vliegen in via block displays (`EntityTypes.BLOCK_DISPLAY`, private setters via
  `mixin/DisplayInvoker` en `BlockDisplayInvoker`, licht via `Brightness(0, 15)` anders zijn ze zwart).
- `MinecraftClockMixin`: vaste klok via `@ModifyArg` op `DeltaTracker$Timer.advanceGameTime(J)I` in `Minecraft.runTick(Z)V`.
- Wachten op chunks: `mc.levelRenderer.hasRenderedAllSections()` (max 12 wachtframes).
- Terraform: water blijft staan en er komt een eiland op waterhoogte; rand uitgevlakt (5x5); gras afgewerkt.
- Camera blijft boven bomen en heuvels (`ShowPlan.computeCameraClearance`).
- Tijd/weer via `server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), ...)`.
- Testscript: `mod/testshow.ps1 -Tp "x y z"` (kies telkens een NIEUWE plek, anders staat de vorige toren er nog).

Gebouwd maar NOG NIET GETEST (compileert, nooit gedraaid): de snelle `Recorder`.
- Meting vooraf: opname 3,5 fps. Tellers: holdBusy=540 van 660 frames, dus de afvoer naar FFmpeg was de rem.
- Oorzaak 1: `Screenshot.takeScreenshot` kopieert pixel voor pixel. Nu eigen uitlezing:
  `device.createBuffer(label, USAGE_MAP_READ|USAGE_COPY_DST, bytes)` + `encoder.copyTextureToBuffer(tex, buf, 0L, callback, 0)`,
  in de callback `buf.map(true,false).data()`. Beeld staat ondersteboven: FFmpeg doet `vflip`.
- Oorzaak 2: stdin-pijp te smal. Nu TCP: FFmpeg luistert op `tcp://127.0.0.1:<poort>?listen=1`, Java schrijft met SocketChannel.
- EERSTVOLGENDE STAP: `gradlew runClient`, dan `testshow.ps1 -Tp "-300 120 250"`; controleer dat het beeld rechtop staat,
  de judge groen is en lees `opnamefps`, `holdBusy`, `writeMs`, `copyMs` uit `/api/status`. Doel: 15 fps of meer.
  Werkt het niet: zet in `Recorder.capture` tijdelijk de oude `Screenshot.takeScreenshot`-route terug (zie git-loze backup-zip).

Extra valkuil: het sessiepad is zo lang dat Python en sommige tools bestanden in `mixin/` niet vinden
(Windows-limiet van 260 tekens). Gebruik dan `"\\?\" + os.path.abspath(pad)` of PowerShell `[IO.File]`.
Mod 0.2.0 is nog steeds NIET geïnstalleerd bij de gebruiker (hun game stond open).

## Stand na sessie 3 (11 nov 2026)

- De snelle `Recorder` (eigen GPU-uitlezing + TCP naar FFmpeg) is GETEST: beeld rechtop, judge groen
  (VMAF 98,64 / 96,26), 941/941 blokken juist. Functioneel in orde.
- Snelheid is NIET verbeterd in de meting: 3,9 fps, later zelfs 1,6 fps. Tellers per frame: uitlezen 147 tot 190 ms,
  versturen 104 tot 456 ms. De hint `USAGE_HINT_CLIENT_STORAGE` hielp niet meetbaar.
- Waarschijnlijke oorzaak: de metingen liepen terwijl de game van de gebruiker ook draaide. De testgame haalde
  in rust maar 6 fps; de Iris Xe was dus al verzadigd. FFmpeg (QSV) gebruikte amper CPU en wachtte op de GPU.
  Dit is een vermoeden, geen bewijs.
- EERSTVOLGENDE STAP: meten met de game van de gebruiker DICHT. Vergelijk dan `takeEncoder` `qsv` en `x264`
  in `run/studio/config.json` (x264 gebruikt de CPU en botst niet met de game om de GPU).
- Open punt: in de ijsvlakte-test toonde het eerste frame alleen lucht (camera vermoedelijk in een ijsberg).
  `computeCameraClearance` nakijken voor de startpositie.
- Het studio-scherm is te openen op http://127.0.0.1:47801/ (testgame) of :47800 (echte game).

### Eerlijke snelheidsmeting (11 nov 2026, alleen de testgame open)
- Rust: 22 fps (venster 960x540, zonder focus). Opname met `takeEncoder=qsv`: **6,0 fps** (651 frames in 109 s).
- Per frame: uitlezen 82,7 ms, versturen 71,3 ms; 1022 wachtframes door volle buffer, 72 door chunks.
- Met twee games tegelijk was het 1,6 tot 3,9 fps: een tweede Minecraft halveert de snelheid dus ruim.
- `x264` als encoder voor de ruwe opname is NIET gemeten (script: `mod/speedtest.ps1 -Encoder x264 -Tp "x y z"`).
- Nog te onderzoeken: (1) splits "uitlezen" in map-tijd en kopieertijd; (2) waarom rust-fps maar 22 is
  (venster zonder focus?); (3) de nabewerking + judge duurt meer dan 8 minuten terwijl de game draait:
  overweeg de judge pas te draaien als de game klaar is of VMAF op elk 4e frame.
- Mod 0.2.0 is nu WEL geïnstalleerd in het profiel van de gebruiker (`regisseur-0.2.0.jar`, pipeline bijgewerkt).

## Stand na sessie 4 (11 nov 2026): versie 0.3.0

Gebouwd en GEMETEN in de testgame (alle 11 criteria groen, demo-toren, testbeat 124 BPM):
- **Muziek op de maat** (`pipeline/beats.py`, `MusicSync.java`): eigen beat-herkenning zonder libraries.
  Judge `pipeline/test_beats.py`: 4/5 synthetische tracks juist binnen 1% en < 10 ms; 174 BPM wordt als 87 BPM
  herkend (halve snelheid, elke gevonden tel valt wel op een echte tel). Zoekbereik 76-190 BPM.
  Resultaat per bestand gecachet in `studio/music/.beats/`.
  Muziek start op een maatstart; intro/terraform/bouw/outro eindigen op maatstarts; terraform-kolommen en
  blok-landingen vallen op achtste noten (in stoten); vuurwerk op een maatstart.
- **Vaste vertraging server -> beeld = 3 frames**, gemeten via `measured.landLagFrames` in show.json.
  Gecompenseerd met config `landLeadFrames` (standaard 3). Daarna: mediaan 1 frame, gemiddeld 0,0.
- **Shots** (`Director.shotPose`): dolly (opening, beweegt vanaf frame 0), crane (van boven, terraform),
  orbit (bouw), hero (laag, omhoog kijkend), reveal (achteruit en omhoog). Cuts op maatstarts bij muziek,
  anders vloeiend. Stijl "orbit" = de oude enkele camera. Tijdens de opening telt de zichtlijn ook de bomen
  (`clearanceTrees`), want die staan er dan nog.
- **Ritme-judge** `pipeline/sync_check.py show.json`: R1 cuts op maatstart, R2 vuurwerk op maatstart,
  R3 >= 95% landingen op achtste noot, R4 in beeld (mediaan <= 2, 90% <= 4 frames), R5 camera nooit in blok,
  R6 >= 4 soorten shots. De mod draait hem automatisch na de kwaliteits-judge.
- **Exportmap**: config `exportDir`; bij de gebruiker `C:\Users\verca\Documents\Minecraft Video Recoder\Exports`.
  In die map staat ook `Regisseur Studio openen.url` (http://127.0.0.1:47800/).
- **Captions schrijven we voortaan in het Engels** (wens van de gebruiker). Standaardproject is Engels.
- Opnamesnelheid in deze test: ca. 11 fps (813 frames in ca. 75 s), met de game van de gebruiker op de achtergrond.

Open punten:
- Mod 0.3.0 is NOG NIET geïnstalleerd bij de gebruiker (game stond open). Pipeline-bestanden en config wel.
- Testmuziek met hoge piekfactor haalt -15,0 LUFS (rand van C3): loudnorm wordt begrensd door de true peak.
  Bij echte muziek controleren; zo nodig een limiter (alimiter) voor loudnorm zetten.
- 174 BPM -> 87 BPM (zie boven).

## Stand na sessie 5 (11 nov 2026): versie 0.6.0

- **Terraform-stijl "square" (standaard)**, naar de voorbeeldvideo van de gebruiker: smalle diepe sleuf rond een
  vierkant (start aan de camerakant), binnenkant weg van het midden naar buiten, bodem komt laag per laag omhoog
  met gras, gelijk met de grond rondom (mediaan van de rand). `ShowPlan.squareTerraform`, shot "ring".
  Stijl "quarry" (lange groef) vond de gebruiker lelijk ("de gang"); blijft als keuze bestaan.
- **Bouw blok per blok, laag per laag** met ease-in/out (`easeBuild`: 0.25x + 0.75 smoothstep) en een korte pauze
  tussen lagen. Geen stoten op de maat meer (gebruiker: "lijkt te hard op AI"). Cuts en vuurwerk blijven op de maat.
- Judge: R3 vervallen; nieuw B1 laag per laag, B2 geen stoot, B3 ease. Baseline: B1 geen data, B2 45 per frame,
  B3 geen ease. Na: alles PASS (piek 4 per frame, gemiddeld 2,26; tempo 79/179/67 per s). Onafhankelijk nagerekend.
- **Afspeelbaar in Windows**: eindencode is nu H.264 (h264_qsv, High) i.p.v. HEVC. Nieuw criterium C6.
- **Wolken**: komt de camera boven y=184 (wolken op 192, aanname), dan wolken uit voor die opname.
- **Shader**: MakeUp Ultra Fast 9.6 (LGPL) gekozen. Gemeten in testgame met Iris+Sodium: geen shader 9,7 fps opname,
  MakeUp 5,6 fps (1,7x trager), Complementary Reimagined 4,7 fps (2,1x, net te zwaar). Opname werkt met Iris.
  Bij de gebruiker: `shaderpacks\MakeUp-UltraFast-9.6.zip`; `config\iris.properties` pas aanpassen als de game dicht is.
- Testscripts: `mod/quicktest.ps1` (volledige ronde + judges + beeldstrook), `mod/shadertest.ps1` (snelheid per shader).
  Testgame laadt nu Iris en Sodium uit `run/mods`; shader kiezen via `run/config/iris.properties`.
- Open: opening (dolly) kijkt soms in boomkruinen; mod 0.6.0 nog niet in het profiel van de gebruiker (game open).

## Sessie 5b (11 nov 2026): versie 0.7.0
- Bouwstijl `build.style`: "place" (standaard, zoals een speler: blok verschijnt op zijn plek en popt van 55% naar
  108% naar 100% in 3 ticks) of "fly" (invliegen). `FlyIn.setPlaceStyle`, `lead()` per stijl. Getest: 14/14 groen.
- Zichtafstand gaat tijdens de opname automatisch omhoog als de camera ver weg moet (max 16 chunks), daarna terug.
  Reden: Colosseum (70x32x70, 17.008 blokken) zat in de mist. Colosseum-run zelf: 14/14 groen, 22 blokken/frame.
- Eenmalig vastgelopen opname (take 1,5 MB in 25 min) liep parallel met een VMAF-meting; niet reproduceerbaar.
- Bureaublad: `Regisseur Studio.lnk` -> `Regisseur Studio starten.ps1` (start launcher, wacht op poort 47800, opent browser).

## Sessie 6: materiaalteller (0.8.0)
- Gebruiker wil de 'materiaalteller'-overlay uit virale Shorts: 2 kolommen x 3 rijen blokiconen met witte, schuine
  getallen (zwarte rand) die meetellen terwijl de blokken landen. Optie `overlay.counter` (on/off, standaard on).
- Mod: `CounterData` schrijft na de opname `counter.json` (top 6 blokken, landingstijden) en kopieert de textures uit
  de geladen resources (blockstate -> model -> parents -> textures; item-texture als die bestaat = plat icoon) naar `icons/`.
- Pipeline: `counter.py` tekent isometrische kubussen met Pillow (4x supersampling) in `counter_panel.png` en de
  getallen in `counter.ass` (aparte ASS, zodat judge C4 alleen de captions meet). Pillow: `pip install --user pillow`.
  Let op: `python -I` ziet user-site niet, dus Pillow ontbreekt dan.
- Exportmap staat nu op het bureaublad (config.json van de gebruiker).
## Sessie 6b: hook (0.8.0)
- Teller telt standaard gemijnde blokken (`ShowPlan.mined`: originele toestand van elke leeggemaakte plek, plantjes eruit).
- Hook: `storyboard.teaser` = laatste maat van de opname vooraan + hooktekst ("I mined {mined} blocks for this"), harde cut
  (concat) naar het begin. Teaser = precies 1 maat (halve bij trage muziek) zodat de muziek op de maat blijft. Laatste beeld
  = eerste beeld -> naadloze loop. Optie `hook.teaser` on/off, `hook.text`.
- `lead_trim` 0.4 s: eerste frames van elke take zijn onrustig (MakeUp auto-belichting, roze lucht, chunks). Muziek-in schuift mee.
- Getest via her-render van een take (judge C1-C6 groen, VMAF 99,66). Het Java-pad (statMined/teaserLen) nog niet in een volle opname.
## Sessie 7: rapport RAPPORT-DYNAMIEK.md uitgevoerd (0.9.0)
- WP2 teller: `counter.py` compact (TOP 190, rijen 120 px, iconen 100 px), per kolom geordend, 1 lettergrootte,
  duizendtal-komma, donker verloop, alleen blokken >= 1%. Judge `judge.py --counter --panel`: U1 zone, U2 1 grootte. M2 luidheid.
- WP3 geluid: `SfxData.java` haalt breek/plaats-geluiden via `SoundManager.getSoundEvent(..).getSound(rnd).getPath()`
  + ResourceManager (ogg) -> `sfx.json` + `sfx/`. `sfx.py` mixt (numpy), 24 tikken/s plafond, 6 LU onder de muziek,
  sidechain. `audio_check.py`: S1-S3, M1, M3, M4. Pillow + numpy: `pip install --user`.
- WP4 muziek: `synth.py` = Regisseur Beat. `music/Regisseur Beat.wav` is een vaste 128-BPM-loop om op te timen;
  bij renderen maakt `render.py` (`music_stem`) een nieuwe track op de show-tijdlijn: drop in teaser en bouw, opbouw +
  riser + 1 tel stilte, impact op bouwstart en finale. Eigen tracks: 1 tel stilte + bas +3 dB in de bouw. Limiter voor loudnorm.
  M4 bijgesteld: meet alleen echte naden (loop, stille tel, einde), want drum-aanslagen springen bewust.
- WP1 tempo: `ShowPlan.easeS/easeSInv` voor sleuf, uithollen (oppervlak-aandeel), omhoog; `beatWarp` = 50% dip
  in de 0,1 s voor elke tel; `Director.breath` = hoeksnelheid 0,55x-1,45x per maat. show.json: camAngle, terraTimes.
  `sync_check.py`: D1, D2, D3.
## Sessie 8: code herschikt (0.9.1, gedrag ongewijzigd)
Overzicht voor de gebruiker: `WEGWIJZER.md`. Oude namen in de notities hierboven -> nieuwe plek:
- `Director.shotPose/applyCamera/buildShots/breath` -> `show/Camera.pose/apply/buildShots/breath`
- `Director.writeShowJson` -> `show/ShowReport.write`; teller/geluid/hook-voorbereiding -> `nabewerking/PostProcess.prepare`
- `ShowPlan.squareTerraform/ringHead/squareFx` -> `draaiboek/VierkantTerraform.maak/ringHead/effecten`
- `ShowPlan.digQuarry/quarryFx` -> `GroefTerraform`; vlakmaken + `computeCameraClearance` -> `VlakTerraform`
- bouwvolgorde -> `Bouwvolgorde.plan`; `smooth/easeBuild/easeInv/easeS/beatWarp/jitter` -> `Easing` (easeInv heet nu easeBuildInv)
- `treeish/ground/fluid/waterY/groundY/smoothRing/interp` -> `Terrein` (+ `Terrein.zichtlijn` voor vierkant en groef)
- Director roept nu `plan.terraformFx(level, t, u)` aan in plaats van per stijl.
- `render.py main()` = 6 benoemde stappen: `plan_timeline`, captions, `make_counter`, `build_master`, `master_audio`, eindencode.
## Sessie 9: grote bouwwerken, kathedraal, GitHub (0.10.0)
- Repository (openbaar): https://github.com/vercauterenkobe1-dev/regisseur-studio (account vercauterenkobe1-dev, commits met
  noreply-adres). Meewerkers: `CONTRIBUTING.md`. gh-login: device-code pas goedkeuren NA Enter in de terminal, anders verloopt hij.
- Geen glitches: `Director.frameStart` wacht tot (1) server klaar met vorige blokken, (2) steekproeven en bouwblokken echt bij de
  client staan (`visibleNow`; niet-ingeladen chunks overslaan), (3) alle chunks hertekend; max 240 render-frames per videoframe.
  `ChunkCacheInvoker` stuurt blokwijzigingen meteen door (niet wachten op een server-tick). `landLeadFrames` 1 in dev-config.
  Tellers in show.json/status: unsettledFrames, serverLateFrames (regel G1), G2 = flitsdetector op take.mp4.
- FOUT GEVONDEN: `level.getHeight` geeft voor NIET-ingeladen chunks minY terug. Gaf bij de kathedraal een put tot de bodem
  (7,5 M ops) en bos dat bleef staan. `Terrein.surfaceY` laadt nu eerst de chunk (`level.getChunk`).
- Landschap rondom moet VOORAF bestaan, anders lege (lichtblauwe) vlakken in de verte: `show/Landschap.maakAan` (32 chunks per
  server-taak). Nog traag (sequentieel, ~3 chunks/s; kathedraal ~12.500 chunks): volgende stap `getChunkFuture` parallel.
- `maxRenderDistance` (config, standaard 16, kathedraal 32); opwarmen schaalt mee; frame 0 wacht op bouwterrein + landschap.
- Venster sluiten tijdens opname/nabewerking wordt genegeerd (`WindowCloseMixin` + `CloseWatchdogMixin`); de testgame
  (poort 47801) sluit nooit via het venster. De gebruiker klikte de testgame meermaals dicht.
- Recorder: "kwam nooit terug van de GPU" was vals alarm bij trage opnames (wachtte op een frame dat nog niet aangevraagd was).
- Nieuw: rood SUBSCRIBE-eindbeeld (`render.py build_endcard`, project `endcard`), tijdsinschatting (`show/Tijdschatting`,
  leert in `studio/tijden.json`), teller K/M-notatie vanaf 100.000, `pipeline/schem_trim.py` (ondergrond en rommel van
  downloads weg), `mod/maakvideo.ps1` (opname met projectbestand -> bureaublad), hoogtecontrole tegen de bouwlimiet.
- Kathedraal (546.094 blokken, 240x225x152): nog GEEN goede video. Proefversie op het bureaublad heeft lege verte en geen
  kathedraal in teaser/slotshot. Open: landschap sneller aanmaken, of camera dichter + zichtafstand 16.
## Sessie 10: sneller afwerken, phonk-muziek, LIKE-knop (0.11.0)
- Colosseum-Short na 24 u: 21,8K weergaven, 56,6% bleef kijken, 94 likes (0,4%), +10 abonnees (gepost met 0.9.1).
- Gemeten (Colosseum-take, 20 s): afwerken 72 s + controles 5 min zonder game; bij de gebruiker 10,5 min met game ernaast.
  Nu: `render.py render_one_pass` = opname + teller + captions + eindbeeld in EEN ffmpeg-ronde die tegelijk final.mp4 en
  master.mkv schrijft (geluid eerst). Oude weg alleen nog bij zoom/overvloei. 72 s -> ~35 s, SSIM 0,9993 t.o.v. oud.
  judge: frames tellen via `-count_packets` (51 s -> 1 s), VMAF `n_subsample=4` (3 min -> 19 s, score 99,89 vs 99,90;
  drempels ongewijzigd). PostProcess draait judge/sync/audio TEGELIJK en zet de game op 10 fps tijdens het afwerken
  (`remGame`, framerateLimit terug na afloop). Totaal zonder game ~2 min.
- Fout gevonden: de geluidsmaster was ~0,13 s korter dan de video, dus de fade-out viel erbuiten (M4 soms rood).
  Nu `apad=whole_dur,atrim=end` voor de fades.
- Muziek: `synth.render_phonk` (standaard; `music.style` "edm" = oude). Vervormde 808 met octaaflaag + glijden, korte kick,
  cowbell-melodie, snare 2/4, hats met rollen. Nieuw criterium M5 (audio_check, alleen Regisseur Beat): in de drop
  100-400 Hz >= -5 dB en 30-80 Hz >= -6 dB van het totaal. EDM: -9,7/-1,4 (FAIL); phonk: -3,6/-4,7 (PASS).
  Reden: gsm-luidsprekers spelen onder ~150 Hz bijna niets af.
- Eindbeeld: witte LIKE-pil met duim (ASS-tekening `THUMB`, inline met de tekst) 0,35 s na de rode SUBSCRIBE, erboven.
  Uit te zetten met project `endcard.like` "off".
