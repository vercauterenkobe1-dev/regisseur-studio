package be.studio.regisseur.show;

import be.studio.regisseur.RegisseurClient;
import be.studio.regisseur.Studio;
import be.studio.regisseur.draaiboek.MusicSync;
import be.studio.regisseur.draaiboek.Schematic;
import be.studio.regisseur.draaiboek.ShowPlan;
import be.studio.regisseur.nabewerking.PostProcess;
import be.studio.regisseur.opname.Recorder;
import com.google.gson.JsonObject;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * DE REGISSEUR: stuurt één opname van begin tot einde.
 *
 * Verloop (phase):  IDLE -> PREPARING -> RECORDING -> FINISHING -> RENDERING -> DONE  (of ERROR)
 *   start()       leest het project, analyseert de muziek en laat het draaiboek berekenen (op de achtergrond);
 *   begin()       zet de opname klaar (resolutie, zichtafstand, wolken) en start de Recorder;
 *   frameStart()  elk frame: camera zetten en de blokwijzigingen van dit moment naar de server sturen;
 *   capture()     elk frame: het beeld vastleggen en meten welke bouwblokken echt in beeld staan;
 *   finish()      alles terugzetten zoals het was en de nabewerking starten (PostProcess).
 *
 * Kernidee "frame-locked": videotijd = frame / 60. Rendert de laptop maar 5 fps, dan duurt de opname langer,
 * maar de video is toch perfect 60 fps. frameStart() en capture() komen uit mixin/GameRendererMixin.
 */
public final class Director {
	public enum Phase { IDLE, PREPARING, RECORDING, FINISHING, RENDERING, DONE, ERROR }

	// ---- toestand, zichtbaar in het studio-scherm (/api/status) ----
	public static volatile Phase phase = Phase.IDLE;
	public static volatile String message = "", output = "", take = "", judge = "", verify = "", export = "";
	public static volatile int heldFrames, holdBusy, holdSections;
	public static volatile int frame, totalFrames;
	public static volatile float fps;

	// ---- de lopende opname ----
	private static ShowPlan plan;
	private static Recorder rec;
	private static JsonObject project;
	private static Path runDir;
	private static IntegratedServer server;
	private static ServerLevel level;
	private static UUID playerId;
	private static int opIdx, warmup, sectionWait, visWait, serverWait, rateFrame;
	/** Hoogstens zoveel render-frames wachten op één videoframe; daarna toch opnemen (en tellen als glitch-risico). */
	private static final int MAX_WAIT = 240;
	public static volatile int holdServer, holdVisible;
	/** Waarop de opname nu wacht (voor het studio-scherm en om problemen te vinden). */
	public static volatile String waitReason = "";
	private static int loadWait;
	/** Steekproef van verstuurde blokwijzigingen: die moeten op hun tijdstip zichtbaar zijn bij de client. */
	private static final java.util.ArrayDeque<int[]> visChecks = new java.util.ArrayDeque<>(); // {opIndex, dueFrame}
	private static boolean hold, finaleDone;
	private static long rateNs;
	private static double fakeMs = Double.NaN;
	/** Gemeten vaste vertraging tussen server en beeld (3 frames); wereldwijzigingen gaan zoveel eerder uit. */
	private static float lead;

	// ---- wat we na de opname terugzetten ----
	private static GameType prevMode;
	private static double px, py, pz;
	private static float pyaw, ppitch;
	private static int prevFov;
	private static boolean prevPause;
	private static CloudStatus prevClouds;
	private static Integer prevRenderDistance;
	/** Vanilla-wolken hangen op y=192 (aanname, standaard in de overwereld). Komt de camera erboven, dan wolken uit. */
	private static final double CLOUD_Y = 192;

	// ---- meting: wanneer verschijnt elk bouwblok echt in beeld? (regel R4) ----
	private static ShowPlan.Op[] pending;
	private static int pendingFrom;
	private static boolean[] seen;
	private static final List<Integer> landLag = new ArrayList<>();

