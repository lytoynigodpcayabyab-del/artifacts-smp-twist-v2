package dev.twist.artifacts.artifacts;

import dev.twist.artifacts.TwistPlugin;
import dev.twist.artifacts.artifact.Artifact;
import dev.twist.artifacts.util.Util;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.List;

/**
 * Star of Invisibility (HELD).
 * Passive:   Invisibility, mobs can't target you. When your health drops to 3 hearts, nearby enemies get
 *            Darkness for 3s (60s cooldown).
 * Ability 1: Swift III for 10s (90s cooldown).
 * Ability 2: nearby players get Slowness II + Weakness I for 30s (120s cooldown).
 */
public final class StarOfInvisibility extends Artifact implements Listener {

    public StarOfInvisibility(TwistPlugin plugin) {
        super(plugin, "star_of_invisibility", Material.NETHER_STAR, 1001);
    }

    @Override public String displayName() { return "<gradient:#b388ff:#6a1b9a><b>Star of Invisibility</b></gradient>"; }

    @Override public List<String> lore() {
        return List.of(
                "<gray>A fragment of a star that never shone.",
                "",
                "<dark_purple>▪ <gray>Hold to vanish, monsters lose sight of you",
                "<dark_purple>▪ <gray>At 3 hearts: Darkness on nearby enemies (60s)",
                "<dark_purple>▪ <gold>F: <gray>Swift III for 10s (90s)",
                "<dark_purple>▪ <gold>Shift+F: <gray>Slowness II + Weakness I on",
                "<gray>  nearby players for 30s (120s)");
    }

    @Override public PassiveMode passiveMode() { return PassiveMode.HELD; }

    @Override public List<PassiveEffect> passiveEffects() {
        return List.of(new PassiveEffect(PotionEffectType.INVISIBILITY, 0));
    }

    @Override public boolean hasAbilities() { return true; }
    @Override public int cooldown1Seconds() { return cfgInt("ability1-cooldown", 90); }
    @Override public int cooldown2Seconds() { return cfgInt("ability2-cooldown", 120); }

    @Override
    public boolean onAbility1(Player p) {
        int ticks = cfgInt("swift-seconds", 10) * 20;
        p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, ticks, 2, true, true, true)); // Swift III
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1.6f);
        msg(p, "<light_purple>Swift III!");
        return true;
    }

    @Override
    public boolean onAbility2(Player p) {
        double radius = cfgDouble("radius", 12.0);
        int ticks = cfgInt("debuff-seconds", 30) * 20;
        List<Player> targets = Util.enemyPlayers(p, radius);
        if (targets.isEmpty()) { msg(p, "<gray>No players nearby."); return false; }
        for (Player t : targets) {
            t.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, ticks, 1, true, true, true));   // Slowness II
            t.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, ticks, 0, true, true, true));   // Weakness I
        }
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_WITHER_SHOOT, 0.8f, 1.4f);
        msg(p, "<light_purple>Starlight drains your enemies!");
        return true;
    }

    /** Passive: crossing down to 3 hearts blinds the surroundings. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p) || !plugin.artifacts().isHeld(p, this)) return;
        double threshold = cfgDouble("passive-health-threshold", 6.0);   // 6 HP = 3 hearts
        double before = p.getHealth();
        double after = before - e.getFinalDamage();
        if (before <= threshold || after > threshold || after <= 0) return;
        if (onCd(p, "p")) return;

        setCd(p, "p", cfgInt("passive-cooldown", 60));
        int ticks = cfgInt("darkness-seconds", 3) * 20;
        for (Player t : Util.enemyPlayers(p, cfgDouble("radius", 12.0))) {
            t.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, ticks, 0, true, false, true));
        }
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, 1f, 0.8f);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMobTarget(EntityTargetLivingEntityEvent e) {
        if (e.getTarget() instanceof Player p && plugin.artifacts().isHeld(p, this)) e.setCancelled(true);
    }
}
