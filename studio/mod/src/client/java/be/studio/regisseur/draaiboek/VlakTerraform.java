package be.studio.regisseur.draaiboek;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import static be.studio.regisseur.draaiboek.Easing.jitter;
import static be.studio.regisseur.draaiboek.Easing.smooth;
import static be.studio.regisseur.draaiboek.Terrein.*;

/**
 * Terraform-stijl "vlakmaken" (keuze "flatten"): een ronde, vlakke grasschijf op grondhoogte, met een glooiende
 * overgangsrand naar het landschap. Een golf loopt van het midden naar buiten. Werkt ook op water: dan komt er
 * een eiland op waterhoogte.
 */
final class VlakTerraform {
	private VlakTerraform() { }

	static void maak(ShowPlan p, ServerLevel level, float tIntro, float tTerra, float distMul, MusicSync sync) {
		final BlockState AIR = Blocks.AIR.defaultBlockState(), GRASS = Blocks.GRASS_BLOCK.defaultBlockState(),
				DIRT = Blocks.DIRT.defaultBlockState(), STONE = Blocks.STONE.defaultBlockState();
		final int cx = p.cx, cz = p.cz;
		int outer = p.radius + p.blend, clear = outer + 4, gn = 2 * clear + 1;
		float t0 = tIntro;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		// Hoogtekaart van de natuurlijke grond; de overgangsrand gebruikt een uitgevlakte versie (5x5 gemiddelde,
		// zelfde idee als WorldEdit //smooth) zodat de rand glooit in plaats van te trappen.
		int[][] gyMap = new int[gn][gn], topMap = new int[gn][gn], hMap = new int[gn][gn];
		for (int ix = 0; ix < gn; ix++)
			for (int iz = 0; iz < gn; iz++) {
				int x = cx - clear + ix, z = cz - clear + iz;
				topMap[ix][iz] = surfaceY(level, x, z);
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
						if (treeish(st)) p.ops.add(new ShowPlan.Op(tcol, x, y, z, AIR, y == top ? st : null, y == top ? 1 : 0));
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
					p.ops.add(new ShowPlan.Op(tcol + (top - y) * 0.006f, x, y, z, AIR, first ? st : null, first ? 1 : 0));
					first = false;
					if (y <= gy) changed = true;
				}
				if (wet && d > p.radius && target <= hMap[ix][iz]) continue; // buiten de schijf blijft water gewoon water
				for (int y = gy + 1; y <= target; y++) { // ophogen
					BlockState st = y == target ? GRASS : (target - y <= 3 ? DIRT : STONE);
					p.ops.add(new ShowPlan.Op(tcol + (y - gy) * 0.006f, x, y, z, st, st, y == target ? 1 : 0));
				}
				if (target <= gy && (d <= p.radius || changed)) { // oppervlak afwerken: gewoon gras, geen sneeuwgras of kale steen
					if (level.getBlockState(pos.set(x, target, z)) != GRASS)
						p.ops.add(new ShowPlan.Op(tcol + 0.2f, x, target, z, GRASS, null, 0));
				}
			}
		}
		p.terraOps = p.ops.size();
		p.camBase = Math.max(22, (p.half + 2) * 2.9 + p.height * 0.5) * distMul;
		zichtlijnen(p, level, gyMap, clear);
		p.rimY = p.y0;
	}

	/**
	 * Voorkomt dat de camera in bomen of heuvels hangt. Per richting het hoogste obstakel tussen de bouwplaats
	 * en de verste camerastand. Twee versies: met de bomen die er voor de terraform nog staan, en zonder.
	 */
	private static void zichtlijnen(ShowPlan p, ServerLevel level, int[][] gyMap, int clear) {
		double dMax = p.camBase * 1.4 + 4;
		float[] raw = new float[p.camTan.length], rawT = new float[p.camTan.length];
		for (int i = 0; i < raw.length; i++) {
			double a = Math.toRadians(i * 360.0 / raw.length), ca = Math.cos(a), sa = Math.sin(a);
			float need = 0, needT = 0;
			for (double sdist = 2; sdist <= dMax; sdist += 1.5) {
				int x = (int) Math.floor(p.cx + 0.5 + ca * sdist), z = (int) Math.floor(p.cz + 0.5 + sa * sdist);
				int ix = x - p.cx + clear, iz = z - p.cz + clear;
				// binnen de opgeruimde zone telt de grond (bomen gaan weg); daarbuiten alles, ook boomkruinen
				int surf = surfaceY(level, x, z);
				needT = Math.max(needT, (float) ((surf + 2.0 - (p.y0 + 1)) / sdist));
				if (sdist < p.radius + 1) continue;
				int obs = ix >= 0 && iz >= 0 && ix < gyMap.length && iz < gyMap.length
						? Math.min(gyMap[ix][iz], p.y0 + (int) Math.ceil(Math.max(0, sdist - p.radius)))
						: surf;
				need = Math.max(need, (float) ((obs + 2.0 - (p.y0 + 1)) / sdist));
			}
			raw[i] = need;
			rawT[i] = needT;
		}
		smoothRing(raw, p.camTan);
		smoothRing(rawT, p.camTanTrees);
	}

	/** Lichtende ring op het golffront. u = 0..1 voortgang. */
	static void effecten(ShowPlan p, ServerLevel level, float u) {
		float r = (p.radius + p.blend) * u;
		int n = Math.max(10, (int) (r * 1.6f));
		double phase = u * 40;
		for (int i = 0; i < n; i++) {
			double a = phase + i * (Math.PI * 2 / n);
			level.sendParticles(ParticleTypes.END_ROD, true, true,
					p.cx + 0.5 + Math.cos(a) * r, p.y0 + 1.3, p.cz + 0.5 + Math.sin(a) * r, 1, 0.05, 0.25, 0.05, 0.01);
		}
	}
}
