package dev.twist.artifacts.artifacts;

import dev.twist.artifacts.TwistPlugin;
import dev.twist.artifacts.artifact.Artifact;
import dev.twist.artifacts.util.Util;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.List;

/**
 * Void Capsule (INVENTORY).
 * Passive:   Strength I + Regeneration I while carried.
 * Ability 1: Void Domain - a 10-wide dome of sculk/deepslate blocks (120s cooldown), Swift III for the caster.
 * Ability 2: Void Pull - drags nearby enemies toward you for a few seconds, damaging them.
 */
public final class VoidCapsule extends Artifact {

    public VoidCapsule(TwistPlugin plugin) {
        super(plugin, "void_capsule", Material.ENDER_PEARL, 1002);
    }

    @Override public String displayName() { return "<gradient:#7b1fa2:#311b92><b>Void Capsule</b></gradient>"; }

    @Override public List<String> lore() {
        return List.of(
                "<gray>A sliver of the End, sealed in glass.",
                "",
                "<dark_purple>▪ <gray>Carry: Strength I, Regeneration I",
                "<dark_purple>▪ <gold>F: <gray>Void Domain - a sculk dome nobody can",
                "<gray>  enter or leave, Swift III for you (120s)",
                "<dark_purple>▪ <gold>Shift+F: <gray>Void Pull - drag enemies to you");
    }

    @Override public PassiveMode passiveMode() { return PassiveMode.INVENTORY; }

    @Override public List<PassiveEffect> passiveEffects() {
        return List.of(new PassiveEffect(PotionEffectType.STRENGTH, 0),
                       new PassiveEffect(PotionEffectType.REGENERATION, 0));
    }

    @Override public boolean hasAbilities() { return true; }
    @Override public int cooldown1Seconds() { return cfgInt("ability1-cooldown", 120); }
    @Override public int cooldown2Seconds() { return cfgInt("ability2-cooldown", 45); }

    @Override
    public boolean onAbility1(Player player) {
        double radius = cfgDouble("dome-radius", 10.0);
        int durationTicks = (int) (cfgDouble("dome-seconds", 40.0) * 20);

        if (!plugin.domains().create(player, radius, durationTicks)) {
            msg(player, "<red>Your Void Domain is already active.");
            return false;
        }
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, durationTicks, 2, true, true, true)); // Swift III
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_END_PORTAL_SPAWN, 1f, 0.6f);
        msg(player, "<dark_purple>Void Domain unleashed!");
        return true;
    }

    @Override
    public boolean onAbility2(Player p) {
        double radius = cfgDouble("pull-radius", 7.5);
        int seconds = cfgInt("pull-seconds", 5);
        double dps = cfgDouble("pull-damage-per-second", 1.0);

        if (Util.enemyPlayers(p, radius).isEmpty()) {
            msg(p, "<gray>No enemies in range.");
            return false;
        }
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 0.5f);

        new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                if (!p.isOnline() || p.isDead() || t >= seconds * 20) { cancel(); return; }
                Location me = p.getLocation();
                for (Player v : Util.enemyPlayers(p, radius)) {
                    Vector to = me.toVector().subtract(v.getLocation().toVector());
                    if (to.lengthSquared() > 2.25) v.setVelocity(to.normalize().multiply(0.45));
                    if (t % 20 == 0) v.damage(dps, p);
                    Util.line(p.getWorld(), me.clone().add(0, 1, 0), v.getLocation().add(0, 1, 0), 90, 20, 160, 0.8f, 1.0);
                }
                p.getWorld().spawnParticle(Particle.PORTAL, me.clone().add(0, 1, 0), 12, 0.6, 0.8, 0.6, 0.4);
                t += 2;
            }
        }.runTaskTimer(plugin, 0L, 2L);
        msg(p, "<dark_purple>Void Pull!");
        return true;
    }
}
