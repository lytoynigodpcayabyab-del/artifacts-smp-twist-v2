package dev.twist.artifacts.artifacts;

import dev.twist.artifacts.TwistPlugin;
import dev.twist.artifacts.artifact.Artifact;
import dev.twist.artifacts.util.Util;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * Wither-Drawn Shield (HELD, a real shield - raise it with right-click as usual).
 * Passive:   blocking a melee hit gives the attacker Wither I for 3s (10s cooldown).
 * Ability 1: 3s of absorbing all damage, then a Wither blast (Wither II) that heals you the absorbed amount (90s).
 * Ability 2: plant the shield: 6-block shadow dome; allies take 30% less damage, enemies are repelled and leeched 1.5 hearts (180s).
 * Allies = you + players on the same scoreboard team.
 */
public final class WitherDrawnShield extends Artifact implements Listener {

    private static final class Absorb { int endTick; double absorbed; }

    private static final class Dome {
        UUID owner; Location center; int endTick; ItemDisplay display; BukkitTask task;
        final Map<UUID, Integer> lastLeech = new HashMap<>();
    }

    private final Map<UUID, Absorb> absorbing = new HashMap<>();
    private final Map<UUID, Dome> domes = new HashMap<>();

    public WitherDrawnShield(TwistPlugin plugin) {
        super(plugin, "wither_drawn_shield", Material.SHIELD, 1013);
    }

    @Override public String displayName() { return "<gradient:#212121:#757575><b>Wither-Drawn Shield</b></gradient>"; }

    @Override public List<String> lore() {
        return List.of(
                "<gray>Forged where the Wither fell.",
                "",
                "<dark_gray>▪ <gray>Blocked melee hits inflict Wither I (10s cd)",
                "<dark_gray>▪ <gold>F: <gray>Absorb 3s, then Wither blast + heal (90s)",
                "<dark_gray>▪ <gold>Shift+F: <gray>Plant a 6-block shadow dome (180s)");
    }

    @Override public PassiveMode passiveMode() { return PassiveMode.HELD; }
    @Override public boolean hasAbilities() { return true; }
    @Override public boolean denyVanillaUse() { return false; }   // keep vanilla blocking
    @Override public int cooldown1Seconds() { return cfgInt("ability1-cooldown", 90); }
    @Override public int cooldown2Seconds() { return cfgInt("ability2-cooldown", 180); }

