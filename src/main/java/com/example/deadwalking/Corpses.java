package com.example.deadwalking;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Server side only remembers how something looked just before dying, and, if it really died
 * (a totem of undying does not count), tells nearby clients to show a local body.
 */
final class Corpses {

    private static final class Snapshot {
        int tick;
        double x, y, z;
        float xRot, yBody, yHead;
        Pose pose = Pose.STANDING;
        final Map<EquipmentSlot, ItemStack> gear = new EnumMap<>(EquipmentSlot.class);
        float walkPos, walkSpeed;
    }

    /** Per-player copy of the client's walk animation, so we know the exact limb pose at death. */
    private static final class Walk {
        double lastX, lastZ;
        float position, speed;
    }

    private static final Map<UUID, Walk> WALK = new HashMap<>();
    private static final Map<UUID, Snapshot> SNAPSHOTS = new HashMap<>();

    private Corpses() {}

    static void snapshot(LivingEntity e) {
        if (!(e.level() instanceof ServerLevel level)) {
            return;
        }
        Snapshot s = new Snapshot();
        s.tick = level.getServer().getTickCount();
        s.x = e.getX();
        s.y = e.getY();
        s.z = e.getZ();
        s.xRot = e.getXRot();
        s.yBody = e.yBodyRot;
        s.yHead = e.yHeadRot;
        s.pose = e.getPose();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack stack = e.getItemBySlot(slot);
            if (!stack.isEmpty()) {
                s.gear.put(slot, stack.copy());
            }
        }
        if (e instanceof ServerPlayer player) {
            Walk w = WALK.get(player.getUUID());
            s.walkPos = w == null ? 0.0F : w.position;
            s.walkSpeed = w == null ? 0.0F : w.speed;
        } else {
            s.walkPos = e.walkAnimation.position();
            s.walkSpeed = e.walkAnimation.speed();
        }
        SNAPSHOTS.put(e.getUUID(), s);
    }

    static void finish(LivingEntity e) {
        Snapshot s = SNAPSHOTS.remove(e.getUUID());
        if (s == null || !(e.level() instanceof ServerLevel level)) {
            return;
        }
        if (e instanceof ServerPlayer player) {
            if (player.isSpectator()) {
                return;
            }
        } else if (e instanceof Mob) {
            String id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
            if (id.equals("ender_dragon") || id.equals("wither")) {
                return; // bosses have their own special death sequences
            }
        } else {
            return;
        }

        FreezePayload payload = new FreezePayload(e.getId(), s.x, s.y, s.z, s.yBody, s.yHead, s.xRot,
                s.pose, s.walkPos, s.walkSpeed, s.gear);
        for (ServerPlayer other : level.players()) {
            if (other.distanceToSqr(e) <= 256.0 * 256.0
                    && ServerPlayNetworking.canSend(other, FreezePayload.TYPE)) {
                ServerPlayNetworking.send(other, payload);
            }
        }
    }

    static void tick(MinecraftServer server) {
        // mirror vanilla's walk animation for every player (same maths as LivingEntity)
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Walk w = WALK.computeIfAbsent(player.getUUID(), id -> {
                Walk fresh = new Walk();
                fresh.lastX = player.getX();
                fresh.lastZ = player.getZ();
                return fresh;
            });
            double dx = player.getX() - w.lastX;
            double dz = player.getZ() - w.lastZ;
            w.lastX = player.getX();
            w.lastZ = player.getZ();
            float target = Math.min((float) Math.sqrt(dx * dx + dz * dz) * 4.0F, 1.0F);
            w.speed += (target - w.speed) * 0.4F;
            w.position += w.speed;
        }

        // forget snapshots of things that survived (e.g. a totem popped)
        int now = server.getTickCount();
        SNAPSHOTS.values().removeIf(s -> now - s.tick > 5);
    }

    static void forget(UUID id) {
        WALK.remove(id);
        SNAPSHOTS.remove(id);
    }

    static void clear() {
        WALK.clear();
        SNAPSHOTS.clear();
    }
}
