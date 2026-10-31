package dev.twist.artifacts.artifact;

import dev.twist.artifacts.TwistPlugin;
import dev.twist.artifacts.util.Util;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;

import java.util.List;

/**
 * Base class for every artifact.
 *  - Legacy single ability: right-click -> {@link #onUse}.
 *  - Two-ability artifacts: F (swap hand) -> {@link #onAbility1}, Shift+F -> {@link #onAbility2}.
 * Tunables are read from config.yml under artifacts.<id>.*
 */
public abstract class Artifact {

    public enum PassiveMode { HELD, INVENTORY }

    public record PassiveEffect(PotionEffectType type, int amplifier) {}

    protected final TwistPlugin plugin;
    private final String id;
    private final Material defaultMaterial;
    private final int defaultModelData;

    protected Artifact(TwistPlugin plugin, String id, Material defaultMaterial, int defaultModelData) {
        this.plugin = plugin;
        this.id = id;
        this.defaultMaterial = defaultMaterial;
        this.defaultModelData = defaultModelData;
    }

    // ---------- identity / looks ----------
    public final String id() { return id; }
    public abstract String displayName();
    public abstract List<String> lore();

    public Material material() {
        Material m = Material.matchMaterial(cfgString("material", defaultMaterial.name()));
        return m != null ? m : defaultMaterial;
    }

    public int customModelData() { return cfgInt("custom-model-data", defaultModelData); }

    // ---------- passive ----------
    public abstract PassiveMode passiveMode();
    public List<PassiveEffect> passiveEffects() { return List.of(); }
    public void onPassiveTick(Player holder) {}

    // ---------- legacy right-click ability ----------
    public boolean hasActiveAbility() { return false; }
    public int cooldownSeconds() { return cfgInt("cooldown-seconds", 0); }
    public boolean onUse(Player player, ItemStack item) { return false; }

    // ---------- two-ability system (F / Shift+F) ----------
    public boolean hasAbilities() { return false; }
    public int cooldown1Seconds() { return cfgInt("ability1-cooldown", 0); }
    public int cooldown2Seconds() { return cfgInt("ability2-cooldown", 0); }
    /** @return true if the ability fired (cooldown starts only then). */
    public boolean onAbility1(Player p) { return false; }
    public boolean onAbility2(Player p) { return false; }
    /** Shields etc. must keep their vanilla right-click behaviour. */
    public boolean denyVanillaUse() { return true; }
    /** Player died or left: end any running effects. */
    public void onPlayerGone(Player p) {}
    /** Plugin disabling: end everything. */
    public void onDisable() {}

    // ---------- helpers ----------
    protected String cfgPath(String key) { return "artifacts." + id + "." + key; }
    protected double cfgDouble(String key, double def) { return plugin.getConfig().getDouble(cfgPath(key), def); }
    protected int cfgInt(String key, int def) { return plugin.getConfig().getInt(cfgPath(key), def); }
    protected String cfgString(String key, String def) { return plugin.getConfig().getString(cfgPath(key), def); }
    protected boolean cfgBool(String key, boolean def) { return plugin.getConfig().getBoolean(cfgPath(key), def); }

    protected void msg(Player p, String miniMessage) {
        p.sendActionBar(MiniMessage.miniMessage().deserialize(miniMessage));
    }

    /** Cooldown helpers for passives ("p") or custom slots. */
    protected boolean onCd(Player p, String slot) {
        return plugin.cooldowns().remainingMillis(p.getUniqueId(), id + ":" + slot) > 0;
    }

    protected void setCd(Player p, String slot, int seconds) {
        plugin.cooldowns().start(p.getUniqueId(), id + ":" + slot, seconds);
    }

    protected boolean isValidTarget(Player caster, Entity e) { return Util.validTarget(caster, e); }
}
