# CS:GO-style movement reference

This documents `dev.csmc.movement.physics`: `CsMovementParams`, `CsGameMovement`, `ConvexBrushWorld`, and `CsUnits`. It is a Source SDK movement port with CS:GO-style settings and explicitly identified Minecraft/community adaptations, **not an exact reconstruction of CS:GO's unpublished movement implementation**. Positions and hull dimensions use Source units; velocities use units/second; `dt` is seconds. Minecraft axes are retained: x east, y up, z south. The origin is the hull's bottom centre. Yaw 0 faces +z; yaw 90 faces -x; positive side movement is right.

## Presets and cvar provenance

All 17 `CsMovementParams` fields are enumerated below. Booleans correspond to cvar 0/1. Unless a community override is identified, BHOP/SURF deliberately retain the competitive value; this is a project composition, not evidence that every community server uses those defaults.

**Exact CS:GO cvar values:** the primary numerical observation is saul's [in-game `cvarlist` capture][cvar-dump] for CS:GO executable **1.37.7.5**, built 2020-12-03, preserved at commit `241c2d569d66c34ef333c8e3decf10eed8c9cbb1`. It is a community-preserved observation of a Valve executable, not published Valve engine source. The [VDC CS:GO cvar list][vdc-cvars] ([2023 archive][vdc-cvars-archive]) documents the final `csgo_legacy` build, but its rendering rounds some values (for example 5, 5, 301 for acceleration, friction, impulse) and lists zero jump/land stamina costs; it is therefore **not used to invent precision or override the exact capture**. The historical [SteamTracking CS:GO cvar-name inventory, lines 28606–28950][tracking-cvars] independently records these cvar names, **not their values**. The repository now has the name `GameTracking-CS2`; this pinned commit still contains the CS:GO `csgo/` tree. Neither a modern CS2 dump nor its changed defaults are substituted here.

