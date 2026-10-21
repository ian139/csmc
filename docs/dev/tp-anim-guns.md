# Third-person gun poses

## Provenance and visibility

CS2 exports contain weapon world-model clips and first-person viewmodel/arm clips, **not third-person player skeleton animations**. `GunPoseProvider` is an authored procedural approximation: long guns use a shouldered grip with the support arm crossing onto the foregrip; pistols use a joined two-handed grip. Both arms track the rendered head pitch. Remote players and the owner's F5 body use these poses; `firstPersonOwner` is rejected, and no first-person playback code or transforms change.

Weapon world-model shoot/reload/inspect clips continue through `CachedGunRendering` unchanged, including the server's reload timing. Its one read-only `thirdPersonCue(entityId, weapon)` accessor lets the adapter observe the existing sound/inspect receiver's playback identity, original receipt timestamp and cancellation, without registering a duplicate network receiver. `Playback` only adds the `TimedGunCue` interface over fields it already contains. The adapter samples on the next client tick/render but preserves the event's original start time, so re-tracking an entity never restarts its old action.

The held-weapon layer also compensates the cached item's hanging-arm basis by the actual applied arm elevation and claim weight, preserving head aim. It reads `blend.HeldItemPoseCorrection` after the smoothed pose is applied; unclaimed players, off-hand items and first person receive no correction. Knives share this correction after observer runtime exposed the same raised-arm issue, retaining their existing authored upward blade tip. This is an item-only third-person adjustment, not a change to `GunTransforms`. The callback/helper from the blend change is a required integration dependency.

## Actions

- Idle/aim: rifle, SMG, sniper, shotgun and pistol silhouettes; server-polled scope tightens a long gun against the shoulder. Scope changes go to trackers and the actor (F5); newly tracked players receive current scope after spawn.
- Fire: a short upward arm kick, maximum at 12% of the cycle, then eased recovery at cycle end. Kick strength varies by weapon class; no gameplay recoil is changed. Cycle/deploy times use the local CS2 weapons export when present; otherwise authored class timing defaults are used. Those local cosmetic timings are not a server-data synchronization guarantee for customized datapacks.
- Reload: server-duration procedural support-hand movement to the magazine well, down/out, back in, then bolt/slide pull and return to foregrip. This is not a literal retargeted export. Shotguns use the same broad support-hand approximation rather than pretending Minecraft arms can reproduce each exported shell insertion; authentic weapon-node reload continues on the item.
- Draw: lower-to-raised eased arms over deploy time. Inspect: inward weapon tilt and support-hand shift, then return over 2.5 seconds.
- Reload end/cancel clears the renderer cue and therefore the timeline; a held item/slot change clears or replaces the action with draw. Entries are forgotten on entity unload/disconnect. Moving between two identical gun items remotely relies on the existing server draw/cancel event, because equipment synchronization does not expose remote hotbar indices.

## Special clips: precise status

- Revolver: FIRE receives ordinary post-shot recoil immediately. There is **no pre-shot hammer-prime lean**: server gameplay has no priming delay/event, and adding one after FIRE would falsely imply the shot had not happened. No new PRIME action is synthesized.
- Dry fire: the gun event protocol has FIRE, RELOAD_START/END/CANCEL and DRAW, but no dry-fire event; no third-person dry-fire motion is triggered.
- `fire_alt`: no alternate-fire gun event or corresponding action is fed to this provider. Revolver alternate fire is not newly implemented.
- Silencer attach/detach: no gameplay/event state transition is available, so no third-person attach/detach motion is synthesized. Suppressed weapon variants retain their existing models and sounds.
- These unavailable actions are also absent from `GunClipChooser.ACTIONS`; this work does not claim to enable otherwise unused exported clips or alter first-person behavior.
