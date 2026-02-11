package local.mimi.netherexile;

import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

final class PortalListener implements Listener {
    private final DeadService deadService;

    PortalListener(DeadService deadService) {
        this.deadService = deadService;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent e) {
        if (!deadService.isNetherExileEnabled()) return;
        if (!deadService.isDead(e.getPlayer().getUniqueId())) return;

        // "Dead" players trying to use a portal will have it extinguished.
        deadService.extinguishNearbyPortals(e.getPlayer().getLocation(), deadService.portalExtinguishRadius());
        deadService.sendTrapMessage(e.getPlayer());
        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (!deadService.isNetherExileEnabled()) return;
        if (deadService.shouldBlockTeleport(e.getPlayer(), e.getFrom(), e.getTo(), e.getCause())) {
            // If they try to escape from Nether, block it.
            if (e.getFrom().getWorld() != null && e.getFrom().getWorld().getEnvironment() == World.Environment.NETHER) {
                deadService.extinguishNearbyPortals(e.getFrom(), deadService.portalExtinguishRadius());
            }
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        if (!deadService.isNetherExileEnabled()) return;
        if (e.getPlayer() == null) return;
        if (!deadService.isDead(e.getPlayer().getUniqueId())) return;

        // "Dead" players can't light portals (or any fire) while imprisoned in the Nether.
        if (e.getPlayer().getWorld().getEnvironment() == World.Environment.NETHER) {
            e.setCancelled(true);
        }
    }
}
