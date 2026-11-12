package be.studio.regisseur;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Het volledige draaiboek van een show, vooraf berekend: elke blokwijziging met zijn tijdstip in VIDEO-tijd.
 * Daardoor is de opname frame-exact en onafhankelijk van hoe snel de laptop rendert.
 */
public final class ShowPlan {
	public static final class Op {
		public final float t;
		public final int x, y, z;
		public final BlockState state;
		public final BlockState fxState; // voor breekdeeltjes (fx 1)
		public final byte fx;            // 0 geen, 1 breekdeeltjes, 2 bouwvonkjes, 3 invliegen, 4 invliegen + vonkjes

		Op(float t, int x, int y, int z, BlockState state, BlockState fxState, int fx) {
			this.t = t; this.x = x; this.y = y; this.z = z; this.state = state; this.fxState = fxState; this.fx = (byte) fx;
		}
	}

	public final List<Op> ops = new ArrayList<>();
	public int cx, cz, y0;          // midden van de bouwplaats en grondniveau
	public int radius, blend;       // vlakke schijf + overgangsrand
	public int half, height;        // halve breedte en hoogte van het bouwwerk
	public float tIntro, tTerra, tBuild, tOutro;
	public int terraOps, buildOps;
	public final FlyIn fly = new FlyIn();
	public double camBase;                 // basisafstand van de camera
	public final float[] camTan = new float[72]; // per 5 graden: minimale helling voor een vrije zichtlijn
	public final float[] camTanTrees = new float[72]; // idem, maar met de bomen die er voor de terraform nog staan
	public final List<Op> buildTargets = new ArrayList<>(); // eindtoestand, voor de controle achteraf
	public final List<Op> mined = new ArrayList<>();        // weggegraven blokken (origineel), voor de materiaalteller
	public MusicSync sync;                 // null = geen muziek of niet op de maat
	public float finaleT;                  // tijdstip van het slotvuurwerk

	/** Instellingen voor de stijl "uitgraven": as (kardinale kijkrichting), diepte en lengte van de groef. */
	public record Quarry(int ax, int az, int depth, float lengthMul) { }
	/** Stijl "vierkant": smalle sleuf rond een vierkant, binnenkant weg, gras komt omhoog. startDeg = kant van de camera. */
	public record Square(int depth, float startDeg) { }
	public boolean square;
	public int sqHalf, sqRing, sqFloor;    // halve zijde, breedte van de sleuf, bodem van het gat
	public float sqRingStart, sqRingDur, sqHollowStart, sqHollowDur, sqRiseStart, sqRiseDur;
	private double sqA0;                   // starthoek van de sleuf (radialen)
	public boolean quarry;                 // true = uitgraven, false = vlakmaken
	public int rimY;                       // maaiveld (voor de opening; bij vlakmaken gelijk aan y0)
	public int qx0, qz0, qu0, qLen, qW, qSegL; // groef: startpunt, afstand tot de rand, lengte, breedte, vaklengte
	public int qax, qaz;                   // as van de groef

	/** Wereldpunt op de middenlijn van de groef, op afstand u langs de as en v dwars erop. */
	public double[] pitPoint(double u, double v) {
		return new double[]{qx0 + 0.5 + qax * u - qaz * v, qz0 + 0.5 + qaz * u + qax * v};
	}

	private boolean inPit(int x, int z) {
		int du = (x - qx0) * qax + (z - qz0) * qaz, dv = (x - qx0) * -qaz + (z - qz0) * qax + qW / 2;
		return du >= qu0 && du < qu0 + qLen && dv >= 0 && dv < qW;
	}

	public float total() { return tIntro + tTerra + tBuild + tOutro; }

	private static final Map<Block, Boolean> TREEISH = new HashMap<>();

	private static boolean treeish(BlockState s) {
		return TREEISH.computeIfAbsent(s.getBlock(), b -> {
			String p = BuiltInRegistries.BLOCK.getKey(b).getPath();
			return p.contains("leaves") || p.endsWith("_log") || p.endsWith("_wood") || p.endsWith("_stem")
					|| p.contains("mushroom_block") || p.contains("vine") || p.equals("bamboo") || p.contains("roots");
		});
	}

	private static boolean ground(BlockState s) {
		return !s.isAir() && s.blocksMotion() && s.isSolidRender() && !treeish(s);
	}

	private static boolean fluid(BlockState s) { return !s.getFluidState().isEmpty(); }

	/** Hoogste vloeistofblok boven de grond, of Integer.MIN_VALUE als de kolom droog is. */
	private static int waterY(ServerLevel level, int x, int z, int top, int gy, BlockPos.MutableBlockPos p) {
		for (int y = top; y > gy; y--) if (fluid(level.getBlockState(p.set(x, y, z)))) return y;
		return Integer.MIN_VALUE;
	}

	private static float smooth(float u) { return u <= 0 ? 0 : u >= 1 ? 1 : u * u * (3 - 2 * u); }

	/** Bouwcurve: aandeel geplaatste blokken op tijd x (0..1). Traag begin en einde (ease-in/out), snel midden. */
	private static float easeBuild(float x) { return 0.25f * x + 0.75f * smooth(x); }

	/** Omgekeerde van easeBuild: op welk moment (0..1) is aandeel u bereikt. */
	private static float easeInv(float u) {
		float lo = 0, hi = 1;
		for (int i = 0; i < 28; i++) { float m = (lo + hi) / 2; if (easeBuild(m) < u) lo = m; else hi = m; }
		return (lo + hi) / 2;
	}

