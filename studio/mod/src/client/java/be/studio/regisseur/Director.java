package be.studio.regisseur;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * De regisseur: stuurt camera, wereld en opname aan per VIDEO-frame.
 * frameStart() en capture() worden vanuit de mixin op de render-thread aangeroepen.
 */
public final class Director {
	public enum Phase { IDLE, PREPARING, RECORDING, FINISHING, RENDERING, DONE, ERROR }

	public static volatile Phase phase = Phase.IDLE;
	public static volatile String message = "", output = "", take = "", judge = "", verify = "", export = "";
	public static volatile int heldFrames;
	public static volatile int frame, totalFrames;
	public static volatile float fps;

	private static ShowPlan plan;
	/** Voor de eindrender: aantallen voor de hooktekst en de lengte van de teaser (precies 1 maat, zodat de muziek op de maat blijft). */
	static volatile int statMined, statPlaced;
	static volatile float teaserLen;
	private static Recorder rec;
	private static JsonObject project;
	private static Path runDir;
	private static IntegratedServer server;
	private static ServerLevel level;
	private static UUID playerId;
	private static int opIdx, warmup;
	private static boolean hold, finaleDone;
	private static GameType prevMode;
	private static double px, py, pz;
	private static float pyaw, ppitch, orbitSpeed, startAngle, distMul;
	private static int prevFov;
	private static boolean prevPause;
	/** Vanilla-wolken hangen op y=192 (aanname, standaard in de overwereld). Komt de camera erboven, dan wolken uit. */
	private static final double CLOUD_Y = 192;
	private static net.minecraft.client.CloudStatus prevClouds;
	private static Integer prevRenderDistance;
	private static long rateNs;
	private static double fakeMs = Double.NaN;
	private static int sectionWait;
	public static volatile int holdBusy, holdSections;
	private static int rateFrame;
	/** Gemeten vaste vertraging tussen server en beeld (3 frames); wereldwijzigingen gaan zoveel eerder uit. */
	private static float lead;

	// ---- shots ----
	private record Shot(String type, float start, float end) { }
	private static final List<Shot> shots = new ArrayList<>();
	private static boolean smoothCuts;
	private static int camInBlock, camLifted;
	/** Camerahoek rond het midden per frame (voor D2 in sync_check.py). */
	private static final java.util.TreeMap<Integer, Float> camAngle = new java.util.TreeMap<>();
	private static float lastLift;

	// ---- meting: wanneer verschijnt elk bouwblok echt in beeld? ----
	private static ShowPlan.Op[] pending;
	private static int pendingFrom;
	private static boolean[] seen;
	private static final List<Integer> landLag = new ArrayList<>();

	public static boolean busy() {
		return phase == Phase.PREPARING || phase == Phase.RECORDING || phase == Phase.FINISHING || phase == Phase.RENDERING;
	}

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
		heldFrames = 0;
		holdBusy = holdSections = 0;
		frame = 0;
		totalFrames = 0;
		finaleDone = false;

		JsonObject tm = Studio.obj(project, "timing"), cam = Studio.obj(project, "camera"), site = Studio.obj(project, "site"),
				world = Studio.obj(project, "world"), music = Studio.obj(project, "music");
		final float tIntro0 = Studio.num(tm, "intro", 2), tTerra0 = Studio.num(tm, "terraform", 5),
				tBuild0 = Studio.num(tm, "build", 14), tOutro0 = Studio.num(tm, "outro", 4);
		final int margin = (int) Studio.num(site, "margin", 3), radius = (int) Studio.num(site, "radius", 12);
		orbitSpeed = Studio.num(cam, "orbitSpeed", 14);
		distMul = Studio.num(cam, "distance", 1f);
		final int fov = (int) Studio.num(cam, "fov", 70);
		final String style = Studio.str(cam, "style", "shots");
		final String schem = Studio.str(project, "schematic", "");
		final String musicFile = Studio.str(music, "file", "");
		final boolean wantSync = !"false".equals(Studio.str(music, "sync", "true"));
		final float musicIn = Studio.num(music, "in", 0);
		String time = Studio.str(world, "time", ""), weather = Studio.str(world, "weather", "");
		final String timeCmd = time.isBlank() ? null : "time set " + time.trim();
		final String weatherCmd = weather.isBlank() ? null : "weather " + weather.trim();
		String tr = Studio.str(cam, "transition", "auto");
		JsonObject terra = Studio.obj(project, "terraform");
		final String tStyle = Studio.str(terra, "style", "square");
		final boolean quarryStyle = "quarry".equals(tStyle), squareStyle = "square".equals(tStyle);
		final int qDepth = (int) Studio.num(terra, "depth", 24);
		final float qLength = Studio.num(terra, "length", 2.5f);

