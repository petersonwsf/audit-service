package com.petersonwsf.audit.security;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;

/** Contador de janela fixa de 1 minuto, em memória (uma instância do serviço). */
@Component
public class RateLimiter {

    private static final long WINDOW_MILLIS = 60_000;
    private static final int CLEANUP_THRESHOLD = 10_000;

    private record Window(long startedAt, int count) {
    }

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;

    public RateLimiter(Clock clock) {
        this.clock = clock;
    }

    /** Conta mais um uso de {@code key} na janela atual e devolve o total. */
    public int hit(String key) {
        long now = clock.millis();
        if (windows.size() > CLEANUP_THRESHOLD) {
            windows.entrySet().removeIf(e -> now - e.getValue().startedAt() >= WINDOW_MILLIS);
        }
        return windows.merge(key, new Window(now, 1),
                (old, fresh) -> now - old.startedAt() >= WINDOW_MILLIS ? fresh : new Window(old.startedAt(), old.count() + 1))
                .count();
    }

    /** Usos de {@code key} na janela atual, sem contar mais um. */
    public int current(String key) {
        Window window = windows.get(key);
        return window == null || clock.millis() - window.startedAt() >= WINDOW_MILLIS ? 0 : window.count();
    }

    /** Segundos até a janela de {@code key} reiniciar. */
    public long retryAfterSeconds(String key) {
        Window window = windows.get(key);
        if (window == null) {
            return 1;
        }
        long remaining = WINDOW_MILLIS - (clock.millis() - window.startedAt());
        return Math.max(1, (remaining + 999) / 1000);
    }
}
