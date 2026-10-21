# CSMC hub world

One void-superflat world hosts four places: a **hub** (spawn), the **PvE arena** (`tools/csmc_arena.py`), the **1v1 Workshop arena** (`tools/csmc_workshop_arena.py`) and an original **surf course** (`tools/csmc_surf.py`, see [arenas.md](arenas.md#surf-course-function-data-pack)). The old indoor PvP mirror (`csmc_pvp_arena.py`) is not installed. `tools/csmc_hub.py` writes the `csmc_hub` data pack: the hub build, the portal plates, the return plates and all world policing. It changes no Java code and none of the other generators.

| Place | Namespace | Box (inclusive) | Spawn |
|---|---|---|---|
| Hub | `csmc_hub` | x -74..-26, y 94..120, z -29..19 | `-49.5 101 9.5`, facing north |
| PvE arena + lobby | `csmc_arena` (`--floor 232`) | arena x 79..121, z 79..121, y 230..247; lobby x 89..111, z 122..140, y 230..241 | lobby `100.5 233 133.5` |
| Workshop map + lobby | `workshop` (`--floor 64`) | map x -1..26, z -40..0, y 63..77; lobby x 38..66, z -34..-6, y 63..78 | lobby `48 65 -20` |
| Surf course | `csmc_surf` (`--floor 190`) | x -300..-120, y 150..230, z -6..126 | start `-281.5 190 8.5`, facing south |

## Install order

```sh
W=/absolute/path/to/world
python3 tools/csmc_arena.py --world $W
python3 tools/csmc_workshop_arena.py --world $W --floor 64
python3 tools/csmc_surf.py --world $W --floor 190
python3 tools/csmc_hub.py --world $W            # --pve-floor/--workshop-floor/--surf-floor must match those generators
```

Then, on the server console (`enable-command-block=true` is needed by the two arenas):

1. `reload`
2. `function csmc_arena:setup`, `function workshop:setup`, `function csmc_surf:setup`, then `function csmc_hub:setup` — **hub setup runs last**, because it pins the server-wide rules and the world spawn (Workshop's setup would otherwise win).
3. Wait a few seconds for the forceloaded chunks.
4. `function csmc_arena:build_all`, `function workshop:build_all`, `function csmc_surf:build_all`, then `function csmc_hub:build` — hub build runs last, because it also repairs the villagers and places the return plates inside both lobbies.

`csmc_hub:build` is idempotent and can be re-run at any time. If someone re-runs `csmc_arena:build_lobby` or `workshop:build_lobby` (admin action, not part of normal play), those wipe the return plate; run `function csmc_hub:build_returns` afterwards. Re-running either arena's `setup` after the hub's changes the world spawn again, so run `function csmc_hub:setup` once more.

`csmc_surf:build_all` clears the whole course box, which includes the surf start and finish return plates. Run `function csmc_hub:build` after every surf rebuild so those plates are restored.

## server.properties

```properties
enable-command-block=true
spawn-protection=0
pvp=true
allow-flight=true
gamemode=adventure
force-gamemode=true
difficulty=normal
level-type=minecraft\:flat
generator-settings={"biome"\:"minecraft\:the_void","features"\:false,"lakes"\:false,"layers"\:[{"block"\:"minecraft\:air","height"\:1}],"structure_overrides"\:[]}
generate-structures=false
```

`level-type` and `generator-settings` only take effect while the world does not exist yet. The `\:` escapes are how Minecraft itself writes the file.

`allow-flight=true` is required by the surf course: its long arcs leave the ground longer than vanilla allows without it, and the server would kick the player for flying.

## Hub and portals

A floating plaza (radius 24, floor Y 100) with a lime crosshair emblem, a spawn dais, rim beacons, lamp posts, decorative plank crates, and an invisible barrier cage above its wall. Nothing in it can be opened, lit, burnt or exploded: no containers, TNT, lava, fire, flint and steel or villagers. There are no command blocks at all.

- **PvE Arena** (orange gate, west of centre, left of the spawn view) and **1v1 Workshop** (blue gate, east): each has a glowing pad with a stone **pressure plate**, a glass screen frame with lantern backing, three signs and a floating label.
  - PvE plate `-59 101 -18` sends you to the PvE lobby `100.5 233 133.5`.
  - Workshop plate `-41 101 -18` sends you to the Workshop lobby `48 65 -20`.
- **Back to hub** plates, one in each lobby on its own lime pad with a standing sign and a label: PvE `94 233 132` (west of the lobby spawn, clear of the buttons, beds, ender chest and window) and Workshop `52 65 -9` (south aisle, clear of the control buttons, shop strip and spawn).
- The plates are polled by `csmc_hub:tick` (a stone plate only counts while `powered=true`), so no redstone is involved. A plate also sets the player's **respawn point** to the destination lobby (hub for the return plates), so dying in the PvE arena respawns in the PvE lobby rather than the hub. Workshop keeps its own per-player spawnpoint handling (team spawn during a match, lobby afterwards); the hub plates only set the same lobby point.
- **Surf** (purple gate, centre, midway between the PvE and Workshop gates): plate `-50 101 -18` sends you to the surf start platform `-281.5 190 8.5`. Surf return plates go to the hub: start `-270 190 4` and finish `-127 175 115`. The finish restart plate `-151 175 115` belongs to `csmc_surf` and sends you to the start platform, not the hub. All set the destination respawn point. See [arenas.md](arenas.md#surf-course-function-data-pack).

## Policing (all areas, every tick)

An **administrator** is a player with the tag `csmc_admin`: `/tag NAME add csmc_admin`. Everybody else is a normal player. Being an operator is not enough.

| Rule | Detail |
|---|---|
| Adventure mode | Every non-admin is forced into adventure mode every tick, everywhere. This stops breaking and placing by hand. It also stops gun, grenade and C4 terrain damage: `BlockBreakPolicy.mayModify` refuses shooters for whom `blockActionRestricted` is true, and adventure mode without a `can_break` item is restricted. Workshop's save/restore of the old game mode ends in adventure again within one tick. |
| Bounds | A non-admin whose hitbox is outside the union of the hub box, the PvE arena and lobby boxes, the Workshop map and lobby boxes and the surf course box is teleported to the hub spawn. This includes everything below y 40, since every box is above it. Admins are never moved. |
| Safe zones | Hub, both lobbies and the surf course box (not the arena or map boxes): `resistance` level 5 (no particles) and `saturation`, 1 s refreshed every tick. Resistance V removes all damage that does not bypass resistance; `csmc:bullet` is only tagged `bypasses_armor`, `bypasses_cooldown`, `is_projectile` and `no_knockback`, so bullets are absorbed too. Leaving a safe zone clears the effect once. |
| Dropped items | Item entities inside the hub box are removed every tick. Nothing is removed in the Workshop area (its dropped property stays) or the PvE area (its own regeneration handles it). |
| Villagers | Every villager (PvE visitors, Workshop shop) gets `Invulnerable`, `PersistenceRequired`, `NoAI`, `Silent` the tick after it appears. |
| Movement mode | The per-player override from [movement.md](movement.md) replaces the `csmcMovement` gamerule. Every player (admins too) holds exactly one movement tag: `csmc_movement_competitive` inside the PvE arena or Workshop map, `csmc_movement_surf` inside the surf box, `csmc_movement_bhop` elsewhere (hub, both lobbies and in transit). Both bhop and surf enable auto-bhop; competitive does not. Tags are only rewritten when wrong, avoiding per-tick client resync. The gamerule stays `competitive` as the fallback. |

### Pinned in `csmc_hub:setup`

| Rule | Value | Why |
|---|---|---|
| `keepInventory`, `doImmediateRespawn` | `true` | The Workshop requires both. PvE's `deliver_rewards` only gives to living players and queues owed rewards in scores, so it works with either setting. |
| `csmcMovement` | `competitive` | Workshop's choice. |
| `mobGriefing`, `doFireTick`, `doMobSpawning`, `doTraderSpawning`, `doPatrolSpawning` | `false` | PvE uses `/summon`, which `doMobSpawning` does not affect. Trader and patrol spawners are separate rules, so they are pinned too. |
| `doDaylightCycle`, `doWeatherCycle` | `false`, plus `time set noon` and `weather clear` | |
| `commandBlockOutput`, `spawnRadius` | `false`, `0` | |
| `csmcC4Breaking` | `false` | |
| `csmcBlockBreaking` | `false` | In adventure mode every block already stops a bullet, so terrain behaves the same for players. With the rule off the same holds for an administrator, and for a grenade whose owner has logged out: `mayModify` skips the game-mode check when it has no shooter, so only this rule protects the terrain then. |
| World spawn | the hub spawn, facing north | Overrides Workshop's. |
| Sidebar scoreboard | cleared | The PvE setup leaves its "Arena Kills" sidebar visible everywhere; PvE and Workshop re-set their own when a round or match starts. |

The hub box is forceloaded.

## Surf verification (local port 25975, 2026-10-20)

- Built the worktree mod with Java 21 (`./gradlew build -x test`). Installed all four packs into a fresh void world with `online-mode=false` and the properties above; setup/build ran in the documented order. No function parse/load or command errors; the known text-display diagnostics below still appeared. The surf/loadout Python suites passed all 31 tests, including build-box limits (largest fill 24,073 cells), geometry bounds, sequential checkpoints, hub integration, custom floor offsets and sound positioning.
- A real modded quickplay client walked onto the centre Surf plate: position changed to the start platform, `SpawnX` became `-282`, and its sole movement tag was `csmc_movement_surf`. Hub was bhop; both arena interiors were competitive.
- Leaving the start registered state 1 and 20 timer ticks. Checkpoint 1 registered, then a fall at `-246.5 160 60.5` returned to `-245.5 187 106.5` with checkpoint 1 retained and the timer continuing. Teleport-assisted visits to the remaining pads finished at 91 ticks (4.55 s), recorded the personal best and sent both finish and record chat messages. The timer remained 91 after waiting.
- Both surf hub-return plates and the finish restart plate activated with the client; destination position and respawn X matched hub/start respectively. Far below the course (Y 140) and far outside it (X -340.5) returned to the hub. Bullet damage in surf left health at 20.
- Scripted input completed the first 80-block ramp stage and landed on checkpoint 1 at 12.35 s, with 232 telemetry samples reporting `surf=true` and about 250 units/s along the ramp. The only setup teleport aligned the player on the start platform at `-275.5 190 8.5`; there were none during the stage. Background input was not refused. Completing all five stages without teleport assistance remains unverified.
- Screenshots were captured and visually inspected: `/tmp/csmc-surf-evidence-5u3bj_wb/{hub-three-gates,surf-start,surf-stage,surf-finish}.png`; client/install logs are alongside them. The three symmetric gates, start controls/return pad, planar ramps and finish/restart/return pads were visible. These are local evidence files, not bundled assets.

## Verification (historical, three-pack: hub, PvE arena, Workshop)

Local server on port 25940, fresh void world, mod built from this branch. This covers the three packs only, before `csmc_surf` existed; it is not verification of the surf course.

- All three packs installed in the stated order with no command errors in the console; `/function csmc_hub:setup` printed the pinned gamerule values.
- Both portal plates and both return plates were walked onto with a real client; spawn was at the hub.
- Policing, observed in the console: a non-admin set to survival was back in adventure within the second; a player teleported to 1000 150 1000 and another to y 20 were returned to the hub spawn; a player in the hub and in both lobbies had resistance V (amplifier 4) and lost no health to `damage 5 csmc:bullet`, while the same damage in the Workshop map and the PvE arena took 5 health; a stone dropped in the hub vanished while one in the Workshop lobby stayed; an unflagged villager gained all four flags and the hub tag within seconds; a player with `csmc_admin` stayed in creative and was not moved.
- Movement tags, via `data get entity <player> Tags`: exactly `csmc_movement_bhop` in the hub and in both lobbies, exactly `csmc_movement_competitive` inside the PvE arena and the Workshop map, and `csmc_movement_off` plus `csmc_movement_surf` added by hand were removed again within seconds. The client-side effect of the tag was not measured (the `[csmc move]` dev log was not enabled).
- Not exercised: real gun and grenade fire at terrain. That adventure shooters are refused is asserted by `BlockDamageGameTests` (adventure cover is `FORBIDDEN` for a trace and `DENIED` for direct damage); it was not re-run here.

## Things to know

- The PvE arena's join and round start (`csmc_arena:loadout`) no longer remove personal items: Workshop gold nuggets, cases, skin attachments, gloves, kevlar and other weapons stay in the inventory. It supplies any missing AK-47, M4A4, AWP and knife, fills only empty armor slots with enchanted chainmail, and replaces arena ammunition and golden apples with a fresh supply (existing ammunition of those four types is reset, not added to).
- The server log shows one `Display entity: Not a string` error for every text display summoned (the PvE lobby title does the same). The entities are created and render; it is not a command failure.
