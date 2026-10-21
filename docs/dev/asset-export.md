# CS2 asset exporter reference

Reference for maintainers and advanced users: every export mode of `tools/export-cs2-assets` and `tools/cs2_case.py`,
the cache layout each mode writes, the `manifest.json` record keys, and the development hooks. The player walkthrough
(prerequisites, option table, recommended order, troubleshooting) is [install.md](../install.md); upstream data
research is [cs2-data.md](cs2-data.md).

All outputs are Valve-derived: personal use only, never committed, never shared (see the rules in
[install.md](../install.md#rules)).

## Shared behavior

- Every mode reads the same VPK index and writes only under the cache root (default `~/.csmc/cache`); nothing is ever written into the repository. See [install.md](../install.md) for options, prerequisites and the recommended order.
- `--arms`, `--gloves`, `--knives`, `--grenades`, `--equipment`, `--skins`, `--icons` and `--gun-clips` are separate modes; do not combine them in one invocation. `--equipment` explicitly rejects combination with the other modes. A fresh cache and the frozen exporter avoid mixing old and new animation/catalog formats.
- Firearm export includes its viewmodel clips; `--gun-clips` is the separate clip-refresh mode.

## Animation mapping

For the AK47 worldmodel, the manifest's `animations` mapping is `{"fire":"shoot","reload":"reload","inspect":"inventory_inspect"}` when those clips export. `draw` is deliberately left unset; this model has no draw clip. The exporter (`ACTION_CLIPS` in `tools/export-cs2-assets`) maps `inspect` to the first clip present from `inspect`, `inventory_inspect`. Rerunning the exporter against the same source refreshes `animations` for every cached weapon without re-exporting models.

## Weapon skins (`--skins`)

Export paint-kit inputs and the skin index from your own CS2 install:

```sh
python3 tools/export-cs2-assets --skins
```

This mode does not export models or audio. `--weapons` narrows the pairs refreshed; existing associations for other weapons remain. Normal `--vpk`, `--cli` and `--cache` options apply.

Under `<cache>/skins/`:

- `index.json`: kit names, compatible/available weapons, styles, wear ranges, rarities and optional `rarity_weights`.
- `<weapon>/<paint_kit>/params.json` and input PNGs, hard-linked from `_textures/raw_*.png`.
- `_sources/`: parsed material/composite documents.
- `missing.json` and `filelist-missing.txt`: missing dependencies and a DepotDownloader filelist.

- `<weapon>/<paint_kit>/shine.json` and `<weapon>/_default/shine.json` (unskinned guns and knives): `{shine_schema: 1, roughness, metalness, pearlescence, style}`. `roughness` is the kit's `g_flPaintRoughness` clamped to 0.04–1 (default 0.6); `metalness` is the largest `g_vPaintMetalness` slot, at least 0.8 for anodized styles 4–6 (Fade, Doppler, Marble Fade); `pearlescence` is `|g_flPearlescentScale| / 6` clamped to 0–1, at least 0.35 for multicolour anodized styles 5–6 (Doppler, Fade) so their hue shift reads. Defaults: guns roughness 0.55 / metalness 0.45, knives 0.3 / 0.9. Gloves, grenades and equipment get none. `python3 tools/cs2_skins.py --shine [--cache DIR]` (also honours `CSMC_CACHE`) writes only these files into an existing cache, leaving every other record untouched; `--skins` writes them too. Like every export mode, it refuses a cache inside the repository or containing symlinks before writing anything. Each file is written to a fresh, exclusively created temporary file beside it and atomically renamed into place, so concurrent runs or a stale temporary path cannot redirect or corrupt another file. Clients treat a missing file or another `shine_schema` as matte.
- `<weapon>/_default/shine_mask_<mesh set>.png` (one per gun/knife body, e.g. `body_hd`, `body_legacy`; at most 512 px, RGB, in that body's painted-atlas UVs): R bare roughness and G bare metalness (the glTF ORM G/B of the base-colour texture drawing the most vertices in that mesh set), B paint-wear exposure (linear red of the kits' `cavity.png` for that body; 0 if no kit has one). Unskinned weapons shine from the mask alone; skinned ones use the kit's `shine.json` paint values where paint survives and the mask's bare values where wear exposes metal. A body without a mask stays matte. `--shine` and `--skins` write these too (92 masks, ~30 MB on the full cache).

Raw RGBA channels are preserved: Source texture alpha often contains packed masks, not opacity. Each run refreshes item, localization and weapon metadata from the selected VPK. A pair is available only when the compositor's required inputs exist.

## Knife export (`--knives`)

Export all 22 knife types (CT/T defaults plus the 20 ★ knives; `knifegg`, `knife_twinblade` and the hidden `default` are excluded):

```sh
tools/export-cs2-assets --knives                      # models, viewmodel clips and sounds
tools/export-cs2-assets --knives --weapons knife_karambit,bayonet
tools/export-cs2-assets --skins --weapons knives      # knife×kit skin inputs only; gun pairs keep their published files
```

Knife records are found by `m_szName` because most knife entries in `weapons.vdata` use numeric keys. Model stems are irregular (`knife_m9`, `knife_bowie`, `knife_navaja`, `knife_talon`, …) and always come from `m_szWorldModel`.

Cache layout (all under the cache root; nothing enters the repository):

| Path | Contents |
|---|---|
| `manifest.json` → `weapons.<id>` | `kind: "knife"`, `status`, `models.world` (knife mesh; embedded clips are only `inventory_inspect`/`inventory_icon`), `viewmodel_clips`, `animations`, `sounds_manifest`. |
| `models/<id>/<id>.gltf` | World/knife mesh with PNG/bin sidecars. |
| `models/<id>/clips/<clip>.gltf` | One glTF per viewmodel clip from `animation/anims/viewmodel/knife/<stem>/*.vnmclip`, holding the shared viewmodel arm skeleton (bone `wpn` carries the knife) and the knife's own skeleton (`weapon`, `blade`, `weapon_hand_l`, …), sampled together. The CT default knife uses folder `_default_knife`. |
| `sounds/<id>/manifest.json` + `*.ogg` | Same shape as guns; `actions`: `deploy`, `slash`, `stab`, `hit`, `hitwall`, `backstab`, `inspect`. The `slash_backstab` event plays `backstab` sounds. |
| `skins/<id>/<kit>/params.json` + PNGs | Knife paint inputs, using the knife's HD `composite_inputs` (knives have no legacy customization inputs). |

`animations` maps actions to clip names (keys of `viewmodel_clips`): `draw` → `draw_*`, `slash` → `light_miss1_*`, `slash_alt` → `light_miss2_*`, `slash_hit` → `light_hit1_*`, `slash_backstab` → `light_backstab_*`, `stab` → `heavy_miss1_*`, `stab_hit` → `heavy_hit1_*` (Navaja has none; falls back to `heavy_miss1_*`), `backstab` → `heavy_backstab_*`, `inspect` → `lookat01_*`, `idle` → `idle1_*`/`idle_*`. Draw/inspect variants (`draw2`, `lookat02`, `lookat01_draw`, `transfix`) are exported but unmapped.

Sounds use the common `Weapon_Knife.*` events (`Deploy`, `Slash`, `Stab`, `Hit`, `HitWall`, `Hit.Heavy.Backstab`, `Inspect.Turn`). Knife-specific events replace an action where CS2 authors them: Butterfly draw/backstab/look, Falchion draw/inspect, Bowie draw/look-at, Shadow Daggers draw/swish/attack/backstab/look-at, Ursus flips, Talon deploy/look-at, Stiletto draw/flips, Flip draw, Navaja draw/open, Paracord flips, Skeleton spin, Karambit catch, Nomad draw/gear. The table lives in `tools/cs2_knives.py`.

Knife finishes have no `[kit]weapon_*` association in `items_game`. The skin export derives knife×kit pairs from the VPK's generated econ icons `panorama/images/econ/default_generated/weapon_<id>_<kit>_light_png.vtex_c` (556 pairs for 20 knives; the CT/T defaults have none) and adds the knife ids to `weapons`/`available_weapons` in `skins/index.json`. Knife viewmodel clips need `pak01_473.vpk`; knife models, sounds and finishes otherwise use chunks the gun export already needs. Knife and gun exports never prune each other's records.

## Grenade export (`--grenades`)

Export your own models and grenade audio:

```sh
tools/export-cs2-assets --grenades \
  --extra-vpk ~/csmc-assets/cs2/game/core/pak01_dir.vpk
```

Use `--weapons hegrenade,flashbang` to narrow that export. The optional extra archive supplies shared explosion audio absent from the CS2-specific index. Its referenced numbered chunks must also be present. Grenade export preserves existing firearm records; later firearm export preserves grenade records. Models, sounds and archive data remain outside the repository in the normal cache. Vanilla icons/sounds are used when a client has no local assets.

## First-person arms export (`--arms`)

```sh
tools/export-cs2-assets --knives   # first: the arms record reports overlap with the knife clip skeleton
tools/export-cs2-assets --arms
```

This mode writes only `models/arms/` and the top-level `manifest.json` key `arms`. It re-reads the index and never rewrites `weapons`, `source` or other keys. Each part is one glTF with PNG/bin sidecars, skinned, without animations:

| Part | Source | Mesh nodes (suffix after `.vmdl_c.`) → role |
|---|---|---|
| `models/arms/ctm_sas/ctm_sas.gltf` | CT default agent | `firstperson_default_gloves_arms` → `arms_gloves`, `firstperson_sleeves` → `sleeves` |
| `models/arms/tm_phoenix/tm_phoenix.gltf` | T default agent | same two meshes |
| `models/arms/glove_hardknuckle/glove_hardknuckle.gltf` | `items_game` `ct_gloves` | `viewmodel` → `arms_gloves` |
| `models/arms/glove_fingerless/glove_fingerless.gltf` | `items_game` `t_gloves` | `viewmodel` → `arms_gloves` |

`arms` record: `status`, `errors`, `default` (`{ct: {ctm_sas: [meshes]}, t: {tm_phoenix: [meshes]}}`: render every listed mesh), `clip_reference`/`clip_joint_count` (the clip glTF whose node names define "matched"), `retarget`, and `parts.<part>` = `{file, source, team, missing_chunks, meshes.<suffix>: {node, role, skeleton_root, joints, matched_joints, unmatched_joints, joint_anchors, materials}}`. Current counts: agents 46/94 (CT) and 46/86 (T), gloves 44/52, against 63 clip nodes.

The meshes keep the character T-pose bind. Pose them in model space: `skin(j) = clipGlobal(j) · inverseBind(j)`. An unmatched joint uses its anchor `a = joint_anchors[j]`: `clipGlobal(a) · inverseBind(a)`. Anchors are the nearest animated ancestor; clavicles, whose ancestors are only the spine, use their animated child `arm_upper_*`. Missing optional chunks are recorded per part in `missing_chunks`. A part fails only if its glTF or a referenced sidecar is missing.

## Glove export (`--gloves`)

```sh
tools/export-cs2-assets --skins    # first: data/items_game.txt and skins/index.json
tools/export-cs2-assets --knives   # first: knife clips for the joint report
tools/export-cs2-assets --gloves   # ~1-2 min; needs numpy and Pillow for the finish bake
```

This mode writes only `models/gloves/`, `skins/<glove_id>/`, glove kit entries in `skins/index.json` and the top-level `manifest.json` key `gloves`. It re-reads both indexes and leaves every other record and key unchanged.

- **Meshes:** `models/gloves/<glove_id>/<glove_id>.gltf` holds the glove model's `viewmodel` mesh. It uses the same CLI options as `--arms`: skinned to the character skeleton in T-pose, with no animations. Its primitives are the bare arm (`bare_arm_*`) and the glove (`glove_<x>_left`, `glove_<x>_right`). If a glove's default texture is in an absent chunk, that image is dropped from the glTF and listed in `dropped_images`; the finish textures replace it anyway.
- **Finishes:** pairs come from the econ icons (94). Each `skins/<glove_id>/<paint_kit>/` holds `color.png` (baked final albedo, 1024², opaque RGB), `normal.png` (the paint's tangent normal with Z rebuilt) and `params.json` = `{schema, weapon, paint_kit, name, kind:"glove", material, paint_material, layout, glove_textures:{color, normal}, available, missing_inputs, degraded_inputs, approximation}`. The bake reads the layered glove shader (see [cs2-data.md](cs2-data.md) "Glove skins") at wear 0. For each texel it takes the layer and tint id, then multiplies the tiled layer detail/surface by the tint. The pattern then replaces its tint id (`g_nPatternReplaceIndex`, or the `g_bId<N>Pattern` ids), and the result is multiplied by AO. Colour-key patterns (weapon paint masks, or any pattern whose texels are almost all pure 0/1 channels) blend tints 5/6/7 by R/G/B. Wear damage, grime, burnishing, sheen and roughness are not baked. A finish is `available` once its layer and tint-id maps and its pattern decode. A missing layer texture bakes flat and is listed in `degraded_inputs`.
- **Index:** glove kits are added to `skins/index.json` `kits` like weapon kits (`paint_kit`, `name`, localized `display_name`, `rarity`, `wear_min`/`wear_max`, `weapons`, `available_weapons`), plus `kind:"glove"` and `glove_textures:{color:"color.png", normal:"normal.png"}`. A later `--skins` run keeps these entries.
- **`gloves.<glove_id>` record:** `status` (`ok` once the mesh validates), `file`, `model_source`, `item_defindex`, `meshes.viewmodel` (same shape as an `arms` part mesh: `node, role:"arms_gloves", skeleton_root, joints, matched_joints, unmatched_joints, joint_anchors, materials`), `glove_materials`, `bare_arm_materials`, `finishes` (available paint kit ids), `finish_count`, `missing_finishes` (`{paint_kit: [missing inputs]}`), `missing_chunks`, `errors`. The exit code is 0 when all 8 meshes are `ok`.

## Equipment export (`--equipment`)

```sh
tools/export-cs2-assets --equipment                # healthshot, c4 and defuse_kit
tools/export-cs2-assets --equipment --weapons c4   # subset of the three
tools/export-cs2-assets --icons                    # afterward, as a separate run (needs numpy and Pillow)
```

`--equipment` is an exclusive mode: combining it with `--grenades`, `--knives`, `--arms`, `--gun-clips`, `--gloves`, `--icons` or `--skins` is an error. Run `--icons` afterward so the HUD icons include these records. `defuse_kit` uses CS2's `defuser` icon (alias in `tools/cs2_icons.py`). Equipment sound-event inputs are freshly decompiled in private temporary storage on each run, and shared `data/*` remains untouched.

Cache layout under the cache root:

| Path | Contents |
|---|---|
| `models/<id>/<id>.gltf` | World model for `healthshot`, `c4` and `defuse_kit`. |
| `models/<id>/clips/<clip>.gltf` | Viewmodel clips. `healthshot`: `draw_healthshot`, `idle_healthshot`, `inject_healthshot`. `c4`: `draw_c4`, `idle_c4`, `lookat01_c4`, `lookat02_c4`, `lookat03_c4`, `plant_c4`. `defuse_kit` has none. |
| `sounds/<id>/manifest.json` | Action to sound-file map. Actions: `healthshot` → `draw`, `prepare`, `inject`, `success`; `c4` → `draw`, `plant`, `beep`, `initiate`, `disarm_start`, `disarm_finish`, `explode`; `defuse_kit` → `equip`, from a direct owned sample. Event names are in `tools/cs2_equipment.py`. |
| `manifest.json` → `weapons.<id>` | Equipment record, below. |
| `manifest.json` → `record_sources.<id>` | Input fingerprint for that record. |

**Record fields** on `weapons.<id>`:

| Key | Contents |
|---|---|
| `kind` | `"equipment"`. |
| `equipment_schema` | `1` for records written by this mode. |
| `status`, `errors` | `status` is `ok` or `failed`. A failed clip adds `viewmodel clip <name>: <reason>` to `errors`. |
| `models.world` | World model file and clips, as for firearms. |
| `viewmodel_animations` | Action → clip name. `healthshot`: `draw`, `idle`, `use` (`inject_healthshot`). `c4`: `draw`, `idle`, `inspect` (`lookat01_c4`), `inspect_alt` (`lookat02_c4`), `inspect_alt2` (`lookat03_c4`), `use` (`plant_c4`). `defuse_kit`: `{}`. |
| `viewmodel_clips.<clip>` | `{file, animation, source, duration_seconds}`. `file` is cache-relative (`models/<id>/clips/<clip>.gltf`); `animation` is the glTF animation name; `source` is the VPK path of the `.vnmclip_c`; `duration_seconds` is the clip length. |
| `viewmodel_markers.<clip>` | Array of `{type, name, time_seconds, duration_seconds, normalized_time, normalized_duration, fields, compiled_event}`, one per compiled event. |
| `viewmodel_note` | Shared-skeleton note, or the passive-equipment note for `defuse_kit`. |
| `worldmodel_animations` | `c4` only: `{"planted": "planted"}` when the world model has a `planted` clip, else `{}`. |
| `sounds_manifest` | Path to `sounds/<id>/manifest.json`. |

**Durations and markers.** Every `duration_seconds` comes from the compiled clip's `m_flDuration` (`--block DATA`), not from glTF accessor maxima: glTF optimization reduces constant idle channels to zero, so a glTF-derived idle length would be wrong. Marker `normalized_time` and `normalized_duration` are the compiled fractions; `time_seconds` and `duration_seconds` are those fractions multiplied by the clip's `m_flDuration`. They are not authoring frame positions. `fields` keeps every authored field and `compiled_event` keeps the event text.

Observed durations: `draw_healthshot` 1.0 s, `idle_healthshot` 6.6667 s, `inject_healthshot` 1.9 s; `draw_c4` 1.2333 s, `idle_c4` 1.0 s, `lookat01_c4` and `lookat02_c4` 4.1 s, `lookat03_c4` 3.8667 s, `plant_c4` 4.0 s. The C4 plant clip is 4.0 s in the cache; the server's 3 s plant timing is a Minecraft adaptation, and the client scales the clip to it. Rounding of marker fractions gives the prepare marker at 0.133332 s and the inject (Thud) marker at 0.666666 s in `inject_healthshot`, not exact repeating thirds.

**Passive kit.** `defuse_kit` is a world model only. It has no `viewmodel_clips` or `viewmodel_markers`, and no first-person clips are invented for it.

**Run semantics.** An equipment run writes only the selected `weapons.<id>` records and their `record_sources`; other weapon records and top-level keys (including `last_run`) are preserved.

**Not exported.** Shield, bump mine, tablet, fists, melee tools, snowball, tagrenade, repulsor and rockets. The owned package lacks complete recovered sets for these, so they are excluded rather than approximated.

## Weapon HUD icons (`--icons`)

```sh
tools/export-cs2-assets            # first: weapon records in manifest.json
tools/export-cs2-assets --icons    # ~5-20 s; needs numpy and Pillow
```

Decompiles `panorama/images/icons/equipment/<id>.vsvg_c` for every weapon id in `manifest.json` `weapons` and rasterizes the SVG (`tools/cs2_icons.py`: paths/shapes, group transforms, nonzero/evenodd, 4× supersampled) to `icons/<id>.png`: 256 px tall, white RGB with coverage alpha. It writes `icons/` (PNGs plus a temporary `.export-*` work directory removed on exit), the top-level manifest key `icons` = `{source, records.<id>: {file, source, width, height}, missing}`, and its CLI logs `logs/icons.log` / `logs/icons-<id>.log`. Every other exported asset and every other manifest key is left unchanged; the manifest file itself is rewritten (atomically) with the new key. Ids without a CS2 icon, or whose icon sits in an absent VPK chunk (many are in `pak01_260.vpk`), are listed in `missing`; if none of the ids has an icon, the CLI is not invoked. With all chunks present, 63 of 64 ids export; only `m14` has no CS2 icon.

The in-game ammo HUD (bottom right while a gun is held) loads `icons/<id>.png` when present. Otherwise, once the gun's cached model has loaded, it renders that model once offscreen and flattens it to a white silhouette, so the HUD works without this export; it never captures the vanilla placeholder item, and shows only the counts until the model is ready (or if it never loads). Icons, their textures and cached absences are dropped on resource reload (F3+T picks up a new export) and on disconnect. The HUD is sized from the screen height rather than the GUI scale (magazine digits about 3.9% of screen height, an AK silhouette about 8% of screen width, drawn at whole framebuffer pixels); a large GUI scale only raises the minimum size. If it would reach the hotbar or offhand slot, it moves above the hotbar and status bars.

## Gun viewmodel clips (`--gun-clips`)

```sh
tools/export-cs2-assets                                   # first: firearm records (world models, sounds)
tools/export-cs2-assets --gun-clips                       # all 36 firearms (~16 s)
tools/export-cs2-assets --gun-clips --weapons ak47,elite
```

The normal firearm export also runs this step for every gun it re-exports. `--gun-clips` alone re-reads `manifest.json` per gun and replaces only that record's `viewmodel_*` keys; `animations` (world-model clips), other records and top-level keys are untouched. Clips are written to `models/<id>/clips/<clip>.gltf` (+ `.bin`), as for knives.

Lifecycle: `viewmodel_status` is saved as `in_progress` before any clip file is replaced or deleted, then set to `ok`, or to `failed` when a clip fails or a required action has no clip. In the normal export a failed viewmodel adds `viewmodel: …` errors to the record, so the gun counts as failed. A firearm record is reused as cached only when `viewmodel_status` is `ok` and its clip list is non-empty; records from before this step re-export. `--gun-clips` exits 1 if any gun is not `ok`. Files in `clips/` whose clip the resolved selection no longer names are deleted only after a run with no clip errors, so a clip whose refresh failed keeps its previous files.

**Which clips.** Each gun's clips come from its CS2 animation graph, not from folder names. The exporter decompiles `animation/graphs/viewmodel/viewmodel_gun.vnmgraph+<key>.vnmgraph_c` (and the own graphs `viewmodel_gun_{cz75,elites,revolver}`) plus the linked `viewmodel_inspects` variation, and takes their `.vnmclip` references. The graph key is matched against the weapon id, the `m_szAnimSkeleton` stem and the world-model folder: exact first, then the longest key that prefixes a candidate (`m4a1s`, `usp`, `cz75`, `glock`), or a key one character longer (`elites`). Clips referenced by more than half of all gun graphs (`idle_from_activity_m249`) are dropped unless they sit in the gun's own folder. Fallback with no graph: `animation/anims/viewmodel/<pistol|rifle>/<kind>_<id>`, else `_default_pistol` (pistols and taser) or `_default_rifle`. Only `m14` uses the fallback (`default:rifle/_default_rifle`; it has no world model or skeleton either). `m4a1_silencer` and `usp_silencer` resolve to graphs that use the `_default_rifle`/`_default_pistol` clips, which are authored on their skeletons. `ssg08` uses the current graph; the `*_lgcy` clips are ignored.

**Record keys** on `weapons.<id>`:

| Key | Meaning |
|---|---|
| `viewmodel_clips` | `{clip: {file, animation, source, duration_seconds, weapon_skeleton}}`. Idle clips are 0 s single-frame poses, except `idle_revolver` (7.5 s). |
| `viewmodel_animations` | `{action: clip}` (see below). |
| `viewmodel_status` | `in_progress`, `ok` or `failed` (see Lifecycle). |
| `viewmodel_clip_resolution` | `graph:<graph path>`, `folder:<folder>` or `default:<folder>`. |
| `viewmodel_clip_folder` | Clip folders actually used. |
| `viewmodel_anim_skeleton` | vdata `m_szAnimSkeleton`. |
| `viewmodel_skeleton` | `clip_skeletons.<skeleton>` = `{matched, unmatched, clip_only}`: world-model bone names found under the clip's weapon skeleton node, missing from it, and only in the clip. `matches_vdata` says whether that skeleton is the vdata one. |
| `viewmodel_unmapped_clips`, `viewmodel_missing_actions` | Clips with no action, and required actions with no clip. |
| `viewmodel_missing_chunks`, `viewmodel_absent_resources`, `viewmodel_errors`, `viewmodel_notes` | Missing VPK chunks, graph references absent from the VPK, per-clip errors, and notes such as "cycle baked into fire". |

Each clip glTF holds the `viewmodel.vnmskel` arm skeleton (bone `wpn`) and a node `animation/skeletons/weapons/<skel>.vnmskel` whose subtree is `weapon` → `weapon_offset` → moving parts (`bolt`, `clip`, `cliprelease`, `trigger`, `silencer`, `econ`, `muzzle`…), named as in the world glTF. No gun has a `slide` bone; pistols use `bolt`. Every gun's clip skeleton is its vdata `m_szAnimSkeleton` and contains all world bones except `ag1_hand_r` everywhere, plus `ag1_hand_l`/`elite_holster` (elite), `sight` (m4a1 = M4A4) and `taser_meter` (taser). `econ*`, `muzzle*`, `flash`, `eholster` and `taser_chargemeter` exist only in clips. `m14` has no world model, so nothing is checked.

**Actions** (clip base name, after removing the gun tag, e.g. `_ak` → action): `draw` (draw), `draw_alt` (draw2/draw_2), `draw_silenced`, `idle` (idle/idle1), `idle_empty` (idle_slide_back, elite idle_leftrightempty), `idle_left_empty`, `idle_ironsight` (ironsight_fidget), `fire` (shoot1), `fire_alt` (revolver shoot_alt1), `fire_last` (shoot_empty), `fire_ironsight` (aug/sg556), `fire_left`/`fire_right`/`fire_left_last`/`fire_right_last` (elite; no `fire`), `dryfire` and `prime` (prepare_shoot) for the revolver, `reload`, `reload_empty` (reload_empty, negev empty_reload), `reload_alt`/`reload_alt_empty` (cz75a reload2), `inspect`/`inspect_alt`/`inspect_alt2` (lookat01/02/03), `silencer_attach`/`silencer_detach` (silenced guns only). CS2 has no shell-reload start/loop/end clips for `nova`, `xm1014`, `sawedoff` and no separate bolt/pump cycle clip (`awp`, `ssg08`, `mag7`, shotguns). The single `reload`/`fire` clip must carry that motion (for example, `shoot1_awp` lasts 1.6 s against 0.77 s for `shoot1_ak`), and `viewmodel_notes` says so. Unmapped by design: the `lookat*_draw`/`_transfix` inspect transitions; `bullet_hide_*` and `idle_from_activity_*` (m249, negev, xm1014); the revolver's `chamber_position_anim_revolver_0..7` cylinder poses; `ui_keychain_elite`; and m14's fallback `silencer_*` clips.

Clips need `pak01_473.vpk` (241 of 245 sources) plus `pak01_386.vpk` and `pak01_262.vpk`. The `--weapons` list takes firearm ids only. The mapping helpers live in `tools/cs2_guns.py` and are tested by `tools/test_cs2_guns.py`.

## Case art (`tools/cs2_case.py`)

Run `python3 tools/cs2_case.py` (same `--vpk`, `--cli` and `--cache` defaults as the exporter; `--list` prints every icon name under `panorama/images/econ/weapon_cases/`; `--case <name>` picks one, default `crate_valve_1`, the original CS:GO Weapon Case; `--item weapon_case|inventory_weapon_case` picks the csmc item it is for, default `weapon_case`). It writes your own install's econ icon to `<cache>/case/<item>.png` plus `case/<item>.source.json`; nothing is written into the repository. For the Loadout Case, `python3 tools/cs2_case.py --case crate_community_4 --item inventory_weapon_case` exports the Operation Breakout case. A missing archive chunk is reported as `pak01_NNN.vpk`. Clients read the icons on resource load (F3+T after exporting), crop them, reduce them to 64×64 pixel art and extrude them like a vanilla item. Without an export, or if it cannot be read, the authored placeholder `assets/csmc/textures/item/<item>.png` is used: a dark crate with an orange latch for the Weapon Case, a navy crate with a teal latch for the Loadout Case.

## Development hooks

`-Dcsmc.devPreload=ak47,glock` (for example, `JAVA_TOOL_OPTIONS="-Dcsmc.devPreload=ak47,glock" ./gradlew runClient`) loads those models and resolves every cached sound through the sound engine once the title screen is up. No world or input is needed. Results are logged under `[csmc glTF]`, `[csmc sound]` and `[csmc dev]`.

`-Dcsmc.devScreenshot=true` waits until a world is loaded, then saves first-person, third-person-front and third-person-back screenshots of the held item to `run/screenshots/csmc-dev-*.png`. Combine it with `--args="--quickPlaySingleplayer '<world>'"` to run without input. These screenshots show exported content: do not commit them.
