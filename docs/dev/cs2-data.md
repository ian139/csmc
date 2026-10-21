# CS2 data → Minecraft Fabric mod: research report

Target: Counter-Strike 2 (Steam app 730), runtime-from-own-install, personal use. Fallback: CS:GO legacy.

Labels: **[OBS]** read directly from a cited source when this document was written. **[INF]** inference or memory, unverified. **[UNV]** could not be checked.

Not reachable when this document was written: developer.valvesoftware.com (Anubis wall), steamdb.info (403). Depot IDs therefore come from memory and are [INF].

---

## 1. Getting the files (macOS arm64, user's own login)

### Facts
- CS2 is app **730**. Its dedicated-server app is **2347773** [OBS: `ServerAppID=2347773` in `steam.inf`, https://raw.githubusercontent.com/SteamTracking/GameTracking-CS2/master/game/csgo/steam.inf; build 1.41.9.1, 2026-10-15]. Note the old CS:GO server app 740 is a different product.
- CS2 ships client and server binaries together in one per-OS depot, and content (VPKs) in a separate depot. [OBS for "one per-OS binaries depot": https://github.com/CS2OpenDev/CS2OpenDev-SchemaTracker]. Exact depot IDs (I recall 2347770 content, 2347771 Windows binaries, 2347772 Linux) are [INF]; confirm with `-manifest-only` (below) before relying on them.
- Binary depots are useless for this project. Only the content depot matters. Assets live in `game/csgo/pak01_dir.vpk` plus numbered chunks `pak01_000.vpk …` [OBS: https://s2v.app/ValveResourceFormat/guides/exporting-models.html, "most assets are inside pak01_dir.vpk"; chunk naming from https://s2v.app/ValveResourceFormat/guides/command-line.html].
- Gameplay data is already in plain-text KV3 in the VPK: `scripts/weapons.vdata` (523 KB) and `scripts/items/items_game.txt` (8.1 MB), plus `scripts/surfaceproperties_game.txt` [OBS: directory listing and fetched files at https://github.com/SteamTracking/GameTracking-CS2/tree/master/game/csgo/pak01_dir/scripts]. This is a public third-party mirror of Valve's data. It is used here only to learn the schema; the mod must read the user's own install.

### Tool: DepotDownloader (recommended over steamcmd)
- .NET 8 app, installable via Homebrew `brew tap steamre/tools && brew install depotdownloader` [OBS: https://github.com/SteamRE/DepotDownloader README]. Whether the brew build is native arm64 is [UNV]; .NET 8 supports arm64 macOS [INF].
- Relevant flags [OBS, same README]: `-app`, `-depot`, `-manifest`, `-username`, `-remember-password`, `-os windows|macos|linux`, `-dir`, `-filelist <file>` (lines are paths, or `regex:` prefixed), `-manifest-only`, `-max-downloads`, `-validate`.
- Anonymous login is the default; `-username` uses your account. Anonymous access to app 730 content: [UNV]. Use your own login (CS2 is free, so your account owns it).
- steamcmd also works but is x86-only and needs Rosetta [OBS: x86-only per https://github.com/GameServerManagers/docker-steamcmd]. Its `+@sSteamCmdForcePlatformType` and `+app_update` pulls the whole ~60 GB game; it has no filelist feature. Use it only as a fallback.

### Commands
```sh
brew tap steamre/tools && brew install depotdownloader

# 1) discover depots and file lists without downloading content
DepotDownloader -app 730 -os windows -username YOU -remember-password \
  -dir ~/csmc-assets/cs2-manifest -manifest-only
#   -> human-readable manifest(s): look for game/csgo/pak01_dir.vpk and pak01_NNN.vpk sizes

# 2) pull only the small, text/loose files first (no VPK chunks)
cat > ~/csmc-assets/filelist-dir.txt <<'EOF'
game/csgo/pak01_dir.vpk
game/csgo/gameinfo.gi
game/csgo/steam.inf
EOF
DepotDownloader -app 730 -os windows -username YOU -remember-password \
  -dir ~/csmc-assets/cs2 -filelist ~/csmc-assets/filelist-dir.txt
```
Why two steps: weapon models, textures and sounds are packed *inside* VPK chunks, so a filelist can only select whole VPK files, not individual assets. The `_dir.vpk` is the index. With it you can list exactly which chunk holds each wanted path [OBS: CLI `--vpk_dir` "print the archive index … of each file", command-line guide], then download only those chunks:
```sh
Source2Viewer-CLI -i ~/csmc-assets/cs2/game/csgo/pak01_dir.vpk --vpk_dir \
   --vpk_filepath "weapons/models/" > weapon-files.txt      # shows archive index per file
# also list: sounds/weapons/, materials/models/weapons/, scripts/
# build a filelist of the distinct pak01_NNN.vpk names, then:
printf 'game/csgo/pak01_007.vpk\ngame/csgo/pak01_012.vpk\n' > ~/csmc-assets/filelist-chunks.txt   # example names
DepotDownloader -app 730 -os windows -username YOU -dir ~/csmc-assets/cs2 -filelist ~/csmc-assets/filelist-chunks.txt
```
Caveat [INF]: files are scattered across chunks by Valve's packer, so weapons+sounds+textures may touch many chunks; the saving is unknown until `--vpk_dir` is run. Worst case is most of the content depot (tens of GB). A one-time full pull is the honest fallback.
Also [OBS]: `Source2Viewer-CLI -i "steam:730/game/csgo/..."` resolves paths from an installed Steam app, so a Steam-client install also works if the user prefers.

### Does an anonymous server install contain scripts and weapon assets?
- Server app 2347773 anonymity and contents: [UNV]. Since CS2 ships client and server binaries in one depot [OBS above], a server install probably carries the same `pak01` set, which would make it a way to get the assets without a login. Not verified; do not depend on it. App 740 (CS:GO server) does not contain CS2 assets [INF].

---

## 2. Weapon data

### Where it lives [OBS]
`scripts/weapons.vdata` (KV3 text). Schema: `CCSWeaponBaseVData` (83 fields) from the Source 2 Schema Explorer https://s2v.app/SchemaExplorer/cs2/server/CCSWeaponBaseVData. Weapons are `weapon_<name>` entries with `_prefab` bases. Fields seen, with AK-47 values read from the file:

| vdata key | AK-47 value | meaning |
|---|---|---|
| `m_nDamage` | 36 | base damage |
| `m_flHeadshotMultiplier` | 4.0 | head multiplier |
| `m_flArmorRatio` | 1.55 | armour ratio (same unit as 1.6's multiplier on 0.5) |
| `m_flPenetration` | 2.0 | penetration power (bullet-ish scalar) |
| `m_flRange` | 8192.0 | max trace distance, inches |
| `m_flRangeModifier` | 0.98 | per-500-unit falloff base |
| `m_flCycleTime` | 0.1 | seconds between shots (two-element array, [primary, secondary]) |
| `m_bIsFullAuto` | true | |
| `m_nNumBullets` | 1 | pellets |
| `m_iMaxClip1` | 30 | magazine |
| `m_nPrimaryReserveAmmoMax` | 3 | **[UNV]** AK reserve is 90 in-game; 3 is not rounds. Likely a multiplier/mag count or ammo-type reference; check `items_game`/ammo definitions |
| `m_flSpread` | 0.0006 | base spread |
| `m_flInaccuracyStand` / `Crouch` / `Move` / `Jump` / `Land` / `Ladder` / `Fire` | 0.00641 / 0.00481 / 0.17506 / 0.14076 / 0.000242 / 0.14 / 0.0078 | inaccuracy terms |
| `m_flInaccuracyJumpInitial` / `JumpApex` / `Reload` | 0.10094 / 0 / 0 | |
| `m_flRecoveryTimeStand` / `Crouch` (+`Final`, `RecoveryTransitionStart/EndBullet`) | 0.368 / 0.305257 (final 0.506 / 0.4197) | inaccuracy recovery |
| `m_flRecoilAngle` / `RecoilAngleVariance` | 0.0 / 70.0 | recoil direction, degrees |
| `m_flRecoilMagnitude` / `RecoilMagnitudeVariance` | 30.0 / 0.0 | recoil size |
| `m_nRecoilSeed` | 223 | per-weapon seed for deterministic pattern |
| `m_nSpreadSeed` | 0 | |
| `m_flMaxSpeed` | [215, 215] | movement speed |
| `m_nKillAward`, `m_nPrice`, `m_flDeployDuration` (1.0) | | economy / draw time |
| `m_flThrowVelocity`, etc. | | grenades |

Many fields are `CFiringModeFloat` = two-element array (`[primary mode, secondary mode]`), e.g. scoped vs unscoped or silencer on/off. [OBS from schema type + values]

**Not in vdata [OBS: reload key search returned only `m_flInaccuracyReload` and `m_flDisallowAttackAfterReloadStartDuration`]:** reload duration and clip-in timing. They live in the viewmodel animation (and animgraph) [INF]. Read the reload sequence duration from the exported glTF animation. Same for exact ammo-type reserve counts.

### Per-weapon values (read from `weapons.vdata`; first element of array fields)
Columns: dmg, clip, armor ratio, penetration, range, range modifier, cycle s, full-auto, pellets, spread, inacc stand / crouch / move / fire, recoil angle / angle var / magnitude / mag var, recoil seed, recovery-stand s.

| weapon | dmg | clip | AR | pen | range | rm | cycle | auto | n | spread | stand | crouch | move | fire | rAng | rVar | rMag | rMagVar | seed | recov |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| ak47 | 36 | 30 | 1.55 | 2 | 8192 | .98 | .1 | y | 1 | .0006 | .00641 | .00481 | .17506 | .0078 | 0 | 70 | 30 | 0 | 223 | .368 |
| m4a1 | 33 | 30 | 1.4 | 2 | 8192 | .97 | .09 | y | 1 | .0006 | .0049 | .0041 | .13788 | .007 | 0 | 70 | 23 | 0 | 38965 | .339 |
| m4a1_silencer | 38 | 20 | 1.4 | 2 | 8192 | .94 | .1 | y | 1 | .0006 | .0049 | .0041 | .09288 | .012 | 0 | 65 | 25 | 3 | 38965 | .339 |
| awp | 115 | 5 | 1.95 | 2.5 | 8192 | .99 | 1.455 | n | 1 | .0002 | .0808 | .0606 | .17648 | .05385 | 0 | 20 | 78 | 15 | 4100 | .345 |
| ssg08 | 88 | 10 | 1.7 | 2.5 | 8192 | .98 | 1.25 | n | 1 | .00028 | .0317 | .02378 | .12345 | .02292 | 0 | 20 | 33 | 15 | 1278 | .142 |
| g3sg1 / scar20 | 80 | 20 | 1.65 | 2.5 | 8192 | .98 | .25 | y | 1 | .0003 | .0258 | .01935 | .15048 | .01861 | 0 | 30 | 30 / 31 | 4 | 29908 / 19364 | .544 |
| galilar | 30 | 35 | 1.55 | 2 | 8192 | .98 | .09 | y | 1 | .0006 | .00877 | .00658 | .12356 | .007 | 0 | 70 | 21 | 1 | 51191 | .3 |
| famas | 30 | 25 | 1.4 | 2 | 8192 | .96 | .09 | y | 1 | .0006 | .00759 | .0055 | .09934 | .00605 | 0 | 60 | 20 | 1 | 39623 | .25 |
| aug | 28 | 30 | 1.8 | 2 | 8192 | .98 | .1 | y | 1 | .0005 | .0049 | .00368 | .13545 | .00729 | 0 | 60 | 24 | 0 | 24204 | .43 |
| sg556 | 30 | 30 | 2.0 | 2 | 8192 | .98 | .11 | y | 1 | .0006 | .00581 | .00381 | .13601 | .00795 | 0 | 60 | 28 | 2 | 43500 | .453 |
| m249 | 32 | 100 | 1.6 | 2 | 8192 | .97 | .08 | y | 1 | .002 | .0077 | .00534 | .15625 | .00356 | 0 | 50 | 25 | 2 | 50310 | .829 |
| negev | 35 | 150 | 1.42 | 2 | 8192 | .97 | .075 | y | 1 | .002 | .01017 | .00763 | .15914 | .03 | 0 | 0 | 20 | 2 | 57966 | .3 |
| mac10 | 29 | 30 | 1.15 | 1 | 3600 | .8 | .075 | y | 1 | .0006 | .0133 | .00998 | .01399 | .00476 | 0 | 70 | 18 | 1 | 34079 | .4 |
| mp9 | 26 | 30 | 1.2 | 1 | 3600 | .87 | .07 | y | 1 | .0006 | .009 | .008 | .02904 | .0037 | 0 | 70 | 21 | 1 | 50729 | .258 |
| mp7 / mp5sd | 30 / 28 | 30 | 1.25 | 1 | 3600 | .87 | .08 | y | 1 | .0006 | .01 | .0085 | .01986 / .03 | .00218 | 0 | 70 | 16 | 1 | 61649 | .437 |
| ump45 | 35 | 25 | 1.3 | 1 | 3700 | .75 | .09 | y | 1 | .001 | .01343 | .01007 | .02876 | .00342 | 0 | 40 | 23 | 1 | 59299 | .35 |
| p90 | 26 | 50 | 1.38 | 1 | 3700 | .86 | .07 | y | 1 | .001 | .01365 | .01024 | .031 | .00285 | 0 | 70 | 16 | 1 | 6213 | .372 |
| bizon | 27 | 64 | 1.26 | 1 | 3600 | .8 | .08 | y | 1 | .001 | .014 | .0105 | .02757 | .00288 | 0 | 70 | 18 | 1 | 36387 | .332 |
| glock | 30 | 20 | 0.94 | 1 | 4096 | .85 | .15 | n | 1 | .002 | .0056 | .0042 | .01 | .056 | 0 | 20 | 18 | 0 | 4484 | .2 |
| hkp2000 | 35 | 13 | 1.01 | 1 | 4096 | .91 | .17 | n | 1 | .002 | .0049 | .00368 | .013 | .05 | 0 | 0 | 26 | 0 | 5426 | .35 |
| usp_silencer | 35 | 12 | 1.01 | 1 | 4096 | .91 | .17 | n | 1 | .0025 | .0049 | .00368 | .01387 | .071 | 0 | 0 | 29 | 0 | 5426 | .35 |
| p250 | 38 | 13 | 1.28 | 1 | 4096 | .9 | .15 | n | 1 | .002 | .0091 | .00683 | .02 | .05245 | 0 | 10 | 26 | 3 | 9788 | .345 |
| elite | 38 | 30 | 1.15 | 1 | 4096 | .79 | .12 | n | 1 | .002 | .007 | .00525 | .01785 | .01116 | 0 | 20 | 27 | 4 | 24563 | .525 |
| fiveseven | 32 | 20 | 1.823 | 1 | 4096 | .81 | .15 | n | 1 | .002 | .0091 | .00683 | .04 | .025 | 0 | 5 | 25 | 4 | 33244 | .2 |
| tec9 | 33 | 18 | 1.812 | 1 | 4096 | .79 | .12 | n | 1 | .002 | .0049 | .00368 | .00381 | .045 | 0 | 60 | 23 | 3 | 789 | .391 |
| cz75a | 31 | 12 | 1.553 | 1 | 4096 | .85 | .1 | y | 1 | .003 | .01043 | .0076 | .01341 | .035 | 0 | 120 | 31 | 6 | 9788 | .243 |
| deagle | 53 | 7 | 1.864 | 2 | 4096 | .85 | .225 | n | 1 | .002 | .0042 | .00218 | .0481 | .07223 | 0 | 60 | 48.2 | 18 | 1454 | .811 |
| revolver | 86 | 8 | 1.864 | 2 | 4096 | .94 | .5 | y | 1 | .00052 | .002 | .001 | .0065 | .05 | 0 | 40 | 20 | 0 | 12345 | .9 |
| nova | 26 | 8 | 1.0 | 1 | 3000 | .7 | .88 | n | 9 | .04 | .007 | .00525 | .03675 | .00972 | 0 | 20 | 143 | 22 | 7763 | .46 |
| xm1014 | 20 | 7 | 1.6 | 1 | 3000 | .7 | .35 | y | 6 | .038 | .007 | .00525 | .03603 | .00883 | 0 | 20 | 80 | 20 | 24862 | .507 |
| mag7 | 30 | 5 | 1.5 | 1 | 1400 | .45 | .85 | n | 8 | .04 | .007 | .00525 | .01599 | .01119 | 0 | 20 | 165 | 25 | 12518 | .4 |
| sawedoff | 32 | 7 | 1.5 | 1 | 1400 | .45 | .85 | n | 8 | .062 | .007 | .00525 | .0168 | .00972 | 0 | 20 | 143 | 22 | 1089 | .461 |
| taser | 500 | 1 | 2.0 | 0 | 120 | .99 | .15 | n | 1 | 0 | .06 | .05 | .013 | .05 | 0 | 0 | 0 | 0 | 687 | 0 |
| (m14 = AWP-class variant) | 115 | 5 | 1.95 | 2.5 | 8192 | .945 | 1.85 | n | 1 | .002 | .0708 | .0506 | .17648 | .05385 | 0 | 20 | 78 | 15 | 4100 | .345 |

Notes: a handful of values (silencer or scoped second mode) live in the second array element and are not shown. `m_flRecoilAngle` is 0 in every row shown except the one with different rAng (none in this table) — in this dump all primaries read 0. The `_prefab` rows duplicate the finals. Grenades, knife and C4 carry placeholder defaults from `weapon_base` (damage 42, etc.) and are not real stats. Row coverage is whatever the file contained; M4A4/MP7 etc. as listed.

### How CS2 produces recoil and spread
- **Not documented by Valve [UNV].** Valve does not publish CS2 game code. Public information is the data fields above plus community reverse engineering. Nothing here is from Valve source.
- Inference from the field set [INF]: each shot adds a view punch whose direction is `m_flRecoilAngle ± m_flRecoilAngleVariance` degrees and size `m_flRecoilMagnitude ± m_flRecoilMagnitudeVariance`, where the "variance" draws come from a PRNG seeded by `m_nRecoilSeed` and advanced by the bullet index. That makes the spray pattern the same every time, which is how CS2 gets fixed spray patterns. The exact RNG, units of "magnitude", and accumulation/decay law (multiplier on view punch, `weapon_recoil_scale`-style cvars) are unconfirmed [UNV]. Treat the AK numbers as: the pattern is a deterministic sequence, so **precompute it empirically** rather than re-deriving the engine's RNG.
- Evidence that pattern generation is seeded and that a randomised seed matters: https://github.com/ValveSoftware/csgo-osx-linux/issues/4327 notes "weapon seed … in cs2 it's set to 0" on official servers [OBS from search snippet; context is about no-spread cheating, read as secondary]. CS2 patch notes from 21 May 2025 mention rendered view angle adjustments from recoil [OBS snippet: https://steamdb.info/patchnotes/18557042].
- Spread: final cone is built from `m_flSpread` + the active `m_flInaccuracy*` terms (stand/crouch/move/jump/land/ladder), plus `m_flInaccuracyFire` added per shot and decaying over `m_flRecoveryTime*`, with a transition between `RecoveryTransitionStartBullet` and `...EndBullet` bullets [INF from field names; the sum formula is unconfirmed]. `m_nSpreadSeed` is the spread RNG seed. The older (CS:GO) formula in leaked/ported client code is similar [INF].
- Rather than guess, **calibrate**: have the mod include a record/replay of the vector offsets the user's real CS2 produces (the community spray-pattern trainers and datasets do this), or accept an approximation and document it.

### Penetration [OBS data, INF algorithm]
`scripts/surfaceproperties_game.txt` carries, per surface, `gamematerial` letter, `bulletPenetrationDistanceModifier` and `bulletPenetrationDamageModifier`. Values read: default C 0.5/0.5; solidmetal M 0.27/0.3; metal 0.4; metalgrate G 0.95/0.99; metalvent V 0.6/0.45; metalpanel V 0.5/0.45; dirt D 0.6/0.3; tile T 0.7/0.3; Wood W 0.9/0.6; Wood_Plank 0.85; Wood_Solid 0.8; water 0.3; glass Y 0.99; computer P 0.4/0.45; concrete C 0.5/0.25 (list continues, file is 8.8 KB). Weapon `m_flPenetration` (1, 2, 2.5) is a power value; AK=2.0, AWP/Scout/auto-snipers=2.5, pistols and SMGs=1.0.

Algorithm [INF, from CS:GO's well-known `HandleBulletPenetration`; the CS2 reimplementation was reported to differ, e.g. unknowncheats thread https://www.unknowncheats.me/forum/counter-strike-2-a/604186-handlebulletpenetration.html, snippet only]:
```
while (pen_count>0 and damage>0):
  trace; apply range falloff: damage *= rangeModifier^(distance/500)
  hit surface -> damageModifier(surface) , distanceModifier(surface)
  find exit point by tracing back (TraceToExit, max ~90 units)
  penetration_modifier = min(weapon_penetration_power-based) ...
  lost_damage = max(0, 3/pen_power*1.25) * (thickness) ...
  thickness^2 * penetration_modifier / (2*...) ; if thickness too large -> stop
  damage -= lost_damage; damage *= damageModifier
  next trace from exit point, range reduced by distance spent
```
I could not verify exact constants for CS2 (the `3/pen*1.25` term is from memory of CS:GO). For the mod, implement a block-material analogue: wall thickness is measurable in Minecraft (counting solid blocks along the ray), so the CS:GO/CS2 style thickness-based cost fits better than the 1.6 "restart past the surface" trick. Calibrate against the surface table above.

### Mapping to the csmc weapon JSON contract
| Contract field | CS2 source | Status |
|---|---|---|
| id | `weapon_<name>` key | direct |
| display_name | localisation `csgo_english.txt` / `items_game` `item_name` | not in vdata; read from resources, [UNV] path |
| damage | `m_nDamage` | direct |
| rate_of_fire_rpm / cycle_time_s | `m_flCycleTime` | prefer `cycle_time_s`; rpm = 60/cycle |
| magazine_size | `m_iMaxClip1` | direct |
| reserve_ammo | `m_nPrimaryReserveAmmoMax` | **suspect** (value 3 for AK); real reserve not located. Use known in-game values (AK 90) hard-coded or find in `items_game` ammo/clip data [UNV] |
| reload_time_s | none | **not provided**; read from reload animation length |
| range | `m_flRange` | direct; inches |
| range_modifier | `m_flRangeModifier` | direct |
| penetration_power | `m_flPenetration` | direct (1/2/2.5) |
| armor_ratio | `m_flArmorRatio` | direct |
| spread_base | `m_flSpread` | direct |
| spread_moving | `m_flInaccuracyMove` | direct; stand/crouch/jump/land/ladder/fire also needed |
| recoil | `m_flRecoilAngle`, `…Variance`, `…Magnitude`, `…MagnitudeVariance`, `m_nRecoilSeed` | **contract change needed** (see below) |
| automatic | `m_bIsFullAuto` | direct |
| ammo_type | `m_iPrimaryAmmoType` or similar | not read [UNV] |

### Proposed contract changes
1. Replace the CS 1.6-style `kickback` object with CS2's params, and add a precomputed pattern:
   ```json
   "recoil": {
     "angle": 0.0, "angle_variance": 70.0,
     "magnitude": 30.0, "magnitude_variance": 0.0,
     "seed": 223,
     "pattern": [[pitch,yaw], ...]   // optional, one [pitch_deg, yaw_deg] per shot, up to clip size
   }
   ```
   Use `pattern` when present (deterministic, easy server/client sync). Fall back to the parametric form (seeded PRNG) only if you can match the engine's RNG, which is currently unknown.
2. Add `headshot_multiplier`, `pellets` (`m_nNumBullets`), `max_speed`, and the full inaccuracy set: `inaccuracy: {stand, crouch, move, jump, land, ladder, fire}`, `recovery: {stand, crouch, stand_final, crouch_final, start_bullet, end_bullet}`.
3. Keep `reload_time_s` but source it from animations; add `deploy_time_s` (`m_flDeployDuration`).
4. Add a top-level `surfaces` data file (from `surfaceproperties_game.txt`): material → `{distance_modifier, damage_modifier}`, plus a hand-authored Minecraft block→material table.

### Implemented mod contract and local data

The Fabric mod uses CS2-style weapon JSON, not the historical CS 1.6 `kickback` contract.
All 36 canonical gun ids in `CsmcMod.FIREARM_IDS` are registered as `csmc:<weapon_id>` items,
including `csmc:ak47`, `csmc:glock`, `csmc:awp`, and `csmc:m4a1` (the M4A4).
Each item's weapon-definition id is identical. Self-authored approximate class defaults keep every
item usable without the cache; the AK-47 and Glock also have bundled datapack JSON definitions.
No exported Valve files are bundled. Ammunition comes from the shared `AmmoType.fromWeaponType` helper (`mod/src/main/java/dev/csmc/data/AmmoType.java`): pistols, SMGs and the taser use pistol/SMG ammunition (`csmc:pistol_ammo`); rifles, including the M14, and machine guns use rifle ammunition (`csmc:rifle_ammo`); sniper rifles use sniper ammunition (`csmc:sniper_ammo`); shotguns use shotgun shells (`csmc:shotgun_shells`).
Unknown imported weapon types produce a warning and use rifle ammunition.
On server startup and datapack reload, `data/weapons.vdata` inside the local cache overrides
available primary and scoped-mode fields, resolving `_base`/`_prefab` inheritance. Precedence, lowest first:
generated class placeholder, hand-authored reserve/reload facts, the datapack JSON, then exported
fields. Reload seconds and reserve rounds therefore always come from authored fallbacks rather than
the unreliable reserve field, and an authored `ammo_type` wins over the exported weapon type. CS2's
`-1` final recovery times mean "use the base time". Each gun is converted and validated on its own:
a malformed gun logs a warning and keeps its datapack/placeholder definition while the rest import.
Imported weapon ids are logged.

Cache root precedence is Java system property `csmc.cache`, environment `CSMC_CACHE`, then
`~/.csmc/cache/`. Data files belong in `data/weapons.vdata` and
`data/surfaceproperties_game.txt` beneath that root. These files must stay outside version control.
For example, use `JAVA_TOOL_OPTIONS="-Dcsmc.cache=/absolute/path/to/cache"` when launching.
CS2 packages the compiled `scripts/weapons.vdata_c`: the setup step must decompile it with
Source2Viewer-CLI into KV3 **text** at `data/weapons.vdata`. The runtime importer deliberately
does not parse compiled Source 2 data.

Recoil accepts optional measured `recoil.pattern` pairs `[pitch_degrees, yaw_degrees]`. Without
a pattern, the mod uses a deterministic seeded approximation: it does **not** reproduce Valve's
unpublished recoil RNG. Stance inaccuracy plus recovered per-shot fire inaccuracy is likewise an
explicit approximation, not a claim of exact CS2 engine parity. Each shot's kick is sent to the
client as a `csmc:gun_recoil` payload that adds it to the real view (no teleport, so movement never
waits for an acknowledgement). Until the client has had time to echo the kicked view (its latency
plus one tick, an estimate) the server adds the unechoed kicks to the aim of further shots itself,
so recoil stays server-authoritative for hits. Drawing another stack — even of the same gun — or
selecting another slot restarts the spray and clears the fire penalty.
The default parametric kick scale is **0.025 degrees per magnitude unit**, 25% stronger than
the earlier 0.02 scale (magnitude 30 gives 0.75° before directional variance). This does not
claim 1:1 CS:GO recoil: seeded directions and recovery/decay remain approximate. Explicit
measured `recoil.pattern` degree pairs are used unchanged, without this multiplier.

Scopes are data-driven: `zoom.levels` lists CS horizontal FOV degrees; `zoom.time_s` is the
default transition duration, and `zoom.times_s` optionally supplies durations for levels 0/1/2.
`zoom.unzoom_after_shot` enables the bolt-action unzoom/re-zoom behavior. The `scoped` object
overrides `spread_base`, `inaccuracy`, `max_speed`, and `cycle_time_s`; missing overrides use
the primary values. These map to `m_nZoomLevels`, `m_nZoomFOV1/2`, `m_flZoomTime0/1/2`,
`m_bUnzoomsAfterShot`, and the second firing-mode array element. The local cache read for this
implementation has AWP FOVs **40° / 10°**, SSG 08 **40° / 15°**, and all three transition
times **0.05 seconds** for both guns. Authored AWP/SSG 08 fallbacks also work without the cache.

Right-click/use cycles unzoomed → first zoom → second zoom → unzoomed (one-level guns skip the
second zoom). Scoped guns consume use input rather than placing blocks or using the offhand.
The server owns zoom, accuracy, shot cadence, and scoped movement speed; reload, slot/stack
changes, dropping, and dimension changes cancel zoom and pending re-zoom. Entering another
dimension refreshes the held gun's scope capabilities. Bolt-action shots temporarily unzoom,
then restore the previous level after the cycle time if the same gun remains held in that world.

FOV conversion preserves the tangent magnification relative to CS's 90° horizontal baseline:
`ratio = tan(CS_zoom_FOV / 2) / tan(90° / 2)`, and
`Minecraft_FOV = 2 * atan(tan(user_FOV / 2) * ratio)`.
Thus the user's chosen Minecraft FOV remains the unzoomed baseline, without treating CS's
horizontal angle as Minecraft's vertical angle. The client smoothly approaches the server's
target FOV and scales mouse rotation by the same zoom ratio. The black mask and crosshair
lines are self-authored HUD geometry, not a Valve texture; first-person hands/guns are hidden
while scoped. Live-client checks are required for transition feel, sensitivity, HUD alignment,
hand hiding, high-latency synchronization, and right-click interaction suppression.

Penetration measures the ray length inside the blocks' actual collision boxes (entry to exit per
box) at **16 CS units per Minecraft block** (a gameplay scale, not Minecraft's physical
metre-to-inch conversion). Hollow shapes such as cauldrons count only their solid parts (one
obstacle per block), and shapes that overhang into the cell above (fences, walls) are hit there.
The squared-thickness/power model is CS:GO/CS2-inspired and approximate, since the report could
not verify CS2's exact constants. `data/csmc/ballistics/surfaces.json` maps Minecraft blocks/tags
to surface names and authored distance/damage defaults. A local `surfaceproperties_game.txt`
overrides matching surface modifiers; neither proprietary source file is bundled.


---

## 3. Assets (Source 2 Viewer / ValveResourceFormat)

### Tools [OBS from https://github.com/ValveResourceFormat/ValveResourceFormat and https://s2v.app]
| Tool | What | macOS arm64 | Licence | Maintenance | Java |
|---|---|---|---|---|---|
| Source2Viewer-CLI (VRF) | VPK list/extract, vmdl→glTF/GLB with materials and animations, vtex→PNG, vsnd→WAV/MP3, KV3 dump | CLI "for all of Windows, Linux, and macOS" [OBS command-line guide]; arm64 build specifically [UNV] | MIT (keep copyright; asks for a credit line) [OBS README] | very active (2026 patch-note references) | no (.NET) |
| ValveResourceFormat NuGet lib | same, as a library | .NET | MIT | active | no |
| ValvePak | VPK reading, .NET | .NET | [UNV] | active | no |
| Blender (glTF import/export) | optional cleanup | native arm64 | GPL | very active | no |
| GeckoLib / Blockbench | MC animated-model format | Java/Electron | [UNV] | [UNV] | GeckoLib is Java |
| ffmpeg | wav/mp3 → ogg | `brew install ffmpeg` | LGPL/GPL | active | no |

No Java library for Source 2 formats was found in my searches [UNV as absence of evidence].

### Exact commands
```sh
CLI=Source2Viewer-CLI          # release binary from https://s2v.app ; macOS arm64 availability: verify on the releases page
VPK=~/csmc-assets/cs2/game/csgo/pak01_dir.vpk

# list weapon files
$CLI -i $VPK --vpk_list --vpk_filepath "weapons/models/" | head
# Source 2 uses "weapons/models/<name>/weapon_*_<name>.vmdl_c" [OBS: weapon_rif_ak47.vmdl in vdata]

# gameplay data (text)
$CLI -i "vpk:$VPK:scripts/weapons.vdata" -o ~/csmc-assets/out/weapons.vdata
$CLI -i $VPK --vpk_filepath "scripts/" -o ~/csmc-assets/out/scripts

# models -> glTF with materials, textures and animations (one weapon)
$CLI -i $VPK --vpk_filepath "weapons/models/ak47/weapon_rif_ak47.vmdl_c" \
  -o ~/csmc-assets/out/ak47/weapon_rif_ak47.gltf -d \
  --gltf_export_format gltf --gltf_export_materials --gltf_textures_adapt \
  --gltf_export_animations --gltf_animation_list "reload,draw,fire,idle"   # animation names: list with a first dump; names are [UNV]

# bulk: all weapon models
$CLI -i $VPK --vpk_extensions vmdl_c --vpk_filepath "weapons/models/" -d \
  -o ~/csmc-assets/out/models --gltf_export_format gltf --gltf_export_materials --gltf_export_animations

# sounds -> wav/mp3
$CLI -i $VPK -o ~/csmc-assets/out/sounds -d --vpk_extensions "vsnd_c" --vpk_filepath "sounds/weapons/"
# sound event definitions (which wav plays for fire/reload; volume/pitch)
$CLI -i $VPK --vpk_extensions vsndevts_c --vpk_filepath "soundevents/" -d -o ~/csmc-assets/out/soundevents
```
Option names are from the command-line guide [OBS]; `--vpk_decompile`/`-d` requires `--output`; all `--gltf_*` need `--gltf_export_format`.

Known export limits [OBS format-support page]: only the highest LOD; materials flattened to glTF PBR; hitboxes not exported; additive animations come out as delta tracks unless `--gltf_compose_additive`; keyframes thinned unless `--gltf_keep_all_keyframes`; animation graphs (`vanmgrph`/`vnmgraph`) are not exported to glTF. Weapon animation clips live in `vnmclip` and export via the model [OBS: "Animgraph 2 clips (vnmclip) … feed … glTF export"]. Whether CS2 weapon viewmodel animations export cleanly with arms and correct skeleton is [UNV].
Audio: extraction is a bit-exact remux of the stored stream, WAV or MP3, never a re-encode [OBS]. Minecraft needs Ogg Vorbis, so a conversion to `.ogg` (ffmpeg `-c:a libvorbis`) is required, or play via OpenAL directly.

### Cheapest route into a Fabric 1.21.1 mod
1. **glTF runtime loader in Java (recommended).** Parse `.gltf` + `.bin` + PNGs (small, open spec; Java libs exist, e.g. jglTF, licence/maintenance [UNV — not checked]). Upload textures as `NativeImage`/dynamic textures, render skinned meshes with a custom `BuiltinModelItemRenderer` / render hook, and drive bones from the glTF animation channels on the CPU. Keeps the user's exported files as the single source of truth and nothing is baked into the repo.
2. **Convert to GeckoLib/Blockbench format.** GeckoLib consumes its own model JSON and animation JSON. Needs a converter (glTF → Bedrock-style geo + animation JSON); no existing tool verified [UNV]. Blockbench can import glTF and export Bedrock/GeckoLib [INF]. The conversion is lossy (skinned meshes → cube/bones for Bedrock geometry; GeckoLib supports `.geo.json` with polygon meshes in newer versions [INF/UNV]). It also requires committing or generating GeckoLib files per user.
Recommendation: option 1. A thin glTF skinned-mesh renderer is a bounded task (≈ 500–800 lines [INF]) and avoids a lossy conversion step. Fabric 1.21.1 render API details are out of scope here and unverified (note: the task brief mentions 1.21.1, an earlier note mentioned 26.3; use whatever the project targets).

Recommended pipeline: DepotDownloader (`-filelist`) → Source2Viewer-CLI export at first launch or via a setup script into `~/.csmc/cache/` (git-ignored) → mod reads `weapons.vdata` (JSON converted on load, or a build step to generate `data/csmc/weapons/*.json`), glTF models/textures and WAV/ogg sounds from the cache.

---

## 4. Legal and practical (not legal advice)

- **Steam Subscriber Agreement** [OBS, https://store.steampowered.com/subscriber_agreement/]: grants "a non-exclusive license and right, to use the Content and Services for your personal, non-commercial use". It also says, without Valve's written consent you may not "copy, photocopy, reproduce, publish, distribute, translate, reverse engineer, derive source code from, modify, disassemble, decompile, create derivative works based on" the Content. Exporting models to glTF is arguably a conversion/derivative; keeping outputs strictly local and personal is the defensible posture, but it is a grey area. [UNV] on how Valve treats it.
- **Fan content clause (§6.D)** [OBS]: you "may incorporate content from Valve games into your Fan Art" and distribute it "solely on a non-commercial basis". Fan Art is defined as "fan art, fan fiction, and audio-visual works". Whether a game mod qualifies is unstated [UNV].
- **Valve mod guidance** (Steamworks, Source mods) [OBS, https://partner.steamgames.com/doc/sdk/uploading/distributing_source_engine]: non-commercial, based on the official SDK, must not imply Valve endorsement; for TF2 "mods can only redistribute their own game files, and load TF2 content from a local install". That is the same load-from-local-install model, but is TF2-specific, and CS2 has no public SDK [INF].
- **VRF** is MIT and asks for attribution when used [OBS README]. If the project ships scripts that call it, add the line it asks for ("Powered by Source 2 Viewer").
- **Practical rules**: (1) do not commit or distribute any exported model, texture, sound, or `weapons.vdata` copy; git-ignore the cache dir. (2) Every player runs the exporter against their own CS2 install (CS2 is free, which helps). (3) Ship only your own code and the field-name mapping; generate the weapon JSON from the user's own `weapons.vdata` at setup rather than committing the numbers (the numbers are facts, but a verbatim table dump of Valve's data file is a copy; the AK values above in this report are for design only). (4) Name the mod distinctly; no Valve/CS logos. (5) Don't use this to bypass ownership of CS:GO/CS2 (not an issue for a free game).
- Server in LAN play: each client needs the cache; the server needs only data JSON and block-collision logic, not art.

## Open uncertainties
1. Exact CS2 content depot ID(s), and whether `-filelist` needs the depot specified; whether the anonymous account can fetch app 730 / server app 2347773 content. Not verified (SteamDB blocked).
2. How many `pak01_NNN.vpk` chunks hold weapons/sounds/materials; the partial-download saving is unknown until `--vpk_dir` is run.
3. Whether Source2Viewer-CLI has a native macOS arm64 release; whether DepotDownloader brew build is arm64 native.
4. `m_nPrimaryReserveAmmoMax` value semantics (3 for AK); where reserve ammo counts and ammo types are defined.
5. Reload duration and animation names are not in vdata; need the animation/vnmclip data. Whether weapon viewmodel animations (fire/reload/draw) export cleanly to glTF with usable skeleton, and whether viewmodel arms are included.
6. CS2's recoil generation: RNG algorithm, units of angle/magnitude, accumulation and decay, how `m_nRecoilSeed` and variance combine; no primary source found. The practical alternative is a measured per-shot pattern.
7. CS2's spread formula (how stand/crouch/move/jump/fire terms combine, `RecoveryTransition*`), and the CS2 penetration constants. The CS:GO-derived algorithm shown is inferred.
8. Secondary-mode array elements (scoped, silenced, burst) were not captured in the table.
9. Localised display names location and ammo type field.
10. Legal: whether glTF/WAV export counts as a prohibited derivative; whether a mod qualifies as "Fan Art"; Valve has no CS2 SDK terms.
11. Licences/maintenance of Java glTF libraries (jglTF etc.) and GeckoLib conversion tooling were not checked.
12. CS:GO legacy fallback (Source 1 `.mdl/.vtx/.vvd/.vtf`, VPK v1/2): not researched in this pass. Weapon script data would then come from `scripts/items/items_game.txt` and `scripts/weapon_*.txt`-style entries, with no maintained Java-native path found; use Crowbar/VPKEdit-class tools [INF, unchecked].
13. Netcode/lag compensation: not in scope so far. CS2 uses sub-tick input, with `m_nSpreadSeed`/`m_nRecoilSeed` as shared seeds [INF].


---

## 5. Weapon skins (paint kits) and agents

### Local VPK findings (2026-10-18)

**[OBS]** Read the user's `pak01_dir.vpk`, extracted `scripts/items/items_game.txt`
(8,390,439 bytes) and UTF-8 `resource/csgo_english.txt`, and dumped paint materials with
Source2Viewer-CLI `-b DATA`. These local files remain external to the repository.
`paint_kits` occurs in repeated KV1 sections: they must be merged, not overwritten.
Definitions contain ids/names, `description_tag`, `style`, wear bounds, optional kit-default
`seed`, `use_legacy_model`, and sometimes `composite_material_path`. They do **not** contain
the legacy color/pattern parameters. Those are in
`materials/models/weapons/customization/paints/vmats/<kit>.vmat_c`.
Modern `weapons/paints/.../*.vcompmat_c` graphs contain loose variables plus referenced
paint-template materials. The exporter merges template defaults with those variables.

**[OBS]** Deduplicating `[kit_name]weapon_id` keys from `client_loot_lists` and `item_sets`
gives **1,456 firearm/kit pairs**, **1,318 distinct associated kits**, and **35 firearms**.
Knives, gloves and unassociated/workshop-only definitions are excluded. Rarity comes from
`paint_kits_rarity`; it alone cannot establish a weapon association. Localized names resolve
`description_tag` through `lang/Tokens`, case-insensitively.

| `items_game` style | Finish | Pairs | Inputs and albedo interpretation |
|---|---|---:|---|
| 1 | Solid color | 107 | Weapon paint-by-number RGB mask, `g_vColor0..3`, base, cavity, wear/grunge |
| 2 | Hydrographic | 252 | Pattern RGB palette selectors, colors, masks, scale/rotation/offset |
| 3 | Spray paint | 171 | Pattern/colors/masks; true projection also needs weapon position/surface |
| 4 | Anodized | 19 | Tint plus weapon masks/base; metallic BRDF is not an albedo texture |
| 5 | Anodized multicolor | 140 | Pattern/colors, masks, metallic substrate |
| 6 | Anodized airbrushed | 38 | Projected pattern/colors, masks, metallic substrate |
| 7 | Custom paint job | 377 | Weapon-specific UV-authored pattern RGB; alpha is not paint opacity |
| 8 | Patina | 64 | Pattern, metal masks, oxidation ramps and wear/grunge; tinted-metal fallback |
| 9 | Gunsmith | 284 | Weapon-specific UV artwork, material masks and selective paint/metal response |
| 10 | Case hardening | 4 | Graph/ramp/metal treatment; tinted-metal fallback |

**[OBS]** Compiled template `F_PAINT_STYLE` is zero-based, unlike the one-based item style
(e.g. custom paint uses 6). Some modern kits' graph selects a different template than the
item's nominal style, so composition follows the actual template, while coverage retains
the declared style. Color inputs are `g_vColor0..3`; textures include `g_tPattern`,
`g_tMasks`/`g_tPaintByNumberMasks`, `g_tWear`, `g_tGrunge` and `g_tAmbientOcclusion`.
Graph variables carry numeric bounds and resource paths. Newer graphs can also override
masks and add overlay textures; arbitrary graph mutators are not generally evaluated.

### Implemented compositor: limits, not exact CS2 parity

Custom/gunsmith pairs account for **661/1,456 (45.4%)**; their UV artwork can be retained
closely in base color. Legacy kits select `body_legacy`, not the incompatible HD UV atlas.
Styles 1–7 and 9 cover **1,388/1,456 (95.3%)**, but simple styles still require compositing;
the remaining 68 patina/case-hardening pairs explicitly use the closest tinted-metal
approximation. No style is claimed to reproduce Valve's final shader exactly.

The CPU compositor blends palette/pattern inputs over the weapon base through masks.
It thresholds the sampled wear texture, biased by cavity and pattern durability, against
the item's actual wear float, exposing more base at higher wear; grunge lightly darkens
remaining paint. This is an authored approximation, **not** Valve's wear equation.
`wear_remap_min/max` constrain item-generation bounds; they do not cause a second remap
of the stored item float. Tier cutoffs are 0.07/0.15/0.38/0.45.
Rendering rounds wear to **256 buckets**; the exact float remains on the item.

Pattern placement uses deterministic Java RNG and graph bounds, not Valve's RNG.
Projected finishes use a seeded 2D UV projection rather than the shader's 3D/triplanar
position mapping. Normal/roughness/metalness, pearlescence, glitter, advanced overlays,
patina chemistry and arbitrary graph effects are not faithfully reproduced by the
base-color-only renderer. Unsupported finishes log their fallback.
Only the primary weapon atlas is replaced; separate accessory/lens materials retain
their base albedo. Live checks must inspect multi-material weapons as well as HD/legacy UVs.

Source 2 Viewer itself exports **inputs**, not a finished skin:
[format support](https://s2v.app/ValveResourceFormat/guides/format-support.html).
No community GLSL was copied or ported; exact unpublished shader/RNG behavior remains uncertain.

### Knives [OBS local VPK, 2026-10-19]
- `weapons.vdata` knife records mostly use numeric keys (`"507" = { m_szName = "weapon_knife_karambit" … }`); combat fields are identical across knife types and are dummy gun values, so gameplay stats are csmc-authored (see setup).
- Models: `weapons/models/knife/<stem>/weapon_<stem>.vmdl_c` hold only the knife mesh plus `inventory_inspect`/`inventory_icon`. First-person motion is in `animation/anims/viewmodel/knife/<stem>/*.vnmclip_c` (317 clips, all in `pak01_473.vpk`); Source2Viewer-CLI 20.1 exports each clip as glTF with the `viewmodel.vnmskel` arm skeleton, bone `wpn` holding the weapon.
- Finishes: no loot-list/item-set pairs exist for knives. `panorama/images/econ/default_generated/weapon_<id>_<kit>_{light,medium,heavy}_png.vtex_c` names 556 knife×kit pairs (78 kits, 20 knives; 0 unmatched icon names). Each ★ knife has HD `materials/composite_inputs/*_composite_inputs.vmat_c`; the defaults have none.

### First-person arms [OBS local VPK, 2026-10-19]
- Viewmodel arms are not under `characters/models/` (those `.vmdl_c` files decompile to a 24-vertex placeholder with a `dummy` bone). They live in the real agent models `agents/models/<agent>/<agent>.vmdl_c` as two mesh groups, `firstperson_default_gloves_arms` (bare arm `bare_arm_133` plus the agent's default glove) and `firstperson_sleeves` (the `characters/models/<agent>/sleeve/materials/*` material). The default agents are `ctm_sas` (CT, `glove_hardknuckle_black`) and `tm_phoenix` (T, `glove_fullfinger`).
- The `items_game` default gloves are `ct_gloves` → `agents/models/shared/arms/glove_hardknuckle/glove_hardknuckle.vmdl` and `t_gloves` → `.../glove_fingerless/glove_fingerless.vmdl`. Each glove model has `worldmodel` and `viewmodel` meshes; `viewmodel` is the first-person glove plus wrist. `weapons/models/shared/arms/weapon_arms.vmdl_c` is a generic bare-arm + fingerless rig (82 joints) and is not exported.
- Source2Viewer-CLI 20.1 writes skins only with `--gltf_export_animations`. A non-matching `--gltf_animation_list` keeps the skin without the 1,799 agent animations (about 500 MB of buffer). `--gltf_mesh_list firstperson_default_gloves_arms,firstperson_sleeves` selects the arm meshes. Loading the agent's animation groups touches `pak01_141.vpk`/`pak01_498.vpk`; that data is filtered out, so those chunks are optional. The T glove AO texture needs `pak01_390.vpk`.
- Skeleton: the meshes are bound to the full character skeleton (94 joints for `ctm_sas`, 86 for `tm_phoenix`, 52 for glove-only models) in a T-pose. 46 (agents) and 44 (gloves) joint names match the 63-node knife clip skeleton. Unmatched joints are `*_TWIST`/`TWIST1`, clavicles, scapulae, spine, legs and jiggle bones. Matched bones have identical local axes and child offsets in both rigs, but parents differ (`arm_upper_*` hangs off `clavicle_*`, not `armUpperStraighten_0_*`), so arms must be posed in model space, not by copying local transforms.

### Glove skins [OBS local VPK, 2026-10-19]
- The 8 paintable gloves are `items_game` items with prefab `hands_paintable`: `studded_bloodhound_gloves` (5027), `sporty_gloves` (5030), `slick_gloves` (5031), `leather_handwraps` (5032), `motorcycle_gloves` (5033), `specialist_gloves` (5034), `studded_hydra_gloves` (5035), `studded_brokenfang_gloves` (4725). Their `model_player` is `agents/models/shared/arms/glove_<bloodhound|sporty|slick|handwrap|motorcycle|specialist|hydra|brokenfang>/…vmdl`, with the same `worldmodel`/`viewmodel` mesh pair as the default gloves. The `viewmodel` mesh has three primitives: `bare_arm_133` plus `glove_<x>_left` and `glove_<x>_right`. Both glove materials take the same paint, so they share one finish texture [INF].
- Pairs: `panorama/images/econ/default_generated/<glove_id>_<kit>_light_png.vtex_c` names 94 glove×kit pairs (handwraps 12, moto 12, driver 20, specialist 19, sport 19, bloodhound/hydra/broken fang 4 each), every one a known paint kit. Glove kits are not in loot lists; many use ids ≥ 10000, which `--skins` skips.
- No final finish textures exist in the VPK. CS2 composites glove finishes at runtime from layered shaders. Older kits (`gloves/paints/<kit>.vmat_c`, shader `csgo_customglove`) use `g_tLayerMask` (R/G/B = layers 2–4 over layer 1; alpha = 8-level tint id) with per-layer `g_tDetailN`/`g_fDetailScaleN` and 8 `g_vColorTintN`. The 22 `volatile_02` kits (`composite_material_path` → vcompmat whose `m_strResourceMaterial` is `items/assets/paintkits/volatile_02/<kit>.vmat`, shader `csgo_customglove_preview`) use `g_tLayerId`, `g_tTintId`, `g_tSurfaceN` with `g_vUvScaleN`, and `g_vId<N>Color`, plus `F_PATTERN`/`g_bId<N>Pattern` with `g_tPattern`. Some kits (`sporty_jaguar`) have only `gloves/paints/<kit>.vcompmat_c`, whose container material `gloves/textiles/<kit>.vmat` is the paint.
- Chunks: the glove textures need `pak01_114`, `pak01_393`, `pak01_427` and `pak01_500` (the last holds the gloves' own default normal maps). Exporter details: [asset-export.md](asset-export.md) "Glove export".

### Gun viewmodel clips [OBS local VPK, 2026-10-19]
- `weapons.vdata` has no animgraph field for guns; it names only `m_szAnimSkeleton` (`animation/skeletons/weapons/<skel>.vnmskel`). Clip selection lives in the viewmodel animgraphs: 33 variations `animation/graphs/viewmodel/viewmodel_gun.vnmgraph+<key>.vnmgraph_c` (keys such as `m4a1s`, `usp`, `five_seven`, `galil`, `ssg08`, `ssg08_legacy`) plus own graphs `viewmodel_gun_{cz75,elites,revolver}`. Each decompiles to KV3 text with `"animation/anims/viewmodel/.../*.vnmclip"` references and a link to its `viewmodel_inspects.vnmgraph+<key>` variation. 32 of the 36 graphs also reference `rifle_m249/idle_from_activity_m249`; negev, cz75, elites and revolver do not.
- Folders: `animation/anims/viewmodel/{pistol,rifle}/<folder>/*.vnmclip_c`, 4–17 clips per gun (245 clip sources for 36 guns; 241 in `pak01_473.vpk`, the rest in `pak01_386`/`pak01_262`). `m4a1_silencer` and `usp_silencer` have no own folder: their graphs use `rifle/_default_rifle` and `pistol/_default_pistol`, and those clips embed the `m4a1_silencer`/`usp_silencer` skeletons. No graph or folder exists for `m14`.
- Source2Viewer-CLI 20.1 exports each clip as glTF with the `viewmodel.vnmskel` arm rig plus the gun skeleton under a node named after the `.vnmskel`. Its bones (`weapon`, `weapon_offset`, `bolt`, `clip`, `cliprelease`, `trigger`, `silencer`…) have the same names as the world-model glTF bones. Durations come from animation input accessor `max`. Idle clips are single-frame, except `idle_revolver` (7.5 s).
- CS2 authors no separate shell-reload start/loop/end or bolt/pump cycle clips; shotguns and bolt-actions have one `reload` and one `shoot1`. The revolver adds `prepare_shoot` (prime), `shoot_alt1`, `dryfire` and eight additive `chamber_position_anim_revolver_<n>` cylinder poses. The elite has `shoot_{left,right}1` and `shoot_{left,right}last` with no plain `shoot1`. Exporter details: setup.md "Gun viewmodel clips".

### Equipment clips and cache metadata
- Owned equipment sources (`tools/cs2_equipment.py`): world models `weapons/models/healthshot/weapon_healthshot.vmdl`, `weapons/models/c4/weapon_c4.vmdl`, `weapons/models/defuser/defuser.vmdl`. Viewmodel clips live under `animation/anims/viewmodel/equipment/<id>/` (healthshot: `draw`, `idle`, `inject`; C4: `draw`, `idle`, `lookat01-03`, `plant`). The defuse kit has no dedicated first-person clips.
- Clip durations come from the compiled clip's `m_flDuration` (`--block DATA`), not from the glTF accessor maxima, because glTF optimization zeroes constant idle channels. Marker times are compiled normalized fractions multiplied by that duration, not authoring frames.
- Cache schema, record fields and the `--equipment` / `--icons` commands: [asset-export.md](asset-export.md) "Equipment export (`--equipment`)".
- Legal posture: same as weapons (section 4).

### CS2 player agents [OBS paths, INF conclusions]
- Agent models are `agents/models/...` `.vmdl_c` in `pak01_dir.vpk` [OBS: exporting-models guide table "Counter-Strike 2 | agents/models/ | Player and agent models"], exportable to glTF with skeleton and animations like weapons (VRF limits above apply: highest LOD only, flattened PBR materials).
- **Minecraft skins:** not possible directly. A Minecraft player skin is a 64×64 texture wrapped on a fixed boxy mesh; an agent is a high-poly skinned mesh with its own UVs. You cannot convert it to a skin. Options: (a) render the agent glTF as a custom entity using the same glTF renderer as the guns (consistent, keeps the real model and textures); (b) hand-author or auto-bake a low-fidelity 64×64 skin per team (e.g. project colours onto the Minecraft box UVs) — lossy, a design choice, no tool verified [UNV]; (c) keep vanilla player models and show agent-coloured team tints. Hitbox/animation sync with the vanilla player model is the main cost of (a). Recommended: (c) first, (a) as a later feature.
- Legal posture is the same as weapons: runtime from the user's own install, local cache, no redistribution (section 4).

### Added open uncertainties
14. Exact Valve compositing/wear/seed RNG equations, advanced graph mutators, projected coordinates
and non-albedo material response remain unverified; the implemented approximations are listed above.
17. Agent glTF export quality and animation compatibility; no verified Minecraft-skin conversion tool.
