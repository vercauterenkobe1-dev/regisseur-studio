package be.studio.regisseur;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/** Mappen, instellingen en het projectbestand (het gedeelde draaiboek van UI, mod en Claude). */
public final class Studio {
	public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	public static Path base() {
		return Minecraft.getInstance().gameDirectory.toPath().resolve("studio");
	}

	public static Path dir(String name) {
		Path p = base().resolve(name);
		try { Files.createDirectories(p); } catch (IOException ignored) { }
		return p;
	}

	public static JsonObject config() {
		Path p = base().resolve("config.json");
		try {
			if (Files.exists(p)) return JsonParser.parseString(Files.readString(p)).getAsJsonObject();
		} catch (Exception e) {
			RegisseurClient.LOG.warn("config.json onleesbaar", e);
		}
		return new JsonObject();
	}

	public static String cfg(String key, String def) { return str(config(), key, def); }

	public static Path projectFile() { return dir("projects").resolve("current.json"); }

	public static JsonObject loadProject() {
		try {
			if (Files.exists(projectFile()))
				return JsonParser.parseString(Files.readString(projectFile())).getAsJsonObject();
		} catch (Exception e) {
			RegisseurClient.LOG.warn("project onleesbaar, standaard gebruikt", e);
		}
		return JsonParser.parseString("""
				{"name":"Mijn eerste short","schematic":"",
				 "timing":{"intro":2,"terraform":5,"build":14,"outro":4},
				 "camera":{"orbitSpeed":14,"fov":70,"distance":1.0,"style":"shots","transition":"auto"},
				 "site":{"margin":3,"radius":12},
				 "terraform":{"style":"square","depth":24,"length":2.5},
				 "build":{"style":"place"},
				 "world":{"time":"1000","weather":"clear"},
				 "music":{"file":"","in":0,"sync":"true"},
				 "captions":[
				  {"start":0.2,"end":1.9,"text":"Watch this spot"},
				  {"start":2.2,"end":6.5,"text":"First we flatten the land"},
				  {"start":7.5,"end":12,"text":"Then it grows block by block"},
				  {"start":21.5,"end":24.5,"text":"Follow for more builds"}],
				 "style":{"font":"Arial Black","size":92,"highlight":"FFE600","y":1180,"words_per_chunk":3}}
				""").getAsJsonObject();
	}

	public static void saveProject(JsonObject p) throws IOException {
		Files.writeString(projectFile(), GSON.toJson(p), StandardCharsets.UTF_8);
	}

	public static JsonArray list(String dirName) {
		JsonArray a = new JsonArray();
		try (Stream<Path> s = Files.list(dir(dirName))) {
			s.filter(Files::isRegularFile).map(x -> x.getFileName().toString()).sorted().forEach(a::add);
		} catch (IOException ignored) { }
		return a;
	}

	// ---- kleine JSON-hulpjes ----
	public static JsonObject obj(JsonObject o, String k) {
		JsonElement e = o.get(k);
		return e != null && e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
	}

	public static String str(JsonObject o, String k, String def) {
		JsonElement e = o.get(k);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : def;
	}

	public static float num(JsonObject o, String k, float def) {
		try {
			JsonElement e = o.get(k);
			return e != null && e.isJsonPrimitive() ? e.getAsFloat() : def;
		} catch (Exception ex) {
			return def;
		}
	}
}
