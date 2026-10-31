package dev.twist.artifacts.artifacts;

import dev.twist.artifacts.TwistPlugin;
import dev.twist.artifacts.artifact.Artifact;
import dev.twist.artifacts.util.Util;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * Particle Ray Array (HELD).
 * Passive:   basic attacks reach +2 blocks and leave a light trail; walking on it gives +15% movement speed.
 * Ability 1: Laser - 10s beam dealing TRUE damage every 0.1s (240s cooldown).
 * Ability 2: Radar - reveals enemy players (Glowing) in a large radius for 120s.
 */
public final class ParticleRayArray extends Artifact implements Listener {

    private record TrailPoint(Location loc, int expiry) {}

    private final Map<UUID, List<TrailPoint>> trails = new HashMap<>();
    private final Map<UUID, Integer> lastTrail = new HashMap<>();

    public ParticleRayArray(TwistPlugin plugin) {
        super(plugin, "particle_ray_array", Material.PRISMARINE_CRYSTALS, 1006);
    }

    @Override public String displayName() { return "<gradient:#00e5ff:#e0f7fa><b>Particle Ray Array</b></gradient>"; }

    @Override public List<String> lore() {
        return List.of(
                "<gray>Light, focused to a point.",
                "",
                "<aqua>▪ <gray>Attacks reach +2 blocks and leave a light trail",
                "<aqua>▪ <gray>Trail grants +15% Movement Speed",
                "<aqua>▪ <gold>F: <gray>Laser - true damage beam, 10s (240s)",
                "<aqua>▪ <gold>Shift+F: <gray>Radar - reveal enemies");
    }

    @Override public PassiveMode passiveMode() { return PassiveMode.HELD; }
    @Override public boolean hasAbilities() { return true; }
    @Override public int cooldown1Seconds() { return cfgInt("ability1-cooldown", 240); }
    @Override public int cooldown2Seconds() { return cfgInt("ability2-cooldown", 180); }

    // ------------------------------------------------------------------ passive
    @Override
    public void onPassiveTick(Player holder) {
        List<TrailPoint> list = trails.get(holder.getUniqueId());
        if (list == null) return;
        int now = Bukkit.getCurrentTick();
        list.removeIf(tp -> tp.expiry() <= now);
        for (TrailPoint tp : list) {
            tp.loc().getWorld().spawnParticle(Particle.END_ROD, tp.loc(), 1, 0.05, 0.02, 0.05, 0);
        }
    }

    private void leaveTrail(Player p) {
        int now = Bukkit.getCurrentTick();
        Integer last = lastTrail.get(p.getUniqueId());
        if (last != null && now - last < 4) return;
        lastTrail.put(p.getUniqueId(), now);

        Vector dir = p.getLocation().getDirection().setY(0);
        if (dir.lengthSquared() < 0.01) return;
        dir.normalize();
        Location base = p.getLocation().clone().add(0, 0.1, 0);
        List<TrailPoint> list = trails.computeIfAbsent(p.getUniqueId(), k -> new ArrayList<>());
        list.removeIf(tp -> tp.expiry() <= now);
        for (int i = 0; i <= 8; i++) {
            list.add(new TrailPoint(base.clone().add(dir.clone().multiply(i * 0.5)), now + cfgInt("trail-ticks", 60)));
        }
        while (list.size() > 64) list.remove(0);
    }

