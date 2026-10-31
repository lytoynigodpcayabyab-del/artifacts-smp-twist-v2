package dev.twist.artifacts.artifacts;

import dev.twist.artifacts.TwistPlugin;
import dev.twist.artifacts.artifact.Artifact;
import dev.twist.artifacts.util.Util;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.WitherSkeleton;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Void Rift (HELD).
 * Passive:   sprinting leaves dark rifts; 25% of incoming arrows/tridents/fireballs are absorbed.
 * Ability 1: black portal summoning 1-3 wither skeletons + 1-3 skeletons (Prot IV diamond, Sharp V iron sword), 30s (180s).
 * Ability 2: Rift Tendrils - roots the target 3s while draining 1 hunger shank per second into yours (120s).
 */
public final class VoidRift extends Artifact implements Listener {

    private final Map<UUID, UUID> summons = new HashMap<>();   // mob -> owner
    private final Map<UUID, Integer> lastRift = new HashMap<>();

    public VoidRift(TwistPlugin plugin) {
        super(plugin, "void_rift", Material.DISC_FRAGMENT_5, 1011);
    }

    @Override public String displayName() { return "<gradient:#1a0033:#8e24aa><b>Void Rift</b></gradient>"; }

    @Override public List<String> lore() {
        return List.of(
                "<gray>A wound in the world that never heals.",
                "",
                "<dark_purple>▪ <gray>Sprinting tears dark rifts behind you",
                "<dark_purple>▪ <gray>25% of arrows, tridents and fireballs vanish",
                "<dark_purple>▪ <gold>F: <gray>Portal - summon skeleton warriors (180s)",
                "<dark_purple>▪ <gold>Shift+F: <gray>Tendrils root and drain a target (120s)");
    }

    @Override public PassiveMode passiveMode() { return PassiveMode.HELD; }
    @Override public boolean hasAbilities() { return true; }
    @Override public int cooldown1Seconds() { return cfgInt("ability1-cooldown", 180); }
    @Override public int cooldown2Seconds() { return cfgInt("ability2-cooldown", 120); }

