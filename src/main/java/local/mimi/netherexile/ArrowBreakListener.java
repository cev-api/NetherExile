package local.mimi.netherexile;

import org.bukkit.Material;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileHitEvent;

final class ArrowBreakListener implements Listener {
    private final DeadService deadService;

    ArrowBreakListener(DeadService deadService) {
        this.deadService = deadService;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHit(ProjectileHitEvent e) {
        if (!deadService.isNetherExileEnabled()) return;
        if (!deadService.isBreakWithArrowEnabled()) return;
        if (!(e.getEntity() instanceof Arrow)) return;
        if (e.getHitBlock() == null) return;
        if (e.getHitBlock().getType() != Material.LODESTONE) return;

        if (!(e.getEntity().getShooter() instanceof Player shooter)) return;

        deadService.reviveByBreakingMarker(shooter, e.getHitBlock().getLocation());
    }
}
