package be.studio.regisseur;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Na de opname: captions + gemasterde muziek + eindencode via de Python-pipeline, daarna de judge. */
public final class PostProcess {
	public static void run(JsonObject project, Path dir, float seconds) throws Exception {
		Director.phase = Director.Phase.RENDERING;
		Director.message = "captions, muziek en eindencode";
		Path pipeline = Path.of(Studio.cfg("pipeline", Studio.base().resolve("pipeline").toString()));
		String python = Studio.cfg("python", "python");
		Path take = dir.resolve("take.mp4");
		if (!Files.exists(pipeline.resolve("render.py"))) {
			Director.output = take.toString();
			Director.phase = Director.Phase.DONE;
			Director.message = "ruwe opname klaar (pipeline niet gevonden: " + pipeline + ")";
			return;
		}
		JsonObject sb = new JsonObject();
		sb.addProperty("title", Studio.str(project, "name", "short"));
		sb.addProperty("encoder", Studio.cfg("finalEncoder", "qsv"));
		sb.addProperty("out", "final.mp4");
		JsonArray clips = new JsonArray();
		JsonObject clip = new JsonObject();
		clip.addProperty("src", take.toString());
		clip.addProperty("in", 0);
		clip.addProperty("dur", seconds);
		clips.add(clip);
		sb.add("clips", clips);
		JsonObject music = Studio.obj(project, "music");
		String mf = Studio.str(music, "file", "");
		boolean hasMusic = !mf.isBlank() && Files.exists(Studio.dir("music").resolve(mf));
		if (hasMusic) {
			JsonObject m = new JsonObject();
			m.addProperty("src", Studio.dir("music").resolve(mf).toString());
			m.addProperty("in", Studio.num(music, "in", 0));
			m.addProperty("fade_in", 0.05);
			m.addProperty("fade_out", 1.2);
			sb.add("music", m);
		}
		if (project.has("captions")) sb.add("captions", project.get("captions"));
		if (project.has("style")) sb.add("style", project.get("style"));
		if (Files.exists(dir.resolve("counter.json"))) sb.addProperty("counter", "counter.json");
		if (Files.exists(dir.resolve("sfx.json"))) sb.addProperty("sfx", "sfx.json");
		if (Files.exists(dir.resolve("show.json"))) sb.addProperty("show", "show.json");
		// eerste frames: shader-belichting en chunks komen nog op gang; weg ermee (muziek schuift mee)
		if (seconds > 6) sb.addProperty("lead_trim", 0.4);
		// Hook: de video opent met het eindresultaat (1 maat), met een hooktekst; daarna begint het verhaal.
		JsonObject hook = Studio.obj(project, "hook");
		if (!"off".equals(Studio.str(hook, "teaser", "on")) && Director.teaserLen > 0 && seconds > 6) {
			boolean placed = "placed".equals(Studio.str(Studio.obj(project, "overlay"), "counter", "mined"));
			String def = placed || Director.statMined < 50 ? "{placed} blocks. One by one." : "I mined {mined} blocks for this";
			String text = Studio.str(hook, "text", def);
			if (text.isBlank()) text = def;
			text = text.replace("{mined}", String.format(java.util.Locale.US, "%,d", Director.statMined))
					.replace("{placed}", String.format(java.util.Locale.US, "%,d", Director.statPlaced));
			JsonObject tz = new JsonObject();
			tz.addProperty("dur", Director.teaserLen);
			tz.addProperty("text", text);
			sb.add("teaser", tz);
		}
		Path sbFile = dir.resolve("storyboard.json");
		Files.writeString(sbFile, Studio.GSON.toJson(sb), StandardCharsets.UTF_8);

		int code = exec(dir, "render.log", List.of(python, pipeline.resolve("render.py").toString(), sbFile.toString()));
		Path fin = dir.resolve("final.mp4");
		if (code != 0 || !Files.exists(fin)) {
			Director.output = take.toString();
			Director.phase = Director.Phase.ERROR;
			Director.message = "eindrender mislukt (zie render.log); de ruwe opname take.mp4 is wel bewaard";
			return;
		}
		Director.output = fin.toString();
		// Kopie naar de exportmap van de gebruiker, meteen (de kwaliteitsmeting erna duurt even).
		String exportDir = Studio.cfg("exportDir", "");
		if (!exportDir.isBlank()) {
			try {
				Path ed = Path.of(exportDir);
				Files.createDirectories(ed);
				String title = Studio.str(project, "name", "short").replaceAll("[\\\\/:*?\"<>|]", "").trim();
				if (title.isEmpty()) title = "short";
				Path dst = ed.resolve(title + " " + dir.getFileName() + ".mp4");
				Files.copy(fin, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				Director.export = dst.toString();
			} catch (Exception e) {
				RegisseurClient.LOG.warn("kopie naar exportmap mislukt", e);
			}
		}
		Director.message = "kwaliteit meten";
		java.util.ArrayList<String> j = new java.util.ArrayList<>(List.of(python, pipeline.resolve("judge.py").toString(),
				fin.toString(), "--master", dir.resolve("master.mkv").toString()));
		if (Files.exists(dir.resolve("captions.ass"))) { j.add("--captions"); j.add(dir.resolve("captions.ass").toString()); }
		if (Files.exists(dir.resolve("counter.ass"))) {
			j.add("--counter"); j.add(dir.resolve("counter.ass").toString());
			j.add("--panel"); j.add(dir.resolve("counter_panel.png").toString());
		}
		exec(dir, "judge.log", j);
		String jt = Files.exists(dir.resolve("judge.log")) ? Files.readString(dir.resolve("judge.log")) : "";
		if (Files.exists(dir.resolve("show.json")) && Files.exists(pipeline.resolve("sync_check.py"))) {
			exec(dir, "sync.log", List.of(python, pipeline.resolve("sync_check.py").toString(), dir.resolve("show.json").toString()));
			jt += "\n" + Files.readString(dir.resolve("sync.log"));
		}
		if (Files.exists(dir.resolve("timeline.json")) && Files.exists(pipeline.resolve("audio_check.py"))) {
			exec(dir, "audio.log", List.of(python, pipeline.resolve("audio_check.py").toString(), dir.toString()));
			jt += "\n" + Files.readString(dir.resolve("audio.log"));
		}
		Director.judge = jt;
		Files.deleteIfExists(dir.resolve("master.mkv")); // lossless tussenbestand is groot
		for (int i = 0; i < 9; i++) Files.deleteIfExists(dir.resolve(String.format("seg%02d.mkv", i)));
		Director.phase = Director.Phase.DONE;
		Director.message = hasMusic ? "klaar" : "klaar (zonder muziek: kies een track voor geluid)";
	}

	private static int exec(Path dir, String log, List<String> cmd) throws Exception {
		ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true)
				.redirectOutput(dir.resolve(log).toFile());
		String ff = Studio.cfg("ffmpeg", "");
		if (!ff.isBlank() && Path.of(ff).getParent() != null) // pipeline roept 'ffmpeg' via PATH aan
			pb.environment().merge("Path", Path.of(ff).getParent().toString(), (old, add) -> add + ";" + old);
		pb.environment().put("PYTHONIOENCODING", "utf-8");
		Process p = pb.start();
		if (!p.waitFor(30, TimeUnit.MINUTES)) { p.destroyForcibly(); return -1; }
		return p.exitValue();
	}
}
