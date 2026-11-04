package com.example.deadwalking;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;

import java.util.EnumMap;
import java.util.Map;

/** Server -> client: "entity X just died; build a local body that looks like this." */
public record FreezePayload(int originalId, double x, double y, double z,
                            float yBody, float yHead, float xRot, Pose pose,
                            float walkPos, float walkSpeed,
                            Map<EquipmentSlot, ItemStack> gear) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<FreezePayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(DeadWalking.MOD_ID, "corpse"));

    public static final StreamCodec<RegistryFriendlyByteBuf, FreezePayload> CODEC =
            StreamCodec.ofMember(FreezePayload::write, FreezePayload::read);

    void write(RegistryFriendlyByteBuf buf) {
        buf.writeVarInt(originalId);
        buf.writeDouble(x);
        buf.writeDouble(y);
        buf.writeDouble(z);
        buf.writeFloat(yBody);
        buf.writeFloat(yHead);
        buf.writeFloat(xRot);
        buf.writeEnum(pose);
        buf.writeFloat(walkPos);
        buf.writeFloat(walkSpeed);
        buf.writeVarInt(gear.size());
        for (Map.Entry<EquipmentSlot, ItemStack> e : gear.entrySet()) {
            buf.writeEnum(e.getKey());
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, e.getValue());
        }
    }

    static FreezePayload read(RegistryFriendlyByteBuf buf) {
        int id = buf.readVarInt();
        double x = buf.readDouble();
        double y = buf.readDouble();
        double z = buf.readDouble();
        float yBody = buf.readFloat();
        float yHead = buf.readFloat();
        float xRot = buf.readFloat();
        Pose pose = buf.readEnum(Pose.class);
        float walkPos = buf.readFloat();
        float walkSpeed = buf.readFloat();
        int n = buf.readVarInt();
        Map<EquipmentSlot, ItemStack> gear = new EnumMap<>(EquipmentSlot.class);
        for (int i = 0; i < n; i++) {
            EquipmentSlot slot = buf.readEnum(EquipmentSlot.class);
            gear.put(slot, ItemStack.OPTIONAL_STREAM_CODEC.decode(buf));
        }
        return new FreezePayload(id, x, y, z, yBody, yHead, xRot, pose, walkPos, walkSpeed, gear);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
