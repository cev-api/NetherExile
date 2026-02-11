package local.mimi.netherexile;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Skull;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Statistic;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

final class DeadService {
    private final JavaPlugin plugin;
    private final DeadStore store;
    private final TimerHistoryStore historyStore;
    private final EntryStore entryStore;
    private final NamespacedKey skeletonKey;

    // Allows one controlled teleport out of the Nether for revival.
    private final java.util.Set<UUID> allowOneExitTeleport = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private int saveEverySecondsCounter = 0;

    DeadService(JavaPlugin plugin, DeadStore store, TimerHistoryStore historyStore, EntryStore entryStore) {
        this.plugin = plugin;
        this.store = store;
        this.historyStore = historyStore;
        this.entryStore = entryStore;
        this.skeletonKey = new NamespacedKey(plugin, "netherexile_skeleton_skull");
    }

    boolean isNetherExileEnabled() {
        // Backward compatibility: older configs used netherdeath_enabled.
        if (plugin.getConfig().contains("netherexile_enabled")) {
            return plugin.getConfig().getBoolean("netherexile_enabled", true);
        }
        return plugin.getConfig().getBoolean("netherdeath_enabled", true);
    }

    boolean isNetherNetherDeathEnabled() {
        return plugin.getConfig().getBoolean("nethernetherdeath_enabled", false);
    }

    boolean isBreakWithArrowEnabled() {
        return plugin.getConfig().getBoolean("breakwitharrow_enabled", false);
    }

    void setNetherExileEnabled(boolean enabled) {
        plugin.getConfig().set("netherexile_enabled", enabled);
        plugin.saveConfig();

        // When disabling, clear active prison state so re-enabling doesn't unexpectedly re-imprison players.
        if (!enabled) {
            clearAllDeadState();
        } else {
            rescheduleAllAutoBreak();
        }
    }

    void clearAllDeadState() {
        // Clear persistent dead state.
        store.all().clear();
        store.save();
    }

    int portalExtinguishRadius() {
        return plugin.getConfig().getInt("portal_extinguish_radius", 4);
    }

    int portalExtinguishMaxBlocks() {
        return plugin.getConfig().getInt("portal_extinguish_max_blocks", 4096);
    }

    private double borderMarginBlocks() {
        return plugin.getConfig().getDouble("border_margin_blocks", 32.0);
    }

    boolean isDead(UUID playerId) {
        return store.get(playerId) != null;
    }

    DeadRecord get(UUID playerId) {
        return store.get(playerId);
    }

    java.util.Set<UUID> getDeadPlayerIds() {
        return java.util.Collections.unmodifiableSet(store.all().keySet());
    }

    void markDeadOnOverworldDeath(Player player, Location deathLoc) {
        if (!isNetherExileEnabled()) return;
        if (player == null || deathLoc == null) return;
        if (isDead(player.getUniqueId())) return;

        World w = deathLoc.getWorld();
        if (w == null || w.getEnvironment() != World.Environment.NORMAL) return;

        World nether = Bukkit.getWorlds().stream()
            .filter(world -> world.getEnvironment() == World.Environment.NETHER)
            .findFirst().orElse(null);
        if (nether == null) {
            plugin.getLogger().warning("Player died in Overworld but no Nether world is loaded; skipping NetherExile for " + player.getName());
            return;
        }

        Location base = pickMarkerBase(deathLoc);
        Location skullLoc = base.clone().add(0, 1, 0);

        // Place blocks
        Block lodestone = base.getBlock();
        lodestone.setType(Material.LODESTONE, false);

        Block skullBlock = skullLoc.getBlock();
        skullBlock.setType(Material.PLAYER_HEAD, false);
        if (skullBlock.getState() instanceof Skull skull) {
            skull.setOwningPlayer(player);
            skull.update(true, true);
        }

        DeadRecord r = new DeadRecord(player.getUniqueId());
        r.overworldDeath = deathLoc.clone();
        r.lodestoneBlock = base.clone();
        r.skullBlock = skullLoc.clone();
        r.markedDeadAtEpochMillis = System.currentTimeMillis();
        r.lastSeenPlayTicks = safeGetPlayTicks(player);
        r.autoBreakRemainingPlayTicks = computeInitialAutoBreakPlayTicks(player.getUniqueId());
        store.put(r);
        store.save();
        scheduleMarkerStabilityCheck(r.playerId);
    }

    void markDeadOnNetherFirstDeath(Player player, Location deathLoc) {
        if (!isNetherExileEnabled()) return;
        if (!isNetherNetherDeathEnabled()) return;
        if (player == null || deathLoc == null) return;
        if (isDead(player.getUniqueId())) return;

        World w = deathLoc.getWorld();
        if (w == null || w.getEnvironment() != World.Environment.NETHER) return;

        // Place markers in the Nether at/near the death location (same placement rules: air -> ground, lava -> above lava).
        Location base = pickMarkerBase(deathLoc);
        Location skullLoc = base.clone().add(0, 1, 0);

        Block lodestone = base.getBlock();
        lodestone.setType(Material.LODESTONE, false);

        Block skullBlock = skullLoc.getBlock();
        skullBlock.setType(Material.PLAYER_HEAD, false);
        if (skullBlock.getState() instanceof Skull skull) {
            skull.setOwningPlayer(player);
            skull.update(true, true);
        }

        DeadRecord r = new DeadRecord(player.getUniqueId());
        r.netherDeath = deathLoc.clone();
        r.startedFromNetherDeath = true;
        r.lodestoneBlock = base.clone();
        r.skullBlock = skullLoc.clone();
        r.markedDeadAtEpochMillis = System.currentTimeMillis();
        r.lastSeenPlayTicks = safeGetPlayTicks(player);
        r.autoBreakRemainingPlayTicks = computeInitialAutoBreakPlayTicks(player.getUniqueId());

        Location entry = entryStore != null ? entryStore.getLastNetherPortalEntry(player.getUniqueId()) : null;
        if (entry == null || entry.getWorld() == null || entry.getWorld().getEnvironment() != World.Environment.NETHER) {
            // Fallback: keep them near the death location (safe).
            entry = deathLoc.clone();
        }
        r.netherEntry = sanitizeNetherTarget(entry.clone());

        store.put(r);
        store.save();
        scheduleMarkerStabilityCheck(r.playerId);
    }

