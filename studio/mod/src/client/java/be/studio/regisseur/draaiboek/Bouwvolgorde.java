package be.studio.regisseur.draaiboek;

import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;
import java.util.Comparator;

import static be.studio.regisseur.draaiboek.Easing.*;

/**
 * Wanneer verschijnt elk blok van het bouwwerk?
 *
 * Regels:
 *  - laag per laag van onder naar boven; binnen een laag een diagonale veeg met wat ruis (leest als "groeien");
 *  - tussen twee lagen een korte adempauze (GAP);
 *  - over de hele bouw een ease-in/ease-out (Easing.easeBuild): traag beginnen, snel midden, rustig afwerken;
 *  - met muziek: een kleine puls op elke tel (Easing.beatWarp).
 * Bij heel grote bouwwerken vliegt maar een deel van de blokken in; de rest verschijnt direct (sparen van entiteiten).
 */
final class Bouwvolgorde {
	private Bouwvolgorde() { }

	/** Pauze tussen twee lagen, uitgedrukt in "blokplaatsen". */
	private static final int GAP = 3;

	static void plan(ShowPlan p, Schematic sch, float tIntro, float tTerra, float tBuild, MusicSync sync) {
		final int n = sch.size(), ox = p.cx - sch.sx / 2, oy = p.y0 + 1, oz = p.cz - sch.sz / 2;
		Integer[] order = new Integer[n];
		for (int i = 0; i < n; i++) order[i] = i;
		Arrays.sort(order, Comparator.<Integer>comparingInt(i -> sch.ys.getInt(i))
				.thenComparingDouble(i -> sch.xs.getInt(i) + sch.zs.getInt(i) * 0.6
						+ 2.5 * jitter(sch.xs.getInt(i), sch.zs.getInt(i) * 31 + sch.ys.getInt(i))));
		float tb = tIntro + tTerra;
		float perSec = n / Math.max(0.5f, tBuild);
		int flyEvery = Math.max(1, (int) Math.ceil(perSec / 500f));
		int sparkEvery = Math.max(1, n / 500);
		int layers = 0;
		for (int k = 0; k < n; k++) if (k == 0 || sch.ys.getInt(order[k]) != sch.ys.getInt(order[k - 1])) layers++;
		float slots = n + GAP * Math.max(0, layers - 1);
		float win = Math.max(0.5f, tBuild * 0.97f - p.fly.lead());

		// 1. landingstijd per blok
		float[] lands = new float[n];
		int gapsSoFar = 0;
		for (int k = 0; k < n; k++) {
			if (k > 0 && sch.ys.getInt(order[k]) != sch.ys.getInt(order[k - 1])) gapsSoFar++;
			lands[k] = tb + p.fly.lead() + win * easeBuildInv((k + 0.5f + GAP * gapsSoFar) / slots);
		}
		// 2. puls op de tel
		if (sync != null) lands = beatWarp(lands, sync.beats, tb + p.fly.lead(), tb + p.fly.lead() + win);

		// 3. omzetten naar blokwijzigingen
		for (int k = 0; k < n; k++) {
			int i = order[k];
			float land = lands[k];
			int x = ox + sch.xs.getInt(i), y = oy + sch.ys.getInt(i), z = oz + sch.zs.getInt(i);
			BlockState st = sch.states.get(i);
			boolean spark = k % sparkEvery == 0;
			if (k % flyEvery == 0) p.ops.add(new ShowPlan.Op(land - p.fly.lead(), x, y, z, st, null, spark ? 4 : 3));
			else p.ops.add(new ShowPlan.Op(land, x, y, z, st, null, spark ? 2 : 0));
			p.buildTargets.add(new ShowPlan.Op(land, x, y, z, st, null, 0));
		}
		p.buildOps = n;
		p.finaleT = tb + tBuild;
	}
}
