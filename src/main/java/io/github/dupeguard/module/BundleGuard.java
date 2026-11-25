package io.github.dupeguard.module;

import io.github.dupeguard.util.BlockLog;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bundle duplication through the "entity gone, connection alive" window
 * (reported against 1.21.2+ servers).
 *
 * <h2>The bug</h2>
 * The client floods the Netty pipeline with oversized/excessive packets so the
 * connection stays technically open but stalls. A queued {@code use item} packet
 * then arrives together with a kick (or any other disconnect). The server saves
 * and removes the player from the world, but the still-ticking {@code Connection}
 * processes the queued {@code use item}, which releases the bundle's contents as
 * dropped items — from a player entity that no longer exists. The inventory was
 * already saved with the bundle full, so the contents exist twice.
 *
 * <h2>What this module does</h2>
 * <ul>
 *   <li>clears the active item on quit (the upstream fix) so the queued use has
 *       nothing to release;</li>
 *   <li>does the same on world change, which opens the same window;</li>
 *   <li>rate-limits bundle use and cancels impossible packet bursts, so the
 *       stall-and-replay pattern is refused before the payload lands.</li>
 * </ul>
 * The module is purely event based and does not require ProtocolLib.
 */
public final class BundleGuard implements Listener {

    private final Plugin plugin;
    private final BlockLog log;
    private final AtomicLong blocked = new AtomicLong();
    private final Map<UUID, State> states = new ConcurrentHashMap<>();

    private volatile boolean active;

    public BundleGuard(Plugin plugin, BlockLog log) {
        this.plugin = plugin;
        this.log = log;
    }

    public boolean enable() {
        if (!plugin.getConfig().getBoolean("bundle.enabled", true)) {
            return false;
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        this.active = true;
        return true;
    }

    public void disable() {
        this.active = false;
        states.clear();
        org.bukkit.event.HandlerList.unregisterAll(this);
    }

    public boolean isActive() {
        return active;
    }

    public long blockedCount() {
        return blocked.get();
    }

    // ------------------------------------------------------------------
    // The fix proper: never leave an active item behind on disconnect.
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        if (!active) return;
        Player player = event.getPlayer();
        states.remove(player.getUniqueId());

        if (!plugin.getConfig().getBoolean("bundle.clear-active-item-on-quit", true)) return;
        // Run before the player is removed from the world so the clear applies to
        // the same entity the queued packet would have used.
        clearActiveItem(player, "quit");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        if (!active) return;
        if (!plugin.getConfig().getBoolean("bundle.clear-active-item-on-world-change", true)) return;
        clearActiveItem(event.getPlayer(), "world-change");
    }

    private void clearActiveItem(Player player, String reason) {
        try {
            if (player.hasActiveItem()) {
                player.clearActiveItem();
            }
        } catch (Throwable t) {
            // Never let a defensive patch break the quit path.
            plugin.getLogger().fine("Could not clear active item on " + reason + ": " + t);
        }
    }

    // ------------------------------------------------------------------
    // Rate limiting: refuse the burst that sets the exploit up.
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (!active) return;
        if (event.getHand() != EquipmentSlot.HAND) return;
        ItemStack item = event.getItem();
        if (item == null || !isBundle(item.getType())) return;

        Player player = event.getPlayer();
        long now = System.currentTimeMillis();
        State state = states.computeIfAbsent(player.getUniqueId(), k -> new State());

        if (state.suspiciousUntil > now) {
            event.setCancelled(true);
            blocked.incrementAndGet();
            log.warn("Cancelled bundle use by " + player.getName() + " while flagged as a packet-burst source.");
            return;
        }

        int maxPing = plugin.getConfig().getInt("bundle.max-ping-ms", -1);
        if (maxPing > 0) {
            int ping = player.getPing();
            if (ping > maxPing) {
                event.setCancelled(true);
                blocked.incrementAndGet();
                log.warn("Cancelled bundle use by " + player.getName() + " (ping " + ping + "ms > " + maxPing + "ms).");
                return;
            }
        }

        long cooldown = plugin.getConfig().getLong("bundle.bundle-use-cooldown-ms", 250L);
        if (now - state.lastBundleUse < cooldown) {
            event.setCancelled(true);
            blocked.incrementAndGet();
            return;
        }

        int window = plugin.getConfig().getInt("bundle.use-item-window-ms", 1000);
        int max = plugin.getConfig().getInt("bundle.max-use-item-per-window", 12);
        if (state.useCounter.inc(now, window) > max) {
            state.suspiciousUntil = now + 3000L;
            event.setCancelled(true);
            blocked.incrementAndGet();
            log.warn("Cancelled bundle use by " + player.getName() + " (>" + max
                    + " bundle uses in " + window + "ms) — probable dupe burst.");
            return;
        }

        state.lastBundleUse = now;
    }

    /**
     * A dupe client hammers inventory clicks while the bundle GUI is open; keep
     * the same sliding window on clicks so the flood is refused at the source.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!active) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        long now = System.currentTimeMillis();
        State state = states.computeIfAbsent(player.getUniqueId(), k -> new State());
        if (state.clickCounter.inc(now, 1000) > 40) {
            state.suspiciousUntil = now + 3000L;
            event.setCancelled(true);
            blocked.incrementAndGet();
            log.warn("Cancelled inventory click flood by " + player.getName() + ".");
        }
    }

    /**
     * Every bundle counts, not just the plain one: 1.21.5 added dyed bundles
     * (WHITE_BUNDLE ... BLACK_BUNDLE) that carry the same contents and therefore
     * the same duplication vector. Matching on the name suffix keeps this correct
     * on versions that predate them, and survives new colours being added.
     */
    static boolean isBundle(Material type) {
        if (type == Material.BUNDLE) return true;
        String name = type.name();
        return name.endsWith("_BUNDLE");
    }

    private static final class State {
        volatile long lastBundleUse = 0L;
        volatile long suspiciousUntil = 0L;
        final RollingCounter useCounter = new RollingCounter();
        final RollingCounter clickCounter = new RollingCounter();
    }

    /** Fixed-window counter; allocation free on the hot path. */
    static final class RollingCounter {
        private long windowStart = 0L;
        private int count = 0;

        synchronized int inc(long now, long windowMs) {
            if (windowStart == 0L || now - windowStart >= windowMs) {
                windowStart = now;
                count = 0;
            }
            return ++count;
        }
    }
}
