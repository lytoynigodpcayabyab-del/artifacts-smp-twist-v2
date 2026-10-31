package dev.twist.artifacts;

import dev.twist.artifacts.artifact.ArtifactManager;
import dev.twist.artifacts.artifact.CooldownManager;
import dev.twist.artifacts.artifacts.DeepDarkChalice;
import dev.twist.artifacts.artifacts.HeartOfTheSculk;
import dev.twist.artifacts.artifacts.ParticleRayArray;
import dev.twist.artifacts.artifacts.PhoenixEmber;
import dev.twist.artifacts.artifacts.SanguineFang;
import dev.twist.artifacts.artifacts.UmbralAbyss;
import dev.twist.artifacts.artifacts.VitalisRelic;
import dev.twist.artifacts.artifacts.VoidRift;
import dev.twist.artifacts.artifacts.WitherDrawnShield;
import dev.twist.artifacts.artifacts.StarOfInvisibility;
import dev.twist.artifacts.artifacts.StormcallerIdol;
import dev.twist.artifacts.artifacts.TidalHeart;
import dev.twist.artifacts.artifacts.VoidCapsule;
import dev.twist.artifacts.command.ArtifactCommand;
import dev.twist.artifacts.domain.DomainManager;
import dev.twist.artifacts.listener.ArtifactListener;
import dev.twist.artifacts.util.Util;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

/**
 * Entry point of the "Twist" artifacts plugin.
 *
 * HOW TO EXTEND:
 *  1. Create a class extending {@link dev.twist.artifacts.artifact.Artifact}.
 *  2. Add a config section under artifacts.<id> in config.yml.
 *  3. Register it below with artifacts.register(new YourArtifact(this)).
 *     If it also implements org.bukkit.event.Listener, its events are registered automatically.
 *
 * Commands are declared in plugin.yml and bound in onEnable via getCommand(..).setExecutor(..).
 */
public final class TwistPlugin extends JavaPlugin {

    public static final String BYPASS_PERMISSION = "twist.artifact.bypass";
    public static final String USE_PERMISSION = "twist.artifact.use";

    private CooldownManager cooldowns;
    private ArtifactManager artifacts;
    private DomainManager domains;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        cooldowns = new CooldownManager();
        artifacts = new ArtifactManager(this);
        domains = new DomainManager(this);

        // Event registration (domain manager listens for teleports of trapped players)
        getServer().getPluginManager().registerEvents(domains, this);
        getServer().getPluginManager().registerEvents(new ArtifactListener(this), this);

        // Artifact registration
        artifacts.register(new StarOfInvisibility(this));
        artifacts.register(new VoidCapsule(this));
        artifacts.register(new TidalHeart(this));
        artifacts.register(new PhoenixEmber(this));
        artifacts.register(new StormcallerIdol(this));
        artifacts.register(new ParticleRayArray(this));
        artifacts.register(new VitalisRelic(this));
        artifacts.register(new UmbralAbyss(this));
        artifacts.register(new SanguineFang(this));
        artifacts.register(new HeartOfTheSculk(this));
        artifacts.register(new VoidRift(this));
        artifacts.register(new DeepDarkChalice(this));
        artifacts.register(new WitherDrawnShield(this));

        // Command registration (must also be declared in plugin.yml)
        PluginCommand cmd = Objects.requireNonNull(getCommand("artifact"), "artifact command missing from plugin.yml");
        ArtifactCommand handler = new ArtifactCommand(this);
        cmd.setExecutor(handler);
        cmd.setTabCompleter(handler);

        // /offhand = ability 1 (F), /crouchoffhand = ability 2 (Shift+F). Handy for keybind mods / command blocks.
        Objects.requireNonNull(getCommand("offhand")).setExecutor((sender, c, l, a) -> {
            if (sender instanceof Player p) artifacts.trigger(p, 1);
            return true;
        });
        Objects.requireNonNull(getCommand("crouchoffhand")).setExecutor((sender, c, l, a) -> {
            if (sender instanceof Player p) artifacts.trigger(p, 2);
            return true;
        });

        // One single repeating task drives every passive effect / aura.
        artifacts.startPassiveTask();
        getLogger().info("Loaded " + artifacts.all().size() + " artifacts.");
    }

    @Override
    public void onDisable() {
        // Cancel tasks and strip our effects so nothing leaks across reloads.
        if (artifacts != null) artifacts.shutdown();
        if (domains != null) domains.shutdown();
        Util.shutdown();
    }

    /** Re-reads config.yml and restarts the passive task with the new interval. */
    public void reloadAll() {
        reloadConfig();
        artifacts.restartPassiveTask();
    }

    public CooldownManager cooldowns() { return cooldowns; }
    public ArtifactManager artifacts() { return artifacts; }
    public DomainManager domains() { return domains; }
}
