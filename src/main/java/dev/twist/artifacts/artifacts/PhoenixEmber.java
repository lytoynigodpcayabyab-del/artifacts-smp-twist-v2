package dev.twist.artifacts.artifacts;

import dev.twist.artifacts.TwistPlugin;
import dev.twist.artifacts.artifact.Artifact;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.List;

/**
 * [Custom idea #2] Phoenix Ember (INVENTORY).
 * Passive: Fire Resistance.
 * Active:  "Rebirth Ascent" - rocket skyward, glide down with Slow Falling, and set nearby foes ablaze.
 */
public final class PhoenixEmber extends Artifact {

    public PhoenixEmber(TwistPlugin plugin) {
        super(plugin, "phoenix_ember", Material.FIRE_CHARGE, 1004);
    }

    @Override public String displayName() { return "<gradient:#ff6f00:#d50000><b>Phoenix Ember</b></gradient>"; }

    @Override public List<String> lore() {
        return List.of(
                "<gray>It is never quite out.",
                "",
                "<red>▪ <gray>Carry: Fire Resistance",
                "<red>▪ <gold>Right-click: <gray>Rebirth Ascent - launch skyward,",
                "<gray>  float down, ignite nearby enemies");
    }

    @Override public PassiveMode passiveMode() { return PassiveMode.INVENTORY; }

    @Override public List<PassiveEffect> passiveEffects() {
        return List.of(new PassiveEffect(PotionEffectType.FIRE_RESISTANCE, 0));
    }

    @Override public boolean hasActiveAbility() { return true; }
    @Override public int cooldownSeconds() { return cfgInt("cooldown-seconds", 40); }

    @Override
    public boolean onUse(Player player, ItemStack item) {
        Location loc = player.getLocation();
        double r = cfgDouble("ignite-radius", 6.0);
        int fireTicks = (int) (cfgDouble("ignite-seconds", 5.0) * 20);

        for (Entity e : player.getNearbyEntities(r, r, r)) {
            if (isValidTarget(player, e)) e.setFireTicks(fireTicks);
        }

        player.setVelocity(player.getVelocity().setY(cfgDouble("launch-power", 1.6)));
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 200, 0, true, true, true));

        player.getWorld().spawnParticle(Particle.FLAME, loc.clone().add(0, 1, 0), 80, 1.0, 0.5, 1.0, 0.15);
        player.getWorld().playSound(loc, Sound.ENTITY_BLAZE_SHOOT, 1.2f, 0.8f);
        msg(player, "<gold>Rebirth Ascent!");
        return true;
    }
}