	/** Ease voor de terraform (WP1): traag vertrekken, snel in het midden, zacht landen. Steilte aan de randen 0,15. */
	static float easeS(float u) {
		u = Math.max(0, Math.min(1, u));
		float s5 = u * u * u * (u * (u * 6 - 15) + 10); // smootherstep: zachter vertrekken en landen dan smoothstep
		return 0.1f * u + 0.9f * s5;
	}

	/** Omgekeerde van easeS: op welk tijdsaandeel is aandeel v klaar. */
	static float easeSInv(float v) {
		float lo = 0, hi = 1;
		for (int i = 0; i < 24; i++) { float m = (lo + hi) / 2; if (easeS(m) < v) lo = m; else hi = m; }
		return (lo + hi) / 2;
	}

	/**
	 * Puls op de tel (WP1): vlak voor elke tel even inhouden (dichtheid zakt naar 50% in de laatste 0,1 s), zodat
	 * elke tel voelt als een nieuwe aanzet. Tijdvervorming G met G' = 1/d, daarna terug op het venster geschaald.
	 * Geen grote pieken: de rest van de tel wordt maar ~10% dichter (B2 blijft gelden).
	 */
	static float[] beatWarp(float[] times, List<Float> beats, float t0, float t1) {
		if (beats == null || beats.size() < 2 || times.length == 0 || t1 - t0 < 0.5f) return times;
		final float step = 0.001f, DIP = 0.10f, DEPTH = 0.5f;
		int n = (int) Math.ceil((t1 - t0) / step) + 1;
		double[] g = new double[n];
		int bi = 0;
		for (int i = 1; i < n; i++) {
			float tau = t0 + i * step;
			while (bi < beats.size() && beats.get(bi) < tau) bi++;
			float d = 1f;
			if (bi < beats.size()) {
				float to = beats.get(bi) - tau; // tijd tot de volgende tel
				if (to < DIP) d = 1f - DEPTH * (0.5f - 0.5f * (float) Math.cos(Math.PI * (1 - to / DIP)));
			}
			g[i] = g[i - 1] + step / d;
		}
		double scale = (t1 - t0) / g[n - 1];
		float[] out = new float[times.length];
		for (int k = 0; k < times.length; k++) {
			double x = (times[k] - t0) / step;
			int i = (int) Math.max(0, Math.min(n - 2, Math.floor(x)));
			double fr = Math.max(0, Math.min(1, x - i));
			out[k] = (float) (t0 + (g[i] + (g[i + 1] - g[i]) * fr) * scale);
		}
		return out;
	}

	private static float jitter(int x, int z) { // deterministische ruis 0..1
		int h = x * 73428767 ^ z * 912931;
		h ^= h >>> 13; h *= 0x5bd1e995; h ^= h >>> 15;
		return (h & 0xFFFF) / 65535f;
	}

	private static int groundY(ServerLevel level, int x, int z, BlockPos.MutableBlockPos p) {
		int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1, min = level.getMinY();
		while (y > min && !ground(level.getBlockState(p.set(x, y, z)))) y--;
		return y;
	}

