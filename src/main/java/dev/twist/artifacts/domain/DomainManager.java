package dev.twist.artifacts.domain;

import dev.twist.artifacts.TwistPlugin;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Void Capsule domain: a real dome (hemisphere shell) built from sculk, sculk veins, deepslate and
 * sculk catalysts. Original blocks are saved and restored when the domain ends.
 *  - players inside at cast time are trapped (pushed back, teleports out cancelled),
 *  - everybody else is repelled and cannot get in,
 *  - dome blocks cannot be broken / blown up / replaced while the domain lives.
 * Only AIR and replaceable blocks are overwritten, so terrain is never destroyed.
 */
public final class DomainManager implements Listener {

    private static final int TASK_INTERVAL = 2;
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final TwistPlugin plugin;
    private final Map<UUID, Domain> byCaster = new HashMap<>();
    private final Map<UUID, Domain> trapped = new HashMap<>();
    private BukkitTask task;
    private boolean internalTeleport;

    private record Placed(int x, int y, int z, BlockData original) {}

    private static final class Domain {
        final UUID caster;
        final Location center;
        final double radius;
        final int endTick;
        final Set<UUID> victims = new HashSet<>();
        final List<Placed> blocks = new ArrayList<>();
        final Set<Long> keys = new HashSet<>();

        Domain(UUID caster, Location center, double radius, int endTick) {
            this.caster = caster;
            this.center = center;
            this.radius = radius;
            this.endTick = endTick;
        }
    }

    public DomainManager(TwistPlugin plugin) { this.plugin = plugin; }

    public boolean hasDomain(UUID caster) { return byCaster.containsKey(caster); }

    /** Creates a domain centred on the caster. @return false if the caster already has one. */
    public boolean create(Player caster, double radius, int durationTicks) {
        if (byCaster.containsKey(caster.getUniqueId())) return false;

        Domain d = new Domain(caster.getUniqueId(), caster.getLocation().clone(), radius,
                Bukkit.getCurrentTick() + durationTicks);

        double rSq = radius * radius;
        for (Player p : caster.getWorld().getNearbyPlayers(d.center, radius)) {
            if (p == caster
                    || p.hasPermission(TwistPlugin.BYPASS_PERMISSION)
                    || p.getGameMode() == GameMode.SPECTATOR
                    || trapped.containsKey(p.getUniqueId())
                    || p.getLocation().distanceSquared(d.center) > rSq) continue;
            d.victims.add(p.getUniqueId());
            trapped.put(p.getUniqueId(), d);
            p.sendMessage(MM.deserialize("<dark_purple>You are trapped inside a Void Domain!"));
            p.playSound(p.getLocation(), Sound.ENTITY_ELDER_GUARDIAN_CURSE, 0.8f, 0.6f);
        }

        buildDome(d);
        byCaster.put(caster.getUniqueId(), d);
        ensureTask();
        return true;
    }

