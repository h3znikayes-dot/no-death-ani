package com.example.deadwalking;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server -> client: "corpse entity X should keep its legs/arms at this walk-animation state".
 * Without this the limbs would relax back to a standing pose a few ticks after death,
 * because the client derives limb swing from movement.
 */
public record FreezePayload(int entityId, float walkPosition, float walkSpeed) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<FreezePayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(DeadWalking.MOD_ID, "freeze"));

    public static final StreamCodec<RegistryFriendlyByteBuf, FreezePayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, FreezePayload::entityId,
            ByteBufCodecs.FLOAT, FreezePayload::walkPosition,
            ByteBufCodecs.FLOAT, FreezePayload::walkSpeed,
            FreezePayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
