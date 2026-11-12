package be.studio.regisseur.draaiboek;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.HashMap;
import java.util.Map;

/**
 * Hulpjes om de wereld te lezen: wat is grond, wat is boom, wat is water, en waar mag de camera hangen.
 * Alles hier leest alleen; er wordt niets veranderd.
 */
public final class Terrein {
	private Terrein() { }

	private static final Map<Block, Boolean> TREEISH = new HashMap<>();

	/** Hoort dit blok bij een boom of begroeiing (bladeren, stammen, ranken, paddenstoelen)? */
	public static boolean treeish(BlockState s) {
		return TREEISH.computeIfAbsent(s.getBlock(), b -> {
			String p = BuiltInRegistries.BLOCK.getKey(b).getPath();
			return p.contains("leaves") || p.endsWith("_log") || p.endsWith("_wood") || p.endsWith("_stem")
					|| p.contains("mushroom_block") || p.contains("vine") || p.equals("bamboo") || p.contains("roots");
		});
	}

	/** Echte grond: vast, ondoorzichtig en geen boom. */
	public static boolean ground(BlockState s) {
		return !s.isAir() && s.blocksMotion() && s.isSolidRender() && !treeish(s);
	}

	public static boolean fluid(BlockState s) { return !s.getFluidState().isEmpty(); }

	/** Hoogste vloeistofblok boven de grond, of Integer.MIN_VALUE als de kolom droog is. */
	public static int waterY(ServerLevel level, int x, int z, int top, int gy, BlockPos.MutableBlockPos p) {
		for (int y = top; y > gy; y--) if (fluid(level.getBlockState(p.set(x, y, z)))) return y;
		return Integer.MIN_VALUE;
	}

	/** Hoogte van de echte grond in deze kolom (bomen en water tellen niet). */
	public static int groundY(ServerLevel level, int x, int z, BlockPos.MutableBlockPos p) {
		int y = surfaceY(level, x, z), min = level.getMinY();
		while (y > min && !ground(level.getBlockState(p.set(x, y, z)))) y--;
		return y;
	}

	/**
	 * Bovenste blok van de kolom (alles telt mee, ook bomen).
	 * LET OP: Minecraft antwoordt voor een stuk wereld dat niet ingeladen is "helemaal onderaan" in plaats van het
	 * in te laden. Bij grote bouwwerken (kathedraal) gaf dat een put tot op de bodem en bos dat bleef staan.
	 * Daarom laden we het stuk eerst echt in (of maken het aan, als het nog nooit bezocht is).
	 */
	public static int surfaceY(ServerLevel level, int x, int z) {
		level.getChunk(x >> 4, z >> 4); // inladen/aanmaken; daarna klopt de hoogtekaart
		return level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
	}

	// ------------------------------------------------------------------ zichtlijn van de camera

	/**
	 * Zet de ruwe "minimale helling per richting" om in een vloeiende ring: elke richting neemt het maximum van
	 * zijn buren en wordt dan uitgevlakt. Zo maakt de camerabaan geen schokken.
	 */
	public static void smoothRing(float[] raw, float[] out) {
		int nA = raw.length;
		for (int i = 0; i < nA; i++) {
			float mx = 0;
			for (int k = -3; k <= 3; k++) mx = Math.max(mx, raw[(i + k + nA) % nA]);
			out[i] = mx;
		}
		for (int pass = 0; pass < 3; pass++) {
			float[] t = out.clone();
			for (int i = 0; i < nA; i++)
				out[i] = Math.max(raw[i], (t[(i + nA - 1) % nA] + 2 * t[i] + t[(i + 1) % nA]) / 4f);
		}
	}

	/** Waarde uit een ring (één waarde per 5 graden), vloeiend tussen de steunpunten. */
	public static float interp(float[] ring, double angleDeg) {
		double f = ((angleDeg % 360) + 360) % 360 / (360.0 / ring.length);
		int i = (int) Math.floor(f) % ring.length, j = (i + 1) % ring.length;
		float u = (float) (f - Math.floor(f));
		return ring[i] * (1 - u) + ring[j] * u;
	}

	/** Vorm van een gebied voor de zichtlijn: welke hoogte telt op plek (x, z)? */
	public interface Obstakel { int hoogte(int x, int z); }

	/**
	 * Per richting rond (cx, cz): de helling die de zichtlijn van de camera minstens moet hebben om over alles
	 * heen te kijken tot op afstand dMax. Resultaat (vloeiend) in out.
	 */
	public static void zichtlijn(int cx, int cz, int baseY, double dMax, Obstakel obstakel, float[] out) {
		float[] raw = new float[out.length];
		for (int i = 0; i < raw.length; i++) {
			double a = Math.toRadians(i * 360.0 / raw.length), ca = Math.cos(a), sa = Math.sin(a);
			float need = 0;
			for (double sd = 2; sd <= dMax; sd += 1.5) {
				int x = (int) Math.floor(cx + 0.5 + ca * sd), z = (int) Math.floor(cz + 0.5 + sa * sd);
				need = Math.max(need, (float) ((obstakel.hoogte(x, z) + 2.0 - (baseY + 1)) / sd));
			}
			raw[i] = need;
		}
		smoothRing(raw, out);
	}
}
