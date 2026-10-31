package dev.twist.artifacts.command;

import dev.twist.artifacts.TwistPlugin;
import dev.twist.artifacts.artifact.Artifact;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/** /artifact give <player> <artifact> [amount] | list | reload */
public final class ArtifactCommand implements TabExecutor {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final TwistPlugin plugin;

    public ArtifactCommand(TwistPlugin plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) { usage(sender); return true; }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "list" -> {
                sender.sendMessage(MM.deserialize("<gold>Artifacts:"));
                for (Artifact a : plugin.artifacts().all()) {
                    sender.sendMessage(MM.deserialize(" <gray>- <white>" + a.id() + " <dark_gray>(CMD " + a.customModelData() + ", " + a.material() + ")"));
                }
            }
            case "reload" -> {
                plugin.reloadAll();
                sender.sendMessage(MM.deserialize("<green>Twist Artifacts config reloaded."));
            }
            case "give" -> give(sender, args);
            case "resetcd" -> {
                if (sender instanceof Player p) {
                    plugin.cooldowns().clear(p.getUniqueId());
                    sender.sendMessage(MM.deserialize("<green>Your artifact cooldowns were reset."));
                }
            }
            default -> usage(sender);
        }
        return true;
    }

    private void give(CommandSender sender, String[] args) {
        if (args.length < 3) { usage(sender); return; }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) { sender.sendMessage(MM.deserialize("<red>Player not found: " + args[1])); return; }

        Artifact artifact = plugin.artifacts().get(args[2]);
        if (artifact == null) { sender.sendMessage(MM.deserialize("<red>Unknown artifact. Use /artifact list")); return; }

        int amount = 1;
        if (args.length >= 4) {
            try { amount = Math.max(1, Math.min(36, Integer.parseInt(args[3]))); }
            catch (NumberFormatException ex) { sender.sendMessage(MM.deserialize("<red>Amount must be a number.")); return; }
        }

        for (int i = 0; i < amount; i++) {
            ItemStack item = plugin.artifacts().createItem(artifact);
            // Artifacts never stack, so each call gets a fresh item; overflow drops at the player's feet.
            target.getInventory().addItem(item).values()
                    .forEach(left -> target.getWorld().dropItemNaturally(target.getLocation(), left));
        }
        sender.sendMessage(MM.deserialize("<green>Gave " + amount + "x " + artifact.id() + " to " + target.getName()));
    }

    private void usage(CommandSender s) {
        s.sendMessage(MM.deserialize("<yellow>/artifact give <player> <artifact> [amount]\n/artifact list\n/artifact reload"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) out.addAll(List.of("give", "list", "reload", "resetcd"));
        else if (args.length == 2 && args[0].equalsIgnoreCase("give"))
            Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
        else if (args.length == 3 && args[0].equalsIgnoreCase("give"))
            plugin.artifacts().all().forEach(a -> out.add(a.id()));
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(prefix));
        return out;
    }
}
