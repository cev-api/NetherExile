package local.mimi.netherexile;

import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFromToEvent;

final class LavaFlowListener implements Listener {
    private final DeadService deadService;

    LavaFlowListener(DeadService deadService) {
        this.deadService = deadService;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {
        if (!deadService.isNetherExileEnabled()) return;
        if (e.getBlock().getType() != Material.LAVA) return;

        Material to = e.getToBlock().getType();
        if (to != Material.LODESTONE && to != Material.PLAYER_HEAD && to != Material.PLAYER_WALL_HEAD) return;

        // If lava is flowing onto a marker, treat it like the marker was broken: revive the player.
        // We don't cancel the flow; the lava can replace the block normally after revival.
        deadService.reviveByBreakingMarker(null, e.getToBlock().getLocation());
    }
}
