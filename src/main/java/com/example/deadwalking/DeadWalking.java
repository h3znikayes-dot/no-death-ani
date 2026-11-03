package com.example.deadwalking;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DeadWalking implements ModInitializer {

    public static final String MOD_ID = "deadwalking";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** How long a body stays, in server ticks (20 ticks = 1 second). */
    public static final int CORPSE_TICKS = 50;

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.playS2C().register(FreezePayload.TYPE, FreezePayload.CODEC);

        // Runs at the very start of death, before the inventory is dropped, so we can copy the gear.
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            if (entity instanceof ServerPlayer player) {
                Corpses.spawn(player);
            }
            return true; // never cancel the death itself
        });

        ServerTickEvents.END_SERVER_TICK.register(Corpses::tick);

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                Corpses.forget(handler.getPlayer().getUUID()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> Corpses.clear());
    }
}
