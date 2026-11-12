package be.studio.regisseur.draaiboek;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

import static be.studio.regisseur.draaiboek.Easing.easeS;
import static be.studio.regisseur.draaiboek.Easing.easeSInv;
import static be.studio.regisseur.draaiboek.Terrein.*;

/**
 * Terraform-stijl "vierkant" (standaard), naar het voorbeeld van de gebruiker, in drie stappen:
 *  1. SLEUF:     een smalle, diepe sleuf wordt als een lijn rond een vierkant getrokken (start aan de camerakant);
 *  2. UITHOLLEN: de binnenkant verdwijnt van het midden naar buiten, tot één diep gat;
 *  3. OMHOOG:    de bodem komt laag per laag terug tot op grondniveau, met gras bovenop.
 * Daarna bouwt Bouwvolgorde het bouwwerk op het grasveld. Water en lava rond het vierkant worden dichtgemaakt.
 *
 * Timing (aandeel van de terraform-tijd): sleuf 0-42%, uithollen 40-72%, omhoog 74-98%.
 * Elke stap gebruikt Easing.easeS: traag vertrekken, versnellen, zacht landen.
 */
final class VierkantTerraform {
	private VierkantTerraform() { }

	static void maak(ShowPlan p, ServerLevel level, Schematic sch, ShowPlan.Square sq, int margin, int radiusNoSchematic,
			float tIntro, float tTerra, float distMul) {
		p.square = true;
		int size = sch == null ? 2 * radiusNoSchematic : Math.max(sch.sx, sch.sz);
		p.sqHalf = (size + 1) / 2 + margin + 1;
		p.sqRing = Math.max(2, Math.round(p.sqHalf * 2 / 7f));
		p.radius = p.sqHalf;
		final int cx = p.cx, cz = p.cz, sqHalf = p.sqHalf;
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
			p.y0 = rim.get(rim.size() / 2);
		}
		final int y0 = p.y0;
		p.rimY = y0;
		p.sqFloor = Math.max(level.getMinY() + 3, y0 - Math.max(6, sq.depth()));
		final int floor = p.sqFloor;
		p.sqA0 = Math.toRadians(sq.startDeg());
		p.sqRingStart = tIntro;                    p.sqRingDur = tTerra * 0.42f;
		p.sqHollowStart = tIntro + tTerra * 0.40f; p.sqHollowDur = tTerra * 0.32f;
		p.sqRiseStart = tIntro + tTerra * 0.74f;   p.sqRiseDur = tTerra * 0.24f;
		final BlockState AIR = Blocks.AIR.defaultBlockState(), STONE = Blocks.STONE.defaultBlockState(),
				DIRT = Blocks.DIRT.defaultBlockState(), GRASS = Blocks.GRASS_BLOCK.defaultBlockState();

		{ // in water graven kan niet: het gat loopt vol
			int wet = 0, n = 0;
			for (int dx = -sqHalf; dx <= sqHalf; dx += 2)
				for (int dz = -sqHalf; dz <= sqHalf; dz += 2) {
					int x = cx + dx, z = cz + dz;
					if (fluid(level.getBlockState(pos.set(x, surfaceY(level, x, z), z)))) wet++;
					n++;
				}
			if (wet > n * 0.15)
				throw new IllegalStateException("hier ligt te veel water (" + Math.round(100f * wet / n)
						+ "% van het vierkant). Ga op het land staan, of kies de stijl Vlakmaken");
		}

