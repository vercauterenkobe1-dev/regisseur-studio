package be.studio.regisseur;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Het ritme van de muziek, omgerekend naar VIDEO-tijd (t = muziektijd - in).
 * De tellen komen van pipeline/beats.py (getest met test_beats.py) en worden per bestand bewaard,
 * zodat een tweede opname met dezelfde muziek meteen kan starten.
 */
public final class MusicSync {
	public float bpm, period, in, confidence, duration;
	public final List<Float> beats = new ArrayList<>();      // video-tijd
	public final List<Float> downbeats = new ArrayList<>();  // video-tijd, eerste tel van elke maat

	/** Analyseert (of leest uit de cache) en kiest 'in' zodat de video op een maatstart begint. Achtergrond-thread. */
	public static MusicSync analyze(Path music, float wantedIn) throws Exception {
		Path cacheDir = music.getParent().resolve(".beats");
		Files.createDirectories(cacheDir);
		Path cache = cacheDir.resolve(music.getFileName() + "-" + Files.size(music) + ".json");
		String json;
		if (Files.exists(cache)) {
			json = Files.readString(cache);
		} else {
			Path pipeline = Path.of(Studio.cfg("pipeline", Studio.base().resolve("pipeline").toString()));
			ProcessBuilder pb = new ProcessBuilder(Studio.cfg("python", "python"),
					pipeline.resolve("beats.py").toString(), music.toString());
			String ff = Studio.cfg("ffmpeg", "");
			if (!ff.isBlank() && Path.of(ff).getParent() != null)
				pb.environment().merge("Path", Path.of(ff).getParent().toString(), (old, add) -> add + ";" + old);
			pb.redirectErrorStream(false);
			Process p = pb.start();
			byte[] out = p.getInputStream().readAllBytes();
			String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
			if (!p.waitFor(3, TimeUnit.MINUTES) || p.exitValue() != 0)
				throw new IllegalStateException("beat-herkenning mislukt: " + err.strip());
			json = new String(out, StandardCharsets.UTF_8).strip();
			Files.writeString(cache, json);
		}
		JsonObject o = JsonParser.parseString(json).getAsJsonObject();
		MusicSync s = new MusicSync();
		s.bpm = o.get("bpm").getAsFloat();
		s.period = o.get("period").getAsFloat();
		s.confidence = o.get("confidence").getAsFloat();
		s.duration = o.get("duration").getAsFloat();
		JsonArray db = o.getAsJsonArray("downbeats"), b = o.getAsJsonArray("beats");
		// muziek start op de eerste maatstart vanaf het gevraagde punt
		float in = wantedIn;
		for (int i = 0; i < db.size(); i++) {
			float d = db.get(i).getAsFloat();
			if (d >= wantedIn - 0.02f) { in = d; break; }
		}
		s.in = in;
		for (int i = 0; i < b.size(); i++) { float t = b.get(i).getAsFloat() - in; if (t > -s.period) s.beats.add(t); }
		for (int i = 0; i < db.size(); i++) { float t = db.get(i).getAsFloat() - in; if (t > -0.01f) s.downbeats.add(t); }
		return s;
	}

	/** Eerste maatstart op of na t (of t zelf als de muziek ophoudt). */
	public float downbeatAtOrAfter(float t) {
		for (float d : downbeats) if (d >= t - 0.001f) return d;
		return t;
	}

	/** Maatstart die het dichtst bij 'target' ligt maar niet voor 'min'. */
	public float nearestDownbeat(float target, float min) {
		float best = Float.NaN;
		for (float d : downbeats) {
			if (d < min - 0.001f) continue;
			if (Float.isNaN(best) || Math.abs(d - target) < Math.abs(best - target)) best = d;
		}
		return Float.isNaN(best) ? Math.max(target, min) : best;
	}

	/** Achtste noten (halve tellen) in [from, to). */
	public List<Float> eighths(float from, float to) {
		List<Float> g = new ArrayList<>();
		for (float b : beats) {
			for (float h : new float[]{b, b + period / 2}) if (h >= from - 0.001f && h < to - 0.001f) g.add(h);
		}
		g.sort(Float::compare);
		return g;
	}

	/** Dichtstbijzijnde achtste noot. */
	public float snap8(float t) {
		float best = t, bd = Float.MAX_VALUE;
		for (float b : beats) {
			for (float h : new float[]{b, b + period / 2}) {
				float d = Math.abs(h - t);
				if (d < bd) { bd = d; best = h; }
			}
		}
		return best;
	}
}
