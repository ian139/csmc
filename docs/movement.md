# CS:GO movement (bunny hopping, surfing)

CS:GO-style player movement for LAN multiplayer: Source `FullWalkMove` physics (ground friction/accelerate,
air-accelerate strafing, bunny hopping, ramp surfing) running on the client for the local player, with the server
validating positions with vanilla collision checks and a bounded CS-mode allowance for bunched movement packets.
Physics and cited constants: `docs/dev/movement-reference.md` (core).
Surf geometry: `docs/surf.md`. Code: `mod/src/main/java/dev/csmc/movement/` (rules, sync, server mixins) and
`mod/src/client/java/dev/csmc/client/movement/` (local simulation, client mixins, dev harness).

## Gamerule and modes

`/gamerule csmcMovement <off|competitive|bhop|surf>` — per world, default **`off`**, server-authoritative.
Changes apply immediately: the server sends every player a `csmc:movement_state` payload (preset + held weapon max
speed) whenever the mode or the held item changes, and on join, respawn and dimension change.

| Mode | Preset (`CsMovementParams`) | Notes |
|---|---|---|
| `off` | — | Vanilla movement, untouched (no mixin changes behaviour). |
| `competitive` | `COMPETITIVE` | CS:GO matchmaking cvars at 64 tick: sv_airaccelerate 12, stamina on, no bhop cap removal. |
| `bhop` | `BHOP` | sv_airaccelerate 1000, sv_enablebunnyhopping 1, **sv_autobunnyhopping 1** (hold jump), no stamina, 128 tick. Community-server convention (labeled). |
| `surf` | `SURF` | sv_airaccelerate 150, sv_accelerate 10, bhop + autobhop, no stamina, 85 tick. Community-server convention (labeled). |

Clients without the mod never receive the payload and keep vanilla movement (the server retries each tick until
the channel is negotiated).

**Per-player override:** an entity tag `csmc_movement_<mode>` (for example `/tag @s add csmc_movement_bhop`) replaces
the world's mode for that player, so datapacks can give lobbies and arenas different movement (the hub pack uses
`bhop` in lobbies and `competitive` in arenas). Remove the tag to fall back to the gamerule. Unknown suffixes are
ignored; with several valid tags, `off`, `competitive`, `bhop`, `surf` is the precedence order.

## Controls (CS movement active)