	/** Moet op de server-thread draaien (leest de wereld). */
	public static ShowPlan build(ServerLevel level, int cx, int cz, Schematic sch,
			float tIntro, float tTerra, float tBuild, float tOutro, int margin, int radiusNoSchematic, float distMul,
			MusicSync sync, Quarry quarry, Square square, boolean placeStyle) {
		ShowPlan p = new ShowPlan();
		p.fly.setPlaceStyle(placeStyle);
		p.sync = sync;
		p.cx = cx; p.cz = cz;
		p.tIntro = tIntro; p.tTerra = tTerra; p.tBuild = sch == null ? 0 : tBuild; p.tOutro = tOutro;
		p.half = sch == null ? radiusNoSchematic : (Math.max(sch.sx, sch.sz) + 1) / 2;
		p.height = sch == null ? 6 : sch.sy;
		p.radius = (int) Math.ceil(p.half * 1.42) + margin; // schijf rond de hoeken van het bouwwerk
		p.blend = Math.max(5, p.radius / 2);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		p.y0 = groundY(level, cx, cz, pos);
		{ // staat het midden in het water, dan bouwen we een eiland op waterhoogte in plaats van op de bodem
			int w = waterY(level, cx, cz, level.getHeight(Heightmap.Types.WORLD_SURFACE, cx, cz) - 1, p.y0, pos);
			if (w > p.y0) p.y0 = w;
		}

		if (square != null) {
			p.squareTerraform(level, sch, square, margin, radiusNoSchematic, tIntro, tTerra, distMul);
		} else if (quarry != null) {
			p.digQuarry(level, sch, quarry, margin, radiusNoSchematic, tIntro, tTerra, distMul, sync);
		} else {
		final BlockState AIR = Blocks.AIR.defaultBlockState(), GRASS = Blocks.GRASS_BLOCK.defaultBlockState(),
				DIRT = Blocks.DIRT.defaultBlockState(), STONE = Blocks.STONE.defaultBlockState();
		int outer = p.radius + p.blend, clear = outer + 4, gn = 2 * clear + 1;
		float t0 = tIntro;
		// Hoogtekaart van de natuurlijke grond; de overgangsrand gebruikt een uitgevlakte versie (5x5 gemiddelde,
		// zelfde idee als WorldEdit //smooth) zodat de rand glooit in plaats van te trappen.
		int[][] gyMap = new int[gn][gn], topMap = new int[gn][gn], hMap = new int[gn][gn];
		for (int ix = 0; ix < gn; ix++)
			for (int iz = 0; iz < gn; iz++) {
				int x = cx - clear + ix, z = cz - clear + iz;
				topMap[ix][iz] = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
				gyMap[ix][iz] = groundY(level, x, z, pos);
				// natuurlijke hoogte: wateroppervlak telt als oppervlak, zodat we geen put in een meer graven
				hMap[ix][iz] = Math.max(gyMap[ix][iz], waterY(level, x, z, topMap[ix][iz], gyMap[ix][iz], pos));
			}
		for (int ix = 0; ix < gn; ix++) {
			for (int iz = 0; iz < gn; iz++) {
				int dx = ix - clear, dz = iz - clear;
				float d = (float) Math.sqrt(dx * dx + dz * dz);
				if (d > clear) continue;
				int x = cx + dx, z = cz + dz;
				int top = topMap[ix][iz], gy = gyMap[ix][iz];
				boolean wet = hMap[ix][iz] > gy;
				float tcol = t0 + tTerra * Math.min(1f, d / outer) * 0.9f + tTerra * 0.06f * jitter(x, z);
				if (sync != null) tcol = Math.max(t0, Math.min(t0 + tTerra * 0.97f, sync.snap8(tcol))); // grond beweegt in stoten op de maat
				if (d > outer) { // buitenste ring: alleen bomen weghalen zodat er geen halve kruinen blijven hangen
					for (int y = top; y > gy; y--) {
						BlockState st = level.getBlockState(pos.set(x, y, z));
						if (treeish(st)) p.ops.add(new Op(tcol, x, y, z, AIR, y == top ? st : null, y == top ? 1 : 0));
					}
					continue;
				}
				int target;
				if (d <= p.radius) target = p.y0;
				else {
					float sum = 0;
					int cnt = 0;
					for (int a = Math.max(0, ix - 2); a <= Math.min(gn - 1, ix + 2); a++)
						for (int b = Math.max(0, iz - 2); b <= Math.min(gn - 1, iz + 2); b++) { sum += hMap[a][b]; cnt++; }
					target = Math.round(p.y0 + (sum / cnt - p.y0) * smooth((d - p.radius) / p.blend));
				}
				boolean first = true, changed = false;
				for (int y = top; y > target; y--) { // afgraven en begroeiing/sneeuw weghalen
					BlockState st = level.getBlockState(pos.set(x, y, z));
					if (st.isAir() || fluid(st)) continue; // water laten staan: weghalen geeft een watermuur
					p.ops.add(new Op(tcol + (top - y) * 0.006f, x, y, z, AIR, first ? st : null, first ? 1 : 0));
					first = false;
					if (y <= gy) changed = true;
				}
				if (wet && d > p.radius && target <= hMap[ix][iz]) continue; // buiten de schijf blijft water gewoon water
				for (int y = gy + 1; y <= target; y++) { // ophogen
					BlockState st = y == target ? GRASS : (target - y <= 3 ? DIRT : STONE);
					p.ops.add(new Op(tcol + (y - gy) * 0.006f, x, y, z, st, st, y == target ? 1 : 0));
				}
				if (target <= gy && (d <= p.radius || changed)) { // oppervlak afwerken: gewoon gras, geen sneeuwgras of kale steen
					if (level.getBlockState(pos.set(x, target, z)) != GRASS)
						p.ops.add(new Op(tcol + 0.2f, x, target, z, GRASS, null, 0));
				}
			}
		}
		p.terraOps = p.ops.size();
		p.camBase = Math.max(22, (p.half + 2) * 2.9 + p.height * 0.5) * distMul;
		p.computeCameraClearance(level, gyMap, clear);
		p.rimY = p.y0;
		}

		if (sch != null) {
			final int n = sch.size(), ox = p.cx - sch.sx / 2, oy = p.y0 + 1, oz = p.cz - sch.sz / 2;
			Integer[] order = new Integer[n];
			for (int i = 0; i < n; i++) order[i] = i;
			// laag voor laag omhoog, binnen een laag een diagonale veeg met wat ruis: leest als "groeien"
			java.util.Arrays.sort(order, Comparator.<Integer>comparingInt(i -> sch.ys.getInt(i))
					.thenComparingDouble(i -> sch.xs.getInt(i) + sch.zs.getInt(i) * 0.6
							+ 2.5 * jitter(sch.xs.getInt(i), sch.zs.getInt(i) * 31 + sch.ys.getInt(i))));
			float tb = tIntro + tTerra;
			// Invliegen kost per blok een entiteit; bij heel grote bouwwerken vliegt een deel en verschijnt de rest direct.
			float perSec = n / Math.max(0.5f, tBuild);
			int flyEvery = Math.max(1, (int) Math.ceil(perSec / 500f));
			int sparkEvery = Math.max(1, n / 500);
			// Blok per blok, laag per laag. Tussen twee lagen een kort adempauze, en over de hele bouw een
			// ease-in/ease-out: traag beginnen, versnellen in het midden, rustig afwerken aan de top.
			final int GAP = 3; // pauze tussen lagen, in "blokplaatsen"
			int layers = 0;
			for (int k = 0; k < n; k++) if (k == 0 || sch.ys.getInt(order[k]) != sch.ys.getInt(order[k - 1])) layers++;
			float slots = n + GAP * Math.max(0, layers - 1);
			float win = Math.max(0.5f, tBuild * 0.97f - p.fly.lead());
			int gapsSoFar = 0;
			float[] lands = new float[n];
			for (int k = 0; k < n; k++) {
				if (k > 0 && sch.ys.getInt(order[k]) != sch.ys.getInt(order[k - 1])) gapsSoFar++;
				lands[k] = tb + p.fly.lead() + win * easeInv((k + 0.5f + GAP * gapsSoFar) / slots);
			}
			if (sync != null) lands = beatWarp(lands, sync.beats, tb + p.fly.lead(), tb + p.fly.lead() + win); // WP1: puls op de tel
			for (int k = 0; k < n; k++) {
				int i = order[k];
				float land = lands[k];
				int x = ox + sch.xs.getInt(i), y = oy + sch.ys.getInt(i), z = oz + sch.zs.getInt(i);
				BlockState st = sch.states.get(i);
				boolean spark = k % sparkEvery == 0;
				if (k % flyEvery == 0) p.ops.add(new Op(land - p.fly.lead(), x, y, z, st, null, spark ? 4 : 3));
				else p.ops.add(new Op(land, x, y, z, st, null, spark ? 2 : 0));
				p.buildTargets.add(new Op(land, x, y, z, st, null, 0));
			}
			p.buildOps = n;
			p.finaleT = tb + tBuild;
		}
		p.ops.sort((a, b) -> Float.compare(a.t, b.t));
		// gemijnde blokken (voor de materiaalteller): wat er stond op elke plek die leeggemaakt wordt.
		// De wereld is hier nog onaangeroerd, dus de huidige toestand is het origineel.
		java.util.HashSet<Long> seen = new java.util.HashSet<>();
		for (Op o : p.ops) {
			if (!o.state.isAir() || !seen.add(BlockPos.asLong(o.x, o.y, o.z))) continue;
			BlockState st = level.getBlockState(pos.set(o.x, o.y, o.z));
			if (!st.isAir() && !fluid(st)) p.mined.add(new Op(o.t, o.x, o.y, o.z, st, null, 0));
		}
		return p;
	}

