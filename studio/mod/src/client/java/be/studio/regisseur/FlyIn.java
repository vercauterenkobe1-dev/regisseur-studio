package be.studio.regisseur;

import be.studio.regisseur.mixin.BlockDisplayInvoker;
import be.studio.regisseur.mixin.DisplayInvoker;
import com.mojang.math.Transformation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Blokken vliegen naar hun plek (idee van Create's Schematicannon + het "inveren" van BlockMotion, eigen code).
 * Techniek: vanilla block displays. Die interpoleren vloeiend per frame op de client, dus dit kost de server
 * bijna niets. Verloop per blok: wachten -> vlucht (iets te groot aankomen) -> inveren -> echt blok.
 * De overgangen gebeuren op VIDEO-tijd (exact op de maat); de animatieduur is in client-ticks (vaste klok).
 * Alles hier draait op de server-thread.
 */
public final class FlyIn {
	/**
	 * Twee stijlen. "fly": het blok vliegt van schuin boven naar zijn plek (2 + 7 + 2 ticks).
	 * "place": zoals een speler een blok zet: het verschijnt op zijn plek, klein, en popt in 2 ticks naar zijn maat.
	 */
	private boolean place;
	private int ARM = 2, FLIGHT = 7, SETTLE = 2;

	public void setPlaceStyle(boolean p) {
		place = p;
		if (p) { ARM = 0; FLIGHT = 2; SETTLE = 1; } else { ARM = 2; FLIGHT = 7; SETTLE = 2; }
	}

	public boolean placeStyle() { return place; }

	/** Hoe lang voor de landing het blok vertrekt (seconden). */
	public float lead() { return (ARM + FLIGHT + SETTLE) / 20f; }
	private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

	private static final class Item {
		Display.BlockDisplay e; BlockPos pos; BlockState state; int stage; float due; boolean spark;
	}

	private final List<Item> items = new ArrayList<>();
	public int launched, landed;

	private static Transformation at(float ox, float oy, float oz, float scale, float spin) {
		float c = (1 - scale) / 2f; // schalen rond het midden van het blok
		return new Transformation(new Vector3f(ox + c, oy + c, oz + c), new Quaternionf().rotateY(spin),
				new Vector3f(scale, scale, scale), new Quaternionf());
	}

	public void launch(ServerLevel level, float t, int x, int y, int z, BlockState state, double cx, double cz, boolean spark) {
		Display.BlockDisplay e = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.COMMAND);
		if (e == null) { level.setBlock(new BlockPos(x, y, z), state, FLAGS); return; }
		e.setPos(x, y, z);
		// start: schuin boven en buiten het bouwwerk, klein en gedraaid
		double dx = x + 0.5 - cx, dz = z + 0.5 - cz, len = Math.max(0.6, Math.sqrt(dx * dx + dz * dz));
		int h = x * 73428767 ^ y * 19349663 ^ z * 912931;
		float r1 = ((h >>> 8) & 255) / 255f, r2 = ((h >>> 16) & 255) / 255f;
		float ox = (float) (dx / len * (2.5 + 2.5 * r1)), oz = (float) (dz / len * (2.5 + 2.5 * r1)), oy = 6f + 5f * r2;
		DisplayInvoker d = (DisplayInvoker) e;
		((BlockDisplayInvoker) e).regisseur$setBlockState(state);
		d.regisseur$setViewRange(16f);
		// De eindplek zit vaak ingesloten tussen blokken (licht 0): zonder dit vliegen de blokken zwart binnen.
		d.regisseur$setBrightness(new net.minecraft.util.Brightness(0, 15));
		d.regisseur$setDuration(0);
		d.regisseur$setTransformation(place ? at(0, 0, 0, 0.55f, 0) : at(ox, oy, oz, 0.25f, (r1 - 0.5f) * 5f));
		level.addFreshEntity(e);
		Item it = new Item();
		it.e = e; it.pos = new BlockPos(x, y, z); it.state = state; it.stage = 0; it.due = t + ARM / 20f; it.spark = spark;
		items.add(it);
		launched++;
	}

	/** Elk videoframe aanroepen met de huidige videotijd. */
	public void pump(ServerLevel level, float tick) {
		for (Iterator<Item> i = items.iterator(); i.hasNext(); ) {
			Item it = i.next();
			if (tick < it.due - 0.0001f) continue;
			DisplayInvoker d = (DisplayInvoker) it.e;
			if (it.stage == 0) { // vlucht: iets te groot aankomen
				d.regisseur$setDelay(0);
				d.regisseur$setDuration(FLIGHT);
				d.regisseur$setTransformation(at(0, 0, 0, place ? 1.08f : 1.14f, 0));
				it.stage = 1; it.due += FLIGHT / 20f;
			} else if (it.stage == 1) { // inveren naar de echte maat
				d.regisseur$setDelay(0);
				d.regisseur$setDuration(SETTLE);
				d.regisseur$setTransformation(at(0, 0, 0, 1f, 0));
				it.stage = 2; it.due += SETTLE / 20f;
			} else {
				land(level, it);
				i.remove();
			}
		}
	}

	private void land(ServerLevel level, Item it) {
		level.setBlock(it.pos, it.state, FLAGS);
		it.e.discard();
		landed++;
		if (it.spark) level.sendParticles(ParticleTypes.WAX_OFF, true, true,
				it.pos.getX() + 0.5, it.pos.getY() + 0.6, it.pos.getZ() + 0.5, 4, 0.35, 0.35, 0.35, 0.0);
	}

	/** Alles wat nog onderweg is meteen neerzetten (einde of afbreken van de show). */
	public void flush(ServerLevel level) {
		for (Item it : items) land(level, it);
		items.clear();
	}

	public int inFlight() { return items.size(); }
}
