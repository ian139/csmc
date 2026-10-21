# Near-term backlog

Requested features to plan after current work; not started or scheduled. No implementation is authorized by this list alone.

## Workshop 1v1 arena and skin trading post (implemented)
- [x] Add one more arena, separate from and preserving the existing arenas.
- [x] Select an actual CS:GO Steam Workshop 1v1 map and reproduce it 1:1: verify its layout, dimensions, elevations, cover, and sightlines from available permitted map/reference files, and document the Source-to-Minecraft scale and any unavoidable block-grid differences.
- [x] Reuse the existing 1v1 arena's match flow: team joining, countdown, first-to-five scoring, respawns/protection, reset/rebuild, and preservation of personal equipment.
- [x] Replace automatic case prizes for this new arena with a villager trading post offering gun-skin attachments, knife-skin attachments, and cases for gold nuggets. Award **5 gold nuggets to the winner and 1 to the loser per completed match**, not per kill. Prices: Weapon Case 3, Loadout Case 4, gun skin 40, knife skin 60 nuggets.
- [x] Keep the arena outdoors/open to the sky unless the selected Workshop map is indoors. Light the arena, lobby, and trading area properly for visibility and to prevent unwanted mob spawning.
- [x] No spectator villager stands, decorative crowds, or similar additions; villagers are for the trading post only.

Implemented as one `am_grass2` arena cell from the author's pinned VMF mapversion 20
at 40 Hammer units per block, on a separate world/server. Geometry, native ramps,
original Source1 prop measurements, Minecraft materials/grid deviations, and
unverified Workshop-BSP/historical-model-version equality are documented in
[arenas](../arenas.md#workshop-1v1-am_grass2-and-nugget-trading). This is not a
pixel-identical art port.

## Glove skins (done)
- [x] Add selectable glove finishes to the existing CS arms/gloves rendering, worn from a glove slot in the survival inventory next to the offhand (shield) slot.

## CS:GO movement and surfing (done)
- [x] Add CS:GO-style movement, including bunny hopping and surfing, suitable for LAN multiplayer.
- [x] Investigate surf blocks/items or another collision/geometry approach that supports surfing in Minecraft.
- [x] Investigate importing or adapting decompiled surf maps from user-provided, permitted files; decide whether direct map conversion or a comparable Minecraft-native method works better.

Movement defaults off per world (`/gamerule csmcMovement`). See [movement](../movement.md), [surf ramps](../surf.md), and [permitted VMF imports](surf-import.md); real map files remain user-provided prerequisites.

## Weapon icon and ammo HUD (done)
- [x] Replace the temporary ammo action-bar readout with a compact, persistent bottom-right HUD while holding a gun, inspired only by the reference screenshot's ammo/icon area.
- [x] Show a white silhouette of the held weapon, a thin vertical divider, a large magazine-round count, and a smaller reserve-ammo count beside it; keep the background minimal and the typography readable.
- [x] Use actual server-authoritative magazine and total matching inventory ammo values, updating on shots and completed reloads. Keep magazine capacity distinct from reserve ammo; do not copy the screenshot's example numbers or show duplicate ammo readouts.
- [x] Hide it when no gun is held and respect Minecraft GUI scaling without overlapping the hotbar. Scope is the weapon icon/ammunition display only, not the screenshot's other visuals or assets.

## Stronger bullet-resistant armor (done)
- [x] Add stronger armor that meaningfully protects against gunfire.
- [x] Give it a more realistic CS-style appearance rather than only vanilla armor visuals.
- [x] Determine how protection, penetration, durability, and existing enchantments should interact before implementation.

## Hidden and unused CS weapons/items (done)
- [x] Inventory hidden, unused, legacy, and special-mode CS weapons/items in the available game files, including shields and syringes/healthshots; investigate whether rocket-launcher-style weapons exist.
- [x] Distinguish functional items from leftover assets, incomplete prototypes, and items absent from the available CS version. Do not assume the requested examples exist.
- [x] Add all discovered usable items, with their relevant first-person hand/viewmodels, arms/gloves, animations, sounds, and gameplay effects where available; document missing assets or behavior.

## Ammunition categories (done)
- [x] Add dedicated sniper ammunition and shotgun shells.
- [x] Make pistols and SMGs share one ammunition type; retain rifle ammunition for rifles and machine guns.
- [x] Update weapon assignments, reload consumption, reserve HUD, crafting, and item labels consistently.

## Weapon damage and role balancing (implemented; values pending playtest)
- [x] Keep guns highly damaging and stronger than vanilla weapons, while giving each gun a useful role rather than making one gun the best choice everywhere.
- [x] Balance damage alongside range, spread, recoil, fire rate, magazine capacity, reload time, penetration, movement, and ammunition cost to establish meaningful tradeoffs.
- [ ] Evaluate against both vanilla mobs and armored LAN players, coordinating with the stronger-armor backlog item; do not commit to exact values before playtesting. (Modelled in `docs/balance.md`; tune via `data/<ns>/weapon_balance/*.json` and `/reload` after playtesting.)

## Other players see CS animations (done)
- [x] Show other players' weapons and knives with CS-style third-person animation instead of the current simple swing/world model. (Player-skeleton poses are procedural approximations, since the CS2 export has no third-person player clips; weapon world-model fire/reload clips are authentic.)