    // ------------------------------------------------------------------ passive
    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (!p.isSprinting() || !e.hasChangedPosition()) return;
        int now = Bukkit.getCurrentTick();
        Integer last = lastRift.get(p.getUniqueId());
        if (last != null && now - last < 3) return;
        if (!plugin.artifacts().isHeld(p, this)) return;
        lastRift.put(p.getUniqueId(), now);
        Location l = e.getFrom().clone().add(0, 0.3, 0);
        Util.dust(p.getWorld(), l, 20, 0, 40, 1.3f, 4, 0.25);
        p.getWorld().spawnParticle(Particle.REVERSE_PORTAL, l, 3, 0.15, 0.2, 0.15, 0.02);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onProjectile(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Entity d = e.getDamager();
        if (!(d instanceof AbstractArrow || d instanceof Fireball)) return;
        if (!plugin.artifacts().isHeld(p, this)) return;
        if (ThreadLocalRandom.current().nextDouble() >= cfgDouble("absorb-chance", 0.25)) return;
        e.setCancelled(true);
        d.remove();
        p.getWorld().spawnParticle(Particle.REVERSE_PORTAL, p.getLocation().add(0, 1, 0), 30, 0.4, 0.6, 0.4, 0.1);
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.7f, 0.4f);
    }

    // ------------------------------------------------------------------ ability 1: portal
    @Override
    public boolean onAbility1(Player p) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int withers = rnd.nextInt(1, 4);
        int skeletons = rnd.nextInt(1, 4);
        int lifeTicks = cfgInt("summon-seconds", 30) * 20;

        Vector fwd = p.getLocation().getDirection().setY(0);
        if (fwd.lengthSquared() < 0.01) fwd = new Vector(1, 0, 0);
        Location portal = p.getLocation().add(fwd.normalize().multiply(3));

        // portal visual
        for (int i = 0; i < 40; i++) {
            double a = Math.PI * 2 * i / 40.0;
            Location l = portal.clone().add(Math.cos(a) * 1.2, 1.2 + Math.sin(a) * 1.2, 0);
            Util.dust(p.getWorld(), l, 10, 0, 20, 1.8f, 2, 0.05);
        }
        p.getWorld().spawnParticle(Particle.REVERSE_PORTAL, portal.clone().add(0, 1.2, 0), 60, 0.8, 1, 0.8, 0.1);
        p.getWorld().playSound(portal, Sound.ENTITY_WITHER_SPAWN, 0.6f, 1.8f);

        List<UUID> spawned = new ArrayList<>();
        for (int i = 0; i < withers; i++) {
            WitherSkeleton ws = p.getWorld().spawn(portal.clone().add(rnd.nextDouble(-1.5, 1.5), 0, rnd.nextDouble(-1.5, 1.5)),
                    WitherSkeleton.class, m -> equip(m));
            summons.put(ws.getUniqueId(), p.getUniqueId());
            spawned.add(ws.getUniqueId());
        }
        for (int i = 0; i < skeletons; i++) {
            Skeleton sk = p.getWorld().spawn(portal.clone().add(rnd.nextDouble(-1.5, 1.5), 0, rnd.nextDouble(-1.5, 1.5)),
                    Skeleton.class, m -> equip(m));
            summons.put(sk.getUniqueId(), p.getUniqueId());
            spawned.add(sk.getUniqueId());
        }

        new BukkitRunnable() {
            @Override public void run() { for (UUID id : spawned) despawn(id); }
        }.runTaskLater(plugin, lifeTicks);
        msg(p, "<dark_purple>The rift opens! <gray>(" + withers + " wither skeletons, " + skeletons + " skeletons)");
        return true;
    }

    private void equip(Mob mob) {
        EntityEquipment eq = mob.getEquipment();
        if (eq == null) return;
        eq.setHelmet(armor(Material.DIAMOND_HELMET));
        eq.setChestplate(armor(Material.DIAMOND_CHESTPLATE));
        eq.setLeggings(armor(Material.DIAMOND_LEGGINGS));
        eq.setBoots(armor(Material.DIAMOND_BOOTS));
        ItemStack sword = new ItemStack(Material.IRON_SWORD);
        sword.addUnsafeEnchantment(Enchantment.SHARPNESS, 5);
        eq.setItemInMainHand(sword);
        eq.setHelmetDropChance(0f);
        eq.setChestplateDropChance(0f);
        eq.setLeggingsDropChance(0f);
        eq.setBootsDropChance(0f);
        eq.setItemInMainHandDropChance(0f);
        mob.setCanPickupItems(false);
        mob.setRemoveWhenFarAway(false);
        mob.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 20 * 40, 0, true, false, false));
    }

    private ItemStack armor(Material m) {
        ItemStack it = new ItemStack(m);
        it.addUnsafeEnchantment(Enchantment.PROTECTION, 4);
        return it;
    }

    private void despawn(UUID mobId) {
        summons.remove(mobId);
        Entity e = Bukkit.getEntity(mobId);
        if (e != null && e.isValid()) {
            e.getWorld().spawnParticle(Particle.REVERSE_PORTAL, e.getLocation().add(0, 1, 0), 25, 0.3, 0.6, 0.3, 0.1);
            e.remove();
        }
    }

    /** Summons never attack their owner. */
    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent e) {
        UUID owner = summons.get(e.getEntity().getUniqueId());
        if (owner != null && e.getTarget() != null && e.getTarget().getUniqueId().equals(owner)) e.setCancelled(true);
    }

    @EventHandler
    public void onSummonDeath(EntityDeathEvent e) {
        if (summons.remove(e.getEntity().getUniqueId()) != null) {
            e.getDrops().clear();
            e.setDroppedExp(0);
        }
    }

    // ------------------------------------------------------------------ ability 2: tendrils
    @Override
    public boolean onAbility2(Player p) {
        LivingEntity t = Util.aimedTarget(p, cfgDouble("tendril-range", 25.0), true);
        if (!(t instanceof Player)) { msg(p, "<gray>Aim at a player."); return false; }
        Player target = (Player) t;
        int total = cfgInt("root-seconds", 3) * 20;

        target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, total + 5, 9, true, false, false));
        target.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, total + 5, 200, true, false, false));
        p.getWorld().playSound(target.getLocation(), Sound.ENTITY_ENDERMAN_SCREAM, 0.8f, 0.5f);

        new BukkitRunnable() {
            int ticks = 0;
            @Override public void run() {
                if (!target.isOnline() || target.isDead() || !p.isOnline() || p.isDead() || ticks >= total) {
                    target.removePotionEffect(PotionEffectType.SLOWNESS);
                    target.removePotionEffect(PotionEffectType.JUMP_BOOST);
                    cancel();
                    return;
                }
                Location base = target.getLocation();
                // rift behind the target
                Vector back = base.getDirection().setY(0);
                if (back.lengthSquared() > 0.01) {
                    Location rift = base.clone().subtract(back.normalize().multiply(1.5)).add(0, 1, 0);
                    target.getWorld().spawnParticle(Particle.REVERSE_PORTAL, rift, 10, 0.3, 0.7, 0.3, 0.05);
                }
                // tendrils
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI * 2 * i / 8.0 + ticks * 0.15;
                    for (double y = 0; y <= 2.0; y += 0.5) {
                        Util.dust(target.getWorld(), base.clone().add(Math.cos(a) * 0.8, y, Math.sin(a) * 0.8), 25, 0, 50, 1.2f, 1, 0);
                    }
                }
                if (ticks % 20 == 0 && ticks > 0 || ticks == 0) {
                    // 1 hunger shank = 2 food points
                    int drained = Math.min(2, target.getFoodLevel());
                    if (drained > 0) {
                        target.setFoodLevel(target.getFoodLevel() - drained);
                        p.setFoodLevel(Math.min(20, p.getFoodLevel() + drained));
                    }
                }
                ticks += 2;
            }
        }.runTaskTimer(plugin, 0L, 2L);
        msg(p, "<dark_purple>Tendrils!");
        return true;
    }

    @Override public void onPlayerGone(Player p) { lastRift.remove(p.getUniqueId()); }

    @Override public void onDisable() {
        for (UUID id : new ArrayList<>(summons.keySet())) despawn(id);
        summons.clear();
    }
}
