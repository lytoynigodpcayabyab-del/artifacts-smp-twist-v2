package dev.twist.artifacts.artifacts;

import dev.twist.artifacts.TwistPlugin;
import dev.twist.artifacts.artifact.Artifact;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.List;

/**
 * [Custom idea #1] Tidal Heart (INVENTORY).
 * Passive: Water Breathing + Dolphin's Grace.
 * Active:  "Tidal Surge" - a shockwave that hurls nearby creatures away, slows them, and douses the caster.
 */
public final class TidalHeart extends Artifact {

    public TidalHeart(TwistPlugin plugin) {
        super(plugin, "tidal_heart", Material.HEART_OF_THE_SEA, 1003);
    }

    @Override public String displayName() { return "<gradient:#00e5ff:#0277bd><b>Tidal Heart</b></gradient>"; }

    @Override public List<String> lore() {
        return List.of(
                "<gray>It beats in time with the tides.",
                "",
                "<aqua>▪ <gray>Carry: Water Breathing, Dolphin's Grace",
                "<aqua>▪ <gold>Right-click: <gray>Tidal Surge - blast enemies",
                "<gray>  away and extinguish yourself");
    }

    @Override public PassiveMode passiveMode() { return PassiveMode.INVENTORY; }

    @Override public List<PassiveEffect> passiveEffects() {
        return List.of(new PassiveEffect(PotionEffectType.WATER_BREATHING, 0),
                       new PassiveEffect(PotionEffectType.DOLPHINS_GRACE, 0));
    }

    @Override public boolean hasActiveAbility() { return true; }
    @Override public int cooldownSeconds() { return cfgInt("cooldown-seconds", 25); }

    @Override
    public boolean onUse(Player player, ItemStack item) {
        double r = cfgDouble("radius", 8.0);
        double knockback = cfgDouble("knockback", 1.4);
        Location origin = player.getLocation();

        for (Entity e : player.getNearbyEntities(r, r, r)) {
            if (!isValidTarget(player, e)) continue;
            Vector dir = e.getLocation().toVector().subtract(origin.toVector());
            dir.setY(0);
            if (dir.lengthSquared() < 0.01) dir = new Vector(1, 0, 0); else dir.normalize();
            e.setVelocity(dir.multiply(knockback).setY(0.5));
            ((LivingEntity) e).addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 1));
        }
        player.setFireTicks(0);

        // Expanding-ring visual (single burst, 48 points).
        for (int i = 0; i < 48; i++) {
            double a = (Math.PI * 2 * i) / 48.0;
            player.getWorld().spawnParticle(Particle.SPLASH,
                    origin.getX() + Math.cos(a) * r, origin.getY() + 1, origin.getZ() + Math.sin(a) * r,
                    4, 0.2, 0.3, 0.2, 0.0);
        }
        player.getWorld().playSound(origin, Sound.ENTITY_GENERIC_SPLASH, 1.5f, 0.7f);
        msg(player, "<aqua>Tidal Surge!");
        return true;
    }
}
