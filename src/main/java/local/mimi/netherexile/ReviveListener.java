package local.mimi.netherexile;

import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

final class ReviveListener implements Listener {
    private final DeadService deadService;

    ReviveListener(DeadService deadService) {
        this.deadService = deadService;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Material t = e.getBlock().getType();
        if (t != Material.LODESTONE
            && t != Material.PLAYER_HEAD
            && t != Material.PLAYER_WALL_HEAD) {
            return;
        }

        // Dead players must not be able to break their own marker.
        java.util.UUID owner = deadService.findMarkerOwner(e.getBlock().getLocation());
        if (owner != null && e.getPlayer() != null && owner.equals(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
            e.setDropItems(false);
            // Message is sent by the service when they attempt to revive themselves.
            deadService.reviveByBreakingMarker(e.getPlayer(), e.getBlock().getLocation());
            return;
        }

        boolean revived = deadService.reviveByBreakingMarker(e.getPlayer(), e.getBlock().getLocation());
        if (revived) {
            // Prevent double drops; we handle the head drop ourselves on revive.
            e.setDropItems(false);
        }
    }
}
