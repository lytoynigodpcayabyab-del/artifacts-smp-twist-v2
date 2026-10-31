package dev.twist.artifacts.artifacts;

import dev.twist.artifacts.TwistPlugin;
import dev.twist.artifacts.artifact.Artifact;
import dev.twist.artifacts.util.Util;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

/**
 * Deep Dark Chalice (HELD).
 * Passive:   over-healing / picking up XP at full health = stacking 5% damage reduction (max 20%) for 15s.
 * Ability 1: sacrifice 2 hearts (45s) for Absorption IV and 30% melee lifesteal for 10s (120s).
 * Ability 2: 4x4 pool of void slime - damages enemies, their "XP" flies to you and heals you (120s).
 */
public final class DeepDarkChalice extends Artifact implements Listener {

    private final Map<UUID, int[]> stacks = new HashMap<>();          // {count, expiryTick}
    private final Map<UUID, Integer> lifestealUntil = new HashMap<>();

    public DeepDarkChalice(TwistPlugin plugin) {
        super(plugin, "deep_dark_chalice", Material.DRAGON_BREATH, 1012);
    }

    @Override public String displayName() { return "<gradient:#1b5e20:#004d40><b>Deep Dark Chalice</b></gradient>"; }

    @Override public List<String> lore() {
        return List.of(
                "<gray>Whatever it holds, it is not water.",
                "",
                "<dark_green>▪ <gray>Over-healing / XP at full health: stacking",
                "<gray>  5% damage reduction (max 20%, 15s)",
                "<dark_green>▪ <gold>F: <gray>Sacrifice 2 hearts for Absorption IV and",
                "<gray>  30% lifesteal (120s)",
                "<dark_green>▪ <gold>Shift+F: <gray>Void slime pool (120s)");
    }

    @Override public PassiveMode passiveMode() { return PassiveMode.HELD; }
    @Override public boolean hasAbilities() { return true; }
    @Override public int cooldown1Seconds() { return cfgInt("ability1-cooldown", 120); }
    @Override public int cooldown2Seconds() { return cfgInt("ability2-cooldown", 120); }

    // ------------------------------------------------------------------ passive
    private void addStack(Player p) {
        int now = Bukkit.getCurrentTick();
        int[] s = stacks.computeIfAbsent(p.getUniqueId(), k -> new int[]{0, 0});
        if (s[1] < now) s[0] = 0;
        s[0] = Math.min(4, s[0] + 1);
        s[1] = now + cfgInt("passive-seconds", 15) * 20;
        p.getWorld().spawnParticle(Particle.SCULK_SOUL, p.getLocation().add(0, 1, 0), 6, 0.3, 0.5, 0.3, 0.01);
    }

    @EventHandler(ignoreCancelled = true)
    public void onRegain(EntityRegainHealthEvent e) {
        if (!(e.getEntity() instanceof Player p) || !plugin.artifacts().isHeld(p, this)) return;
        if (p.getHealth() + e.getAmount() > Util.maxHealth(p) + 0.01) addStack(p);
    }

