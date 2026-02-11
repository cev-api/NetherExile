package local.mimi.netherexile;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.*;

final class NetherExileCommand implements CommandExecutor, TabCompleter {
    private final JavaPlugin plugin;
    private final DeadService deadService;

    NetherExileCommand(JavaPlugin plugin, DeadService deadService) {
        this.plugin = plugin;
        this.deadService = deadService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender, label);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "enable" -> {
                return handleToggle(sender, label, true);
            }
            case "disable" -> {
                return handleToggle(sender, label, false);
            }
            case "status" -> {
                info(sender, "enabled=" + deadService.isNetherExileEnabled());
                info(sender, "messages_enabled=" + plugin.getConfig().getBoolean("messages_enabled", true)
                    + " skeleton_enabled=" + plugin.getConfig().getBoolean("skeleton_enabled", false));
                info(sender, "revive_to_bed_enabled=" + plugin.getConfig().getBoolean("revive_to_bed_enabled", false));
                info(sender, "nethernetherdeath_enabled=" + plugin.getConfig().getBoolean("nethernetherdeath_enabled", false)
                    + " breakwitharrow_enabled=" + plugin.getConfig().getBoolean("breakwitharrow_enabled", false));
                info(sender, "autobreak_enabled=" + plugin.getConfig().getBoolean("autobreak_enabled", false)
                    + " autobreak_after=" + plugin.getConfig().getString("autobreak_after", "1h")
                    + " progressive_enabled=" + plugin.getConfig().getBoolean("autobreak_progressive_enabled", false)
                    + " progressive_max=" + plugin.getConfig().getString("autobreak_progressive_max", "off"));
                info(sender, "nether_death_penalty_enabled=" + plugin.getConfig().getBoolean("nether_death_penalty_enabled", false)
                    + " nether_death_penalty=" + plugin.getConfig().getString("nether_death_penalty", "5m")
                    + " nether_death_max_remaining=" + plugin.getConfig().getString("nether_death_max_remaining", "6h"));
                info(sender, "border_margin_blocks=" + plugin.getConfig().getDouble("border_margin_blocks", 32.0));
                return true;
            }
            case "help" -> {
                sendHelp(sender, label);
                return true;
            }
            case "messages" -> {
                return handleToggleFlag(sender, label, "messages_enabled", "netherexile.toggle", args);
            }
            case "bedreturn" -> {
                return handleToggleFlag(sender, label, "revive_to_bed_enabled", "netherexile.toggle", args);
            }
            case "skeleton" -> {
                return handleToggleFlag(sender, label, "skeleton_enabled", "netherexile.toggle", args);
            }
            case "nethernetherdeath" -> {
                return handleToggleFlag(sender, label, "nethernetherdeath_enabled", "netherexile.toggle", args);
            }
            case "breakwitharrow" -> {
                return handleToggleFlag(sender, label, "breakwitharrow_enabled", "netherexile.toggle", args);
            }
            case "progressive" -> {
                return handleProgressive(sender, label, args);
            }
            case "progressivecap" -> {
                // Backward-compatible alias for older command shape.
                return handleProgressiveCap(sender, label, args);
            }
            case "netherpenalty", "penalty" -> {
                return handleNetherPenalty(sender, label, args);
            }
            case "revive" -> {
                return handleRevive(sender, label, args);
            }
            case "autobreak" -> {
                return handleAutoBreak(sender, label, args);
            }
            default -> {
                sendHelp(sender, label);
                return true;
            }
        }
    }

    private void sendHelp(CommandSender sender, String label) {
        Component title = prefix().append(Component.text("Commands", NamedTextColor.WHITE).decorate(TextDecoration.BOLD));
        sender.sendMessage(title);
        sender.sendMessage(line("/" + label + " help", "Show this help"));
        sender.sendMessage(line("/" + label + " status", "Show status"));
        sender.sendMessage(line("/" + label + " enable", "Enable NetherExile"));
        sender.sendMessage(line("/" + label + " disable", "Disable NetherExile (clears dead state)"));
        sender.sendMessage(line("/" + label + " messages <on|off>", "Toggle player messages"));
        sender.sendMessage(line("/" + label + " bedreturn <on|off>", "Revive to bed/respawn point if available"));
        sender.sendMessage(line("/" + label + " skeleton <on|off>", "Toggle skeleton helmet for dead players"));
        sender.sendMessage(line("/" + label + " nethernetherdeath <on|off>", "Start exile on first Nether death"));
        sender.sendMessage(line("/" + label + " breakwitharrow <on|off>", "Allow shooting lodestone to revive"));
        sender.sendMessage(line("/" + label + " revive <player>", "Revive a dead player"));
        sender.sendMessage(line("/" + label + " autobreak <status|on|off|set> [duration]", "Auto-break markers after a timeout"));
        sender.sendMessage(line("/" + label + " progressive <status|on|off|cap> [value]", "Progressive doubling controls"));
        sender.sendMessage(line("/" + label + " netherpenalty <status|on|off|set|cap> [value]", "Nether-death penalty time (optional)"));
    }

    private boolean handleToggle(CommandSender sender, String label, boolean enabled) {
        if (!sender.hasPermission("netherexile.toggle")) {
            err(sender, "No permission.");
            return true;
        }

        deadService.setNetherExileEnabled(enabled);
        ok(sender, "NetherExile enabled=" + deadService.isNetherExileEnabled());
        if (!enabled) {
            warn(sender, "Disabling clears all current 'dead' state (markers are not removed).");
        }
        return true;
    }

    private boolean handleRevive(CommandSender sender, String label, String[] args) {
        if (!sender.hasPermission("netherexile.revive")) {
            err(sender, "No permission.");
            return true;
        }
        if (args.length != 2) {
            err(sender, "Usage: /" + label + " revive <player>");
            return true;
        }

        String name = args[1];
        UUID targetId = null;

        Player online = Bukkit.getPlayerExact(name);
        if (online != null) targetId = online.getUniqueId();

        if (targetId == null) {
            for (UUID id : deadService.getDeadPlayerIds()) {
                OfflinePlayer op = Bukkit.getOfflinePlayer(id);
                if (op.getName() != null && op.getName().equalsIgnoreCase(name)) {
                    targetId = id;
                    break;
                }
            }
        }

        if (targetId == null) {
            err(sender, "Unknown player: " + name);
            return true;
        }
        if (!deadService.isDead(targetId)) {
            warn(sender, name + " is not dead.");
            return true;
        }

        Player breaker = (sender instanceof Player p) ? p : null;
        boolean ok = deadService.reviveByCommand(breaker, targetId);
        if (!ok) {
            err(sender, "Failed to revive " + name + ".");
            return true;
        }

        ok(sender, "Revived " + name + ".");
        return true;
    }

    private boolean handleAutoBreak(CommandSender sender, String label, String[] args) {
        if (!sender.hasPermission("netherexile.autobreak")) {
            err(sender, "No permission.");
            return true;
        }

        if (args.length < 2) {
            err(sender, "Usage: /" + label + " autobreak <status|on|off|set> [duration]");
            info(sender, "Examples: /" + label + " autobreak on 1h | /" + label + " autobreak set 30m | /" + label + " autobreak off");
            return true;
        }

        String sub = args[1].toLowerCase(Locale.ROOT);
        boolean enabled = plugin.getConfig().getBoolean("autobreak_enabled", false);
        String durStr = plugin.getConfig().getString("autobreak_after", "1h");

        switch (sub) {
            case "status" -> {
                info(sender, "autobreak_enabled=" + enabled + " autobreak_after=" + durStr);
                return true;
            }
            case "on" -> {
                plugin.getConfig().set("autobreak_enabled", true);
                if (args.length >= 3) {
                    plugin.getConfig().set("autobreak_after", args[2]);
                }
                plugin.saveConfig();
                deadService.rescheduleAllAutoBreak();
                ok(sender, "Auto-break enabled. after=" + plugin.getConfig().getString("autobreak_after", "1h"));
                return true;
            }
            case "off" -> {
                plugin.getConfig().set("autobreak_enabled", false);
                plugin.saveConfig();
                deadService.rescheduleAllAutoBreak();
                ok(sender, "Auto-break disabled.");
                return true;
            }
            case "set" -> {
                if (args.length < 3) {
                    err(sender, "Usage: /" + label + " autobreak set <duration>");
                    info(sender, "Examples: 60s, 1m, 1h, 1d");
                    return true;
                }
                plugin.getConfig().set("autobreak_after", args[2]);
                plugin.saveConfig();
                deadService.rescheduleAllAutoBreak();
                ok(sender, "Auto-break duration set to " + args[2] + " (enabled=" + plugin.getConfig().getBoolean("autobreak_enabled", false) + ")");
                return true;
            }
            default -> {
                err(sender, "Unknown subcommand. Use: /" + label + " autobreak <status|on|off|set>");
                return true;
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String p = args[0].toLowerCase(Locale.ROOT);
            List<String> base = Arrays.asList(
                "help", "status", "enable", "disable",
                "messages", "bedreturn", "skeleton", "nethernetherdeath", "breakwitharrow",
                "revive", "autobreak", "progressive", "netherpenalty"
            );
            return prefixMatches(base, p);
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("revive")) {
            if (!sender.hasPermission("netherexile.revive")) return Collections.emptyList();
            if (args.length == 2) {
                String p = args[1].toLowerCase(Locale.ROOT);
                List<String> out = new ArrayList<>();
                for (UUID id : deadService.getDeadPlayerIds()) {
                    OfflinePlayer op = Bukkit.getOfflinePlayer(id);
                    String n = op.getName();
                    if (n == null) continue;
                    if (p.isEmpty() || n.toLowerCase(Locale.ROOT).startsWith(p)) out.add(n);
                }
                out.sort(String.CASE_INSENSITIVE_ORDER);
                return out;
            }
            return Collections.emptyList();
        }

        if (sub.equals("autobreak")) {
            if (!sender.hasPermission("netherexile.autobreak")) return Collections.emptyList();

            if (args.length == 2) {
                String p = args[1].toLowerCase(Locale.ROOT);
                return prefixMatches(Arrays.asList("status", "on", "off", "set"), p);
            }

            if (args.length == 3) {
                String ab = args[1].toLowerCase(Locale.ROOT);
                if (!ab.equals("on") && !ab.equals("set")) return Collections.emptyList();
                String p = args[2].toLowerCase(Locale.ROOT);
                return prefixMatches(Arrays.asList("60s", "1m", "5m", "15m", "30m", "1h", "6h", "12h", "1d"), p);
            }
        }

        if (sub.equals("messages") || sub.equals("bedreturn") || sub.equals("skeleton") || sub.equals("nethernetherdeath")
            || sub.equals("breakwitharrow")) {
            if (args.length == 2) {
                String p = args[1].toLowerCase(Locale.ROOT);
                return prefixMatches(Arrays.asList("on", "off"), p);
            }
            return Collections.emptyList();
        }

        if (sub.equals("progressive")) {
            if (!sender.hasPermission("netherexile.autobreak")) return Collections.emptyList();
            if (args.length == 2) {
                String p = args[1].toLowerCase(Locale.ROOT);
                return prefixMatches(Arrays.asList("status", "on", "off", "cap"), p);
            }
            if (args.length == 3 && args[1].equalsIgnoreCase("cap")) {
                String p = args[2].toLowerCase(Locale.ROOT);
                return prefixMatches(Arrays.asList("off", "0", "30m", "1h", "6h", "12h", "1d"), p);
            }
            return Collections.emptyList();
        }

        if (sub.equals("progressivecap")) {
            if (!sender.hasPermission("netherexile.autobreak")) return Collections.emptyList();
            if (args.length == 2) {
                String p = args[1].toLowerCase(Locale.ROOT);
                return prefixMatches(Arrays.asList("off", "0", "30m", "1h", "6h", "12h", "1d"), p);
            }
            return Collections.emptyList();
        }

        if (sub.equals("netherpenalty") || sub.equals("penalty")) {
            if (!sender.hasPermission("netherexile.autobreak")) return Collections.emptyList();

            if (args.length == 2) {
                String p = args[1].toLowerCase(Locale.ROOT);
                return prefixMatches(Arrays.asList("status", "on", "off", "set", "cap"), p);
            }
            if (args.length == 3) {
                String mode = args[1].toLowerCase(Locale.ROOT);
                if (mode.equals("set") || mode.equals("cap")) {
                    String p = args[2].toLowerCase(Locale.ROOT);
                    return prefixMatches(Arrays.asList("0", "off", "60s", "5m", "15m", "30m", "1h", "6h", "12h", "1d"), p);
                }
            }
            return Collections.emptyList();
        }

        return Collections.emptyList();
    }

    private static List<String> prefixMatches(List<String> base, String prefixLower) {
        List<String> out = new ArrayList<>();
        for (String s : base) {
            if (prefixLower.isEmpty() || s.toLowerCase(Locale.ROOT).startsWith(prefixLower)) out.add(s);
        }
        return out;
    }

    private static Component prefix() {
        return Component.text("[", NamedTextColor.DARK_GRAY)
            .append(Component.text("NetherExile", NamedTextColor.GOLD).decorate(TextDecoration.BOLD))
            .append(Component.text("] ", NamedTextColor.DARK_GRAY));
    }

    private static Component line(String cmd, String desc) {
        return Component.text("  ", NamedTextColor.DARK_GRAY)
            .append(Component.text(cmd, NamedTextColor.AQUA))
            .append(Component.text("  ", NamedTextColor.DARK_GRAY))
            .append(Component.text(desc, NamedTextColor.GRAY));
    }

    private static void ok(CommandSender s, String msg) {
        s.sendMessage(prefix().append(Component.text(msg, NamedTextColor.GREEN)));
    }

    private static void info(CommandSender s, String msg) {
        s.sendMessage(prefix().append(Component.text(msg, NamedTextColor.WHITE)));
    }

    private static void warn(CommandSender s, String msg) {
        s.sendMessage(prefix().append(Component.text(msg, NamedTextColor.YELLOW)));
    }

    private static void err(CommandSender s, String msg) {
        s.sendMessage(prefix().append(Component.text(msg, NamedTextColor.RED)));
    }

    private boolean handleToggleFlag(CommandSender sender, String label, String key, String perm, String[] args) {
        if (!sender.hasPermission(perm)) {
            err(sender, "No permission.");
            return true;
        }
        if (args.length != 2) {
            err(sender, "Usage: /" + label + " " + args[0] + " <on|off>");
            return true;
        }
        boolean enabled = args[1].equalsIgnoreCase("on");
        plugin.getConfig().set(key, enabled);
        plugin.saveConfig();
        if (key.equals("autobreak_progressive_enabled") || key.equals("autobreak_enabled") || key.equals("autobreak_after")) {
            deadService.rescheduleAllAutoBreak();
        }
        ok(sender, key + "=" + enabled);
        return true;
    }

    private boolean handleNetherPenalty(CommandSender sender, String label, String[] args) {
        if (!sender.hasPermission("netherexile.autobreak")) {
            err(sender, "No permission.");
            return true;
        }

        if (args.length < 2) {
            err(sender, "Usage: /" + label + " netherpenalty <status|on|off|set|cap> [value]");
            return true;
        }

        String sub = args[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> {
                info(sender, "nether_death_penalty_enabled=" + plugin.getConfig().getBoolean("nether_death_penalty_enabled", false)
                    + " nether_death_penalty=" + plugin.getConfig().getString("nether_death_penalty", "5m")
                    + " nether_death_max_remaining=" + plugin.getConfig().getString("nether_death_max_remaining", "6h"));
                return true;
            }
            case "on" -> {
                plugin.getConfig().set("nether_death_penalty_enabled", true);
                plugin.saveConfig();
                ok(sender, "nether_death_penalty_enabled=true");
                return true;
            }
            case "off" -> {
                plugin.getConfig().set("nether_death_penalty_enabled", false);
                plugin.saveConfig();
                ok(sender, "nether_death_penalty_enabled=false");
                return true;
            }
            case "set" -> {
                if (args.length < 3) {
                    err(sender, "Usage: /" + label + " penalty set <duration>");
                    info(sender, "Examples: 60s, 5m, 1h");
                    return true;
                }
                plugin.getConfig().set("nether_death_penalty", args[2]);
                plugin.saveConfig();
                ok(sender, "nether_death_penalty=" + args[2]);
                return true;
            }
            case "cap" -> {
                if (args.length < 3) {
                    err(sender, "Usage: /" + label + " penalty cap <duration|off>");
                    info(sender, "Examples: 6h, 1d, off");
                    return true;
                }
                plugin.getConfig().set("nether_death_max_remaining", args[2]);
                plugin.saveConfig();
                ok(sender, "nether_death_max_remaining=" + args[2]);
                return true;
            }
            default -> {
                err(sender, "Unknown subcommand. Use: /" + label + " penalty <status|on|off|set|cap>");
                return true;
            }
        }
    }

    private boolean handleProgressiveCap(CommandSender sender, String label, String[] args) {
        if (!sender.hasPermission("netherexile.autobreak")) {
            err(sender, "No permission.");
            return true;
        }
        if (args.length != 2) {
            err(sender, "Usage: /" + label + " progressivecap <duration|off>");
            info(sender, "Examples: 6h, 12h, 1d, off");
            return true;
        }

        plugin.getConfig().set("autobreak_progressive_max", args[1]);
        plugin.saveConfig();
        ok(sender, "autobreak_progressive_max=" + args[1]);
        return true;
    }

    private boolean handleProgressive(CommandSender sender, String label, String[] args) {
        if (!sender.hasPermission("netherexile.autobreak")) {
            err(sender, "No permission.");
            return true;
        }
        if (args.length < 2) {
            err(sender, "Usage: /" + label + " progressive <status|on|off|cap> [value]");
            return true;
        }

        String sub = args[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> {
                info(sender, "autobreak_progressive_enabled=" + plugin.getConfig().getBoolean("autobreak_progressive_enabled", false)
                    + " autobreak_progressive_max=" + plugin.getConfig().getString("autobreak_progressive_max", "off"));
                return true;
            }
            case "on" -> {
                boolean autobreak = plugin.getConfig().getBoolean("autobreak_enabled", false);
                if (!autobreak) {
                    err(sender, "Cannot enable progressive: autobreak_enabled is false.");
                    return true;
                }
                plugin.getConfig().set("autobreak_progressive_enabled", true);
                plugin.saveConfig();
                deadService.rescheduleAllAutoBreak();
                ok(sender, "autobreak_progressive_enabled=true");
                return true;
            }
            case "off" -> {
                plugin.getConfig().set("autobreak_progressive_enabled", false);
                plugin.saveConfig();
                deadService.rescheduleAllAutoBreak();
                ok(sender, "autobreak_progressive_enabled=false");
                return true;
            }
            case "cap" -> {
                if (args.length < 3) {
                    err(sender, "Usage: /" + label + " progressive cap <duration|off>");
                    info(sender, "Examples: 6h, 12h, 1d, off");
                    return true;
                }
                plugin.getConfig().set("autobreak_progressive_max", args[2]);
                plugin.saveConfig();
                ok(sender, "autobreak_progressive_max=" + args[2]);
                return true;
            }
            default -> {
                err(sender, "Unknown subcommand. Use: /" + label + " progressive <status|on|off|cap>");
                return true;
            }
        }
    }
}
