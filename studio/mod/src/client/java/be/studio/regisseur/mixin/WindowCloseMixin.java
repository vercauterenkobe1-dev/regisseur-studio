package be.studio.regisseur.mixin;

import be.studio.regisseur.RegisseurClient;
import be.studio.regisseur.show.Director;
import com.mojang.blaze3d.platform.Window;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Venster per ongeluk gesloten tijdens een opname? Dan zou de video verloren gaan. Zolang er een opname of
 * nabewerking bezig is, negeren we het sluiten (en zeggen dat in het studio-scherm). Daarna sluit het gewoon.
 * Echt stoppen kan altijd met "Stop opname" in de studio.
 */
@Mixin(Window.class)
public abstract class WindowCloseMixin {
	@Inject(method = "shouldClose", at = @At("HEAD"), cancellable = true)
	private void regisseur$keepOpen(CallbackInfoReturnable<Boolean> cir) {
		if (!Director.busy() && !testgame()) return;
		long h = ((Window) (Object) this).handle();
		if (GLFW.glfwWindowShouldClose(h)) {
			GLFW.glfwSetWindowShouldClose(h, false);
			Director.closeRefused();
		}
		cir.setReturnValue(false);
	}

	/** De testgame van de ontwikkelomgeving (poort 47801) sluit alleen via het testscript, nooit via het venster. */
	private static boolean testgame() { return RegisseurClient.PORT != 47800; }
}
