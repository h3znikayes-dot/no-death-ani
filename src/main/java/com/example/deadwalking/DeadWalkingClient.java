package com.example.deadwalking;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

public class DeadWalkingClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(FrozenLimbs::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> FrozenLimbs.clear());
    }
}
