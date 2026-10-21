# Arenas

CSMC ships Python generators in `tools/` that write command-block data packs into a Minecraft world: a PvE wave
arena, a 1v1 PvP mirror of it, a separate Workshop-map 1v1 arena with a nugget shop, and a surf course with checkpoints and a timer. The packs contain only
Minecraft blocks, command blocks, functions and (for surf) the mod's `csmc:surf_ramp` blocks; the repository contains no Valve assets. For the hub that ties
arenas together, see [hub.md](hub.md).

Requirements common to these packs: Python 3, a server (or integrated LAN world) with `enable-command-block=true` in
`server.properties`, and a restart after changing it. CSMC guns, knives and cases work in the arenas; players should
have exported their own CS2 assets ([install.md](install.md)) to see the real models and hear the real sounds.

## PvE arena (command-block data pack)

`python3 tools/csmc_arena.py [--world mod/run-server/csmc-test] [--floor 232]` (the default world is the development server world, `mod/run-server/csmc-test`) writes the `csmc_arena` data pack into the world. It needs `enable-command-block=true` in `server.properties` and a server restart. Then run `/function csmc_arena:setup`, wait a few seconds for the forceloaded chunks, and run `/function csmc_arena:build_all`.

- **Layout:** a sky arena (x/z 79-121, floor Y 232) with a bedrock shell, obsidian lining, rainbow concentric floor, coloured pillars, a stripe around the walls, sea-lantern lighting and 30 villager visitors in colour-coded stands; and a lobby south of it (x 89-111, z 122-140) with a glass window into the arena.
- **Lobby buttons** (each under a pedestal, driving a command block): START (10 s countdown, then a 60 s round of zombie, skeleton, spider and husk waves), TP (to the arena), REBUILD (regenerates the arena between rounds), STOP, SET SPAWN. Nine coloured beds also set spawn. A button on the arena podium returns you to the lobby.
- **HUD:** a bossbar (countdown, then time left), an actionbar mobs-left counter, and a sidebar scoreboard of kills per player. A round ends with the kill totals and winner in chat, and the arena regenerates 5 seconds later.
- A hidden repeating command block in the lobby floor runs `csmc_arena:tick`. The pack is the only source of the build, so `build_arena` and `build_lobby` always restore it exactly. Re-run the tool after editing it, then `/reload`.

Arena entry and match start never remove personal items: other weapons, skins, cases, gloves, kevlar and Workshop nuggets stay in your inventory with all their components. Each missing AK-47, M4A4 (`m4a1`) and AWP and a missing knife are supplied, with ammunition (180 rifle, 60 pistol/SMG, 30 sniper rounds and 32 shotgun shells), enchanted chainmail (Unbreaking III, Blast Protection IV) in any empty armor slot, and five normal golden apples. Re-entering replaces those four ammunition types and the golden apples with a fresh supply rather than accumulating them. Both lobbies have additional lighting; dangerous mobs inside the arena/lobby are cleared before entry and match start.

Completed games award the winner **2 Weapon Cases + 1 Loadout Case** (`csmc:inventory_weapon_case`), and other participants **1 Weapon Case**. PvE uses the highest positive kill count (tied leaders share the winning payout); a zero-kill round has no winner. PvP pays the first-to-five winner and the other duelist. STOP/abort and repeated completion calls do not award cases. Rewards stay in your inventory after the game.

PvE rewards owed to players on the death screen are queued until they respawn, rather than given into inventory that vanilla discards. The queue survives round cleanup and reconnects. Regeneration removes only arena litter from the arena floor (wave-mob drops and arena-issued ammunition, golden apples and chainmail); dropped weapons, cases and other personal items are kept.

## 1v1 PvP arena (mirror of the PvE arena)

`python3 tools/csmc_pvp_arena.py` writes the `csmc_pvp` data pack, a mirror of the PvE arena across x=0 (arena x -121 to -79, lobby x -111 to -89, same floor Y). Run it on a server with `enable-command-block=true`, then `/reload`, `/datapack enable "file/csmc_pvp"` if needed, `/function csmc_pvp:setup`, wait for the forceloaded chunks, and `/function csmc_pvp:build_all`.