		px = pl.getX(); py = pl.getY(); pz = pl.getZ(); pyaw = pl.getYRot(); ppitch = pl.getXRot();
		// camera start achter de speler: wat jij nu ziet is ongeveer het openingsbeeld
		double fx = -Math.sin(Math.toRadians(pyaw)), fz = Math.cos(Math.toRadians(pyaw));
		startAngle = (float) Math.toDegrees(Math.atan2(-fz, -fx));
		final int cx = (int) Math.floor(px), cz = (int) Math.floor(pz);
		// groef in de kijkrichting van de speler, afgerond op een windrichting (rechte Minecraft-wanden)
		final int qax = Math.abs(fx) >= Math.abs(fz) ? (int) Math.signum(fx) : 0;
		final int qaz = qax == 0 ? (int) Math.signum(fz == 0 ? 1 : fz) : 0;
		final ShowPlan.Quarry qopts = quarryStyle ? new ShowPlan.Quarry(qax, qaz, qDepth, qLength) : null;
		final ShowPlan.Square sopts = squareStyle ? new ShowPlan.Square(qDepth, startAngle) : null;
		final boolean placeStyle = !"fly".equals(Studio.str(Studio.obj(project, "build"), "style", "place"));

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
				// 2. delen van de show op maatstarts leggen
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
				final boolean cut = "cut".equals(tr) || ("auto".equals(tr) && sync != null);
				smoothCuts = !cut;
				buildShots("orbit".equals(style), squareStyle ? "square" : quarryStyle ? "quarry" : "flatten", fa, fb, fc, fe, fsync);
				message = "draaiboek berekenen";
				server.execute(() -> {
					try {
						ServerPlayer sp = server.getPlayerList().getPlayer(playerId);
						level = sp.level();
						// via de server zelf, zodat het ook werkt in een wereld zonder cheats
						if (timeCmd != null) server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), timeCmd);
						if (weatherCmd != null) server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), weatherCmd);
						Schematic sch = schem.isBlank() ? null : Schematic.load(Studio.dir("schematics").resolve(schem));
						ShowPlan p = ShowPlan.build(level, cx, cz, sch, fa, fb - fa, fc - fb, fe - fc, margin, radius, distMul, fsync, qopts, sopts, placeStyle);
						GameType mode = sp.gameMode.getGameModeForPlayer();
						sp.setGameMode(GameType.SPECTATOR);
						mc.execute(() -> begin(p, mode, fov, sch == null ? 0 : sch.unknown));
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

	/** Shotlijst: cuts op de grenzen tussen de delen (op maatstarts als er muziek is), plus een cut halverwege de bouw. */
	private static void buildShots(boolean singleOrbit, String terraStyle, float a, float b, float c, float e, MusicSync sync) {
		boolean quarry = terraStyle.equals("quarry");
		shots.clear();
		if (singleOrbit) { shots.add(new Shot("orbit", 0, e)); return; }
		if (a > 0.05f) shots.add(new Shot("dolly", 0, a));
		shots.add(new Shot(quarry ? "dig" : terraStyle.equals("square") ? "ring" : "crane", a, b));
		if (c - b > 0.05f) {
			float mid = (b + c) / 2;
			if (sync != null) mid = sync.nearestDownbeat(mid, b + 1f);
			if (c - b >= 4f && mid > b + 1f && mid < c - 1f) {
				shots.add(new Shot("orbit", b, mid));
				shots.add(new Shot(quarry ? "canyon" : "hero", mid, c));
			} else shots.add(new Shot("orbit", b, c));
		}
		shots.add(new Shot("reveal", c, e));
	}

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
			frame = 0;
			opIdx = 0;
			warmup = 30;
			rateNs = 0;
			rateFrame = 0;
			fakeMs = Double.NaN;
			sectionWait = 0;
			camInBlock = camLifted = 0;
			camAngle.clear();
			lead = Float.parseFloat(Studio.cfg("landLeadFrames", "3")) / Recorder.FPS;
			lastLift = 0;
			pending = p.buildTargets.toArray(new ShowPlan.Op[0]);
			Arrays.sort(pending, (x, y) -> Float.compare(x.t, y.t));
			seen = new boolean[pending.length];
			pendingFrom = 0;
			landLag.clear();
			{ // komt de camera ooit in of boven de wolkenlaag, zet de wolken dan uit voor deze opname
				double maxEy = 0;
				for (float tt = 0; tt < p.total(); tt += 1 / 30f) maxEy = Math.max(maxEy, shotPose(shotAt(tt), tt)[1]);
				prevClouds = mc.options.cloudStatus().get();
				if (maxEy > CLOUD_Y - 8) mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
				// Zichtafstand: alles tot een stuk voorbij het verste camerapunt moet geladen zijn, anders zit het
				// beeld in de mist (gezien bij het Colosseum). Hoogstens 16 chunks, dat kan een Iris Xe nog aan.
				double far = 0;
				for (float tt = 0; tt < p.total(); tt += 0.25f) {
					double[] e = shotPose(shotAt(tt), tt);
					far = Math.max(far, Math.hypot(e[0] - p.cx, e[2] - p.cz));
				}
				int need = (int) Math.ceil((far + p.half + 48) / 16.0);
				prevRenderDistance = mc.options.renderDistance().get();
				if (need > prevRenderDistance) mc.options.renderDistance().set(Math.min(16, need));
				warmup = Math.max(warmup, need > prevRenderDistance ? 120 : 30); // nieuwe chunks laten inladen
			}
			writeShowJson(false);
			phase = Phase.RECORDING;
			message = p.terraOps + " terraform-blokken, " + p.buildOps + " bouwblokken"
					+ (p.sync != null ? String.format(", muziek %.0f BPM", p.sync.bpm) : "")
					+ (unknown > 0 ? " (" + unknown + " onbekende blokken overgeslagen)" : "");
		} catch (Throwable t) {
			RegisseurClient.LOG.error("opname starten mislukt", t);
			fail("opname starten mislukt: " + t.getMessage());
		}
	}

	private static float smooth(float u) { return u <= 0 ? 0 : u >= 1 ? 1 : u * u * (3 - 2 * u); }

	/** Begin van elk gerenderd frame (voor de camera wordt bepaald). */
	public static void frameStart() {
		if (phase != Phase.RECORDING) return;
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer pl = mc.player;
		if (pl == null) { fail("wereld verlaten tijdens de opname"); return; }
		if (rec.error() != null) { fail("opname: " + rec.error()); return; }
		rec.ensureSize();
		float t = frame / (float) Recorder.FPS;
		applyCamera(mc, pl, t);
		if (!pl.isSpectator()) { hold = true; return; }
		if (warmup > 0) { warmup--; hold = true; return; } // chunks en nieuwe resolutie laten bijkomen
		hold = rec.busy();
		if (hold) holdBusy++;
		// Wacht tot de client alle gewijzigde chunks opnieuw heeft opgebouwd (zoals ReplayMod), met een bovengrens.
		if (!hold && !mc.levelRenderer.hasRenderedAllSections() && sectionWait < 12) { sectionWait++; holdSections++; hold = true; }
		if (hold) { heldFrames++; return; }
		sectionWait = 0;

		int j = opIdx, n = plan.ops.size();
		while (j < n && plan.ops.get(j).t <= t + lead) j++;
		final int from = opIdx, to = j;
		opIdx = j;
		final boolean wave = t >= plan.tIntro && t < plan.tIntro + plan.tTerra && frame % 2 == 0;
		final float u = (t - plan.tIntro) / Math.max(0.01f, plan.tTerra);
		final boolean fin = !finaleDone && plan.buildOps > 0 && t + lead >= plan.finaleT;
		if (fin) finaleDone = true;
		{
			final ShowPlan p = plan;
			final ServerLevel lv = level;
			final float tv = t + lead;
			server.execute(() -> {
				p.apply(lv, from, to, tv);
				if (wave) { if (p.square) p.squareFx(lv, tv); else if (p.quarry) p.quarryFx(lv, u); else p.waveFx(lv, u); }
				if (fin) p.finaleFx(lv);
			});
		}
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
	 * Vaste klok (aangeroepen vanuit MinecraftClockMixin): tijdens de opname schuift de speltijd van de client
	 * exact een derde tick op per vastgelegd frame (20 ticks/s bij 60 fps) en staat stil tijdens wachtframes.
	 */
	public static long clock(long realMs) {
		if (phase != Phase.RECORDING) { fakeMs = Double.NaN; return realMs; }
		if (Double.isNaN(fakeMs)) fakeMs = realMs;
		return (long) fakeMs;
	}

	/** Vlak voor de HUD getekend wordt: het beeld is nu schoon. */
	public static void capture() {
		if (phase != Phase.RECORDING || hold) return;
		measureLandings(Minecraft.getInstance());
		rec.capture(frame);
		frame++;
		Minecraft mc = Minecraft.getInstance();
		float mspt = mc.level != null ? mc.level.tickRateManager().millisecondsPerTick() : 50f;
		if (!Double.isNaN(fakeMs)) fakeMs += mspt * 20.0 / Recorder.FPS;
		if (frame >= totalFrames) finish();
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

	// ------------------------------------------------------------------ camera

	private static Shot shotAt(float t) {
		for (Shot s : shots) if (t < s.end) return s;
		return shots.get(shots.size() - 1);
	}

	/** Oog en kijkpunt voor shot s op tijd t: {ex, ey, ez, lx, ly, lz, hoek}. */
	/** WP1: hoeksnelheid ademt per maat: 0,55x op de maatstart, 1,45x halverwege. Geeft de "gevoelde" tijd sinds start. */
	private static float breath(float t, float start) {
		float bar = 2f, db = 0f;
		if (plan.sync != null && plan.sync.downbeats.size() > 1) {
			db = plan.sync.downbeats.get(0);
			bar = plan.sync.downbeats.get(1) - db;
		}
		double w = 2 * Math.PI / Math.max(0.5f, bar), A = 0.45;
		return (float) ((t - start) - A / w * (Math.sin(w * (t - db)) - Math.sin(w * (start - db))));
	}

	private static double[] shotPose(Shot s, float t) {
		ShowPlan p = plan;
		float dur = Math.max(0.1f, s.end - s.start), u = smooth((t - s.start) / dur), lt = t - s.start;
		float bl = breath(t, s.start); // WP1: shot-tijd die per maat ademt (traag op de maatstart)
		double cxw = p.cx + 0.5, czw = p.cz + 0.5, gy = p.y0 + 1, H = Math.max(4, p.height), B = p.camBase;
		double grow = smooth((t - p.tIntro - p.tTerra) / Math.max(0.1f, p.tBuild)); // hoe ver de bouw is
		if (p.square && s.type.equals("ring")) { // hoog boven het vierkant; volgt de kop van de sleuf, daarna het midden
			double[] head = p.ringHead(t);
			float w = smooth((t - p.sqHollowStart + 0.3f) / 1.2f);
			double lx = head[0] + (p.cx + 0.5 - head[0]) * w, lz = head[1] + (p.cz + 0.5 - head[1]) * w;
			double lyR = p.y0 - (p.y0 - p.sqFloor) * 0.35 * w * (1 - smooth((t - p.sqRiseStart) / Math.max(0.1f, p.sqRiseDur)));
			double dd = Math.max(28, p.sqHalf * 3.2) * (1 + 0.15 * w), el = Math.toRadians(64 - 6 * u);
			double ang = Math.toRadians(startAngle + 20 + 10 * bl), hdR = dd * Math.cos(el);
			// de camera draait rond het midden (niet rond de kop), zo zwiept het beeld niet bij de hoeken
			return new double[]{p.cx + 0.5 + Math.cos(ang) * hdR, p.y0 + dd * Math.sin(el), p.cz + 0.5 + Math.sin(ang) * hdR, lx, lyR, lz};
		}
		if (p.quarry && s.type.equals("dig")) { // hoog en schuin achter het graaffront, langs de groef naar beneden
			float f = Math.min(1f, Math.max(0f, (t - p.tIntro) / Math.max(0.1f, p.tTerra)));
			double front = p.qu0 + Math.max(p.qSegL * 0.5, Math.min(p.qLen - p.qSegL * 0.5, f * p.qLen));
			double[] look = p.pitPoint(front, 0);
			double lyD = (p.rimY + p.y0) / 2.0, dd = Math.max(30, p.qW * 2.2), el = Math.toRadians(62 - 6 * u);
			double side = Math.toRadians(22), hdD = dd * Math.cos(el);
			double bx = -(p.qax * Math.cos(side) - p.qaz * Math.sin(side)), bz = -(p.qaz * Math.cos(side) + p.qax * Math.sin(side));
			return new double[]{look[0] + bx * hdD, lyD + dd * Math.sin(el), look[1] + bz * hdD, look[0], lyD, look[1]};
		}
		if (p.quarry && s.type.equals("canyon")) { // op de rand aan het begin, langs de hele kloof naar het bouwwerk
			float x = Math.min(1f, Math.max(0f, lt / dur));
			double[] eye = p.pitPoint(p.qu0 - 2, -3 + 6 * x);
			return new double[]{eye[0], p.rimY + 5 + 4 * x, eye[1], cxw, gy + H * 0.6, czw};
		}
		if (p.quarry && s.type.equals("dolly")) gy = p.rimY + 1; // voor het graven: kijk naar het maaiveld
		double angDeg, d, elevDeg, ly;
		switch (s.type) {
			case "dolly" -> { // laag openingsshot dat naar de bouwplaats toe schuift (de "hook")
				// Geen zacht optrekken: een Short moet vanaf het eerste frame bewegen (alleen uitlopen naar het einde).
				float x = Math.min(1f, Math.max(0f, lt / dur)), uo = 1 - (1 - x) * (1 - x);
				angDeg = startAngle + 8 * lt; d = B * (0.80 - 0.25 * uo); elevDeg = 14 - 4 * uo; ly = gy + 1.5;
			}
			case "crane" -> { // hoog, bijna van boven: de terraform-golf is dan het best te zien
				angDeg = startAngle + 35 + 9 * lt; d = B * 1.05; elevDeg = 68 - 12 * u; ly = gy;
			}
			case "hero" -> { // laag en dichtbij, omhoog kijkend naar de top die net af raakt
				angDeg = startAngle + 200 - 0.6 * orbitSpeed * bl;
				d = Math.max(p.half + 7, B * 0.6); elevDeg = 6 + 6 * u; ly = gy + H * 0.72;
			}
			case "reveal" -> { // achteruit en omhoog: het hele resultaat in beeld
				angDeg = startAngle + 250 + 0.7 * orbitSpeed * bl; d = p.quarry ? B * (0.7 + 0.35 * u) : B * (0.85 + 0.5 * u);
				elevDeg = p.quarry ? 50 + 16 * u : 22 + 14 * u; // bij een groef: steiler, anders zie je de bodem niet
				ly = gy + H * 0.45;
			}
			default -> { // "orbit": klassieke draai die meegroeit met de bouw
				if (shots.size() == 1) { // oude stijl: één camera voor de hele video
					float total = p.total(), k = t / total;
					double pushIn = 1.0 + 0.32 * (1 - smooth(t / Math.max(0.1f, p.tIntro + p.tTerra * 0.6f)));
					double pullOut = 1.0 + 0.14 * smooth((t - (total - p.tOutro)) / Math.max(0.1f, p.tOutro));
					angDeg = startAngle + orbitSpeed * t; d = B * pushIn * pullOut; elevDeg = 34 - 13 * smooth(k);
					ly = gy + H * (0.1 + 0.36 * smooth((t - p.tIntro - p.tTerra * 0.5f) / Math.max(0.1f, p.tTerra * 0.5f + p.tBuild)));
				} else {
					angDeg = startAngle + 110 + orbitSpeed * bl; d = p.quarry ? B * 0.7 : B * 0.95; elevDeg = p.quarry ? 62 : 30;
					ly = gy + H * (0.15 + 0.35 * grow);
				}
			}
		}
		double elev = Math.toRadians(elevDeg), ang = Math.toRadians(angDeg), hd = d * Math.cos(elev);
		double ex = cxw + Math.cos(ang) * hd, ez = czw + Math.sin(ang) * hd, ey = ly + d * Math.sin(elev);
		// nooit door bomen of heuvels; voor de terraform staan de bomen er nog, dan telt ook het bladerdak
		boolean treesStand = t < p.tIntro + p.tTerra * 0.6f;
		ey = Math.max(ey, gy + (treesStand ? p.clearanceTrees(angDeg) : p.clearance(angDeg)) * hd + 0.5);
		return new double[]{ex, ey, ez, cxw, ly, czw};
	}

	private static void applyCamera(Minecraft mc, LocalPlayer pl, float t) {
		Shot s = shotAt(t);
		double[] e = shotPose(s, t);
		if (smoothCuts) { // vloeiende overgang over 0,8 s rond elke grens
			int i = shots.indexOf(s);
			double[] o = null;
			float w = 0;
			if (i > 0 && t < s.start + 0.4f) { o = shotPose(shots.get(i - 1), t); w = smooth((t - (s.start - 0.4f)) / 0.8f); }
			else if (i < shots.size() - 1 && t > s.end - 0.4f) { o = shotPose(shots.get(i + 1), t); w = 1 - smooth((t - (s.end - 0.4f)) / 0.8f); }
			if (o != null) for (int k = 0; k < 6; k++) e[k] = o[k] + (e[k] - o[k]) * w;
		}
		double ex = e[0], ey = e[1], ez = e[2];
		camAngle.put(Math.round(t * 60), (float) Math.toDegrees(Math.atan2(ez - (plan.cz + 0.5), ex - (plan.cx + 0.5))));
		// Veiligheid: zit het oog toch in een blok of in water, til het dan op tot het vrij is (en tel het).
		if (mc.level != null) {
			BlockPos.MutableBlockPos bp = new BlockPos.MutableBlockPos();
			float lift = 0;
			while (lift < 24 && blocked(mc, bp.set(Math.floor(ex), Math.floor(ey + lift), Math.floor(ez)))) lift += 1;
			lift = Math.max(lift, lastLift * 0.9f); // niet schokken: zachtjes terugzakken
			if (lift > 0.01f) camLifted++;
			ey += lift;
			lastLift = lift;
			if (blocked(mc, bp.set(Math.floor(ex), Math.floor(ey), Math.floor(ez)))) camInBlock++;
		}
		double dx = e[3] - ex, dy = e[4] - ey, dz = e[5] - ez;
		float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
		float pitch = (float) Math.toDegrees(-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
		double fy = ey - pl.getEyeHeight();
		pl.setPos(ex, fy, ez);
		pl.xo = pl.xOld = ex; pl.yo = pl.yOld = fy; pl.zo = pl.zOld = ez;
		pl.setYRot(yaw); pl.setXRot(pitch);
		pl.yRotO = yaw; pl.xRotO = pitch;
		pl.yHeadRot = pl.yHeadRotO = pl.yBodyRot = pl.yBodyRotO = yaw;
		pl.setDeltaMovement(Vec3.ZERO);
	}

	private static boolean blocked(Minecraft mc, BlockPos p) {
		BlockState s = mc.level.getBlockState(p);
		return s.isSolidRender() || !s.getFluidState().isEmpty();
	}

	// ------------------------------------------------------------------ afronden

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

	private static void finish() {
		phase = Phase.FINISHING;
		message = "opname afsluiten";
		restore();
		try { writeShowJson(true); } catch (Exception e) { RegisseurClient.LOG.warn("show.json schrijven mislukt", e); }
		if (plan != null) {
			int m = 0;
			for (ShowPlan.Op o : plan.mined) if (!o.state.canBeReplaced()) m++;
			statMined = m;
			statPlaced = plan.buildTargets.size();
			float bar = 1.5f;
			if (plan.sync != null && plan.sync.downbeats.size() > 1) {
				bar = plan.sync.downbeats.get(1) - plan.sync.downbeats.get(0);
				if (bar > 2.4f) bar /= 2; // trage muziek: halve maat, anders duurt de teaser te lang
			}
			teaserLen = bar;
		}
		if (plan != null && !"off".equals(Studio.str(Studio.obj(project, "sound"), "sfx", "on"))) {
			try {
				Files.writeString(runDir.resolve("sfx.json"), Studio.GSON.toJson(SfxData.build(plan, runDir)), StandardCharsets.UTF_8);
			} catch (Exception e) { RegisseurClient.LOG.warn("geluidseffecten voorbereiden mislukt", e); }
		}
		String cm = Studio.str(Studio.obj(project, "overlay"), "counter", "mined"); // mined (standaard) | placed | off
		java.util.List<ShowPlan.Op> csrc = plan == null ? null : "placed".equals(cm) ? plan.buildTargets : plan.mined;
		if (!"off".equals(cm) && csrc != null && !csrc.isEmpty()) {
			try {
				Files.writeString(runDir.resolve("counter.json"), Studio.GSON.toJson(CounterData.build(csrc, runDir)), StandardCharsets.UTF_8);
			} catch (Exception e) { RegisseurClient.LOG.warn("materiaalteller voorbereiden mislukt", e); }
		}
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

	/** show.json: het draaiboek en de metingen, voor pipeline/sync_check.py (de ritme-judge). */
	private static void writeShowJson(boolean done) throws Exception {
		ShowPlan p = plan;
		JsonObject o = new JsonObject();
		o.addProperty("fps", Recorder.FPS);
		o.addProperty("total", p.total());
		JsonObject sec = new JsonObject();
		sec.addProperty("intro", p.tIntro);
		sec.addProperty("terraEnd", p.tIntro + p.tTerra);
		sec.addProperty("buildEnd", p.tIntro + p.tTerra + p.tBuild);
		sec.addProperty("end", p.total());
		o.add("sections", sec);
		o.addProperty("finale", p.buildOps > 0 ? p.finaleT : -1);
		JsonArray sh = new JsonArray();
		for (Shot s : shots) {
			JsonObject x = new JsonObject();
			x.addProperty("type", s.type); x.addProperty("start", s.start); x.addProperty("end", s.end);
			sh.add(x);
		}
		o.add("shots", sh);
		o.addProperty("smoothCuts", smoothCuts);
		if (p.sync != null) {
			JsonObject m = new JsonObject();
			m.addProperty("bpm", p.sync.bpm);
			m.addProperty("period", p.sync.period);
			m.addProperty("in", p.sync.in);
			m.addProperty("confidence", p.sync.confidence);
			JsonArray b = new JsonArray(), d = new JsonArray();
			for (float x : p.sync.beats) if (x <= p.total() + 1) b.add(x);
			for (float x : p.sync.downbeats) if (x <= p.total() + 1) d.add(x);
			m.add("beats", b);
			m.add("downbeats", d);
			o.add("music", m);
		}
		JsonArray land = new JsonArray();
		JsonArray landY = new JsonArray();
		for (ShowPlan.Op op : p.buildTargets) { land.add(op.t); landY.add(op.y); }
		o.add("landings", land);
		o.add("landingsY", landY);
		JsonArray ca = new JsonArray();
		for (float v : camAngle.values()) ca.add(Math.round(v * 100) / 100f);
		o.add("camAngle", ca);
		o.addProperty("camAngleStartFrame", camAngle.isEmpty() ? 0 : camAngle.firstKey());
		if (p.square) { // terraform-tijden per deelfase (voor D1 in sync_check.py), uitgedund tot ~4000 per fase
			JsonArray ring = new JsonArray(), hollow = new JsonArray(), rise = new JsonArray();
			int nr = 0, nh = 0, ni = 0;
			int inner = p.sqHalf - p.sqRing; // sleuf = buiten het eiland (zelfde indeling als in ShowPlan)
			java.util.function.Predicate<ShowPlan.Op> isRing = op -> Math.max(Math.abs(op.x - p.cx), Math.abs(op.z - p.cz)) > inner;
			for (ShowPlan.Op op : p.ops) {
				if (op.t >= p.tIntro + p.tTerra) continue;
				if (op.state.isAir()) { if (isRing.test(op)) nr++; else nh++; }
				else if (op.t >= p.sqRiseStart) ni++;
			}
			int sr = Math.max(1, nr / 4000), shh = Math.max(1, nh / 4000), si = Math.max(1, ni / 4000), kr = 0, kh = 0, ki = 0;
			for (ShowPlan.Op op : p.ops) {
				if (op.t >= p.tIntro + p.tTerra) continue;
				float v = Math.round(op.t * 1000) / 1000f;
				if (op.state.isAir()) {
					if (isRing.test(op)) { if (kr++ % sr == 0) ring.add(v); }
					else if (kh++ % shh == 0) hollow.add(v);
				} else if (op.t >= p.sqRiseStart && ki++ % si == 0) rise.add(v);
			}
			JsonObject tt = new JsonObject();
			tt.add("ring", ring); tt.add("hollow", hollow); tt.add("rise", rise);
			o.add("terraTimes", tt);
		}
		if (done) {
			JsonObject meas = new JsonObject();
			meas.addProperty("camInBlock", camInBlock);
			meas.addProperty("camLifted", camLifted);
			JsonArray lag = new JsonArray();
			for (int v : landLag) lag.add(v);
			meas.add("landLagFrames", lag);
			o.add("measured", meas);
		}
		Files.writeString(runDir.resolve("show.json"), Studio.GSON.toJson(o), StandardCharsets.UTF_8);
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
		o.addProperty("camInBlock", camInBlock);
		o.addProperty("camLifted", camLifted);
		if (rec != null) { o.addProperty("resizes", rec.resizes); o.addProperty("writeMs", rec.writeNs / 1e6); o.addProperty("copyMs", rec.copyNs / 1e6); }
		o.addProperty("holdBusy", holdBusy); o.addProperty("holdSections", holdSections);
		o.addProperty("gameFps", Minecraft.getInstance().getFps());
		return o;
	}
}
