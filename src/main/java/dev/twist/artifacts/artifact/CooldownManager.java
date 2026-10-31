package dev.twist.artifacts.artifact;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Tiny per-player, per-artifact cooldown store. Expired entries are purged lazily. */
public final class CooldownManager {

    private final Map<UUID, Map<String, Long>> expiries = new HashMap<>();

    /** @return remaining cooldown in milliseconds, or 0 if ready. */
    public long remainingMillis(UUID player, String artifactId) {
        Map<String, Long> map = expiries.get(player);
        if (map == null) return 0L;
        Long expiry = map.get(artifactId);
        if (expiry == null) return 0L;
        long left = expiry - System.currentTimeMillis();
        if (left <= 0) {
            map.remove(artifactId);
            if (map.isEmpty()) expiries.remove(player);
            return 0L;
        }
        return left;
    }

    public void start(UUID player, String artifactId, long seconds) {
        if (seconds <= 0) return;
        expiries.computeIfAbsent(player, k -> new HashMap<>())
                .put(artifactId, System.currentTimeMillis() + seconds * 1000L);
    }

    /** Called on quit: drops the player's entries if they've all expired (prevents unbounded growth). */
    public void purgeIfExpired(UUID player) {
        Map<String, Long> map = expiries.get(player);
        if (map == null) return;
        long now = System.currentTimeMillis();
        map.values().removeIf(t -> t <= now);
        if (map.isEmpty()) expiries.remove(player);
    }

    /** Admin helper: wipe all cooldowns of a player. */
    public void clear(UUID player) { expiries.remove(player); }
}