| Field / CS:GO setting | COMPETITIVE | BHOP | SURF | Value source and overrides |
| --- | ---: | ---: | ---: | --- |
| `gravity` / `sv_gravity` | 800 | 800 | 800 | [CS:GO capture, line 4313](https://github.com/saul/cvar-unhide/blob/241c2d569d66c34ef333c8e3decf10eed8c9cbb1/cvarlist-csgo.md#L4313) |
| `accelerate` / `sv_accelerate` | 5.5 | 5.5 | 10 | [Capture, line 4130](https://github.com/saul/cvar-unhide/blob/241c2d569d66c34ef333c8e3decf10eed8c9cbb1/cvarlist-csgo.md#L4130); SURF: [community skill-surf guide][surf-config] |
| `airAccelerate` / `sv_airaccelerate` | 12 | 1000 | 150 | [Capture, line 4137](https://github.com/saul/cvar-unhide/blob/241c2d569d66c34ef333c8e3decf10eed8c9cbb1/cvarlist-csgo.md#L4137); BHOP: [shavit Normal, line 36][bhop-config]; SURF: [community guide][surf-config] |
| `airMaxWishSpeed` / `sv_air_max_wishspeed` | 30 | 30 | 30 | [Capture, line 4135](https://github.com/saul/cvar-unhide/blob/241c2d569d66c34ef333c8e3decf10eed8c9cbb1/cvarlist-csgo.md#L4135); SDK [AirAccelerate][air-accelerate] also hardcodes 30 |
| `friction` / `sv_friction` | 5.2 | 5.2 | 5.2 | [Capture, line 4307](https://github.com/saul/cvar-unhide/blob/241c2d569d66c34ef333c8e3decf10eed8c9cbb1/cvarlist-csgo.md#L4307) |
| `stopSpeed` / `sv_stopspeed` | 80 | 80 | 80 | [Capture, line 4558](https://github.com/saul/cvar-unhide/blob/241c2d569d66c34ef333c8e3decf10eed8c9cbb1/cvarlist-csgo.md#L4558) |
| `maxVelocity` / `sv_maxvelocity` | 3500 | 3500 | 3500 | [Capture, line 4409](https://github.com/saul/cvar-unhide/blob/241c2d569d66c34ef333c8e3decf10eed8c9cbb1/cvarlist-csgo.md#L4409); also [community surf guide][surf-config] |
| `jumpImpulse` / `sv_jump_impulse` | 301.993377 | 301.993377 | 301.993377 | [Capture, line 4350](https://github.com/saul/cvar-unhide/blob/241c2d569d66c34ef333c8e3decf10eed8c9cbb1/cvarlist-csgo.md#L4350) |
| `enableBunnyhopping` / `sv_enablebunnyhopping` | false (0) | true (1) | true (1) | [Capture, line 4271](https://github.com/saul/cvar-unhide/blob/241c2d569d66c34ef333c8e3decf10eed8c9cbb1/cvarlist-csgo.md#L4271); [shavit line 27][bhop-config]; [surf guide][surf-config] |
| `autoBunnyhopping` / `sv_autobunnyhopping` | false (0) | true (1) | true (1) | [Capture, line 4153](https://github.com/saul/cvar-unhide/blob/241c2d569d66c34ef333c8e3decf10eed8c9cbb1/cvarlist-csgo.md#L4153); [shavit `autobhop`, line 22][bhop-config]; [surf guide, optional setting][surf-config] |
| `staminaMax` / `sv_staminamax` | 80 | 0 | 0 | [Capture, line 4549](https://github.com/saul/cvar-unhide/blob/241c2d569d66c34ef333c8e3decf10eed8c9cbb1/cvarlist-csgo.md#L4549); zeros: [surf guide][surf-config], adopted by the project for both community presets |
| `staminaJumpCost` / `sv_staminajumpcost` | 0.08 | 0 | 0 | [Capture, line 4547](https://github.com/saul/cvar-unhide/blob/241c2d569d66c34ef333c8e3decf10eed8c9cbb1/cvarlist-csgo.md#L4547); zero costs: [surf guide][surf-config] |
| `staminaLandCost` / `sv_staminalandcost` | 0.05 | 0 | 0 | [Capture, line 4548](https://github.com/saul/cvar-unhide/blob/241c2d569d66c34ef333c8e3decf10eed8c9cbb1/cvarlist-csgo.md#L4548); zero costs: [surf guide][surf-config] |
| `staminaRecoveryRate` / `sv_staminarecoveryrate` | 60 | 60 | 60 | [Capture, line 4550](https://github.com/saul/cvar-unhide/blob/241c2d569d66c34ef333c8e3decf10eed8c9cbb1/cvarlist-csgo.md#L4550); deliberately retained and inert when max/costs are zero, unlike the surf guide's recovery 0 |
| `duckSpeedModifier` (stance multiplier, not a cvar) | 0.34 | 0.34 | 0.34 | **Community-documented approximation (no official Valve primary source)**; [public movement behaviour][stance-behaviour], with exact-ratio limitation below |
| `walkSpeedModifier` (stance multiplier, not a cvar) | 0.52 | 0.52 | 0.52 | **Community-documented approximation (no official Valve primary source)**; [public movement behaviour][stance-behaviour], with exact-ratio limitation below |
| `tickRate` (nominal, not a simulated exact rate) | 64 | 128 | 85 | Project selections: [community CS:GO matchmaking/community 64/128 description][bhop-analysis]; SURF stores integer 85 inspired by the [85.3-tick community convention][surf-ticks]. Actual rates are 60/120/80 Hz; see Approximations |

**Stance-source limitation:** duck ×0.34 and walk ×0.52 are **community-documented approximations (no official Valve primary source)**. The [Counter-Strike Wiki movement page][stance-behaviour] documents CS:GO's walking slowdown, but does not establish either exact ratio. No public documentation stating both exact numbers was found during this work. The project retains these explicitly approximate multipliers rather than claiming an official numerical citation; duck takes precedence over walk. The public SDK algorithm reference does not establish these CS:GO-specific values.

**Community convention, not Valve defaults:** [shavit bhoptimer's Normal style][bhop-config] supplies air acceleration 1000 and enabled auto/bunnyhopping. Its `easybhop` option is not the same mechanism as setting all stamina costs/max to zero here, and its config does not establish a 128-tick default. [Mezz's skill-surf guide][surf-config] supplies acceleration 10, air acceleration 150, bunnyhopping and zero stamina; auto-bunnyhopping is optional there. It recommends 64 tick, while [Robby's separate surf guide][surf-ticks] describes 85.3 tick. This project's SURF combines those conventions; it is **not** a verified SurfTimer global config. Both presets retain weapon/attribute speed caps; they do not copy the guide's optional `sv_accelerate_use_weapon_speed 0` or shavit's optional 260 u/s static prestrafe feature.

## Algorithm sources

The algorithm reference is Valve's Source SDK 2013 multiplayer `gamemovement.cpp` at immutable revision `0d8dceea4310fde5706b3ce1c70609d72a38efdf` (2015-09-10), **not CS:GO source code**. Function names and line ranges in Java comments refer to this revision:

| Operation | Primary-source permalink |
| --- | --- |
| Half gravity before movement | [StartGravity, lines 1248–1267][start-gravity] |
| Compare ordinary slide with raised stair route | [StepMove, lines 1517–1607][step-move] |
| Ground friction | [Friction, lines 1612–1679][friction] |
| Half gravity after movement | [FinishGravity, lines 1684–1700][finish-gravity] |
| Air acceleration and movement | [AirAccelerate, lines 1707–1748][air-accelerate]; [AirMove, lines 1753–1799][air-move] |
| Ground acceleration | [Accelerate, lines 1822–1854][accelerate] |
| Down-step/slope adhesion | [StayOnGround, lines 1859–1890][stay-ground] |
| Walking | [WalkMove, lines 1895–2020][walk-move] |
| Substep ordering | [FullWalkMove, lines 2025–2141][full-walk] |
| Jump input/impulse ordering | [CheckJumpButton, lines 2354–2530][jump] |
| Iterative sweep/slide | [TryPlayerMove, lines 2560–2814][try-move] |
| NaN handling and per-axis speed bounds | [CheckVelocity, lines 3049–3087][check-velocity] |
| Plane velocity clipping | [ClipVelocity, lines 3145–3180][clip-velocity] |
| Four-quadrant ground retry | [TryTouchGroundInQuadrants, lines 3711–3768][quadrants] |
| Ground/slope classification | [CategorizePosition, lines 3774–3893][categorize] |

### Fixed movement constants

These are separate from the preset cvars. Sources below describe provenance, not a claim that all Source games have identical movement. For project-selected constants, the primary implementation is [`CsGameMovement.java`](../../mod/src/main/java/dev/csmc/movement/physics/CsGameMovement.java) or [`CsUnits.java`](../../mod/src/main/java/dev/csmc/movement/physics/CsUnits.java), rather than an invented external engine setting.

| Java constant / fixed rule | Value | Source / status |
| --- | --- | --- |
| `NON_JUMP_VELOCITY` | 140 u/s upwards | [CategorizePosition, lines 3808–3814][non-jump] |
| `WALKABLE_NORMAL` | 0.7 minimum upward normal component | [CategorizePosition, lines 3844–3857][ground-normal]; steeper positive-upward planes can be surf contacts |
| `GROUND_PROBE` / `StayOnGround` upward probe | 2 units | [CategorizePosition, lines 3798–3802][probe]; [StayOnGround][stay-ground] |
| `MAX_BUMPS` | 4 | [TryPlayerMove, lines 2560–2580][bump-count] |
| `MAX_CLIP_PLANES` | 5 | [gamemovement.cpp, line 26][clip-planes] |
| `COORD_RESOLUTION` | 1/32 unit | [coordsize.h, lines 17–19][coord]; `StayOnGround` snaps only when the height difference exceeds half this value |
| `RISING_AIR_FRICTION` | 0.25 only while airborne with `0 < vy <= 140` (failed ground probe); 1 when rising faster than `NON_JUMP_VELOCITY` or not rising | [CategorizePosition, lines 3853–3857][ground-normal] |
| Friction negligible-speed threshold | 0.1 u/s | [Friction][friction] |
| Walk negligible-speed threshold | 1 u/s | [WalkMove][walk-move] |
| Collision overbounce | 1 (fixed `sv_bounce = 0` behaviour) | [TryPlayerMove][try-move]; [ClipVelocity][clip-velocity]; no bounce cvar is exposed here |
| `BUNNYHOP_BASE_SPEED` | 260 u/s | **Community extrapolation**, not SDK/Valve-confirmed CS:GO code: [zer0k's CS2 bunnyhop analysis, lines 53–57][bhop-analysis] |
| `BUNNYJUMP_MAX_SPEED_FACTOR` | 1.1 (cap 286 u/s) | Same [community analysis][bhop-analysis]. The whole-vector scaling structure is [TF2 PreventBunnyJumping, lines 1086–1112][tf2-cap], whose own factor is **1.2**, not 1.1 |
| `STAMINA_RANGE` | 100 | **Project-selected percentage representation**, informed by [ReGameDLL_CS's community CS 1.6 ground penalty][regame]; not a CS:GO implementation constant |
| `STAMINA_REFERENCE_RATE` | 64 moves/s | **Project selection** matching the nominal competitive rate; time-normalisation structure is inspired by [ReGameDLL_CS][regame], whose ratio, stamina units and default reference setting differ |
| `UNITS_PER_BLOCK` | 40 | **Project scale** in [`CsUnits`](../../mod/src/main/java/dev/csmc/movement/physics/CsUnits.java), chosen so the [VDC's 72-unit standing height][dimensions] maps to Minecraft's 1.8 blocks; not a Valve cvar |
| `TICKS_PER_SECOND` | 20 | [Mojang's Java 1.20.3 tick-command notes][minecraft-clock] document the default 20 Hz player clock; not a Source tick rate |

### Order of each substep

`CsGameMovement.step(state, input, params, world, dt)` executes the following, in order:

1. Save previous grounded state and pre-step fall speed; clear `jumpedThisStep`, `landedThisStep`, and `surfing`. Recover stamina by `staminaRecoveryRate * dt`, bounded below by zero and above by `staminaMax`.
2. Select effective surface friction. Ground uses the supplied surface value. Air uses 0.25 only while rising at `0 < vy <= 140` u/s (`CategorizePosition` skips the ground probe, and so never sets 0.25, when rising faster than `NON_JUMP_VELOCITY`) and 1 otherwise; the categorisation velocity is reconstructed the velocity before the preceding `FinishGravity` as `velocity.y + gravity * dt / 2`.
3. Replace NaN velocity components by zero, clamp each component to `±maxVelocity`, then apply half gravity (`gravity * dt / 2`).
4. Process jump **before ground friction**. Require ground and either a fresh press or auto-bunnyhopping. If bunnyhopping is disabled, uniformly scale the entire velocity vector down to the 286 u/s cap when necessary. Clear ground; add the stamina-scaled impulse (replace vertical velocity when ducked); charge jump stamina; apply `FinishGravity` as the SDK jump routine itself does. Record the jump flag.
5. If still grounded, zero vertical velocity and apply friction: `control = max(speed, stopSpeed)`, `drop = control * friction * surfaceFriction * dt`, then scale to `max(0, speed - drop)`. Clamp velocity again. A successful jump therefore skips this ground-friction pass.
6. Rotate forward/side commands by yaw to form horizontal wish velocity. Cap its length to the weapon/attribute run cap, multiplied by the duck modifier if ducked, otherwise the walk modifier if walking; modifiers do **not** stack.
   - **Ground:** accelerate along wish direction by at most `accelerate * dt * wishSpeed * surfaceFriction`, limited by the missing speed component along that direction. Apply the project stamina ground multiplier. Trace a horizontal move; on obstruction compare normal sliding against the raised stair route, keeping the greater horizontal distance. `StayOnGround` probes up 2 units then down `stepHeight`, snapping only to a walkable plane.
   - **Air:** limit the desired speed component along wish direction to `airMaxWishSpeed`, but deliberately use the **uncapped-by-airMaxWishSpeed** `wishSpeed` in `airAccelerate * wishSpeed * dt * surfaceFriction`. This is the public SDK's air-acceleration behaviour, not the alternative capped-rate fix. Sweep/slide with `TryPlayerMove`.
7. Categorize ground without moving the origin: if upward velocity exceeds 140 u/s, clear ground. Otherwise sweep 2 units down with the full hull, then up to four quarter hulls if needed. A normal with y ≥ 0.7 sets ground and zeroes vertical velocity. Contacts with `0 < normal.y < 0.7` during movement/probing can mark surfing but do not set ground. `groundNormalY` is meaningful only while grounded.
8. Clamp velocity, apply the final half gravity, then zero vertical velocity on ground.
9. On a new landing without a jump in the same substep, set `landedThisStep` and charge landing stamina from pre-step fall speed. Save the current jump button as `jumpHeldLastStep`.

`TryPlayerMove` consumes remaining time over at most four bumps and stores at most five clip planes. An all-solid trace stops movement. Successful movement resets the active plane list. The first airborne collision removes the into-plane velocity component with overbounce 1. Later collisions try a velocity compatible with every active plane; if necessary, exactly two planes yield a crease direction. Opposing the original velocity, too many planes, or no movement across all bumps stops the player. Surfing is the consequence of this clipping on steep planes, not a separate artificial propulsion force.

`categorizePosition` performs only the ground/surf classification and grounded vertical-velocity reset; adapters use it after mode entry, teleportation, or external velocity changes.

## Collision model

`ConvexBrushWorld` accepts bounded convex half-space intersections in **block coordinates** and answers bottom-centred, axis-aligned hull traces in **Source units**. A brush is solid where every outward unit-normal plane satisfies `n·p ≤ d`. Its supplied bounds locate the local solve origin; collision bounds are recomputed from the actual vertices.

### Minkowski sweep and bevels

At `reset`, every input plane is rescaled to an exact unit normal (`BrushPlane` accepts lengths within 1e-6 of 1, and the 2^-24 grid canonicalisation used by surf ramps produces such lengths; without rescaling an over-length normal can read as a duplicate of an axis and suppress that axial bevel). Plane triples are then intersected in coordinates relative to the brush bounds' centre. Points outside any half-space or duplicate vertices are rejected; empty/flat intersections are omitted. Translating the solve near zero avoids cancellation for geometry far from the world origin. Each retained plane is tightened to the vertices' support value. The plane set is completed with six axial bound planes and supporting `edge × axis` planes, suppressing duplicate normal directions.

This is the same **kind** of axial/edge-bevel construction Valve's compiler uses: [Source SDK `CMapFile::AddBrushBevels`, lines 448–599][bevels]. It is a local double-precision implementation, not a call to Source's BSP compiler. Merely offsetting the original brush faces would overestimate the Minkowski sum at edges, causing ghost collisions on rotated ramps or thin brushes. Completing the face directions gives the convex-polytope/axis-aligned-box Minkowski sum (subject to the numerical tolerances below).

For hull half-width `w` and height `h`, the origin traces a segment against planes expanded to `d + w|nx| + w|nz| + h*max(0,-ny)`. This is the brush plus the reflected bottom-centred hull; Minkowski addition distributes over the union of brushes. A padded swept AABB rejects irrelevant brushes before clipping. Enter/leave fractions use the unmodified expanded planes. `CONTACT_TOLERANCE` classifies flush starts as outside (entry fraction zero) and groups crossings within 1e-6 units along the move. The globally earliest true entry fixes the contact point. At that point, a **local union-boundary solve** removes hidden facets before a normal is selected: active planes partition each candidate facet's tangent circle into angular sectors; a facet is exposed only if a sector lies in its relative interior and its outward side is not covered by any other expanded brush. Coplanar same-facing boundary facets remain exposed; opposite-facing internal facets and bevels covered collectively by several neighbors do not. Only genuine exposed facets at a real corner are compared by opposition to the move, with a lexicographic normal/distance tie-break independent of brush order. `DIST_EPSILON` is applied only after this selection.

**No-tunnel fallback.** The sector solve treats every plane within `CONTACT_TOLERANCE` of the contact as active, so near-active constraints (strictly inside by less than the tolerance, e.g. a box corner clipped 2.7e-6 units deep) can make every coincident facet look hidden. Whenever the clip found a true entry but the exposure filter keeps no facet, the trace reports the entering plane of the earliest brush (the plane with the largest crossing fraction, Quake's choice; equal entries choose by opposition, then normal order). A sweep that genuinely enters a solid therefore never reports clear. Regressions cover the clipped-corner trace (fraction ≈ 0.019254, normal `(1,1,1)/√3`), the COMPETITIVE idle substep at `(-3060,-3053.33,-3060)` u/s that previously traversed it, and a seeded invariant over random clipped brushes and cell unions: a sweep starting clear whose midpoint or endpoint hull lies inside a solid always hits before that point.

**Embedded escapes.** The fallback restores only genuine union entries. A sweep that starts inside one cell (`startSolid`) and crosses into a neighbouring cell enters that cell's expanded planes at a point robustly inside the first cell's expanded region (more than `CONTACT_TOLERANCE`); such an entry is discarded and the next entry is resolved, so the split solid escapes like the unsplit one (fraction 1, `startSolid` true, no hidden shared-face normal). Discarding requires the two brushes to touch in real space (a vertex of one within `CONTACT_TOLERANCE` of the other): a separate solid across a gap narrower than the hull is still hit from an embedded start. **Project rule, approximation:** touching is detected by shared/contained vertices only, so two brushes that overlap without either containing a vertex of the other count as separate (the conservative side, a hit rather than a pass-through), and an embedded hull touching an exposed part of a neighbouring cell's face (a cell taller than the one it starts in) is treated as hidden. Regressions: the reviewed floor escape `(-20,-1,0) → (80,-1,0)` in both brush orders, an escape followed by a wall hit, the narrow-gap wall, embedded starts in the rotated cell-union sweep set, and a seeded invariant comparing split and unsplit obliquely clipped solids beside a wall (including embedded starts, both orders).

The lineage is [Quake II `CM_ClipBoxToBrush`, lines 989–1096][quake-clip]. **Deliberate differences:** true crossing fractions establish intersection validity and global contact ordering; the tangent-sector solve resolves the exposed union boundary rather than assuming opposition identifies it. Epsilon is applied only afterward to the selected entry, `max(0, (d1 - DIST_EPSILON) / (d1 - d2))`. Thus a later internal face cannot win through a larger pull-back, and epsilon cannot create an edge hit merely by enlarging the crossing interval.

`DIST_EPSILON = 0.03125` units (1/32 unit, 0.00078125 block), from [Source `coordsize.h`, line 35][dist-epsilon]. It is a normal-distance pullback from the selected plane, not a fixed distance along every sweep direction. A stationary hull flush with a Minecraft surface is considered outside rather than `startSolid`. A hull starting inside a brush sets `startSolid`; remaining inside that brush for the whole requested move also sets `allSolid`, forcing fraction zero.

### Seams and numerical constants

There is no brush welding, convex-hull merging or seam teleport. Brushes retain their exact expanded solids, including arbitrary rotated/clipped intersections. At coincident contact, the tangent-sector coverage test resolves their local union boundary; it does not substitute a normal-approach heuristic for exposure. Exact bevel completion prevents individual edge expansions from protruding beyond the intended hull/brush sum. Split/whole fraction, normal and retained-velocity regressions cover floor/ramp approach ratios, reversed brush order, rotated large-coordinate geometry and multiple obliquely clipped neighbors. These are finite double-precision checks subject to the tolerances below, not an unconditional guarantee for arbitrarily degenerate input. Actual gaps or misaligned source geometry are not repaired.

The following are **project-chosen implementation tolerances**, whose primary implementation is [`ConvexBrushWorld.java`](../../mod/src/main/java/dev/csmc/movement/physics/ConvexBrushWorld.java). They are not Valve cvars and are not falsely attributed to Source:

| Constant / guard | Value | Purpose |
| --- | --- | --- |
| `VERTEX_TOLERANCE` | 1e-7 units | Vertex half-space inclusion |
| `MERGE_TOLERANCE` | 1e-6 units per coordinate | Duplicate vertex suppression |
| `NORMAL_TOLERANCE` | 1e-10 in `1 - dot` | Duplicate plane directions |
| `CONTACT_TOLERANCE` | 1e-6 units | Classify flush starts as outside; merge coincident crossings (classification only, never shifts a plane) |
| `PARALLEL_TOLERANCE` | 1e-7 units over the entire sweep | Ignore numerically parallel approach |
| `TANGENT_PARALLEL_TOLERANCE_SQUARED` | 1e-20 | Treat an active normal's tangent projection as parallel to the candidate normal |
| `FACET_TOLERANCE` | 1e-12 | Reject degenerate angular sectors and boundary-only tangent directions |
| Plane-triple determinant / face-pair cross-product guard | 1e-12 | Skip degenerate intersections/parallel faces |
| Nonzero-volume guard | 1e-9 units³ | Reject flat intersections |
| Edge/plane membership and support-distance agreement | 1e-6 units | Identify real edges and supporting bevels |
| Edge/axis cross-product length guard | 1e-9 | Skip degenerate bevel normals |
| Broad-phase padding | 1 unit | Conservative swept-AABB rejection only |

Array growth at `reset` and one-time thread scratch creation can allocate; normal movement steps and hull traces reuse their storage. This is not a promise that the Minecraft geometry collector or adapter performs no allocations.

## Approximations

- **Scale:** 40 units/block is a project choice, not Source's real-world scale. A 250 u/s player travels 6.25 blocks/s (0.3125 blocks/tick). Movement scale is independent of ballistic range scale.
- **Clock and input sampling:** Minecraft ticks at 20 Hz. `N = max(1, round(tickRate / 20))`; each tick advances `N` equal substeps of `0.05/N` seconds, not genuine 64/85.3/128 Hz server frames. Nominal COMPETITIVE 64 becomes 3 substeps at 60 Hz; BHOP 128 becomes 6 at 120 Hz; SURF's stored integer 85 becomes 4 at 80 Hz. The adapter linearly interpolates yaw along the shortest wrapped angle: for `i = 1..N`, `lastYaw + wrapDegrees(currentYaw-lastYaw)*i/N`. It excludes the start endpoint and includes the current yaw. Forward/side/jump/duck/walk inputs are held for the tick; substeps do not recover independently sampled Source user commands. Minecraft publishes/replays one resulting displacement per tick, so substeps do not raise the network update rate.
- **Default tick-rate assumption:** unit conversion and substep scheduling assume the default 20 Hz player clock. Mojang's [tick-command documentation][minecraft-clock] explains that a lowered Minecraft tick target also slows player simulation; this core does not compensate its units/second conversion for a custom lower target.
- **Hull and stairs:** Minecraft's 0.6×1.8-block standing hull becomes 24×72 units; crouching 0.6×1.5 becomes 24×60 units. This differs from the CS:GO 32×72 standing / 32×54 crouching dimensions in the [VDC Mapper's Reference][dimensions] ([archived revision 368575][dimensions-archive], whose own disclaimer calls the measurements suggestions, not official guarantees). Minecraft's 0.6-block step becomes 24 units rather than the cited CS:GO 18. The core takes dimensions from `CsMoveInput`; it does not silently substitute Source hull sizes.
- **Stance speeds:** duck ×0.34 and walk ×0.52 are community-documented approximations (no official Valve primary source). [Published CS:GO movement behaviour][stance-behaviour] supports slowing movement while walking; the exact ratios remain unverified by public documentation found for this work.
- **Stamina:** CS:GO's cvar values do not make this a verified CS:GO stamina algorithm. This project models stamina as a percentage penalty: recover `recoveryRate*dt`; charge `jumpCost*jumpImpulse` after jumping and `landCost*max(0, preStepFallSpeed)` on landing; clamp to `staminaMax`. Jump impulse uses `(100-stamina)/100`; horizontal ground velocity uses that ratio raised to `dt*64`. These cost equations, the direct percentage representation, and the 64-reference normalisation are project approximations informed by [ReGameDLL_CS community CS 1.6 code][regame], **not** a literal port of its different `fuser2` units/formula or a validated CS:GO implementation. BHOP/SURF disable the penalty through zero maximum and costs; their recovery value is consequently inert.
- **Bunnyhop cap:** the 286 u/s cap combines public TF2 whole-vector scaling with a community CS2-derived factor/base-speed observation extrapolated to CS:GO. No public Valve CS:GO function is claimed as authority. Auto-bunnyhopping reuses held input at every grounded substep; competitive requires a fresh press.
- **Preset composition:** BHOP and SURF are community-inspired project presets, not official Valve modes or exact copies of every field of a third-party server config. Their nominal rates and inherited default fields are documented separately from community overrides.
- **Collision:** runtime bevel generation, double precision, project tolerances, flush-contact treatment and true-crossing-before-epsilon selection differ from compiled Source BSP collision. Geometry must be bounded convex brushes with valid outward normals. The world here is a snapshot of supplied brushes; the physics core has no Source entity/platform velocity, material database or dynamic-entity collision system.
- **Air friction state:** the core reconstructs airborne friction at the start of each substep rather than retaining a separate `m_surfaceFriction` field; input supplies ground friction. Above 140 u/s upward Source keeps the last ground surface's friction; the core uses 1 there. This is explicitly documented in the Java code.
- **Scope:** water/swimming, ladders, noclip, conveyor/base velocity, moving-ground compensation, gradual duck transitions, and Source fall-damage handling are not implemented by this core. Duck is a supplied pose/hull. Minecraft integration owns selection of vanilla versus CS-style movement; the core alone does not assert those omitted modes have Source parity.

[start-gravity]: https://github.com/ValveSoftware/source-sdk-2013/blob/0d8dceea4310fde5706b3ce1c70609d72a38efdf/mp/src/game/shared/gamemovement.cpp#L1248-L1267
[step-move]: https://github.com/ValveSoftware/source-sdk-2013/blob/0d8dceea4310fde5706b3ce1c70609d72a38efdf/mp/src/game/shared/gamemovement.cpp#L1517-L1607
[friction]: https://github.com/ValveSoftware/source-sdk-2013/blob/0d8dceea4310fde5706b3ce1c70609d72a38efdf/mp/src/game/shared/gamemovement.cpp#L1612-L1679
[finish-gravity]: https://github.com/ValveSoftware/source-sdk-2013/blob/0d8dceea4310fde5706b3ce1c70609d72a38efdf/mp/src/game/shared/gamemovement.cpp#L1684-L1700
[air-accelerate]: https://github.com/ValveSoftware/source-sdk-2013/blob/0d8dceea4310fde5706b3ce1c70609d72a38efdf/mp/src/game/shared/gamemovement.cpp#L1707-L1748
[air-move]: https://github.com/ValveSoftware/source-sdk-2013/blob/0d8dceea4310fde5706b3ce1c70609d72a38efdf/mp/src/game/shared/gamemovement.cpp#L1753-L1799
[accelerate]: https://github.com/ValveSoftware/source-sdk-2013/blob/0d8dceea4310fde5706b3ce1c70609d72a38efdf/mp/src/game/shared/gamemovement.cpp#L1822-L1854
[stay-ground]: https://github.com/ValveSoftware/source-sdk-2013/blob/0d8dceea4310fde5706b3ce1c70609d72a38efdf/mp/src/game/shared/gamemovement.cpp#L1859-L1890
[walk-move]: https://github.com/ValveSoftware/source-sdk-2013/blob/0d8dceea4310fde5706b3ce1c70609d72a38efdf/mp/src/game/shared/gamemovement.cpp#L1895-L2020
[full-walk]: https://github.com/ValveSoftware/source-sdk-2013/blob/0d8dceea4310fde5706b3ce1c70609d72a38efdf/mp/src/game/shared/gamemovement.cpp#L2025-L2141
[jump]: https://github.com/ValveSoftware/source-sdk-2013/blob/0d8dceea4310fde5706b3ce1c70609d72a38efdf/mp/src/game/shared/gamemovement.cpp#L2354-L2530
[try-move]: https://github.com/ValveSoftware/source-sdk-2013/blob/0d8dceea4310fde5706b3ce1c70609d72a38efdf/mp/src/game/shared/gamemovement.cpp#L2560-L2814
[check-velocity]: https://github.com/ValveSoftware/source-sdk-2013/blob/0d8dceea4310fde5706b3ce1c70609d72a38efdf/mp/src/game/shared/gamemovement.cpp#L3049-L3087
[clip-velocity]: https://github.com/ValveSoftware/source-sdk-2013/blob/0d8dceea4310fde5706b3ce1c70609d72a38efdf/mp/src/game/shared/gamemovement.cpp#L3145-L3180
[quadrants]: https://github.com/ValveSoftware/source-sdk-2013/blob/0d8dceea4310fde5706b3ce1c70609d72a38efdf/mp/src/game/shared/gamemovement.cpp#L3711-L3768
[categorize]: https://github.com/ValveSoftware/source-sdk-2013/blob/0d8dceea4310fde5706b3ce1c70609d72a38efdf/mp/src/game/shared/gamemovement.cpp#L3774-L3893
[non-jump]: https://github.com/ValveSoftware/source-sdk-2013/blob/0d8dceea4310fde5706b3ce1c70609d72a38efdf/mp/src/game/shared/gamemovement.cpp#L3808-L3814
[ground-normal]: https://github.com/ValveSoftware/source-sdk-2013/blob/0d8dceea4310fde5706b3ce1c70609d72a38efdf/mp/src/game/shared/gamemovement.cpp#L3844-L3857
[probe]: https://github.com/ValveSoftware/source-sdk-2013/blob/0d8dceea4310fde5706b3ce1c70609d72a38efdf/mp/src/game/shared/gamemovement.cpp#L3798-L3802
[bump-count]: https://github.com/ValveSoftware/source-sdk-2013/blob/0d8dceea4310fde5706b3ce1c70609d72a38efdf/mp/src/game/shared/gamemovement.cpp#L2560-L2580
[clip-planes]: https://github.com/ValveSoftware/source-sdk-2013/blob/0d8dceea4310fde5706b3ce1c70609d72a38efdf/mp/src/game/shared/gamemovement.cpp#L26
[coord]: https://github.com/ValveSoftware/source-sdk-2013/blob/0d8dceea4310fde5706b3ce1c70609d72a38efdf/mp/src/public/coordsize.h#L17-L19
[dist-epsilon]: https://github.com/ValveSoftware/source-sdk-2013/blob/0d8dceea4310fde5706b3ce1c70609d72a38efdf/mp/src/public/coordsize.h#L35
[bevels]: https://github.com/ValveSoftware/source-sdk-2013/blob/be57e3d01ac7d7aa6a2cacbfef187c0b46616db0/src/utils/vbsp/map.cpp#L448-L599
[quake-clip]: https://github.com/id-Software/Quake-2/blob/372afde46e7defc9dd2d719a1732b8ace1fa096e/qcommon/cmodel.c#L989-L1096
[tf2-cap]: https://github.com/ValveSoftware/source-sdk-2013/blob/39f6dde8fbc238727c020d13b05ecadd31bda4c0/src/game/shared/tf/tf_gamemovement.cpp#L1086-L1112
[bhop-analysis]: https://github.com/zer0k-z/cs2-movement-issue-bhop/blob/58e80d1f462dcf31d14ca7b02847e72b3cb421bc/README.md#L53-L57
[regame]: https://github.com/rehlds/ReGameDLL_CS/blob/b0889847fe6d03898be88acc9e366660efb40ab5/regamedll/pm_shared/pm_shared.cpp#L1059-L1078
[dimensions]: https://developer.valvesoftware.com/wiki/Counter-Strike:_Global_Offensive/Mapper%27s_Reference
[dimensions-archive]: https://web.archive.org/web/20250101150843/https://developer.valvesoftware.com/wiki/Counter-Strike:_Global_Offensive/Mapper%27s_Reference

[cvar-dump]: https://github.com/saul/cvar-unhide/blob/241c2d569d66c34ef333c8e3decf10eed8c9cbb1/cvarlist-csgo.md#L1-L8
[vdc-cvars]: https://developer.valvesoftware.com/wiki/List_of_Counter-Strike:_Global_Offensive_console_commands_and_variables
[vdc-cvars-archive]: https://web.archive.org/web/20231112105946/https://developer.valvesoftware.com/wiki/List_of_Counter-Strike:_Global_Offensive_console_commands_and_variables
[tracking-cvars]: https://github.com/SteamTracking/GameTracking-CS2/blob/e63fc7fdb8dfb4f1873f0db214c0cc16613d5beb/csgo/bin/linux64/server_client_strings.txt#L28606-L28950
[bhop-config]: https://github.com/shavitush/bhoptimer/blob/2c87128446302f534e1fd0147090b83be3256a6e/addons/sourcemod/configs/shavit-styles.cfg#L15-L39
[surf-config]: https://steamcommunity.com/sharedfiles/filedetails/?id=1436068277
[surf-ticks]: https://steamcommunity.com/sharedfiles/filedetails/?id=477486389
[stance-behaviour]: https://counterstrike.fandom.com/wiki/Movement
[minecraft-clock]: https://www.minecraft.net/en-us/article/minecraft-java-edition-1-20-3
