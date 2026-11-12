package be.studio.regisseur.nabewerking;

import be.studio.regisseur.RegisseurClient;
import be.studio.regisseur.Studio;
import be.studio.regisseur.draaiboek.ShowPlan;
import be.studio.regisseur.show.Director;
import be.studio.regisseur.show.Tijdschatting;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * NA DE OPNAME: van ruwe opname (take.mp4) naar de afgewerkte Short.
 *
 *   prepare()  direct na het laatste frame (client-thread): gegevens voor de teller (counter.json), de
 *              geluidseffecten (sfx.json) en de hook (aantallen + lengte van de teaser);
 *   run()      daarna op de achtergrond: storyboard.json schrijven, pipeline/render.py draaien (captions, teller,
 *              muziek, geluid, eindencode), een kopie naar de exportmap, en de controles (judge, sync_check,
 *              audio_check). De uitkomst komt in Director.judge en dus in het studio-scherm.
 */
public final class PostProcess {
	/** Voor de hook: aantallen voor de hooktekst en de lengte van de teaser (precies 1 maat, zodat de muziek op de maat blijft). */
	static volatile int statMined, statPlaced;
	static volatile float teaserLen;

	/** Direct na de opname, op de client-thread (textures en geluiden komen uit de geladen resources van de game). */
	public static void prepare(ShowPlan plan, JsonObject project, Path runDir) {
		if (plan == null) return;
		int m = 0;
		for (ShowPlan.Op o : plan.mined) if (!o.state.canBeReplaced()) m++;
		statMined = m;
		statPlaced = plan.buildTargets.size();
		float bar = 1.5f;
		if (plan.sync != null && plan.sync.downbeats.size() > 1) {
			bar = plan.sync.downbeats.get(1) - plan.sync.downbeats.get(0);
			if (bar > 2.4f) bar /= 2; // trage muziek: halve maat, anders duurt de teaser te lang
		}
		teaserLen = bar;
		if (!"off".equals(Studio.str(Studio.obj(project, "sound"), "sfx", "on"))) {
			try {
				Files.writeString(runDir.resolve("sfx.json"), Studio.GSON.toJson(SfxData.build(plan, runDir)), StandardCharsets.UTF_8);
			} catch (Exception e) { RegisseurClient.LOG.warn("geluidseffecten voorbereiden mislukt", e); }
		}
		String cm = Studio.str(Studio.obj(project, "overlay"), "counter", "mined"); // mined (standaard) | placed | off
		List<ShowPlan.Op> csrc = "placed".equals(cm) ? plan.buildTargets : plan.mined;
		if (!"off".equals(cm) && !csrc.isEmpty()) {
			try {
				Files.writeString(runDir.resolve("counter.json"), Studio.GSON.toJson(CounterData.build(csrc, runDir)), StandardCharsets.UTF_8);
			} catch (Exception e) { RegisseurClient.LOG.warn("materiaalteller voorbereiden mislukt", e); }
		}
	}

	/** Op de achtergrond: eindrender en controles. */
	public static void run(JsonObject project, Path dir, float seconds) throws Exception {
		Tijdschatting.naar(Tijdschatting.Stap.AFWERKEN);
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
		// eindbeeld: rode subscribeknop de laatste 3 seconden (standaard aan)
		JsonObject endcard = Studio.obj(project, "endcard");
		if (!"off".equals(Studio.str(endcard, "mode", "subscribe")) && seconds > 8) {
			JsonObject ec = new JsonObject();
			ec.addProperty("text", Studio.str(endcard, "text", "SUBSCRIBE"));
			ec.addProperty("dur", Studio.num(endcard, "dur", 3));
			sb.add("endcard", ec);
		}
		// eerste frames: shader-belichting en chunks komen nog op gang; weg ermee (muziek schuift mee)
		if (seconds > 6) sb.addProperty("lead_trim", 0.4);
		// Hook: de video opent met het eindresultaat (1 maat), met een hooktekst; daarna begint het verhaal.
		JsonObject hook = Studio.obj(project, "hook");
		if (!"off".equals(Studio.str(hook, "teaser", "on")) && teaserLen > 0 && seconds > 6) {
			boolean placed = "placed".equals(Studio.str(Studio.obj(project, "overlay"), "counter", "mined"));
			String def = placed || statMined < 50 ? "{placed} blocks. One by one." : "I mined {mined} blocks for this";
			String text = Studio.str(hook, "text", def);
			if (text.isBlank()) text = def;
			// "I mined 62,169 for this" is onduidelijk: een getal zonder "blocks" ernaast zegt de kijker niets.
			if (!text.toLowerCase().contains("block")) text = text.replace("{mined}", "{mined} blocks").replace("{placed}", "{placed} blocks");
			text = text.replace("{mined}", String.format(java.util.Locale.US, "%,d", statMined))
					.replace("{placed}", String.format(java.util.Locale.US, "%,d", statPlaced));
			JsonObject tz = new JsonObject();
			tz.addProperty("dur", teaserLen);
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
		Tijdschatting.naar(Tijdschatting.Stap.CONTROLEREN);
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
		Tijdschatting.klaar();
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
