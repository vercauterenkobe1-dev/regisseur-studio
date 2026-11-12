package be.studio.regisseur.nabewerking;

import be.studio.regisseur.RegisseurClient;
import be.studio.regisseur.draaiboek.ShowPlan;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.block.Block;

import java.io.InputStream;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * Materiaalteller ("hoeveel blokken kost dit?"): de meest gebruikte blokken van de build, met per blok
 * de landingstijden en de textures voor een icoontje. De pipeline (render.py) tekent er de teller mee.
 * Textures komen uit de geladen resources van de game (dus ook uit een resourcepack). Client-thread.
 */
final class CounterData {
	static final int MAX = 6;

	static JsonObject build(List<ShowPlan.Op> src, Path runDir) {
		Map<Block, List<Float>> by = new LinkedHashMap<>();
		for (ShowPlan.Op op : src)
			if (!op.state.isAir() && !op.state.canBeReplaced()) by /* geen gras/bloemen/sneeuwlaagjes */.computeIfAbsent(op.state.getBlock(), b -> new ArrayList<>()).add(op.t);
		List<Map.Entry<Block, List<Float>>> top = new ArrayList<>(by.entrySet());
		top.sort((a, b) -> b.getValue().size() - a.getValue().size());
		JsonObject o = new JsonObject();
		JsonArray mats = new JsonArray();
		Path icons = runDir.resolve("icons");
		ResourceManager rm = Minecraft.getInstance().getResourceManager();
		for (int i = 0; i < Math.min(MAX, top.size()); i++) {
			Block b = top.get(i).getKey();
			Identifier id = BuiltInRegistries.BLOCK.getKey(b);
			JsonObject m = new JsonObject();
			m.addProperty("id", id.toString());
			List<Float> ts = top.get(i).getValue();
			Collections.sort(ts);
			JsonArray t = new JsonArray();
			for (float x : ts) t.add(Math.round(x * 1000f) / 1000f);
			m.add("times", t);
			try {
				Files.createDirectories(icons);
				Identifier item = Identifier.fromNamespaceAndPath(id.getNamespace(), "textures/item/" + id.getPath() + ".png");
				if (copy(rm, item, icons.resolve(i + "_flat.png"))) {
					m.addProperty("flat", i + "_flat.png");
				} else {
					Map<String, String> tex = textures(rm, id);
					String top_ = pick(tex, "top", "end", "up", "all", "texture", "particle", "side");
					String side = pick(tex, "side", "all", "north", "front", "texture", "particle", "top");
					if (top_ != null && copy(rm, tex(top_), icons.resolve(i + "_top.png"))) m.addProperty("top", i + "_top.png");
					if (side != null && copy(rm, tex(side), icons.resolve(i + "_side.png"))) m.addProperty("side", i + "_side.png");
					m.addProperty("tintTop", tinted(top_));
					m.addProperty("tintSide", tinted(side));
				}
			} catch (Exception e) {
				RegisseurClient.LOG.warn("icoon voor " + id + " niet gevonden", e);
			}
			mats.add(m);
		}
		o.add("materials", mats);
		o.addProperty("total", src.size());
		return o;
	}

	/** Groen inkleuren zoals de game doet voor gras en bladeren (vaste vlakte-kleur). */
	private static String tinted(String tex) {
		if (tex == null) return "";
		String n = tex.substring(tex.indexOf(':') + 1);
		if (n.contains("cherry") || n.contains("azalea") || n.contains("pale_oak")) return "";
		if (n.endsWith("grass_block_top") || n.endsWith("/short_grass") || n.endsWith("/fern") || n.endsWith("/vine")
				|| n.endsWith("grass_block_side_overlay")) return "79C05A";
		if (n.endsWith("spruce_leaves")) return "619961";
		if (n.endsWith("birch_leaves")) return "80A755";
		if (n.endsWith("_leaves")) return "59AE30";
		return "";
	}

	private static Identifier tex(String ref) {
		Identifier r = Identifier.parse(ref);
		return Identifier.fromNamespaceAndPath(r.getNamespace(), "textures/" + r.getPath() + ".png");
	}

	private static String pick(Map<String, String> m, String... keys) {
		for (String k : keys) if (m.containsKey(k)) return m.get(k);
		return null;
	}

	private static boolean copy(ResourceManager rm, Identifier id, Path dst) throws Exception {
		Optional<Resource> r = rm.getResource(id);
		if (r.isEmpty()) return false;
		try (InputStream in = r.get().open()) { Files.copy(in, dst, StandardCopyOption.REPLACE_EXISTING); }
		return true;
	}

	/** Blockstate -> eerste model -> textures (met parents), '#verwijzingen' opgelost. */
	private static Map<String, String> textures(ResourceManager rm, Identifier block) throws Exception {
		Map<String, String> tex = new HashMap<>();
		JsonObject bs = json(rm, Identifier.fromNamespaceAndPath(block.getNamespace(), "blockstates/" + block.getPath() + ".json"));
		String model = null;
		if (bs != null && bs.has("variants")) {
			for (Map.Entry<String, JsonElement> e : bs.getAsJsonObject("variants").entrySet()) { model = modelOf(e.getValue()); break; }
		} else if (bs != null && bs.has("multipart")) {
			for (JsonElement part : bs.getAsJsonArray("multipart")) { model = modelOf(part.getAsJsonObject().get("apply")); if (model != null) break; }
		}
		if (model == null) model = block.getNamespace() + ":block/" + block.getPath();
		for (int depth = 0; model != null && depth < 8; depth++) {
			Identifier mid = Identifier.parse(model);
			JsonObject mj = json(rm, Identifier.fromNamespaceAndPath(mid.getNamespace(), "models/" + mid.getPath() + ".json"));
			if (mj == null) break;
			if (mj.has("textures"))
				for (Map.Entry<String, JsonElement> e : mj.getAsJsonObject("textures").entrySet())
					if (e.getValue().isJsonPrimitive()) tex.putIfAbsent(e.getKey(), e.getValue().getAsString());
			model = mj.has("parent") ? mj.get("parent").getAsString() : null;
		}
		Map<String, String> out = new HashMap<>();
		for (String k : tex.keySet()) {
			String v = tex.get(k);
			for (int i = 0; i < 8 && v != null && v.startsWith("#"); i++) v = tex.get(v.substring(1));
			if (v != null && !v.startsWith("#")) out.put(k, v.contains(":") ? v : "minecraft:" + v);
		}
		return out;
	}

	private static String modelOf(JsonElement v) {
		if (v == null) return null;
		if (v.isJsonArray()) v = v.getAsJsonArray().size() > 0 ? v.getAsJsonArray().get(0) : null;
		if (v == null || !v.isJsonObject() || !v.getAsJsonObject().has("model")) return null;
		return v.getAsJsonObject().get("model").getAsString();
	}

	private static JsonObject json(ResourceManager rm, Identifier id) throws Exception {
		Optional<Resource> r = rm.getResource(id);
		if (r.isEmpty()) return null;
		try (Reader in = r.get().openAsReader()) { return JsonParser.parseReader(in).getAsJsonObject(); }
	}
}