- **Flow:** one player presses JOIN BLUE and another JOIN RED in the lobby, then START. A 5 s countdown holds both on their spawn pads (blue west, red east). First to 5 kills wins. A killed player respawns at once on their own side with replenished arena gear and 3 s of protection; the match is not reset. The winner is announced with a firework, and everyone returns to the lobby after 5 s. STOP aborts a match or clears the teams, WATCH teleports to the stands, and the EXIT button on the stands returns to the lobby.
- **Map:** unbreakable base (bedrock shell and obsidian lining). Obsidian hiding spots (spawn rooms, centre pillar, side corners) block bullets and are permanent. Planks, glass, stone bricks and wool cover can be shot away; the whole map is rebuilt before each match and by REBUILD.
- **Side effects while a match runs:** players are put in adventure mode, `keepInventory` and `doImmediateRespawn` are on. Personal guns and knives are retained, including after the match. At the end both gamerules go back to false, players return to survival, and their respawn point is set to the PvP lobby.
- **HUD:** an actionbar score (`BLUE 3 - 2 RED`) and the sidebar kill scoreboard (colored by team).

## Workshop 1v1: am_grass2 and nugget trading

This arena can run on its own server, or together with the PvE arena in the hub world ([hub.md](hub.md)), where
`csmc_hub` re-pins the shared settings after this pack's setup. Enable command blocks on that server. The generator
requires an explicit world path so it cannot accidentally target the default development world:

```sh
python3 tools/csmc_workshop_arena.py --world /absolute/path/to/new-server/world --floor 64
```

From that server's console, run `reload`, `function workshop:setup`, wait for the
forceloaded chunks to load, then `function workshop:build_all`. The lobby is at
`48 65 -20` for floor 64. Use JOIN BLUE / JOIN RED, then START; the other buttons
stop/leave and rebuild the map. Setup enables `keepInventory`, immediate respawn,
and competitive CS movement **server-wide**, and sets the world spawn to the
open-sky lobby with spawn radius zero. On a standalone server that is what you want; in the hub world run
`csmc_hub:setup` and `csmc_hub:build` afterwards (see [hub.md](hub.md)). It does not change the PvE arena's
functions or state.

Matches reuse the five-second countdown, first-to-five kills, immediate team
respawns and three-second respawn protection. Each completed match pays **5 gold
nuggets to the winner and 1 to the loser**, once per match, not per kill. Abort,
disconnect and repeated completion calls do not create prizes. Owed rewards wait
for a living player. Personal items and their components—including weapons,
armor, gloves, skins, cases and currency—are retained; missing basic weapons and
ammunition are supplied without clearing personal stacks. Map resets do not
rebuild the shop or delete dropped property.

Three trading villagers sell Weapon Cases for **3 nuggets**, Loadout Cases
(`csmc:inventory_weapon_case`) for **4**, gun-skin attachments for **40**, and
knife-skin attachments for **60**. The initial selection includes AK-47 Asiimov,
M4A4 Howl, AWP Dragon Lore, and Karambit/Butterfly Fade, Marble Fade and Case
Hardened. Craft an attachment with its matching weapon as usual. There are no
spectator villagers or crowds; the arena, lobby and trading stalls are lit.

