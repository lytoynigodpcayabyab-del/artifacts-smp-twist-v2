package dev.twist.artifacts.artifact;

import dev.twist.artifacts.TwistPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * Registry + item factory + passive effect engine.
 *
 * ANTI-CHEAT: an item is an artifact ONLY if its PersistentDataContainer holds our id tag.
 * Names / lore / custom model data are purely cosmetic, so anvil-renaming a normal item does nothing.
 *
 * PERFORMANCE: ONE repeating task for the whole plugin. Each pass first filters items by material
 * (cheap) before reading item meta, and effects are short-lived (auto-expire) so there is nothing to leak.
 */
public final class ArtifactManager {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final TwistPlugin plugin;
    private final NamespacedKey idKey;
    private final Map<String, Artifact> registry = new LinkedHashMap<>();
    private final Set<Material> trackedMaterials = EnumSet.noneOf(Material.class);
    /** Effects we applied per player, so we can remove exactly those when the artifact leaves. */
    private final Map<UUID, Map<PotionEffectType, PotionEffect>> applied = new HashMap<>();

    private BukkitTask passiveTask;
    private int intervalTicks = 10;

    public ArtifactManager(TwistPlugin plugin) {
        this.plugin = plugin;
        this.idKey = new NamespacedKey(plugin, "artifact_id");
    }

    // ------------------------------------------------------------------ registry
    public void register(Artifact artifact) {
        registry.put(artifact.id(), artifact);
        trackedMaterials.add(artifact.material());
        if (artifact instanceof Listener listener) {
            plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        }
    }

    public Artifact get(String id) { return registry.get(id.toLowerCase(Locale.ROOT)); }
    public Collection<Artifact> all() { return Collections.unmodifiableCollection(registry.values()); }

    // ------------------------------------------------------------------ items
    @SuppressWarnings("deprecation") // setCustomModelData(Integer) is deprecated on 1.21.4+ but still works
    public ItemStack createItem(Artifact a) {
        ItemStack item = new ItemStack(a.material());
        ItemMeta meta = item.getItemMeta();

        meta.displayName(MM.deserialize("<!i>" + a.displayName()));
        List<Component> lore = new ArrayList<>();
        for (String line : a.lore()) lore.add(MM.deserialize("<!i>" + line));
        meta.lore(lore);

        int cmd = a.customModelData();
        if (cmd > 0) meta.setCustomModelData(cmd);

        meta.setMaxStackSize(1);                 // never stack artifacts
        meta.setEnchantmentGlintOverride(true);  // magical shimmer without real enchants
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);

