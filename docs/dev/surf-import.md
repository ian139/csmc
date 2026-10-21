# Surf map import

`/csmc surf import <name> [origin]` turns a user-provided Hammer map file (`.vmf`) into Minecraft blocks, surf ramp
cells (the `csmc:surf_ramp` block from `docs/surf.md`), teleport zones and spawn points. This page records why VMF
import was chosen, exactly what is supported, and every approximation.

Code: `mod/src/main/java/dev/csmc/surf/importer/`. Tests: `mod/src/test/java/dev/csmc/surf/importer/` (fixtures in
`mod/src/test/resources/surf/`, all synthetic) and `mod/src/gametest/java/dev/csmc/surf/importer/`.

## Decision: convert decompiled VMF; Minecraft-native builders for new maps

There are three ways to get a surf map into the game:

| Path | What it needs | Fidelity | Cost / risk |
|---|---|---|---|
| **A. Direct `.bsp` reader** | Parse the compiled map: header + lump directory, `LUMP_PLANES` (1), `LUMP_BRUSHES` (18), `LUMP_BRUSHSIDES` (19), entity lump (0) | Exact collision brushes (vbsp keeps every player-solid brush with its planes and adds bevel planes, `dbrushside_t.bevel`) | Game-specific lump versions (VDC "BSP (Source)/Game-Specific"; version 21 was introduced with Left 4 Dead 2 per the VDC BSP talk page); the file also embeds packed materials/models (pakfile lump) that must never be imported; func_detail is already merged so classes are lost; we would own a binary parser for every variant. |
| **B. Decompile to VMF, then convert (chosen)** | A VMF file, from Hammer (the user's own map) or from BSPSource (`.bsp` → `.vmf`) | Brush planes as authored (three points per side), entity classes and keyvalues | One extra user step (run BSPSource); BSPSource cannot perfectly restore a few vbsp-consumed entities (its README: func_instance, func_viscluster, ...) — none of them affect surf collision. |
| **C. Minecraft-native rebuild** | `/csmc surf ramp|ridge` builders (SurfBlocks packet, `docs/surf.md`) | Exact ramps by construction, any angle | Manual: a builder places one ramp/ridge box per command; rebuilding an existing map by hand is slow and not faithful. |

Decision: **B for existing maps, C for new maps.** Evidence:

- VMF is a documented plain-text format (VDC "VMF (Valve Map Format)"); the reference loader is open
  (Source SDK 2013 `utils/vbsp/map.cpp`, `CMapFile::LoadSideCallback`, `PlaneFromPoints`), so the importer
  reproduces vbsp's own plane construction instead of guessing.
- BSPSource is maintained (GitHub `ata4/bspsrc`, last push 2026-10-11) and released into the public domain
  (`LICENSE.md`: Unlicense; bundled libraries Apache 2.0/BSD), so recommending it carries no licence burden. Its
  README states it decompiles Source BSP maps (not GoldSrc or Source 2) back to VMF for Hammer.
- Reading VMF keeps textures, models, sounds and packed files out of the pipeline by construction: the importer
  reads only plane points, material *names* and entity keyvalues. A direct BSP reader would sit next to the packed
  content.
- The output format is the same for both import sources and for C: ramp cells are `SurfRamps.placeWorld` cells, so
  imported and hand-built ramps behave identically under CS movement.

## Prerequisites (what the user must provide)

1. **A map you may use.** Your own map, or a map whose author permits this use. The importer never downloads
   anything and ships no maps; the test fixtures are synthetic (`csmc_test/*` material names).
2. **A `.vmf` file.** From Hammer (File → Save) or from BSPSource (open the `.bsp` you are allowed to use, write the
   `.vmf`). BSPSource is a Java desktop/command-line tool from <https://github.com/ata4/bspsrc>, public domain
   (Unlicense; see its `LICENSE.md`); it runs on the user's machine and csmc neither bundles nor downloads it. It only
   handles Source engine maps (CS:GO, CS:S); Source 2 (CS2 `.vpk` maps) and GoldSrc maps are not supported by it or by
   this importer. Plain text; UTF-8 or ASCII (other bytes inside values are replaced, structure must be ASCII).
3. **Put it on the server** at `<server directory>/csmc/surfmaps/<name>.vmf` (dedicated server: the directory with
   `server.properties`; single-player/LAN host: the game directory, e.g. `.minecraft`). The directory is created on the
   first `/csmc surf import`. `<name>` is 1–64 characters of `A-Z a-z 0-9 _ -`; names with dots, slashes, drive
   letters or absolute paths are refused. Map-file symlinks are refused during the secure read.
4. **Run** `/csmc surf import <name> [x y z]` with permission level 2 (operator). The optional origin is the block
   where Source `(0,0,0)` lands; default is the command source's block. Parsing and voxelisation run off the server
   thread; blocks are then placed at most 4096 cells per tick, bottom-up, with a *cooperative* 15 ms per-tick
   budget (the clock is checked every 64 writes, so a tick can overrun it by up to 63 cell writes; it is not a hard
   maximum). Cells are written only into chunks that are already loaded: an unloaded chunk is requested
   asynchronously with a chunk ticket (`csmc_surf_import`, expires 300 ticks after its last refresh) and placement
   yields until a later tick, so no write generates or loads a chunk synchronously. Both full-block and ramp writes use
   `UPDATE_CLIENTS | UPDATE_KNOWN_SHAPE` without neighbor/comparator cascades (which could otherwise load adjacent
   chunks). When done the summary lists brushes, solid cells, ramp cells, teleport zones, spawns, every skipped feature
   with its count, and approximation counters.
5. **Then** `/csmc surf spawn <name>` moves you to the map's first `info_player_*` spawn; `/csmc surf forget <name>`
   removes the map's teleport zones and spawns (blocks stay; break or `/fill` them). Set the gamerule
   `csmcMovement surf` for Source-style surfing (`docs/movement.md`).

## Limits

| Limit | Value | Source |
|---|---|---|
| File size | 64 MiB | importer budget; enforced during reading through one `NOFOLLOW_LINKS` channel before constructing the text. A secure directory stream anchors the open where available; otherwise portable Java opens the validated real path. Real path, fileKey (where supported), size and mtime are checked before/after reading; drift rejects the file. |
| Brushes (all entities) | 8192 | `MAX_MAP_BRUSHES`, Source SDK 2013 `public/bspfile.h` |
| Sides per brush | 128 | `MAX_BRUSH_SIDES`, `utils/vbsp/vbsp.h` |
| Entities | 8192 | `MAX_MAP_ENTITIES`, `public/bspfile.h` |
| Coordinates | ±16384 units (plane points ±32768) | `MAX_COORD_INTEGER`, `public/worldsize.h` |
| Placed cells | 4,000,000 | importer budget |
| Cell tests | 64 × cell limit | importer budget (stops pathological skewed brushes) |
| Polytope work | 2 × 10^9 work units per import (C(n,3)·n per vertex enumeration, plus mesh-clipping, cap and volume cost per volume calculation) | importer budget shared by brush validation, every voxelisation cell enumeration/volume and plane trimming; one 128-sided prism converts, 60 of them are refused, and a brush that passes validation can still be refused during voxelisation (unit-tested) |
| Teleport zones per dimension | 4096 zones, 16384 volumes, 262,144 (zone, chunk column) index entries over all imported maps, enforced on import and saved-data load | importer budget; zones are indexed per chunk column so each tick tests only zones whose bounds share a column with the player |
| Text | depth 64, 1,000,000 blocks, 4096 keys per block, 8192 chars per token | importer budget |

The map must fit the world at the chosen origin. Translated coordinates are computed in `long`/`double` before any
narrowing (an origin y of `Integer.MAX_VALUE` is rejected, not wrapped). Cells must lie inside the build height
(overworld rows −64..319 = 384 blocks = 15360 units at the import scale; the message names the valid origin-y range)
and the world border; teleport zone bounds, teleport destinations, landmarks and spawns must lie inside the border
and build height too — also for maps that place no cells. A teleport whose (landmark-relative) target is outside the
current border or build height at teleport time is not performed, and `/csmc surf spawn` refuses a spawn that is no
longer inside the world. Exceeding any limit rejects the whole file with a message; nothing is placed.

**Filesystem threat model:** map files are permitted files managed by the local server administrator. The portable
Java path (including macOS) does not claim protection against a hostile same-UID filesystem writer: another process
could swap a parent directory/file and restore its path, file key, size and mtime between the checks. Java does not
provide a portable descriptor-real-path / `fstat` API. `SecureDirectoryStream` strengthens confinement when the provider
supports it; no native/JNA open code is used. Regardless of races, bytes are read from one handle and the byte cap is
enforced during every read, so a growing file cannot force an unbounded text allocation.

## Coordinate mapping and scale

- Source is z-up with x east and y north (Hammer top view); Minecraft is y-up with x east and z south. The importer
  maps `(x, y, z)_source → (x, z, −y)_minecraft`, a proper rotation, so outward normals stay outward.
- **Scale: 40 Source units per block** (run-wide contract; CS player hull 32×72 units vs Minecraft 0.6×1.8 blocks).
  *Approximation*, not a Valve ratio.
- Plane orientation follows vbsp: normal = `(p0 − p1) × (p2 − p1)` normalised, distance = `normal · p0`
  (`CMapFile::PlaneFromPoints`), solid where `normal · p <= distance`. Duplicate planes are dropped as vbsp does.
- Angles: Minecraft yaw = −90 − Source yaw (Source yaw 0 = east, 90 = north); pitch is the same sign, clamped to ±90.

## Supported subset

**Brush entities that become geometry**

| Class | Result | Source behaviour cited |
|---|---|---|
| `worldspawn` | solid | world brushes |
| `func_detail` | solid | vbsp moves func_detail brushes into the world (`map.cpp`) |
| `func_wall` | solid | `SOLID_BSP` (`server/bmodels.cpp`) |
| `func_brush` | solid unless `Solidity` = 1 (Never), or `StartDisabled` = 1 with `Solidity` ≠ 2 | `CFuncBrush::Spawn/TurnOff`, `BRUSHSOLID_*` (`server/modelentities.*`) |
| `func_illusionary` | skipped (non-solid) | `SOLID_NONE` (`server/bmodels.cpp`) |
| `trigger_teleport` | teleport zone (below) | `CTriggerTeleport::Touch` (`server/triggers.cpp`) |
| anything else with brushes | skipped, counted per class (doors, movelinear, breakables, ladders, water, …) | moving/conditional brushes have no static equivalent |

**Tool textures** (material names compared case-insensitively, `\` = `/`), per VDC "Tool textures (Source)" and vbsp:

| Material | Result |
|---|---|
| `tools/toolsnodraw`, `tools/toolsinvisible` | brush with only these (and clip) sides → invisible solid (`minecraft:barrier`); mixed with visible sides → visible block |
| `tools/toolsclip`, `tools/toolsplayerclip` | invisible solid (`minecraft:barrier`) — player-solid, never drawn |
| `tools/toolsskybox`, `tools/toolsskybox2d` | skipped: the sky shell would enclose the map in barrier/blocks; surf maps keep players in with walls/triggers |
| `tools/toolstrigger` on a non-trigger brush | skipped |
| `tools/toolshint`, `tools/toolsskip` | skipped (vbsp gives hint/skip faces no contents) |
| `tools/toolsorigin` | skipped (vbsp removes origin brushes) |
| `tools/toolsnpcclip`, `toolsblockbullets`, `toolsblock_los`, `toolsblocklight`, `toolsareaportal`, `toolsoccluder`, `toolsfog`, `toolsdotted`, `toolsblack_noportal` | skipped (not player-solid in CS) |
| any other `tools/*` | invisible solid, reported as "unknown tool texture" |
| everything else | drawn: deterministic palette block (below) |

**Materials.** Textures are never imported. A drawn brush uses its most frequent non-tool material (ties: alphabetical);
the block is one of the 16 concrete colours chosen by CRC-32 of the lower-case material name. The same name always
gives the same block, so re-imports are identical.

**Point entities**

- `info_player_counterterrorist`, `info_player_terrorist`, `info_player_start`, `info_player_deathmatch` → stored
  spawns (file order; `/csmc surf spawn` uses the first). Origin = feet (CS hull mins z = 0).
- Any entity with `targetname` and `origin` can be a teleport destination or landmark (Source's
  `FindEntityByName` accepts any class, e.g. `info_teleport_destination`, `info_target`). Name matching is
  case-insensitive; the first entity with a name wins.

**Teleport zones** (`trigger_teleport`): imported when `spawnflags` contains 1 (clients) or 64 (everything), there is
no `filtername`, `StartDisabled` is not 1, and `target` resolves. Every brush of the trigger is one convex volume
(not snapped to the grid). At the end of every server tick each non-spectator player whose bounding box touches a
volume is moved, as `CTriggerTeleport::Touch` does:
- position = destination origin (feet), or destination + (player − landmark) when `landmark` resolves;
- view angles = destination `angles` unless a landmark is used or spawnflag 32 (preserve angles) is set;
- velocity is kept (the SDK passes a null velocity). Implemented as a relative teleport so the client keeps its own
  momentum; fall distance is reset.
Zones and spawns are saved per dimension in `data/csmc_surf_maps.dat` and survive restarts.
Spatial lookup merges only nearby chunk-column candidates, then restores global map-name/file-order precedence.
Cross-column overlaps therefore choose the same first touching zone as a single-column lookup.

## Voxelisation rules

All brushes are converted in map-local block space (Source origin at block 0,0,0) and shifted by the integer origin
afterwards, so cell-local planes do not depend on where the map is placed.

1. **Axis-aligned faces snap outward** to the block grid (within 1e-6 of an integer they stay). Each snapped brush
   contains the canonical input brush, so before sliver removal (rule 4) and overlap resolution (rule 5) the union
   never opens a gap where input brushes touched — no leaks at thin-wall corners —
   and every brush keeps at least one layer. *Approximation*: axis faces move outward by < 1 block (40 units); 8–16
   unit walls become one block thick; rooms shrink by < 1 block per side when their inner faces are off-grid.
2. **Fully covered cells** (all eight corners inside every half-space) → a full block.
3. **Cells crossed by a non-axis face** (sloped or yaw-rotated faces; after rule 1 nothing else can cross a cell) →
   a surf ramp. Brush planes are canonicalised once, in map-local space and before voxelisation
   (`SurfRamps.canonicalPlane`, 2^-24 grid); the cell's planes are those canonical planes translated into the cell,
   and placement moves them to the world with `SurfRamps.translateCanonical`, which keeps the normal bit-identical and
   translates the distance exactly. Neighbouring cells cut by the same face therefore carry bit-identical planes, and
   the placed solid is the one the voxeliser classified, independent of the import origin (unit-tested at x = 0 and
   x = 10,000,000). Planes that do not change the cell's
   solid are dropped. If more than 4 remain (the ramp block's maximum), the 4 whose removal would add the most volume
   are kept — *approximation*: the cell becomes a superset of the true piece (counted as "trimmed").
4. **Slivers**: pieces under 1e-4 block³ (6.4 cubic units) are not placed (counted). A brush whose snapped volume is
   below that is dropped (counted as "thin brush").
5. **Overlaps**: a full cell beats a ramp; between full cells a drawn block beats a barrier, then the earlier brush in
   file order wins; between ramps the larger piece wins (ties: earlier brush). Counted as "overlapping ramp cells".
   *Approximation, can lose geometry*: a cell holds one ramp piece, so where two partial pieces jointly fill a cell
   (e.g. two complementary half-cell wedges) only the larger is kept and the other's solid is missing — the no-gap
   guarantee of rule 1 does not survive this step.
6. **Output order** is bottom-up (y, z, x); the whole conversion is deterministic (unit-tested by converting twice).

Vertex enumeration and plane deduplication use a 1e-7-block tolerance. Volumes instead clip a closed polygon mesh
against the half-spaces with strict signed-distance tests, creating one cap per cut rather than inferring overlapping
facets from tolerant vertex membership. This is double-precision geometry, not exact arithmetic. Regressions cover
near-coplanar planes with identical and unequal vertex memberships, plane-order permutations, and the larger-piece
overlap winner. Every clipping operation shares the conversion work budget, and removing a redundant plane updates
the cached volume to that of the retained solid.

Surfability is decided by the movement code, not here: Source treats a plane as ground only while its normal's up
component is ≥ 0.7 (Source SDK 2013 `game/shared/gamemovement.cpp`, `CategorizePosition`), i.e. slopes steeper than
about 45.57° are surf ramps. The import preserves slopes exactly (up to the 2^-24 plane grid), so a map's surfable
ramps stay surfable.

## Not supported (reported in the summary or ignored)

- **Displacements** (`dispinfo`): the brush is skipped. vbsp removes displacement brushes and replaces their faces
  with the displacement surface (`map.cpp`, "remove them from the world"); rebuilding the subdivided surface is out of
  scope. Surf maps rarely use displacements for ramps.
- **Entity I/O** (`connections`): outputs/inputs are not executed, so triggers that start disabled, doors, buttons,
  `point_teleport`, `logic_*`, timers and stage logic do not work. `trigger_teleport` with `StartDisabled` is skipped.
- **Filters** (`filtername`, e.g. `filter_activator_team`): such teleports are skipped rather than applied to everyone.
- **Other triggers** (`trigger_push`, `trigger_multiple`, `trigger_hurt`, gravity/speed modifiers) and every other
  brush entity class: skipped and counted by class.
- **Name wildcards** (`dest*`) and `!activator`-style targets: matched literally (normally unresolved → skipped).
- **Hidden objects** (`hidden { }` blocks, objects hidden in Hammer): skipped, as vbsp registers no handler for them.
- **Instances** (`func_instance`): not expanded; BSPSource output already contains instance contents.
- **Textures, models (`prop_static`, `prop_dynamic`), overlays, decals, lights, sounds, skybox (`skyname`), water**:
  never imported.
- **Direct `.bsp` files**: not read (see decision). Decompile them with BSPSource first.
- Removing an imported map's blocks: `/csmc surf forget` only removes zones/spawns.

## Verification

Review-fix verification (2026-10-19): `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew test --console=plain` succeeded: 198 JUnit tests (34 suites, 0 failures/errors/skipped), and
`All 170 required tests passed :)` from the full Fabric GameTest suite.

- Unit tests (`./gradlew test --tests 'dev.csmc.surf.importer.*'`): parser, file-name/symlink guards, bounded
  confined read, plane orientation, classifier, voxeliser, near-coplanar facets, shared work budget, origin
  invariance, chunk-indexed zones and aggregate zone budgets, teleport/spawn extraction, limits and determinism over
  the synthetic fixtures.
- GameTest `SurfImportGameTests`: `/csmc surf import` of a generated map places palette concrete under a 2:1 slope,
  a barrier for a nodraw brush, `csmc:surf_ramp` cells whose world plane equals the canonical slope plane in every
  cell (seam-free) with one local plane, an empty cell above the slope; a mock player entering the trigger is moved
  to the destination with the converted yaw; `/csmc surf spawn` uses the CT spawn; the saved record round-trips.
  Path traversal names and permission level 0 are refused. A second test checks the fit rules (origin y
  `Integer.MAX_VALUE`/`MIN_VALUE`, a spawn-only map beyond the border, destinations beyond the border), that a
  teleport to a target outside the border is refused at teleport time, and that preparing an unloaded chunk requests
  it without loading it synchronously.
- Runtime (dedicated server, console): `csmc surf import yaw_ramp 0 -50 100` on the synthetic yaw-rotated fixture
  logged `placing yaw_ramp: 51 cells (0 full, 51 ramp)` and `changed=51/51`; a second import logged `changed=0/51`
  (all cells already present), and `execute if block` found `csmc:surf_ramp` at three cells predicted independently
  from the fixture's planes, and none at a control cell.

## Approximations (summary)

| Approximation | Authoritative behaviour it approximates |
|---|---|
| 40 units per block | Source units; run-wide scale contract |
| Axis faces snapped outward (< 1 block) | exact brush faces in vbsp |
| ≤ 4 planes per ramp cell; trimmed cells are supersets | exact convex brushes in the BSP collision model |
| Sliver pieces < 1e-4 block³ dropped | exact brushes |
| Trigger touch = brush planes + bounding box (no edge bevels) | engine box-vs-brush test with vbsp bevel planes (`AddBrushBevels`) |
| Palette blocks instead of textures | material appearance (not imported on purpose) |
| Sloped clip/nodraw brushes become visible surf ramps | invisible sloped clip (there is no invisible ramp block) |
| Fall distance reset on teleport | Source fall damage is velocity-based at landing |

## Sources

- Valve Developer Community: "VMF (Valve Map Format)" <https://developer.valvesoftware.com/wiki/VMF_(Valve_Map_Format)>,
  "Displacement" <https://developer.valvesoftware.com/wiki/Displacement>, "Tool textures (Source)"
  <https://developer.valvesoftware.com/wiki/Tool_textures_(Source)>, "BSP (Source)"
  <https://developer.valvesoftware.com/wiki/BSP_(Source)> and its talk/game-specific pages, "trigger_teleport"
  <https://developer.valvesoftware.com/wiki/Trigger_teleport>, "func_brush" <https://developer.valvesoftware.com/wiki/Func_brush>.
- Source SDK 2013 (<https://github.com/ValveSoftware/source-sdk-2013>, master): `src/utils/vbsp/map.cpp`
  (VMF loader, `PlaneFromPoints`, side contents, func_detail merge, displacement removal), `src/utils/vbsp/vbsp.h`,
  `src/public/bspfile.h` (limits, lumps, `dbrush_t`/`dbrushside_t`/`dplane_t`), `src/public/bspflags.h`,
  `src/public/worldsize.h`, `src/game/server/triggers.{h,cpp}` (`CTriggerTeleport::Touch`, spawnflags),
  `src/game/server/modelentities.{h,cpp}` (`CFuncBrush`), `src/game/server/bmodels.cpp`,
  `src/game/shared/gamemovement.cpp` (0.7 ground normal).
- BSPSource: <https://github.com/ata4/bspsrc> (README, `LICENSE.md` — Unlicense).
- No leaked CS:GO source was used.
