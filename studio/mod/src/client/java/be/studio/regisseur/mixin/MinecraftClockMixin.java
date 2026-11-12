package be.studio.regisseur.mixin;

import be.studio.regisseur.show.Director;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Vaste klok tijdens de opname: de speltijd van de client loopt exact 1/60 s per vastgelegd frame.
 * Daardoor bewegen deeltjes, displays en animaties in videotijd, hoe traag de laptop ook rendert.
 */
@Mixin(Minecraft.class)
public class MinecraftClockMixin {
	@ModifyArg(method = "runTick(Z)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/DeltaTracker$Timer;advanceGameTime(J)I"))
	private long regisseur$clock(long realMs) {
		return Director.clock(realMs);
	}
}
