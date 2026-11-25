package io.github.dupeguard.util;

import org.bukkit.plugin.Plugin;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Console logging with a hard rate limit, so a griefing client cannot turn the
 * log into a second denial-of-service vector.
 */
public final class BlockLog {

    private final Plugin plugin;
    private final AtomicLong suppressed = new AtomicLong();
    private volatile long windowStart = System.currentTimeMillis();
    private volatile int inWindow = 0;

    private static final int MAX_PER_WINDOW = 20;
    private static final long WINDOW_MS = 30_000L;

    public BlockLog(Plugin plugin) {
        this.plugin = plugin;
    }

    public void warn(String message) {
        if (!plugin.getConfig().getBoolean("bundle.log-blocks", true)) return;
        long now = System.currentTimeMillis();
        synchronized (this) {
            if (now - windowStart >= WINDOW_MS) {
                if (suppressed.get() > 0) {
                    plugin.getLogger().warning("... " + suppressed.getAndSet(0)
                            + " further message(s) suppressed in the previous window.");
                }
                windowStart = now;
                inWindow = 0;
            }
            if (inWindow >= MAX_PER_WINDOW) {
                suppressed.incrementAndGet();
                return;
            }
            inWindow++;
        }
        plugin.getLogger().warning(message);
    }
}
