package be.studio.regisseur.mixin;

import be.studio.regisseur.Director;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Twee haakjes in de frame-lus: camera/draaiboek vooraan, opname vlak voor de HUD. */
@Mixin(GameRenderer.class)
public class GameRendererMixin {
	@Inject(method = "update(Lnet/minecraft/client/DeltaTracker;)V", at = @At("HEAD"))
	private void regisseur$frameStart(DeltaTracker delta, CallbackInfo ci) {
		Director.frameStart();
	}

	@Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/render/GuiRenderer;render()V"))
	private void regisseur$capture(DeltaTracker delta, boolean advance, CallbackInfo ci) {
		Director.capture();
	}
}
