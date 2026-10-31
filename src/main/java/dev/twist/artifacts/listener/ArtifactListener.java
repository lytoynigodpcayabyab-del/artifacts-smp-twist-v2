package dev.twist.artifacts.listener;

import dev.twist.artifacts.TwistPlugin;
import dev.twist.artifacts.artifact.Artifact;
import dev.twist.artifacts.artifact.ArtifactManager;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

/** Core listener: right-click abilities + exploit protection + cleanup. Registered in TwistPlugin#onEnable. */
public final class ArtifactListener implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final TwistPlugin plugin;

    public ArtifactListener(TwistPlugin plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() == null) return;
        Action action = e.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

        ArtifactManager manager = plugin.artifacts();
        ItemStack item = e.getItem();
        Artifact artifact = manager.identify(item);
        if (artifact == null) return;

        Player player = e.getPlayer();

        // Let players still open chests / doors normally (vanilla: item isn't used on interactable blocks).
        Block clicked = e.getClickedBlock();
        if (clicked != null && clicked.getType().isInteractable() && !player.isSneaking()) return;

        // The base item must never do its vanilla thing (throw pearl, place lightning rod, light fire...).
        if (artifact.denyVanillaUse()) e.setUseItemInHand(Event.Result.DENY);

        if (!artifact.hasActiveAbility()) return;
        if (!player.hasPermission(TwistPlugin.USE_PERMISSION)) return;

        long left = plugin.cooldowns().remainingMillis(player.getUniqueId(), artifact.id());
        if (left > 0) {
            long secs = (long) Math.ceil(left / 1000.0);
            player.sendActionBar(MM.deserialize("<red>" + MM.stripTags(artifact.displayName()) + " <gray>on cooldown: <white>" + secs + "s"));
            return;
        }

        if (artifact.onUse(player, item)) {
            plugin.cooldowns().start(player.getUniqueId(), artifact.id(), artifact.cooldownSeconds());
        }
    }

    /** F = ability 1, Shift+F = ability 2 (only for artifacts that have the two-ability system). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        ArtifactManager m = plugin.artifacts();
        Artifact a = m.identify(e.getOffHandItem());      // was in main hand
        if (a == null || !a.hasAbilities()) a = m.identify(e.getMainHandItem());   // was in off hand
        if (a == null || !a.hasAbilities()) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        m.triggerWith(p, a, p.isSneaking() ? 2 : 1);
    }

    /** Artifacts can't be used as crafting ingredients. */
    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent e) {
        for (ItemStack stack : e.getInventory().getMatrix()) {
            if (plugin.artifacts().identify(stack) != null) {
                e.getInventory().setResult(null);
                return;
            }
        }
    }

    /** Dispensers can't fire / place the base item either. */
    @EventHandler(ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent e) {
        if (plugin.artifacts().identify(e.getItem()) != null) e.setCancelled(true);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        plugin.artifacts().playerGone(e.getEntity());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        plugin.artifacts().playerGone(p);
        plugin.domains().handleQuit(p);
        plugin.cooldowns().purgeIfExpired(p.getUniqueId());
    }
}
