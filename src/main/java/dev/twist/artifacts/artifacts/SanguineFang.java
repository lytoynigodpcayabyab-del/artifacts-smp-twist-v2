package dev.twist.artifacts.artifacts;

import dev.twist.artifacts.TwistPlugin;
import dev.twist.artifacts.artifact.Artifact;
import dev.twist.artifacts.util.Util;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Bat;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Sanguine Fang (HELD).
 * Passive:   enemies under 50% health leave a crimson trail only you see; +20% speed while sprinting toward them.
 * Ability 1: Bite - 1 heart true damage, victim loses 2 max hearts for 30s and bleeds; you steal those 2 hearts (90s).
 * Ability 2: Bat swarm - 3-4 bats, 1 heart true damage per bat (90s).
 */
public final class SanguineFang extends Artifact {

    private final Map<UUID, Location> lastPos = new HashMap<>();

    public SanguineFang(TwistPlugin plugin) {
        super(plugin, "sanguine_fang", Material.FERMENTED_SPIDER_EYE, 1009);
    }

    @Override public String displayName() { return "<gradient:#b71c1c:#ff1744><b>Sanguine Fang</b></gradient>"; }

    @Override public List<String> lore() {
        return List.of(
                "<gray>It remembers every drop.",
                "",
                "<dark_red>▪ <gray>Wounded enemies (<50% HP) leave a crimson trail",
                "<dark_red>▪ <gray>+20% speed sprinting toward them",
                "<dark_red>▪ <gold>F: <gray>Bite - steal 2 hearts for 30s (90s)",
                "<dark_red>▪ <gold>Shift+F: <gray>Summon a swarm of bats (90s)");
    }

    @Override public PassiveMode passiveMode() { return PassiveMode.HELD; }
    @Override public boolean hasAbilities() { return true; }
    @Override public int cooldown1Seconds() { return cfgInt("ability1-cooldown", 90); }
    @Override public int cooldown2Seconds() { return cfgInt("ability2-cooldown", 90); }

    // ------------------------------------------------------------------ passive
    @Override
    public void onPassiveTick(Player holder) {
        double range = cfgDouble("wounded-range", 32.0);
        Location o = holder.getLocation();
        boolean toward = false;
        Particle.DustOptions crimson = new Particle.DustOptions(Color.fromRGB(170, 0, 20), 1.3f);

        for (Player t : holder.getWorld().getNearbyPlayers(o, range)) {
            if (!Util.validTarget(holder, t) || Util.isAlly(holder, t)) continue;
            Location cur = t.getLocation();
            Location prev = lastPos.put(t.getUniqueId(), cur.clone());
            if (t.getHealth() >= Util.maxHealth(t) * 0.5) continue;

            if (prev != null && prev.getWorld() == cur.getWorld() && prev.distanceSquared(cur) < 100) {
                for (int i = 0; i <= 6; i++) {
                    double f = i / 6.0;
                    holder.spawnParticle(Particle.DUST, prev.getX() + (cur.getX() - prev.getX()) * f,
                            prev.getY() + 0.5 + (cur.getY() - prev.getY()) * f,
                            prev.getZ() + (cur.getZ() - prev.getZ()) * f, 1, 0.05, 0.1, 0.05, 0, crimson);
                }
            } else {
                holder.spawnParticle(Particle.DUST, cur.clone().add(0, 0.5, 0), 3, 0.2, 0.3, 0.2, 0, crimson);
            }

            Vector to = cur.toVector().subtract(o.toVector());
            if (to.lengthSquared() > 1 && holder.isSprinting()
                    && o.getDirection().normalize().dot(to.normalize()) > 0.8) toward = true;
        }
        if (toward) {
            Util.tempAttr(plugin, holder, Attribute.MOVEMENT_SPEED, "fang_chase", 0.2,
                    AttributeModifier.Operation.ADD_SCALAR, 16);
        }
    }

