# NetherExile (Paper 1.21.x)

NetherExile is a Paper plugin that turns death into a temporary "Nether exile".

By default, exile starts when a player dies in the Overworld: a lodestone + their head is placed at/near the death location, they respawn in the Nether, and cannot leave until revived (by breaking the lodestone/head marker, by auto-break timeout, or by lava flowing onto the marker).

Optionally, if `nethernetherdeath_enabled=true`, exile can also start from a player's first death in the Nether.

## Features

- **Death marker**
  - Places a **lodestone** and **player head** at the death location.
  - If the player dies in the air, the marker is placed on the **ground below**.
  - If the player dies in lava, the marker is placed **on top of the lava surface**.
- **Nether exile**
  - On respawn, the player is teleported to the Nether at the **Overworld-equivalent** location (`x/8`, `z/8`), searching for a safe position and avoiding the Nether roof.
  - Exiled players cannot leave the Nether (portals are extinguished; teleporting to other dimensions is blocked).
  - If an exiled player dies in the Nether, they are sent back to their original Nether entry location and shown a "no escape" message (if enabled).
- **Nether -> Nether exile (optional)**
  - If `nethernetherdeath_enabled=true`, a player's first death in the Nether also starts the exile.
  - Their lodestone/head marker is placed at/near the Nether death location (same air/lava placement rules).
  - On respawn, they are sent back to where they last entered the Nether via portal (Overworld -> Nether).
- **Revival**
  - Breaking the lodestone or head revives the player.
  - The breaker receives the dead player's head item.
  - Lightning strikes the lodestone, and the revived player is struck with lightning on return to the Overworld.
  - Revived players get **30s Regeneration**.
  - Anti-grief: if the dead player stayed near their Nether entry point, they are revived ~**100 blocks away** from the original death spot (random direction).
- **Marker rules**
  - Dead players cannot break their own lodestone/head marker to revive themselves.
- **Break Lodestone With Arrow (optional)**
  - If `breakwitharrow_enabled=true`, shooting the lodestone with an arrow can trigger revival.
- **World border safety**
  - Revive teleports are clamped inside the world border and moved to a safe standing spot.
- **Auto-break (optional)**
  - If nobody breaks the marker after a timeout, the plugin auto-breaks it and revives the player.
  - Timers are based on **in-game time while the player is online** (so logging out does not advance the timer).
  - Optional **progressive** mode: each new exile can **double** that player's timeout.
- **Skeleton mode (optional)**
  - Dead players spawning in the Nether are equipped with a skeleton skull named with death coords and time.

## Commands

All commands are under `/netherexile`.

- `/netherexile help`
- `/netherexile status`
- `/netherexile enable`
- `/netherexile disable`
  - Disabling clears current "dead" state (markers are not removed).
- `/netherexile revive <player>`
  - Revives a currently-dead player (same effect as breaking their marker).
- `/netherexile autobreak <status|on|off|set> [duration]`
  - Duration supports: `s`, `m`, `h`, `d` (examples: `60s`, `30m`, `1h`, `1d`)
- `/netherexile progressive <status|on|off|cap> [value]`
  - Progressive (doubling) controls, including optional max cap (via `cap`). Only meaningful when auto-break is enabled.
- `/netherexile netherpenalty <status|on|off|set|cap> [value]`
  - Optional: add time to the remaining auto-break timer when a dead player dies in the Nether.
- `/netherexile messages <on|off>`
  - Toggle player-facing messages (trap/revive/etc).
- `/netherexile bedreturn <on|off>`
  - If enabled, revived players return to their bed/respawn point when available.
- `/netherexile skeleton <on|off>`
  - Toggle skeleton helmet feature.
- `/netherexile nethernetherdeath <on|off>`
  - If enabled, a player's first death in the Nether can start the exile too.
- `/netherexile breakwitharrow <on|off>`
  - If enabled, shooting a lodestone with an arrow can trigger revival.

## Permissions

- `netherexile.toggle` (default: op)
- `netherexile.revive` (default: op)
- `netherexile.autobreak` (default: op)

## Configuration

`plugins/NetherExile/config.yml` (defaults are in `src/main/resources/config.yml`):

- `netherexile_enabled`: master enable/disable
- `messages_enabled`: player-facing messages
- `revive_to_bed_enabled`: revive to bed/respawn location if available
- `autobreak_enabled`: enable auto-break timeout
- `autobreak_after`: base timeout (e.g. `1h`)
- `autobreak_progressive_enabled`: double timeout each new exile
- `autobreak_progressive_max`: optional max cap for progressive doubling (e.g. `12h`, or `off`)
- `nether_death_penalty_enabled`: add time when a dead player dies in the Nether (optional)
- `nether_death_penalty`: how much time to add per Nether death (e.g. `5m`)
- `nether_death_max_remaining`: cap on remaining time after penalties (e.g. `6h`, or `off`)
- `skeleton_enabled`: equip skeleton skull in Nether for dead players
- `nethernetherdeath_enabled`: start exile on first Nether death too
- `breakwitharrow_enabled`: allow breaking lodestone by shooting it with an arrow
- `near_entry_threshold_blocks`: controls anti-grief "revive 100 blocks away" behavior
- `revive_offset_blocks`: the revive offset distance when near-entry
- `regen_seconds`, `regen_amplifier`: revival buff
- `portal_extinguish_radius`: how far to search around a dead player for a portal block to extinguish
- `portal_extinguish_max_blocks`: max connected portal blocks to remove (safety cap)

## Data files

In `plugins/NetherExile/`:

- `dead.yml`: current exiled player state (marker locations, nether entry, etc.)
- `history.yml`: per-player progressive timeout history (used when `autobreak_progressive_enabled=true`)
- `entries.yml`: last known Nether portal entry locations (used by `nethernetherdeath_enabled`)

## Build

This project uses the Gradle wrapper.

```powershell
.\gradlew build
```

Output jar:

- `build/libs/netherexile-1.0.0.jar`

## Install

1. Put the jar into your server's `plugins/` folder.
2. Start the server once to generate `plugins/NetherExile/config.yml`.
3. Configure as desired and restart/reload.

## Notes / Known behavior

- Marker placement replaces blocks where it is placed (for lava deaths it places above lava rather than replacing lava).
- Auto-break countdown only progresses while the dead player is online (in-game time).
- If your server runs in offline mode, player skin rendering for heads may be inconsistent depending on client/cache behavior.
