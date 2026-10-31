package dev.twist.artifacts.artifacts;

import dev.twist.artifacts.TwistPlugin;
import dev.twist.artifacts.artifact.Artifact;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;

import java.util.Comparator;
import java.util.List;

/**
 * [Custom idea #3] Stormcaller's Idol (HELD).
 * Passive: Haste I + Jump Boost I while wielded.
 * Active:  "Smite" - strikes up to N nearest creatures with visual lightning plus direct damage.
 *          Uses strikeLightningEffect (cosmetic) + damage(), so there is no fire / block / charged-creeper grief.
 */
public final class StormcallerIdol extends Artifact {

    public StormcallerIdol(TwistPlugin plugin) {
        super(plugin, "stormcaller_idol", Material.LIGHTNING_ROD, 1005);
    }

    @Override public String displayName() { return "<gradient:#fff176:#5c6bc0><b>Stormcaller's Idol</b></gradient>"; }

    @Override public List<String> lore() {
        return List.of(
                "<gray>The sky answers to whoever holds it.",
                "",
                "<yellow>▪ <gray>Hold: Haste I, Jump Boost I",
                "<yellow>▪ <gold>Right-click: <gray>Smite the nearest foes",
                "<gray>  with bolts of lightning");
    }

    @Override public PassiveMode passiveMode() { return PassiveMode.HELD; }

    @Override public List<PassiveEffect> passiveEffects() {
        return List.of(new PassiveEffect(PotionEffectType.HASTE, 0),
                       new PassiveEffect(PotionEffectType.JUMP_BOOST, 0));
    }

    @Override public boolean hasActiveAbility() { return true; }
    @Override public int cooldownSeconds() { return cfgInt("cooldown-seconds", 45); }

    @Override
    public boolean onUse(Player player, ItemStack item) {
        double r = cfgDouble("radius", 16.0);
        int max = cfgInt("max-targets", 5);
        double damage = cfgDouble("damage", 8.0);

        List<LivingEntity> targets = player.getNearbyEntities(r, r, r).stream()
                .filter(e -> isValidTarget(player, e))
                .map(e -> (LivingEntity) e)
                .sorted(Comparator.comparingDouble((LivingEntity e) -> e.getLocation().distanceSquared(player.getLocation())))
                .limit(max)
                .toList();

        if (targets.isEmpty()) {
            msg(player, "<gray>The storm finds no targets.");
            return false;   // no cooldown spent
        }
        for (LivingEntity t : targets) {
            t.getWorld().strikeLightningEffect(t.getLocation());
            t.damage(damage, player);
        }
        msg(player, "<yellow>Smite!");
        return true;
    }
}
