package io.github.dupeguard;

import io.github.dupeguard.module.BundleGuard;
import io.github.dupeguard.module.DripleafGuard;
import io.github.dupeguard.util.BlockLog;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * DupeGuard — plugin-level patches for two item-duplication vectors that are
 * still reproducible on stock Paper/Spigot 1.21.11:
 *
 * <ul>
 *   <li>small-dripleaf / bonemeal "tree capture" leak</li>
 *   <li>bundle "use item after the player entity is gone" (Netty stall + kick)</li>
 * </ul>
 *
 * Both modules are defensive: they never assume NMS internals, they observe the
 * server through the Bukkit event API and act on states that a legitimate game
 * cannot produce.
 */
public final class DupeGuardPlugin extends JavaPlugin {

    private DripleafGuard dripleafGuard;
    private BundleGuard bundleGuard;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        if (!getConfig().getBoolean("enabled", true)) {
            getLogger().warning("DupeGuard is disabled in config.yml (enabled: false) — no patches active.");
            return;
        }

        BlockLog log = new BlockLog(this);

        this.dripleafGuard = new DripleafGuard(this, log);
        this.bundleGuard = new BundleGuard(this, log);

        int active = 0;
        if (dripleafGuard.enable()) active++;
        if (bundleGuard.enable()) active++;

        if (active == 0) {
            getLogger().warning("Every module is disabled — DupeGuard loaded but patches nothing.");
        } else {
            getLogger().info("DupeGuard enabled with " + active + " active module(s).");
        }
    }

    @Override
    public void onDisable() {
        if (bundleGuard != null) bundleGuard.disable();
        if (dripleafGuard != null) dripleafGuard.disable();
    }

    private static void reply(CommandSender sender, String text, NamedTextColor color) {
        // Send through Audience so no deprecated BaseComponent overload is resolved.
        ((net.kyori.adventure.audience.Audience) sender)
                .sendMessage(net.kyori.adventure.text.Component.text(text, color));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("dupeguard.admin")) {
            ((net.kyori.adventure.audience.Audience) sender)
                    .sendMessage(net.kyori.adventure.text.Component.text("No permission.", NamedTextColor.RED));
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            reply(sender, "DupeGuard " + getPluginMeta().getVersion(), NamedTextColor.GOLD);
            reply(sender, "dripleaf: " + (dripleafGuard != null && dripleafGuard.isActive() ? "active" : "disabled"),
                    dripleafGuard != null && dripleafGuard.isActive() ? NamedTextColor.GREEN : NamedTextColor.RED);
            reply(sender, "bundle:   " + (bundleGuard != null && bundleGuard.isActive() ? "active" : "disabled"),
                    bundleGuard != null && bundleGuard.isActive() ? NamedTextColor.GREEN : NamedTextColor.RED);
            reply(sender, "blocked:  " + (dripleafGuard == null ? 0 : dripleafGuard.blockedCount())
                    + " dripleaf / " + (bundleGuard == null ? 0 : bundleGuard.blockedCount()) + " bundle", NamedTextColor.GRAY);
            return true;
        }
        if (args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            reply(sender, "DupeGuard config reloaded. (restart required for module enable/disable changes)", NamedTextColor.GREEN);
            return true;
        }
        if (args[0].equalsIgnoreCase("check")) {
            // Console form: /dupeguard check <world> <x> <y> <z>
            // In-game form: /dupeguard check <x> <y> <z>
            boolean fromConsole = !(sender instanceof org.bukkit.entity.Entity)
                    && !(sender instanceof org.bukkit.command.BlockCommandSender);
            int base = fromConsole ? 2 : 1;
            if (args.length < base + 3) {
                reply(sender, fromConsole
                        ? "Usage: /" + label + " check <world> <x> <y> <z>"
                        : "Usage: /" + label + " check <x> <y> <z>", NamedTextColor.YELLOW);
                return true;
            }
            org.bukkit.World world;
            if (fromConsole) {
                world = org.bukkit.Bukkit.getWorld(args[1]);
                if (world == null) {
                    reply(sender, "Unknown world: " + args[1], NamedTextColor.RED);
                    return true;
                }
            } else if (sender instanceof org.bukkit.entity.Entity entity) {
                world = entity.getWorld();
            } else {
                world = ((org.bukkit.command.BlockCommandSender) sender).getBlock().getWorld();
            }
            try {
                int x = Integer.parseInt(args[base]);
                int y = Integer.parseInt(args[base + 1]);
                int z = Integer.parseInt(args[base + 2]);
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    reply(sender, "Chunk " + (x >> 4) + "," + (z >> 4) + " is not loaded in " + world.getName()
                            + " — use /forceload add first.", NamedTextColor.RED);
                    return true;
                }
                org.bukkit.block.Block block = world.getBlockAt(x, y, z);
                DripleafGuard.BlockStateKind kind = DripleafGuard.classify(block);
                boolean wouldBlock = kind != DripleafGuard.BlockStateKind.HEALTHY
                        && block.getType() == org.bukkit.Material.SMALL_DRIPLEAF;
                reply(sender, world.getName() + " " + x + "," + y + "," + z + " " + block.getType()
                        + " -> " + kind + (wouldBlock ? " (WOULD BE BLOCKED)" : " (ok)"),
                        wouldBlock ? NamedTextColor.RED : NamedTextColor.GREEN);
            } catch (NumberFormatException e) {
                reply(sender, "Coordinates must be integers.", NamedTextColor.RED);
            }
            return true;
        }
        if (args[0].equalsIgnoreCase("purge")) {
            // /dupeguard purge [world] [radius]
            org.bukkit.World world;
            int radius = 64;
            int idx = 1;
            if (args.length > idx && org.bukkit.Bukkit.getWorld(args[idx]) != null) {
                world = org.bukkit.Bukkit.getWorld(args[idx++]);
            } else if (sender instanceof org.bukkit.entity.Entity entity) {
                world = entity.getWorld();
            } else if (sender instanceof org.bukkit.command.BlockCommandSender bcs) {
                world = bcs.getBlock().getWorld();
            } else {
                reply(sender, "Usage: /" + label + " purge <world> [radius]", NamedTextColor.YELLOW);
                return true;
            }
            if (args.length > idx) {
                try {
                    radius = Math.max(1, Math.min(512, Integer.parseInt(args[idx])));
                } catch (NumberFormatException e) {
                    reply(sender, "Radius must be an integer.", NamedTextColor.RED);
                    return true;
                }
            }
            if (dripleafGuard == null || !dripleafGuard.isActive()) {
                reply(sender, "The dripleaf module is disabled.", NamedTextColor.RED);
                return true;
            }
            int removed = dripleafGuard.purgeAround(world, radius);
            reply(sender, "Purged " + removed + " illegal small dripleaf plant(s) within "
                    + radius + " blocks in " + world.getName() + ".", NamedTextColor.GREEN);
            return true;
        }
        reply(sender, "Usage: /" + label + " <status|reload|check x y z|purge [world] [radius]>", NamedTextColor.YELLOW);
        return true;
    }
}