**Map authority and fidelity:** this recreates one playable cell of
[am_grass2, Workshop item 279697343](https://steamcommunity.com/sharedfiles/filedetails/?id=279697343),
by bakéd potato / Sean Lewis (`splewis`), from the
[author-published VMF, mapversion 20](https://github.com/splewis/csgo-multi-1v1/blob/a710ab701f58c8ebe2bb4619297ccd37fcd62232/misc/am_grass2.vmf).
The VMF SHA-256 is
`ab420e622e973912de80f85e99df440b7223668a1a247f6c8e6ce96ebff4c1b6`.
It is the geometry authority; equality with the compiled Workshop BSP is
**unverified**. The upstream repository's license at that pinned revision is
GPL v2; its text and source attribution accompany the derived map geometry in
`tools/licenses/am_grass2-GPL-2.0.txt`.

The selected cell's grass floor is Source X `704..1728`, Y `1232..2768`,
Z `0..32`: **1024 × 1536 Hammer units**, or **25.6 × 38.4 blocks** at the existing
40-units-per-block scale. Source `(x,y,z)` becomes
`((x-704)/40, floor+0.2+z/40, -(y-1232)/40)`. The chosen original T and CT spawns
map to `(6.4, floor+1.025, -34)` and `(19.2, floor+1.025, -4.4)`, facing inward.
The original is outdoors; invisible boundary/sky collision is not a visible roof.

Floor, walls and low L-shaped cover follow the measured brush planes; the two
sloped metal ramps use the native partial-block collision system. Thin walls,
boundaries and prop silhouettes are constrained by Minecraft's block grid, so
this is **not a pixel-identical art or collision port**. Materials are Minecraft
interpretations, not Valve textures. The four wooden crates and two nitro stacks
use occupied cells measured from original Source1 physics hulls, not guessed
model names or a single bounding box around the nitro stack.

Prop measurements came from the maintainer's authorized CS:GO legacy depot 731,
manifest `1224088799001669801` (2023-10-12), chunks 070 and 092. These are Source1
models, not substitute CS2 models; equality with the map author's historical
model versions is unverified. Extracted models, physics files, textures and
private analysis metadata are **not bundled or committed**. Only the authored
Minecraft cell interpretation and source/model identity are included.

**Verification:** on a separate Minecraft 1.21.1 server, two real clients joined;
a fresh player appeared at lobby floor height, and a killed participant respawned
alive at the correct team spawn without ending the match. Five server-command
bullet kills credited to the opponent drove the actual first-to-five scoreboard:
the winner received exactly 5 nuggets and the loser 1, with the loser's existing
inventory unchanged. Actual merchant-menu checks exercised every initial offer;
same-tick shop rebuilding left exactly three active merchants. Independent
numerical checks compared the generated brush, ramp and prop occupancy with the
pinned source and private collision measurements. Rendered views confirmed the
open-air arena and separate three-stall lobby. Manual ramp traversal and
pixel-identical Workshop art remain unverified.

## Surf course (function data pack)

`python3 tools/csmc_surf.py --world /absolute/path/to/world [--floor 190]` writes the `csmc_surf` data pack. There are no rounds or kill scoring, just per-player timed runs. It uses functions, not command blocks, and needs `allow-flight=true` and the CSMC mod: the geometry uses `/csmc surf ramp` and `/csmc surf ridge`. The hub world's arenas additionally require `enable-command-block=true`. If changing `--floor`, pass the same value as the hub generator's `--surf-floor`. See [hub.md](hub.md#install-order): run `csmc_surf:setup`, wait for the forceloaded chunks, then `csmc_surf:build_all`, with hub setup/build last.

- **Layout:** box x -300..-120, y 150..230, z -6..126. The start platform is at the north end (z 0..19), spawn `-281.5 190 8.5` facing south. Five stages, each an 80-block ramp along z 20..99, alternate between the two ends: start (north) → stage 1 → checkpoint 1 (south, z 100..119) → stage 2 → checkpoint 2 (north) → stage 3 → checkpoint 3 (south) → stage 4 → checkpoint 4 (north) → stage 5 → finish pad (south, x -154..-124, z 100..119, walking y 175). Each pad lies 3 blocks lower than the one before. The whole course is lit so the ramps stay readable at night.
- **Restart:** a plate at `-151 175 115` on the finish pad returns you to the start platform and resets the run.
- **Timer:** standing on the start platform readies a run, and leaving it starts the timer. Reaching the finish after checkpoint 4 stops it. Times display seconds and hundredths at 20-tick resolution (0.05 s), for example `12.35 s`.
- **Checkpoints:** each player has a checkpoint count (`surf_cp`): 0 at the start, 1 to 4 at the checkpoints and 5 at the finish. Pads only advance the next checkpoint in course order. Each checkpoint is announced in chat with its time; falling returns the player there.
- **Best time:** each player's personal best (`surf_best`) persists with the world. The actionbar shows the run's time, checkpoint count and best while running, and the finish time and best after the finish, with a new-record message when the best is beaten.
- **Falls:** falling into a stage's catch plane (20 blocks under its pad) returns you to your last checkpoint, facing along the next channel. Before checkpoint 1 that is the start platform.
- **Hub policing:** the whole course box is a safe zone (see the policing table in [hub.md](hub.md#policing-all-areas-every-tick)). A non-admin outside the union of all boxes is sent to the hub. Inside the course box every player holds the `csmc_movement_surf` tag.
