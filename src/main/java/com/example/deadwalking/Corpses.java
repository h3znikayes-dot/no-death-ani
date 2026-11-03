package com.example.deadwalking;

import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ResolvableProfile;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Spawns a short-lived "body" (a vanilla Mannequin) when a player dies.
 *
 * Why a separate entity: vanilla deletes the dead player's own body after 20 ticks, or instantly
 * when they press Respawn. A Mannequin is a normal player-shaped entity that vanilla clients already
 * know how to render, so this part works even for players without the mod installed.
 */
final class Corpses {

    private record Corpse(Mannequin entity, int expiresAtTick) {}

    /** Per-player copy of the client's walk animation, so we know the exact limb pose at death. */
    private static final class Walk {
        double lastX, lastZ;
        float position, speed;
    }

    private static final List<Corpse> ACTIVE = new ArrayList<>();
    private static final Map<UUID, Walk> WALK = new HashMap<>();

    private Corpses() {}

    // ---------------------------------------------------------------- spawning

    static void spawn(ServerPlayer player) {
        if (player.isSpectator() || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        Mannequin corpse = EntityType.MANNEQUIN.create(level, EntitySpawnReason.TRIGGERED);
        if (corpse == null) {
            return;
        }

        // --- position, facing, head
        corpse.setPos(player.getX(), player.getY(), player.getZ());
        float body = player.yBodyRot;
        corpse.setYRot(body);
        corpse.setXRot(player.getXRot());
        corpse.yBodyRot = body;
        corpse.yBodyRotO = body;
        corpse.yHeadRot = player.yHeadRot;
        corpse.yHeadRotO = player.yHeadRot;

        // --- pose (standing, sneaking, swimming, ...)
        Pose pose = player.getPose();
        if (pose == Pose.SLEEPING || pose == Pose.DYING) {
            pose = Pose.STANDING; // sleeping needs a bed position; dying is vanilla's own animation
        }
        corpse.setPose(pose);

        // --- look: skin, name, armour and held items
        corpse.setSilent(true);
        corpse.setCustomName(player.getName());
        copyProfile(corpse, player.getGameProfile());
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack stack = player.getItemBySlot(slot);
            if (!stack.isEmpty()) {
                corpse.setItemSlot(slot, stack.copy());
            }
        }

        // --- make it a corpse: 0 health makes every client play the vanilla "fall over" animation
        corpse.setHealth(0.0F);
        corpse.setInvulnerable(true);

        level.addFreshEntity(corpse);

        // Hide the real dying body so there aren't two bodies on top of each other.
        player.setInvisible(true);

        MinecraftServer server = level.getServer();
        ACTIVE.add(new Corpse(corpse, server.getTickCount() + DeadWalking.CORPSE_TICKS));

        // --- freeze the limbs at the exact walk-cycle position the player had
        Walk walk = WALK.get(player.getUUID());
        float pos = walk == null ? 0.0F : walk.position;
        float speed = walk == null ? 0.0F : walk.speed;
        FreezePayload payload = new FreezePayload(corpse.getId(), pos, speed);
        for (ServerPlayer other : level.players()) {
            if (other.distanceToSqr(player) <= 256.0 * 256.0
                    && ServerPlayNetworking.canSend(other, FreezePayload.TYPE)) {
                ServerPlayNetworking.send(other, payload);
            }
        }
    }

    // ---------------------------------------------------------------- ticking

    static void tick(MinecraftServer server) {
        // 1) mirror vanilla's walk animation for every player (same maths as LivingEntity)
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

        // 2) keep bodies alive for CORPSE_TICKS, then remove them with vanilla's "poof"
        int now = server.getTickCount();
        Iterator<Corpse> it = ACTIVE.iterator();
        while (it.hasNext()) {
            Corpse c = it.next();
            Mannequin e = c.entity();
            if (e.isRemoved()) {
                it.remove();
            } else if (now >= c.expiresAtTick()) {
                e.level().broadcastEntityEvent(e, (byte) 60);
                e.discard();
                it.remove();
            } else {
                e.deathTime = 0; // stops vanilla's own 20-tick death cleanup from removing it early
            }
        }
    }

    static void forget(UUID id) {
        WALK.remove(id);
    }

    static void clear() {
        ACTIVE.clear();
        WALK.clear();
    }

    // ---------------------------------------------------------------- helpers

    /** Mannequin's profile/description setters are not guaranteed to be public, so go through reflection. */
    private static void copyProfile(Mannequin corpse, GameProfile profile) {
        invoke(corpse, "setProfile", ResolvableProfile.class, ResolvableProfile.createResolved(profile));
        invoke(corpse, "setHideDescription", boolean.class, true);
    }

    private static void invoke(Object target, String name, Class<?> argType, Object arg) {
        try {
            Method m = target.getClass().getMethod(name, argType);
            m.setAccessible(true);
            m.invoke(target, arg);
            return;
        } catch (ReflectiveOperationException ignored) {
            // fall through and look for a non-public declaration
        }
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name, argType);
                m.setAccessible(true);
                m.invoke(target, arg);
                return;
            } catch (ReflectiveOperationException ignored) {
                // keep walking up the class hierarchy
            }
        }
        DeadWalking.LOGGER.warn("Mannequin.{} not found; the body may use the default skin or label.", name);
    }
}
