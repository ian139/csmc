# Surf ramps

Surf ramps are sloped blocks with exact planar collision for the CS movement modes (`/gamerule csmcMovement surf`,
see `movement.md`). Source code: `mod/src/main/java/dev/csmc/surf/` (server/common) and
`mod/src/client/java/dev/csmc/client/surf/` (rendering).

## Blocks and items

| Id | What |
|---|---|
| `csmc:surf_ramp` (block, block entity, item) | One block cell holding a convex solid: the unit cell `[0,1]^3` cut by 1..4 planes stored in the block entity. Stone-like (strength 1.5/6, stone sounds), non-occluding, not suffocating, not a redstone conductor, cannot be pushed by pistons. |

- Creative tab: *Building Blocks*.
- Recipe (`data/csmc/recipe/surf_ramp.json`, shaped, 6 ramps):
  ```
    Q      Q = quartz
   QS      S = smooth stone
  QSS
  ```
- Loot table (`data/csmc/loot_table/blocks/surf_ramp.json`): drops one ramp item (survives-explosion condition).
- Language key: `block.csmc.surf_ramp` = "Surf Ramp".

## Commands

Permission level 2. Coordinates accept `~`/`^`. `<to>`/`<from>` are opposite corners of the box (inclusive);
the box may hold at most 32768 blocks (same limit as vanilla `/fill`); the cell count is computed overflow-safely
(`Math.multiplyExact`), so even a box spanning a whole tall custom dimension is rejected rather than wrapping.

Every chunk the box touches must already be loaded (full status). The builder runs synchronously inside the
command, so instead of loading or generating chunks mid-command (which could stall the server for an ungenerated
corridor between two loaded corners) it rejects such selections before changing any block, with
`commands.csmc.surf.unloaded`. Move closer or `/forceload` the area first. (Choice: rejection rather than batched
asynchronous chunk preparation; it keeps the command atomic and bounded.)

Bulk builder writes (ramps, ramp geometry changes, and filler blocks) sync clients using
`UPDATE_CLIENTS | UPDATE_KNOWN_SHAPE`, without neighbor, comparator, or shape-update cascades. Thus a selection at
a chunk boundary does not synchronously load unchecked adjacent chunks. As with import placement, nearby redstone,
attachments, and other shape-dependent blocks are not automatically refreshed by the bulk operation; hand placement
keeps vanilla neighbor-update semantics.

```
/csmc surf ramp  <from> <to> <north|south|east|west> [angle] [filler]
/csmc surf ridge <from> <to> <x|z>                   [angle] [filler]
```

- `ramp`: one straight plane that rises towards the given direction, starting at the bottom of the box on the
  opposite edge.
- `ridge`: two mirrored planes rising from both long sides of the box; the crest runs along the given axis (`x` = crest
  runs east–west, slopes face north and south; `z` = crest runs north–south). Where the slopes would rise above the
  box they are clipped by the box top (flat crest).
- `angle`: slope in degrees from horizontal, 5–85, default `atan(2)` = 63.43° (rise:run 2:1). Surfable slopes must
  be steeper than Source's walkable limit (ground normal `z >= 0.7`, about 45.57°); anything flatter is walked on.
- `filler`: any block state for cells entirely below the surface (default `minecraft:smooth_stone`). Cells entirely
  above the surface are left untouched; cells the surface cuts become ramp cells.
- Feedback: `Built <n> surf ramp cells and <m> filler blocks` (unchanged cells are not counted).

Examples:

```
/csmc surf ramp 4 -60 0 7 -53 5 east            # 4 wide (x), 8 high, 6 deep (z), rising east at 63.4°
/csmc surf ridge 4 -60 10 10 -54 16 x 60        # 7x7 ridge, crest along x, 60° slopes
/csmc surf ramp ~ ~ ~ ~10 ~20 ~30 north 70 minecraft:stone
```

## Hand placement

When a ramp item is placed, the server chooses the planes of the new cell:

1. **Continue an adjacent ramp**: the clicked neighbour is tried first, then the other neighbours in `Direction`
   order (down, up, north, south, west, east). The first neighbouring ramp whose *world* planes cut the new cell
   (non-empty and not the full cell) is continued, so placing against a ramp extends the same plane seamlessly.
2. **Otherwise the default slope**: rise:run 2:1 rising along the player's horizontal facing, starting at the cell's
   bottom edge nearest the player.

The geometry is chosen *before* the block is placed. Because vanilla's obstruction check sees the still-empty cell,
the item additionally checks the chosen geometry's vanilla collision (the inscribed staircase) against entities
that block building (mobs, players with CS movement off, ...); placement fails if it would overlap one. Entities using
analytic collision are skipped, matching movement's omission of their staircase boxes. The checked geometry is the one installed.

Use the builder commands for other angles, long ramps and ridges.

## API (shared with the map importer)

`dev.csmc.surf.SurfRamps`:

- `boolean place(ServerLevel level, BlockPos pos, List<BrushPlane> localPlanes)` — places or updates a ramp cell from
  block-local planes (cell `[0,1]^3`, solid = cell ∩ half-spaces `n·p <= d`). Planes are canonicalised and
  deduplicated. Returns `false` with the world unchanged when there are 0 or more than 4 planes, the solid has volume
  below `1e-6` blocks³, or the cell already holds exactly these planes.
