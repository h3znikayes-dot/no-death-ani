package com.example.deadwalking;

import java.util.HashMap;
import java.util.Map;

/** Client-side store of "entity id -> frozen walk animation". Only touched from the client thread. */
public final class FrozenLimbs {

    /** How many client ticks a new body shows the red "just got hurt" tint. */
    public static final int RED_TICKS = 5;

    public record Frozen(float position, float speed, long expiresAt) {}

    private static final Map<Integer, Frozen> FROZEN = new HashMap<>();
    private static final Map<Integer, Long> FIRST_SEEN = new HashMap<>();
    private static long clientTicks;

    private FrozenLimbs() {}

    public static void put(int entityId, float position, float speed) {
        // 400 ticks is far longer than a body lives; this only cleans up stale entries
        FROZEN.put(entityId, new Frozen(position, speed, clientTicks + 400));
        FIRST_SEEN.remove(entityId);
    }

    public static Frozen get(int entityId) {
        return FROZEN.get(entityId);
    }

    /** True for the first RED_TICKS ticks after the body is first drawn. */
    public static boolean isRed(int entityId) {
        if (!FROZEN.containsKey(entityId)) {
            return false;
        }
        long first = FIRST_SEEN.computeIfAbsent(entityId, id -> clientTicks);
        return clientTicks - first < RED_TICKS;
    }

    public static void tick() {
        clientTicks++;
        FROZEN.values().removeIf(f -> f.expiresAt() < clientTicks);
        FIRST_SEEN.keySet().removeIf(id -> !FROZEN.containsKey(id));
    }

    public static void clear() {
        FROZEN.clear();
        FIRST_SEEN.clear();
    }
}
