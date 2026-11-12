package be.studio.regisseur.draaiboek;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import static be.studio.regisseur.draaiboek.Terrein.*;

/**
 * Terraform-stijl "uitgraven" (keuze "quarry"): een lange, diepe groef met loodrechte wanden, met het bouwwerk
 * aan het verre einde. De groef wordt vak per vak uitgegraven; binnen een vak zakt de grond laag per laag weg
 * (hele laag tegelijk), zodat je een blok terrein ziet "wegzinken" en de wanden met ertsen open komen te liggen.
 * Water en lava in de wanden worden dichtgemaakt met steen, zodat de put niet onderloopt.
 */
final class GroefTerraform {
	private GroefTerraform() { }

	static void maak(ShowPlan p, ServerLevel level, Schematic sch, ShowPlan.Quarry q, int margin, int radiusNoSchematic,
			float tIntro, float tTerra, float distMul, MusicSync sync) {
		p.quarry = true;
		p.qax = q.ax(); p.qaz = q.az(); p.qx0 = p.cx; p.qz0 = p.cz; p.qu0 = 3;
		int along = sch == null ? 2 * radiusNoSchematic : (p.qax != 0 ? sch.sx : sch.sz);
		int across = sch == null ? 2 * radiusNoSchematic : (p.qax != 0 ? sch.sz : sch.sx);
		p.qW = across + 2 * margin;
		p.qLen = Math.max(along + 2 * margin, Math.round(p.qW * Math.max(1f, q.lengthMul())));
		// bouwwerk aan het verre einde van de groef
		double uC = p.qu0 + p.qLen - margin - along / 2.0;
		double[] c = p.pitPoint(uC, 0);
		p.cx = (int) Math.floor(c[0]); p.cz = (int) Math.floor(c[1]);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		p.rimY = groundY(level, p.cx, p.cz, pos);
		p.y0 = p.rimY; // de bouw staat op het maaiveld; de kloof steekt er bovenuit
		final int floorY = p.y0;
		final BlockState AIR = Blocks.AIR.defaultBlockState(), STONE = Blocks.STONE.defaultBlockState();
		{ // in het water graven kan niet: de put loopt vol. Dan liever meteen een duidelijke melding.
			int wet = 0, n = 0;
			for (int u = 0; u < p.qLen; u += 2)
				for (int v = 0; v < p.qW; v += 2) {
					int[] xz = cell(p, u, v);
					if (fluid(level.getBlockState(pos.set(xz[0], surfaceY(level, xz[0], xz[1]), xz[1])))) wet++;
					n++;
				}
			if (wet > n * 0.15)
				throw new IllegalStateException("hier ligt te veel water (" + Math.round(100f * wet / n)
						+ "% van de groef). Ga op het land staan, of kies de stijl Vlakmaken");
		}

		int nW = Math.max(1, Math.round(p.qW / 14f)), segW = (p.qW + nW - 1) / nW;
		int nL = Math.max(1, Math.round(p.qLen / (float) segW)), segL = (p.qLen + nL - 1) / nL;
		p.qSegL = segL;
		int nSeg = nW * nL;
		float step = tTerra * 0.86f / (nSeg + 0.8f), dur = step * 1.8f;
		int k = 0;
		for (int i = 0; i < nL; i++) {
			for (int jj = 0; jj < nW; jj++, k++) {
				int j = (i % 2 == 0) ? jj : nW - 1 - jj; // slingerend over de breedte
				float ts = tIntro + k * step;
				if (sync != null) ts = Math.max(tIntro, sync.snap8(ts));
				float te = Math.min(tIntro + tTerra * 0.98f, ts + dur);
				int u1 = i * segL, u2 = Math.min(p.qLen, u1 + segL), v1 = j * segW, v2 = Math.min(p.qW, v1 + segW);
				int segTop = floorY + 1;
				int[][] tops = new int[u2 - u1][v2 - v1];
				for (int u = u1; u < u2; u++)
					for (int v = v1; v < v2; v++) {
						int[] xz = cell(p, u, v);
						tops[u - u1][v - v1] = surfaceY(level, xz[0], xz[1]);
						segTop = Math.max(segTop, tops[u - u1][v - v1]);
					}
				float span = Math.max(1, segTop - floorY);
				for (int u = u1; u < u2; u++)
					for (int v = v1; v < v2; v++) {
						int[] xz = cell(p, u, v);
						int x = xz[0], z = xz[1];
						for (int y = tops[u - u1][v - v1]; y > floorY; y--) {
							BlockState st = level.getBlockState(pos.set(x, y, z));
							if (st.isAir()) continue;
							float t = ts + (te - ts) * (segTop - y) / span; // hele laag tegelijk: het blok zinkt
							// weinig stof: alleen aan het oppervlak en dun gezaaid, anders wordt het beeld korrelig
							boolean dust = y == tops[u - u1][v - v1] && ((x * 7 + z * 13) & 3) == 0;
							p.ops.add(new ShowPlan.Op(t, x, y, z, AIR, dust && !fluid(st) ? st : null, dust && !fluid(st) ? 1 : 0));
						}
						BlockState fl = level.getBlockState(pos.set(x, floorY, z));
						if (fl.isAir() || fluid(fl) || !fl.isSolidRender()) p.ops.add(new ShowPlan.Op(te, x, floorY, z, STONE, null, 0));
						if (v == 0 || v == p.qW - 1) { // verhoogde wand langs de rand
							int wallH = Math.max(4, q.depth() / 3);
							BlockState wall = (u % 3 == 0) ? Blocks.STONE_BRICKS.defaultBlockState() : Blocks.STONE.defaultBlockState();
							for (int h = 1; h <= wallH; h++) p.ops.add(new ShowPlan.Op(te + h * 0.02f, x, floorY + h, z, wall, null, h == wallH ? 1 : 0));
						}
					}
				// wanden dichten (water/lava) en overhangende bomen langs dit vak weghalen
				for (int u = u1 - 1; u <= u2; u++)
					for (int v = v1 - 1; v <= v2; v++) {
						boolean edgeU = u == -1 || u == p.qLen, edgeV = v == -1 || v == p.qW;
						if (!(edgeU || edgeV) || u < -1 || u > p.qLen || v < -1 || v > p.qW) continue;
						for (int r = 0; r < 3; r++) {
							int uu = u + (u == -1 ? -r : u == p.qLen ? r : 0), vv = v + (v == -1 ? -r : v == p.qW ? r : 0);
							int[] xz = cell(p, uu, vv);
							int top = surfaceY(level, xz[0], xz[1]);
							for (int y = top; y >= floorY; y--) {
								BlockState st = level.getBlockState(pos.set(xz[0], y, xz[1]));
								if (r == 0 && fluid(st)) p.ops.add(new ShowPlan.Op(ts, xz[0], y, xz[1], STONE, null, 0));
								else if (treeish(st)) p.ops.add(new ShowPlan.Op(ts, xz[0], y, xz[1], AIR, y == top ? st : null, y == top ? 1 : 0));
							}
						}
					}
			}
		}
		p.terraOps = p.ops.size();
		p.camBase = Math.max(26, (p.half + 2) * 2.9 + p.height * 0.5 + (p.rimY - floorY) * 0.8) * distMul;
		// zichtlijn: binnen de groef telt de bodem, daarbuiten het maaiveld met bomen
		zichtlijn(p.cx, p.cz, floorY, p.camBase * 1.4 + 6, (x, z) -> inPit(p, x, z) ? floorY : surfaceY(level, x, z), p.camTan);
		System.arraycopy(p.camTan, 0, p.camTanTrees, 0, p.camTan.length);
	}

