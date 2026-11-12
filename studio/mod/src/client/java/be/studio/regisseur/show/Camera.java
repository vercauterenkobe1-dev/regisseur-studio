package be.studio.regisseur.show;

import be.studio.regisseur.draaiboek.MusicSync;
import be.studio.regisseur.draaiboek.ShowPlan;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import static be.studio.regisseur.draaiboek.Easing.smooth;

/**
 * DE CAMERA: welke shot wanneer, en waar het oog hangt op elk moment van de video.
 *
 * Shots (in volgorde):  dolly (opening, schuift naar de plek) -> ring/dig/crane (terraform, van boven)
 *                       -> orbit (bouw, draait mee) -> hero (laag, omhoog kijkend) -> reveal (achteruit en omhoog).
 * Cuts vallen op maatstarts als er muziek is, anders vloeien de shots in elkaar over.
 * De camera "ademt" op de maat (breath): traag op de maatstart, sneller ertussen.
 * Veiligheid: het oog blijft boven bomen en heuvels (zichtlijn uit het draaiboek) en wordt opgetild als het
 * toch in een blok zit; dat wordt geteld voor de controle (R5).
 */
public final class Camera {
	private Camera() { }

	/** Eén shot: soort en begin/einde in seconden video. */
	public record Shot(String type, float start, float end) { }

	static final List<Shot> shots = new ArrayList<>();
	static boolean smoothCuts;
	static float orbitSpeed, startAngle;
	static ShowPlan plan;

	// ---- metingen voor de controles ----
	static int camInBlock, camLifted;
	/** Camerahoek rond het midden per frame (voor D2 in sync_check.py). */
	static final TreeMap<Integer, Float> camAngle = new TreeMap<>();
	private static float lastLift;

	/** Nieuwe opname: metingen op nul. */
	static void reset(ShowPlan p) {
		plan = p;
		camInBlock = camLifted = 0;
		camAngle.clear();
		lastLift = 0;
	}

