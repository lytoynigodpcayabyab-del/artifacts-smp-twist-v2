package dev.twist.artifacts.util;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.RayTraceResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Shared helpers: target filtering, true damage, temporary attribute modifiers, particles. */
public final class Util {

    private Util() {}

    private record Active(BukkitTask task, Runnable undo) {}

    private static final Map<String, Active> ACTIVE = new HashMap<>();

    // ------------------------------------------------------------------ targeting
    public static boolean validTarget(Player caster, Entity e) {
        if (e == caster || !(e instanceof LivingEntity) || e instanceof ArmorStand) return false;
        if (e instanceof Tameable t && t.getOwner() != null
                && t.getOwner().getUniqueId().equals(caster.getUniqueId())) return false;
        if (e instanceof Player p) {
            return !p.hasPermission("twist.artifact.bypass")
                    && p.getGameMode() != GameMode.SPECTATOR
                    && p.getGameMode() != GameMode.CREATIVE;
        }
        return true;
    }

    /** Allies = the caster plus anyone on the same scoreboard team. */
    public static boolean isAlly(Player a, Player b) {
        if (a.getUniqueId().equals(b.getUniqueId())) return true;
        Team t = Bukkit.getScoreboardManager().getMainScoreboard().getEntryTeam(a.getName());
        return t != null && t.hasEntry(b.getName());
    }

    public static List<Player> enemyPlayers(Player caster, double radius) {
        List<Player> out = new ArrayList<>();
        Location o = caster.getLocation();
        double rSq = radius * radius;
        for (Player p : caster.getWorld().getNearbyPlayers(o, radius)) {
            if (!validTarget(caster, p) || isAlly(caster, p)) continue;
            if (p.getLocation().distanceSquared(o) > rSq) continue;
            out.add(p);
        }
        return out;
    }

    /** Players or mobs (not allies) in a sphere around the caster. */
    public static List<LivingEntity> enemies(Player caster, double radius) {
        List<LivingEntity> out = new ArrayList<>();
        Location o = caster.getLocation();
        double rSq = radius * radius;
        for (Entity e : caster.getNearbyEntities(radius, radius, radius)) {
            if (!validTarget(caster, e)) continue;
            if (e instanceof Player p && isAlly(caster, p)) continue;
            if (e.getLocation().distanceSquared(o) > rSq) continue;
            out.add((LivingEntity) e);
        }
        return out;
    }

    /** The living entity under the crosshair (line of sight required), or null. */
    public static LivingEntity aimedTarget(Player p, double range, boolean playersOnly) {
        Location eye = p.getEyeLocation();
        RayTraceResult r = p.getWorld().rayTraceEntities(eye, eye.getDirection(), range, 0.4,
                e -> e != p && e instanceof LivingEntity && (!playersOnly || e instanceof Player) && validTarget(p, e));
        if (r == null || !(r.getHitEntity() instanceof LivingEntity le)) return null;
        if (le instanceof Player pl && isAlly(p, pl)) return null;
        return p.hasLineOfSight(le) ? le : null;
    }

    // ------------------------------------------------------------------ health
    public static double maxHealth(LivingEntity e) {
        AttributeInstance a = e.getAttribute(Attribute.MAX_HEALTH);
        return a != null ? a.getValue() : 20.0;
    }

    public static void heal(LivingEntity e, double hp) {
        if (e.isDead() || hp <= 0) return;
        e.setHealth(Math.min(maxHealth(e), e.getHealth() + hp));
    }

    public static void clampHealth(LivingEntity e) {
        double max = maxHealth(e);
        if (e.getHealth() > max) e.setHealth(max);
    }

    /** Damage that ignores armor, enchantments and absorption. {@code source} gets kill credit. */
    public static void trueDamage(LivingEntity victim, double hp, Entity source) {
        if (victim == null || victim.isDead() || hp <= 0.001) return;
        if (victim instanceof Player p && (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR)) return;
        if (source != null) victim.damage(0.0001, source);   // hurt animation + kill attribution
        double left = victim.getHealth() - hp;
        victim.setHealth(Math.max(0.0, left));
    }

    // ------------------------------------------------------------------ temporary attribute modifiers
    public static void tempAttr(Plugin plugin, Player p, Attribute attr, String name, double amount,
                                AttributeModifier.Operation op, int ticks) {
        AttributeInstance inst = p.getAttribute(attr);
        if (inst == null) return;
        NamespacedKey key = new NamespacedKey(plugin, name);
        String tk = p.getUniqueId() + ":" + name;

        Active old = ACTIVE.remove(tk);
        if (old != null) old.task().cancel();
        removeMod(inst, key);
        inst.addTransientModifier(new AttributeModifier(key, amount, op));
        if (attr == Attribute.MAX_HEALTH) clampHealth(p);

        Runnable undo = () -> {
            AttributeInstance i = p.getAttribute(attr);
            if (i != null) removeMod(i, key);
            if (attr == Attribute.MAX_HEALTH) clampHealth(p);
        };
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            ACTIVE.remove(tk);
            undo.run();
        }, Math.max(1, ticks));
        ACTIVE.put(tk, new Active(task, undo));
    }

    private static void removeMod(AttributeInstance inst, NamespacedKey key) {
        for (AttributeModifier m : new ArrayList<>(inst.getModifiers())) {
            if (m.getKey().equals(key)) inst.removeModifier(m);
        }
    }

    /** Called from onDisable: cancels timers and strips every modifier we added. */
    public static void shutdown() {
        for (Active a : new ArrayList<>(ACTIVE.values())) {
            a.task().cancel();
            try { a.undo().run(); } catch (Exception ignored) { }
        }
        ACTIVE.clear();
    }

    // ------------------------------------------------------------------ particles
    public static void dust(World w, Location l, int r, int g, int b, float size, int count, double spread) {
        w.spawnParticle(Particle.DUST, l, count, spread, spread, spread, 0,
                new Particle.DustOptions(Color.fromRGB(r, g, b), size));
    }

    public static void dustTo(Player viewer, Location l, int r, int g, int b, float size) {
        viewer.spawnParticle(Particle.DUST, l, 1, 0, 0, 0, 0,
                new Particle.DustOptions(Color.fromRGB(r, g, b), size));
    }

    /** Draws a particle line between two points (visible to everyone). */
    public static void line(World w, Location a, Location b, int r, int g, int bl, float size, double step) {
        org.bukkit.util.Vector d = b.toVector().subtract(a.toVector());
        double len = d.length();
        if (len < 0.01) return;
        d.normalize().multiply(step);
        Location cur = a.clone();
        for (double t = 0; t < len; t += step) {
            dust(w, cur, r, g, bl, size, 1, 0);
            cur.add(d);
        }
    }
}