    @EventHandler(ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p && e.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                && plugin.artifacts().isHeld(p, this)) leaveTrail(p);
    }

    /** +2 block reach: a left-click that hit nothing at vanilla range hits what is up to 5 blocks away. */
    @EventHandler
    public void onSwing(PlayerInteractEvent e) {
        if (e.getAction() != Action.LEFT_CLICK_AIR) return;
        Player p = e.getPlayer();
        if (!plugin.artifacts().isHeld(p, this)) return;
        leaveTrail(p);
        if (p.getAttackCooldown() < 0.9f) return;

        double reach = 3.0 + cfgDouble("bonus-reach", 2.0);
        Location eye = p.getEyeLocation();
        RayTraceResult r = p.getWorld().rayTraceEntities(eye, eye.getDirection(), reach, 0.1,
                en -> en != p && en instanceof LivingEntity && Util.validTarget(p, en)
                        && !(en instanceof Player pl && Util.isAlly(p, pl)));
        if (r == null || r.getHitEntity() == null) return;
        Entity target = r.getHitEntity();
        if (!p.hasLineOfSight(target)) return;
        p.swingMainHand();
        p.attack(target);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (!e.hasChangedPosition()) return;
        List<TrailPoint> list = trails.get(e.getPlayer().getUniqueId());
        if (list == null || list.isEmpty()) return;
        Location to = e.getTo();
        int now = Bukkit.getCurrentTick();
        for (TrailPoint tp : list) {
            if (tp.expiry() > now && tp.loc().getWorld() == to.getWorld() && tp.loc().distanceSquared(to) < 2.25) {
                Util.tempAttr(plugin, e.getPlayer(), Attribute.MOVEMENT_SPEED, "ray_trail_speed", 0.15,
                        AttributeModifier.Operation.ADD_SCALAR, 20);
                return;
            }
        }
    }

    // ------------------------------------------------------------------ abilities
    @Override
    public boolean onAbility1(Player p) {
        int iterations = cfgInt("laser-seconds", 10) * 10;      // one hit every 2 ticks (0.1s)
        double range = cfgDouble("laser-range", 40.0);
        double damage = cfgDouble("laser-damage", 1.0);         // HP per hit (1.0 = 0.5 hearts), TRUE damage

        p.getWorld().playSound(p.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1f, 2f);
        new BukkitRunnable() {
            int i = 0;
            @Override public void run() {
                if (!p.isOnline() || p.isDead() || i++ >= iterations) { cancel(); return; }
                Location eye = p.getEyeLocation();
                Vector dir = eye.getDirection();
                RayTraceResult r = p.getWorld().rayTrace(eye, dir, range, FluidCollisionMode.NEVER, true, 0.3,
                        en -> en != p && en instanceof LivingEntity && Util.validTarget(p, en)
                                && !(en instanceof Player pl && Util.isAlly(p, pl)));
                double len = range;
                if (r != null) {
                    len = r.getHitPosition().distance(eye.toVector());
                    if (r.getHitEntity() instanceof LivingEntity le) Util.trueDamage(le, damage, p);
                }
                Location cur = eye.clone().add(dir.clone().multiply(0.6)).add(0, -0.25, 0);
                Vector step = dir.clone().multiply(0.8);
                for (double d = 0.6; d < len; d += 0.8) {
                    Util.dust(p.getWorld(), cur, 0, 229, 255, 1.2f, 1, 0);
                    cur.add(step);
                }
            }
        }.runTaskTimer(plugin, 0L, 2L);
        msg(p, "<aqua>Laser online!");
        return true;
    }

    @Override
    public boolean onAbility2(Player p) {
        double radius = cfgDouble("radar-radius", 50.0);
        int total = cfgInt("radar-seconds", 120) * 2;           // runs every 10 ticks
        p.getWorld().playSound(p.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 1f, 1.5f);
        new BukkitRunnable() {
            int i = 0;
            @Override public void run() {
                if (!p.isOnline() || p.isDead() || i++ >= total) { cancel(); return; }
                for (Player t : Util.enemyPlayers(p, radius)) {
                    t.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 30, 0, true, false, false));
                }
            }
        }.runTaskTimer(plugin, 0L, 10L);
        msg(p, "<aqua>Radar active.");
        return true;
    }

    @Override public void onPlayerGone(Player p) {
        trails.remove(p.getUniqueId());
        lastTrail.remove(p.getUniqueId());
    }
}
