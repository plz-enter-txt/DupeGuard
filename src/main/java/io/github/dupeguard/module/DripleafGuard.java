package io.github.dupeguard.module;

import io.github.dupeguard.util.BlockLog;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Small-dripleaf duplication ("Dripleaf Universal Dupe", Spigot/Paper/Folia
 * 1.17 – 26.1.2).
 *
 * <h2>The bug</h2>
 * Spigot wraps {@code BonemealableBlock.performBonemeal} in
 * {@code World.captureTreeGeneration = true} bookkeeping: every block change made
 * during the grow is recorded into {@code capturedBlockStates} instead of being
 * written to the chunk, then the recorded states are replayed as a
 * {@link BlockFertilizeEvent}. When the grow routine returns early — which it
 * does for a "small dripleaf" that has lost its other half (built with an
 * update skipper) — the capture flag is left set on the world. From then on the
 * world swallows block writes: breaking the dripleaf drops its item but the
 * block is never actually removed, so the same plant can be broken over and over.
 *
 * <h2>What this module does</h2>
 * The exploit needs a structurally impossible plant, and it needs the grow call
 * to run on that plant. Both are observable from the Bukkit API, so the module
 * refuses the grow before it can leak the flag, and validates the plant halves on
 * interaction. Nothing here depends on NMS, so it works on Spigot, Paper, Purpur,
 * Folia and their forks alike.
 */
public final class DripleafGuard implements Listener {

    private final Plugin plugin;
    private final BlockLog log;
    private final AtomicLong blocked = new AtomicLong();

    private volatile boolean active;

    public DripleafGuard(Plugin plugin, BlockLog log) {
        this.plugin = plugin;
        this.log = log;
    }