	/** Shotlijst: cuts op de grenzen tussen de delen (op maatstarts als er muziek is), plus een cut halverwege de bouw. */
	static void buildShots(boolean singleOrbit, String terraStyle, float a, float b, float c, float e, MusicSync sync) {
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

	static Shot shotAt(float t) {
		for (Shot s : shots) if (t < s.end) return s;
		return shots.get(shots.size() - 1);
	}

	/** Hoogste en verste punt van de hele camerabaan (voor wolken en zichtafstand). {maxY, maxAfstand} */
	static double[] extremen() {
		double maxEy = 0, far = 0;
		for (float t = 0; t < plan.total(); t += 1 / 30f) {
			double[] e = pose(shotAt(t), t);
			maxEy = Math.max(maxEy, e[1]);
			far = Math.max(far, Math.hypot(e[0] - plan.cx, e[2] - plan.cz));
		}
		return new double[]{maxEy, far};
	}

	/** Hoeksnelheid ademt per maat: 0,55x op de maatstart, 1,45x halverwege. Geeft de "gevoelde" tijd sinds start. */
	private static float breath(float t, float start) {
		float bar = 2f, db = 0f;
		if (plan.sync != null && plan.sync.downbeats.size() > 1) {
			db = plan.sync.downbeats.get(0);
			bar = plan.sync.downbeats.get(1) - db;
		}
		double w = 2 * Math.PI / Math.max(0.5f, bar), A = 0.45;
		return (float) ((t - start) - A / w * (Math.sin(w * (t - db)) - Math.sin(w * (start - db))));
	}

	/** Oog en kijkpunt voor shot s op tijd t: {ex, ey, ez, lx, ly, lz}. */
	static double[] pose(Shot s, float t) {
		ShowPlan p = plan;
		float dur = Math.max(0.1f, s.end() - s.start()), u = smooth((t - s.start()) / dur), lt = t - s.start();
		float bl = breath(t, s.start()); // shot-tijd die per maat ademt
		double cxw = p.cx + 0.5, czw = p.cz + 0.5, gy = p.y0 + 1, H = Math.max(4, p.height), B = p.camBase;
		double grow = smooth((t - p.tIntro - p.tTerra) / Math.max(0.1f, p.tBuild)); // hoe ver de bouw is
		if (p.square && s.type().equals("ring")) { // hoog boven het vierkant; volgt de kop van de sleuf, daarna het midden
			double[] head = p.ringHead(t);
			float w = smooth((t - p.sqHollowStart + 0.3f) / 1.2f);
			double lx = head[0] + (p.cx + 0.5 - head[0]) * w, lz = head[1] + (p.cz + 0.5 - head[1]) * w;
			double lyR = p.y0 - (p.y0 - p.sqFloor) * 0.35 * w * (1 - smooth((t - p.sqRiseStart) / Math.max(0.1f, p.sqRiseDur)));
			// begint dichtbij (sleuf groot in beeld, direct na de teaser) en trekt dan rustig naar achter
			double pull = 0.65 + 0.35 * smooth((t - p.sqRingStart) / Math.max(0.1f, p.sqRingDur * 0.7f));
			double dd = Math.max(28, p.sqHalf * 3.2) * pull * (1 + 0.15 * w), el = Math.toRadians(64 - 6 * u);
			double ang = Math.toRadians(startAngle + 20 + 10 * bl), hdR = dd * Math.cos(el);
			// de camera draait rond het midden (niet rond de kop), zo zwiept het beeld niet bij de hoeken
			return new double[]{p.cx + 0.5 + Math.cos(ang) * hdR, p.y0 + dd * Math.sin(el), p.cz + 0.5 + Math.sin(ang) * hdR, lx, lyR, lz};
		}
		if (p.quarry && s.type().equals("dig")) { // hoog en schuin achter het graaffront, langs de groef naar beneden
			float f = Math.min(1f, Math.max(0f, (t - p.tIntro) / Math.max(0.1f, p.tTerra)));
			double front = p.qu0 + Math.max(p.qSegL * 0.5, Math.min(p.qLen - p.qSegL * 0.5, f * p.qLen));
			double[] look = p.pitPoint(front, 0);
			double lyD = (p.rimY + p.y0) / 2.0, dd = Math.max(30, p.qW * 2.2), el = Math.toRadians(62 - 6 * u);
			double side = Math.toRadians(22), hdD = dd * Math.cos(el);
			double bx = -(p.qax * Math.cos(side) - p.qaz * Math.sin(side)), bz = -(p.qaz * Math.cos(side) + p.qax * Math.sin(side));
			return new double[]{look[0] + bx * hdD, lyD + dd * Math.sin(el), look[1] + bz * hdD, look[0], lyD, look[1]};
		}
		if (p.quarry && s.type().equals("canyon")) { // op de rand aan het begin, langs de hele kloof naar het bouwwerk
			float x = Math.min(1f, Math.max(0f, lt / dur));
			double[] eye = p.pitPoint(p.qu0 - 2, -3 + 6 * x);
			return new double[]{eye[0], p.rimY + 5 + 4 * x, eye[1], cxw, gy + H * 0.6, czw};
		}
		if (p.quarry && s.type().equals("dolly")) gy = p.rimY + 1; // voor het graven: kijk naar het maaiveld
		double angDeg, d, elevDeg, ly;
		switch (s.type()) {
			case "dolly" -> { // laag openingsshot dat naar de bouwplaats toe schuift; beweegt vanaf het eerste frame
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
				if (shots.size() == 1) { // stijl "één camera" voor de hele video
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

	/** Zet de speler (in spectator) op de camerapositie voor videotijd t. Elk frame aangeroepen. */
	static void apply(Minecraft mc, LocalPlayer pl, float t) {
		Shot s = shotAt(t);
		double[] e = pose(s, t);
		if (smoothCuts) { // vloeiende overgang over 0,8 s rond elke grens
			int i = shots.indexOf(s);
			double[] o = null;
			float w = 0;
			if (i > 0 && t < s.start() + 0.4f) { o = pose(shots.get(i - 1), t); w = smooth((t - (s.start() - 0.4f)) / 0.8f); }
			else if (i < shots.size() - 1 && t > s.end() - 0.4f) { o = pose(shots.get(i + 1), t); w = 1 - smooth((t - (s.end() - 0.4f)) / 0.8f); }
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
		// Alle "vorige" posities gelijk zetten, anders interpoleert de game en trilt het beeld.
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
}
