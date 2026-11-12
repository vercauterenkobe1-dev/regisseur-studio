package be.studio.regisseur;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import be.studio.regisseur.show.Director;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Regisseur: lokale studio-server (alleen 127.0.0.1).
 *  - http://127.0.0.1:47800/        het studio-scherm (bouwwerk, timing, muziek, captions, opname)
 *  - /api/...                       hetzelfde draaiboek als JSON, zodat ook Claude kan regisseren
 *  - /ping /state /cmd              directe besturing van de game
 *
 * Zwakke hardware: JDK-HttpServer zonder extra libraries, kleine thread-pool, geen werk per frame in rust.
 */
public final class RegisseurClient implements ClientModInitializer {
	public static final Logger LOG = LoggerFactory.getLogger("regisseur");
	public static final int PORT = Integer.getInteger("regisseur.port", 47800);
	private static final long MAX_UPLOAD = 300L << 20;

	@Override
	public void onInitializeClient() {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), PORT), 16);
			server.setExecutor(Executors.newFixedThreadPool(3, r -> {
				Thread t = new Thread(r, "regisseur-http");
				t.setDaemon(true);
				return t;
			}));
			route(server, "/ping", ex -> json(ex, 200, onClient(RegisseurClient::ping)));
			route(server, "/state", ex -> json(ex, 200, onClient(RegisseurClient::state)));
			route(server, "/cmd", ex -> {
				if (!post(ex)) return;
				String cmd = body(ex).trim();
				final String c = cmd.startsWith("/") ? cmd.substring(1) : cmd;
				json(ex, 200, onClient(() -> runCommand(c)));
			});
			route(server, "/api/status", ex -> json(ex, 200, Director.status().toString()));
			route(server, "/api/files", ex -> {
				JsonObject o = new JsonObject();
				o.add("schematics", Studio.list("schematics"));
				o.add("music", Studio.list("music"));
				o.addProperty("folder", Studio.base().toString());
				json(ex, 200, o.toString());
			});
			route(server, "/api/project", ex -> {
				if ("GET".equals(ex.getRequestMethod())) { json(ex, 200, Studio.loadProject().toString()); return; }
				JsonObject p = JsonParser.parseString(body(ex)).getAsJsonObject();
				Studio.saveProject(p);
				json(ex, 200, "{\"ok\":true}");
			});
			route(server, "/api/upload", ex -> { // ?kind=schematics|music&name=bestand.ext  (body = bestand)
				if (!post(ex)) return;
				Map<String, String> q = query(ex);
				String kind = q.getOrDefault("kind", ""), name = Path.of(q.getOrDefault("name", "")).getFileName().toString();
				if (!(kind.equals("schematics") || kind.equals("music")) || name.isBlank() || name.startsWith(".")) {
					json(ex, 400, err("ongeldige upload")); return;
				}
				Path dst = Studio.dir(kind).resolve(name);
				try (InputStream in = ex.getRequestBody(); OutputStream out = Files.newOutputStream(dst)) {
					byte[] buf = new byte[1 << 16];
					long total = 0;
					for (int n; (n = in.read(buf)) > 0; ) {
						if ((total += n) > MAX_UPLOAD) throw new IOException("bestand te groot");
						out.write(buf, 0, n);
					}
				}
				json(ex, 200, "{\"ok\":true,\"name\":" + q(name) + "}");
			});
			route(server, "/api/show/start", ex -> {
				if (!post(ex)) return;
				String b = body(ex).trim();
				final JsonObject proj = b.isEmpty() ? Studio.loadProject() : JsonParser.parseString(b).getAsJsonObject();
				if (!b.isEmpty()) Studio.saveProject(proj);
				json(ex, 200, onClient(() -> {
					String e = Director.start(proj);
					return e == null ? "{\"ok\":true}" : err(e);
				}));
			});
			route(server, "/api/show/stop", ex -> {
				if (!post(ex)) return;
				json(ex, 200, onClient(() -> { Director.fail("gestopt door gebruiker"); return "{\"ok\":true}"; }));
			});
			route(server, "/media", RegisseurClient::media);
			route(server, "/", ex -> {
				if (!"/".equals(ex.getRequestURI().getPath())) { json(ex, 404, err("niet gevonden")); return; }
				try (InputStream in = RegisseurClient.class.getResourceAsStream("/assets/regisseur/ui/index.html")) {
					send(ex, 200, "text/html; charset=utf-8", in == null ? "UI ontbreekt".getBytes() : in.readAllBytes());
				}
			});
			server.start();
			LOG.info("Regisseur luistert op http://127.0.0.1:{}", PORT);
		} catch (IOException e) {
			LOG.error("Regisseur kon poort {} niet openen", PORT, e);
		}
	}

	// ---------- game-endpoints (client-thread) ----------

	private static String ping() {
		Minecraft mc = Minecraft.getInstance();
		String ver = FabricLoader.getInstance().getModContainer("minecraft")
				.map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?");
		String mod = FabricLoader.getInstance().getModContainer("regisseur")
				.map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?");
		return "{\"ok\":true,\"mod\":\"regisseur\",\"version\":\"" + mod + "\",\"minecraft\":\"" + ver + "\",\"fps\":"
				+ mc.getFps() + ",\"inWorld\":" + (mc.player != null) + ",\"singleplayer\":" + (mc.getSingleplayerServer() != null) + "}";
	}

	private static String state() {
		LocalPlayer p = Minecraft.getInstance().player;
		if (p == null) return err("niet in een wereld");
		return String.format(Locale.ROOT,
				"{\"ok\":true,\"x\":%.3f,\"y\":%.3f,\"z\":%.3f,\"yaw\":%.2f,\"pitch\":%.2f}",
				p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot());
	}

	private static String runCommand(String cmd) {
		LocalPlayer p = Minecraft.getInstance().player;
		if (p == null) return err("niet in een wereld");
		if (cmd.isEmpty()) return err("leeg commando");
		p.connection.sendCommand(cmd);
		return "{\"ok\":true,\"sent\":" + q(cmd) + "}";
	}

	// ---------- video's afspelen in het studio-scherm (met Range, zodat spoelen werkt) ----------

	private static void media(HttpExchange ex) throws IOException {
		String f = query(ex).getOrDefault("f", "");
		Path root = Studio.dir("renders").toAbsolutePath().normalize();
		Path p = Path.of(f).toAbsolutePath().normalize();
		if (!p.startsWith(root) || !Files.isRegularFile(p)) { json(ex, 404, err("niet gevonden")); return; }
		long size = Files.size(p), from = 0, to = size - 1;
		String range = ex.getRequestHeaders().getFirst("Range");
		boolean partial = false;
		if (range != null && range.startsWith("bytes=")) {
			String[] r = range.substring(6).split("-", 2);
			try {
				if (!r[0].isEmpty()) from = Long.parseLong(r[0]);
				if (r.length > 1 && !r[1].isEmpty()) to = Math.min(size - 1, Long.parseLong(r[1]));
				partial = from <= to && from < size;
			} catch (NumberFormatException ignored) { }
			if (!partial) { from = 0; to = size - 1; }
		}
		long len = to - from + 1;
		ex.getResponseHeaders().set("Content-Type", p.toString().endsWith(".mp4") ? "video/mp4" : "application/octet-stream");
		ex.getResponseHeaders().set("Accept-Ranges", "bytes");
		if (partial) ex.getResponseHeaders().set("Content-Range", "bytes " + from + "-" + to + "/" + size);
		ex.sendResponseHeaders(partial ? 206 : 200, len);
		try (RandomAccessFile raf = new RandomAccessFile(p.toFile(), "r"); OutputStream os = ex.getResponseBody()) {
			raf.seek(from);
			byte[] buf = new byte[1 << 16];
			while (len > 0) {
				int n = raf.read(buf, 0, (int) Math.min(buf.length, len));
				if (n < 0) break;
				os.write(buf, 0, n);
				len -= n;
			}
		} catch (IOException ignored) { // browser sloot de verbinding
		}
	}

	// ---------- hulpfuncties ----------

	/** Alleen verzoeken van het eigen studio-scherm of van scripts: een willekeurige website mag de game niet aansturen. */
	private static void route(HttpServer s, String path, HttpHandler h) {
		s.createContext(path, ex -> {
			try {
				String origin = ex.getRequestHeaders().getFirst("Origin");
				if (origin != null && !origin.equals("http://127.0.0.1:" + PORT) && !origin.equals("http://localhost:" + PORT)) {
					json(ex, 403, err("vreemde herkomst geweigerd"));
					return;
				}
				h.handle(ex);
			} catch (Throwable t) {
				LOG.warn("verzoek {} mislukt", path, t);
				try { json(ex, 500, err(String.valueOf(t.getMessage()))); } catch (IOException ignored) { }
			} finally {
				ex.close();
			}
		});
	}

	private static boolean post(HttpExchange ex) throws IOException {
		if ("POST".equals(ex.getRequestMethod())) return true;
		json(ex, 405, err("gebruik POST"));
		return false;
	}

	private static String body(HttpExchange ex) throws IOException {
		return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
	}

	private static Map<String, String> query(HttpExchange ex) {
		Map<String, String> m = new HashMap<>();
		String raw = ex.getRequestURI().getRawQuery();
		if (raw != null) for (String kv : raw.split("&")) {
			int i = kv.indexOf('=');
			if (i > 0) m.put(URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8),
					URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
		}
		return m;
	}

	private static String onClient(Supplier<String> work) {
		CompletableFuture<String> f = new CompletableFuture<>();
		Minecraft.getInstance().execute(() -> {
			try { f.complete(work.get()); } catch (Throwable t) { f.complete(err(String.valueOf(t))); }
		});
		try {
			return f.get(8, TimeUnit.SECONDS);
		} catch (Exception e) {
			return err("timeout op client-thread");
		}
	}

	private static void json(HttpExchange ex, int code, String body) throws IOException {
		send(ex, code, "application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
	}

	private static void send(HttpExchange ex, int code, String type, byte[] b) throws IOException {
		ex.getResponseHeaders().set("Content-Type", type);
		ex.getResponseHeaders().set("Cache-Control", "no-store");
		ex.sendResponseHeaders(code, b.length);
		try (OutputStream os = ex.getResponseBody()) { os.write(b); }
	}

	static String err(String msg) { return "{\"ok\":false,\"error\":" + q(msg) + "}"; }

	static String q(String s) { return Studio.GSON.toJson(s == null ? "" : s); }
}
