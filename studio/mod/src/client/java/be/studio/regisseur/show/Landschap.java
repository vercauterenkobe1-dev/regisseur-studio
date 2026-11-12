package be.studio.regisseur.show;

import be.studio.regisseur.draaiboek.ShowPlan;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerLevel;

import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

/**
 * Het landschap rond de bouwplaats VOORAF aanmaken, zo ver als de camera kan kijken.
 *
 * Waarom: tijdens de opname draait de server traag (2 ticks per seconde als de laptop traag rendert). Moet hij dan
 * nog nieuw landschap aanmaken, dan komt dat niet mee en zie je in de verte gaten (lichtblauwe vlakken met rechte
 * randen). Eén keer vooraf aanmaken lost dat op; daarna leest de server het gewoon van de schijf.
 *
 * In kleine porties (32 chunks per server-taak), zodat de server tussendoor kan ademen en oude chunks kan
 * opruimen: anders loopt het geheugen vol.
 */
final class Landschap {
	private Landschap() { }

	static void maakAan(IntegratedServer server, ServerLevel level, ShowPlan p, int maxRd, IntConsumer voortgang) throws Exception {
		// verste camerapunt ~ 1,5 x basisafstand; daar voorbij nog de zichtafstand
		int r = (int) Math.ceil((p.camBase * 1.5 + Math.max(8, maxRd) * 16) / 16.0);
		r = Math.min(r, 64);
		int c0x = p.cx >> 4, c0z = p.cz >> 4;
		List<int[]> todo = new ArrayList<>();
		for (int dx = -r; dx <= r; dx++)
			for (int dz = -r; dz <= r; dz++)
				if (dx * dx + dz * dz <= r * r) todo.add(new int[]{c0x + dx, c0z + dz});
		todo.sort((a, b) -> Integer.compare(sq(a, c0x, c0z), sq(b, c0x, c0z))); // van binnen naar buiten
		// Parallel: per portie vraagt de server-thread alle chunks tegelijk aan (getChunkFuture), de wereldgeneratie
		// werkt ze dan op meerdere kernen af; wij wachten buiten de server-thread tot de hele portie klaar is.
		final int BATCH = 96;
		for (int i = 0; i < todo.size(); i += BATCH) {
			final List<int[]> part = todo.subList(i, Math.min(todo.size(), i + BATCH));
			List<CompletableFuture<?>> futures = server.submit(() -> {
				List<CompletableFuture<?>> fs = new ArrayList<>();
				for (int[] c : part) fs.add(level.getChunkSource().getChunkFuture(c[0], c[1], ChunkStatus.FULL, true));
				return fs;
			}).join();
			CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(5, TimeUnit.MINUTES);
			voortgang.accept((int) Math.round(100.0 * Math.min(todo.size(), i + BATCH) / todo.size()));
		}
	}

	private static int sq(int[] a, int x, int z) { int dx = a[0] - x, dz = a[1] - z; return dx * dx + dz * dz; }
}