    // ------------------------------------------------------------------ passive
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMeleeBlocked(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player p) || e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (!(e.getDamager() instanceof LivingEntity attacker)) return;
        if (!p.isBlocking() || !plugin.artifacts().isHeld(p, this)) return;
        Vector to = attacker.getLocation().toVector().subtract(p.getLocation().toVector());
        if (to.lengthSquared() < 0.01 || p.getLocation().getDirection().dot(to.normalize()) <= 0) return;
        if (onCd(p, "p")) return;
        setCd(p, "p", cfgInt("passive-cooldown", 10));
        attacker.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, cfgInt("passive-wither-seconds", 3) * 20, 0, true, true, true));
        p.getWorld().spawnParticle(Particle.SMOKE, attacker.getLocation().add(0, 1, 0), 12, 0.3, 0.5, 0.3, 0.02);
    }

    // ------------------------------------------------------------------ ability 1
    @Override
    public boolean onAbility1(Player p) {
        if (absorbing.containsKey(p.getUniqueId())) return false;
        int ticks = cfgInt("absorb-seconds", 3) * 20;
        Absorb a = new Absorb();
        a.endTick = Bukkit.getCurrentTick() + ticks;
        absorbing.put(p.getUniqueId(), a);
        p.getWorld().playSound(p.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1f, 0.6f);
        msg(p, "<dark_gray>Absorbing...");

        new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                if (!p.isOnline() || p.isDead()) { absorbing.remove(p.getUniqueId()); cancel(); return; }
                t += 2;
                Util.dust(p.getWorld(), p.getLocation().add(0, 1, 0), 30, 30, 30, 1.6f, 6, 0.6);
                if (t < ticks) return;
                cancel();
                absorbing.remove(p.getUniqueId());
                blast(p, a.absorbed);
            }
        }.runTaskTimer(plugin, 0L, 2L);
        return true;
    }

    private void blast(Player p, double absorbed) {
        double range = cfgDouble("blast-range", 8.0);
        int ticks = cfgInt("blast-wither-seconds", 5) * 20;
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().normalize();

        for (double d = 1; d <= range; d += 0.7) {
            Location l = eye.clone().add(dir.clone().multiply(d)).add(0, -0.3, 0);
            p.getWorld().spawnParticle(Particle.SMOKE, l, 6, d * 0.08, d * 0.08, d * 0.08, 0.01);
            Util.dust(p.getWorld(), l, 20, 20, 20, 2.0f, 2, d * 0.06);
        }
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_WITHER_SHOOT, 1.2f, 0.8f);
        for (LivingEntity le : Util.enemies(p, range)) {
            Vector to = le.getEyeLocation().toVector().subtract(eye.toVector());
            if (to.lengthSquared() < 0.01 || dir.dot(to.normalize()) < 0.6) continue;
            le.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, ticks, 1, true, true, true));   // Wither II
        }
        Util.heal(p, absorbed);
        msg(p, "<dark_gray>Wither blast! <gray>Healed " + String.format("%.1f", absorbed / 2.0) + " hearts.");
    }

    // ------------------------------------------------------------------ ability 2
    @Override
    public boolean onAbility2(Player p) {
        if (domes.containsKey(p.getUniqueId())) { msg(p, "<red>Your shield is already planted."); return false; }
        Dome d = new Dome();
        d.owner = p.getUniqueId();
        d.center = p.getLocation().clone();
        d.endTick = Bukkit.getCurrentTick() + cfgInt("dome-seconds", 15) * 20;
        d.display = p.getWorld().spawn(d.center.clone().add(0, 0.7, 0), ItemDisplay.class, disp -> {
            disp.setItemStack(new ItemStack(Material.SHIELD));
            disp.setPersistent(false);
        });
        double r = cfgDouble("dome-radius", 6.0);
        double leech = cfgDouble("leech-hp", 3.0);   // 1.5 hearts

        d.task = new BukkitRunnable() {
            int pass = 0;
            @Override public void run() {
                Player owner = Bukkit.getPlayer(d.owner);
                int now = Bukkit.getCurrentTick();
                if (owner == null || owner.isDead() || now >= d.endTick) { endDome(d.owner); return; }
                pass++;
                if (pass % 5 == 0) drawDome(d, r);
                for (Player pl : d.center.getWorld().getNearbyPlayers(d.center, r + 3)) {
                    if (!Util.validTarget(owner, pl) || Util.isAlly(owner, pl)) continue;
                    Vector out = pl.getLocation().toVector().subtract(d.center.toVector());
                    if (out.length() >= r + 0.3) continue;
                    Vector flat = out.clone().setY(0);
                    if (flat.lengthSquared() < 0.01) flat = new Vector(1, 0, 0);
                    pl.setVelocity(flat.normalize().multiply(1.0).setY(0.3));
                    Integer last = d.lastLeech.get(pl.getUniqueId());
                    if (last == null || now - last >= 20) {
                        d.lastLeech.put(pl.getUniqueId(), now);
                        double before = pl.getHealth();
                        Util.trueDamage(pl, leech, owner);
                        Util.heal(owner, Math.min(before, leech));
                        pl.getWorld().playSound(pl.getLocation(), Sound.ENTITY_WITHER_HURT, 0.6f, 1.2f);
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 2L);
        domes.put(p.getUniqueId(), d);
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.5f, 1.5f);
        msg(p, "<dark_gray>Shadow dome planted.");
        return true;
    }

    private void drawDome(Dome d, double r) {
        for (int i = 0; i < 60; i++) {
            double y = Math.random();                        // hemisphere
            double ring = Math.sqrt(1 - y * y);
            double a = Math.random() * Math.PI * 2;
            Location l = d.center.clone().add(Math.cos(a) * ring * r, y * r, Math.sin(a) * ring * r);
            Util.dust(d.center.getWorld(), l, 25, 25, 25, 1.5f, 1, 0);
        }
    }

    private void endDome(UUID owner) {
        Dome d = domes.remove(owner);
        if (d == null) return;
        if (d.task != null) d.task.cancel();
        if (d.display != null && d.display.isValid()) d.display.remove();
    }

    // ------------------------------------------------------------------ damage handling (absorb + ally reduction)
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;

        Absorb a = absorbing.get(p.getUniqueId());
        if (a != null && Bukkit.getCurrentTick() < a.endTick) {
            a.absorbed += e.getFinalDamage();
            e.setCancelled(true);
            p.getWorld().playSound(p.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1f, 0.7f);
            return;
        }

        if (domes.isEmpty()) return;
        double r = cfgDouble("dome-radius", 6.0);
        for (Dome d : domes.values()) {
            Player owner = Bukkit.getPlayer(d.owner);
            if (owner == null || d.center.getWorld() != p.getWorld()) continue;
            if (!Util.isAlly(owner, p)) continue;
            if (p.getLocation().distanceSquared(d.center) > r * r) continue;
            e.setDamage(e.getDamage() * (1.0 - cfgDouble("ally-reduction", 0.30)));
            return;
        }
    }

    @Override public void onPlayerGone(Player p) {
        absorbing.remove(p.getUniqueId());
        endDome(p.getUniqueId());
    }

    @Override public void onDisable() {
        for (UUID id : new ArrayList<>(domes.keySet())) endDome(id);
        domes.clear();
        absorbing.clear();
    }
}
