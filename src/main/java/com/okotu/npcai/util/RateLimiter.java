package com.okotu.npcai.util;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cooldown semplice per-player. Non persistito: si resetta ad ogni riavvio, va bene
 * perche' serve solo ad evitare spam nella stessa sessione di gioco.
 */
public class RateLimiter {

    private final ConcurrentHashMap<UUID, Long> lastRequestAt = new ConcurrentHashMap<>();
    private final long cooldownMs;

    public RateLimiter(long cooldownMs) {
        this.cooldownMs = cooldownMs;
    }

    /**
     * @return true se il player puo' inviare una nuova richiesta ora (e ne registra il timestamp),
     *         false se e' ancora in cooldown.
     */
    public boolean tryAcquire(UUID playerUuid) {
        long now = System.currentTimeMillis();
        long[] result = new long[1];
        lastRequestAt.compute(playerUuid, (uuid, last) -> {
            if (last == null || now - last >= cooldownMs) {
                result[0] = now;
                return now;
            }
            result[0] = last;
            return last;
        });
        return result[0] == now;
    }

    public long remainingCooldownMs(UUID playerUuid) {
        Long last = lastRequestAt.get(playerUuid);
        if (last == null) return 0;
        long elapsed = System.currentTimeMillis() - last;
        return Math.max(0, cooldownMs - elapsed);
    }
}
