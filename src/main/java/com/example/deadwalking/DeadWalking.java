package com.example.deadwalking;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DeadWalking implements ModInitializer {

    public static final String MOD_ID = "deadwalking";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** How long a body stays, in server ticks (20 ticks = 1 second). */
    public static final int CORPSE_TICKS = 40;

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.clientboundPlay().register(FreezePayload.TYPE, FreezePayload.CODEC);

        // Start of a death check (a totem of undying can still cancel it): just remember how it looked.
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            Corpses.snapshot(entity);
            return true;
        });

        // Only runs when the entity really died (not when a totem saved it): spawn the body now.
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> Corpses.finish(entity));

        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> !Corpses.isCorpse(entity));

        ServerTickEvents.END_SERVER_TICK.register(Corpses::tick);

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                Corpses.forget(handler.getPlayer().getUUID()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> Corpses.clear());
    }
}