    @EventHandler
    public void onXp(PlayerPickupExperienceEvent e) {
        Player p = e.getPlayer();
        if (!plugin.artifacts().isHeld(p, this)) return;
        if (p.getHealth() >= Util.maxHealth(p) - 0.01) addStack(p);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p) || !plugin.artifacts().isHeld(p, this)) return;
        int[] s = stacks.get(p.getUniqueId());
        if (s == null || s[1] < Bukkit.getCurrentTick() || s[0] <= 0) return;
        e.setDamage(e.getDamage() * (1.0 - 0.05 * s[0]));
    }

    // ------------------------------------------------------------------ ability 1
    @Override
    public boolean onAbility1(Player p) {
        if (Util.maxHealth(p) <= 8.0) { msg(p, "<red>You have too little life to sacrifice."); return false; }
        Util.tempAttr(plugin, p, Attribute.MAX_HEALTH, "chalice_sacrifice", -4.0,
                AttributeModifier.Operation.ADD_NUMBER, cfgInt("sacrifice-return-seconds", 45) * 20);
        int ticks = cfgInt("burst-seconds", 10) * 20;
        p.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, ticks, 3, true, true, true));   // Absorption IV
        lifestealUntil.put(p.getUniqueId(), Bukkit.getCurrentTick() + ticks);
        p.getWorld().spawnParticle(Particle.SCULK_SOUL, p.getLocation().add(0, 1, 0), 30, 0.5, 0.8, 0.5, 0.05);
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_PLAYER_BURP, 1f, 0.5f);
        msg(p, "<dark_green>You drink deep.");
        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLifesteal(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p) || e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        Integer until = lifestealUntil.get(p.getUniqueId());
        if (until == null) return;
        if (until < Bukkit.getCurrentTick()) { lifestealUntil.remove(p.getUniqueId()); return; }
        Util.heal(p, e.getFinalDamage() * cfgDouble("lifesteal", 0.30));
    }

    // ------------------------------------------------------------------ ability 2
    @Override
    public boolean onAbility2(Player p) {
        Block target = p.getTargetBlockExact((int) cfgDouble("pool-range", 20.0));
        if (target == null) { msg(p, "<gray>Aim at a block."); return false; }

        int x0 = target.getX() - 1, z0 = target.getZ() - 1;     // 4x4 area: x0..x0+3, z0..z0+3
        double y = target.getY() + 1.0;
        int total = cfgInt("pool-seconds", 10) * 20;
        double dmg = cfgDouble("pool-damage", 1.0);              // per 0.5s pulse
        double heal = cfgDouble("pool-heal-per-hit", 1.0);
        org.bukkit.World w = target.getWorld();

        w.playSound(target.getLocation(), Sound.ENTITY_GENERIC_SPLASH, 1f, 0.5f);
        new BukkitRunnable() {
            int ticks = 0;
            @Override public void run() {
                if (!p.isOnline() || ticks >= total) { cancel(); return; }
                for (int dx = 0; dx < 4; dx++) for (int dz = 0; dz < 4; dz++) {
                    Util.dust(w, new Location(w, x0 + dx + 0.5, y + 0.05, z0 + dz + 0.5), 20, 90, 40, 1.6f, 2, 0.25);
                }
                w.spawnParticle(Particle.ITEM_SLIME, x0 + 2.0, y + 0.2, z0 + 2.0, 6, 1.5, 0.1, 1.5, 0);

                for (Entity en : w.getNearbyEntities(new Location(w, x0 + 2.0, y, z0 + 2.0), 3.0, 2.5, 3.0)) {
                    if (!(en instanceof LivingEntity le) || !Util.validTarget(p, en)) continue;
                    if (en instanceof Player pl && Util.isAlly(p, pl)) continue;
                    Location l = en.getLocation();
                    if (l.getX() < x0 || l.getX() >= x0 + 4 || l.getZ() < z0 || l.getZ() >= z0 + 4
                            || Math.abs(l.getY() - y) > 2.0) continue;
                    le.damage(dmg, p);
                    // extra XP: the orb appears on the caster ("flies to you") and restores health
                    Util.line(w, l.clone().add(0, 1, 0), p.getLocation().add(0, 1, 0), 120, 255, 80, 0.8f, 0.8);
                    w.spawn(p.getLocation(), ExperienceOrb.class, o -> o.setExperience(1));
                    Util.heal(p, heal);
                }
                ticks += 10;
            }
        }.runTaskTimer(plugin, 0L, 10L);
        msg(p, "<dark_green>Void slime pools!");
        return true;
    }

    @Override public void onPlayerGone(Player p) {
        stacks.remove(p.getUniqueId());
        lifestealUntil.remove(p.getUniqueId());
    }
}
