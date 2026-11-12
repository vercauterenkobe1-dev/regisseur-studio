package be.studio.regisseur.mixin;

import be.studio.regisseur.RegisseurClient;
import be.studio.regisseur.show.Director;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Bij het sluiten van het venster start Minecraft een "sluit-bewaker" die de game na een tijd hard afbreekt.
 * Tijdens een opname negeren we het sluiten (zie WindowCloseMixin), dus mag die bewaker ook niet starten.
 */
@Mixin(targets = "net.minecraft.client.Minecraft$1")
public abstract class CloseWatchdogMixin {
	@Inject(method = "run", at = @At("HEAD"), cancellable = true)
	private void regisseur$noWatchdog(CallbackInfo ci) {
		if (Director.busy() || RegisseurClient.PORT != 47800) ci.cancel(); // testgame (poort 47801): nooit via het venster
	}
}