	/** Wereldcoördinaat van vak (u langs de groef, v dwars erop). */
	private static int[] cell(ShowPlan p, int u, int v) {
		return new int[]{p.qx0 + p.qax * (p.qu0 + u) - p.qaz * (v - p.qW / 2), p.qz0 + p.qaz * (p.qu0 + u) + p.qax * (v - p.qW / 2)};
	}

	private static boolean inPit(ShowPlan p, int x, int z) {
		int du = (x - p.qx0) * p.qax + (z - p.qz0) * p.qaz, dv = (x - p.qx0) * -p.qaz + (z - p.qz0) * p.qax + p.qW / 2;
		return du >= p.qu0 && du < p.qu0 + p.qLen && dv >= 0 && dv < p.qW;
	}

	/** Effecten bij het uitgraven: stofwolk en lichtpuntjes langs het graaffront, over de hele breedte. u = 0..1. */
	static void effecten(ShowPlan p, ServerLevel level, float u) {
		for (int i = 0; i < 9; i++) {
			double v = (i - 4) * (p.qW * 0.45) / 4.0;
			double[] pt = p.pitPoint(p.qu0 + Math.max(0.5, Math.min(p.qLen - 0.5, u * p.qLen)), v);
			level.sendParticles(ParticleTypes.CLOUD, true, true, pt[0] + 0.5, p.y0 + 1.6, pt[1] + 0.5, 2, 0.5, 0.4, 0.5, 0.02);
			level.sendParticles(ParticleTypes.END_ROD, true, true, pt[0] + 0.5, p.y0 + 2.6, pt[1] + 0.5, 1, 0.2, 0.4, 0.2, 0.02);
		}
	}
}