	// ---- meting: glitches (regel G1) ----
	/** Laatste frame waarvan de server de blokwijzigingen helemaal uitgevoerd heeft. */
	private static volatile int serverDoneFrame;
	/** Frames opgenomen terwijl de wereld nog niet helemaal opnieuw getekend was (halve chunks = glitch). */
	static int unsettledFrames;
	/** Frames opgenomen terwijl de server nog niet klaar was met de blokken die nu in beeld moesten staan. */
	static int serverLateFrames, maxServerLag;

	/** Het venster werd gesloten tijdens een opname: genegeerd. Laat het zien in het studio-scherm. */
	public static void closeRefused() {
		message = "Venster sluiten genegeerd: de opname loopt nog. Druk eerst op Stop opname als je echt wil stoppen.";
		RegisseurClient.LOG.info("venster sluiten genegeerd tijdens de opname");
	}

	public static boolean busy() {
		return phase == Phase.PREPARING || phase == Phase.RECORDING || phase == Phase.FINISHING || phase == Phase.RENDERING;
	}

	// =================================================================== 1. voorbereiden

	/** Start een show met dit project. Client-thread. Geeft null terug als het gelukt is, anders de fout. */
	public static String start(JsonObject proj) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer pl = mc.player;
		if (pl == null || mc.getSingleplayerServer() == null) return "open eerst een singleplayer-wereld";
		if (busy()) return "er loopt al een opname";
		project = proj.deepCopy();
		prevPause = mc.options.pauseOnLostFocus;
		mc.options.pauseOnLostFocus = false; // de show moet doorlopen als het venster de focus verliest
		if (mc.gui.screen() != null) mc.gui.setScreen(null); // pauzemenu zou de wereld stilzetten
		server = mc.getSingleplayerServer();
		playerId = pl.getUUID();
		phase = Phase.PREPARING;
		message = "draaiboek berekenen";
		output = take = judge = verify = export = "";
		heldFrames = holdBusy = holdSections = 0;
		frame = totalFrames = 0;
		finaleDone = false;

		// --- instellingen uit het project ---
		JsonObject tm = Studio.obj(project, "timing"), cam = Studio.obj(project, "camera"), site = Studio.obj(project, "site"),
				world = Studio.obj(project, "world"), music = Studio.obj(project, "music"), terra = Studio.obj(project, "terraform");
		final float tIntro0 = Studio.num(tm, "intro", 2), tTerra0 = Studio.num(tm, "terraform", 5),
				tBuild0 = Studio.num(tm, "build", 14), tOutro0 = Studio.num(tm, "outro", 4);
		final int margin = (int) Studio.num(site, "margin", 3), radius = (int) Studio.num(site, "radius", 12);
		Camera.orbitSpeed = Studio.num(cam, "orbitSpeed", 14);
		final float distMul = Studio.num(cam, "distance", 1f);
		final int fov = (int) Studio.num(cam, "fov", 70);
		final String style = Studio.str(cam, "style", "shots");
		final String transition = Studio.str(cam, "transition", "auto");
		final String schem = Studio.str(project, "schematic", "");
		final String musicFile = Studio.str(music, "file", "");
		final boolean wantSync = !"false".equals(Studio.str(music, "sync", "true"));
		final float musicIn = Studio.num(music, "in", 0);
		String time = Studio.str(world, "time", ""), weather = Studio.str(world, "weather", "");
		final String timeCmd = time.isBlank() ? null : "time set " + time.trim();
		final String weatherCmd = weather.isBlank() ? null : "weather " + weather.trim();
		final String tStyle = Studio.str(terra, "style", "square");
		final boolean quarryStyle = "quarry".equals(tStyle), squareStyle = "square".equals(tStyle);
		final int qDepth = (int) Studio.num(terra, "depth", 24);
		final float qLength = Studio.num(terra, "length", 2.5f);
		final boolean placeStyle = !"fly".equals(Studio.str(Studio.obj(project, "build"), "style", "place"));

