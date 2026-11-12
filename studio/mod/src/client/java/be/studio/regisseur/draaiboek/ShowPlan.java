package be.studio.regisseur.draaiboek;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * HET DRAAIBOEK van één show, vooraf berekend: elke blokwijziging met zijn tijdstip in VIDEO-tijd.
 * Daardoor is de opname frame-exact en onafhankelijk van hoe snel de laptop rendert.
 *
 * Wie doet wat:
 *  - build()            maakt het draaiboek: eerst de terraform (VierkantTerraform / GroefTerraform / VlakTerraform),
 *                       dan het bouwwerk (Bouwvolgorde), en noteert wat er weggegraven wordt (voor de teller);
 *  - apply()            voert per frame de blokwijzigingen uit die aan de beurt zijn (server-thread);
 *  - terraformFx()      deeltjes-effecten tijdens de terraform; finaleFx() het slotvuurwerk;
 *  - verify()           telt na de show of elk blok van het bouwwerk juist staat.
 */
public final class ShowPlan {

	/** Eén blokwijziging: op tijd t wordt (x, y, z) het blok state. */
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

	/** Instellingen voor de stijl "uitgraven": as (kijkrichting), diepte en lengte van de groef. */
	public record Quarry(int ax, int az, int depth, float lengthMul) { }
	/** Instellingen voor de stijl "vierkant": diepte van het gat en de kant waar de sleuf start (camerakant). */
	public record Square(int depth, float startDeg) { }

	// ---- het draaiboek zelf ----
	public final List<Op> ops = new ArrayList<>();          // alle blokwijzigingen, gesorteerd op tijd
	public final List<Op> buildTargets = new ArrayList<>(); // eindtoestand van het bouwwerk (controle + ritme-judge)
	public final List<Op> mined = new ArrayList<>();        // weggegraven blokken (origineel), voor de teller en geluid
	public final FlyIn fly = new FlyIn();                   // blokken die invliegen of "poppen"
	public MusicSync sync;                                  // null = geen muziek of niet op de maat

	// ---- tijden (seconden video) ----
	public float tIntro, tTerra, tBuild, tOutro;
	public float finaleT;                                   // tijdstip van het slotvuurwerk
	public int terraOps, buildOps;

	// ---- de bouwplaats ----
	public int cx, cz, y0;          // midden van de bouwplaats en grondniveau
	public int radius, blend;       // vlakke schijf + overgangsrand
	public int half, height;        // halve breedte en hoogte van het bouwwerk
	public int rimY;                // maaiveld (voor de opening)

	// ---- camera ----
	public double camBase;                                   // basisafstand van de camera
	public final float[] camTan = new float[72];             // per 5 graden: minimale helling voor een vrije zichtlijn
	public final float[] camTanTrees = new float[72];        // idem, met de bomen die er voor de terraform nog staan

	// ---- stijl "vierkant" ----
	public boolean square;
	public int sqHalf, sqRing, sqFloor;                      // halve zijde, breedte van de sleuf, bodem van het gat
	public float sqRingStart, sqRingDur, sqHollowStart, sqHollowDur, sqRiseStart, sqRiseDur;
	double sqA0;                                             // starthoek van de sleuf (radialen)

	// ---- stijl "uitgraven" ----
	public boolean quarry;
	public int qx0, qz0, qu0, qLen, qW, qSegL;               // groef: startpunt, afstand tot de rand, lengte, breedte, vaklengte
	public int qax, qaz;                                     // as van de groef

	public float total() { return tIntro + tTerra + tBuild + tOutro; }

	/** Maakt het draaiboek. Moet op de server-thread draaien (leest de wereld). */
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
		p.y0 = Terrein.groundY(level, cx, cz, pos);
		{ // staat het midden in het water, dan bouwen we een eiland op waterhoogte in plaats van op de bodem
			int w = Terrein.waterY(level, cx, cz, level.getHeight(Heightmap.Types.WORLD_SURFACE, cx, cz) - 1, p.y0, pos);
			if (w > p.y0) p.y0 = w;
		}

		// 1. terraform
		if (square != null) VierkantTerraform.maak(p, level, sch, square, margin, radiusNoSchematic, tIntro, tTerra, distMul);
		else if (quarry != null) GroefTerraform.maak(p, level, sch, quarry, margin, radiusNoSchematic, tIntro, tTerra, distMul, sync);
		else VlakTerraform.maak(p, level, tIntro, tTerra, distMul, sync);

		// 2. het bouwwerk
		if (sch != null) Bouwvolgorde.plan(p, sch, tIntro, tTerra, tBuild, sync);

		p.ops.sort((a, b) -> Float.compare(a.t, b.t));

		// 3. wat wordt er weggegraven? De wereld is hier nog onaangeroerd, dus de huidige toestand is het origineel.
		HashSet<Long> seen = new HashSet<>();
		for (Op o : p.ops) {
			if (!o.state.isAir() || !seen.add(BlockPos.asLong(o.x, o.y, o.z))) continue;
			BlockState st = level.getBlockState(pos.set(o.x, o.y, o.z));
			if (!st.isAir() && !Terrein.fluid(st)) p.mined.add(new Op(o.t, o.x, o.y, o.z, st, null, 0));
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

	/** Deeltjes tijdens de terraform, passend bij de stijl. t = videotijd, u = 0..1 voortgang van de terraform. */
	public void terraformFx(ServerLevel level, float t, float u) {
		if (square) VierkantTerraform.effecten(this, level, t);
		else if (quarry) GroefTerraform.effecten(this, level, u);
		else VlakTerraform.effecten(this, level, u);
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

	// ---- voor de camera ----

	/** Waar de kop van de sleuf is op tijd t (alleen stijl "vierkant"). */
	public double[] ringHead(float t) { return VierkantTerraform.ringHead(this, t); }

	/** Wereldpunt op de middenlijn van de groef, op afstand u langs de as en v dwars erop (stijl "uitgraven"). */
	public double[] pitPoint(double u, double v) {
		return new double[]{qx0 + 0.5 + qax * u - qaz * v, qz0 + 0.5 + qaz * u + qax * v};
	}

	/** Minimale helling (hoogte per blok afstand) voor de zichtlijn in deze richting; bomen tellen mee. */
	public float clearanceTrees(double angleDeg) { return Terrein.interp(camTanTrees, angleDeg); }

	/** Minimale helling voor de zichtlijn in deze richting, na de terraform. */
	public float clearance(double angleDeg) { return Terrein.interp(camTan, angleDeg); }
}
