# Third-person blending and movement

## Provenance

The player-skeleton movement curves are procedural CS-like approximations, not CS2 player animation exports or retargeted first-person arms. CS2 weapon world-model clips remain separate and unchanged. First-person owners bypass the entire third-person blend; no viewmodel code is modified.

## Transitions

`ThirdPersonPoseApplier` resolves each provider into main-hand-relative coordinates, applies `MovementLayer`, then runs `PoseSmoother` before mirroring onto the actual dominant arm. Each entity has independent angles, velocities, weights, and timestamps. The exact critically damped spring uses a 20 rad/s frequency, bounded 100 ms frame steps, shortest angular paths, and endpoint-crossing protection. First appearance seeds arm angles from that player's current vanilla model with zero pose weight. Item switches retain velocity; cancellation holds the last requested angles while smoothly reducing weight to vanilla. Body yaw and head pitch offsets also fade. Equal timestamps do not advance the spring. Disconnect, entity unload, sleeping/spectating, and entering first-person owner view clear the relevant state.

Vanilla animation continues to run each frame, including while a pose fades out. The smoother never caches a shared model's vanilla pose as another player's initial state. Final arm composition linearly interpolates the smoother's already-unwrapped angle branch; it does not choose a fresh shortest branch against moving vanilla angles, avoiding half-turn snaps at fractional weights.

`HeldItemPoseCorrection` publishes only the currently applied player's smoothed main-arm weight and actual arm/head angles for held-item render adapters. It is cleared before each model pose and on lifecycle cleanup; it never changes item transforms itself.

## Equipment arbitration

Before any provider resolves, `ThirdPersonPoseApplier` classifies the main-hand, off-hand and in-use items (`HealthshotItem`, `C4Item`, `DefuseKitItem`) and asks the pure `dev.csmc.thirdperson.EquipmentArbitration.decide`, so gun, knife and grenade providers share one rule. A healthshot or C4 in use in either hand, or a held C4, is vanilla's two-handed crossbow pose from `EquipmentClient.armPose`: the applier drops that player's smoother state and leaves both arms (and body/head offsets) vanilla, so a fading weapon pose never overrides it, and a later pose fades in from vanilla. The decision reads the item actually being used, so an off-hand inject or plant stays vanilla across main-hand switches. Any other equipment held idle in the off hand (healthshot, defuse kit) keeps the main-arm pose while `EquipmentArbitration.smooth` masks the off-arm weight before and after smoothing, so the off arm is vanilla from the first frame and its retained weight decays while masked; if the equipment leaves the off hand within a few frames, the off arm can resume part of the earlier weapon pose immediately (short-dwell reacquisition is not smoothed). JUnit covers AK + off-hand C4, AK + in-use off-hand healthshot, knife + off-hand defuse kit, use across main-hand switches, and decision changes while pose weights are nonzero.

## Movement layer

Every provider receives the same small upper-body layer. Grounded arms get opposing walk bob proportional to clamped vanilla limb swing amount. Airborne players suppress that bob and raise their arms slightly, with a bounded additional offset based on received vertical position change (rather than remote delta movement). Landing adds a 180 ms damped settle. Crouching adds a small lowering compensation to the arms while retaining vanilla's crouched body lean. Legs and body pitch are never written: vanilla walking, crouching, jumping, and sprint leg animation remain intact.

The client `MovementLayer.apply(PoseContext, PoseFrame)` is the shared entry point. Main-source `dev.csmc.thirdperson.blend.MovementLayer.applyOffsets(...)` exposes the same pure movement curve for previews and numeric tests; `PoseFrame` is a client-independent mutable value carrier. The frozen provider `PlayerPose` is copied once into reusable scratch storage at the adapter boundary. These curves do not alter action timing, server state, or gameplay.

## Behavioural tests

Main-source JUnit tests cover spring convergence, bounded endpoints, entity isolation, target replacement and cancellation, vanilla seeding, lifecycle clearing, timestamp handling, walk amount bounds, airborne speed response, crouch compensation, and landing decay. Final-composition regressions cover a held pose at −2.8 radians while vanilla crosses 0.340 → 0.343 radians, both at half weight and during fade-out. They use synthetic `PoseFrame` values, not a production fallback provider; no client classpath change is required.

## Opt-in runtime observer

`ThirdPersonDevHarness.initialize()` is inert unless `-Dcsmc.tpDev=actor|observer` is supplied. Use isolated, offline two-client development servers only: the actor intentionally issues setup commands and controls movement. Both configured usernames must be operators. Connect with `--quickPlayMultiplayer 127.0.0.1:25903` and set:

```text
-Dcsmc.tpDev.actor=BlendActor
-Dcsmc.tpDev.observer=BlendObserver
-Dcsmc.tpDev.hotbar=empty,csmc:ak47
-Dcsmc.tpDev.stages=idle,walk,crouch,jump
-Dcsmc.tpDev.stageTicks=80
-Dcsmc.tpDev.actorCamera=back
-Dcsmc.tpDev.quit=true
```

The hotbar plan uses real server-confirmed held items, never a production test pose provider. The observer waits for an armed cycle before capturing each stage, including transition bursts and stable angled views. Screenshots are written below each client's `screenshots/` directory with `tpdev-` names. Back-camera actor captures also exercise the local F5-equivalent model. Without the opt-in property there are no dev callbacks, connection attempts, commands, input overrides, camera changes, or screenshots.