        // The security tag. Without this, the item is just a renamed vanilla item.
        meta.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, a.id());
        item.setItemMeta(meta);
        return item;
    }

    /** @return the artifact this stack is, or null for ordinary items. */
    public Artifact identify(ItemStack item) {
        if (item == null || !trackedMaterials.contains(item.getType()) || !item.hasItemMeta()) return null;
        String id = item.getItemMeta().getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
        return id == null ? null : registry.get(id);
    }

    public boolean isHeld(Player player, Artifact artifact) {
        PlayerInventory inv = player.getInventory();
        return identify(inv.getItemInMainHand()) == artifact || identify(inv.getItemInOffHand()) == artifact;
    }

    // ------------------------------------------------------------------ passive engine
    public void startPassiveTask() {
        intervalTicks = Math.max(2, plugin.getConfig().getInt("settings.passive-check-interval-ticks", 10));
        trackedMaterials.clear();
        for (Artifact a : registry.values()) trackedMaterials.add(a.material());
        passiveTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, intervalTicks);
    }

    public void restartPassiveTask() {
        if (passiveTask != null) passiveTask.cancel();
        startPassiveTask();
    }

    private void tick() {
        // Effects live slightly longer than one interval, so they refresh seamlessly
        // and vanish on their own if the task ever stops.
        int duration = intervalTicks + 30;

        for (Player player : plugin.getServer().getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            if (player.isDead()) { applied.remove(uuid); continue; }

            Set<Artifact> active = collectActive(player);
            if (active.isEmpty() && !applied.containsKey(uuid)) continue; // nothing to do: cheap exit

            Map<PotionEffectType, PotionEffect> desired = new HashMap<>();
            for (Artifact a : active) {
                for (Artifact.PassiveEffect pe : a.passiveEffects()) {
                    PotionEffect effect = new PotionEffect(pe.type(), duration, pe.amplifier(), true, false, true);
                    desired.merge(pe.type(), effect, (o, n) -> n.getAmplifier() > o.getAmplifier() ? n : o);
                }
                a.onPassiveTick(player);
            }
            syncEffects(player, desired);
        }
    }

    private Set<Artifact> collectActive(Player player) {
        PlayerInventory inv = player.getInventory();
        Set<Artifact> active = new LinkedHashSet<>();

        Artifact main = identify(inv.getItemInMainHand());
        if (main != null) active.add(main);
        Artifact off = identify(inv.getItemInOffHand());
        if (off != null) active.add(off);

        for (ItemStack stack : inv.getContents()) {
            Artifact a = identify(stack);
            if (a != null && a.passiveMode() == Artifact.PassiveMode.INVENTORY) active.add(a);
        }
        return active;
    }

    private void syncEffects(Player player, Map<PotionEffectType, PotionEffect> desired) {
        UUID uuid = player.getUniqueId();
        Map<PotionEffectType, PotionEffect> previous = applied.get(uuid);

        // Remove effects from artifacts no longer active. Only if it still looks like OUR effect
        // (same amplifier, short duration) so we never strip a real potion the player drank.
        if (previous != null) {
            for (Map.Entry<PotionEffectType, PotionEffect> e : previous.entrySet()) {
                if (desired.containsKey(e.getKey())) continue;
                removeIfOurs(player, e.getKey(), e.getValue());
            }
        }

        for (PotionEffect effect : desired.values()) player.addPotionEffect(effect);

        if (desired.isEmpty()) applied.remove(uuid);
        else applied.put(uuid, desired);
    }

    private void removeIfOurs(Player player, PotionEffectType type, PotionEffect ours) {
        PotionEffect current = player.getPotionEffect(type);
        if (current != null
                && current.getAmplifier() == ours.getAmplifier()
                && current.getDuration() <= ours.getDuration()) {
            player.removePotionEffect(type);
        }
    }

    /** Forget a player (quit / death) and strip our effects. */
    public void forget(Player player) {
        Map<PotionEffectType, PotionEffect> previous = applied.remove(player.getUniqueId());
        if (previous == null) return;
        previous.forEach((type, effect) -> removeIfOurs(player, type, effect));
    }

    /** Player died / left: forget passive effects and end running ability effects. */
    public void playerGone(Player player) {
        forget(player);
        for (Artifact a : registry.values()) a.onPlayerGone(player);
    }

    /** F / Shift+F or /offhand: fire the ability of whichever ability-artifact the player holds. */
    public void trigger(Player p, int slot) {
        Artifact a = null;
        PlayerInventory inv = p.getInventory();
        Artifact main = identify(inv.getItemInMainHand());
        Artifact off = identify(inv.getItemInOffHand());
        if (main != null && main.hasAbilities()) a = main;
        else if (off != null && off.hasAbilities()) a = off;
        if (a == null) { p.sendActionBar(MM.deserialize("<gray>Hold an artifact in your hand.")); return; }
        triggerWith(p, a, slot);
    }

    public void triggerWith(Player p, Artifact a, int slot) {
        if (!p.hasPermission(TwistPlugin.USE_PERMISSION)) return;
        String key = a.id() + ":" + slot;
        long left = plugin.cooldowns().remainingMillis(p.getUniqueId(), key);
        if (left > 0) {
            long secs = (long) Math.ceil(left / 1000.0);
            p.sendActionBar(MM.deserialize("<red>" + MM.stripTags(a.displayName()) + " <gray>ability " + slot
                    + " on cooldown: <white>" + secs + "s"));
            return;
        }
        boolean ok = slot == 1 ? a.onAbility1(p) : a.onAbility2(p);
        if (ok) plugin.cooldowns().start(p.getUniqueId(), key, slot == 1 ? a.cooldown1Seconds() : a.cooldown2Seconds());
    }

    public void shutdown() {
        for (Artifact a : registry.values()) {
            try { a.onDisable(); } catch (Exception ex) { plugin.getLogger().warning("onDisable " + a.id() + ": " + ex); }
        }
        if (passiveTask != null) passiveTask.cancel();
        for (Player p : plugin.getServer().getOnlinePlayers()) forget(p);
        applied.clear();
    }
}
