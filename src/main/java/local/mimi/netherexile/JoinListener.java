package local.mimi.netherexile;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

final class JoinListener implements Listener {
    private final DeadService deadService;

    JoinListener(DeadService deadService) {
        this.deadService = deadService;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        deadService.onJoin(e.getPlayer());
    }
}
