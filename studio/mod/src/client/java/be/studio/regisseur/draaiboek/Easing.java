package be.studio.regisseur.draaiboek;

import java.util.List;

/**
 * Alle tempo-curves op één plek: hoe snel iets begint, versnelt en afremt.
 *
 * Elke curve zet een voortgang 0..1 om naar 0..1. De "Inv"-versies doen het omgekeerde: ze zeggen op welk
 * moment een bepaald aandeel klaar is. Daarmee krijgt elk blok zijn tijdstip.
 */
public final class Easing {
	private Easing() { }

	/** Smoothstep: zacht vertrekken en zacht landen. */
	public static float smooth(float u) { return u <= 0 ? 0 : u >= 1 ? 1 : u * u * (3 - 2 * u); }

	/** Bouwcurve: aandeel geplaatste blokken op tijd x (0..1). Traag begin en einde, snel midden. */
	public static float easeBuild(float x) { return 0.25f * x + 0.75f * smooth(x); }

	/** Omgekeerde van easeBuild: op welk moment (0..1) is aandeel u bereikt. */
	public static float easeBuildInv(float u) {
		float lo = 0, hi = 1;
		for (int i = 0; i < 28; i++) { float m = (lo + hi) / 2; if (easeBuild(m) < u) lo = m; else hi = m; }
		return (lo + hi) / 2;
	}

	/** Terraform-curve: traag vertrekken, snel in het midden, zacht landen (smootherstep met een klein lineair deel). */
	public static float easeS(float u) {
		u = Math.max(0, Math.min(1, u));
		float s5 = u * u * u * (u * (u * 6 - 15) + 10);
		return 0.1f * u + 0.9f * s5;
	}

	/**
	 * Curve voor de sleuf: die is het eerste wat de kijker na de teaser ziet, dus er moet meteen iets gebeuren.
	 * Mildere ease-in dan easeS (steilte 0,35 aan het begin in plaats van 0,1), zacht landen blijft.
	 */
	public static float easeRing(float u) {
		u = Math.max(0, Math.min(1, u));
		return 0.35f * u + 0.65f * smooth(u);
	}

	/** Omgekeerde van easeRing. */
	public static float easeRingInv(float v) {
		float lo = 0, hi = 1;
		for (int i = 0; i < 24; i++) { float m = (lo + hi) / 2; if (easeRing(m) < v) lo = m; else hi = m; }
		return (lo + hi) / 2;
	}

	/** Omgekeerde van easeS: op welk tijdsaandeel is aandeel v klaar. */
	public static float easeSInv(float v) {
		float lo = 0, hi = 1;
		for (int i = 0; i < 24; i++) { float m = (lo + hi) / 2; if (easeS(m) < v) lo = m; else hi = m; }
		return (lo + hi) / 2;
	}

	/**
	 * Puls op de tel: vlak voor elke tel even inhouden (dichtheid zakt naar 50% in de laatste 0,1 s), zodat elke
	 * tel voelt als een nieuwe aanzet. Tijdvervorming G met G' = 1/d, daarna terug op het venster [t0, t1] geschaald.
	 * De rest van de tel wordt maar ~10% dichter, dus geen pieken.
	 */
	public static float[] beatWarp(float[] times, List<Float> beats, float t0, float t1) {
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

	/** Vaste "ruis" 0..1 per plek: altijd dezelfde waarde voor dezelfde x en z (geen toeval tussen opnames). */
	public static float jitter(int x, int z) {
		int h = x * 73428767 ^ z * 912931;
		h ^= h >>> 13; h *= 0x5bd1e995; h ^= h >>> 15;
		return (h & 0xFFFF) / 65535f;
	}
}
