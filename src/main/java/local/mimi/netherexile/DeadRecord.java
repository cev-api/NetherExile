package local.mimi.netherexile;

import org.bukkit.Location;

import java.util.UUID;

final class DeadRecord {
    final UUID playerId;

    // Overworld death marker
    Location overworldDeath;    // precise-ish death location
    // Nether death marker (used when nethernetherdeath_enabled starts the exile from a Nether death)
    Location netherDeath;
    Location lodestoneBlock;    // block location (x,y,z) where lodestone was placed
    Location skullBlock;        // block location (x,y,z) where player head was placed

    // Nether imprisonment
    Location netherEntry;       // where they first spawned in Nether due to death
    boolean startedFromNetherDeath;

    // If revived while offline, perform on join
    Location pendingReviveOverworldTarget;
    boolean pendingReviveLightning;

    // If a dead player dies in the Nether, force their respawn back to netherEntry.
    boolean forceRespawnToNetherEntry;

    // For auto-break scheduling across restarts.
    long markedDeadAtEpochMillis;

    // Auto-break is based on in-game playtime (Statistic.PLAY_ONE_MINUTE ticks).
    // Only decremented while the player is online.
    long autoBreakRemainingPlayTicks;
    int lastSeenPlayTicks;

    DeadRecord(UUID playerId) {
        this.playerId = playerId;
    }
}
