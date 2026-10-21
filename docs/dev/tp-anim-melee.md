# Third-person knives and grenades

## Provenance and rendering boundary

Player-skeleton poses are procedural CS-style approximations. The CS2 exports contain weapon world-model animations and first-person weapon/arm clips, not third-person Minecraft-compatible player animation sets. No first-person arm clip is retargeted onto the player skeleton. The pose providers reject the first-person owner; vanilla movement and the existing first-person viewmodel playback remain separate.

Knife ready, draw, inspection, wide horizontal slash, straight stab and heavier backstab follow accepted knife events and the existing read-only weapon playback cue. Hit feedback preserves the swing start instead of restarting it. Draw/inspect cancellation, death, item switches and disconnects discard the relevant pose state. Aim pitch influences the ready grip; the central pose applier handles left-handed players.

## Butterfly and Shadow Daggers

The cached Butterfly model retains articulated `front`, `blade`, `rear` and `lock` joints. Its embedded `inventory_inspect` and `inventory_icon` clips contain only static, time-zero keys; they are not an authored handle-flipping performance. Third-person moving-part motion is therefore procedural, not an authentic CS2 third-person animation. First-person clips and their exported arm skeleton are not changed.

The Butterfly topology cache uses weak model keys and stores only joint indices, so resource reloads can release retired model geometry without the motion hook retaining it.

`knife_push` (Shadow Daggers) contains both blades in a single skinned primitive, with `weapon_hand_l`/`weapon_l` and `weapon_hand_r`/`weapon_r` joints. Both player arms are posed symmetrically, and the existing both-daggers model is rendered once. Rendering a second complete item through vanilla's offhand layer would create four daggers, so it is deliberately not done. Limitation: the authored pair remains attached to the main-hand item transform rather than one independently anchored item in each hand. Splitting the primitive or joint-masking separate draws would require additional renderer work.

## Grenade lifecycle

Ready hold transitions into a pin-pull support-hand gesture and a cocked throwing arm. Overhand, low underhand lob, and medium throws have distinct release silhouettes and recover over half a second. Cancelling a prime restores the ready pose without throwing. Local F5 receives the same cosmetic timeline transitions as observers; it does not change the first-person grenade pose.

`GrenadePrimePayload` is cosmetic C2S intent only. The server requires a living, nonspectating player holding a main-hand grenade, ignores duplicate prime starts, and permits at most four new primes in a rolling twenty-tick window. Cancellation is not rate-limited. Death, slot/item changes, dimension transfer, respawn and disconnects clear prime state. New or returning trackers receive the actor's current held-prime state after tracking starts. Successful existing grenade throws broadcast their accepted strength (`OVERHAND=0`, `LOB=1`, `MEDIUM=2`) without depending on prime state or modifying launch physics, inventory rules or damage.

The small grenade body-yaw accent keeps its canonical sign for left-handed players; arm angles, not additive body yaw, are mirrored by the shared pose applier.

## Behavioral checks

Pure main-source JUnit tests exercise knife event selection, attack envelopes, exact recovery, aim following, dual-arm symmetry, grenade timing/cancellation, server prime-state rate/cancellation transitions, tracking snapshots, and prime reset/restart across world changes or respawn. Tracking snapshots are queued until tick end (after spawn and state validation), target only the new observer, and never replay a prime cancelled before delivery. Client adapters and observer visibility require the isolated two-client runtime scenario in addition to those math/state tests.

The verification run for this work compiled main/client sources and passed 222 JUnit tests plus all 173 required GameTests. These include existing gameplay regression coverage, not just the newly added pose/state tests.

An isolated two-client run on `127.0.0.1:25902` captured 31 observer screenshots from real server-confirmed events: knife draw/slash/stab/inspect, four grenade primes, overhand and lob throws, two cancels, and return to empty-hand idle. The inspected images show the pin-pull, raised cocked arm, differing release silhouettes, ready-after-cancel, and articulated Butterfly parts. This run used real C2S grenade notices/throws directly, not the local mouse/focus path; it does not claim a local F5 grenade-input runtime check.