    private void scheduleMarkerStabilityCheck(UUID playerId) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> validateAndRepairMarker(playerId), 1L);
    }

    private void validateAndRepairMarker(UUID playerId) {
        DeadRecord r = store.get(playerId);
        if (r == null) return;

        boolean lodestoneOk = isTypeAt(r.lodestoneBlock, Material.LODESTONE);
        boolean skullOk = isTypeAt(r.skullBlock, Material.PLAYER_HEAD) || isTypeAt(r.skullBlock, Material.PLAYER_WALL_HEAD);
        if (lodestoneOk && skullOk) return;

        // Clean up leftovers before relocating.
        removeBlockIfPresent(r.lodestoneBlock, Material.LODESTONE);
        removeBlockIfPresent(r.skullBlock, Material.PLAYER_HEAD);
        removeBlockIfPresent(r.skullBlock, Material.PLAYER_WALL_HEAD);

        Location preferred = r.lodestoneBlock != null ? r.lodestoneBlock
            : (r.overworldDeath != null ? r.overworldDeath
            : (r.netherDeath != null ? r.netherDeath : null));
        if (preferred == null || preferred.getWorld() == null) return;

        Location newBase = findStableMarkerBaseNear(preferred, 8);
        if (newBase == null) newBase = preferred.getBlock().getLocation();
        Location newHead = newBase.clone().add(0, 1, 0);

        Block lodestone = newBase.getBlock();
        lodestone.setType(Material.LODESTONE, false);
        Block skull = newHead.getBlock();
        skull.setType(Material.PLAYER_HEAD, false);
        if (skull.getState() instanceof Skull skullState) {
            OfflinePlayer op = Bukkit.getOfflinePlayer(playerId);
            skullState.setOwningPlayer(op);
            skullState.update(true, true);
        }

        r.lodestoneBlock = newBase.clone();
        r.skullBlock = newHead.clone();
        store.save();
    }

    private static boolean isTypeAt(Location loc, Material mat) {
        if (loc == null || loc.getWorld() == null) return false;
        return loc.getBlock().getType() == mat;
    }

    private static boolean canReplaceForMarker(Block b) {
        Material t = b.getType();
        if (t == Material.LODESTONE || t == Material.PLAYER_HEAD || t == Material.PLAYER_WALL_HEAD) return true;
        if (b.isLiquid()) return false;
        return b.isPassable();
    }

    private static Location findStableMarkerBaseNear(Location preferred, int radius) {
        if (preferred == null || preferred.getWorld() == null) return null;
        World w = preferred.getWorld();

        int cx = preferred.getBlockX();
        int cy = preferred.getBlockY();
        int cz = preferred.getBlockZ();
        int minY = w.getMinHeight() + 1;
        int maxY = w.getMaxHeight() - 2;

        for (int r = 0; r <= radius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int x = cx + dx;
                    int z = cz + dz;

                    int startY = clamp(cy, minY, maxY);
                    for (int dy = 0; dy <= 12; dy++) {
                        int y1 = clamp(startY - dy, minY, maxY);
                        Location l1 = markerBaseIfStable(w, x, y1, z);
                        if (l1 != null) return l1;
                        int y2 = clamp(startY + dy, minY, maxY);
                        Location l2 = markerBaseIfStable(w, x, y2, z);
                        if (l2 != null) return l2;
                    }
                }
            }
        }
        return null;
    }

    private static Location markerBaseIfStable(World w, int x, int y, int z) {
        Block base = w.getBlockAt(x, y, z);
        Block head = w.getBlockAt(x, y + 1, z);
        Block below = w.getBlockAt(x, y - 1, z);

        if (!canReplaceForMarker(base)) return null;
        if (!canReplaceForMarker(head)) return null;
        if (below.isPassable() || below.isLiquid()) return null;
        return new Location(w, x, y, z);
    }

    private Location pickMarkerBase(Location deathLoc) {
        World w = deathLoc.getWorld();
        if (w == null) return deathLoc.getBlock().getLocation();

        int x = deathLoc.getBlockX();
        int y = deathLoc.getBlockY();
        int z = deathLoc.getBlockZ();

        // If death happened in lava, place the marker on top of the lava surface in that column.
        if (w.getBlockAt(x, y, z).getType() == Material.LAVA) {
            int maxY = w.getMaxHeight() - 3; // room for head above
            int top = y;
            while (top + 1 <= maxY && w.getBlockAt(x, top + 1, z).getType() == Material.LAVA) {
                top++;
            }
            int placeY = Math.min(top + 1, maxY);
            // If it's obstructed, nudge upward a bit to find a replaceable space.
            for (int i = 0; i <= 6; i++) {
                int yy = Math.min(placeY + i, maxY);
                if (w.getBlockAt(x, yy, z).isPassable()) {
                    return new Location(w, x, yy, z);
                }
            }
            return new Location(w, x, placeY, z);
        }

        // If death happened in the air, place the marker on the ground below.
        if (w.getBlockAt(x, y, z).isPassable() && !w.getBlockAt(x, y, z).isLiquid()) {
            int minY = w.getMinHeight();
            int groundY = Integer.MIN_VALUE;
            for (int yy = y; yy >= minY; yy--) {
                var b = w.getBlockAt(x, yy, z);
                if (!b.isPassable() && !b.isLiquid()) {
                    groundY = yy;
                    break;
                }
            }

            int placeY;
            if (groundY != Integer.MIN_VALUE) {
                placeY = groundY + 1;
            } else {
                placeY = w.getHighestBlockYAt(x, z) + 1;
            }

            int maxY = w.getMaxHeight() - 3;
            placeY = Math.min(placeY, maxY);
            return new Location(w, x, placeY, z);
        }

        // Default: keep old behavior (replace the block they died in).
        return deathLoc.getBlock().getLocation();
    }

    Location computeNetherEquivalent(Location overworldLoc) {
        World overworld = overworldLoc.getWorld();
        if (overworld == null) return null;

        World nether = Bukkit.getWorlds().stream()
            .filter(w -> w.getEnvironment() == World.Environment.NETHER)
            .findFirst().orElse(null);
        if (nether == null) return null;

        double x = overworldLoc.getX() / 8.0;
        double z = overworldLoc.getZ() / 8.0;
        // Keep the preferred Y comfortably below the Nether roof area.
        // The roof itself is around Y=127 (bedrock), and standing spots at/above 128 are "on the roof".
        double yCap = 120.0;
        double y = Math.max(nether.getMinHeight() + 10, Math.min(overworldLoc.getY(), yCap));

        Location preferred = new Location(nether, x, y, z, overworldLoc.getYaw(), overworldLoc.getPitch());
        Location safe = findSafeSpotNear(preferred, 48);
        // Nether border is generally same as Overworld, but keep logic consistent anyway.
        return sanitizeNetherTarget(clampInsideWorldBorder(safe, borderMarginBlocks()));
    }

    void onRespawn(Player player, Location defaultRespawn, org.bukkit.event.player.PlayerRespawnEvent event) {
        if (!isNetherExileEnabled()) return;
        DeadRecord r = store.get(player.getUniqueId());
        if (r == null) return;

        if (r.forceRespawnToNetherEntry) {
            r.forceRespawnToNetherEntry = false;
            if (r.netherEntry != null) {
                Location safe = sanitizeNetherTarget(r.netherEntry.clone());
                r.netherEntry = safe.clone();
                store.save();
                event.setRespawnLocation(safe);
                clearInventoryHard(player);
                plugin.getServer().getScheduler().runTask(plugin, () -> maybeEquipSkeletonHelmet(player, r));
            } else {
                store.save();
            }
            return;
        }

        // If they have a pending revive (revived while offline), do nothing here;
        // JoinListener will handle it after login.
        if (r.pendingReviveOverworldTarget != null) {
            return;
        }

        // If this prison started from a Nether death, always respawn them back at netherEntry.
        if (r.startedFromNetherDeath && r.netherEntry != null) {
            Location safe = sanitizeNetherTarget(r.netherEntry.clone());
            r.netherEntry = safe.clone();
            store.save();
            event.setRespawnLocation(safe);
            clearInventoryHard(player);
            plugin.getServer().getScheduler().runTask(plugin, () -> sendTrapMessage(player));
            plugin.getServer().getScheduler().runTask(plugin, () -> maybeEquipSkeletonHelmet(player, r));
            return;
        }

        // First-time imprisonment: respawn them in Nether equivalent location.
        if (r.netherEntry == null && r.overworldDeath != null) {
            Location nether = computeNetherEquivalent(r.overworldDeath);
            if (nether != null) {
                r.netherEntry = sanitizeNetherTarget(nether.clone());
                store.save();
                event.setRespawnLocation(r.netherEntry.clone());
                clearInventoryHard(player);
                // Send the trap message after the player actually spawns.
                plugin.getServer().getScheduler().runTask(plugin, () -> sendTrapMessage(player));
                plugin.getServer().getScheduler().runTask(plugin, () -> maybeEquipSkeletonHelmet(player, r));
            } else {
                plugin.getLogger().warning("Failed to compute Nether entry for " + player.getName() + " (no Nether world?)");
            }
        }
    }

    boolean shouldBlockTeleport(Player player, Location from, Location to, PlayerTeleportEvent.TeleportCause cause) {
        if (!isNetherExileEnabled()) return false;
        if (player == null || from == null || to == null) return false;
        if (!isDead(player.getUniqueId())) return false;
        if (allowOneExitTeleport.remove(player.getUniqueId())) return false;

        World fw = from.getWorld();
        World tw = to.getWorld();
        if (fw == null || tw == null) return false;

        // Dead players must not leave Nether to any other dimension via portals/commands/etc.
        if (fw.getEnvironment() == World.Environment.NETHER && tw.getEnvironment() != World.Environment.NETHER) {
            return true;
        }

        return false;
    }

    void extinguishNearbyPortals(Location at, int radius) {
        if (!isNetherExileEnabled()) return;
        if (at == null || at.getWorld() == null) return;
        extinguishConnectedPortal(at, radius, portalExtinguishMaxBlocks());
    }

    void extinguishConnectedPortal(Location at, int searchRadius, int maxBlocks) {
        if (!isNetherExileEnabled()) return;
        if (at == null || at.getWorld() == null) return;
        World w = at.getWorld();

        Block start = findAnyPortalBlockNear(at, searchRadius);
        if (start == null) return;

        int cap = Math.max(1, maxBlocks);
        java.util.ArrayDeque<Block> q = new java.util.ArrayDeque<>();
        java.util.HashSet<Long> seen = new java.util.HashSet<>();

        q.add(start);
        seen.add(blockKey(start));

        int removed = 0;
        while (!q.isEmpty() && removed < cap) {
            Block b = q.poll();
            if (b.getType() != Material.NETHER_PORTAL) continue;

            b.setType(Material.AIR, false);
            removed++;

            // 6-way adjacency flood fill
            for (int[] d : NEIGHBORS_6) {
                Block nb = w.getBlockAt(b.getX() + d[0], b.getY() + d[1], b.getZ() + d[2]);
                if (nb.getType() != Material.NETHER_PORTAL) continue;
                long k = blockKey(nb);
                if (seen.add(k)) {
                    q.add(nb);
                }
            }
        }
    }

    private static final int[][] NEIGHBORS_6 = new int[][]{
        {1, 0, 0}, {-1, 0, 0},
        {0, 1, 0}, {0, -1, 0},
        {0, 0, 1}, {0, 0, -1}
    };

    private static long blockKey(Block b) {
        // pack x/z (26 bits each) and y (12 bits) into a long; good enough for set membership
        long x = (long) (b.getX() & 0x3FFFFFF);
        long z = (long) (b.getZ() & 0x3FFFFFF);
        long y = (long) (b.getY() & 0xFFF);
        return (x << 38) | (z << 12) | y;
    }

    private static Block findAnyPortalBlockNear(Location at, int radius) {
        World w = at.getWorld();
        if (w == null) return null;
        int r = Math.max(0, radius);
        int cx = at.getBlockX();
        int cy = at.getBlockY();
        int cz = at.getBlockZ();

        for (int y = cy - r; y <= cy + r; y++) {
            for (int x = cx - r; x <= cx + r; x++) {
                for (int z = cz - r; z <= cz + r; z++) {
                    Block b = w.getBlockAt(x, y, z);
                    if (b.getType() == Material.NETHER_PORTAL) return b;
                }
            }
        }
        return null;
    }

    boolean reviveByBreakingMarker(Player breaker, Location brokenBlockLoc) {
        if (brokenBlockLoc == null || brokenBlockLoc.getWorld() == null) return false;

        DeadRecord found = null;
        for (DeadRecord r : store.all().values()) {
            if (sameBlock(r.lodestoneBlock, brokenBlockLoc) || sameBlock(r.skullBlock, brokenBlockLoc)) {
                found = r;
                break;
            }
        }
        if (found == null) return false;

        UUID deadId = found.playerId;
        Player deadPlayer = Bukkit.getPlayer(deadId);

        // Dead players cannot break their own marker.
        if (breaker != null && breaker.getUniqueId().equals(deadId)) {
            if (messagesEnabled()) {
                breaker.sendMessage(prefix().append(Component.text("You cannot revive yourself.", NamedTextColor.RED)));
            }
            return false;
        }

        // Give breaker the dead player's head (always, regardless of which marker block was broken).
        if (breaker != null) {
            ItemStack head = makePlayerHead(deadId, deadPlayer != null ? deadPlayer.getName() : null);
            breaker.getInventory().addItem(head);
        }

        // Strike lightning at lodestone (if it still exists / location known)
        if (found.lodestoneBlock != null && found.lodestoneBlock.getWorld() != null) {
            found.lodestoneBlock.getWorld().strikeLightning(found.lodestoneBlock);
        }

        // Remove both marker blocks (so revival is one-shot).
        removeBlockIfPresent(found.lodestoneBlock, Material.LODESTONE);
        removeBlockIfPresent(found.skullBlock, Material.PLAYER_HEAD);
        removeBlockIfPresent(found.skullBlock, Material.PLAYER_WALL_HEAD);

        Location reviveTarget = computeReviveTarget(found, deadPlayer);
        if (reviveTarget == null) {
            plugin.getLogger().warning("Revive target was null for " + deadId);
            // Still clear record to avoid permanent prison if marker already broken.
            store.remove(deadId);
            store.save();
            return true;
        }

        if (deadPlayer != null && deadPlayer.isOnline()) {
            allowOneExitTeleport.add(deadId);
            Location safeTarget = sanitizeOverworldReviveTarget(reviveTarget);
            deadPlayer.teleport(safeTarget, PlayerTeleportEvent.TeleportCause.PLUGIN);
            postTeleportBorderCorrection(deadPlayer);
            safeTarget.getWorld().strikeLightning(safeTarget);
            applyReviveBuffs(deadPlayer);
            sendRevivedMessage(deadPlayer, breaker);
        } else {
            found.pendingReviveOverworldTarget = reviveTarget;
            found.pendingReviveLightning = true;
        }

        if (breaker != null) {
            sendBreakerRevivedMessage(breaker, deadPlayer != null ? deadPlayer.getName() : Bukkit.getOfflinePlayer(deadId).getName());
        }

        store.remove(deadId);
        store.save();
        return true;
    }

    UUID findMarkerOwner(Location blockLoc) {
        if (blockLoc == null || blockLoc.getWorld() == null) return null;
        for (DeadRecord r : store.all().values()) {
            if (sameBlock(r.lodestoneBlock, blockLoc) || sameBlock(r.skullBlock, blockLoc)) {
                return r.playerId;
            }
        }
        return null;
    }

    boolean reviveByCommand(Player breaker, UUID deadId) {
        DeadRecord r = store.get(deadId);
        if (r == null) return false;
        // Mimic breaking: lightning, remove blocks, breaker gets head.
        // Use whichever marker location still exists as the "broken" block location to reuse logic.
        Location marker = r.lodestoneBlock != null ? r.lodestoneBlock : r.skullBlock;
        if (marker == null) return false;
        return reviveByBreakingMarker(breaker, marker);
    }

    void rescheduleAllAutoBreak() {
        if (!isNetherExileEnabled()) {
            return;
        }

        boolean enabled = plugin.getConfig().getBoolean("autobreak_enabled", false);
        if (!enabled) return;

        long base = parseDurationPlayTicks(plugin.getConfig().getString("autobreak_after", "1h"));
        if (base <= 0) return;

        // Ensure all dead records have a timer initialized (only decremented while online).
        for (DeadRecord r : store.all().values()) {
            if (r.autoBreakRemainingPlayTicks <= 0) {
                r.autoBreakRemainingPlayTicks = base;
            }
        }
        store.save();
    }

    private static long parseDurationPlayTicks(String s) {
        if (s == null) return -1;
        String t = s.trim().toLowerCase(java.util.Locale.ROOT);
        if (t.isEmpty()) return -1;
        if (t.equals("0") || t.equals("off") || t.equals("false")) return -1;

        long multSeconds;
        char last = t.charAt(t.length() - 1);
        String num = t;
        if (Character.isLetter(last)) {
            num = t.substring(0, t.length() - 1).trim();
            multSeconds = switch (last) {
                case 's' -> 1L;
                case 'm' -> 60L;
                case 'h' -> 3600L;
                case 'd' -> 86400L;
                default -> -1L;
            };
        } else {
            // If no suffix, treat as seconds.
            multSeconds = 1L;
        }
        if (multSeconds <= 0) return -1;

        try {
            long n = Long.parseLong(num);
            if (n <= 0) return -1;
            long seconds = Math.multiplyExact(n, multSeconds);
            return Math.multiplyExact(seconds, 20L); // convert to ticks
        } catch (Exception e) {
            return -1;
        }
    }

    void tick() {
        if (!isNetherExileEnabled()) return;

        boolean autobreak = plugin.getConfig().getBoolean("autobreak_enabled", false);
        long base = parseDurationPlayTicks(plugin.getConfig().getString("autobreak_after", "1h"));

        for (Player p : Bukkit.getOnlinePlayers()) {
            DeadRecord r = store.get(p.getUniqueId());
            if (r == null) continue;

            int current = safeGetPlayTicks(p);
            int last = r.lastSeenPlayTicks;
            int delta = current - last;
            if (delta < 0) delta = 0;
            r.lastSeenPlayTicks = current;

            if (autobreak && base > 0) {
                if (r.autoBreakRemainingPlayTicks <= 0) {
                    r.autoBreakRemainingPlayTicks = base;
                } else if (delta > 0) {
                    r.autoBreakRemainingPlayTicks = Math.max(0L, r.autoBreakRemainingPlayTicks - (long) delta);
                }

                if (r.autoBreakRemainingPlayTicks <= 0) {
                    Location marker = r.lodestoneBlock != null ? r.lodestoneBlock : r.skullBlock;
                    if (marker != null) {
                        reviveByBreakingMarker(null, marker);
                        continue;
                    }
                }
            }
        }

        // Periodic persistence.
        saveEverySecondsCounter++;
        if (saveEverySecondsCounter >= 15) {
            saveEverySecondsCounter = 0;
            store.save();
            historyStore.save();
        }
    }

    private int safeGetPlayTicks(Player p) {
        try {
            return p.getStatistic(Statistic.PLAY_ONE_MINUTE);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private long computeInitialAutoBreakPlayTicks(UUID playerId) {
        if (!plugin.getConfig().getBoolean("autobreak_enabled", false)) return 0L;

        long base = parseDurationPlayTicks(plugin.getConfig().getString("autobreak_after", "1h"));
        if (base <= 0) return 0L;

        boolean progressive = plugin.getConfig().getBoolean("autobreak_progressive_enabled", false);
        if (!progressive) return base;

        long storedBase = historyStore.getLastBasePlayTicks(playerId);
        if (storedBase != base) {
            // If the admin changed the base timeout, reset the progression so the next death uses the new base.
            historyStore.setLastBasePlayTicks(playerId, base);
            historyStore.setLastTimeoutPlayTicks(playerId, base);
            return base;
        }

        long last = historyStore.getLastTimeoutPlayTicks(playerId);
        long next = last > 0 ? safeMul2(last) : base;

        long progressiveCap = parseDurationPlayTicks(plugin.getConfig().getString("autobreak_progressive_max", "off"));
        if (progressiveCap > 0) {
            next = Math.min(next, progressiveCap);
        }

        historyStore.setLastTimeoutPlayTicks(playerId, next);
        historyStore.setLastBasePlayTicks(playerId, base);
        return next;
    }

    private static long safeMul2(long v) {
        long out = v * 2L;
        if (out < 0L) return Long.MAX_VALUE / 2L;
        return out;
    }

    private boolean messagesEnabled() {
        return plugin.getConfig().getBoolean("messages_enabled", true);
    }

    void sendTrapMessage(Player p) {
        if (p == null) return;
        if (!messagesEnabled()) return;
        if (!isDead(p.getUniqueId())) return;
        if (p.getWorld().getEnvironment() != World.Environment.NETHER) return;

        DeadRecord r = store.get(p.getUniqueId());
        if (r == null) return;

        Component msg = prefix().append(Component.text("You are trapped here until you are revived", NamedTextColor.RED));

        boolean autobreak = plugin.getConfig().getBoolean("autobreak_enabled", false);
        long base = parseDurationPlayTicks(plugin.getConfig().getString("autobreak_after", "1h"));
        if (autobreak && base > 0) {
            long remaining = Math.max(0L, r.autoBreakRemainingPlayTicks > 0 ? r.autoBreakRemainingPlayTicks : base);
            msg = prefix().append(Component.text("You are trapped here until you are revived or until ", NamedTextColor.RED))
                .append(Component.text(formatTicks(remaining), NamedTextColor.GOLD, TextDecoration.BOLD))
                .append(Component.text(" has passed!", NamedTextColor.RED));
        } else {
            msg = prefix().append(Component.text("You are trapped here until you are revived!", NamedTextColor.RED));
        }

        p.sendMessage(msg);
    }

    private void sendRevivedMessage(Player revived, Player breaker) {
        if (revived == null) return;
        if (!messagesEnabled()) return;

        if (breaker != null) {
            revived.sendMessage(prefix()
                .append(Component.text("You have been revived by ", NamedTextColor.GREEN))
                .append(Component.text(breaker.getName(), NamedTextColor.AQUA, TextDecoration.BOLD))
                .append(Component.text("!", NamedTextColor.GREEN)));
        } else {
            revived.sendMessage(prefix().append(Component.text("You have been revived!", NamedTextColor.GREEN)));
        }
    }

    private void sendBreakerRevivedMessage(Player breaker, String revivedName) {
        if (breaker == null) return;
        if (!messagesEnabled()) return;
        if (revivedName == null) revivedName = "player";

        breaker.sendMessage(prefix()
            .append(Component.text("You have revived ", NamedTextColor.GREEN))
            .append(Component.text(revivedName, NamedTextColor.AQUA, TextDecoration.BOLD))
            .append(Component.text("!", NamedTextColor.GREEN)));
    }

    void sendNoEscapeMessage(Player p) {
        if (p == null) return;
        if (!messagesEnabled()) return;
        // Reuse the same trap message format (and remaining timer if enabled).
        sendTrapMessage(p);
    }

    private static Component prefix() {
        return Component.text("[", NamedTextColor.DARK_GRAY)
            .append(Component.text("NetherExile", NamedTextColor.GOLD).decorate(TextDecoration.BOLD))
            .append(Component.text("] ", NamedTextColor.DARK_GRAY));
    }

    private static String formatTicks(long ticks) {
        long totalSeconds = Math.max(0L, ticks / 20L);
        long d = totalSeconds / 86400L;
        long h = (totalSeconds % 86400L) / 3600L;
        long m = (totalSeconds % 3600L) / 60L;
        long s = totalSeconds % 60L;

        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append("d ");
        if (h > 0) sb.append(h).append("h ");
        if (m > 0) sb.append(m).append("m ");
        sb.append(s).append("s");
        return sb.toString().trim();
    }

    private void maybeEquipSkeletonHelmet(Player p, DeadRecord r) {
        if (p == null || r == null) return;
        if (!plugin.getConfig().getBoolean("skeleton_enabled", false)) return;
        if (p.getWorld().getEnvironment() != World.Environment.NETHER) return;

        p.getInventory().setHelmet(buildSkeletonSkull(p.getName(), r));
    }

    ItemStack buildSkeletonSkull(String playerName, DeadRecord r) {
        ItemStack skull = new ItemStack(Material.SKELETON_SKULL, 1);
        var meta = skull.getItemMeta();
        if (meta != null) {
            String coords = r != null && r.overworldDeath != null
                ? (r.overworldDeath.getBlockX() + ", " + r.overworldDeath.getBlockY() + ", " + r.overworldDeath.getBlockZ())
                : "unknown";
            String when = r != null ? formatWhen(r.markedDeadAtEpochMillis) : "unknown time";
            String n = playerName != null ? playerName : "Player";
            meta.displayName(Component.text(n + " died at " + coords + " on " + when, NamedTextColor.GRAY));
            meta.getPersistentDataContainer().set(skeletonKey, PersistentDataType.BYTE, (byte) 1);
            skull.setItemMeta(meta);
        }
        return skull;
    }

    void removeSkeletonSkullFromDropsOnNetherDeath(org.bukkit.event.entity.PlayerDeathEvent e) {
        if (e == null) return;
        if (!isNetherExileEnabled()) return;
        if (!plugin.getConfig().getBoolean("skeleton_enabled", false)) return;

        Player p = e.getEntity();
        if (p.getWorld().getEnvironment() != World.Environment.NETHER) return;
        DeadRecord r = store.get(p.getUniqueId());
        if (r == null) return;

        // You explicitly lose the skeleton skull if you die in the Nether while imprisoned.
        e.getDrops().removeIf(item -> {
            if (item == null) return false;
            var m = item.getItemMeta();
            if (m == null) return false;
            Byte tag = m.getPersistentDataContainer().get(skeletonKey, PersistentDataType.BYTE);
            return tag != null && tag == (byte) 1;
        });
    }

    private static String formatWhen(long epochMillis) {
        if (epochMillis <= 0) return "unknown time";
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
        return fmt.format(Instant.ofEpochMilli(epochMillis));
    }

    void onJoin(Player player) {
        if (!isNetherExileEnabled()) return;
        DeadRecord r = store.get(player.getUniqueId());
        if (r == null) return;

        // If revived while offline, execute the revive now.
        if (r.pendingReviveOverworldTarget != null) {
            Location target = sanitizeOverworldReviveTarget(r.pendingReviveOverworldTarget);
            r.pendingReviveOverworldTarget = null;

            allowOneExitTeleport.add(player.getUniqueId());
            player.teleport(target, PlayerTeleportEvent.TeleportCause.PLUGIN);
            postTeleportBorderCorrection(player);
            if (r.pendingReviveLightning && target.getWorld() != null) {
                target.getWorld().strikeLightning(target);
            }
            r.pendingReviveLightning = false;
            store.remove(player.getUniqueId());
            store.save();
            applyReviveBuffs(player);
            return;
        }

        // Enforce: dead players must stay in Nether. If they log in elsewhere, send them back.
        World w = player.getWorld();
        if (w.getEnvironment() != World.Environment.NETHER) {
            if (r.netherEntry != null) {
                allowOneExitTeleport.add(player.getUniqueId()); // allow the corrective teleport into Nether
                Location safe = sanitizeNetherTarget(r.netherEntry.clone());
                r.netherEntry = safe.clone();
                store.save();
                player.teleport(safe, PlayerTeleportEvent.TeleportCause.PLUGIN);
            }
        }
    }

    private Location sanitizeNetherTarget(Location loc) {
        if (loc == null || loc.getWorld() == null) return loc;
        World w = loc.getWorld();
        if (w.getEnvironment() != World.Environment.NETHER) return loc;

        double margin = borderMarginBlocks();

        // If this netherEntry came from older plugin versions, it might be on the roof.
        if (loc.getY() >= 128.0) loc.setY(120.0);

        Location clamped = clampInsideWorldBorder(loc, margin);
        Location safe = findSafeSpotNearInsideBorder(clamped, 160, margin);
        // Hard clamp below roof.
        if (safe != null && safe.getY() >= 128.0) {
            safe.setY(120.0);
            safe = findSafeSpotNearInsideBorder(safe, 160, margin);
        }
        return safe != null ? safe : loc;
    }

    void markDeadPlayerDiedInNether(UUID playerId) {
        if (!isNetherExileEnabled()) return;
        DeadRecord r = store.get(playerId);
        if (r == null) return;
        r.forceRespawnToNetherEntry = true;
        applyNetherDeathPenalty(r);
        store.save();
    }

    private void applyNetherDeathPenalty(DeadRecord r) {
        if (r == null) return;
        if (!plugin.getConfig().getBoolean("autobreak_enabled", false)) return;
        if (!plugin.getConfig().getBoolean("nether_death_penalty_enabled", true)) return;

        long penalty = parseDurationPlayTicks(plugin.getConfig().getString("nether_death_penalty", "5m"));
        if (penalty <= 0) return;

        long base = parseDurationPlayTicks(plugin.getConfig().getString("autobreak_after", "1h"));
        if (base <= 0) return;

        if (r.autoBreakRemainingPlayTicks <= 0) {
            r.autoBreakRemainingPlayTicks = base;
        }

        long updated = r.autoBreakRemainingPlayTicks + penalty;

        long cap = parseDurationPlayTicks(plugin.getConfig().getString("nether_death_max_remaining", "6h"));
        if (cap > 0) {
            updated = Math.min(updated, cap);
        }

        r.autoBreakRemainingPlayTicks = updated;
    }

    private Location computeReviveTarget(DeadRecord r, Player deadPlayer) {
        if (plugin.getConfig().getBoolean("revive_to_bed_enabled", false)) {
            Location bed = findRespawnLocationFor(r.playerId, deadPlayer);
            if (bed != null && bed.getWorld() != null && bed.getWorld().getEnvironment() == World.Environment.NORMAL) {
                return sanitizeOverworldReviveTarget(bed.clone());
            }
        }

        Location reviveBase;
        if (r.overworldDeath != null && r.overworldDeath.getWorld() != null) {
            reviveBase = r.overworldDeath.clone();
        } else if (r.netherDeath != null && r.netherDeath.getWorld() != null && r.netherDeath.getWorld().getEnvironment() == World.Environment.NETHER) {
            // Nether->Overworld coordinate conversion: x*8, z*8
            World overworld = Bukkit.getWorlds().stream()
                .filter(w -> w.getEnvironment() == World.Environment.NORMAL)
                .findFirst().orElse(null);
            if (overworld == null) return null;
            reviveBase = new Location(overworld, r.netherDeath.getX() * 8.0, r.netherDeath.getY(), r.netherDeath.getZ() * 8.0, r.netherDeath.getYaw(), r.netherDeath.getPitch());
        } else {
            return null;
        }

        if (reviveBase.getWorld() == null || reviveBase.getWorld().getEnvironment() != World.Environment.NORMAL) return reviveBase;
        World overworld = reviveBase.getWorld();

        FileConfiguration cfg = plugin.getConfig();
        double threshold = cfg.getDouble("near_entry_threshold_blocks", 64.0);
        int offset = cfg.getInt("revive_offset_blocks", 100);

        boolean nearEntry = false;
        if (deadPlayer != null && r.netherEntry != null && deadPlayer.getWorld().getEnvironment() == World.Environment.NETHER) {
            nearEntry = deadPlayer.getLocation().distance(r.netherEntry) < threshold;
        }

        if (!nearEntry) {
            Location safe = findSafeSpotNearInsideBorder(reviveBase.clone(), 64, borderMarginBlocks());
            return sanitizeOverworldReviveTarget(safe);
        }

        Location candidate = pickReviveOffsetInsideBorder(reviveBase.clone(), offset, borderMarginBlocks());
        candidate.setY(overworld.getHighestBlockYAt(candidate) + 1.0);
        Location safe = findSafeSpotNearInsideBorder(candidate, 160, borderMarginBlocks());
        return sanitizeOverworldReviveTarget(safe);
    }

    private Location findRespawnLocationFor(UUID playerId, Player deadPlayer) {
        try {
            if (deadPlayer != null) {
                Location l = deadPlayer.getRespawnLocation();
                if (l != null) return l;
            }
        } catch (Throwable ignored) {
        }

        try {
            Location l = Bukkit.getOfflinePlayer(playerId).getRespawnLocation();
            if (l != null) return l;
        } catch (Throwable ignored) {
        }

        return null;
    }

    private void applyReviveBuffs(Player p) {
        FileConfiguration cfg = plugin.getConfig();
        int seconds = cfg.getInt("regen_seconds", 30);
        int amp = cfg.getInt("regen_amplifier", 1);
        p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, seconds * 20, amp, false, true, true));
    }

    private static boolean sameBlock(Location a, Location b) {
        if (a == null || b == null) return false;
        if (a.getWorld() == null || b.getWorld() == null) return false;
        return Objects.equals(a.getWorld().getName(), b.getWorld().getName())
            && a.getBlockX() == b.getBlockX()
            && a.getBlockY() == b.getBlockY()
            && a.getBlockZ() == b.getBlockZ();
    }

    private static void removeBlockIfPresent(Location loc, Material expected) {
        if (loc == null || loc.getWorld() == null) return;
        Block b = loc.getBlock();
        if (b.getType() == expected) b.setType(Material.AIR, false);
    }

    private static ItemStack makePlayerHead(UUID ownerId, String ownerName) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD, 1);
        if (head.getItemMeta() instanceof SkullMeta meta) {
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(ownerId));
            head.setItemMeta(meta);
        }
        return head;
    }

    private static void clearInventoryHard(Player p) {
        p.getInventory().clear();
        p.getInventory().setArmorContents(null);
        p.getInventory().setItemInOffHand(null);
        p.setFireTicks(0);
    }

    // Attempts to find a safe standing spot (2-high air with solid block below).
    private static Location findSafeSpotNear(Location preferred, int radiusXZ) {
        if (preferred == null || preferred.getWorld() == null) return preferred;
        World w = preferred.getWorld();

        int startX = preferred.getBlockX();
        int startZ = preferred.getBlockZ();
        int prefY = preferred.getBlockY();

        int minY = w.getMinHeight() + 1;
        int maxY = w.getMaxHeight() - 2;
        if (w.getEnvironment() == World.Environment.NETHER) {
            // Avoid any location that could land the player on/above the Nether roof.
            // This keeps teleports below the bedrock roof layer.
            maxY = Math.min(maxY, 126);
        }

        for (int attempt = 0; attempt < 64; attempt++) {
            int dx = ThreadLocalRandom.current().nextInt(-radiusXZ, radiusXZ + 1);
            int dz = ThreadLocalRandom.current().nextInt(-radiusXZ, radiusXZ + 1);
            int x = startX + dx;
            int z = startZ + dz;

            // Try near preferred Y first, then expand.
            for (int dy = 0; dy <= 48; dy++) {
                int y1 = clamp(prefY - dy, minY, maxY);
                int y2 = clamp(prefY + dy, minY, maxY);
                Location l1 = tryStanding(w, x, y1, z);
                if (l1 != null) return l1;
                Location l2 = tryStanding(w, x, y2, z);
                if (l2 != null) return l2;
            }
        }
        return preferred;
    }

    private static Location tryStanding(World w, int x, int y, int z) {
        if (w.getEnvironment() == World.Environment.NETHER && y >= 128) return null;

        Block feet = w.getBlockAt(x, y, z);
        Block head = w.getBlockAt(x, y + 1, z);
        Block below = w.getBlockAt(x, y - 1, z);

        if (!feet.isPassable()) return null;
        if (!head.isPassable()) return null;
        if (feet.isLiquid() || head.isLiquid()) return null;
        if (below.isPassable()) return null;
        if (below.isLiquid()) return null;
        if (below.getType() == Material.LAVA) return null;
        if (below.getType() == Material.MAGMA_BLOCK) return null;
        if (below.getType() == Material.CAMPFIRE || below.getType() == Material.SOUL_CAMPFIRE) return null;
        if (below.getType() == Material.CACTUS) return null;

        return new Location(w, x + 0.5, y, z + 0.5);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static Location clampInsideWorldBorder(Location loc, double margin) {
        if (loc == null || loc.getWorld() == null) return loc;
        World w = loc.getWorld();
        WorldBorder border = w.getWorldBorder();
        if (border == null) return loc;
        if (border.isInside(loc)) return loc;

        Location c = border.getCenter();
        double half = border.getSize() / 2.0;
        if (half <= margin) {
            return new Location(w, c.getX(), loc.getY(), c.getZ(), loc.getYaw(), loc.getPitch());
        }

        double minX = c.getX() - half + margin;
        double maxX = c.getX() + half - margin;
        double minZ = c.getZ() - half + margin;
        double maxZ = c.getZ() + half - margin;

        double x = clampD(loc.getX(), minX, maxX);
        double z = clampD(loc.getZ(), minZ, maxZ);
        return new Location(w, x, loc.getY(), z, loc.getYaw(), loc.getPitch());
    }

    private static double clampD(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private Location sanitizeOverworldReviveTarget(Location target) {
        if (target == null || target.getWorld() == null) return target;
        World w = target.getWorld();
        if (w.getEnvironment() != World.Environment.NORMAL) return target;

        // Always re-sanitize at the moment of teleport in case the border has changed since we computed it.
        double margin = borderMarginBlocks();
        Location inside = clampInsideWorldBorder(target, margin);
        // Ensure Y is sane for the clamped X/Z (this is just a hint for the search).
        int hintY = w.getHighestBlockYAt(inside.getBlockX(), inside.getBlockZ()) + 1;
        inside.setY(clamp(hintY, w.getMinHeight() + 1, w.getMaxHeight() - 2));

        // Force a true "standing" location: not mid-air, not in/over lava.
        Location safe = findSafeSpotNearInsideBorder(inside, 256, margin);
        return clampInsideWorldBorder(safe, margin);
    }

    private void postTeleportBorderCorrection(Player p) {
        if (p == null || p.getWorld() == null) return;
        if (p.getWorld().getEnvironment() != World.Environment.NORMAL) return;

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            World w = p.getWorld();
            WorldBorder b = w.getWorldBorder();
            if (b == null) return;
            if (b.isInside(p.getLocation())) return;

            Location corrected = sanitizeOverworldReviveTarget(p.getLocation());
            p.teleport(corrected, PlayerTeleportEvent.TeleportCause.PLUGIN);
        });
    }

    private static Location pickReviveOffsetInsideBorder(Location death, int desiredOffset, double margin) {
        if (death == null || death.getWorld() == null) return death;
        World w = death.getWorld();
        if (w.getEnvironment() != World.Environment.NORMAL) return death;

        WorldBorder border = w.getWorldBorder();
        int offset = Math.max(1, desiredOffset);

        // Try random directions. If none fit, shrink the radius.
        for (int shrink = 0; shrink < 8; shrink++) {
            int r = Math.max(1, offset - (shrink * (offset / 8 + 1)));
            for (int attempt = 0; attempt < 24; attempt++) {
                double angle = ThreadLocalRandom.current().nextDouble(0.0, Math.PI * 2.0);
                double dx = Math.cos(angle) * r;
                double dz = Math.sin(angle) * r;
                Location cand = death.clone().add(dx, 0, dz);
                cand = clampInsideWorldBorder(cand, margin);
                if (border == null) return cand;
                if (border.isInside(cand)) return cand;
            }
        }

        return clampInsideWorldBorder(death.clone(), margin);
    }

    private static Location findSafeSpotNearInsideBorder(Location preferred, int radiusXZ, double borderMargin) {
        if (preferred == null || preferred.getWorld() == null) return preferred;
        World w = preferred.getWorld();
        WorldBorder border = w.getWorldBorder();
        if (border == null) return findSafeSpotNear(preferred, radiusXZ);

        Location clamped = clampInsideWorldBorder(preferred, borderMargin);

        Location c = border.getCenter();
        double half = border.getSize() / 2.0;
        double minX = c.getX() - half + borderMargin;
        double maxX = c.getX() + half - borderMargin;
        double minZ = c.getZ() - half + borderMargin;
        double maxZ = c.getZ() + half - borderMargin;

        int startX = clamped.getBlockX();
        int startZ = clamped.getBlockZ();
        int prefY = clamped.getBlockY();

        int minY = w.getMinHeight() + 1;
        int maxY = w.getMaxHeight() - 2;
        if (w.getEnvironment() == World.Environment.NETHER) {
            maxY = Math.min(maxY, 126);
        }

        for (int attempt = 0; attempt < 96; attempt++) {
            int dx = ThreadLocalRandom.current().nextInt(-radiusXZ, radiusXZ + 1);
            int dz = ThreadLocalRandom.current().nextInt(-radiusXZ, radiusXZ + 1);
            int x = startX + dx;
            int z = startZ + dz;

            if (x + 0.5 < minX || x + 0.5 > maxX) continue;
            if (z + 0.5 < minZ || z + 0.5 > maxZ) continue;

            for (int dy = 0; dy <= 48; dy++) {
                int y1 = clamp(prefY - dy, minY, maxY);
                int y2 = clamp(prefY + dy, minY, maxY);

                Location l1 = tryStanding(w, x, y1, z);
                if (l1 != null && border.isInside(l1)) return l1;

                Location l2 = tryStanding(w, x, y2, z);
                if (l2 != null && border.isInside(l2)) return l2;
            }
        }

        // Last resort: try the clamped column (scan for a safe standing spot).
        int fx = clamped.getBlockX();
        int fz = clamped.getBlockZ();
        int startY2 = clamp(w.getHighestBlockYAt(fx, fz) + 1, minY, maxY);
        Location column = findSafeInColumn(w, fx, startY2, fz, minY, maxY, border);
        if (column != null) return column;

        // Final fallback: world spawn (also scanned for safety), clamped inside border.
        Location spawn = clampInsideWorldBorder(w.getSpawnLocation(), borderMargin);
        int sx = spawn.getBlockX();
        int sz = spawn.getBlockZ();
        int startY3 = clamp(w.getHighestBlockYAt(sx, sz) + 1, minY, maxY);
        Location spawnSafe = findSafeInColumn(w, sx, startY3, sz, minY, maxY, border);
        if (spawnSafe != null) return spawnSafe;

        // If everything fails, return a clamped location (may still be unsafe, but inside border).
        return spawn;
    }

    private static Location findSafeInColumn(World w, int x, int startY, int z, int minY, int maxY, WorldBorder border) {
        // Scan down first (most common), then up a bit if needed.
        for (int dy = 0; dy <= 128; dy++) {
            int y1 = clamp(startY - dy, minY, maxY);
            Location l1 = tryStanding(w, x, y1, z);
            if (l1 != null && (border == null || border.isInside(l1))) return l1;

            int y2 = clamp(startY + dy, minY, maxY);
            Location l2 = tryStanding(w, x, y2, z);
            if (l2 != null && (border == null || border.isInside(l2))) return l2;
        }
        return null;
    }
}
