package com.example.deadwalking.mixin;

import com.example.deadwalking.FrozenLimbs;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Bodies can't be clicked on, aimed at, or pushed. */
@Mixin(LivingEntity.class)
public abstract class LivingEntityHitboxMixin {

    @Inject(method = "isPickable", at = @At("HEAD"), cancellable = true, require = 0)
    private void deadwalking$notPickable(CallbackInfoReturnable<Boolean> cir) {
        if (FrozenLimbs.isCorpse(((LivingEntity) (Object) this).getId())) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "isPushable", at = @At("HEAD"), cancellable = true, require = 0)
    private void deadwalking$notPushable(CallbackInfoReturnable<Boolean> cir) {
        if (FrozenLimbs.isCorpse(((LivingEntity) (Object) this).getId())) {
            cir.setReturnValue(false);
        }
    }
}
