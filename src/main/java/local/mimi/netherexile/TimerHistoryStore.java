package local.mimi.netherexile;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Stores per-player progressive auto-break timeouts across revives.
 */
final class TimerHistoryStore {
    private final JavaPlugin plugin;
    private final File file;
    private final Map<UUID, Long> lastTimeoutPlayTicks = new HashMap<>();
    private final Map<UUID, Long> lastBasePlayTicks = new HashMap<>();

    TimerHistoryStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "history.yml");
    }

    long getLastTimeoutPlayTicks(UUID id) {
        return lastTimeoutPlayTicks.getOrDefault(id, 0L);
    }

    void setLastTimeoutPlayTicks(UUID id, long ticks) {
        if (ticks <= 0) {
            lastTimeoutPlayTicks.remove(id);
        } else {
            lastTimeoutPlayTicks.put(id, ticks);
        }
    }

    long getLastBasePlayTicks(UUID id) {
        return lastBasePlayTicks.getOrDefault(id, 0L);
    }

    void setLastBasePlayTicks(UUID id, long ticks) {
        if (ticks <= 0) {
            lastBasePlayTicks.remove(id);
        } else {
            lastBasePlayTicks.put(id, ticks);
        }
    }

    void load() {
        lastTimeoutPlayTicks.clear();
        lastBasePlayTicks.clear();
        if (!file.exists()) return;

        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection timeouts = yml.getConfigurationSection("timeouts");
        if (timeouts != null) {
            for (String key : timeouts.getKeys(false)) {
                try {
                    UUID id = UUID.fromString(key);
                    long v = timeouts.getLong(key, 0L);
                    if (v > 0) lastTimeoutPlayTicks.put(id, v);
                } catch (IllegalArgumentException ignored) {
                }
            }
        }

        ConfigurationSection bases = yml.getConfigurationSection("bases");
        if (bases != null) {
            for (String key : bases.getKeys(false)) {
                try {
                    UUID id = UUID.fromString(key);
                    long v = bases.getLong(key, 0L);
                    if (v > 0) lastBasePlayTicks.put(id, v);
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
    }

    void save() {
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().warning("Failed to create plugin data folder: " + plugin.getDataFolder());
            return;
        }

        YamlConfiguration yml = new YamlConfiguration();
        ConfigurationSection s = yml.createSection("timeouts");
        for (var e : lastTimeoutPlayTicks.entrySet()) {
            s.set(e.getKey().toString(), e.getValue());
        }

        ConfigurationSection b = yml.createSection("bases");
        for (var e : lastBasePlayTicks.entrySet()) {
            b.set(e.getKey().toString(), e.getValue());
        }

        try {
            yml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed to save history.yml: " + ex.getMessage());
        }
    }
}
