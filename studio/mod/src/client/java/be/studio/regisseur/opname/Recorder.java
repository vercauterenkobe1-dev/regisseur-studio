package be.studio.regisseur.opname;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import net.minecraft.client.Minecraft;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Frame-locked opname in Shorts-formaat.
 *
 *  - De game rendert tijdelijk rechtstreeks op 1080x1920 (zelfde truc als vanilla's panorama-screenshot):
 *    echte pixels, geen uitsnede of opschaling van het venster.
 *  - Elk frame wordt asynchroon van de GPU gelezen en als ruwe RGBA naar FFmpeg gestuurd.
 *  - Videotijd loopt alleen door per vastgelegd frame, dus de video is altijd exact 60 fps,
 *    ook als de laptop traag rendert. Bij een volle buffer wacht de show gewoon (geen gedropte frames).
 *
 * Twee dingen die gemeten veel uitmaakten (zie docs/STUDIE.md):
 *  - NIET Screenshot.takeScreenshot gebruiken: dat kopieert pixel voor pixel (ca. 250 ms per frame).
 *    Wij lezen de GPU-buffer in één keer uit; het beeld staat dan ondersteboven en FFmpeg draait het om (vflip).
 *  - NIET via stdin pijpen: Windows-pijpen zijn smal. Een lokale TCP-verbinding met grote buffer is veel sneller.
 */
public final class Recorder {
	public static final int W = 1080, H = 1920, FPS = 60, BYTES = W * H * 4;
	private static final int MAX_IN_FLIGHT = 8;

	private Process ffmpeg;
	private Thread writer;
	private final Map<Integer, ByteBuffer> ready = new HashMap<>();
	private final ConcurrentLinkedQueue<ByteBuffer> pool = new ConcurrentLinkedQueue<>();
	private final AtomicInteger inFlight = new AtomicInteger();
	/** Hoeveel frames er al bij de GPU aangevraagd zijn (index van het volgende + 1). */
	private final AtomicInteger requested = new AtomicInteger();
	private volatile int endAt = -1;
	private volatile String error;
	private int oldW, oldH;
	private boolean sized;
	public int resizes;
	public volatile long copyNs, writeNs;

	public String error() { return error; }
	public boolean busy() { return inFlight.get() >= MAX_IN_FLIGHT; }

	public void start(String ffmpegExe, Path out, Path log, String encoder) throws Exception {
		int port;
		try (ServerSocket probe = new ServerSocket(0)) { port = probe.getLocalPort(); }
		// Gemeten op echte gamebeelden (i7-1255U): h264_qsv 36 fps, x264 ultrafast 28, x264 superfast 16.
		boolean qsv = "qsv".equals(encoder);
		List<String> enc = qsv
				? List.of("-c:v", "h264_qsv", "-preset", "fast", "-global_quality", "14")
				: List.of("-c:v", "libx264", "-preset", "ultrafast", "-crf", "14");
		java.util.ArrayList<String> cmd = new java.util.ArrayList<>(List.of(ffmpegExe, "-hide_banner", "-y",
				"-f", "rawvideo", "-pix_fmt", "rgba", "-s", W + "x" + H, "-r", String.valueOf(FPS),
				"-i", "tcp://127.0.0.1:" + port + "?listen=1&recv_buffer_size=16777216", "-an",
				"-vf", "vflip,scale=in_range=pc:out_range=tv:out_color_matrix=bt709,format=" + (qsv ? "nv12" : "yuv420p")));
		cmd.addAll(enc);
		cmd.addAll(List.of("-g", "30", "-color_primaries", "bt709", "-color_trc", "bt709", "-colorspace", "bt709",
				"-color_range", "tv", "-movflags", "+faststart", out.toString()));
		ProcessBuilder pb = new ProcessBuilder(cmd);
		pb.redirectErrorStream(true);
		pb.redirectOutput(log.toFile());
		ffmpeg = pb.start();
		SocketChannel ch = null;
		for (int i = 0; i < 60 && ch == null; i++) { // wachten tot FFmpeg luistert
			try {
				ch = SocketChannel.open();
				ch.setOption(StandardSocketOptions.SO_SNDBUF, 16 << 20);
				ch.connect(new InetSocketAddress("127.0.0.1", port));
			} catch (Exception e) {
				if (ch != null) ch.close();
				ch = null;
				if (!ffmpeg.isAlive()) throw new IllegalStateException("ffmpeg startte niet (zie take.log)");
				Thread.sleep(100);
			}
		}
		if (ch == null) throw new IllegalStateException("geen verbinding met ffmpeg");
		final SocketChannel channel = ch;
		writer = new Thread(() -> writeLoop(channel), "regisseur-writer");
		writer.setDaemon(true);
		writer.start();
	}

	/** Zet (en houdt) de interne renderresolutie op 1080x1920. Client-thread, elk frame. */
	public void ensureSize() {
		Minecraft mc = Minecraft.getInstance();
		Window w = mc.getWindow();
		RenderTarget rt = mc.gameRenderer.mainRenderTarget();
		if (!sized) { oldW = w.getWidth(); oldH = w.getHeight(); sized = true; }
		if (w.getWidth() != W || w.getHeight() != H || rt.width != W || rt.height != H) {
			w.setWidth(W);
			w.setHeight(H);
			rt.resize(W, H);
			resizes++;
		}
	}

	public void restoreSize() {
		if (!sized) return;
		Minecraft mc = Minecraft.getInstance();
		mc.getWindow().setWidth(oldW);
		mc.getWindow().setHeight(oldH);
		mc.gameRenderer.mainRenderTarget().resize(oldW, oldH);
		sized = false;
	}

	/** Vraagt frame idx aan bij de GPU. Client/render-thread, vlak voor de HUD getekend wordt. */
	public void capture(int idx) {
		GpuTexture tex = Minecraft.getInstance().gameRenderer.mainRenderTarget().getColorTexture();
		if (tex == null || tex.getFormat().blockSize() != 4) { error = "onverwacht beeldformaat van de game"; return; }
		inFlight.incrementAndGet();
		requested.set(Math.max(requested.get(), idx + 1));
		GpuBuffer buf = RenderSystem.getDevice().createBuffer(() -> "Regisseur frame",
				// CLIENT_STORAGE: buffer in gewoon geheugen. Zonder die hint kostte uitlezen 147 ms per frame (gemeten).
				GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_HINT_CLIENT_STORAGE, (long) BYTES);
		RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(tex, buf, 0L, () -> {
			long c0 = System.nanoTime();
			try (GpuBufferSlice.MappedView view = buf.map(true, false)) {
				ByteBuffer src = view.data();
				if (src.remaining() < BYTES) { error = "frame te klein: " + src.remaining(); return; }
				ByteBuffer b = pool.poll();
				if (b == null) b = ByteBuffer.allocateDirect(BYTES);
				b.clear();
				ByteBuffer s = src.duplicate();
				s.limit(s.position() + BYTES);
				b.put(s);
				b.flip();
				synchronized (ready) {
					ready.put(idx, b);
					ready.notifyAll();
				}
			} catch (Throwable t) {
				error = String.valueOf(t);
			} finally {
				buf.close();
				copyNs += System.nanoTime() - c0;
			}
		}, 0);
	}

	private void writeLoop(SocketChannel ch) {
		int next = 0;
		try (ch) {
			while (error == null) {
				ByteBuffer b;
				synchronized (ready) {
					long waited = 0;
					while ((b = ready.remove(next)) == null) {
						if (endAt >= 0 && next >= endAt) return;
						if (error != null) return;
						ready.wait(250);
						// Alleen een fout als het frame echt aangevraagd is en de GPU het niet teruggeeft. Is het nog niet
						// aangevraagd (de regisseur wacht tot de wereld klaar is), dan gewoon blijven wachten.
						if (next >= requested.get()) { waited = 0; continue; }
						if ((waited += 250) > 60000) { error = "frame " + next + " kwam nooit terug van de GPU"; return; }
					}
				}
				long w0 = System.nanoTime();
				while (b.hasRemaining()) ch.write(b);
				writeNs += System.nanoTime() - w0;
				pool.offer(b);
				inFlight.decrementAndGet();
				next++;
			}
		} catch (Throwable t) {
			if (error == null) error = "verbinding met ffmpeg: " + t;
		}
	}

	/** Sluit af na 'frames' frames; blokkeert tot FFmpeg klaar is. NIET op de client-thread aanroepen. */
	public boolean finish(int frames) throws InterruptedException {
		endAt = frames;
		synchronized (ready) { ready.notifyAll(); }
		writer.join(120000);
		boolean ok = ffmpeg.waitFor(180, java.util.concurrent.TimeUnit.SECONDS) && ffmpeg.exitValue() == 0;
		if (!ok && error == null) error = "ffmpeg sloot niet goed af (zie take.log)";
		if (!ok) ffmpeg.destroyForcibly();
		return ok && error == null;
	}

	public void abort() {
		error = error == null ? "afgebroken" : error;
		if (ffmpeg != null) ffmpeg.destroyForcibly();
	}
}