- `boolean placeWorld(ServerLevel level, BlockPos pos, List<BrushPlane> worldPlanes)` — the same for world-space
  planes; recommended for builders/importers because every cell cut from one world plane gets bit-identical world
  coefficients back.
- `boolean placeWorld(ServerLevel level, BlockPos pos, List<BrushPlane> worldPlanes, int updateFlags)` — the same
  with explicit update flags for placement and geometry updates. Importers preparing only the target chunk use
  `Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE` so clients still receive the block/entity data, but no neighbor
  shape/comparator update cascade synchronously loads adjacent chunks. The target chunk must already be loaded.
- `BrushPlane canonicalPlane(BrushPlane plane)` — snaps to the grid below; idempotent (bit-identical on canonical
  input).
- `BrushPlane translateCanonical(BrushPlane plane, int dx, int dy, int dz)` — canonicalises, then moves by an integer
  block offset keeping the normal bit-identical and computing the distance exactly. Importers canonicalise in
  map-local space *before* deciding which cells are cut, then translate to the import origin with this helper, so
  the placed geometry does not depend on the origin.
- `List<BrushPlane> localPlanes(BlockGetter level, BlockPos pos)` — the stored local planes (empty when the block is
  not a ramp). `worldPlanes` returns them translated to world coordinates.

Planes are snapped to a 2^-24 grid (normal components and distance), so translating a canonical world plane by an
integer block position is exact in double arithmetic for positions within 2^25 blocks of the origin.

## Collision model

- **Analytic (CS movement active for the entity):** the shared `BlockCollisions` movement iterator omits vanilla
  shapes of analytic blocks when `AnalyticCollision.appliesTo(entity)` is true, on client and server (including
  server move replay). `SurfRampBlock` itself always exposes its solid staircase to shape/clip queries.
  The `MovementGeometry` provider `SurfRampGeometryProvider` instead emits one `ConvexBrush` per ramp cell:
  the cell's world planes plus the six cell faces. The CS hull trace (`ConvexBrushWorld`) collides against exact
  planes; adjacent cells cut from one world plane share bit-identical coefficients, so there are no seams or
  steps between cells on a continuous ramp. Pose-fit checks test those same analytic solids on both sides;
  releasing Sneak beneath a low ramp keeps the authoritative player crouched with crouching dimensions.
- **Vanilla (everything else: CS movement off, mobs, items, projectiles):** an *inscribed* staircase — a union of
  1/8-block columns, each box entirely inside the solid with its top rounded down to 1/64 block. It never pokes above
  the true surface, so a vanilla entity can never stand above where a CS player would surf.
- **Combat / visibility:** bullets (including real shooters), knife obstruction, flash LOS, HE/C4 exposure and
  defuse visibility all see the entity-independent inscribed staircase, with CS movement on or off. These are
  approximations, not exact analytic traces: 1/8-block columns with tops rounded down to 1/64 block stay inside
  the visible solid. A grazing bullet/visibility ray can pass through the gap between the staircase and visual slope.
- **Outline / selection / ray picking:** the covering staircase (1/8 columns, rounded outwards), so the whole visible
  solid can be targeted.
- The block entity syncs its planes to clients (update packet and chunk data) and persists them in NBT. Loaded or
  synced plane lists outside 1..4 entries are rejected before any entry is decoded (fallback: default slope), and
  `validate` stops at the fifth distinct canonical plane, so oversized tags cost no extra work.

## Rendering

`SurfRampModel` (Fabric Renderer API, Indigo) emits the exact boundary polygons of the solid, each split into a fan of
planar quads:

- Texture: vanilla `block/smooth_stone`. UVs are UV-locked (`BAKE_LOCK_UV`) onto the polygon's nominal face — the
  axis plane nearest the polygon normal — so every cell gets one 0..16 tile on its projected footprint and sloped
  faces tile continuously across cells.
- Shading: cell faces use the default material (vanilla smooth lighting / ambient occlusion). Sloped polygons use
  flat lighting (no AO) with normal-based diffuse shading; smooth lighting samples the cube corners of each cell and
  would produce per-cell light bands across one continuous plane.
- Culling: only polygons covering a whole cell face cull against neighbours.
- The item renders the default 2:1 wedge with vanilla `block/block` display transforms.

Dev screenshot hook (inert unless set): `-Dcsmc.devSurfCommands=cmd|cmd` and
`-Dcsmc.devSurfShots=name@x,y,z,yaw,pitch|…` on the client; log prefix `[csmc surf dev]`.

## Approximations

- Slope texture is projected onto the nearest axis plane, not along the slope: the tile is stretched along the fall
  line by `1/cos(angle between normal and that axis)` — at most 1.41× for builder/hand ramps (normal in one vertical
  plane), at most 1.73× for arbitrary imported planes.
- Outline/selection uses a covering staircase rather than the exact polygon.
- Default angle 63.43° (2:1) is chosen for buildability on the block grid, not taken from a specific CS map.

## Limits

- 1..4 planes per cell; more complex solids must be split across cells (or rejected by `place`).
- Builder box ≤ 32768 blocks; angles 5–85°.
- Only one ramp shape per cell; a cell cannot hold two disjoint solids.
- Exactness of seam-free planes holds within 2^25 blocks of the origin (well beyond the world border).
