package dev.twist.artifacts.artifacts;

import dev.twist.artifacts.TwistPlugin;
import dev.twist.artifacts.artifact.Artifact;
import dev.twist.artifacts.util.Util;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * The Umbral Abyss (HELD).
 * Passive:   sneaking on the ground = Night Vision + shadow outlines of players/mobs within 16 blocks.
 * Ability 1: Burrow - vanish into the ground leaving a black trail, burst out in a particle explosion (90s).
 *            Press Shift to pop out early.
 * Ability 2: Abyssal Grasp - pull the aimed player to you, then hurl them 10 blocks away for 4 hearts true damage (120s).
 */
public final class UmbralAbyss extends Artifact implements Listener {

    private final Set<UUID> nightVision = new HashSet<>();
    private final Map<UUID, BukkitTask> burrowed = new HashMap<>();

    public UmbralAbyss(TwistPlugin plugin) {
        super(plugin, "umbral_abyss", Material.BLACK_DYE, 1008);
    }

    @Override public String displayName() { return "<gradient:#212121:#4a148c><b>The Umbral Abyss</b></gradient>"; }

    @Override public List<String> lore() {
        return List.of(
                "<gray>The dark looks back.",
                "",
                "<dark_gray>▪ <gray>Sneak on the ground: Night Vision and",
                "<gray>  shadow outlines of everything within 16 blocks",
                "<dark_gray>▪ <gold>F: <gray>Burrow under the ground, burst out (90s)",
                "<dark_gray>▪ <gold>Shift+F: <gray>Pull your target, hurl them away (120s)");
    }

    @Override public PassiveMode passiveMode() { return PassiveMode.HELD; }
    @Override public boolean hasAbilities() { return true; }
    @Override public int cooldown1Seconds() { return cfgInt("ability1-cooldown", 90); }
    @Override public int cooldown2Seconds() { return cfgInt("ability2-cooldown", 120); }

