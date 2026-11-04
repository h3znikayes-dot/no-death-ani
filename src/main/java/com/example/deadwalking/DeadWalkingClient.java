package com.example.deadwalking;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public class DeadWalkingClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(FreezePayload.TYPE, (payload, context) ->
                FrozenLimbs.spawn(context.client(), payload));

        ClientTickEvents.END_CLIENT_TICK.register(client -> FrozenLimbs.tick());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> FrozenLimbs.clear());
    }
}
