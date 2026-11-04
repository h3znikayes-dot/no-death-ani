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
    public static final int RED_TICKS = 15;

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

    private
