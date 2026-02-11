package local.mimi.netherexile;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;

final class DeadStore {
    private final JavaPlugin plugin;
    private final File file;
    private final Map<UUID, DeadRecord> dead = new HashMap<>();

    DeadStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "dead.yml");
    }

    Map<UUID, DeadRecord> all() {
        return dead;
    }

    DeadRecord get(UUID id) {
        return dead.get(id);
    }

    void put(DeadRecord rec) {
        dead.put(rec.playerId, rec);
    }

    void remove(UUID id) {
        dead.remove(id);
    }

    void load() {
        dead.clear();
        if (!file.exists()) return;

        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yml.getConfigurationSection("dead");
        if (root == null) return;

        for (String key : root.getKeys(false)) {
            UUID id;
            try {
                id = UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                continue;
            }

            ConfigurationSection s = root.getConfigurationSection(key);
            if (s == null) continue;

            DeadRecord r = new DeadRecord(id);
            r.overworldDeath = readLoc(s.getConfigurationSection("overworldDeath"));
            r.netherDeath = readLoc(s.getConfigurationSection("netherDeath"));
            r.lodestoneBlock = readLoc(s.getConfigurationSection("lodestoneBlock"));
            r.skullBlock = readLoc(s.getConfigurationSection("skullBlock"));
            r.netherEntry = readLoc(s.getConfigurationSection("netherEntry"));
            r.startedFromNetherDeath = s.getBoolean("startedFromNetherDeath", false);
            r.pendingReviveOverworldTarget = readLoc(s.getConfigurationSection("pendingReviveTarget"));
            r.pendingReviveLightning = s.getBoolean("pendingReviveLightning", false);
            r.forceRespawnToNetherEntry = s.getBoolean("forceRespawnToNetherEntry", false);
            r.markedDeadAtEpochMillis = s.getLong("markedDeadAtEpochMillis", 0L);
            r.autoBreakRemainingPlayTicks = s.getLong("autoBreakRemainingPlayTicks", 0L);
            r.lastSeenPlayTicks = s.getInt("lastSeenPlayTicks", 0);

            // If the worlds are missing, keep record but it will be a no-op until worlds load
            dead.put(id, r);
        }
    }

    void save() {
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().warning("Failed to create plugin data folder: " + plugin.getDataFolder());
            return;
        }

        YamlConfiguration yml = new YamlConfiguration();
        ConfigurationSection root = yml.createSection("dead");

        for (var e : dead.entrySet()) {
            ConfigurationSection s = root.createSection(e.getKey().toString());
            DeadRecord r = e.getValue();

            writeLoc(s.createSection("overworldDeath"), r.overworldDeath);
            writeLoc(s.createSection("netherDeath"), r.netherDeath);
            writeLoc(s.createSection("lodestoneBlock"), r.lodestoneBlock);
            writeLoc(s.createSection("skullBlock"), r.skullBlock);
            writeLoc(s.createSection("netherEntry"), r.netherEntry);
            s.set("startedFromNetherDeath", r.startedFromNetherDeath);
            writeLoc(s.createSection("pendingReviveTarget"), r.pendingReviveOverworldTarget);
            s.set("pendingReviveLightning", r.pendingReviveLightning);
            s.set("forceRespawnToNetherEntry", r.forceRespawnToNetherEntry);
            s.set("markedDeadAtEpochMillis", r.markedDeadAtEpochMillis);
            s.set("autoBreakRemainingPlayTicks", r.autoBreakRemainingPlayTicks);
            s.set("lastSeenPlayTicks", r.lastSeenPlayTicks);
        }

        try {
            yml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed to save dead.yml: " + ex.getMessage());
        }
    }

    private static Location readLoc(ConfigurationSection s) {
        if (s == null) return null;
        String worldName = s.getString("world");
        if (worldName == null || worldName.isEmpty()) return null;
        World w = Bukkit.getWorld(worldName);
        if (w == null) return null;
        double x = s.getDouble("x");
        double y = s.getDouble("y");
        double z = s.getDouble("z");
        float yaw = (float) s.getDouble("yaw", 0.0);
        float pitch = (float) s.getDouble("pitch", 0.0);
        return new Location(w, x, y, z, yaw, pitch);
    }

    private static void writeLoc(ConfigurationSection s, Location loc) {
        if (loc == null) {
            s.set("world", null);
            return;
        }
        s.set("world", loc.getWorld().getName());
        s.set("x", loc.getX());
        s.set("y", loc.getY());
        s.set("z", loc.getZ());
        s.set("yaw", loc.getYaw());
        s.set("pitch", loc.getPitch());
    }
}
