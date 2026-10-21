# Gameplay: controls and features

What CSMC adds to Minecraft 1.21.1 and how to use it. Everything here is server-authoritative unless noted: the
server owns damage, ammunition, grenades, equipment and case rolls, and each client renders from its **own**
local CS2 asset export (see [install.md](install.md)). Without an export, vanilla placeholders are used and the
mechanics still work.

Related pages: [CS:GO movement and bunny hopping](movement.md), [surf ramps](surf.md), [weapon balance](balance.md),
[arenas](arenas.md), and [permitted surf-map imports](dev/surf-import.md).

## Controls and settings at a glance

| Input | Action |
|---|---|
| Attack / Use (guns) | Fire / cycle scope level on scoped weapons (AWP, SSG 08). |
| `R` (default, rebindable) | Reload the held gun. |
| `F` (default, rebindable) | Inspect the held gun or knife. |
| Attack / Use (knife, main hand) | Slash / stab; see [Knives](#knives). |
| Attack / Use (grenade, main hand) | Hold to pull the pin, release to throw; see [Throwable grenades](#throwable-grenades). |
| `G` (default, rebindable) | Toggle the grenade trajectory preview. |
| Use (hold) | Healthshot inject, C4 plant, defuse; see [Equipment](#equipment-healthshot-c4-and-defuse-kit). |
| `K` or `/csmc skins` | Skin picker for the held gun, knife or glove. |
| `/csmc skin <name_or_id> [wear] [seed]` | Operator command that grants a skin attachment for the held weapon. |
| Left Alt (default, rebindable) | Walk (CS movement); see [movement.md](movement.md). |

All CSMC key bindings sit under the **CSMC** category in Controls (the `csmc` category). **Options → Gun Viewmodel...**
opens the local viewmodel size/position preferences. The optional `config/csmc-gun-rendering.json` file is described in
[Client overrides](#client-overrides). Relevant gamerules: `csmcBlockBreaking` (bullet and grenade terrain damage, see
[Shooting and block damage](#shooting-and-block-damage)), `csmcC4Breaking` (C4 terrain damage) and `csmcMovement`
(CS movement modes, see [movement.md](movement.md)).

## Rendering, viewmodels and HUD

- The mod uses `<id>.gltf` (the manifest's `models.world.file`) as the world model.
- The renderer loads `<id>.gltf` for every context; `<id>_v.gltf` is an additional export for manual use and is never selected.
- **First-person gun viewmodel.** When the record has `viewmodel_animations` and `viewmodel_clips` and the first-person arms (`manifest.json` → `arms`) load, the main-hand gun plays CS2's viewmodel clips in camera space with the CS2 arms and gloves, as knives do. Each clip's weapon skeleton drives the gun model's nodes by name (`bolt`, `clip`, `cliprelease`, `trigger`, `silencer`, ...) and the gun's `weapon` bone rides the arm skeleton's `wpn` bone. The default and legacy bodies take the same clips, so skins keep working. Playback follows gameplay:
  - Guns and knives use their own projection, independent of the player's FOV, sprinting and scoping. The [dumped CS2 defaults](https://github.com/SteamTracking/GameTracking-CS2/blob/master/DumpSource2/convars.txt) are `viewmodel_fov 60` (68 is the maximum), `viewmodel_offset_x 1`, `viewmodel_offset_y 1`, `viewmodel_offset_z -1`. FOV is horizontal at 4:3, about 46.8° vertical; wider aspects widen horizontally. Offsets are inches right, forward and down (mirrored for a left hand). The user's viewmodel offset and size settings apply on top. The renderer flushes the arms and weapon under this projection, then restores the hand projection for other items; it never scales vertex positions to emulate FOV, which would distort normals and depend on the camera heading.
  - `draw` plays on slot select; `idle` (a single-frame pose in CS2) holds between actions, switching to `idle_empty` with an empty magazine (the Glock's slide locked back) and, for the Dual Berettas, `idle_left_empty` with one round left. The inspected AK/Glock/AWP idle skeletons have no camera bone, and their graph uses the exported idle rather than a separate rest layer: `root_motion` is the origin, `wpn` carries the weapon, and the arm chains are sampled with it. The export already converts Source X-forward/Y-left/Z-up to glTF Z-forward/X-left/Y-up; the renderer converts only once more to Minecraft -Z-forward/X-right/Y-up.
  - `fire` restarts on every server shot, so automatic fire restarts at the fire rate. Recoil stays on the camera. Each server FIRE event carries the shooter's shot sequence and post-shot magazine (`GunSoundPayload`). The client matches it to the synced magazine drop of the same shot by post-shot magazine within the held item and slot (never by arrival time), so each shot starts exactly one playback; the magazine drop only plays a shot whose event is missing. The event's magazine selects `fire_last` for the round that empties the gun. The Dual Berettas fire left when a shot leaves an odd count and right when it leaves an even one, so the side is fixed per shot whatever frames render, the left pistol empties first, and each pistol's `_last` variant plays for its final round. Bolt and pump cycles are part of CS2's `shoot1` clips; a separate `cycle` clip, if mapped, plays after the shot.
  - `reload` is time-scaled to the server's reload duration, using `reload_empty` from an empty magazine when mapped. The RELOAD_START event carries the rounds the server will load (what fits, limited by the reserve). Guns with `viewmodel_reload_single_shells` (Nova, XM1014, Sawed-Off) loop the reload clip once per shell loaded, so topping up one shell and loading five both last the server's reload time with one or five insertions. `reload_start`/`reload_loop`/`reload_end`, if mapped, run as start, one loop per shell, then end.
  - `inspect` plays CS2's `lookat` clip on the existing inspect state.
  - While scoped, the arms and gun are hidden as before.
  - Without clips or arms, the gun renders the world model with its embedded `shoot`/`reload`/`inventory_inspect` clips. When only one action lacks a viewmodel clip, that action falls back the same way for the server's action duration (or the embedded clip's length), then the clip viewmodel resumes.
  - First-person transform overrides in `csmc-gun-rendering.json` apply to the clip viewmodel as an adjustment about the hand, with the same precedence as the world-model path: the weapon's own context, then (guns) the `"*"` context, then none.
  - Not wired: `prime`, `dryfire` and `fire_alt` (the mod has no revolver prime delay or alternate fire), and `silencer_attach`/`silencer_detach` (no silencer toggle exists; the M4A1-S and USP-S render their world model's silencer).
- Arms, hand skeletons and a `draw` clip in the world model are not guaranteed.
- CS2 models carry alternative mesh sets (nodes named `….vmdl_c.body_hd`, `….vmdl_c.body_legacy`, plus accessories such as the Dual Berettas' `eholster`). The renderer draws exactly one set: `body_hd`, else `body_legacy`, else the first set present. Meshes of other sets are never decoded, and their textures are never read.
- The renderer uses the **base color texture only**, from the chosen set, plus finish shine from `shine.json` and the body's exported shine mask (bare roughness/metalness from the ORM map, downscaled to 512 px; see [Appearance and resource limits](#appearance-and-resource-limits)). Normal and other maps are never read. Textures are downscaled on load to a max edge of 1024 px by default, uploaded once, and the CPU copy is freed.
- Models load per weapon on two background threads, so the vanilla placeholder item shows until a load finishes. Anything rendered or requested now (the held gun, a case reel's tiles) loads first. After you join a world, every gun model is also queued at lower priority (held gun, then the hotbar, then the rest), so later reels and weapon switches rarely wait; a lower-priority load jumps ahead as soon as something needs it, and queued ones are dropped on resource reload. Loaded models stay resident until resource reload: about 4 MiB of GPU texture each, roughly 145 MiB for all 35 guns, plus legacy bodies (also about 4 MiB each) as legacy skins request them. Each load logs `[csmc glTF] Loaded <id> …` with the mesh set, clip mapping, texture sizes, GPU bytes and parse/decode/upload times.
- Clip mapping comes from the manifest's per-weapon `animations` (fire/reload/draw/inspect). Without that field, the clip list is used: `shoot`/`fire` for fire, `reload` for reload, `draw`/`deploy` for draw, `inspect`/`inventory_inspect` for inspect. No clip is guaranteed to exist.
- **Inspect:** default key `F`, rebindable under the `csmc` controls category. Inspecting lasts 2.5 s. With viewmodel clips the main hand plays CS2's `lookat` clip (above). Only as the fallback (no viewmodel clips or arms, the off hand, other players), the held gun gets a procedural camera-facing rotate/tilt on top of the mapped world-model clip: CS2 world models ship only `inventory_inspect`, a single-key static inventory pose (duration 0), so the procedural motion supplies all of that animation. Other players see the mapped clip with a smaller procedural tilt. The server starts an inspect only for a living, non-spectator player holding a gun that is not reloading and whose trigger is not held. Firing, starting a reload, or changing slot/item cancels it.
- **Ammo HUD:** while holding a gun, a bottom-right readout shows the weapon silhouette, a divider, the rounds in the magazine (large) and the matching inventory ammo (small). Values come from the server (`AmmoHudPayload`, sent only when they change), so magazine capacity and reserve stay separate. A small line above it shows `Reloading…`, or briefly `No matching ammunition` / `Reload cancelled` for the gun that caused it. It is hidden with no gun held, with F1 (hide GUI) and in spectator mode. Firing reduces the magazine only; completing a reload transfers exactly the missing rounds from matching inventory ammo stacks (for example, 16 + 98 reserve becomes 30 + 84). Reserve totals all matching stacks, not just the stack visible in the hotbar.
- **Walking weapon bob:** holding a gun or knife reduces first-person walking bob to 8% of vanilla amplitude at half vanilla speed, including the exported arms and fallback model. World-camera bob, hurt motion, attack/inspect animations, recoil, looking sway and third-person animations are unchanged.
- Exports are glTF Y-up, in metres, barrel along +Z (checked on all 35 exported guns). In hand, guns render at real size, pivoted on their `ag1_hand_r` grip node. In the GUI, on the ground and in item frames, a gun is fitted to one block. If a model's largest extent is over 5 units, it is assumed to be in raw Source inches and scaled by 0.0254.

### Sounds

Gun sounds are server-authoritative. The server broadcasts a small `csmc:gun_sound` event (weapon id, fire/reload start/reload end/reload cancel/draw, position, shooter entity id, reload length) to the shooter and every player tracking them. Each client plays the matching `.ogg` from **its own** cache via `sounds/<id>/manifest.json`:

- **Fire:** one of the close-report files, attenuated over 48 blocks. Beyond 24 blocks, the exported `SingleDistant` layer is used instead when present (160 blocks). Silenced weapons (M4A1-S, USP-S) use their silenced events.
- **Reload:** the parts (clip out, clip in, bolt or slide) play in order across the server's reload time. `_Q` quick-reload duplicates are skipped. A cancelled reload stops the remaining parts.
- **Draw:** the draw file.

Sounds use the Players volume slider and vanilla linear distance attenuation. Files are decoded by vanilla's OGG reader into vanilla's sound buffer cache (the `csmc_cache:` sound namespace maps to `<cache>/sounds/`). When a weapon has no cached file for an event, the client plays the old vanilla placeholder (firework blast for fire, iron trapdoor for reload). Draw had no placeholder and stays silent.

### Client overrides

Optional `config/csmc-gun-rendering.json`, keyed by weapon id; `"*"` applies to all weapons:

```json
{
  "*": { "texture_max_edge": 2048 },
  "ak47": {
    "mesh_sets": ["body_legacy"],
    "animations": { "fire": "shoot" },
    "contexts": { "first_person_right_hand": { "translation": [0, 0.05, 0], "rotation": [0, 180, 0], "scale": 0.9 } }
  }
}
```

`scale` multiplies the real-size normalisation in hand contexts, and the fit-to-block normalisation in other contexts. Overrides are reloaded with resources (F3+T).

## Ammunition

Each gun's `ammo_type` selects the ammunition item that fills its magazine and supplies its reserve. Reload pulls the missing rounds from every matching stack in the inventory, and the reserve HUD totals all of them. A gun's class comes from its CS2 `m_WeaponType` during import; an authored `ammo_type` in the datapack wins over that.

New guns start with a full magazine using the server's effective weapon capacity,
without taking rounds from reserve. This initialization happens only while the
magazine component is absent; explicit empty or partially used magazines stay
as they are. Once those initial rounds are spent, normal ammo-consuming reloads
still apply.

| `ammo_type` | Item (`csmc:`) | Weapon class | Explicit memberships |
|---|---|---|---|
| `pistol` | `pistol_ammo` (Pistol/SMG ammunition) | Pistols, SMGs and the taser | Pistols: `glock`, `hkp2000`, `usp_silencer`, `p250`, `fiveseven`, `tec9`, `cz75a`, `deagle`, `elite`, `revolver`. SMGs: `mac10`, `mp9`, `mp7`, `mp5sd`, `ump45`, `p90`, `bizon`. Taser: `taser`. |
| `rifle` | `rifle_ammo` (Rifle ammunition) | Rifles and machine guns | Rifles: `famas`, `galilar`, `ak47`, `m4a1`, `m4a1_silencer`, `aug`, `sg556`, `m14`. Machine guns: `m249`, `negev`. |
| `sniper` | `sniper_ammo` (Sniper ammunition) | Sniper rifles | `ssg08`, `awp`, `scar20`, `g3sg1`. |
| `shotgun` | `shotgun_shells` (Shotgun shells) | Shotguns | `nova`, `xm1014`, `mag7`, `sawedoff`. |

All 36 firearms are covered: 18 pistol-ammo guns (10 pistols, 7 SMGs, the taser), 10 rifle-ammo guns, 4 sniper and 4 shotgun guns.

- **M14 is a rifle.** Its vdata `m_WeaponType` is `WEAPONTYPE_RIFLE`, so it uses rifle ammunition even though its placeholder stats are sniper-like.
- **SMGs changed from rifle to pistol.** `mac10` through `bizon` previously took rifle ammunition and now take pistol/SMG ammunition. This is an explicit reclassification.
- **No stack migration.** Existing `rifle_ammo` stacks are not converted. Rifles and machine guns keep using them, and SMGs reload only from pistol/SMG stacks.
- **Taser (Zeus) now uses pistol ammunition.** It uses one pistol/SMG round per charge instead of rifle ammunition. Its reserve stays zero. Reloading it is a 30-second interruptible manual reload (the `taser` balance override; switching away cancels it), see [balance.md](balance.md).
- **Crafting cost is a balance input, not a damage change.** Per craft: pistol/SMG 4 iron nuggets + 1 gunpowder for 16 rounds; rifle 4 iron nuggets + 2 gunpowder for 16 rounds; sniper 4 iron ingots + 1 iron nugget + 1 gunpowder for 8 rounds; shells 4 iron nuggets + 1 paper + 1 gunpowder for 8 shells. That is about 0.31, 0.38, 0.75 and 0.75 ingredient items per round. Sniper rounds cost more than rifle rounds by design. Damage, reload and reserve values are not tuned here; they stay under the open balancing backlog item.
- **Weapon balance:** roles, the post-import override layer (`data/<namespace>/weapon_balance/`, reloadable with `/reload`), deviations from CS2 and TTK tables are in [balance.md](balance.md).

## Inspect, scopes and recoil

- **F:** inspect the held gun or knife; fire, reload and slot changes cancel it. In first person with viewmodel clips, CS2's own inspect clip plays; otherwise (and for other players) the 2.5-second movement is procedural, because CS2 world-model inspect clips are static.
- **Use key / right-click:** cycle scope levels. AWP uses 40°/10°; SSG 08 uses 40°/15°. Reload, slot changes and world changes clear zoom. Bolt-action weapons temporarily unzoom and restore the previous level after cycling.
- **Scope sounds:** the AWP and SSG 08 play CS2's scope-in sound when you enter or change zoom level and the scope-out sound when you leave the scope, for manual zoom changes only. Automatic unzoom/rezoom around bolt cycles and weapon switches is silent. The SSG 08 uses the canonical ZoomOut sound definition because its vdata points both directions to Zoom. Older caches need `python3 tools/export-cs2-assets --sounds --weapons awp,ssg08`.
- Scoped aim uses alternate weapon accuracy and movement values. The client transitions FOV and sensitivity, hides hands and draws a scope overlay.
- Generated recoil is 25% stronger than the initial implementation: magnitude-to-degrees scale is 0.025 instead of 0.02. Seeded directions remain unchanged; explicitly supplied measured patterns retain their exact degrees. This is still an approximation, not 1:1 CS:GO recoil.

## Shooting and block damage

Bullets accumulate damage using the block's material in `mod/src/main/resources/data/csmc/ballistics/surfaces.json`. Nearby shooters and different weapons share the same damage total. Cracks clear after 200 quiet ticks or when the block state changes; partial damage is not saved across server restarts.

Fragile materials shatter on a positive hit. Wood needs fewer hits than soil, stone or metal; stronger weapons and multiple shotgun pellets contribute more damage. Bedrock, explicit bullet stoppers and containers/block entities are not destroyed. Block damage and penetration are separate: a round can damage cover without having enough energy to pass through it.

`/gamerule csmcBlockBreaking false` disables bullet/grenade terrain damage and makes solid cover stop bullets rather than allowing wallbangs. Spawn/world-border restrictions, player game mode and Fabric break-protection callbacks are respected. Destruction grants no block loot.

## Armor

`csmc:kevlar_vest` and `csmc:kevlar_helmet` are real chest/head armor in the Firearms tab. They need no exported assets. Vest: **6 vanilla defense, 0 toughness, 640 durability**; helmet: **2 defense, 0 toughness, 440 durability**. Their ordinary defense is iron-equivalent: kevlar is bullet-resistant armor, not a netherite replacement. Each has 0.1 knockback resistance and enchantability 15. Repair either with **iron ingots** in an anvil. Both support ordinary armor enchantments.

Craft one vest with `S_S/ILI/ILI`, or one helmet with `III/LSL` (`S` string, `I` iron ingot, `L` leather). These recipes use vanilla materials and have distinct ingredient grids.

Bullet/knife protection uses the **covering piece's material**, not total armor points:

| Material | Protection strength `s` | Health at 40 CS, ratio 1.55 | Health at 40 CS, ratio 1.95 |
|---|---:|---:|---:|
| None/non-armor/broken | 0 | 40 | 40 |
| Leather; other modded armor | 0.20 | 38.2 | 39.8 |
| Chainmail, gold, turtle | 0.35 | 36.85 | 39.65 |
| Iron | 0.50 | 35.5 | 39.5 |
| Diamond | 0.65 | 34.15 | 39.35 |
| Netherite | 0.80 | 32.8 | 39.2 |
| CSMC kevlar | 1.00 | 31 | 39 |

All table damage is **CS units**, before the normal ×0.2 Minecraft-health conversion. Given post-hit-group/range/penetration damage `D`, `r = clamp(weapon armorRatio × 0.5, 0, 1)` and `H = D × (1 − s × (1 − r))`. Kevlar therefore matches the CS baseline `H = D × r`, while other armor gives less reduction. Headshots consult only the helmet; chest/stomach/generic hits (including arms) consult only the vest/chestplate. **Leg hits are never covered**, even with leggings/boots. The covering stack must have a positive effective armor-attribute contribution applicable to that slot and remaining durability. Modded items need not extend ArmorItem: qualifying unknown equipment uses strength 0.20. A kevlar stack whose effective attributes give zero armor does not protect; ordinary non-armor items placed in armor slots do not protect.

An accepted hit recorded by vanilla damage handling wears only its covering piece by `floor((D − H) × armorBonus)` durability (knives use bonus 0.5). Vanilla `hurtAndBreak` applies Unbreaking and break events. A piece with one durability left protects that hit fully, then breaks, including when a totem saves the target or a lethal hit already transferred the piece into a death drop; replacement equipment is never worn. Subsequent hits are unprotected. ArmorModel is pure: PvP/team guards run before armor mutation, and rejected/invulnerable hits or projectile dodges cause no wear.

Bullets and knives retain `bypasses_armor`, `bypasses_cooldown`, and `no_knockback`, so vanilla defense **never reduces their damage a second time**. They do not bypass enchantments. Bullets additionally have `is_projectile`: Projectile Protection and Protection use vanilla's global equipment EPF after custom absorption (4% per EPF, capped at 20). Thus Projectile Protection IV gives 32% additional reduction, Protection IV 16%; they do not change the custom wear calculation. Knives are not projectiles, so only applicable vanilla enchantments such as Protection reduce them.

**Endermen:** hitscan bullets explicitly skip only the Enderman projectile-dodge predicate, retaining projectile classification for enchantments and all normal damage guards. Bullets therefore damage Endermen even when teleporting is possible or blocked. Ordinary projectiles retain vanilla dodge behavior. Shields remain bypassed by the existing `bypasses_armor` tag (which vanilla includes in `bypasses_shield`).

**HE/fire decision:** retain vanilla damage rules, not ArmorModel. HE uses an explosion source (vanilla defense/toughness and Blast Protection apply); incendiary/molotov uses `in_fire` (vanilla fire rules and Fire Protection apply). Neither gets a second CS absorption/wear pass. Kevlar's iron-equivalent ordinary defense helps against HE and ordinary attacks; it is not fireproof.

## Knives

22 knife items, one per CS2 knife type, appear in the **CSMC Knives** creative tab: `csmc:<id>` where `<id>` is the CS2 `weapons.vdata` key without `weapon_` (Navaja is `knife_gypsy_jackknife`, Talon is `knife_widowmaker`, Huntsman is `knife_tactical`, Bowie is `knife_survival_bowie`, Shadow Daggers is `knife_push`).

With a knife in your **main hand**:

| Input | Action |
|---|---|
| Attack (left click) | **Slash**: light attack. A second slash shortly after the first is a weaker, faster follow-up. |
| Use (right click) | **Stab**: heavy attack, shorter reach, longer recharge. No vanilla use happens. |
| F | Inspect (attacks and slot changes cancel it). |

**First-person animation.** With the knife's exported viewmodel clips in the cache, the main-hand knife plays CS2's own clips in camera space: each clip's knife skeleton drives the knife model's nodes by name, so Butterfly handles and lock, the Flip Knife blade and both Shadow Daggers (one per hand) move as authored, and the knife's `weapon` bone rides the arm skeleton's `wpn` bone. Left-handed players get the mirrored viewmodel. The idle loops while no action plays, alternating `idle1`/`idle2` where both exist. Each new action picks a variant, among the clips that loaded, without repeating the previous one: draws among `draw*` (plus `lookat01_draw` on the Flip Knife), inspects among `lookat01`–`03`, slashes among `light_miss1`–`N` and stabs among `heavy_miss*`. When the server reports a hit, the running swing switches to a `light_hit*`/`heavy_hit*` clip at the same time position. The server names the backstab's swing (`slash_backstab` or `backstab` event), so every viewer plays `light_backstab*` or `heavy_backstab*`; a knife without its own slash-backstab sound or clip uses the backstab one. A lookat clip plays to its end even after the server's 2.5-second inspect window closes. An action without a clip (an inspect included) uses the procedural motion. When the arms export (`manifest.json` → `arms`, `models/arms/`) is present, the CT default agent's arms, gloves and sleeves are drawn on the same clip pose, retargeted in model space; a worn sleeve hides the bare arm beneath it. The viewmodel offset applies in view space and the size scales about the resting hand. A per-weapon first-person entry in `config/csmc-gun-rendering.json` applies as its difference from the built-in default, about the hand. An off-hand knife and third person stay procedural.

Melee is server-authoritative: the client only sends the button. The server checks the recharge (slash and stab share one), traces from the eyes along your current aim for the attack's reach, widening entity boxes by 0.25 blocks (a small hull), and stops at the first solid block (a wall hit). The hull never reaches around cover: a target also needs a clear line to its real body, so a mob pressed behind a glass pane or door is shielded. A knife never damages or breaks blocks, even in creative, and vanilla punching and mining are cancelled while one is held. A hit is a **backstab** when you are behind the target: the target's horizontal facing (a player's current yaw, a mob's head direction), dotted with the direction from the target to you, is below −0.5. Damage uses the same scale as bullets: CS damage × 0.2 = Minecraft health (100 CS HP = 20 health), with tiered chest absorption and durability wear as described in [Armor](#armor). PvP-off and team friendly-fire settings are respected, and kills read "was knifed by". A hit pushes the target back slightly (knockback strength 0.25, below vanilla's 0.4 melee knockback; knockback resistance applies). While a knife is in your main hand, right-click only stabs: it does not open doors or chests, use blocks or entities, or use an off-hand item. A follow-up slash is one made within 1 second after the previous slash's recharge ends; a stab or slot change resets the chain.

CS2's knife data is identical for every knife, so these per-knife numbers are **authored Minecraft adaptations**, not CS2 values. The defaults keep CS:GO's knife (slash 40, follow-up 25, stab 65, backstab 90/180, reach 48/32 units, recharge 0.5/0.4/1.0 s); heavy blades hit harder and reach further but recharge slower, light blades are faster but weaker and shorter. They live in the server data files `data/csmc/knives/<id>.json` (bundled in `mod/src/main/resources`), so datapacks can override them and `/reload` applies changes; a missing or malformed file logs an error and that knife uses the default knife's stats. Damage is in CS units (×0.2 for health); reach is in CS units (16 per block).

| Item (`csmc:`) | Name | Slash / follow-up / stab | Backstab slash / stab | Reach slash/stab, units (blocks) | Recharge slash / follow-up / stab, s | Armor ratio |
|---|---|---|---|---|---|---|
| `knife` | Knife (CT) | 40/25/65 | 90/180 | 48/32 (3.00/2.00) | 0.5/0.4/1 | 1.7 |
| `knife_t` | Knife (T) | 40/25/65 | 90/180 | 48/32 (3.00/2.00) | 0.5/0.4/1 | 1.7 |
| `bayonet` | Bayonet | 42/26/68 | 92/180 | 50/34 (3.12/2.12) | 0.5/0.4/1 | 1.7 |
| `knife_css` | Classic Knife | 40/25/66 | 90/180 | 48/32 (3.00/2.00) | 0.5/0.4/1.05 | 1.7 |
| `knife_flip` | Flip Knife | 39/25/64 | 88/180 | 48/32 (3.00/2.00) | 0.45/0.38/0.95 | 1.7 |
| `knife_gut` | Gut Knife | 36/23/58 | 84/170 | 44/30 (2.75/1.88) | 0.42/0.34/0.88 | 1.6 |
| `knife_karambit` | Karambit | 34/22/56 | 80/170 | 42/28 (2.62/1.75) | 0.38/0.3/0.8 | 1.6 |
| `knife_m9_bayonet` | M9 Bayonet | 45/28/73 | 100/190 | 53/36 (3.31/2.25) | 0.56/0.45/1.12 | 1.8 |
| `knife_tactical` | Huntsman Knife | 46/29/74 | 102/190 | 54/36 (3.38/2.25) | 0.58/0.46/1.15 | 1.8 |
| `knife_falchion` | Falchion Knife | 47/30/75 | 104/195 | 54/36 (3.38/2.25) | 0.6/0.48/1.15 | 1.8 |
| `knife_survival_bowie` | Bowie Knife | 50/32/80 | 110/200 | 56/38 (3.50/2.38) | 0.65/0.5/1.25 | 1.8 |
| `knife_butterfly` | Butterfly Knife | 35/22/58 | 82/170 | 42/28 (2.62/1.75) | 0.36/0.3/0.82 | 1.6 |
| `knife_push` | Shadow Daggers | 30/20/50 | 75/160 | 38/26 (2.38/1.62) | 0.32/0.26/0.7 | 1.6 |
| `knife_cord` | Paracord Knife | 41/26/65 | 90/180 | 50/32 (3.12/2.00) | 0.5/0.4/1 | 1.7 |
| `knife_canis` | Survival Knife | 45/28/72 | 100/190 | 52/35 (3.25/2.19) | 0.57/0.45/1.1 | 1.8 |
| `knife_ursus` | Ursus Knife | 48/30/76 | 106/195 | 55/37 (3.44/2.31) | 0.62/0.48/1.2 | 1.8 |
| `knife_gypsy_jackknife` | Navaja Knife | 34/22/57 | 80/170 | 43/29 (2.69/1.81) | 0.39/0.31/0.84 | 1.6 |
| `knife_outdoor` | Nomad Knife | 41/25/67 | 90/180 | 49/33 (3.06/2.06) | 0.52/0.4/1.02 | 1.7 |
| `knife_stiletto` | Stiletto Knife | 33/21/60 | 80/175 | 42/30 (2.62/1.88) | 0.37/0.3/0.78 | 1.6 |
| `knife_widowmaker` | Talon Knife | 35/23/58 | 82/170 | 44/29 (2.75/1.81) | 0.4/0.32/0.85 | 1.6 |
| `knife_skeleton` | Skeleton Knife | 40/26/65 | 91/180 | 48/33 (3.00/2.06) | 0.48/0.4/1 | 1.7 |
| `knife_kukri` | Kukri Knife | 52/33/78 | 112/200 | 58/36 (3.62/2.25) | 0.7/0.52/1.2 | 1.8 |

Knife finishes use the normal skin system: craft a knife with its matching Weapon Skin, use `/csmc skin`, or the skin picker (K or `/csmc skins`) while holding a knife. The Weapon Case can roll knife finishes, always in the gold ★ (`immortal`) tier at that tier's weight; the Loadout Case rolls finishes only for the guns and knives you carry.

Knife finish bars, rarity tooltips and the picker's rarity filter all use that gold ★ tier, independent of the paint kit's gun rarity. Doppler and Gamma Doppler labels distinguish phases and gems everywhere skins are named, for example **Doppler (Phase 2)**, **Doppler (Ruby)** and **Gamma Doppler (Emerald)**.

## Gloves

8 glove items, one per CS2 glove type, appear in the **CSMC Gloves** creative tab: `csmc:<id>` where `<id>` is the CS2 item name.

| Item (`csmc:`) | Name | Recipe (`L` = leather) |
|---|---|---|
| `studded_bloodhound_gloves` | Bloodhound Gloves | `L_L/LXL`, `X` = gold nugget |
| `sporty_gloves` | Sport Gloves | `L_L/LXL`, `X` = lime wool |
| `slick_gloves` | Driver Gloves | `L_L/LXL`, `X` = black dye |
| `leather_handwraps` | Hand Wraps | `L_L/LXL`, `X` = string |
| `motorcycle_gloves` | Moto Gloves | `L_L/LXL`, `X` = iron ingot |
| `specialist_gloves` | Specialist Gloves | `L_L/LXL`, `X` = green dye |
| `studded_hydra_gloves` | Hydra Gloves | `L_L/LXL`, `X` = prismarine shard |
| `studded_brokenfang_gloves` | Broken Fang Gloves | `L_L/LXL`, `X` = bone |

**Glove slot.** The survival inventory has one extra slot directly above the offhand (shield) slot. It holds one glove of any type and nothing else. Click a glove into it or shift-click a glove from the main inventory, hotbar or offhand while the slot is empty; shift-click the worn glove to move it back into the inventory. In creative mode the slot can also be edited from the creative inventory; it still only takes one glove. An empty slot means the default CT arms gloves. Gloves have no attack behaviour.

The worn glove is saved with the player. On death it drops like armour unless `keepInventory` is on or the player is a spectator, in which case it is still worn after respawn; it is also kept through dimension changes. The server sends the worn glove (only the item and its finish) to its wearer and to every player tracking them when it changes, on join, on respawn and when tracking starts. The worn glove and its finish replace the gloves on the CS2 first-person arms for guns and knives; third person is unchanged.

**Finishes.** Glove finishes use the normal skin system, as knives do: craft a glove with its matching Weapon Skin (a lone finished glove removes the finish and returns it), use `/csmc skin`, or the skin picker while holding the glove. The Weapon Case can roll glove finishes, always in the gold ★ (`immortal`) tier alongside knives. The Loadout Case stays weapon-only: carried or worn gloves never count and never roll.

### Gloves: client rendering and sync

- **Glove slot.** The survival inventory draws a vanilla-style slot frame at `GloveSlot.SLOT_X/SLOT_Y` (directly above the offhand slot) with the `csmc:item/empty_glove_slot` outline icon and a "Gloves" tooltip while empty. The creative inventory's survival tab shows the same slot left of the offhand slot (at 9, 20); without the client mixin, vanilla would place menu slot 46 on top of a hotbar slot.
- **Sync.** Each `GloveSyncPayload` (item plus finish only) is kept as a client-only render snapshot per entity id, because tracking data can arrive before the entity spawns. An empty payload is stored as an authoritative empty snapshot (no glove); an absent snapshot means none has been received. Snapshots are never written into the glove slot or `GloveSlot.ATTACHMENT`: the inventory shows the menu's own synced stack with all its components. The first-person arms use the local player's snapshot, falling back to the menu-synced attachment only until a snapshot arrives. Other players' snapshots (`GloveClient.remoteGlove`) are kept for future third-person use; nothing renders them yet.
- **First-person arms.** For guns and knives, the worn glove's `manifest.json` → `gloves.<id>` viewmodel mesh (any status except `failed`, with its file present) replaces the team arms' `arms_gloves` mesh (the default gloves and arms). Sleeves stay, and the glove mesh's bare arm is cut under them. Its glove primitives (every material except `bare_arm*`) take the finish's `skins/<id>/<kit>/color.png`, loaded through the skin texture pools. The default gloves stay when the slot is empty, the glove's record is missing or failed, or its mesh is still loading. The embedded glove texture is used until the finish is ready. At most 2 worn-glove meshes are resident; all are disposed on resource reload and disconnect. Disconnect also releases every finish texture and glove item model and cancels their pending loads.
- **Glove items.** In the GUI, in hand and on the ground, a glove item renders the glove primitive of its viewmodel mesh that indexes the most vertices, with its finish. It is fitted to the bind-pose bounds of the vertices that primitive draws (the export's primitives share one vertex accessor). Until that loads, or when the asset is missing, it uses the item's baked placeholder model. Held gloves never play viewmodel clips.
- **Picker.** `K` with a glove in the main hand lists that glove's finishes (kits whose `available_weapons` include the glove id), all with gold ★ bars, as knives have.

## Throwable grenades

Six separate utility items appear in the CSMC creative tab:

| Item ID | Effect |
|---|---|
| `csmc:hegrenade` | Fused explosion, distance/cover-dependent damage and knockback, plus material-aware terrain damage. |
| `csmc:flashbang` | Fused whiteout with hold/fade, attenuated by distance, facing and line of sight. |
| `csmc:smokegrenade` | Expanding smoke that obscures the view, hides name tags through dense smoke and extinguishes grenade fire. |
| `csmc:molotov` | Bounded fire pool on landing; damages exposed entities and flammable blocks. |
| `csmc:incgrenade` | Separate incendiary item/model, sharing the authored fire-pool behavior. |
| `csmc:decoy` | Harmless positional gun reports; no bullets, damage, recoil or ammunition use. |

Grenades throw like Counter-Strike: with a grenade in your **main hand**, hold a button to pull the pin and release it to throw. Off-hand grenades do not throw.

| Hold, then release | Throw |
|---|---|
| Attack (left click) | Full overhand throw |
| Use (right click) | Short underhand lob |
| Both | Medium throw |

Pressing a button while primed re-aims the throw at the buttons now held; letting go of one of two keeps the medium throw, so releasing both in either order throws medium. A quick click still throws. While a grenade is in your main hand, attack and use do not punch, mine or interact (spectators keep normal clicks). Switching slots, opening a screen, switching to another window or dying while the pin is out cancels the throw and keeps the grenade; release both buttons before priming again. While primed, the held grenade rises and a yellow line shows the predicted path. A ring marks where it comes to rest, lands (molotov/incendiary), detonates (HE/flash) or is put out by water (blue ring), and stays visible through blocks. The preview runs the server's grenade physics, including walls, the world border, water and currents, but does not predict glancing off players or mobs, slowdown in cobwebs or powder snow, or blocks that change mid-flight. It updates every tick from your current aim. Press **G** (Controls → CSMC → *Toggle grenade trajectory preview*) to turn the preview off or on. It is on by default.

The server computes each throw from its own view of the player, following CS:GO `ThrowGrenade`. The aim tilts up by `10 * (90 - |pitch|) / 90` degrees. Speed is `675 * (0.3 + 0.7 * strength)` units/s, where strength is overhand 1, both 0.5 and lob 0. The grenade starts at your eyes, drops up to 0.75 blocks for a lob and is pushed up to 22 units forward; floors, walls and the world border stop both offsets, so it never spawns inside a block. Throws inherit 1.25× your movement, so running and jumping throws carry. Survival consumes one item; creative does not. Grenades stack to 16 and share a short throw cooldown. They bounce and settle; fuse evaluation and effects run on the server. In unloaded chunks, saved fuse/effect time pauses.

Authored defaults are in `GrenadeTuning.java`: HE/flash fuse 1.5 seconds; smoke grows over one second and lasts 18 seconds; fire lasts seven seconds; decoy reports last 15 seconds. These are Minecraft adaptations, not a claim of exact CS2 simulation. Fire pools are bounded hazards, not uncontrolled vanilla fire spreading across the world. Water and smoke extinguish them. Terrain changes require a resolved owning player; disconnected ownership never becomes an unrestricted anonymous blast.

## Equipment: healthshot, C4 and defuse kit

Three equipment items are registered in the existing CSMC Firearms creative tab, alongside the grenades:

| Item ID | Role |
|---|---|
| `csmc:healthshot` | Single-use heal, injected by holding use. |
| `csmc:c4` | Placed bomb with a timed fuse. |
| `csmc:defuse_kit` | Reusable passive kit that shortens defusing. |

Each recipe is a shapeless crafting-grid recipe (any arrangement) that yields one item.

| Item ID | Ingredients |
|---|---|
| `csmc:healthshot` | Glass bottle, iron nugget, glistering melon slice |
| `csmc:c4` | TNT, redstone dust, copper ingot, iron ingot |
| `csmc:defuse_kit` | Shears, redstone dust, iron ingot |

After a cancel, fresh equipment use or defuse can start on the following server tick at the earliest (a one-tick minimum restart gap).

### Healthshot

- Hold use to inject. The inject takes 38 ticks (1.9 seconds), matching the authored injection clip.
- On completion, heal 10 health points. Healing never exceeds maximum health (no overheal). The item is consumed when the inject completes.
- Releasing use, switching the held item or slot, or dying cancels the inject and keeps the item.
- After each completed inject, a 20-tick (1 second) cooldown blocks the next inject.

### C4

- Hold use while looking at the top face of a solid block within reach. After 3 seconds the C4 is planted on that face and one item is consumed. Releasing use before 3 seconds cancels the plant and keeps the item.
- The plant uses the final point the server validates on the same support block's top face when the hold completes; reach and line of sight are rechecked at that moment.
- The fuse lasts 40 seconds from the plant.
- On detonation, the blast damages entities within an 8-block radius with CS-style Gaussian falloff: damage decreases with distance and ends at the radius.
- Damage follows the server's PvP, team and owner rules. Players excluded by those rules are never damaged by the blast.
- Terrain is not destroyed by default. Setting the gamerule `csmcC4Breaking` to `true` opts C4 blasts into terrain damage through the existing BlockDamage policy. `csmcBlockBreaking` must also be enabled for terrain to break.
- The fuse pauses while the planted C4's chunk is unloaded and resumes when the chunk loads.

### Defuse

- Hold use on a planted C4 for 10 seconds. With a defuse kit anywhere in your inventory, the hold takes 5 seconds.
- Defusing needs clear sight to the physical bomb. Doors, trapdoors and cover block the hold even when the aim is within tolerance.
- Releasing use or any other interruption resets defuse progress to zero; the next attempt starts over.
- A completed defuse removes the planted C4 without an explosion.
- The defuse kit is not consumed, so one kit can defuse any number of bombs.
- Continuing to hold use on an active defuse does not restart its timer; the restart gap applies only after cancellation. Only the current defuser can renew an active hold. A player displaced by another defuser must start a fresh attempt and observes the same restart gap.

### Equipment: client

- Healthshot and C4 use a dedicated equipment viewmodel route, with exported CS2 arms, gloves and draw/idle/use clips. C4 also supports **F** inspect. Its authored 4-second plant clip is scaled to the server's 3-second planting action.
- While a healthshot is held, the bottom-right HUD box (ammo-HUD style) shows how many healthshots you carry; the ammo HUD stays hidden for all equipment, so no ammo is shown. Injecting, planting and defusing show a centred progress bar with server-confirmed progress; releasing use or changing the item in the active hand cancels the held action.
- Holding use on a planted C4 sends a defuse heartbeat every 5 ticks; releasing use, looking away, switching slots, opening a screen, losing window focus or dying sends a cancel. Held injection/planting also releases on screen or focus loss. On disconnect the server's 10-tick lease simply expires. Defuse progress appears only when the bomb's synchronized defuser identity matches you; the client never decides defuse success.
- The planted bomb renders its cached world model in the planted pose, with an approximated blinking indicator and countdown. These indicators are Minecraft presentation, not a recreation of Valve's animated screen shader.
- Cached equipment sounds play positionally. Draw, inject and plant sounds follow the clip's authored sound markers (healthshot prepare at 0.13 s and thud at 0.67 s; C4 initiate and keypresses scaled from the 4-second clip to the 3-second plant) and stop when the action is cancelled or the item is switched. Inject success, the plant, bomb beeps, disarm start/finish and the explosion follow server events; a cancelled defuse stops the disarm sound. A missing cache file stays silent.
- Changing the selected main-hand slot cancels only its affected sounds; an uninterrupted offhand injection keeps its remaining sound cues.
- Positional equipment audio is downmixed to mono before playback. Cancelled or unloaded actions cannot start a pending decoded sound later. Scheduled clip sounds stop advancing during a single-player pause; session cleanup still runs.
- Third person: a held C4 uses a two-handed hold pose, and injecting or planting uses a two-handed charge pose.
- The defuse kit is passive equipment with a world model and equip sound; there is no invented first-person kit animation.

### Not implemented

The riot shield, Danger Zone equipment (bump mine, breach charge, tablet, tactical-awareness grenade, zone repulsor, rockets and launchers), fists, melee tools, snowball and parachute are outside this scope. The local CS2 export does not contain complete models, clips or definitions for them.

## Skins and cases

Weapon skins are paint-kit attachments applied to guns, knives and gloves. Each machine builds its own skin catalog
from its own CS2 export (`--skins`, see [install.md](install.md#export-modes)); only the attachment's paint-kit ID,
wear and seed synchronize.

### Catalog and rarity weights

The server and clients read their own skin index; only the attachment's paint-kit ID, wear and seed synchronize. The catalog notices index changes within about two seconds. Case rolls use positive, integral index weights up to 1,000,000,000. Missing/invalid tiers use authored defaults:

| Tier | Default weight |
|---|---:|
| common | 10,000,000 |
| uncommon | 2,000,000 |
| rare | 400,000 |
| mythical | 80,000 |
| legendary | 16,000 |
| ancient | 3,200 |
| immortal | 640 |

Unknown rarity names use common weight. The current CS2 data provides weights through mythical; the remaining defaults are not official case odds.

### Crafting, applying and the picker

- Craft a **Weapon Case** with eight iron ingots around a chest. Right-click to consume it and receive one skin attachment. Missing local skin inputs leave the case unconsumed.
- Craft a **Loadout Case** from a Weapon Case with a compass above and three iron ingots (left, right, below). It works like the Weapon Case, with the same rarity weights, grant-first ordering and reel, but only rolls skins for guns and knives you currently carry: every distinct csmc gun or knife anywhere in your inventory (main inventory, hotbar, offhand and armor slots, skinned or not) counts once. The reel filler is drawn from the same guns, so it shows only your weapons, and the reel is titled "Opening Loadout Case". With no gun, or no available skin for any gun you carry, the case is kept, nothing is consumed and a chat message explains why.
- Opening a case plays a CS-style reel. The server rolls the result, puts the attachment in the inventory (a last case becomes the attachment in the same hand), consumes one case and then sends the opener a 50-tile strip, drawn by the same rarity weights from a server seed, with the result on tile 45. The client scrolls it for six seconds with a quartic ease-out, ticks as each tile passes the gold marker and stops at a random point inside the result tile, then shows a reveal panel with a rarity sound. Bars use CS colours: grey, light blue, blue, purple, pink, red, gold (`immortal`). The screen is cosmetic: Esc, closing early or disconnecting loses nothing, and opening another case replaces the reel. If another screen (such as the inventory) is open when the strip arrives, the animation is skipped. Tiles show the skinned weapon (fillers at their kit's minimum wear, seed 0). Only tiles on screen are drawn. When the strip arrives, the model and 256 px skin thumbnail of every tile are requested, the result first and then outward from it, since those tiles scroll slowest and stay up for the reveal. The thumbnail pool (below) holds a whole strip. The reel waits up to 1.5 s for the result's model and thumbnail before scrolling. A tile whose model is still loading shows the attachment icon and "Loading…"; without a local model it shows the attachment icon with its names.
- Case art: run `python3 tools/cs2_case.py` (same `--vpk`, `--cli` and `--cache` defaults as the exporter; `--list` prints every icon name under `panorama/images/econ/weapon_cases/`; `--case <name>` picks one, default `crate_valve_1`, the original CS:GO Weapon Case; `--item weapon_case|inventory_weapon_case` picks the csmc item it is for, default `weapon_case`). It writes your own install's econ icon to `<cache>/case/<item>.png` plus `case/<item>.source.json`; nothing is written into the repository. For the Loadout Case, `python3 tools/cs2_case.py --case crate_community_4 --item inventory_weapon_case` exports the Operation Breakout case. A missing archive chunk is reported as `pak01_NNN.vpk`. Clients read the icons on resource load (F3+T after exporting), crop them, reduce them to 64×64 pixel art and extrude them like a vanilla item. Without an export, or if it cannot be read, the authored placeholder `assets/csmc/textures/item/<item>.png` is used: a dark crate with an orange latch for the Weapon Case, a navy crate with a teal latch for the Loadout Case.
- Craft a gun together with a matching **Weapon Skin** to apply it. The magazine and unrelated item data are preserved.
- Swapping returns the previous skin as an attachment. Crafting a skinned gun alone removes and returns its skin. Attachments only fit their named weapon.
- Operators can use `/csmc skin <name_or_id> [wear] [seed]` while holding a gun or knife to receive an attachment. Numeric IDs work without a server catalog; names and known kit compatibility use local metadata. Quote display names containing spaces, for example `/csmc skin "Gamma Doppler (Phase 3)"`; shared display names resolve to a kit supporting the held weapon. Explicit numeric IDs and internal names still identify a specific kit and are checked for weapon compatibility. The command does not require server-side textures.
- **Skin picker:** press **K** (rebindable under Controls → CSMC) or run `/csmc skins` while holding a gun. It lists every kit your local catalog makes available for that weapon, plus **No skin (default)**, as a grid of skinned previews with rarity-coloured bars that fills the space beside the preview panel (at most 28 tiles at once; the mouse wheel scrolls it). Long names end in "…"; hover a tile for its full name and rarity. Type in the search box to filter by name; the seven rarity colour swatches (hover for the grade name) isolate tiers: click one to show only it, more to add, the last one off restores all. Click a tile for a larger preview, then set wear (0–1, starts at the kit's minimum wear, labelled with its wear tier) and pattern seed (0–1000, or **Random**) and press **Apply**. Operators (permission level 2, as for `/csmc skin`) skin the held gun directly: the previous skin is simply replaced, not returned, and the magazine and other item data are kept; **No skin** clears it. The server checks the held gun, kit compatibility with the same rule as `/csmc skin` (numeric kits missing from the server catalog are applied as-is), wear and seed bounds, and accepts one request per quarter second. Non-operators can browse, but **Apply** is disabled. Only on-screen tiles plus prefetch within what the GUI thumbnail pool has left load skin thumbnails.

### Appearance and resource limits

**Shine.** Guns and knives, skinned or not, get a CS-style specular finish in first person, third person and inventory/GUI icons: GGX highlights from a sun (the key item light) and a fill light, a sky/sun/ground environment reflection with Fresnel that metal tints with its colour, and a view-angle hue shift on pearlescent paints (Doppler and Fade shift visibly as the weapon turns, e.g. during inspect). Per-texel roughness and metalness come from the body's `shine_mask_<mesh set>.png` (so steel, polymer and wood parts differ); on skins, painted areas use the kit's `shine.json` paint roughness/metalness/pearlescence and edges the wear float exposes fall back to the bare-metal values. All added light is scaled by the block/sky lightmap, so finishes never glow in the dark; GUI icons use a fixed view direction. Turn it off with **Skin shine** in the viewmodel settings screen (`shine` in the viewmodel config, default on). Shine masks have their own GPU pool: 16 slots of at most 512 px (16 MiB), counted with the 32 full composites (128 MiB) and 96 thumbnails (24 MiB) in `SkinTextureBudget` (168 MiB of a 176 MiB ceiling). Shine render layers are cached for at most 256 base textures (every resident skin slot plus unskinned weapon textures) with 8 finish/wear combinations each, least recently used first, and dropped on resource reload and disconnect. A cache without `shine.json` or masks, or a newer `shine_schema`, keeps the matte base-colour look; BLEND primitives and gloves are always matte. While an Iris shader pack is active, weapons also use the matte layer: Iris replaces custom core shaders, which would otherwise hide the weapon. CSMC reads Iris's public `IrisApi.isShaderPackInUse()` by reflection once per client tick (Iris stays an optional, undeclared mod; if its API cannot be read, shine stays off while Iris is loaded). Iris without a shader pack, and Sodium, keep shine; neither is tested in game.

This is an albedo-only approximation, not Valve's complete material shader. Custom paint/gunsmith artwork is UV-mapped; projected styles use seeded UV approximations. Patina and case-hardening use tinted-metal approximations. Wear and pattern-seed behavior are not CS2-identical.

Wear follows the shape of CS2's weapon shader: scratches only remove paint where the weapon's cavity map marks exposed edges, scaled by `1 + 6 × wear`. Flat surfaces keep their paint even on Battle-Scarred skins. For custom paint, pattern alpha below 128 makes the paint more durable (0 never wears). The pattern-alpha masking band around 196 and the weapon's no-paint map are not applied.

Legacy kits select the legacy model's UV mesh. Wear is quantized to 256 display buckets while exact item wear and seed remain stored. Incomplete client inputs retain the default weapon texture. Skins composite asynchronously into two separate GPU pools, each retiring least-recently-used entries and clearing on resource reload:

- **In-hand (full):** up to 32 variants at 1024 px (4 MiB each), on two loader threads. Used everywhere except GUI icons.
- **GUI thumbnails:** up to 96 variants at 256 px (256 KiB each), on up to two loader threads. Used by case reels, the skin picker and inventory icons, so they never evict a held gun's texture. Thumbnail inputs are decoded subsampled (at least twice the thumbnail size), which is about three times faster than a full decode.

Decoded inputs are shared across variants in an in-memory cache of at most an eighth of the heap (192 MiB cap), keyed by file identity, so the hard-linked shared grunge, wear and per-weapon maps decode once. Decode scratch in flight is capped at a quarter of the heap (256 MiB cap); one larger image decodes alone. Thumbnails are also saved to `<cache>/skins/.composited/v<N>/<weapon>/` and reused on later reels, keyed by kit, wear bucket, seed, compositor version and the size and mtime of every input, so a changed input or compositor recomposites. Once per session the client deletes other `v<N>` folders and trims the current one to 256 MiB, least recently used first, without following symbolic links (a symlinked `.composited` is left untouched). The folder is derived data: delete it any time; never commit it.

To check a skin in game without any input, add `-Dcsmc.devSkin=<weapon>:<paint_kit>[:<wear>[:<seed>]]` to the dev smoke hook. Each joining player's selected hotbar slot gets that skinned gun. For example, `JAVA_TOOL_OPTIONS="-Dcsmc.devScreenshot=true -Dcsmc.devSkin=ak47:801:0.05:0"` captures an AK-47 | Asiimov.

## Crafting recipes

Every registered item is survival-obtainable: craft all 36 firearms, all 22 knives, all 8 gloves (see [Gloves](#gloves)), all four ammunition types, all six grenades and weapon cases using vanilla materials. **`csmc:weapon_skin` is the only direct-recipe exemption:** open a crafted weapon case to obtain an attachment bound to a real paint kit from the local skin catalog. No standalone recipe invents a paint kit. A gun plus its matching skin uses the existing special application recipe; a skinned gun alone removes the skin.

Use a crafting table. Patterns below show rows separated by `/`, with `_` for an empty slot; horizontal mirrors also work. Recipes do not require recipe-book unlocks: place the ingredients manually. Each recipe makes one item unless a batch size is shown.

For firearms: `I` = iron ingot, `R` = redstone dust, `S` = stick, `B` = iron block, `G` = glass pane, `C` = copper ingot. Pistols use 2–4 iron ingots (the taser uses two and one copper); SMGs use 4–5, rifles use 5–6, and snipers/machine guns also require an iron block.

| Item (`csmc:`) | Pattern |
|---|---|
| `glock` | `II_/_R_/_S_` |
| `hkp2000` | `_I_/IR_/_S_` |
| `usp_silencer` | `III/_R_/_S_` |
| `p250` | `II_/_R_/S__` |
| `fiveseven` | `_II/IR_/_S_` |
| `tec9` | `II_/IR_/_S_` |
| `cz75a` | `I_I/_R_/_S_` |
| `deagle` | `III/IR_/_S_` |
| `elite` | `I_I/R_R/S_S` |
| `revolver` | `III/_R_/IS_` |
| `mac10` | `III/RS_/_I_` |
| `mp9` | `III/IR_/S__` |
| `mp7` | `III/_RI/S__` |
| `mp5sd` | `III/_R_/S_I` |
| `ump45` | `III/R_I/_S_` |
| `p90` | `III/IRI/_S_` |
| `bizon` | `III/RI_/_S_` |
| `famas` | `III/IRR/IS_` |
| `galilar` | `III/RIR/IS_` |
| `ak47` | `III/RRI/IS_` |
| `m4a1` (M4A4) | `III/IRR/SI_` |
| `m4a1_silencer` (M4A1-S) | `III/RIR/SI_` |
| `aug` | `III/RRI/SI_` |
| `sg556` | `III/IIS/RI_` |
| `ssg08` | `IGI/BIR/_S_` |
| `awp` | `IGI/BIR/IS_` |
| `scar20` | `IGI/IBR/IS_` |
| `g3sg1` | `IGI/BIR/SI_` |
| `m14` | `IGI/IBR/SI_` |
| `nova` | `III/_R_/ISS` |
| `xm1014` | `III/IR_/ISS` |
| `mag7` | `III/_RI/ISS` |
| `sawedoff` | `II_/_R_/ISS` |
| `m249` | `III/BRI/IS_` |
| `negev` | `III/IBR/IS_` |
| `taser` | `_C_/IRI/_S_` |

Utility recipes use the per-row ingredient key below (`I` always means iron ingot and `G` means gunpowder here). Minecraft 1.21.1 has no vanilla copper nugget; the pistol and rifle batches use iron nuggets, the sniper batch uses iron ingots with a nugget, and the shell batch uses iron nuggets with paper.

| Item (`csmc:`) | Pattern | Other ingredients / output |
|---|---|---|
| `pistol_ammo` | `_N_/NGN/_N_` | `N` = iron nugget; **16 rounds** |
| `rifle_ammo` | `NGN/NGN` | `N` = iron nugget; **16 rounds** |
| `sniper_ammo` | `IGI/INI` | `I` = iron ingot, `G` = gunpowder, `N` = iron nugget; **8 rounds** |
| `shotgun_shells` | `NPN/NGN` | `N` = iron nugget, `P` = paper, `G` = gunpowder; **8 shells** |
| `hegrenade` | `_I_/ITI/_G_` | `T` = TNT |
| `flashbang` | `_I_/ILI/_R_` | `L` = glowstone dust, `R` = redstone dust |
| `smokegrenade` | `_S_/ICI/_G_` | `S` = string, `C` = coal |
| `molotov` | `_S_/_F_/_V_` | `S` = string, `F` = fire charge, `V` = glass bottle |
| `incgrenade` | `_S_/IFI/_C_` | `S` = string, `F` = fire charge, `C` = clay ball |
| `decoy` | `_I_/GNG/_R_` | `N` = note block, `R` = redstone dust |
| `weapon_case` | `III/ICI/III` | `C` = chest; existing recipe unchanged |
| `inventory_weapon_case` | `_M_/IWI/_I_` | `M` = compass, `W` = Weapon Case |
| `weapon_skin` | No standalone pattern | Open a weapon case; see the explicit exemption above |

Knives: the two default knives come from iron and a stick; every other knife upgrades a default knife (either side) with its own material placed above it. Crafting consumes the default knife.

| Item (`csmc:`) | Pattern | Other ingredients |
|---|---|---|
| `knife` | `I/S` | `I` = iron ingot, `S` = stick |
| `knife_t` | `_I/S_` | `I` = iron ingot, `S` = stick |
| `bayonet` | `M/K` | `M` = iron ingot, `K` = Knife (CT) or Knife (T) |
| `knife_css` | `M/K` | `M` = leather, `K` = Knife (CT) or Knife (T) |
| `knife_flip` | `M/K` | `M` = feather, `K` = Knife (CT) or Knife (T) |
| `knife_gut` | `M/K` | `M` = slime ball, `K` = Knife (CT) or Knife (T) |
| `knife_karambit` | `M/K` | `M` = gold ingot, `K` = Knife (CT) or Knife (T) |
| `knife_m9_bayonet` | `M/K` | `M` = iron block, `K` = Knife (CT) or Knife (T) |
| `knife_tactical` | `M/K` | `M` = bone, `K` = Knife (CT) or Knife (T) |
| `knife_falchion` | `M/K` | `M` = quartz, `K` = Knife (CT) or Knife (T) |
| `knife_survival_bowie` | `M/K` | `M` = copper ingot, `K` = Knife (CT) or Knife (T) |
| `knife_butterfly` | `M/K` | `M` = diamond, `K` = Knife (CT) or Knife (T) |
| `knife_push` | `M/K` | `M` = ender pearl, `K` = Knife (CT) or Knife (T) |
| `knife_cord` | `M/K` | `M` = string, `K` = Knife (CT) or Knife (T) |
| `knife_canis` | `M/K` | `M` = flint, `K` = Knife (CT) or Knife (T) |
| `knife_ursus` | `M/K` | `M` = obsidian, `K` = Knife (CT) or Knife (T) |
| `knife_gypsy_jackknife` | `M/K` | `M` = amethyst shard, `K` = Knife (CT) or Knife (T) |
| `knife_outdoor` | `M/K` | `M` = emerald, `K` = Knife (CT) or Knife (T) |
| `knife_stiletto` | `M/K` | `M` = blaze rod, `K` = Knife (CT) or Knife (T) |
| `knife_widowmaker` | `M/K` | `M` = prismarine shard, `K` = Knife (CT) or Knife (T) |
| `knife_skeleton` | `M/K` | `M` = bone block, `K` = Knife (CT) or Knife (T) |
| `knife_kukri` | `M/K` | `M` = netherite scrap, `K` = Knife (CT) or Knife (T) |