    public boolean enable() {
        if (!plugin.getConfig().getBoolean("dripleaf.enabled", true)) {
            return false;
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        this.active = true;
        return true;
    }

    public void disable() {
        this.active = false;
        org.bukkit.event.HandlerList.unregisterAll(this);
    }

    public boolean isActive() {
        return active;
    }

    public long blockedCount() {
        return blocked.get();
    }

    // ------------------------------------------------------------------
    // Exploit entry point: bonemeal on a broken small dripleaf.
    // ------------------------------------------------------------------

    /**
     * Runs at HIGHEST so other plugins still see the event first; the grow itself
     * happens after the event, so cancelling here still prevents the flag leak.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFertilize(BlockFertilizeEvent event) {
        if (!active) return;
        Block block = event.getBlock();
        if (block.getType() != Material.SMALL_DRIPLEAF) return;

        BlockStateKind kind = classify(block);
        if (kind == BlockStateKind.HEALTHY) return;

        boolean blockBroken = plugin.getConfig().getBoolean("dripleaf.block-broken-dripleaf-bonemeal", true);
        boolean blockMismatch = plugin.getConfig().getBoolean("dripleaf.block-half-mismatch-bonemeal", true);
        boolean refuse = (kind == BlockStateKind.ORPHAN && blockBroken)
                || (kind == BlockStateKind.MISMATCH && blockMismatch);
        if (!refuse) return;

        event.setCancelled(true);
        blocked.incrementAndGet();
        log.warn("Refused bonemeal on a broken small dripleaf at " + describe(block)
                + " (" + kind + ") — this is the small-dripleaf dupe setup.");
        notifyPlayer(event.getPlayer());
    }

    // ------------------------------------------------------------------
    // Secondary guards: repair the setup, and clean up a successful grow.
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (!active) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (!plugin.getConfig().getBoolean("dripleaf.repair-on-interact", true)) return;

        Block clicked = event.getClickedBlock();
        if (clicked == null || clicked.getType() != Material.SMALL_DRIPLEAF) return;

        BlockStateKind kind = classify(clicked);
        if (kind == BlockStateKind.HEALTHY) return;

        // Breaking is the exploit's payload: refuse it while the plant is broken.
        // (A healthy plant is left alone; only the impossible states are touched.)
        event.setCancelled(true);
        blocked.incrementAndGet();
        log.warn("Cancelled interaction with a broken small dripleaf at " + describe(clicked) + " (" + kind + ").");
        notifyPlayer(event.getPlayer());
        purgeIfConfigured(clicked);
    }

    /**
     * Remove the illegal plant instead of leaving it hanging in the world.
     *
     * An ORPHAN/MISMATCH plant is not something vanilla can produce: it only
     * exists because the exploit built it while update suppression was active.
     * Vanilla's {@code canSurvive} neighbour updates cannot reach it, so it stays
     * in the world forever and keeps inviting attempts. Clearing it restores a
     * legal world state.
     *
     * Runs on the next tick so the block is removed outside the event callback,
     * and only touches blocks that are still illegal when the tick runs.
     */
    private void purgeIfConfigured(Block block) {
        if (!plugin.getConfig().getBoolean("dripleaf.auto-purge-illegal", true)) return;

        World world = block.getWorld();
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!world.isChunkLoaded(x >> 4, z >> 4)) return;
            Block target = world.getBlockAt(x, y, z);
            if (target.getType() != Material.SMALL_DRIPLEAF) return;
            if (classify(target) == BlockStateKind.HEALTHY) return;

            target.setType(Material.AIR, false);
            log.warn("Removed an illegal small dripleaf at " + describe(target)
                    + " (auto-purge) — this plant was an exploit leftover.");
        });
    }

    /**
     * If a grow did go through (another plugin's ordering, a fork without the
     * event, a cancelled event that was re-fired), verify the resulting big
     * dripleaf and remove malformed leftovers so the world state stays sane.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGrow(BlockFertilizeEvent event) {
        if (!active) return;
        if (!plugin.getConfig().getBoolean("dripleaf.sanitize-after-grow", true)) return;
        if (event.getBlock().getType() != Material.SMALL_DRIPLEAF) return;

        Block grown = event.getBlock();
        World world = grown.getWorld();
        int x = grown.getX();
        int y = grown.getY();
        int z = grown.getZ();
        // The grow replaces the lower half in place; verify on the next tick so the
        // server has finished writing the new blocks.
        Bukkit.getScheduler().runTask(plugin, () -> sanitize(world, x, y, z));
    }

    private void sanitize(World world, int x, int y, int z) {
        if (!world.isChunkLoaded(x >> 4, z >> 4)) return;
        Block block = world.getBlockAt(x, y, z);
        if (block.getType() != Material.BIG_DRIPLEAF) return;

        BlockData data = block.getBlockData();
        if (!(data instanceof Bisected bisected)) return;
        if (bisected.getHalf() != Bisected.Half.BOTTOM) {
            // The lower half was replaced but we are looking at a top half: the plant
            // is inverted, which is another impossible state.
            log.warn("Removing an inverted big dripleaf at " + describe(block) + ".");
            block.setType(Material.AIR, false);
            return;
        }
        Block above = block.getRelative(BlockFace.UP);
        if (above.getType() != Material.BIG_DRIPLEAF) {
            log.warn("Removing a headless big dripleaf at " + describe(block) + ".");
            block.setType(Material.AIR, false);
        }
    }

    /**
     * Also refuse to break a plant whose halves are inconsistent: the dupe is only
     * useful if the block keeps dropping while staying in the world, and a broken
     * plant is the marker of that state.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!active) return;
        Block block = event.getBlock();
        if (block.getType() != Material.SMALL_DRIPLEAF) return;
        if (classify(block) == BlockStateKind.HEALTHY) return;

        event.setCancelled(true);
        blocked.incrementAndGet();
        log.warn("Cancelled break of a broken small dripleaf at " + describe(block) + ".");
        notifyPlayer(event.getPlayer());
        purgeIfConfigured(block);
    }

    /**
     * Scan loaded chunks around a center and remove every illegal small dripleaf.
     *
     * Used by {@code /dupeguard purge}, so an operator can clean up leftovers from
     * attempts that happened before the plugin was installed. Only loaded chunks
     * are visited: unloading a chunk to scan it would be far more disruptive than
     * the leftover block itself.
     */
    public int purgeAround(World world, int radius) {
        int removed = 0;
        // Anchor the square on the sender's location when we have no better center.
        int centerX = 0;
        int centerZ = 0;
        if (!world.getPlayers().isEmpty()) {
            org.bukkit.Location loc = world.getPlayers().get(0).getLocation();
            centerX = loc.getBlockX();
            centerZ = loc.getBlockZ();
        }
        int minChunkX = (centerX - radius) >> 4;
        int maxChunkX = (centerX + radius) >> 4;
        int minChunkZ = (centerZ - radius) >> 4;
        int maxChunkZ = (centerZ + radius) >> 4;

        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                if (!world.isChunkLoaded(cx, cz)) continue;
                removed += purgeChunkPlants(world, world.getChunkAt(cx, cz));
            }
        }
        return removed;
    }

    private int purgeChunkPlants(World world, org.bukkit.Chunk chunk) {
        int removed = 0;
        int baseX = chunk.getX() << 4;
        int baseZ = chunk.getZ() << 4;
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight();

        for (int x = baseX; x < baseX + 16; x++) {
            for (int z = baseZ; z < baseZ + 16; z++) {
                for (int y = minY; y < maxY; y++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (block.getType() != Material.SMALL_DRIPLEAF) continue;
                    if (classify(block) == BlockStateKind.HEALTHY) continue;
                    block.setType(Material.AIR, false);
                    removed++;
                    log.warn("Removed an illegal small dripleaf at " + describe(block) + " (manual purge).");
                }
            }
        }
        return removed;
    }

    // ------------------------------------------------------------------
    // Structural classification.
    // ------------------------------------------------------------------

    public enum BlockStateKind {
        /** Two matching halves, waterlogged consistently: a normal plant. */
        HEALTHY,
        /** A half exists without its partner. */
        ORPHAN,
        /** Both halves exist but disagree (type/half/waterlog). */
        MISMATCH
    }

    /**
     * A small dripleaf is always a two-block plant. The Bukkit API exposes the
     * halves through {@link Bisected}, so any state that cannot be produced by
     * vanilla placement is detectable without NMS.
     */
    public static BlockStateKind classify(Block block) {
        if (block.getType() != Material.SMALL_DRIPLEAF) return BlockStateKind.HEALTHY;

        BlockData data = block.getBlockData();
        if (!(data instanceof Bisected bisected)) return BlockStateKind.HEALTHY;

        Block partner = bisected.getHalf() == Bisected.Half.BOTTOM
                ? block.getRelative(BlockFace.UP)
                : block.getRelative(BlockFace.DOWN);

        if (partner.getType() != Material.SMALL_DRIPLEAF) {
            return BlockStateKind.ORPHAN;
        }
        if (!(partner.getBlockData() instanceof Bisected partnerData)) {
            return BlockStateKind.MISMATCH;
        }
        if (partnerData.getHalf() == bisected.getHalf()) {
            // Two bottoms or two tops: impossible.
            return BlockStateKind.MISMATCH;
        }
        if (data instanceof Waterlogged a && partner.getBlockData() instanceof Waterlogged b
                && a.isWaterlogged() != b.isWaterlogged()) {
            return BlockStateKind.MISMATCH;
        }
        return BlockStateKind.HEALTHY;
    }

    private void notifyPlayer(Player player) {
        if (player == null) return;
        if (!plugin.getConfig().getBoolean("dripleaf.notify-player", false)) return;
        ((net.kyori.adventure.audience.Audience) player).sendActionBar(net.kyori.adventure.text.Component.text(
                "Broken small dripleaf blocked (dupe protection)", net.kyori.adventure.text.format.NamedTextColor.RED));
    }

    private static String describe(Block block) {
        return block.getWorld().getName() + " " + block.getX() + "," + block.getY() + "," + block.getZ();
    }
}
