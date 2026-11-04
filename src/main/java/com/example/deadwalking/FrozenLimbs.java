package com.example.deadwalking;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ResolvableProfile;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Client-only bodies. They exist only in this player's game, never on the server. */
public final class FrozenLimbs {

    /** How many client ticks a new body shows the red "just got hurt" tint. */
    public static final int RED_TICKS = 5;

    public record Frozen(float position, float speed) {}

    private record Body(LivingEntity entity, Frozen limbs, long bornTick) {}

    private static final Map<Integer, Body> BODIES = new HashMap<>();
    private static long ticks;
    private static int nextId = -1_000_000; // negative ids can never clash with real entities

    private FrozenLimbs() {}

    public static Frozen get(int entityId) {
        Body b = BODIES.get(entityId);
        return b == null ? null : b.limbs();
    }

    public static boolean isCorpse(int entityId) {
        return BODIES.containsKey(entityId);
    }

    public static boolean isRed(int entityId) {
        Body b = BODIES.get(entityId);
        return b != null && ticks - b.bornTick() < RED_TICKS;
    }

    public static void spawn(Minecraft mc, FreezePayload p) {
        ClientLevel level = mc.level;
        if (level == null || !(level.getEntity(p.originalId()) instanceof LivingEntity src)) {
            return;
        }
        LivingEntity body = src instanceof Player player ? playerBody(level, player) : mobBody(level, src);
        if (body == null) {
            return;
        }

        body.setInvisible(false);
        body.setHealth(body.getMaxHealth());
        body.setNoGravity(true);
        body.noPhysics = true;
        body.setSilent(true);

        body.setPos(p.x(), p.y(), p.z());
        body.setYRot(p.yBody());
        body.setXRot(p.xRot());
        body.yRotO = p.yBody();
        body.xRotO = p.xRot();
        body.yBodyRot = p.yBody();
        body.yBodyRotO = p.yBody();
        body.yHeadRot = p.yHead();
        body.yHeadRotO = p.yHead();

        Pose pose = p.pose();
        if (pose == Pose.SLEEPING || pose == Pose.DYING) {
            pose = Pose.STANDING;
        }
        body.setPose(pose);

        for (Map.Entry<EquipmentSlot, ItemStack> e : p.gear().entrySet()) {
            body.setItemSlot(e.getKey(), e.getValue().copy());
        }

        body.setId(nextId--);
        level.addEntity(body);
        BODIES.put(body.getId(), new Body(body, new Frozen(p.walkPos(), p.walkSpeed()), ticks));

        // Hide the real dying body on this client only.
        src.setInvisible(true);
    }

    private static LivingEntity playerBody(ClientLevel level, Player player) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.fromNamespaceAndPath("minecraft", "mannequin"));
        if (!(type.create(level, EntitySpawnReason.TRIGGERED) instanceof Mannequin m)) {
            DeadWalking.LOGGER.warn("Dead Walking: could not create a Mannequin body.");
            return null;
        }
        m.setCustomName(player.getName());
        GameProfile profile = player.getGameProfile();
        invoke(m, "setProfile", ResolvableProfile.class, ResolvableProfile.createResolved(profile));
        invoke(m, "setHideDescription", boolean.class, true);
        invoke(m, "setImmovable", boolean.class, true);
        return m;
    }

    private static LivingEntity mobBody(ClientLevel level, LivingEntity src) {
        if (!(src.getType().create(level, EntitySpawnReason.TRIGGERED) instanceof LivingEntity body)) {
            return null;
        }
        // Copy everything visible: colour, variant, size, baby, name, fire, ...
        var values = src.getEntityData().getNonDefaultValues();
        if (values != null) {
            body.getEntityData().assignValues(values);
        }
        if (body instanceof Mob mob) {
            mob.setNoAi(true);
        }
        return body;
    }

    public static void tick() {
        ticks++;
        Iterator<Body> it = BODIES.values().iterator();
        while (it.hasNext()) {
            Body b = it.next();
            LivingEntity e = b.entity();
            if (e.isRemoved()) {
                it.remove();
            } else if (ticks - b.bornTick() >= DeadWalking.CORPSE_TICKS) {
                e.handleEntityEvent((byte) 60); // vanilla poof
                e.discard();
                it.remove();
            }
        }
    }

    public static void clear() {
        BODIES.clear();
    }

    private static void invoke(Object target, String name, Class<?> argType, Object arg) {
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
