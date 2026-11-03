package com.example.deadwalking.mixin;

import com.example.deadwalking.FrozenLimbs;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {

    /** After vanilla fills in the render state, pin the limb animation of bodies to the frozen values. */
    @Inject(
            method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V",
            at = @At("TAIL"))
    private void deadwalking$freezeLimbs(LivingEntity entity, LivingEntityRenderState state, float partialTick, CallbackInfo ci) {
        FrozenLimbs.Frozen frozen = FrozenLimbs.get(entity.getId());
        if (frozen != null) {
            state.walkAnimationPos = frozen.position();
            state.walkAnimationSpeed = frozen.speed();
        }
    }
}
