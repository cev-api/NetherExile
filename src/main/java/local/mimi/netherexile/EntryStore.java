package local.mimi.netherexile;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Stores the last known Nether portal entry location for players (Overworld -> Nether).
 */
final class EntryStore {
    private final JavaPlugin plugin;
    private final File file;
    private final Map<UUID, Location> lastNetherPortalEntry = new HashMap<>();

    EntryStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "entries.yml");
    }

    Location getLastNetherPortalEntry(UUID id) {
        return lastNetherPortalEntry.get(id);
    }

    void setLastNetherPortalEntry(UUID id, Location loc) {
        if (loc == null) {
            lastNetherPortalEntry.remove(id);
        } else {
            lastNetherPortalEntry.put(id, loc.clone());
        }
    }

    void load() {
        lastNetherPortalEntry.clear();
        if (!file.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection s = yml.getConfigurationSection("entries");
        if (s == null) return;

        for (String key : s.getKeys(false)) {
            UUID id;
            try {
                id = UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                continue;
            }
            ConfigurationSection l = s.getConfigurationSection(key);
            Location loc = readLoc(l);
            if (loc != null) lastNetherPortalEntry.put(id, loc);
        }
    }

    void save() {
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().warning("Failed to create plugin data folder: " + plugin.getDataFolder());
            return;
        }

        YamlConfiguration yml = new YamlConfiguration();
        ConfigurationSection s = yml.createSection("entries");
        for (var e : lastNetherPortalEntry.entrySet()) {
            writeLoc(s.createSection(e.getKey().toString()), e.getValue());
        }
        try {
            yml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed to save entries.yml: " + ex.getMessage());
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
