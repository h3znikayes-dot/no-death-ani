package com.example.deadwalking;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
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
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Client-only. Watches every player and mob, remembers how each looked on its last living tick,
 * and when one dies builds a local body from that memory. Nothing is sent to or needed from the server.
 */
public final class FrozenLimbs {

    /** How many client ticks a new body shows the red "just got hurt" tint. */
    public static final int RED_TICKS = 5;

    public record Frozen(float position, float speed) {}

    private record Body(LivingEntity entity, Frozen limbs, long bornTick) {}

    /** How something looked on its last living tick. */
    private static final class Look {
        double x, y, z;
        float xRot, yBody, yHead;
        Pose pose = Pose.STANDING;
        float walkPos, walkSpeed;
        final Map<EquipmentSlot, ItemStack> gear = new EnumMap<>(EquipmentSlot.class);
    }

    private static final Map<Integer, Body> BODIES = new HashMap<>();
    private static final Map<Integer, Look> LOOKS = new HashMap<>();
    private static final Set<Integer> HANDLED = new HashSet<>();
    private static long ticks;
    private static int nextId = -1_000_000; // negative ids can never clash with real entities

    private FrozenLimbs() {}

    // ---------------------------------------------------------------- used by the mixins

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

    // ---------------------------------------------------------------- every client tick

    public static void tick(Minecraft mc) {
        ticks++;
        scan(mc);

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

    private static void scan(Minecraft mc) {
        ClientLevel level = mc.level;
        if (level == null) {
            LOOKS.clear();
            HANDLED.clear();
            return;
        }
        Set<Integer> seen = new HashSet<>();
        List<LivingEntity> justDied = new ArrayList<>();

        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living) || BODIES.containsKey(living.getId())) {
                continue;
            }
            if (!(living instanceof Player || living instanceof Mob) || isBoss(living)) {
                continue;
            }
            int id = living.getId();
            seen.add(id);
            if (living.isDeadOrDying()) {
                if (HANDLED.add(id)) {
                    justDied.add(living);
                }
            } else {
                HANDLED.remove(id);
                if (living.isSpectator() || living.isInvisible()) {
                    LOOKS.remove(id);
                } else {
                    remember(living);
                }
            }
        }
        LOOKS.keySet().retainAll(seen);
        HANDLED.retainAll(seen);

        // spawn after the loop so we never change the entity list while reading it
        for (LivingEntity src : justDied) {
            Look look = LOOKS.get(src.getId());
            if (look != null) {
                spawn(level, src, look);
            }
        }
    }

    private static boolean isBoss(LivingEntity e) {
        String path = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
        return path.equals("ender_dragon") || path.equals("wither"); // special death sequences
    }

    private static void remember(LivingEntity e) {
        Look l = LOOKS.computeIfAbsent(e.getId(), id -> new Look());
        l.x = e.getX();
        l.y = e.getY();
        l.z = e.getZ();
        l.xRot = e.getXRot();
        l.yBody = e.yBodyRot;
        l.yHead = e.yHeadRot;
        l.pose = e.getPose();
        l.walkPos = e.walkAnimation.position();
        l.walkSpeed = e.walkAnimation.speed();
        l.gear.clear();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack stack = e.getItemBySlot(slot);
            if (!stack.isEmpty()) {
                l.gear.put(slot, stack);
            }
        }
    }

    // ---------------------------------------------------------------- building the body

    private static void spawn(ClientLevel level, LivingEntity src, Look look) {
        LivingEntity body = src instanceof Player player ? playerBody(level, player) : mobBody(level, src);
        if (body == null) {
            return;
        }

        body.setInvisible(false);
        body.setHealth(body.getMaxHealth());
        body.setNoGravity(true);
        body.noPhysics = true;
        body.setSilent(true);

        body.setPos(look.x, look.y, look.z);
        body.setYRot(look.yBody);
        body.setXRot(look.xRot);
        body.yRotO = look.yBody;
        body.xRotO = look.xRot;
        body.yBodyRot = look.yBody;
        body.yBodyRotO = look.yBody;
        body.yHeadRot = look.yHead;
        body.yHeadRotO = look.yHead;

        Pose pose = look.pose;
        if (pose == Pose.SLEEPING || pose == Pose.DYING) {
            pose = Pose.STANDING;
        }
        body.setPose(pose);

        for (Map.Entry<EquipmentSlot, ItemStack> e : look.gear.entrySet()) {
            body.setItemSlot(e.getKey(), e.getValue().copy());
        }

        body.setId(nextId--);
        level.addEntity(body);
        BODIES.put(body.getId(), new Body(body, new Frozen(look.walkPos, look.walkSpeed), ticks));

        // Hide the real dying body (on this client only).
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

    public static void clear() {
        BODIES.clear();
        LOOKS.clear();
        HANDLED.clear();
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
