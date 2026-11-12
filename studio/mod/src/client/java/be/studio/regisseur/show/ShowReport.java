package be.studio.regisseur.show;

import be.studio.regisseur.Studio;
import be.studio.regisseur.draaiboek.ShowPlan;
import be.studio.regisseur.opname.Recorder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;

/**
 * show.json: het draaiboek en de metingen van één opname, voor de controles in de pipeline
 * (sync_check.py = ritme, audio_check.py en render.py gebruiken de secties en de muziek).
 *
 * Inhoud: secties, shots, muziek (tellen en maatstarts), landingstijden van elk bouwblok, camerahoek per frame,
 * terraform-tijden per deelfase, en na de opname de metingen (camera in blok, vertraging per blok).
 */
final class ShowReport {
	private ShowReport() { }

	static void write(Path runDir, ShowPlan p, boolean done, List<Integer> landLag) throws Exception {
		JsonObject o = new JsonObject();
		o.addProperty("fps", Recorder.FPS);
		o.addProperty("total", p.total());
		JsonObject sec = new JsonObject();
		sec.addProperty("intro", p.tIntro);
		sec.addProperty("terraEnd", p.tIntro + p.tTerra);
		sec.addProperty("buildEnd", p.tIntro + p.tTerra + p.tBuild);
		sec.addProperty("end", p.total());
		o.add("sections", sec);
		o.addProperty("finale", p.buildOps > 0 ? p.finaleT : -1);

		JsonArray sh = new JsonArray();
		for (Camera.Shot s : Camera.shots) {
			JsonObject x = new JsonObject();
			x.addProperty("type", s.type()); x.addProperty("start", s.start()); x.addProperty("end", s.end());
			sh.add(x);
		}
		o.add("shots", sh);
		o.addProperty("smoothCuts", Camera.smoothCuts);

		if (p.sync != null) {
			JsonObject m = new JsonObject();
			m.addProperty("bpm", p.sync.bpm);
			m.addProperty("period", p.sync.period);
			m.addProperty("in", p.sync.in);
			m.addProperty("confidence", p.sync.confidence);
			JsonArray b = new JsonArray(), d = new JsonArray();
			for (float x : p.sync.beats) if (x <= p.total() + 1) b.add(x);
			for (float x : p.sync.downbeats) if (x <= p.total() + 1) d.add(x);
			m.add("beats", b);
			m.add("downbeats", d);
			o.add("music", m);
		}

		JsonArray land = new JsonArray(), landY = new JsonArray();
		for (ShowPlan.Op op : p.buildTargets) { land.add(op.t); landY.add(op.y); }
		o.add("landings", land);
		o.add("landingsY", landY);

		JsonArray ca = new JsonArray();
		for (float v : Camera.camAngle.values()) ca.add(Math.round(v * 100) / 100f);
		o.add("camAngle", ca);
		o.addProperty("camAngleStartFrame", Camera.camAngle.isEmpty() ? 0 : Camera.camAngle.firstKey());

		if (p.square) o.add("terraTimes", terraTimes(p));

		if (done) {
			JsonObject meas = new JsonObject();
			meas.addProperty("camInBlock", Camera.camInBlock);
			meas.addProperty("camLifted", Camera.camLifted);
			JsonArray lag = new JsonArray();
			for (int v : landLag) lag.add(v);
			meas.add("landLagFrames", lag);
			o.add("measured", meas);
		}
		Files.writeString(runDir.resolve("show.json"), Studio.GSON.toJson(o), StandardCharsets.UTF_8);
	}

	/** Terraform-tijden per deelfase (sleuf, uithollen, omhoog), uitgedund tot ~4000 per fase. Voor regel D1. */
	private static JsonObject terraTimes(ShowPlan p) {
		JsonArray ring = new JsonArray(), hollow = new JsonArray(), rise = new JsonArray();
		int inner = p.sqHalf - p.sqRing; // sleuf = buiten het eiland (zelfde indeling als VierkantTerraform)
		Predicate<ShowPlan.Op> isRing = op -> Math.max(Math.abs(op.x - p.cx), Math.abs(op.z - p.cz)) > inner;
		float terraEnd = p.tIntro + p.tTerra;
		int nr = 0, nh = 0, ni = 0;
		for (ShowPlan.Op op : p.ops) {
			if (op.t >= terraEnd) continue;
			if (op.state.isAir()) { if (isRing.test(op)) nr++; else nh++; }
			else if (op.t >= p.sqRiseStart) ni++;
		}
		int sr = Math.max(1, nr / 4000), shh = Math.max(1, nh / 4000), si = Math.max(1, ni / 4000), kr = 0, kh = 0, ki = 0;
		for (ShowPlan.Op op : p.ops) {
			if (op.t >= terraEnd) continue;
			float v = Math.round(op.t * 1000) / 1000f;
			if (op.state.isAir()) {
				if (isRing.test(op)) { if (kr++ % sr == 0) ring.add(v); }
				else if (kh++ % shh == 0) hollow.add(v);
			} else if (op.t >= p.sqRiseStart && ki++ % si == 0) rise.add(v);
		}
		JsonObject tt = new JsonObject();
		tt.add("ring", ring); tt.add("hollow", hollow); tt.add("rise", rise);
		return tt;
	}
}
