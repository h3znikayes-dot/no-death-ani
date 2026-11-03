package com.example.deadwalking;

import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ResolvableProfile;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Leaves a short-lived "body" behind when something really dies.
 *
 * Two steps, so that a totem of undying does NOT count as a death:
 *   1) snapshot()  at the start of a death check: remember how the entity looks right now
 *   2) finish()    only if the entity really died: spawn the body from that snapshot
 *
 * Players: a vanilla Mannequin wearing their skin and gear.
 * Mobs: a no-AI copy of the same mob type.
 */
final class Corpses {

    private record Corpse(LivingEntity entity, int expiresAtTick) {}

    /** What the entity looked like just before it died. */
    private static final class Snapshot {
        int tick;
        double x, y, z;
        float xRot, yBody, yHead;
        Pose pose;
        final Map<EquipmentSlot, ItemStack> gear = new EnumMap<>(EquipmentSlot.class);
        List<SynchedEntityData.DataValue<?>> data;
        float walkPos, walkSpeed;
    }

    /** Per-player copy of the client's walk animation, so we know the exact limb pose at death. */
    private static final class Walk {
        double lastX, lastZ;
        float position, speed;
    }

    private static final List<Corpse> ACTIVE = new ArrayList<>();
    private static final Map<UUID, Walk> WALK = new HashMap<>();
    private static final Map<UUID, Snapshot> SNAPSHOTS = new HashMap<>();

    private Corpses() {}

    // ---------------------------------------------------------------- step 1: snapshot

    static void snapshot(LivingEntity e) {
        if (isCorpse(e) || !(e.level() instanceof ServerLevel level)) {
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
            s.data = e.getEntityData().getNonDefaultValues();
            s.walkPos = e.walkAnimation.position();
            s.walkSpeed = e.walkAnimation.speed();
        }
        SNAPSHOTS.put(e.getUUID(), s);
    }

    // ---------------------------------------------------------------- step 2: spawn

    static void finish(LivingEntity e) {
        Snapshot s = SNAPSHOTS.remove(e.getUUID());
        if (s == null || isCorpse(e) || !(e.level() instanceof ServerLevel level)) {
            return;
        }
        if (e instanceof ServerPlayer player) {
            if (!player.isSpectator()) {
                spawnPlayerBody(level, player, s);
            }
        } else if (e instanceof Mob mob) {
            spawnMobBody(level, mob, s);
        }
    }

    private static void spawnPlayerBody(ServerLevel level, ServerPlayer player, Snapshot s) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.fromNamespaceAndPath("minecraft", "mannequin"));
        Object made = type.create(level, EntitySpawnReason.TRIGGERED);
        DeadWalking.LOGGER.info("Dead Walking: player died, made {} (type {})", made, BuiltInRegistries.ENTITY_TYPE.getKey(type));
        if (!(made instanceof Mannequin corpse)) {
            DeadWalking.LOGGER.warn("Dead Walking: that is not a Mannequin, so no body was spawned.");
            return;
        }
        applySnapshot(corpse, s);
        corpse.setCustomName(player.getName());
        copyProfile(corpse, player.getGameProfile());
        register(level, corpse, player, s);
    }

    private static void spawnMobBody(ServerLevel level, Mob original, Snapshot s) {
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(original.getType()).getPath();
        if (id.equals("ender_dragon") || id.equals("wither")) {
            return; // bosses have their own special death sequences
        }
        Entity made = original.getType().create(level, EntitySpawnReason.TRIGGERED);
        if (!(made instanceof Mob clone)) {
            return;
        }
        // Copy everything the client can see: colour, variant, size, baby, name, fire, ...
        if (s.data != null) {
            clone.getEntityData().assignValues(s.data);
        }
        clone.setHealth(clone.getMaxHealth()); // the copied data may say "0 health"; undo that
        clone.setNoAi(true);
        applySnapshot(clone, s);
        register(level, clone, original, s);
    }

    private static void applySnapshot(LivingEntity corpse, Snapshot s) {
        corpse.setPos(s.x, s.y, s.z);
        corpse.setYRot(s.yBody);
        corpse.setXRot(s.xRot);
        corpse.yBodyRot = s.yBody;
        corpse.yBodyRotO = s.yBody;
        corpse.yHeadRot = s.yHead;
        corpse.yHeadRotO = s.yHead;

        Pose pose = s.pose;
        if (pose == null || pose == Pose.SLEEPING || pose == Pose.DYING) {
            pose = Pose.STANDING; // sleeping needs a bed position; dying is vanilla's own animation
        }
        corpse.setPose(pose);

        corpse.setSilent(true);
        for (Map.Entry<EquipmentSlot, ItemStack> entry : s.gear.entrySet()) {
            corpse.setItemSlot(entry.getKey(), entry.getValue().copy());
        }
    }

    private static void register(ServerLevel level, LivingEntity corpse, LivingEntity original, Snapshot s) {
        level.addFreshEntity(corpse);

        // Hide the real dying body so there aren't two bodies on top of each other.
        original.setInvisible(true);

        MinecraftServer server = level.getServer();
        ACTIVE.add(new Corpse(corpse, server.getTickCount() + DeadWalking.CORPSE_TICKS));

        // Tell nearby clients to freeze the limbs and flash red.
        FreezePayload payload = new FreezePayload(corpse.getId(), s.walkPos, s.walkSpeed);
        for (ServerPlayer other : level.players()) {
            if (other.distanceToSqr(original) <= 256.0 * 256.0
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

        int now = server.getTickCount();

        // 2) forget snapshots of things that survived (e.g. a totem popped)
        SNAPSHOTS.values().removeIf(s -> now - s.tick > 5);

        // 3) keep bodies for CORPSE_TICKS, then remove them with vanilla's "poof"
        Iterator<Corpse> it = ACTIVE.iterator();
        while (it.hasNext()) {
            Corpse c = it.next();
            LivingEntity e = c.entity();
            if (e.isRemoved()) {
                it.remove();
            } else if (now >= c.expiresAtTick()) {
                e.level().broadcastEntityEvent(e, (byte) 60);
                e.discard();
                it.remove();
            }
        }
    }

    static boolean isCorpse(Entity entity) {
        for (Corpse c : ACTIVE) {
            if (c.entity() == entity) {
                return true;
            }
        }
        return false;
    }

    static void forget(UUID id) {
        WALK.remove(id);
        SNAPSHOTS.remove(id);
    }

    static void clear() {
        ACTIVE.clear();
        WALK.clear();
        SNAPSHOTS.clear();
    }

    // ---------------------------------------------------------------- helpers

    /** Mannequin's profile/description setters are not guaranteed to be public, so go through reflection. */
    private static void copyProfile(Mannequin corpse, GameProfile profile) {
        invoke(corpse, "setProfile", ResolvableProfile.class, ResolvableProfile.createResolved(profile));
        invoke(corpse, "setHideDescription", boolean.class, true);
        invoke(corpse, "setImmovable", boolean.class, true);
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
