package local.mimi.netherexile;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public final class NetherExilePlugin extends JavaPlugin {
    private DeadStore deadStore;
    private TimerHistoryStore historyStore;
    private EntryStore entryStore;
    private DeadService deadService;
    private org.bukkit.scheduler.BukkitTask tickTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.deadStore = new DeadStore(this);
        this.deadStore.load();

        this.historyStore = new TimerHistoryStore(this);
        this.historyStore.load();

        this.entryStore = new EntryStore(this);
        this.entryStore.load();

        this.deadService = new DeadService(this, deadStore, historyStore, entryStore);

        var pm = getServer().getPluginManager();
        pm.registerEvents(new DeathListener(deadService), this);
        pm.registerEvents(new PortalListener(deadService), this);
        pm.registerEvents(new ReviveListener(deadService), this);
        pm.registerEvents(new JoinListener(deadService), this);
        pm.registerEvents(new LavaFlowListener(deadService), this);
        pm.registerEvents(new NetherEntryListener(entryStore), this);
        pm.registerEvents(new ArrowBreakListener(deadService), this);

        NetherExileCommand cmd = new NetherExileCommand(this, deadService);
        Objects.requireNonNull(getCommand("netherexile"), "netherexile command missing from plugin.yml")
            .setExecutor(cmd);
        Objects.requireNonNull(getCommand("netherexile"), "netherexile command missing from plugin.yml")
            .setTabCompleter(cmd);

        // Ensure Nether exists (most servers do, but avoids NPEs)
        World nether = Bukkit.getWorlds().stream()
            .filter(w -> w.getEnvironment() == World.Environment.NETHER)
            .findFirst().orElse(null);
        if (nether == null) {
            getLogger().warning("No Nether world loaded. Dead players cannot be imprisoned until a Nether is available.");
        }

        deadService.rescheduleAllAutoBreak();

        // Tick loop for in-game-time based timers and periodic persistence.
        this.tickTask = getServer().getScheduler().runTaskTimer(this, deadService::tick, 20L, 20L);
    }

    @Override
    public void onDisable() {
        if (tickTask != null) {
            try {
                tickTask.cancel();
            } catch (Throwable ignored) {
            }
            tickTask = null;
        }
        if (deadStore != null) deadStore.save();
        if (historyStore != null) historyStore.save();
        if (entryStore != null) entryStore.save();
    }
}