	/** Voert ops [from, to) uit. Server-thread. Geen buur-updates: snel en het bouwwerk blijft exact zoals opgeslagen. */
	public void apply(ServerLevel level, int from, int to, float tick) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		fly.pump(level, tick);
		final int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
		for (int i = from; i < to; i++) {
			Op o = ops.get(i);
			if (o.fx >= 3) { fly.launch(level, tick, o.x, o.y, o.z, o.state, cx + 0.5, cz + 0.5, o.fx == 4); continue; }
			pos.set(o.x, o.y, o.z);
			if (o.fx == 1 && o.fxState != null) level.levelEvent(null, 2001, pos, Block.getId(o.fxState));
			level.setBlock(pos, o.state, flags);
			if (o.fx == 2)
				level.sendParticles(ParticleTypes.WAX_OFF, true, true, o.x + 0.5, o.y + 0.6, o.z + 0.5, 4, 0.35, 0.35, 0.35, 0.0);
		}
	}

	/** Lichtende ring op het golffront van de terraform. u = 0..1 voortgang. */
	public void waveFx(ServerLevel level, float u) {
		float r = (radius + blend) * u;
		int n = Math.max(10, (int) (r * 1.6f));
		double phase = u * 40;
		for (int i = 0; i < n; i++) {
			double a = phase + i * (Math.PI * 2 / n);
			level.sendParticles(ParticleTypes.END_ROD, true, true,
					cx + 0.5 + Math.cos(a) * r, y0 + 1.3, cz + 0.5 + Math.sin(a) * r, 1, 0.05, 0.25, 0.05, 0.01);
		}
	}

	/** Slotvuurwerk boven het afgewerkte bouwwerk. */
	public void finaleFx(ServerLevel level) {
		level.sendParticles(ParticleTypes.FIREWORK, true, true, cx + 0.5, y0 + height + 3, cz + 0.5,
				260, half * 0.6, 2.0, half * 0.6, 0.12);
		level.sendParticles(ParticleTypes.END_ROD, true, true, cx + 0.5, y0 + height * 0.5, cz + 0.5,
				160, half * 0.8, height * 0.4, half * 0.8, 0.04);
	}

	/** Na de show: alles laten landen en tellen hoeveel blokken afwijken van het schematic. Server-thread. */
	public String verify(ServerLevel level) {
		fly.flush(level);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int wrong = 0;
		for (Op o : buildTargets) if (level.getBlockState(pos.set(o.x, o.y, o.z)) != o.state) wrong++;
		return "bouw: " + (buildTargets.size() - wrong) + "/" + buildTargets.size() + " blokken juist, "
				+ fly.launched + " ingevlogen, " + fly.inFlight() + " displays over";
	}

	/**
	 * Voorkomt dat de camera in bomen of heuvels hangt. Per richting zoeken we het hoogste obstakel tussen de
	 * bouwplaats en de verste camerastand en bewaren de helling die de zichtlijn minstens moet hebben.
	 */
	private void computeCameraClearance(ServerLevel level, int[][] gyMap, int clear) {
		double dMax = camBase * 1.4 + 4;
		float[] raw = new float[camTan.length], rawT = new float[camTan.length];
		for (int i = 0; i < raw.length; i++) {
			double a = Math.toRadians(i * 360.0 / raw.length), ca = Math.cos(a), sa = Math.sin(a);
			float need = 0, needT = 0;
			for (double sdist = 2; sdist <= dMax; sdist += 1.5) {
				int x = (int) Math.floor(cx + 0.5 + ca * sdist), z = (int) Math.floor(cz + 0.5 + sa * sdist);
				int ix = x - cx + clear, iz = z - cz + clear;
				// binnen de opgeruimde zone telt de grond (bomen gaan weg); daarbuiten alles, ook boomkruinen
				int surf = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
				needT = Math.max(needT, (float) ((surf + 2.0 - (y0 + 1)) / sdist));
				if (sdist < radius + 1) continue;
				int obs = ix >= 0 && iz >= 0 && ix < gyMap.length && iz < gyMap.length
						? Math.min(gyMap[ix][iz], y0 + (int) Math.ceil(Math.max(0, sdist - radius)))
						: level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
				need = Math.max(need, (float) ((obs + 2.0 - (y0 + 1)) / sdist));
			}
			raw[i] = need;
			rawT[i] = needT;
		}
		smoothRing(raw, camTan);
		smoothRing(rawT, camTanTrees);
	}

	private static void smoothRing(float[] raw, float[] camTan) {
		int nA = raw.length;
		for (int i = 0; i < nA; i++) { // verbreden (maximum over buren) en uitvlakken: geen schokken in de baan
			float mx = 0;
			for (int k = -3; k <= 3; k++) mx = Math.max(mx, raw[(i + k + nA) % nA]);
			camTan[i] = mx;
		}
		for (int pass = 0; pass < 3; pass++) {
			float[] t = camTan.clone();
			for (int i = 0; i < nA; i++)
				camTan[i] = Math.max(raw[i], (t[(i + nA - 1) % nA] + 2 * t[i] + t[(i + 1) % nA]) / 4f);
		}
	}

	/** Minimale helling (hoogte per blok afstand) voor de zichtlijn in deze richting, vloeiend tussen de steunpunten. */
	public float clearanceTrees(double angleDeg) { return interp(camTanTrees, angleDeg); }

	public float clearance(double angleDeg) { return interp(camTan, angleDeg); }

	private static float interp(float[] camTan, double angleDeg) {
		double f = ((angleDeg % 360) + 360) % 360 / (360.0 / camTan.length);
		int i = (int) Math.floor(f) % camTan.length, j = (i + 1) % camTan.length;
		float u = (float) (f - Math.floor(f));
		return camTan[i] * (1 - u) + camTan[j] * u;
	}

	/**
	 * Stijl "uitgraven" (naar het voorbeeld van de gebruiker): een lange, diepe groef met loodrechte wanden.
	 * De groef wordt vak per vak uitgegraven; binnen een vak zakt de grond laag per laag weg (hele laag tegelijk),
	 * zodat je een blok terrein ziet "wegzinken" en de wanden met ertsen open komen te liggen.
	 * Water en lava in de wanden worden dichtgemaakt met steen, zodat de put niet onderloopt.
	 */
	private void digQuarry(ServerLevel level, Schematic sch, Quarry q, int margin, int radiusNoSchematic,
			float tIntro, float tTerra, float distMul, MusicSync sync) {
		quarry = true;
		qax = q.ax(); qaz = q.az(); qx0 = cx; qz0 = cz; qu0 = 3;
		int along = sch == null ? 2 * radiusNoSchematic : (qax != 0 ? sch.sx : sch.sz);
		int across = sch == null ? 2 * radiusNoSchematic : (qax != 0 ? sch.sz : sch.sx);
		qW = across + 2 * margin;
		qLen = Math.max(along + 2 * margin, Math.round(qW * Math.max(1f, q.lengthMul())));
		// bouwwerk aan het verre einde van de groef
		double uC = qu0 + qLen - margin - along / 2.0;
		double[] c = pitPoint(uC, 0);
		cx = (int) Math.floor(c[0]); cz = (int) Math.floor(c[1]);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		rimY = groundY(level, cx, cz, pos);
		y0 = rimY; // de bouw staat op het maaiveld; de kloof steekt er bovenuit
		final int floorY = y0;
		final BlockState AIR = Blocks.AIR.defaultBlockState(), STONE = Blocks.STONE.defaultBlockState();
		{ // in het water graven kan niet: de put loopt vol. Dan liever meteen een duidelijke melding.
			int wet = 0, n = 0;
			for (int u = 0; u < qLen; u += 2)
				for (int v = 0; v < qW; v += 2) {
					int[] xz = cell(u, v);
					int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, xz[0], xz[1]) - 1;
					if (fluid(level.getBlockState(pos.set(xz[0], top, xz[1])))) wet++;
					n++;
				}
			if (wet > n * 0.15)
				throw new IllegalStateException("hier ligt te veel water (" + Math.round(100f * wet / n)
						+ "% van de groef). Ga op het land staan, of kies de stijl Vlakmaken");
		}

		int nW = Math.max(1, Math.round(qW / 14f)), segW = (qW + nW - 1) / nW;
		int nL = Math.max(1, Math.round(qLen / (float) segW)), segL = (qLen + nL - 1) / nL;
		qSegL = segL;
		int nSeg = nW * nL;
		float step = tTerra * 0.86f / (nSeg + 0.8f), dur = step * 1.8f;
		int k = 0;
		for (int i = 0; i < nL; i++) {
			for (int jj = 0; jj < nW; jj++, k++) {
				int j = (i % 2 == 0) ? jj : nW - 1 - jj; // slingerend over de breedte
				float ts = tIntro + k * step;
				if (sync != null) ts = Math.max(tIntro, sync.snap8(ts));
				float te = Math.min(tIntro + tTerra * 0.98f, ts + dur);
				int u1 = i * segL, u2 = Math.min(qLen, u1 + segL), v1 = j * segW, v2 = Math.min(qW, v1 + segW);
				int segTop = floorY + 1;
				int[][] tops = new int[u2 - u1][v2 - v1];
				for (int u = u1; u < u2; u++)
					for (int v = v1; v < v2; v++) {
						int[] xz = cell(u, v);
						tops[u - u1][v - v1] = level.getHeight(Heightmap.Types.WORLD_SURFACE, xz[0], xz[1]) - 1;
						segTop = Math.max(segTop, tops[u - u1][v - v1]);
					}
				float span = Math.max(1, segTop - floorY);
				for (int u = u1; u < u2; u++)
					for (int v = v1; v < v2; v++) {
						int[] xz = cell(u, v);
						int x = xz[0], z = xz[1];
						for (int y = tops[u - u1][v - v1]; y > floorY; y--) {
							BlockState st = level.getBlockState(pos.set(x, y, z));
							if (st.isAir()) continue;
							float t = ts + (te - ts) * (segTop - y) / span; // hele laag tegelijk: het blok zinkt
							// weinig stof: alleen aan het oppervlak en dun gezaaid, anders wordt het beeld korrelig
							boolean dust = y == tops[u - u1][v - v1] && ((x * 7 + z * 13) & 3) == 0;
							ops.add(new Op(t, x, y, z, AIR, dust && !fluid(st) ? st : null, dust && !fluid(st) ? 1 : 0));
						}
						BlockState fl = level.getBlockState(pos.set(x, floorY, z));
						if (fl.isAir() || fluid(fl) || !fl.isSolidRender()) ops.add(new Op(te, x, floorY, z, STONE, null, 0));
						if (v == 0 || v == qW - 1) { // verhoogde wand langs de rand, steen met een oplichtende rand
							int wallH = Math.max(4, q.depth() / 3);
							BlockState wall = (u % 3 == 0) ? Blocks.STONE_BRICKS.defaultBlockState() : Blocks.STONE.defaultBlockState();
							for (int h = 1; h <= wallH; h++) ops.add(new Op(te + h * 0.02f, x, floorY + h, z, wall, null, h == wallH ? 1 : 0));
						}
					}
				// wanden dichten (water/lava) en overhangende bomen langs dit vak weghalen
				for (int u = u1 - 1; u <= u2; u++)
					for (int v = v1 - 1; v <= v2; v++) {
						boolean edgeU = u == -1 || u == qLen, edgeV = v == -1 || v == qW;
						if (!(edgeU || edgeV) || u < -1 || u > qLen || v < -1 || v > qW) continue;
						for (int r = 0; r < 3; r++) {
							int uu = u + (u == -1 ? -r : u == qLen ? r : 0), vv = v + (v == -1 ? -r : v == qW ? r : 0);
							int[] xz = cell(uu, vv);
							int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, xz[0], xz[1]) - 1;
							for (int y = top; y >= floorY; y--) {
								BlockState st = level.getBlockState(pos.set(xz[0], y, xz[1]));
								if (r == 0 && fluid(st)) ops.add(new Op(ts, xz[0], y, xz[1], STONE, null, 0));
								else if (treeish(st)) ops.add(new Op(ts, xz[0], y, xz[1], AIR, y == top ? st : null, y == top ? 1 : 0));
							}
						}
					}
			}
		}
		terraOps = ops.size();
		camBase = Math.max(26, (half + 2) * 2.9 + height * 0.5 + (rimY - floorY) * 0.8) * distMul;
		// zichtlijn: binnen de groef telt de bodem, daarbuiten het maaiveld met bomen
		double dMax = camBase * 1.4 + 6;
		float[] raw = new float[camTan.length];
		for (int i = 0; i < raw.length; i++) {
			double a = Math.toRadians(i * 360.0 / raw.length), ca = Math.cos(a), sa = Math.sin(a);
			float need = 0;
			for (double sd = 2; sd <= dMax; sd += 1.5) {
				int x = (int) Math.floor(cx + 0.5 + ca * sd), z = (int) Math.floor(cz + 0.5 + sa * sd);
				int obs = inPit(x, z) ? floorY : level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
				need = Math.max(need, (float) ((obs + 2.0 - (floorY + 1)) / sd));
			}
			raw[i] = need;
		}
		smoothRing(raw, camTan);
		smoothRing(raw, camTanTrees);
	}

	private int[] cell(int u, int v) {
		return new int[]{qx0 + qax * (qu0 + u) - qaz * (v - qW / 2), qz0 + qaz * (qu0 + u) + qax * (v - qW / 2)};
	}

	/** Effecten bij het uitgraven: stofwolk en lichtpuntjes langs het graaffront, over de hele breedte. */
	public void quarryFx(ServerLevel level, float u) {
		double[] c = pitPoint(qu0 + Math.max(0.5, Math.min(qLen - 0.5, u * qLen)), 0);
		for (int i = 0; i < 9; i++) {
			double v = (i - 4) * (qW * 0.45) / 4.0;
			double[] p = pitPoint(qu0 + Math.max(0.5, Math.min(qLen - 0.5, u * qLen)), v);
			level.sendParticles(ParticleTypes.CLOUD, true, true, p[0] + 0.5, y0 + 1.6, p[1] + 0.5, 2, 0.5, 0.4, 0.5, 0.02);
			level.sendParticles(ParticleTypes.END_ROD, true, true, p[0] + 0.5, y0 + 2.6, p[1] + 0.5, 1, 0.2, 0.4, 0.2, 0.02);
		}
	}

	// ------------------------------------------------------------------ stijl "vierkant"

	/**
	 * Naar het voorbeeld van de gebruiker, in vier stappen:
	 *  1. een smalle, diepe sleuf wordt als een lijn rond een vierkant getrokken (start aan de kant van de camera);
	 *  2. de binnenkant van het vierkant verdwijnt, van het midden naar buiten (rond patroon), tot één diep gat;
	 *  3. de bodem komt laag voor laag weer omhoog tot op grondniveau, met gras bovenop;
	 *  4. daarop wordt het bouwwerk gebouwd (zie build()).
	 * Water en lava rond het vierkant worden dichtgemaakt, zodat het gat niet onderloopt.
	 */
	private void squareTerraform(ServerLevel level, Schematic sch, Square sq, int margin, int radiusNoSchematic,
			float tIntro, float tTerra, float distMul) {
		square = true;
		int size = sch == null ? 2 * radiusNoSchematic : Math.max(sch.sx, sch.sz);
		sqHalf = (size + 1) / 2 + margin + 1;
		sqRing = Math.max(2, Math.round(sqHalf * 2 / 7f));
		radius = sqHalf;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		{ // grasveld gelijk met de grond rondom: mediaan van de grondhoogte net buiten het vierkant (zonder bomen)
			List<Integer> rim = new ArrayList<>();
			for (int i = -sqHalf - 1; i <= sqHalf + 1; i++) {
				rim.add(groundY(level, cx + i, cz - sqHalf - 1, pos));
				rim.add(groundY(level, cx + i, cz + sqHalf + 1, pos));
				rim.add(groundY(level, cx - sqHalf - 1, cz + i, pos));
				rim.add(groundY(level, cx + sqHalf + 1, cz + i, pos));
			}
			rim.sort(Integer::compare);
			y0 = rim.get(rim.size() / 2);
		}
		rimY = y0;
		sqFloor = Math.max(level.getMinY() + 3, y0 - Math.max(6, sq.depth()));
		sqA0 = Math.toRadians(sq.startDeg());
		sqRingStart = tIntro;               sqRingDur = tTerra * 0.42f;
		sqHollowStart = tIntro + tTerra * 0.40f; sqHollowDur = tTerra * 0.32f;
		sqRiseStart = tIntro + tTerra * 0.74f;   sqRiseDur = tTerra * 0.24f;
		final BlockState AIR = Blocks.AIR.defaultBlockState(), STONE = Blocks.STONE.defaultBlockState(),
				DIRT = Blocks.DIRT.defaultBlockState(), GRASS = Blocks.GRASS_BLOCK.defaultBlockState();
		{ // in water graven kan niet: het gat loopt vol
			int wet = 0, n = 0;
			for (int dx = -sqHalf; dx <= sqHalf; dx += 2)
				for (int dz = -sqHalf; dz <= sqHalf; dz += 2) {
					int x = cx + dx, z = cz + dz, top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
					if (fluid(level.getBlockState(pos.set(x, top, z)))) wet++;
					n++;
				}
			if (wet > n * 0.15)
				throw new IllegalStateException("hier ligt te veel water (" + Math.round(100f * wet / n)
						+ "% van het vierkant). Ga op het land staan, of kies de stijl Vlakmaken");
		}
		int inner = sqHalf - sqRing; // cellen met afstand <= inner horen bij het eiland in het midden
		double dMax = Math.sqrt(2) * inner + 0.5;
		for (int dx = -sqHalf - 3; dx <= sqHalf + 3; dx++) {
			for (int dz = -sqHalf - 3; dz <= sqHalf + 3; dz++) {
				int x = cx + dx, z = cz + dz, cheb = Math.max(Math.abs(dx), Math.abs(dz));
				int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
				float tRing = sqRingStart + sqRingDur * easeSInv(ringParam(dx, dz)); // WP1: sleuf vertrekt traag, landt zacht
				if (cheb > sqHalf) { // rondom: water dichten (direct naast de sleuf) en bomen weghalen
					for (int y = top; y >= sqFloor; y--) {
						BlockState st = level.getBlockState(pos.set(x, y, z));
						if (cheb == sqHalf + 1 && fluid(st)) ops.add(new Op(tRing, x, y, z, STONE, null, 0));
						else if (treeish(st)) ops.add(new Op(tRing, x, y, z, AIR, y == top ? st : null, y == top ? 1 : 0));
					}
					continue;
				}
				// 1 en 2: weghalen. Sleuf volgt de rondgaande kop; eiland van het midden naar buiten.
				float t0 = cheb > inner ? tRing
						: sqHollowStart + sqHollowDur * 0.92f * easeSInv((float) Math.min(1, (dx * dx + dz * dz) / (dMax * dMax))); // WP1
				float digDur = cheb > inner ? 0.30f : 0.40f;
				float span = Math.max(1, top - sqFloor);
				for (int y = top; y > sqFloor; y--) {
					BlockState st = level.getBlockState(pos.set(x, y, z));
					if (st.isAir()) continue;
					boolean dust = y == top && ((x * 7 + z * 13) & 3) == 0 && !fluid(st);
					ops.add(new Op(t0 + digDur * (top - y) / span, x, y, z, AIR, dust ? st : null, dust ? 1 : 0));
				}
				BlockState fl = level.getBlockState(pos.set(x, sqFloor, z));
				if (fl.isAir() || fluid(fl) || !fl.isSolidRender()) ops.add(new Op(t0 + digDur, x, sqFloor, z, STONE, null, 0));
				// 3: de bodem komt omhoog, hele laag tegelijk, gras bovenop
				int layers = Math.max(1, y0 - sqFloor);
				for (int y = sqFloor + 1; y <= y0; y++) {
					float t = sqRiseStart + sqRiseDur * easeSInv((y - sqFloor - 1) / (float) layers); // WP1: versnellen en afremmen
					BlockState st = y == y0 ? GRASS : (y0 - y <= 3 ? DIRT : STONE);
					ops.add(new Op(t, x, y, z, st, null, 0));
				}
			}
		}
		terraOps = ops.size();
		camBase = Math.max(24, (half + 2) * 2.9 + height * 0.5) * distMul;
		// zichtlijn: binnen het vierkant telt het nieuwe grasveld, daarbuiten het maaiveld met bomen
		double dMaxCam = camBase * 1.4 + 6;
		float[] raw = new float[camTan.length];
		for (int i = 0; i < raw.length; i++) {
			double a = Math.toRadians(i * 360.0 / raw.length), ca = Math.cos(a), sa = Math.sin(a);
			float need = 0;
			for (double sd = 2; sd <= dMaxCam; sd += 1.5) {
				int x = (int) Math.floor(cx + 0.5 + ca * sd), z = (int) Math.floor(cz + 0.5 + sa * sd);
				boolean inSq = Math.max(Math.abs(x - cx), Math.abs(z - cz)) <= sqHalf;
				int obs = inSq ? y0 : level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
				need = Math.max(need, (float) ((obs + 2.0 - (y0 + 1)) / sd));
			}
			raw[i] = need;
		}
		smoothRing(raw, camTan);
		smoothRing(raw, camTanTrees);
	}

	/** Positie 0..1 langs de ring, met de klok mee vanaf de kant van de camera. */
	private float ringParam(int dx, int dz) {
		double a = Math.atan2(dz, dx) - sqA0;
		a = ((a % (2 * Math.PI)) + 2 * Math.PI) % (2 * Math.PI);
		return (float) (a / (2 * Math.PI));
	}

	/** Waar de kop van de sleuf is op tijd t (wereld-x, -z), op het midden van de sleuf. */
	public double[] ringHead(float t) {
		float f = easeS(Math.min(1f, Math.max(0f, (t - sqRingStart) / Math.max(0.01f, sqRingDur))));
		double a = sqA0 + f * 2 * Math.PI, ca = Math.cos(a), sa = Math.sin(a);
		double r = (sqHalf - sqRing / 2.0) / Math.max(Math.abs(ca), Math.abs(sa)); // op het vierkant, niet op een cirkel
		return new double[]{cx + 0.5 + ca * r, cz + 0.5 + sa * r};
	}

	/** Effecten per fase: stof aan de kop van de sleuf, een lichtrand die met de bodem mee omhoog komt, sterretjes op het gras. */
	public void squareFx(ServerLevel level, float t) {
		if (t >= sqRingStart && t < sqRingStart + sqRingDur) {
			double[] h = ringHead(t);
			level.sendParticles(ParticleTypes.CLOUD, true, true, h[0], y0 + 1.2, h[1], 6, sqRing * 0.4, 0.5, sqRing * 0.4, 0.03);
			level.sendParticles(ParticleTypes.END_ROD, true, true, h[0], y0 + 1.5, h[1], 4, sqRing * 0.3, 0.6, sqRing * 0.3, 0.04);
		} else if (t >= sqRiseStart && t < sqRiseStart + sqRiseDur + 0.2f) {
			float f = Math.min(1f, (t - sqRiseStart) / Math.max(0.01f, sqRiseDur));
			double yy = sqFloor + 1 + easeS(f) * (y0 - sqFloor) + 0.2;
			for (int i = -sqHalf; i <= sqHalf; i += 2) {
				for (double[] e : new double[][]{{i, -sqHalf}, {i, sqHalf + 1}, {-sqHalf, i}, {sqHalf + 1, i}})
					level.sendParticles(ParticleTypes.END_ROD, true, true, cx + e[0] + 0.5, yy, cz + e[1] + 0.5, 1, 0.2, 0.1, 0.2, 0.01);
			}
			if (f >= 0.97f)
				level.sendParticles(ParticleTypes.HAPPY_VILLAGER, true, true, cx + 0.5, y0 + 1.3, cz + 0.5, 40, sqHalf * 0.6, 0.3, sqHalf * 0.6, 0.0);
		}
	}
}
