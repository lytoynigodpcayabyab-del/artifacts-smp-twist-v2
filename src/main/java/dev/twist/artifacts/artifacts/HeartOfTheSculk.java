package dev.twist.artifacts.artifacts;

import dev.twist.artifacts.TwistPlugin;
import dev.twist.artifacts.artifact.Artifact;
import dev.twist.artifacts.util.Util;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.world.GenericGameEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * Heart of the Sculk (HELD).
 * Passive:   kills in the dark (light level 4 or below) restore 2 hearts and 1 hunger shank.
 * Ability 1: silent shockwave - Darkness + steals 4 hearts (true damage) from everything within 10 blocks (120s).
 * Ability 2: Blindspot Tether - 5s: you are silent (also to sculk sensors) and 30% of damage you take goes to the target (120s).
 */
public final class HeartOfTheSculk extends Artifact implements Listener {

    private record Tether(UUID target, BukkitTask task) {}

    private final Map<UUID, Tether> tethers = new HashMap<>();

    public HeartOfTheSculk(TwistPlugin plugin) {
        super(plugin, "heart_of_the_sculk", Material.ECHO_SHARD, 1010);
    }

    @Override public String displayName() { return "<gradient:#006064:#00e5ff><b>Heart of the Sculk</b></gradient>"; }

    @Override public List<String> lore() {
        return List.of(
                "<gray>Something deep below is still beating.",
                "",
                "<dark_aqua>▪ <gray>Kills in the dark heal 2 hearts + 1 shank",
                "<dark_aqua>▪ <gold>F: <gray>Silent shockwave, steals 4 hearts (120s)",
                "<dark_aqua>▪ <gold>Shift+F: <gray>Blindspot Tether (120s)");
    }

    @Override public PassiveMode passiveMode() { return PassiveMode.HELD; }
    @Override public boolean hasAbilities() { return true; }
    @Override public int cooldown1Seconds() { return cfgInt("ability1-cooldown", 120); }
    @Override public int cooldown2Seconds() { return cfgInt("ability2-cooldown", 120); }

    // ------------------------------------------------------------------ passive
    @EventHandler(ignoreCancelled = true)
    public void onKill(EntityDeathEvent e) {
        Player killer = e.getEntity().getKiller();
        if (killer == null || !plugin.artifacts().isHeld(killer, this)) return;
        if (killer.getLocation().getBlock().getLightLevel() > 4) return;
        Util.heal(killer, 4.0);                                              // 2 hearts
        killer.setFoodLevel(Math.min(20, killer.getFoodLevel() + 2));        // 1 shank
        killer.getWorld().spawnParticle(Particle.SCULK_SOUL, killer.getLocation().add(0, 1, 0), 12, 0.4, 0.6, 0.4, 0.02);
    }

    // ------------------------------------------------------------------ ability 1
    @Override
    public boolean onAbility1(Player p) {
        double r = cfgDouble("shockwave-radius", 10.0);
        double steal = cfgDouble("steal-hp", 8.0);   // 4 hearts, true damage
        int dark = cfgInt("darkness-seconds", 10) * 20;

        List<LivingEntity> targets = Util.enemies(p, r);
        // silent: no sound at all
        Location o = p.getLocation();
        for (int i = 0; i < 72; i++) {
            double a = Math.PI * 2 * i / 72.0;
            for (double d = 2; d <= r; d += 2) {
                Util.dust(p.getWorld(), o.clone().add(Math.cos(a) * d, 0.3, Math.sin(a) * d), 5, 5, 8, 1.6f, 1, 0);
            }
        }
        if (targets.isEmpty()) return true;   // wave still released (and cooldown spent), nobody caught in it

        double stolen = 0;
        for (LivingEntity le : targets) {
            stolen += Math.min(le.getHealth(), steal);
            le.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, dark, 0, true, false, true));
            Util.trueDamage(le, steal, p);
        }
        Util.heal(p, stolen);
        msg(p, "<dark_aqua>The sculk feeds.");
        return true;
    }

    // ------------------------------------------------------------------ ability 2
    @Override
    public boolean onAbility2(Player p) {
        if (tethers.containsKey(p.getUniqueId())) return false;
        LivingEntity t = Util.aimedTarget(p, cfgDouble("tether-range", 25.0), false);
        if (t == null) { msg(p, "<gray>No target in sight."); return false; }

        int total = cfgInt("tether-seconds", 5) * 20;
        p.setSilent(true);
        BukkitTask task = new BukkitRunnable() {
            int ticks = 0;
            @Override public void run() {
                ticks += 2;
                if (!p.isOnline() || p.isDead() || t.isDead() || t.getWorld() != p.getWorld()
                        || t.getLocation().distanceSquared(p.getLocation()) > 1600 || ticks > total) {
                    endTether(p);
                    return;
                }
                Util.line(p.getWorld(), p.getLocation().add(0, 0.2, 0), t.getLocation().add(0, 0.2, 0), 5, 20, 25, 1.0f, 0.8);
            }
        }.runTaskTimer(plugin, 0L, 2L);
        tethers.put(p.getUniqueId(), new Tether(t.getUniqueId(), task));
        msg(p, "<dark_aqua>You slip into their shadow.");
        return true;
    }

    private void endTether(Player p) {
        Tether t = tethers.remove(p.getUniqueId());
        if (t == null) return;
        t.task().cancel();
        p.setSilent(false);
    }

    /** 30% of the damage the tethered caster takes is redirected (as true damage) to the target. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Tether th = tethers.get(p.getUniqueId());
        if (th == null) return;
        Entity target = Bukkit.getEntity(th.target());
        if (!(target instanceof LivingEntity le) || le.isDead()) return;
        double share = e.getFinalDamage() * 0.3;
        e.setDamage(e.getDamage() * 0.7);
        Util.trueDamage(le, share, p);
    }

    /** Muted: no vibrations for sculk sensors / wardens while tethered. */
    @EventHandler(ignoreCancelled = true)
    public void onGameEvent(GenericGameEvent e) {
        if (e.getEntity() instanceof Player p && tethers.containsKey(p.getUniqueId())) e.setCancelled(true);
    }

    @Override public void onPlayerGone(Player p) { endTether(p); }

    @Override public void onDisable() {
        for (UUID id : new ArrayList<>(tethers.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) endTether(p);
        }
        tethers.clear();
    }
}
