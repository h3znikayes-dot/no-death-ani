package com.example.deadwalking;

import java.util.HashMap;
import java.util.Map;

/** Client-side store of "entity id -> frozen walk animation". Only touched from the client thread. */
public final class FrozenLimbs {

    public record Frozen(float position, float speed, long expiresAt) {}

    private static final Map<Integer, Frozen> FROZEN = new HashMap<>();
    private static long clientTicks;

    private FrozenLimbs() {}

    public static void put(int entityId, float position, float speed) {
        // 400 ticks is far longer than a body lives; this only cleans up stale entries
        FROZEN.put(entityId, new Frozen(position, speed, clientTicks + 400));
    }

    public static Frozen get(int entityId) {
        return FROZEN.get(entityId);
    }

    public static void tick() {
        clientTicks++;
        FROZEN.values().removeIf(f -> f.expiresAt() < clientTicks);
    }

    public static void clear() {
        FROZEN.clear();
    }
}
