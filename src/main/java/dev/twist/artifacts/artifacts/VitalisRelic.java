package dev.twist.artifacts.artifacts;

import dev.twist.artifacts.TwistPlugin;
import dev.twist.artifacts.artifact.Artifact;
import dev.twist.artifacts.util.Util;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.List;

/**
 * The Vitalis Relic (HELD).
 * Ability 1: Overcharge - +5 hearts max health for 30s (120s cooldown).
 * Ability 2: Revitalise - heal 5 hearts, then Regeneration I + Resistance I (240s cooldown).
 */
public final class VitalisRelic extends Artifact {

    public VitalisRelic(TwistPlugin plugin) {
        super(plugin, "vitalis_relic", Material.GLISTERING_MELON_SLICE, 1007);
    }

    @Override public String displayName() { return "<gradient:#ff5252:#ffd740><b>The Vitalis Relic</b></gradient>"; }

    @Override public List<String> lore() {
        return List.of(
                "<gray>It beats with a life of its own.",
                "",
                "<red>▪ <gold>F: <gray>Overcharge - +5 hearts for 30s (120s)",
                "<red>▪ <gold>Shift+F: <gray>Heal 5 hearts and fortify (240s)");
    }

    @Override public PassiveMode passiveMode() { return PassiveMode.HELD; }
    @Override public boolean hasAbilities() { return true; }
    @Override public int cooldown1Seconds() { return cfgInt("ability1-cooldown", 120); }
    @Override public int cooldown2Seconds() { return cfgInt("ability2-cooldown", 240); }

    @Override
    public boolean onAbility1(Player p) {
        double hp = cfgInt("overcharge-hearts", 5) * 2.0;
        int ticks = cfgInt("overcharge-seconds", 30) * 20;
        Util.tempAttr(plugin, p, Attribute.MAX_HEALTH, "vitalis_overcharge", hp, AttributeModifier.Operation.ADD_NUMBER, ticks);
        Util.heal(p, hp);
        p.getWorld().spawnParticle(Particle.HEART, p.getLocation().add(0, 1.2, 0), 15, 0.5, 0.6, 0.5, 0);
        p.getWorld().playSound(p.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1f, 1.5f);
        msg(p, "<red>Overcharge!");
        return true;
    }

    @Override
    public boolean onAbility2(Player p) {
        double hp = cfgInt("heal-hearts", 5) * 2.0;
        int ticks = cfgInt("buff-seconds", 60) * 20;
        Util.heal(p, hp);
        p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, ticks, 0, true, true, true));
        p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, ticks, 0, true, true, true));
        p.getWorld().spawnParticle(Particle.HEART, p.getLocation().add(0, 1.2, 0), 20, 0.6, 0.7, 0.6, 0);
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
        msg(p, "<red>Revitalised!");
        return true;
    }
}
