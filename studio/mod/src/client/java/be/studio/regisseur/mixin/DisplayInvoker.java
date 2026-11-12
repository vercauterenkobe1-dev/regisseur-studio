package be.studio.regisseur.mixin;

import com.mojang.math.Transformation;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** De setters van display-entiteiten zijn private in 26.2; via deze invoker kunnen we ze aansturen. */
@Mixin(Display.class)
public interface DisplayInvoker {
	@Invoker("setTransformation") void regisseur$setTransformation(Transformation t);
	@Invoker("setTransformationInterpolationDuration") void regisseur$setDuration(int ticks);
	@Invoker("setTransformationInterpolationDelay") void regisseur$setDelay(int ticks);
	@Invoker("setViewRange") void regisseur$setViewRange(float range);
	@Invoker("setBrightnessOverride") void regisseur$setBrightness(net.minecraft.util.Brightness b);
}