    // ------------------------------------------------------------------ dome blocks
    private void buildDome(Domain d) {
        World w = d.center.getWorld();
        int cx = d.center.getBlockX(), cy = d.center.getBlockY(), cz = d.center.getBlockZ();
        int r = (int) Math.ceil(d.radius) + 1;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        BlockData vein = veinData();

        for (int dx = -r; dx <= r; dx++) {
            for (int dy = 0; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (dist < d.radius - 0.5 || dist >= d.radius + 0.9) continue;
                    int x = cx + dx, y = cy + dy, z = cz + dz;
                    if (y < w.getMinHeight() || y >= w.getMaxHeight()) continue;

                    Block b = w.getBlockAt(x, y, z);
                    if (!(b.getType().isAir() || b.isReplaceable())) continue;

                    BlockData original = b.getBlockData();
                    int n = rnd.nextInt(100);
                    BlockData data;
                    if (n < 35) data = Material.SCULK.createBlockData();
                    else if (n < 70) data = Material.DEEPSLATE.createBlockData();
                    else if (n < 90) data = vein;
                    else data = Material.SCULK_CATALYST.createBlockData();

                    b.setBlockData(data, false);
                    d.blocks.add(new Placed(x, y, z, original));
                    d.keys.add(Block.getBlockKey(x, y, z));
                }
            }
        }
    }

    private static BlockData veinData() {
        BlockData bd = Material.SCULK_VEIN.createBlockData();
        if (bd instanceof MultipleFacing mf) {
            for (BlockFace f : mf.getAllowedFaces()) mf.setFace(f, true);
        }
        return bd;
    }

    private static boolean isDomeMaterial(Material m) {
        return m == Material.SCULK || m == Material.SCULK_VEIN
                || m == Material.DEEPSLATE || m == Material.SCULK_CATALYST;
    }

    private void restoreBlocks(Domain d) {
        World w = d.center.getWorld();
        for (Placed pl : d.blocks) {
            Block b = w.getBlockAt(pl.x(), pl.y(), pl.z());
            if (isDomeMaterial(b.getType())) b.setBlockData(pl.original(), false);
        }
        d.blocks.clear();
        d.keys.clear();
    }

    private boolean isDome(Block b) {
        if (byCaster.isEmpty()) return false;
        long k = Block.getBlockKey(b.getX(), b.getY(), b.getZ());
        for (Domain d : byCaster.values()) {
            if (d.center.getWorld() == b.getWorld() && d.keys.contains(k)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ task
    private void ensureTask() {
        if (task == null) task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, TASK_INTERVAL, TASK_INTERVAL);
    }

    private void tick() {
        int now = Bukkit.getCurrentTick();

        Iterator<Domain> it = byCaster.values().iterator();
        while (it.hasNext()) {
            Domain d = it.next();
            Player caster = Bukkit.getPlayer(d.caster);
            boolean expired = now >= d.endTick;

            if (expired || caster == null || caster.isDead()) {
                release(d, caster, !expired);
                it.remove();
                continue;
            }
            enforceVictims(d);
            repelOutsiders(d);
        }

        if (byCaster.isEmpty() && task != null) {
            task.cancel();
            task = null;
        }
    }

    private void enforceVictims(Domain d) {
        double inner = d.radius - 1.0;
        double innerSq = inner * inner;
        double hardSq = (d.radius + 3.0) * (d.radius + 3.0);

        Iterator<UUID> it = d.victims.iterator();
        while (it.hasNext()) {
            UUID id = it.next();
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            if (p.isDead() || p.getWorld() != d.center.getWorld()) {
                it.remove();
                trapped.remove(id);
                continue;
            }
            double distSq = p.getLocation().distanceSquared(d.center);
            if (distSq <= innerSq) continue;
            if (distSq > hardSq) {
                internalTeleport = true;
                try { p.teleport(d.center); } finally { internalTeleport = false; }
                continue;
            }
            Vector back = d.center.toVector().subtract(p.getLocation().toVector()).normalize().multiply(0.6);
            p.setVelocity(back);
        }
    }

    /** Anyone who is not the caster or a trapped victim cannot enter: they get thrown out of the dome. */
    private void repelOutsiders(Domain d) {
        World w = d.center.getWorld();
        for (Player p : w.getNearbyPlayers(d.center, d.radius + 6)) {
            UUID id = p.getUniqueId();
            if (id.equals(d.caster) || d.victims.contains(id)
                    || p.hasPermission(TwistPlugin.BYPASS_PERMISSION)
                    || p.getGameMode() == GameMode.SPECTATOR) continue;

            Vector out = p.getLocation().toVector().subtract(d.center.toVector());
            double dist = out.length();
            if (dist >= d.radius + 1.0) continue;

            Vector flat = out.clone().setY(0);
            if (flat.lengthSquared() < 0.01) flat = new Vector(1, 0, 0);
            flat.normalize();

            if (dist < d.radius - 0.5) {
                // somehow inside (flew over / spawned in): put them 3 blocks outside the shell
                double target = d.radius + 3.0;
                Location dest = d.center.clone().add(flat.getX() * target, 0, flat.getZ() * target);
                dest.setY(w.getHighestBlockYAt(dest) + 1.0);
                dest.setYaw(p.getLocation().getYaw());
                dest.setPitch(p.getLocation().getPitch());
                internalTeleport = true;
                try { p.teleport(dest); } finally { internalTeleport = false; }
            } else {
                p.setVelocity(flat.multiply(1.1).setY(0.35));
            }
            p.sendActionBar(MM.deserialize("<dark_purple>The Void Domain repels you!"));
            p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.6f, 0.5f);
        }
    }

    private void release(Domain d, Player caster, boolean endedEarly) {
        for (UUID id : d.victims) {
            trapped.remove(id);
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.sendMessage(MM.deserialize("<green>The Void Domain collapses. You are free."));
        }
        d.victims.clear();
        restoreBlocks(d);

        if (caster != null) {
            if (endedEarly && !caster.isDead()) {
                PotionEffect speed = caster.getPotionEffect(PotionEffectType.SPEED);
                if (speed != null && speed.getAmplifier() == 2) caster.removePotionEffect(PotionEffectType.SPEED);
            }
            caster.getWorld().playSound(d.center, Sound.BLOCK_BEACON_DEACTIVATE, 1f, 0.7f);
        }
    }

    // ------------------------------------------------------------------ events
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (internalTeleport || byCaster.isEmpty()) return;
        Player p = e.getPlayer();
        Location to = e.getTo();

        Domain mine = trapped.get(p.getUniqueId());
        if (mine != null && (to.getWorld() != mine.center.getWorld()
                || to.distanceSquared(mine.center) > mine.radius * mine.radius)) {
            e.setCancelled(true);
            p.sendActionBar(MM.deserialize("<dark_purple>The Void Domain blocks your escape!"));
            return;
        }
        for (Domain d : byCaster.values()) {
            if (d.caster.equals(p.getUniqueId()) || d.victims.contains(p.getUniqueId())) continue;
            if (to.getWorld() == d.center.getWorld()
                    && to.distanceSquared(d.center) <= (d.radius + 1.0) * (d.radius + 1.0)) {
                e.setCancelled(true);
                p.sendActionBar(MM.deserialize("<dark_purple>The Void Domain blocks you from entering!"));
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) { if (isDome(e.getBlock())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) { if (isDome(e.getBlockPlaced())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) { e.blockList().removeIf(this::isDome); }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) { e.blockList().removeIf(this::isDome); }

    public void handleQuit(Player player) {
        Domain d = byCaster.remove(player.getUniqueId());
        if (d != null) release(d, player, true);
    }

    public void shutdown() {
        if (task != null) task.cancel();
        task = null;
        for (Domain d : byCaster.values()) release(d, Bukkit.getPlayer(d.caster), true);
        byCaster.clear();
        trapped.clear();
    }
}
