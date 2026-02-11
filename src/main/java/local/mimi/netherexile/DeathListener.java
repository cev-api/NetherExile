package local.mimi.netherexile;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

final class DeathListener implements Listener {
    private final DeadService deadService;

    DeathListener(DeadService deadService) {
        this.deadService = deadService;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent e) {
        if (!deadService.isNetherExileEnabled()) return;
        Location death = e.getPlayer().getLocation();
        World w = death.getWorld();
        if (w == null) return;

        // If this is a normal player dying in the Nether and nethernetherdeath is enabled, start the prison.
        if (w.getEnvironment() == World.Environment.NETHER && !deadService.isDead(e.getPlayer().getUniqueId())
            && deadService.isNetherNetherDeathEnabled()) {
            deadService.markDeadOnNetherFirstDeath(e.getPlayer(), death);
            return;
        }

        // If a dead player dies in the Nether, force them back to their nether entry on respawn.
        if (w.getEnvironment() == World.Environment.NETHER && deadService.isDead(e.getPlayer().getUniqueId())) {
            deadService.removeSkeletonSkullFromDropsOnNetherDeath(e);
            deadService.markDeadPlayerDiedInNether(e.getPlayer().getUniqueId());
            deadService.sendNoEscapeMessage(e.getPlayer());
            return;
        }

        // Only start the prison on Overworld deaths.
        if (w.getEnvironment() != World.Environment.NORMAL) return;

        deadService.markDeadOnOverworldDeath(e.getPlayer(), death);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent e) {
        deadService.onRespawn(e.getPlayer(), e.getRespawnLocation(), e);
    }
}