    // ------------------------------------------------------------------ passive
    @Override
    @SuppressWarnings("deprecation")
    public void onPassiveTick(Player holder) {
        UUID id = holder.getUniqueId();
        boolean active = holder.isSneaking() && holder.isOnGround() && !burrowed.containsKey(id);
        if (!active) {
            if (nightVision.remove(id)) {
                PotionEffect cur = holder.getPotionEffect(PotionEffectType.NIGHT_VISION);
                if (cur != null && cur.getDuration() <= 240) holder.removePotionEffect(PotionEffectType.NIGHT_VISION);
            }
            return;
        }
        nightVision.add(id);
        holder.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, 240, 0, true, false, true));

        double r = cfgDouble("detect-radius", 16.0);
        boolean glow = cfgBool("detect-glow", true);   // Glowing is the only way to see through walls without packets
        Location o = holder.getLocation();
        for (Entity e : holder.getNearbyEntities(r, r, r)) {
            if (!(e instanceof LivingEntity le) || e instanceof ArmorStand) continue;
            if (le.getLocation().distanceSquared(o) > r * r) continue;
            outline(holder, le);
            if (glow) le.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 25, 0, true, false, false));
        }
    }

    private void outline(Player viewer, LivingEntity le) {
        BoundingBox bb = le.getBoundingBox();
        double[] xs = {bb.getMinX(), bb.getMaxX()};
        double[] ys = {bb.getMinY(), bb.getMaxY()};
        double[] zs = {bb.getMinZ(), bb.getMaxZ()};
        for (double x : xs) for (double y : ys) for (double z : zs) {
            Util.dustTo(viewer, new Location(le.getWorld(), x, y, z), 40, 0, 70, 1.0f);
        }
    }

    // ------------------------------------------------------------------ ability 1: burrow
    @Override
    public boolean onAbility1(Player p) {
        UUID id = p.getUniqueId();
        if (burrowed.containsKey(id)) return false;
        int maxTicks = cfgInt("burrow-seconds", 6) * 20;

        p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, maxTicks + 10, 0, true, false, false));
        p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, maxTicks + 10, 1, true, false, true));
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 0.5f);
        p.getWorld().spawnParticle(Particle.SQUID_INK, p.getLocation().add(0, 0.5, 0), 40, 0.5, 0.3, 0.5, 0.1);

        BukkitTask t = new BukkitRunnable() {
            int ticks = 0;
            @Override public void run() {
                if (!p.isOnline() || p.isDead()) { endBurrow(p, false); return; }
                ticks += 2;
                Location l = p.getLocation();
                Util.dust(p.getWorld(), l.clone().add(0, 0.15, 0), 8, 8, 8, 2.2f, 8, 0.35);
                p.getWorld().spawnParticle(Particle.SQUID_INK, l.clone().add(0, 0.1, 0), 2, 0.3, 0.05, 0.3, 0.01);
                if (ticks >= maxTicks) endBurrow(p, true);
            }
        }.runTaskTimer(plugin, 0L, 2L);
        burrowed.put(id, t);
        msg(p, "<dark_gray>You sink into the abyss... <gray>(Shift to surface)");
        return true;
    }

    private void endBurrow(Player p, boolean pop) {
        BukkitTask t = burrowed.remove(p.getUniqueId());
        if (t == null) return;
        t.cancel();
        p.removePotionEffect(PotionEffectType.INVISIBILITY);
        PotionEffect speed = p.getPotionEffect(PotionEffectType.SPEED);
        if (speed != null && speed.getAmplifier() == 1) p.removePotionEffect(PotionEffectType.SPEED);
        if (!pop || !p.isOnline() || p.isDead()) return;

        Location l = p.getLocation();
        double radius = cfgDouble("pop-radius", 4.0);
        double dmg = cfgDouble("pop-damage", 4.0);   // 2 hearts
        p.getWorld().spawnParticle(Particle.EXPLOSION, l.clone().add(0, 1, 0), 6, 1.2, 0.5, 1.2, 0);
        p.getWorld().spawnParticle(Particle.SQUID_INK, l.clone().add(0, 1, 0), 80, 1.5, 1, 1.5, 0.2);
        Util.dust(p.getWorld(), l.clone().add(0, 1, 0), 10, 0, 20, 2.5f, 40, 1.5);
        p.getWorld().playSound(l, Sound.ENTITY_GENERIC_EXPLODE, 1f, 0.8f);
        for (LivingEntity le : Util.enemies(p, radius)) {
            le.damage(dmg, p);
            le.setVelocity(new Vector(0, 0.6, 0));
        }
    }

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent e) {
        if (e.isSneaking() && burrowed.containsKey(e.getPlayer().getUniqueId())) endBurrow(e.getPlayer(), true);
    }

    // ------------------------------------------------------------------ ability 2: grasp
    @Override
    public boolean onAbility2(Player p) {
        LivingEntity t = Util.aimedTarget(p, cfgDouble("grasp-range", 30.0), true);
        if (!(t instanceof Player)) { msg(p, "<gray>Aim at a player."); return false; }
        Player target = (Player) t;
        double dmg = cfgDouble("grasp-damage", 8.0);   // 4 hearts, true damage

        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_WARDEN_SONIC_CHARGE, 1f, 1.5f);
        new BukkitRunnable() {
            int ticks = 0;
            @Override public void run() {
                if (!p.isOnline() || p.isDead() || !target.isOnline() || target.isDead()
                        || target.getWorld() != p.getWorld() || ticks > 60) { cancel(); return; }
                ticks += 2;
                Vector to = p.getLocation().toVector().subtract(target.getLocation().toVector());
                double dist = to.length();
                Util.line(p.getWorld(), p.getLocation().add(0, 1, 0), target.getLocation().add(0, 1, 0), 15, 0, 30, 1.2f, 0.7);
                if (dist <= 2.5) {
                    cancel();
                    Vector away = target.getLocation().toVector().subtract(p.getLocation().toVector()).setY(0);
                    if (away.lengthSquared() < 0.01) away = p.getLocation().getDirection().setY(0);
                    away.normalize().multiply(1.3).setY(0.45);   // ~10 blocks of travel
                    target.setVelocity(away);
                    Util.trueDamage(target, dmg, p);
                    target.getWorld().spawnParticle(Particle.EXPLOSION, target.getLocation().add(0, 1, 0), 3, 0.3, 0.3, 0.3, 0);
                    target.getWorld().playSound(target.getLocation(), Sound.ENTITY_WARDEN_SONIC_BOOM, 1f, 1.2f);
                    return;
                }
                target.setVelocity(to.normalize().multiply(Math.min(1.5, 0.4 + dist * 0.06)));
            }
        }.runTaskTimer(plugin, 0L, 2L);
        return true;
    }

    @Override public void onPlayerGone(Player p) {
        endBurrow(p, false);
        nightVision.remove(p.getUniqueId());
    }

    @Override public void onDisable() {
        for (UUID id : new ArrayList<>(burrowed.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) endBurrow(p, false);
        }
        burrowed.clear();
    }
}
