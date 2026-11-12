package be.studio.regisseur.mixin;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Gewijzigde blokken meteen naar de client sturen in plaats van te wachten op de volgende server-tick.
 * Tijdens de opname loopt de server soms maar 2 ticks per seconde; zonder dit komen blokken te laat in beeld.
 */
@Mixin(ServerChunkCache.class)
public interface ChunkCacheInvoker {
	@Invoker("broadcastChangedChunks") void regisseur$broadcastChangedChunks(ProfilerFiller profiler);
}
