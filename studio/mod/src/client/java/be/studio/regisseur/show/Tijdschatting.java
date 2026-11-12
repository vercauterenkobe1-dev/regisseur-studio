package be.studio.regisseur.show;

import be.studio.regisseur.RegisseurClient;
import be.studio.regisseur.Studio;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * HOE LANG NOG? Schat de resterende tijd tot de video klaar is, voor het studio-scherm.
 *
 *   opnemen    : resterende frames x (tot nu toe gemeten seconden per frame)
 *   afwerken   : videolengte x (seconden render per videoseconde)       - geleerd van vorige opnames
 *   controleren: videolengte x (seconden controle per videoseconde)     - geleerd van vorige opnames
 *
 * Na elke opname worden de werkelijke tijden onthouden in studio/tijden.json (gemiddeld met de vorige),
 * zodat de schatting op jouw laptop steeds juister wordt.
 */
public final class Tijdschatting {
	private Tijdschatting() { }

	public enum Stap { OPNEMEN, AFWERKEN, CONTROLEREN }

	/** Startwaarden (seconden werk per seconde video) tot er echte metingen zijn. */
	private static double renderPerSec = 8, controlePerSec = 12;
	private static boolean geladen;
	private static volatile long startMs, stapStartMs;
	private static volatile Stap stap;
	private static volatile float videoSec;

	private static Path bestand() { return Studio.base().resolve("tijden.json"); }

	private static synchronized void laad() {
		if (geladen) return;
		geladen = true;
		try {
			if (Files.exists(bestand())) {
				JsonObject o = JsonParser.parseString(Files.readString(bestand(), StandardCharsets.UTF_8)).getAsJsonObject();
				renderPerSec = o.get("renderPerSec").getAsDouble();
				controlePerSec = o.get("controlePerSec").getAsDouble();
			}
		} catch (Exception e) { RegisseurClient.LOG.warn("tijden.json lezen mislukt", e); }
	}

	private static synchronized void bewaar() {
		try {
			JsonObject o = new JsonObject();
			o.addProperty("renderPerSec", renderPerSec);
			o.addProperty("controlePerSec", controlePerSec);
			Files.writeString(bestand(), Studio.GSON.toJson(o), StandardCharsets.UTF_8);
		} catch (Exception e) { RegisseurClient.LOG.warn("tijden.json schrijven mislukt", e); }
	}

	/** De opname begint (eerste frame komt eraan). */
	public static void begin(float lengteVideo) {
		laad();
		videoSec = lengteVideo;
		startMs = stapStartMs = System.currentTimeMillis();
		stap = Stap.OPNEMEN;
	}

	/** Volgende stap; de duur van de vorige stap wordt geleerd. */
	public static void naar(Stap nieuw) {
		long nu = System.currentTimeMillis();
		double sec = (nu - stapStartMs) / 1000.0;
		if (stap != null && videoSec > 1) {
			if (stap == Stap.AFWERKEN) renderPerSec = 0.5 * renderPerSec + 0.5 * sec / videoSec;
			if (stap == Stap.CONTROLEREN) controlePerSec = 0.5 * controlePerSec + 0.5 * sec / videoSec;
		}
		stap = nieuw;
		stapStartMs = nu;
	}

	/** Alles klaar: de laatste stap leren en bewaren. */
	public static void klaar() {
		naar(null);
		bewaar();
	}

	/** Seconden sinds de start van de opname. */
	public static long verstreken() { return stap == null || startMs == 0 ? 0 : (System.currentTimeMillis() - startMs) / 1000; }

	/** Geschatte resterende seconden, of -1 als er (nog) geen schatting is. */
	public static long resterend(int frame, int totalFrames) {
		Stap s = stap;
		if (s == null) return -1;
		double inStap = (System.currentTimeMillis() - stapStartMs) / 1000.0;
		double render = renderPerSec * videoSec, controle = controlePerSec * videoSec;
		double rest;
		switch (s) {
			case OPNEMEN -> {
				if (frame < 30) return -1; // eerst wat frames meten
				double perFrame = inStap / frame;
				rest = perFrame * (totalFrames - frame) + render + controle;
			}
			case AFWERKEN -> rest = Math.max(render * 0.1, render - inStap) + controle;
			default -> rest = Math.max(controle * 0.1, controle - inStap);
		}
		return Math.round(rest);
	}
}