| Action | Key |
|---|---|
| Move | W A S D (each `cl_forwardspeed`/`cl_sidespeed` = 450) |
| Jump | Space. Competitive: re-press per jump (no held-jump bhop, stamina). bhop/surf: hold to auto-hop. |
| Duck | Sneak (Shift): ×0.34 max speed; ducking in the air lifts the feet (head stays put), as in Source. No ledge back-off. |
| Walk (`+speed`) | **Left Alt** by default — "Walk (CS movement)" in Controls → CSMC; ×0.52 max speed. |
| Sprint | Suppressed (CS has no sprint; it would add vanilla's +30 % speed). Vanilla auto-jump and vanilla jump are off for the local player. |

Pending vanilla auto-jumps are cleared before input is consumed; genuine Space input is unchanged. Suppressing a
vanilla ground jump also suppresses its cooldown write, without overwriting an existing cooldown, so switching
movement off does not leave a delay for a jump that never happened.

CS movement rebuilds commands from raw keys and **bypasses vanilla's ×0.2 item-use slowdown**, including
healthshot and C4 use in either hand. Ordinary CS weapon speed, duck and walk modifiers still apply.

## Weapon speed rule

Max ground speed = held item's CS:GO **unscoped** `max_player_speed` × (`MOVEMENT_SPEED` attribute value / base):

- guns: `WeaponRegistry.get(gun.weaponId()).maxSpeed()` (e.g. AK-47 215, AWP 200);
- grenades: 245; knives, empty hand and every other item: 250 (CS:GO items_game.txt values).

The existing `csmc:scope_speed` modifier therefore yields the scoped speed (AWP 200 → 100), and Speed/Slowness
effects scale it. Movement inaccuracy (Ballistics) rescales the measured speed by
`(4.317/20) / unitsPerSecondToBlocksPerTick(250)` ≈ 0.6907 while CS movement is on for the shooter, so running at
250 u/s costs the same inaccuracy as vanilla walking did; with `off` the factor is exactly 1.

Weapon `max_speed` and the protocol's held-speed value share the finite, positive, at-most-2,000 units/s
domain. Invalid balance overrides are rejected before installation. Mode-off updates always disable CS movement,
regardless of their unused held-speed value. The bound also caps the per-tick collision-collection radius
(`MovementResolve.collisionReach`: under 18 blocks for a stationary player at 2,000 units/s with the shipped presets, as asserted by `CollisionReachTest`).

## How it fits Minecraft (and LAN/server validation)

- The **client** simulates: each Minecraft tick (20 Hz) runs `tickRate/20` substeps of `CsGameMovement.step`
  (rounded by `substepsPerTick()`), with the view yaw interpolated linearly along the shortest arc from the
  previous tick's yaw to the current one; keys are held constant within a tick. The result is committed once per
  tick through vanilla `Entity.move`, so step sounds, block effects (soul sand, honey, cobwebs) and the server's
  single `move(PLAYER, …)` replay see an ordinary player move. Velocity is stored in `deltaMovement`, so knockback,
  explosions and pushing still apply and feed back into the simulation.
- The **server never simulates CS movement**; it replays the client's move like vanilla. Before committing, the
  client runs that same axis-separated replay (`Entity.collide`); if it differs from the swept CS move by more than
  0.05 blocks horizontally, a fallback is committed only if its endpoint is clear of the **combined** collision
  world (vanilla boxes + analytic solids) and replay-compatible. If the raw replay is unsafe, it is swept through
  that combined world and checked again; if no safe compatible result exists, the player stops. Ground/surf state
  and velocity are reconciled with the endpoint actually committed, not the abandoned CS endpoint.
- **Activation, teleport and respawn** resynchronization resolves shallow analytic penetration before simulation:
  push along the least-penetrated analytic plane, with a 1/1024-block clearance, at most four passes and 0.5 blocks
  total. This lifts players from a ramp's inscribed vanilla staircase onto its true surface. Deeply embedded
  teleports beyond that bound are not automatically rescued.
- **Fall damage**: fall distance is cleared only while resting on or sliding along an **upward-facing** analytic
  support/surf plane (`normal.y > 0`) within a 1/16-block downward hull sweep. Walls, undersides, and near misses
  keep accumulating fall distance, including damage on a subsequent landing. Movement off and landings reported
  on ground use vanilla unchanged (`MovementContact`, `ServerPlayerFallMixin`).
- **Hand-off states** use vanilla movement: water/lava/swimming, ladders/vines, creative/spectator flight, elytra,
  vehicles, levitation, riptide, sleeping. Analytic movement solids (surf ramps) still block the local player there.
- `AnalyticCollision` predicate (identical on server and client): non-spectator players for whom CS movement is
  active. Their vanilla collision with surf-ramp blocks is empty; the exact convex solids are traced by the client.
- **Long airtime**: vanilla kicks players who stay airborne without ascending checks for 80 ticks ("Flying is not
  enabled on this server") only if the server believes they float. Long ascending surf/bhop arcs on big maps can
  trip it on dedicated servers: set `allow-flight=true` in `server.properties` for surf servers. LAN hosts
  (integrated server) are unaffected for the host player.
- **Speed check / packet bunching**: vanilla compares cumulative displacement since the start of the server tick
  with `100 × q` blocks² beyond velocity², but resets `q` to one after five packets. In CS mode only,
  `ServerMovePacketMixin` raises that budget to accommodate up to **ten** bunched client ticks at the active preset's
  per-axis `maxVelocity`: displacement allowance is `min(packetCount, 10) × (√3 × maxVelocity / 800 + 0.75)` blocks.
  The √3 covers Source's per-axis clamp; 0.75 is an integration approximation for step/duck shifts and rounding.
  More packets gain no extra allowance, absurd displacements are still rejected, and the vanilla collision,
  "moved wrongly", teleport and other validation remain in place. **Movement off retains vanilla thresholds.**
  A packet-handler GameTest sends six bunched updates at 4 blocks/tick (3200 u/s): accepted in CS mode, rejected
  with movement off; a 60-block update remains rejected in CS mode.
- **State payload bounds**: malformed/non-finite or out-of-range movement variables are logged and ignored,
  retaining the previous client state. Tick rates outside 20..128 are rejected (1..6 substeps at 20 Hz); speed-like
  variables are bounded at 10,000 u/s, modifiers at 0..1, and accelerations/stamina have explicit finite bounds.

## Dev harness (system properties, inert unless set; log prefix `[csmc move]`)

| Property | Side | Effect |
|---|---|---|
| `-Dcsmc.devMovementScript=<file>` | client | Run a script once in a world; disables pause-on-lost-focus. |
| `-Dcsmc.devMovementTelemetry=true` | client | Per-tick telemetry from start (scripts toggle with `telemetry on/off`). |
| `-Dcsmc.devMovementObserve=<name>` | client | Per-tick position of another player as this client sees it (observer client). |
| `-Dcsmc.devMovementServerLog=true` | server | Per-tick accepted position, onGround, fall distance, mode, held speed of every player. |

Telemetry line: `[csmc move] tick=… gt=… pos=x,y,z vel=vx,vy,vz hs=<horizontal u/s> meas=<u/s from displacement>
grounded= onGround= surf= jumped= landed= max=<max speed> mode=cs|vanilla active= clamp=<replay fallback>
duck=<air-duck shift u> pose= yaw= held= solids=<analytic solids nearby>`.

Script syntax (one step per line, `#` comments; keys `forward back left right jump sneak sprint walk use attack`,
comma-separated):

```
wait N                 block for N ticks
waitground N           block until on ground, at most N ticks
hold KEYS N            hold keys for N ticks (non-blocking; N >= 1; only -1 = until released; 0 rejected)
press KEYS             hold keys for one tick
release KEYS|all       release keys
yaw DEG | pitch DEG    set view angle
yawrate DEG            turn DEG per tick (0 stops)
slot 1-9               select hotbar slot
chat TEXT              chat, or a command when TEXT starts with /
gamerule MODE          /gamerule csmcMovement MODE
tp X Y Z [YAW PITCH]   /tp @s …
screenshot NAME        screenshots/NAME.png
mark TEXT              marker line in the log
telemetry on|off       per-tick telemetry
quit                   close the client
```

Scoping is `press use` with a scoped gun in hand. Sample (bhop strafe-turn; speed should climb above 250 u/s):

```
wait 100
telemetry on
gamerule bhop
tp 0.5 -60 0.5 0 0
waitground 60
hold forward 20
wait 20
mark strafe start
hold jump -1
release forward
hold right 20
yawrate 9
wait 20
yawrate -9
hold left 20
wait 20
yawrate 0
release all
mark strafe end
quit
```

Running (generic example; choose your own port and a dedicated run dir): an init script redirects loom's
`runServer`/`runClient` (`workingDir`, `--username`, `--quickPlayMultiplayer localhost:<port>`, the properties
above); `server.properties`: `online-mode=false`, `server-port=<port>`, `level-type=minecraft:flat`. Then
`./gradlew runServer --init-script init.gradle -PmoveRole=server` and
`./gradlew runClient --init-script init.gradle -PmoveRole=client1 -PmoveScript=/abs/script.txt` (a second client
with `-PmoveRole=client2` observes `MoveP1`). Stop the server with `stop`; scripts end with `quit`.

## Approximations (also labeled in code)

- 40 units per block; Minecraft bounding box as the CS hull (0.6×1.8, crouch 0.6×1.5 blocks); step height
  `maxUpStep()` 0.6 blocks (CS: 18 units).
- 20 Hz Minecraft ticks split into `tickRate/20` substeps with linearly interpolated yaw (Source runs one usercmd
  per server tick with its own view angles).
- Surface friction from block slipperiness, `(1 − s)/(1 − 0.6)` clamped to [0.05, 1] (Source uses surfaceprops).
- In-air duck shifts the origin by the standing−crouching height difference; an unduck without room below keeps
  the feet in place (Minecraft already decided to stand).
- Replay guard: when Minecraft's axis-separated collision disagrees with the swept CS move by > 0.05 blocks horizontally,
  only a combined-world-clear, replay-compatible endpoint is committed; otherwise the move is swept shorter or
  stopped, and velocity and contact state are reconciled to the committed endpoint.
- Ballistics movement inaccuracy factor ≈ 0.6907 maps CS 250 u/s onto vanilla walking speed (4.317 blocks/s).
- Remote players are rendered from server positions (vanilla interpolation), not re-simulated.
