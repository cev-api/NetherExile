package local.mimi.netherexile;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;

final class NetherEntryListener implements Listener {
    private final EntryStore entryStore;

    NetherEntryListener(EntryStore entryStore) {
        this.entryStore = entryStore;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        Location from = e.getFrom();
        Location to = e.getTo();
        if (from == null || to == null) return;
        if (from.getWorld() == null || to.getWorld() == null) return;

        if (e.getCause() != PlayerTeleportEvent.TeleportCause.NETHER_PORTAL) return;
        if (from.getWorld().getEnvironment() != World.Environment.NORMAL) return;
        if (to.getWorld().getEnvironment() != World.Environment.NETHER) return;

        // Record where they entered the Nether (near the portal exit).
        entryStore.setLastNetherPortalEntry(e.getPlayer().getUniqueId(), to.clone());
        entryStore.save();
    }
}
