package be.studio.regisseur;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.RandomSource;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * Geluidseffecten (WP3): per actie in de show het echte Minecraft-geluid. Schrijft sfx.json en kopieert de
 * .ogg-bestanden (uit de geladen resources, dus ook uit resourcepacks) naar sfx/. De pipeline (sfx.py) mixt ze.
 * Uitdunnen: per geluid hoogstens één gebeurtenis per frame, met het echte aantal erbij (n). Client-thread.
 */
final class SfxData {
	private static final int FPS = 60;

	static JsonObject build(ShowPlan p, Path runDir) throws Exception {
		Path dir = runDir.resolve("sfx");
		Files.createDirectories(dir);
		Minecraft mc = Minecraft.getInstance();
		ResourceManager rm = mc.getResourceManager();
		RandomSource rnd = RandomSource.create(7);
		// groep -> (frame -> aantal)
		Map<String, TreeMap<Integer, Integer>> ev = new LinkedHashMap<>();
		Map<String, String> kind = new HashMap<>();
		float terraEnd = p.tIntro + p.tTerra;
		for (ShowPlan.Op o : p.mined) {
			if (o.state.canBeReplaced()) continue; // gras/bloemetjes: te veel geritsel
			add(ev, kind, o.state.getSoundType().getBreakSound(), "break", o.t);
		}
		for (ShowPlan.Op o : p.ops) // terraform: grond die omhoog komt / wanden
			if (o.t < terraEnd && !o.state.isAir()) add(ev, kind, o.state.getSoundType().getPlaceSound(), "rise", o.t);
		for (ShowPlan.Op o : p.buildTargets) add(ev, kind, o.state.getSoundType().getPlaceSound(), "place", o.t);
		JsonArray fin = new JsonArray();
		if (p.buildOps > 0) {
			for (String s : new String[]{"entity.firework_rocket.blast", "entity.firework_rocket.large_blast", "entity.firework_rocket.twinkle"})
				fin.add(s);
		}
		Set<String> needed = new LinkedHashSet<>(ev.keySet());
		for (int i = 0; i < fin.size(); i++) needed.add("minecraft:" + fin.get(i).getAsString());
		needed.add("minecraft:entity.experience_orb.pickup");

		JsonObject sounds = new JsonObject();
		int fileNo = 0;
		for (String key : needed) {
			WeighedSoundEvents w = mc.getSoundManager().getSoundEvent(Identifier.parse(key));
			if (w == null) continue;
			JsonArray files = new JsonArray();
			Set<String> seen = new HashSet<>();
			for (int k = 0; k < 24 && seen.size() < 4; k++) { // varianten verzamelen (gewogen trekking)
				Sound s = w.getSound(rnd);
				if (s == null || s == net.minecraft.client.sounds.SoundManager.EMPTY_SOUND) continue;
				Identifier path = s.getPath(); // bv. minecraft:sounds/dig/stone1.ogg
				if (!seen.add(path.toString())) continue;
				Optional<Resource> r = rm.getResource(path);
				if (r.isEmpty()) continue;
				String name = (fileNo++) + "_" + path.getPath().replaceAll("[^a-z0-9]+", "_") ;
				try (InputStream in = r.get().open()) { Files.copy(in, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING); }
				JsonObject f = new JsonObject();
				f.addProperty("file", "sfx/" + name);
				f.addProperty("volume", s.getVolume().sample(rnd));
				f.addProperty("pitch", s.getPitch().sample(rnd));
				files.add(f);
			}
			if (files.size() > 0) sounds.add(key, files);
		}
		JsonArray events = new JsonArray();
		for (Map.Entry<String, TreeMap<Integer, Integer>> e : ev.entrySet()) {
			if (!sounds.has(e.getKey())) continue;
			for (Map.Entry<Integer, Integer> f : e.getValue().entrySet()) {
				JsonArray a = new JsonArray();
				a.add(f.getKey() / (float) FPS);
				a.add(e.getKey());
				a.add(f.getValue());
				a.add(kind.get(e.getKey()));
				events.add(a);
			}
		}
		JsonObject o = new JsonObject();
		o.add("sounds", sounds);
		o.add("events", events);
		o.addProperty("finale", p.buildOps > 0 ? p.finaleT : -1);
		JsonArray fk = new JsonArray();
		for (int i = 0; i < fin.size(); i++) fk.add("minecraft:" + fin.get(i).getAsString());
		o.add("finaleSounds", fk);
		o.addProperty("xp", "minecraft:entity.experience_orb.pickup");
		return o;
	}

	private static void add(Map<String, TreeMap<Integer, Integer>> ev, Map<String, String> kind, SoundEvent s, String k, float t) {
		if (s == null) return;
		String key = s.location().toString();
		ev.computeIfAbsent(key, x -> new TreeMap<>()).merge(Math.round(t * FPS), 1, Integer::sum);
		kind.putIfAbsent(key, k);
	}
}