		int inner = sqHalf - p.sqRing; // cellen met afstand <= inner horen bij het eiland in het midden
		double dMax = Math.sqrt(2) * inner + 0.5;
		for (int dx = -sqHalf - 3; dx <= sqHalf + 3; dx++) {
			for (int dz = -sqHalf - 3; dz <= sqHalf + 3; dz++) {
				int x = cx + dx, z = cz + dz, cheb = Math.max(Math.abs(dx), Math.abs(dz));
				int top = surfaceY(level, x, z);
				float tRing = p.sqRingStart + p.sqRingDur * easeSInv(ringParam(p, dx, dz));
				if (cheb > sqHalf) { // rondom: water dichten (direct naast de sleuf) en bomen weghalen
					for (int y = top; y >= floor; y--) {
						BlockState st = level.getBlockState(pos.set(x, y, z));
						if (cheb == sqHalf + 1 && fluid(st)) p.ops.add(new ShowPlan.Op(tRing, x, y, z, STONE, null, 0));
						else if (treeish(st)) p.ops.add(new ShowPlan.Op(tRing, x, y, z, AIR, y == top ? st : null, y == top ? 1 : 0));
					}
					continue;
				}
				// stap 1 en 2: weghalen. De sleuf volgt de rondgaande kop; het eiland gaat van het midden naar buiten
				// (oppervlak-aandeel door de ease, zodat het tempo traag begint en zacht eindigt).
				float t0 = cheb > inner ? tRing
						: p.sqHollowStart + p.sqHollowDur * 0.92f * easeSInv((float) Math.min(1, (dx * dx + dz * dz) / (dMax * dMax)));
				float digDur = cheb > inner ? 0.30f : 0.40f;
				float span = Math.max(1, top - floor);
				for (int y = top; y > floor; y--) {
					BlockState st = level.getBlockState(pos.set(x, y, z));
					if (st.isAir()) continue;
					boolean dust = y == top && ((x * 7 + z * 13) & 3) == 0 && !fluid(st);
					p.ops.add(new ShowPlan.Op(t0 + digDur * (top - y) / span, x, y, z, AIR, dust ? st : null, dust ? 1 : 0));
				}
				BlockState fl = level.getBlockState(pos.set(x, floor, z));
				if (fl.isAir() || fluid(fl) || !fl.isSolidRender()) p.ops.add(new ShowPlan.Op(t0 + digDur, x, floor, z, STONE, null, 0));
				// stap 3: de bodem komt omhoog, hele laag tegelijk, gras bovenop
				int layers = Math.max(1, y0 - floor);
				for (int y = floor + 1; y <= y0; y++) {
					float t = p.sqRiseStart + p.sqRiseDur * easeSInv((y - floor - 1) / (float) layers);
					BlockState st = y == y0 ? GRASS : (y0 - y <= 3 ? DIRT : STONE);
					p.ops.add(new ShowPlan.Op(t, x, y, z, st, null, 0));
				}
			}
		}
		p.terraOps = p.ops.size();
		p.camBase = Math.max(24, (p.half + 2) * 2.9 + p.height * 0.5) * distMul;
		// zichtlijn: binnen het vierkant telt het nieuwe grasveld, daarbuiten het maaiveld met bomen
		Terrein.Obstakel obs = (x, z) -> Math.max(Math.abs(x - cx), Math.abs(z - cz)) <= sqHalf ? y0 : surfaceY(level, x, z);
		zichtlijn(cx, cz, y0, p.camBase * 1.4 + 6, obs, p.camTan);
		System.arraycopy(p.camTan, 0, p.camTanTrees, 0, p.camTan.length);
	}

	/** Positie 0..1 langs de ring, met de klok mee vanaf de kant van de camera. */
	private static float ringParam(ShowPlan p, int dx, int dz) {
		double a = Math.atan2(dz, dx) - p.sqA0;
		a = ((a % (2 * Math.PI)) + 2 * Math.PI) % (2 * Math.PI);
		return (float) (a / (2 * Math.PI));
	}

	/** Waar de kop van de sleuf is op tijd t (wereld-x, -z), op het midden van de sleuf. */
	static double[] ringHead(ShowPlan p, float t) {
		float f = easeS(Math.min(1f, Math.max(0f, (t - p.sqRingStart) / Math.max(0.01f, p.sqRingDur))));
		double a = p.sqA0 + f * 2 * Math.PI, ca = Math.cos(a), sa = Math.sin(a);
		double r = (p.sqHalf - p.sqRing / 2.0) / Math.max(Math.abs(ca), Math.abs(sa)); // op het vierkant, niet op een cirkel
		return new double[]{p.cx + 0.5 + ca * r, p.cz + 0.5 + sa * r};
	}

	/** Effecten: stof aan de kop van de sleuf, een lichtrand die met de bodem mee omhoog komt, sterretjes op het gras. */
	static void effecten(ShowPlan p, ServerLevel level, float t) {
		if (t >= p.sqRingStart && t < p.sqRingStart + p.sqRingDur) {
			double[] h = ringHead(p, t);
			level.sendParticles(ParticleTypes.CLOUD, true, true, h[0], p.y0 + 1.2, h[1], 6, p.sqRing * 0.4, 0.5, p.sqRing * 0.4, 0.03);
			level.sendParticles(ParticleTypes.END_ROD, true, true, h[0], p.y0 + 1.5, h[1], 4, p.sqRing * 0.3, 0.6, p.sqRing * 0.3, 0.04);
		} else if (t >= p.sqRiseStart && t < p.sqRiseStart + p.sqRiseDur + 0.2f) {
			float f = Math.min(1f, (t - p.sqRiseStart) / Math.max(0.01f, p.sqRiseDur));
			double yy = p.sqFloor + 1 + easeS(f) * (p.y0 - p.sqFloor) + 0.2;
			int h = p.sqHalf;
			for (int i = -h; i <= h; i += 2) {
				for (double[] e : new double[][]{{i, -h}, {i, h + 1}, {-h, i}, {h + 1, i}})
					level.sendParticles(ParticleTypes.END_ROD, true, true, p.cx + e[0] + 0.5, yy, p.cz + e[1] + 0.5, 1, 0.2, 0.1, 0.2, 0.01);
			}
			if (f >= 0.97f)
				level.sendParticles(ParticleTypes.HAPPY_VILLAGER, true, true, p.cx + 0.5, p.y0 + 1.3, p.cz + 0.5, 40, h * 0.6, 0.3, h * 0.6, 0.0);
		}
	}
}