		// --- de speler: hier wordt gebouwd, en de camera start achter hem (wat jij nu ziet is ongeveer het openingsbeeld) ---
		px = pl.getX(); py = pl.getY(); pz = pl.getZ(); pyaw = pl.getYRot(); ppitch = pl.getXRot();
		double fx = -Math.sin(Math.toRadians(pyaw)), fz = Math.cos(Math.toRadians(pyaw));
		Camera.startAngle = (float) Math.toDegrees(Math.atan2(-fz, -fx));
		final int cx = (int) Math.floor(px), cz = (int) Math.floor(pz);
		// groef in de kijkrichting, afgerond op een windrichting (rechte Minecraft-wanden)
		final int qax = Math.abs(fx) >= Math.abs(fz) ? (int) Math.signum(fx) : 0;
		final int qaz = qax == 0 ? (int) Math.signum(fz == 0 ? 1 : fz) : 0;
		final ShowPlan.Quarry qopts = quarryStyle ? new ShowPlan.Quarry(qax, qaz, qDepth, qLength) : null;
		final ShowPlan.Square sopts = squareStyle ? new ShowPlan.Square(qDepth, Camera.startAngle) : null;

		Thread prep = new Thread(() -> {
			try {
				// 1. muziek analyseren (eenmalig per bestand, daarna uit de cache)
				MusicSync sync = null;
				Path mpath = musicFile.isBlank() ? null : Studio.dir("music").resolve(musicFile);
				if (wantSync && mpath != null && Files.exists(mpath)) {
					message = "muziek analyseren (tempo en maten)";
					sync = MusicSync.analyze(mpath, musicIn);
					music.addProperty("in", sync.in); // de nabewerking start de muziek op dezelfde maat
				}
				// 2. de delen van de show (intro, terraform, bouw, outro) op maatstarts leggen
				float a = tIntro0, b = a + tTerra0, c = b + (schem.isBlank() ? 0 : tBuild0), e = c + tOutro0;
				if (sync != null) {
					a = tIntro0 <= 0.05f ? 0 : sync.nearestDownbeat(tIntro0, 0.5f);
					b = sync.nearestDownbeat(a + tTerra0, a + 1f);
					c = schem.isBlank() ? b : sync.nearestDownbeat(b + tBuild0, b + 2f);
					e = sync.nearestDownbeat(c + tOutro0, c + 1f);
					if (sync.duration - sync.in < e) message = "let op: de muziek is korter dan de video";
				}
				final float fa = a, fb = b, fc = c, fe = e;
				final MusicSync fsync = sync;
				// 3. shotlijst
				Camera.smoothCuts = !("cut".equals(transition) || ("auto".equals(transition) && sync != null));
				Camera.buildShots("orbit".equals(style), squareStyle ? "square" : quarryStyle ? "quarry" : "flatten", fa, fb, fc, fe, fsync);
				message = "draaiboek berekenen";
				// 4. het draaiboek (leest de wereld, dus op de server-thread)
				server.execute(() -> {
					try {
						ServerPlayer sp = server.getPlayerList().getPlayer(playerId);
						level = sp.level();
						// via de server zelf, zodat het ook werkt in een wereld zonder cheats
						if (timeCmd != null) server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), timeCmd);
						if (weatherCmd != null) server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), weatherCmd);
						Schematic sch = schem.isBlank() ? null : Schematic.load(Studio.dir("schematics").resolve(schem));
						ShowPlan p = ShowPlan.build(level, cx, cz, sch, fa, fb - fa, fc - fb, fe - fc, margin, radius, distMul,
								fsync, qopts, sopts, placeStyle);
						GameType mode = sp.gameMode.getGameModeForPlayer();
						sp.setGameMode(GameType.SPECTATOR);
						final int unknown = sch == null ? 0 : sch.unknown;
						// 5. het landschap rondom aanmaken, zo ver als de camera kan kijken (eenmalig per plek)
						Thread land = new Thread(() -> {
							try {
								Landschap.maakAan(server, level, p, Integer.parseInt(Studio.cfg("maxRenderDistance", "16")),
										pct -> message = "landschap rondom aanmaken (eenmalig per plek): " + pct + "%");
								mc.execute(() -> begin(p, mode, fov, unknown));
							} catch (Throwable t) {
								RegisseurClient.LOG.error("landschap aanmaken mislukt", t);
								phase = Phase.ERROR;
								message = "landschap aanmaken mislukt: " + t.getMessage();
							}
						}, "regisseur-landschap");
						land.setDaemon(true);
						land.start();
					} catch (Throwable t) {
						RegisseurClient.LOG.error("show voorbereiden mislukt", t);
						phase = Phase.ERROR;
						message = "voorbereiden mislukt: " + t.getMessage();
					}
				});
			} catch (Throwable t) {
				RegisseurClient.LOG.error("muziek of draaiboek mislukt", t);
				phase = Phase.ERROR;
				message = "voorbereiden mislukt: " + t.getMessage();
			}
		}, "regisseur-prep");
		prep.setDaemon(true);
		prep.start();
		return null;
	}

	/** Het draaiboek is klaar: opname klaarzetten en starten. Client-thread. */
	private static void begin(ShowPlan p, GameType mode, int fov, int unknown) {
		Minecraft mc = Minecraft.getInstance();
		try {
			plan = p;
			prevMode = mode;
			prevFov = mc.options.fov().get();
			mc.options.fov().set(fov);
			runDir = Studio.dir("renders").resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
			Files.createDirectories(runDir);
			rec = new Recorder();
			rec.start(Studio.cfg("ffmpeg", "ffmpeg"), runDir.resolve("take.mp4"), runDir.resolve("take.log"),
					Studio.cfg("takeEncoder", "x264"));
			totalFrames = Math.round(p.total() * Recorder.FPS);
			frame = opIdx = sectionWait = rateFrame = 0;
			warmup = 30;
			rateNs = 0;
			fakeMs = Double.NaN;
			lead = Float.parseFloat(Studio.cfg("landLeadFrames", "3")) / Recorder.FPS;
			Camera.reset(p);
			serverDoneFrame = -1;
			holdServer = holdVisible = visWait = serverWait = loadWait = 0;
			waitReason = "";
			visChecks.clear();
			unsettledFrames = serverLateFrames = maxServerLag = 0;
			pending = p.buildTargets.toArray(new ShowPlan.Op[0]);
			Arrays.sort(pending, (x, y) -> Float.compare(x.t, y.t));
			seen = new boolean[pending.length];
			pendingFrom = 0;
			landLag.clear();

			double[] ext = Camera.extremen(); // {hoogste camerapunt, verste afstand}
			// komt de camera ooit in of boven de wolkenlaag, zet de wolken dan uit voor deze opname
			prevClouds = mc.options.cloudStatus().get();
			if (ext[0] > CLOUD_Y - 8) mc.options.cloudStatus().set(CloudStatus.OFF);
			// zichtafstand: alles tot een stuk voorbij het verste camerapunt moet geladen zijn, anders zit het beeld
			// in de mist. Standaard hoogstens 16 chunks; voor reuzen (kathedraal) kan config "maxRenderDistance" tot 32.
			// De opname is frame-locked: een hogere zichtafstand maakt de opname trager, niet de video.
			int maxRd = Math.max(2, Math.min(32, Integer.parseInt(Studio.cfg("maxRenderDistance", "16"))));
			int need = (int) Math.ceil((ext[1] + p.half + 48) / 16.0);
			prevRenderDistance = mc.options.renderDistance().get();
			if (need > prevRenderDistance) mc.options.renderDistance().set(Math.min(maxRd, need));
			int rd = mc.options.renderDistance().get();
			warmup = Math.max(warmup, need > prevRenderDistance ? 120 + 15 * Math.max(0, rd - 16) : 30); // nieuwe chunks laten inladen (32 chunks: 360 frames)

			ShowReport.write(runDir, plan, false, landLag);
			Tijdschatting.begin(p.total());
			phase = Phase.RECORDING;
			message = p.terraOps + " terraform-blokken, " + p.buildOps + " bouwblokken"
					+ (p.sync != null ? String.format(", muziek %.0f BPM", p.sync.bpm) : "")
					+ (unknown > 0 ? " (" + unknown + " onbekende blokken overgeslagen)" : "");
		} catch (Throwable t) {
			RegisseurClient.LOG.error("opname starten mislukt", t);
			fail("opname starten mislukt: " + t.getMessage());
		}
	}

	// =================================================================== 2. opnemen (elk frame)

	/** Begin van elk gerenderd frame: camera zetten en de blokwijzigingen van dit moment versturen. */
	public static void frameStart() {
		if (phase != Phase.RECORDING) return;
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer pl = mc.player;
		if (pl == null) { fail("wereld verlaten tijdens de opname"); return; }
		if (rec.error() != null) { fail("opname: " + rec.error()); return; }
		rec.ensureSize();
		float t = frame / (float) Recorder.FPS;
		Camera.apply(mc, pl, t);

		// Wachtframes: dit frame wordt niet opgenomen (de videotijd staat stil).
		if (!pl.isSpectator()) { hold = true; return; }
		if (warmup > 0) { warmup--; hold = true; waitReason = "opwarmen"; return; } // chunks en nieuwe resolutie laten bijkomen
		if (frame == 0 && !(siteLoaded(mc) && viewLoaded(mc)) && loadWait++ < 60 * 120) { hold = true; heldFrames++; waitReason = "bouwterrein en landschap inladen"; return; }
		hold = rec.busy();
		if (hold) holdBusy++;
		// Geen glitches: een frame wordt pas opgenomen als de wereld echt klaar is. Liever trager dan fout.
		// 1. de server is klaar met de blokken van het vorige frame
		if (!hold && serverDoneFrame < frame - 1 && serverWait < MAX_WAIT) { serverWait++; holdServer++; hold = true; waitReason = "server zet nog blokken"; }
		// 2. de blokken die nu in beeld moeten staan, staan ook echt in de wereld van de client
		if (!hold && !visibleNow(mc, t) && visWait < MAX_WAIT) { visWait++; holdVisible++; hold = true; }
		// 3. de client heeft alle gewijzigde chunks opnieuw getekend (zoals ReplayMod)
		if (!hold && !mc.levelRenderer.hasRenderedAllSections() && sectionWait < MAX_WAIT) { sectionWait++; holdSections++; hold = true; waitReason = "chunks opnieuw tekenen"; }
		if (hold) { heldFrames++; return; }
		sectionWait = visWait = serverWait = 0;

		// blokwijzigingen die nu aan de beurt zijn (lead frames vooruit, want de server loopt achter op het beeld)
		int j = opIdx, n = plan.ops.size();
		while (j < n && plan.ops.get(j).t <= t + lead) j++;
		final int from = opIdx, to = j;
		opIdx = j;
		final boolean fx = t >= plan.tIntro && t < plan.tIntro + plan.tTerra && frame % 2 == 0;
		final float u = (t - plan.tIntro) / Math.max(0.01f, plan.tTerra);
		final boolean fin = !finaleDone && plan.buildOps > 0 && t + lead >= plan.finaleT;
		if (fin) finaleDone = true;
		final ShowPlan p = plan;
		final ServerLevel lv = level;
		final float tv = t + lead;
		final int thisFrame = frame;
		queueVisibilityChecks(from, to);
		server.execute(() -> {
			p.apply(lv, from, to, tv);
			if (fx) p.terraformFx(lv, tv, u);
			if (fin) p.finaleFx(lv);
			serverDoneFrame = thisFrame;
		});

		// Houd de wereld (water, deeltjes, wolken) in de pas met de videotijd: trage laptop -> tragere ticks.
		if (frame - rateFrame >= 30) {
			long now = System.nanoTime();
			if (rateNs != 0) {
				fps = (frame - rateFrame) / ((now - rateNs) / 1e9f);
				final float rate = Math.max(2f, Math.min(20f, 20f * fps / Recorder.FPS));
				server.execute(() -> server.tickRateManager().setTickRate(rate));
			}
			rateNs = now;
			rateFrame = frame;
		}
	}

	/**
	 * Vaste klok (vanuit MinecraftClockMixin): tijdens de opname schuift de speltijd van de client exact een
	 * derde tick op per vastgelegd frame (20 ticks/s bij 60 fps) en staat stil tijdens wachtframes.
	 */
	public static long clock(long realMs) {
		if (phase != Phase.RECORDING) { fakeMs = Double.NaN; return realMs; }
		if (Double.isNaN(fakeMs)) fakeMs = realMs;
		return (long) fakeMs;
	}

	/** Vlak voor de HUD getekend wordt: het beeld is nu schoon. Frame vastleggen. */
	public static void capture() {
		if (phase != Phase.RECORDING || hold) return;
		measureLandings(Minecraft.getInstance());
		measureGlitches(Minecraft.getInstance());
		rec.capture(frame);
		frame++;
		Minecraft mc = Minecraft.getInstance();
		float mspt = mc.level != null ? mc.level.tickRateManager().millisecondsPerTick() : 50f;
		if (!Double.isNaN(fakeMs)) fakeMs += mspt * 20.0 / Recorder.FPS;
		if (frame >= totalFrames) finish();
	}

	/** Zijn alle chunks van het bouwterrein (plus een rand) bij de client ingeladen? Anders zijn er gaten in beeld. */
	private static boolean siteLoaded(Minecraft mc) {
		if (mc.level == null) return false;
		// alleen het terrein dat echt verandert (vierkant of groef) plus een kleine rand; de overgangsrand van
		// "vlakmaken" alleen bij die stijl. Te ruim = stukken buiten het bereik van de server = eeuwig wachten.
		int r = (plan.square ? plan.sqHalf : plan.quarry ? Math.max(plan.qLen, plan.qW) : plan.radius + plan.blend) + 8;
		for (int x = (plan.cx - r) >> 4; x <= (plan.cx + r) >> 4; x++)
			for (int z = (plan.cz - r) >> 4; z <= (plan.cz + r) >> 4; z++)
				if (!mc.level.getChunkSource().hasChunk(x, z)) return false;
		return true;
	}

	/** Heeft de client het landschap rond de camera binnen (tot net voor de zichtafstand)? Tot 2% mag nog ontbreken. */
	private static boolean viewLoaded(Minecraft mc) {
		if (mc.level == null || mc.player == null) return false;
		int r = Math.max(2, Math.min(mc.options.renderDistance().get() - 2, 30));
		int px = mc.player.getBlockX() >> 4, pz = mc.player.getBlockZ() >> 4, missing = 0, total = 0;
		for (int dx = -r; dx <= r; dx++)
			for (int dz = -r; dz <= r; dz++) {
				if (dx * dx + dz * dz > r * r) continue;
				total++;
				if (!mc.level.getChunkSource().hasChunk(px + dx, pz + dz)) missing++;
			}
		return missing <= total * 0.02;
	}

	/** Neemt uit elke verstuurde reeks blokwijzigingen een paar steekproeven (eerste, midden, laatste). */
	private static void queueVisibilityChecks(int from, int to) {
		if (to <= from) return;
		int[] picks = to - from <= 3 ? new int[]{from, to - 1} : new int[]{from, (from + to) / 2, to - 1};
		for (int i : picks) {
			ShowPlan.Op o = plan.ops.get(i);
			if (o.fx >= 3) continue; // invliegers worden later echt geplaatst; die controleert de bouwlijst hieronder
			visChecks.add(new int[]{i, Math.round(o.t * Recorder.FPS) + 1});
		}
	}

	/** Staat alles wat op dit moment in beeld moet staan, ook echt in de wereld van de client? */
	private static boolean visibleNow(Minecraft mc, float t) {
		if (mc.level == null) return true;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		// terraform en bouw: steekproeven
		while (!visChecks.isEmpty()) {
			int[] c = visChecks.peek();
			if (frame < c[1]) break; // nog niet aan de beurt
			ShowPlan.Op o = plan.ops.get(c[0]);
			boolean loaded = mc.level.getChunkSource().hasChunk(o.x >> 4, o.z >> 4);
			if (!loaded || mc.level.getBlockState(pos.set(o.x, o.y, o.z)) == o.state || frame > c[1] + 120) { visChecks.poll(); continue; }
			waitReason = "blok " + o.x + " " + o.y + " " + o.z + " nog niet in beeld";
			return false;
		}
		// bouwwerk: elk blok dat 1 frame geleden had moeten verschijnen
		if (pending != null) {
			for (int i = pendingFrom; i < pending.length; i++) {
				ShowPlan.Op o = pending[i];
				int planned = Math.round(o.t * Recorder.FPS);
				if (planned > frame - 1) break;
				if (seen[i] || frame - planned > 120) continue;
				if (mc.level.getChunkSource().hasChunk(o.x >> 4, o.z >> 4) && mc.level.getBlockState(pos.set(o.x, o.y, o.z)) != o.state) {
					waitReason = "bouwblok " + o.x + " " + o.y + " " + o.z + " nog niet in beeld";
					return false;
				}
			}
		}
		return true;
	}

	/** Wordt dit frame "onaf" opgenomen? Telt voor regel G1 (0 is het doel). */
	private static void measureGlitches(Minecraft mc) {
		if (!mc.levelRenderer.hasRenderedAllSections()) unsettledFrames++;
		int leadFrames = Math.round(lead * Recorder.FPS);
		int lag = frame - serverDoneFrame;
		maxServerLag = Math.max(maxServerLag, lag);
		if (lag > leadFrames) serverLateFrames++;
	}

	/** Welk bouwblok staat in DIT frame voor het eerst echt in de wereld van de client? Verschil met het plan = vertraging. */
	private static void measureLandings(Minecraft mc) {
		if (pending == null || mc.level == null) return;
		float t = frame / (float) Recorder.FPS;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		while (pendingFrom < pending.length && seen[pendingFrom]) pendingFrom++;
		for (int i = pendingFrom; i < pending.length; i++) {
			ShowPlan.Op o = pending[i];
			if (o.t > t + 1f) break;
			if (seen[i]) continue;
			BlockState s = mc.level.getBlockState(pos.set(o.x, o.y, o.z));
			int planned = Math.round(o.t * Recorder.FPS);
			if (s == o.state) {
				seen[i] = true;
				landLag.add(frame - planned);
			} else if (frame - planned > 120) { // na 2 s nog niet zichtbaar: tel als heel laat
				seen[i] = true;
				landLag.add(999);
			}
		}
	}

	// =================================================================== 3. afronden

	/** Laatste frame is binnen: alles terugzetten, gegevens voor de nabewerking schrijven, nabewerking starten. */
	private static void finish() {
		phase = Phase.FINISHING;
		message = "opname afsluiten";
		restore();
		try { ShowReport.write(runDir, plan, true, landLag); } catch (Exception e) { RegisseurClient.LOG.warn("show.json schrijven mislukt", e); }
		PostProcess.prepare(plan, project, runDir); // teller, geluiden, hook (moet op de client-thread)
		final Recorder r = rec;
		final int frames = totalFrames;
		final Path dir = runDir;
		final JsonObject proj = project;
		Thread th = new Thread(() -> {
			try {
				if (!r.finish(frames)) { phase = Phase.ERROR; message = "opname: " + r.error(); return; }
				take = dir.resolve("take.mp4").toString();
				PostProcess.run(proj, dir, frames / (float) Recorder.FPS);
			} catch (Throwable t) {
				RegisseurClient.LOG.error("afwerken mislukt", t);
				phase = Phase.ERROR;
				message = "afwerken mislukt: " + t.getMessage();
			}
		}, "regisseur-post");
		th.setDaemon(true);
		th.start();
	}

	/** Zet resolutie, instellingen, speler en spelmodus terug zoals voor de opname. */
	private static void restore() {
		Minecraft mc = Minecraft.getInstance();
		try {
			if (rec != null) rec.restoreSize();
			mc.options.fov().set(prevFov);
			mc.options.pauseOnLostFocus = prevPause;
			if (prevClouds != null) { mc.options.cloudStatus().set(prevClouds); prevClouds = null; }
			if (prevRenderDistance != null) { mc.options.renderDistance().set(prevRenderDistance); prevRenderDistance = null; }
			if (mc.player != null) {
				mc.player.setPos(px, py, pz);
				mc.player.setYRot(pyaw);
				mc.player.setXRot(ppitch);
			}
			final IntegratedServer s = server;
			final GameType mode = prevMode;
			final ShowPlan pl2 = plan;
			final ServerLevel lv2 = level;
			if (s != null && s.isRunning()) s.execute(() -> {
				if (pl2 != null && lv2 != null) verify = pl2.verify(lv2);
				s.tickRateManager().setTickRate(20f);
				ServerPlayer sp = s.getPlayerList().getPlayer(playerId);
				if (sp != null) {
					sp.teleportTo(px, py, pz);
					if (mode != null) sp.setGameMode(mode);
				}
			});
		} catch (Throwable t) {
			RegisseurClient.LOG.error("herstellen na opname mislukt", t);
		}
	}

	/** Breekt een lopende opname af. Client-thread. */
	public static void fail(String why) {
		boolean wasRecording = phase == Phase.RECORDING;
		phase = Phase.ERROR;
		message = why;
		if (wasRecording) {
			restore();
			if (rec != null) rec.abort();
		}
	}

	/** De toestand voor het studio-scherm. */
	public static JsonObject status() {
		JsonObject o = new JsonObject();
		o.addProperty("phase", phase.name());
		o.addProperty("message", message);
		o.addProperty("frame", frame);
		o.addProperty("totalFrames", totalFrames);
		o.addProperty("fps", fps);
		o.addProperty("output", output);
		o.addProperty("export", export);
		o.addProperty("take", take);
		o.addProperty("judge", judge);
		o.addProperty("verify", verify);
		o.addProperty("heldFrames", heldFrames);
		o.addProperty("camInBlock", Camera.camInBlock);
		o.addProperty("camLifted", Camera.camLifted);
		if (rec != null) { o.addProperty("resizes", rec.resizes); o.addProperty("writeMs", rec.writeNs / 1e6); o.addProperty("copyMs", rec.copyNs / 1e6); }
		o.addProperty("holdBusy", holdBusy); o.addProperty("holdSections", holdSections);
		o.addProperty("unsettledFrames", unsettledFrames); o.addProperty("serverLateFrames", serverLateFrames);
		o.addProperty("maxServerLag", maxServerLag);
		o.addProperty("holdServer", holdServer); o.addProperty("holdVisible", holdVisible);
		o.addProperty("waitReason", waitReason);
		o.addProperty("gameFps", Minecraft.getInstance().getFps());
		o.addProperty("etaSec", busy() ? Tijdschatting.resterend(frame, totalFrames) : -1);
		o.addProperty("elapsedSec", busy() ? Tijdschatting.verstreken() : 0);
		return o;
	}
}
