# Twist Artifacts (Paper 1.21.x, Java 21)

## Build
    mvn clean package        # -> target/TwistArtifacts.jar  (drop into /plugins)
Targets Paper 1.21.4 (`paper-api:1.21.4-R0.1-SNAPSHOT`). Works on 1.21.x. Needs Java 21.
(For 1.20.4 / Java 17 you'd need to swap a few renamed constants: STRENGTH->INCREASE_DAMAGE,
 SLOWNESS->SLOW, HASTE->FAST_DIGGING, JUMP_BOOST->JUMP, Particle.DUST->REDSTONE, SPLASH->WATER_SPLASH,
 and remove setMaxStackSize / setEnchantmentGlintOverride.)

## Commands & permissions
| Command | Permission |
|---|---|
| `/artifact give <player> <artifact> [amount]` | `twist.artifact.admin` (op) |
| `/artifact list` | `twist.artifact.admin` |
| `/artifact reload` | `twist.artifact.admin` |

`twist.artifact.use` (default true) lets players trigger right-click abilities.
`twist.artifact.bypass` (default false) makes a player immune to the Star's Darkness aura,
the Void Domain, and the other abilities (handy for staff).

## Controls (v2)
* **F** (swap hand) = ability 1, **Shift+F** = ability 2, while holding the artifact in either hand.
* Also available as `/offhand` and `/crouchoffhand`. `/artifact resetcd` clears your cooldowns (testing).
* Delete your old `plugins/TwistArtifacts/config.yml` once so the new defaults are generated.

IDs: `star_of_invisibility`, `void_capsule`, `particle_ray_array`, `vitalis_relic`, `umbral_abyss`,
`sanguine_fang`, `heart_of_the_sculk`, `void_rift`, `deep_dark_chalice`, `wither_drawn_shield`
(legacy right-click artifacts kept: `tidal_heart`, `phoenix_ember`, `stormcaller_idol`)

Every number (cooldowns, durations, damage) is in `config.yml` under `artifacts.<id>`.

Notes
* "True damage" ignores armor, enchantments and absorption (health is set directly, kill credit goes to the caster).
* Void Capsule dome: real blocks (sculk, sculk vein, deepslate, sculk catalyst) placed only on air/replaceable
  blocks and restored afterwards. Dome blocks can't be broken or blown up while active.
* Allies (Wither-Drawn Shield, Void Capsule/Ray Array targeting) = you + players on your scoreboard team.
* Umbral Abyss "see through walls" uses the Glowing effect (visible to everyone); set `detect-glow: false` to disable.

## Custom Model Data
Edit `plugins/TwistArtifacts/config.yml`:

    artifacts:
      void_capsule:
        material: ENDER_PEARL
        custom-model-data: 1002

Then `/artifact reload` and give a fresh item (existing items keep the CMD they were created with;
they still WORK because identification uses the hidden PDC tag, never the CMD or name).

### Resource pack - 1.21.4+ (`assets/minecraft/items/nether_star.json`)
    {
      "model": {
        "type": "minecraft:range_dispatch",
        "property": "minecraft:custom_model_data",
        "fallback": { "type": "minecraft:model", "model": "minecraft:item/nether_star" },
        "entries": [
          { "threshold": 1001, "model": { "type": "minecraft:model", "model": "twist:item/star_of_invisibility" } }
        ]
      }
    }
### Resource pack - 1.21.3 and older (`assets/minecraft/models/item/nether_star.json`)
    {
      "parent": "minecraft:item/generated",
      "textures": { "layer0": "minecraft:item/nether_star" },
      "overrides": [
        { "predicate": { "custom_model_data": 1001 }, "model": "twist:item/star_of_invisibility" }
      ]
    }

## Anti-cheat notes
* An item is an artifact only if it has the PDC key `twistartifacts:artifact_id` (set by `/artifact give`).
  Renaming a nether star "Star of Invisibility" in an anvil does nothing.
* Artifacts can't stack, can't be crafted with, and the base item's vanilla use (pearl throw, lightning-rod placing, fire charge) is blocked.
* Optional hardening you may want: cancel anvil / grindstone use on artifacts, or keep them on death.

## Performance notes
* One plugin-wide repeating task (default every 10 ticks), with a cheap material pre-filter before reading item meta.
* Passive effects are short-lived and refreshed; they self-expire if anything fails.
* Void Domain places no blocks. Its task exists only while a domain is active. Teleports out are cancelled.
* All tasks are cancelled and effects stripped in `onDisable`.