    // ------------------------------------------------------------------ ability 1: bite
    @Override
    public boolean onAbility1(Player p) {
        LivingEntity t = Util.aimedTarget(p, cfgDouble("bite-range", 2.0), true);
        if (!(t instanceof Player)) { msg(p, "<gray>No player within bite range."); return false; }
        Player target = (Player) t;
        int ticks = cfgInt("bite-seconds", 30) * 20;

        Util.trueDamage(target, 2.0, p);   // 1 heart
        double loss = Math.min(4.0, Util.maxHealth(target) - 2.0);   // 2 hearts, never below 1 heart
        if (loss > 0) {
            Util.tempAttr(plugin, target, Attribute.MAX_HEALTH, "fang_bitten", -loss, AttributeModifier.Operation.ADD_NUMBER, ticks);
            Util.tempAttr(plugin, p, Attribute.MAX_HEALTH, "fang_stolen", loss, AttributeModifier.Operation.ADD_NUMBER, ticks);
            Util.heal(p, loss);
        }
        p.getWorld().playSound(target.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 1f, 0.6f);
        p.getWorld().playSound(target.getLocation(), Sound.ENTITY_BAT_TAKEOFF, 1f, 0.5f);

        int bleedSeconds = cfgInt("bleed-seconds", 5);
        double bleedDmg = cfgDouble("bleed-damage-per-second", 1.0);
        new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                if (!target.isOnline() || target.isDead() || t >= bleedSeconds) { cancel(); return; }
                t++;
                Util.trueDamage(target, bleedDmg, p);
                target.getWorld().spawnParticle(Particle.BLOCK, target.getLocation().add(0, 1, 0), 14, 0.3, 0.5, 0.3, 0,
                        Material.REDSTONE_BLOCK.createBlockData());
            }
        }.runTaskTimer(plugin, 20L, 20L);
        msg(p, "<dark_red>Bite!");
        return true;
    }

    // ------------------------------------------------------------------ ability 2: bats
    @Override
    public boolean onAbility2(Player p) {
        double range = cfgDouble("bat-range", 30.0);
        if (Util.enemyPlayers(p, range).isEmpty()) { msg(p, "<gray>No enemy players nearby."); return false; }

        int count = ThreadLocalRandom.current().nextInt(3, 5);   // 3-4
        double dmg = cfgDouble("bat-damage", 2.0);                // 1 heart per bat, true damage
        int lifeTicks = cfgInt("bat-seconds", 8) * 20;
        List<Bat> bats = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Bat b = p.getWorld().spawn(p.getLocation().add(0, 1.6, 0), Bat.class, bat -> {
                bat.setInvulnerable(true);
                bat.setPersistent(false);
                bat.setAwake(true);
            });
            bats.add(b);
        }
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_BAT_TAKEOFF, 1.5f, 0.6f);

        new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                t += 2;
                List<Player> enemies = p.isOnline() ? Util.enemyPlayers(p, range) : List.of();
                Iterator<Bat> it = bats.iterator();
                while (it.hasNext()) {
                    Bat b = it.next();
                    if (!b.isValid()) { it.remove(); continue; }
                    Player nearest = null;
                    double best = Double.MAX_VALUE;
                    for (Player e : enemies) {
                        if (e.getWorld() != b.getWorld()) continue;
                        double d = e.getLocation().distanceSquared(b.getLocation());
                        if (d < best) { best = d; nearest = e; }
                    }
                    if (nearest == null || t >= lifeTicks) { b.remove(); it.remove(); continue; }
                    Location goal = nearest.getLocation().add(0, 1.0, 0);
                    if (best < 2.5) {
                        Util.trueDamage(nearest, dmg, p);
                        nearest.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR, goal, 4, 0.2, 0.2, 0.2, 0);
                        nearest.getWorld().playSound(goal, Sound.ENTITY_BAT_HURT, 1f, 0.7f);
                        b.remove();
                        it.remove();
                    } else {
                        b.setVelocity(goal.toVector().subtract(b.getLocation().toVector()).normalize().multiply(0.6));
                    }
                }
                if (bats.isEmpty()) cancel();
            }
        }.runTaskTimer(plugin, 2L, 2L);
        msg(p, "<dark_red>The swarm hunts!");
        return true;
    }

    @Override public void onPlayerGone(Player p) { lastPos.remove(p.getUniqueId()); }
}
