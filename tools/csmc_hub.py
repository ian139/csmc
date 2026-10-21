#!/usr/bin/env python3
"""Generate the csmc_hub data pack: spawn hub, game portals and world policing (Minecraft 1.21.1).

One void-superflat world hosts four places: this hub, the PvE arena (csmc_arena), the Workshop 1v1 arena
(workshop) and the surf course (csmc_surf). The old indoor PvP mirror (csmc_pvp) is NOT part of it.
The hub owns the server-wide rules and the policing of all four areas; see docs/hub.md.

python3 tools/csmc_hub.py --world /abs/path/to/world [--floor 100] [--pve-floor 232] [--workshop-floor 64] [--surf-floor 190]

Install order (the four generators may be run in any order, but the console order matters):
  1. python3 tools/csmc_arena.py --world W; python3 tools/csmc_workshop_arena.py --world W;
     python3 tools/csmc_surf.py --world W; python3 tools/csmc_hub.py --world W
     (--surf-floor here must equal the --floor given to csmc_surf.py)
  2. /reload
  3. /function csmc_arena:setup, /function workshop:setup, /function csmc_surf:setup, /function csmc_hub:setup
     (hub setup runs LAST: it pins the server-wide gamerules and the world spawn)
  4. wait for the forceloaded chunks
  5. /function csmc_arena:build_all, /function workshop:build_all, /function csmc_surf:build_all,
     /function csmc_hub:build
     (hub build runs last as well: it also repairs shop and visitor villagers and adds the return plates)
Administrators are the players carrying tag `csmc_admin` (/tag NAME add csmc_admin); everybody
else is forced into adventure mode and kept inside the four areas. Nobody can be hurt in safe zones
(the hub, the two arena lobbies and the whole surf course).

The hub adds no Java of its own and no command blocks. `csmc_hub:tick` is registered in #minecraft:tick and
also polls the pressure plates, so there is no redstone to break. It does need the mod's per-player movement
override (tag csmc_movement_<mode>): every player, administrators included, gets csmc_movement_surf inside the
surf course, csmc_movement_competitive inside the two arenas and csmc_movement_bhop everywhere else. Each
player carries exactly one csmc_movement_* tag at a time.
"""
import argparse
import json
import math
import shutil
from pathlib import Path

from csmc_arena import A_HI, A_LO, CX as PVE_CX, L_X0, L_X1, L_Z0, L_Z1, fill, tellraw, text
from csmc_surf import DEFAULT_FLOOR as SURF_DEFAULT_FLOOR, NS as SURF_NS, geometry as surf_geometry
from csmc_workshop_map import bounds as workshop_bounds

NS = 'csmc_hub'
CX, CZ = -50, -5                 # plaza centre block
RADIUS = 24                      # floor disc radius
SIGN_WOOD = 'dark_oak'
DECO = 'csmc_hub_deco'           # tag on every decorative entity; build removes them first
ADMIN = 'csmc_admin'


def box_selector(box):
    x0, y0, z0, x1, y1, z1 = box
    return f'x={x0},y={y0},z={z0},dx={x1 - x0},dy={y1 - y0},dz={z1 - z0}'


def snbt(value):
    """Single-quoted SNBT string holding JSON text (escape backslashes and quotes)."""
    return "'" + value.replace('\\', '\\\\').replace("'", "\\'") + "'"


def disc(r, y1, y2, block):
    rows = []
    for dz in range(-int(r), int(r) + 1):
        v = r * r - dz * dz
        if v >= 0:
            h = int(math.sqrt(v) + 0.5)
            rows.append(fill(CX - h, y1, CZ + dz, CX + h, y2, CZ + dz, block))
    return rows


def ring(r_out, r_in, y1, y2, block):
    rows = []
    for dz in range(-int(r_out), int(r_out) + 1):
        vo = r_out * r_out - dz * dz
        if vo < 0:
            continue
        ho = int(math.sqrt(vo) + 0.5)
        vi = r_in * r_in - dz * dz
        if vi < 0:
            rows.append(fill(CX - ho, y1, CZ + dz, CX + ho, y2, CZ + dz, block))
            continue
        hi = int(math.sqrt(vi) + 0.5)
        if hi < ho:
            rows.append(fill(CX - ho, y1, CZ + dz, CX - hi - 1, y2, CZ + dz, block))
            rows.append(fill(CX + hi + 1, y1, CZ + dz, CX + ho, y2, CZ + dz, block))
    return rows


def wall_sign(x, y, z, facing, lines, wood=SIGN_WOOD):
    padded = list(lines) + [''] * (4 - len(lines))
    msgs = ','.join(snbt(text(t, **s)) for t, s in padded)
    return (f'setblock {x} {y} {z} minecraft:{wood}_wall_sign[facing={facing}]'
            f'{{front_text:{{messages:[{msgs}],color:"white",has_glowing_text:1b}},is_waxed:1b}}')


def standing_sign(x, y, z, rotation, lines, wood=SIGN_WOOD):
    padded = list(lines) + [''] * (4 - len(lines))
    msgs = ','.join(snbt(text(t, **s)) for t, s in padded)
    return (f'setblock {x} {y} {z} minecraft:{wood}_sign[rotation={rotation}]'
            f'{{front_text:{{messages:[{msgs}],color:"white",has_glowing_text:1b}},is_waxed:1b}}')


def centre(v):
    """Block coordinate -> centre of that block (appending '.5' to a negative integer would be wrong)."""
    return v + 0.5


def label(x, y, z, title, subtitle, color, scale):
    """Billboarded text display; entities cannot be picked up or broken in adventure mode."""
    parts = [{'text': title, 'color': color, 'bold': True}]
    if subtitle:
        parts.append({'text': '\n' + subtitle, 'color': 'gray', 'bold': False})
    return (f'summon minecraft:text_display {x} {y} {z} {{Tags:["{DECO}"],billboard:"center",'
            f'text:{snbt(json.dumps(parts, separators=(",", ":")))},'
            'transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],'
            f'translation:[0f,0f,0f],scale:[{scale}f,{scale}f,{scale}f]}}}}')


def build(floor, pve_floor, ws_floor, surf_floor):
    # ---- geometry shared by build and tick ---------------------------------------------------
    hub = (CX - RADIUS, floor - 6, CZ - RADIUS, CX + RADIUS, floor + 20, CZ + RADIUS)
    spawn_block = (CX, floor + 1, CZ + 14)
    hub_spawn = f'{centre(spawn_block[0])} {spawn_block[1]} {centre(spawn_block[2])} 180 0'
    hub_spawnpoint = f'{spawn_block[0]} {spawn_block[1]} {spawn_block[2]} 180'

    pve_arena = (A_LO, pve_floor - 2, A_LO, A_HI, pve_floor + 15, A_HI)
    pve_lobby = (L_X0, pve_floor - 2, L_Z0, L_X1, pve_floor + 9, L_Z1)
    pve_spawn = f'{PVE_CX}.5 {pve_floor + 1} 133.5 180 0'
    pve_spawnpoint = f'{PVE_CX} {pve_floor + 1} 133 180'
    pve_return = (94, pve_floor + 1, 132)

    ws_map = workshop_bounds(ws_floor)
    ws_lobby = (38, ws_floor - 1, -34, 66, ws_floor + 14, -6)    # lobby is open-sky below a barrier ring
    ws_spawn = f'48 {ws_floor + 1} -20 90 0'
    ws_spawnpoint = f'48 {ws_floor + 1} -20 90'
    ws_return = (52, ws_floor + 1, -9)

    surf = surf_geometry(surf_floor)
    surf_box = surf['box']
    surf_start_return = surf['hub_plate_start']
    surf_finish_return = surf['hub_plate_finish']

    # Portal row on the north side: three gates 7 wide with 2-block gaps (x -62..-56, -53..-47, -44..-38).
    pve_plate = (CX - 9, floor + 1, CZ - 13)
    surf_plate = (CX, floor + 1, CZ - 13)
    ws_plate = (CX + 9, floor + 1, CZ - 13)

    f: dict[str, list[str]] = {}

    # ---- setup: server-wide rules (runs after the other packs' setup) ------------------------
    f['setup'] = [
        'gamerule keepInventory true',
        'gamerule doImmediateRespawn true',
        'gamerule csmcMovement competitive',
        'gamerule mobGriefing false',
        'gamerule doFireTick false',
        'gamerule doMobSpawning false',
        'gamerule doTraderSpawning false',
        'gamerule doPatrolSpawning false',
        'gamerule doDaylightCycle false',
        'time set noon',
        'gamerule doWeatherCycle false',
        'weather clear',
        'gamerule commandBlockOutput false',
        'gamerule spawnRadius 0',
        'gamerule csmcC4Breaking false',
        'gamerule csmcBlockBreaking false',
        'scoreboard objectives setdisplay sidebar',
        f'setworldspawn {spawn_block[0]} {spawn_block[1]} {spawn_block[2]} 180',
        f'forceload add {hub[0]} {hub[2]} {hub[3]} {hub[5]}',
        # Other packs check this to know they run inside the hub world (Workshop leave keeps adventure).
        'scoreboard objectives add csmc_hub_state dummy',
        'scoreboard players set #hub csmc_hub_state 1',
    ]
    f['build_all'] = [f'function {NS}:build']

    # ---- build: the hub ---------------------------------------------------------------------
    b = [f'kill @e[type=minecraft:text_display,tag={DECO}]']
    for y in range(hub[1], hub[4] + 1, 9):                      # <= 32768 blocks per fill
        b.append(fill(hub[0], y, hub[2], hub[3], min(y + 8, hub[4]), hub[5], 'air'))
    # Underside: a tapering floating island, so the plate reads as built rather than cut out.
    for depth, (r, block) in enumerate(((RADIUS, 'gray_concrete'), (RADIUS - 1, 'polished_deepslate'),
                                         (RADIUS - 3, 'deepslate_bricks'), (RADIUS - 6, 'deepslate_tiles'),
                                         (RADIUS - 9, 'polished_blackstone'), (RADIUS - 13, 'blackstone')), 1):
        b += disc(r, floor - depth, floor - depth, block)
    b += disc(RADIUS, floor, floor, 'light_gray_concrete')
    b += ring(RADIUS, 22.5, floor, floor, 'black_concrete')
    b += ring(22.5, 20.5, floor, floor, 'gray_concrete')
    # Outer wall, a rim of beacons, a barrier cage (invisible, unbreakable) above it, no roof over the plaza.
    b += ring(RADIUS, 22.5, floor + 1, floor + 2, 'polished_blackstone_bricks')
    b += ring(RADIUS, 22.5, floor + 3, floor + 3, 'polished_blackstone_brick_slab[type=bottom]')
    b += ring(RADIUS, 22.5, floor + 4, floor + 13, 'barrier')
    for k in range(16):
        a = math.radians(22.5 * k + 11.25)
        b.append(f'setblock {CX + round(23.2 * math.cos(a))} {floor + 2} {CZ + round(23.2 * math.sin(a))} minecraft:sea_lantern')
    # Scope-style ring and crosshair at the centre.
    b += ring(11.6, 10.4, floor, floor, 'gray_concrete')
    for x0, z0, x1, z1 in ((CX - 9, CZ, CX - 3, CZ), (CX + 3, CZ, CX + 9, CZ),
                           (CX, CZ - 9, CX, CZ - 3), (CX, CZ + 3, CX, CZ + 9), (CX, CZ, CX, CZ)):
        b.append(fill(x0, floor, z0, x1, floor, z1, 'lime_concrete'))
    # Spawn dais.
    b += [fill(CX - 2, floor, CZ + 12, CX + 2, floor, CZ + 16, 'gray_concrete'),
          fill(CX - 1, floor, CZ + 13, CX + 1, floor, CZ + 15, 'white_concrete'),
          f'setblock {CX} {floor} {CZ + 14} minecraft:lime_concrete']
    # Floor lights on a circle, lamp posts further out; neither near the gates.
    for k in range(12):
        a = math.radians(30 * k + 15)
        x, z = CX + round(15.5 * math.cos(a)), CZ + round(15.5 * math.sin(a))
        if not any(abs(x - px) <= 3 and abs(z - pz) <= 3 for px, _, pz in (pve_plate, surf_plate, ws_plate)):
            b.append(f'setblock {x} {floor} {z} minecraft:sea_lantern')
    # Lamp posts on radius 19; the north one (CX, CZ - 19) stands just behind the surf gate frame, off its pad.
    for k in range(8):
        a = math.radians(45 * k)
        x, z = CX + round(19 * math.cos(a)), CZ + round(19 * math.sin(a))
        b += [f'setblock {x} {floor + 1} {z} minecraft:polished_blackstone',
              fill(x, floor + 2, z, x, floor + 4, z, 'polished_blackstone_wall'),
              f'setblock {x} {floor + 5} {z} minecraft:sea_lantern']
    # Decorative crates (plain planks: nothing to open, break or loot).
    for x, z in ((CX - 17, CZ + 8), (CX + 16, CZ + 8), (CX - 15, CZ + 12), (CX + 14, CZ + 12)):
        b += [fill(x, floor + 1, z, x + 1, floor + 2, z + 1, 'oak_planks'),
              fill(x, floor + 3, z, x, floor + 3, z, 'spruce_planks'),
              fill(x, floor + 1, z, x, floor + 2, z, 'stripped_oak_log[axis=y]'),
              fill(x + 1, floor + 1, z + 1, x + 1, floor + 2, z + 1, 'stripped_oak_log[axis=y]')]
    b.append(label(centre(CX), floor + 8, centre(CZ), 'CSMC HUB', 'Step on a glowing plate', 'gold', 3))

    gates = (
        (pve_plate, 'orange', 'sea_lantern', 'PvE ARENA', 'gold', 'Mob waves, 60 s', 'Kills earn cases',
         ('PvE ARENA', 'vs. mob waves', 'Step on the', 'glowing plate')),
        (surf_plate, 'purple', 'sea_lantern', 'SURF', 'light_purple', 'Five-stage course', 'Beat your best time',
         ('SURF', 'Ramps and strafes', 'Step on the', 'glowing plate')),
        (ws_plate, 'light_blue', 'sea_lantern', '1v1 WORKSHOP', 'aqua', 'am_grass2 duel + shop', 'Win to earn nuggets',
         ('1v1 WORKSHOP', 'Duel and trade', 'Step on the', 'glowing plate')),
    )
    for (px, py, pz), color, backing, title, title_color, tip1, tip2, sign_lines in gates:
        sx = px
        body = {'orange': 'orange_concrete', 'purple': 'purple_concrete', 'light_blue': 'light_blue_concrete'}[color]
        glass = f'{color}_stained_glass'
        gz0, gz1 = CZ - 18, CZ - 17
        b += [
            # Pad, then plate on a lit base.
            fill(sx - 2, floor, pz - 2, sx + 2, floor, pz + 2, body),
            fill(sx - 1, floor, pz - 1, sx + 1, floor, pz + 1, 'white_concrete'),
            f'setblock {sx} {floor} {pz} minecraft:sea_lantern',
            f'setblock {sx} {py} {pz} minecraft:stone_pressure_plate',
            # Frame: two pillars, lintel with trim, glowing glass screen backed by lanterns.
            fill(sx - 3, floor + 1, gz0, sx - 3, floor + 7, gz1, body),
            fill(sx + 3, floor + 1, gz0, sx + 3, floor + 7, gz1, body),
            fill(sx - 3, floor + 7, gz0, sx + 3, floor + 7, gz1, body),
            fill(sx - 3, floor + 8, gz0, sx + 3, floor + 8, gz1, 'black_concrete'),
            fill(sx - 2, floor + 1, gz1, sx + 2, floor + 6, gz1, glass),
            fill(sx - 2, floor + 1, gz0, sx + 2, floor + 6, gz0, backing),
            wall_sign(sx, floor + 7, gz1 + 1, 'south',
                      [(sign_lines[0], {'bold': True}), (sign_lines[1], {}), (sign_lines[2], {}), (sign_lines[3], {})]),
            wall_sign(sx - 3, floor + 3, gz1 + 1, 'south',
                      [(title, {'bold': True}), (tip1, {}), (tip2, {}), ('', {})]),
            wall_sign(sx + 3, floor + 3, gz1 + 1, 'south',
                      [(title, {'bold': True}), ('Step on the', {}), ('glowing plate', {}), ('to teleport', {})]),
            label(centre(sx), floor + 10, gz1 + 1.5, title, tip1, title_color, 2.5),
        ]
    f['build'] = b
    # Re-assert the hub world spawn: re-running workshop:setup would otherwise move it to the Workshop lobby.
    f['build'] += [f'function {NS}:build_returns', f'function {NS}:fix_villagers',
                   f'setworldspawn {spawn_block[0]} {spawn_block[1]} {spawn_block[2]} 180']

    # ---- return plates inside the two lobbies and on the surf start platform and finish pad -----
    rx, ry, rz = pve_return
    wx, wy, wz = ws_return
    sx, sy, sz = surf_start_return
    ex, ey, ez = surf_finish_return
    f['build_returns'] = [
        # PvE lobby: the rainbow floor needs a white pad; the sign stands beside the plate, not in front of it.
        fill(rx - 1, ry - 1, rz - 1, rx + 1, ry - 1, rz + 1, 'white_concrete'),
        f'setblock {rx} {ry - 1} {rz} minecraft:sea_lantern',
        f'setblock {rx} {ry} {rz} minecraft:stone_pressure_plate',
        standing_sign(rx + 2, ry, rz + 2, 12, [('BACK TO HUB', {'bold': True}), ('Step on the', {}), ('glowing plate', {}), ('', {})]),
        # Workshop lobby: white quartz floor, so a lime pad.
        fill(wx - 1, wy - 1, wz - 1, wx + 1, wy - 1, wz + 1, 'lime_concrete'),
        f'setblock {wx} {wy - 1} {wz} minecraft:sea_lantern',
        f'setblock {wx} {wy} {wz} minecraft:stone_pressure_plate',
        standing_sign(wx + 2, wy, wz - 2, 8, [('BACK TO HUB', {'bold': True}), ('Step on the', {}), ('glowing plate', {}), ('', {})]),
        # Surf start platform (white floor): lime pad, sign faces west towards the course spawn.
        fill(sx - 1, sy - 1, sz - 1, sx + 1, sy - 1, sz + 1, 'lime_concrete'),
        f'setblock {sx} {sy - 1} {sz} minecraft:sea_lantern',
        f'setblock {sx} {sy} {sz} minecraft:stone_pressure_plate',
        standing_sign(sx + 2, sy, sz + 2, 4, [('BACK TO HUB', {'bold': True}), ('Step on the', {}), ('glowing plate', {}), ('', {})]),
        # Surf finish pad: lime pad, sign faces north towards the arriving riders.
        fill(ex - 1, ey - 1, ez - 1, ex + 1, ey - 1, ez + 1, 'lime_concrete'),
        f'setblock {ex} {ey - 1} {ez} minecraft:sea_lantern',
        f'setblock {ex} {ey} {ez} minecraft:stone_pressure_plate',
        standing_sign(ex + 2, ey, ez - 2, 8, [('BACK TO HUB', {'bold': True}), ('Step on the', {}), ('glowing plate', {}), ('', {})]),
        # Labels belong to the hub pack, so rebuilding the hub never duplicates them.
        label(centre(rx), ry + 2.5, centre(rz), 'BACK TO HUB', None, 'green', 2),
        label(centre(wx), wy + 2.5, centre(wz), 'BACK TO HUB', None, 'green', 2),
        label(centre(sx), sy + 2.5, centre(sz), 'BACK TO HUB', None, 'green', 2),
        label(centre(ex), ey + 2.5, centre(ez), 'BACK TO HUB', None, 'green', 2),
    ]

    # ---- villagers: every shop/visitor villager is permanent, silent, inert and unkillable ----
    f['fix_villagers'] = [f'execute as @e[type=minecraft:villager,tag=!csmc_vfixed] run function {NS}:fix_villager']
    f['fix_villager'] = [
        'data merge entity @s {Invulnerable:1b,PersistenceRequired:1b,NoAI:1b,Silent:1b}',
        'tag @s add csmc_vfixed',
    ]

    # ---- plates -----------------------------------------------------------------------------
    def plate_check(plate, function):
        px, py, pz = plate
        return (f'execute as @a[gamemode=!spectator,x={px},y={py},z={pz},dx=0,dy=1,dz=0] at @s '
                f'if block {px} {py} {pz} minecraft:stone_pressure_plate[powered=true] run function {NS}:{function}')

    def arrive(spawn, spawnpoint, name, color):
        return [
            'playsound minecraft:entity.enderman.teleport master @s ~ ~ ~ 0.6 1.2',
            f'tp @s {spawn}',
            f'spawnpoint @s {spawnpoint}',
            'playsound minecraft:entity.enderman.teleport master @s ~ ~ ~ 0.6 1.2',
            'title @s actionbar ' + text(name, color=color, bold=True),
        ]
    f['to_pve'] = arrive(pve_spawn, pve_spawnpoint, 'PvE Arena lobby', 'gold')
    f['to_workshop'] = arrive(ws_spawn, ws_spawnpoint, '1v1 Workshop lobby', 'aqua')
    # The surf pack owns its start: to_start resets the run, teleports and sets the spawnpoint.
    f['to_surf'] = [
        'playsound minecraft:entity.enderman.teleport master @s ~ ~ ~ 0.6 1.2',
        f'function {SURF_NS}:to_start',
        'title @s actionbar ' + text('Surf course', color='light_purple', bold=True),
    ]
    f['to_hub'] = arrive(hub_spawn, hub_spawnpoint, 'Hub', 'green')
    f['return_to_hub'] = [f'tp @s {hub_spawn}', f'spawnpoint @s {hub_spawnpoint}',
                          tellraw('@s', {'text': 'Returned to the hub: you left the playable area.', 'color': 'yellow'})]

    # ---- policing: every tick ---------------------------------------------------------------
    boxes = (hub, pve_arena, pve_lobby, ws_map, ws_lobby, surf_box)
    safe_boxes = (hub, pve_lobby, ws_lobby, surf_box)
    outside = ' '.join(f'unless entity @s[{box_selector(box)}]' for box in boxes)
    not_safe = ' '.join(f'unless entity @s[{box_selector(box)}]' for box in safe_boxes)
    f['safe'] = [
        'effect give @s minecraft:resistance 1 4 true',
        'effect give @s minecraft:saturation 1 0 true',
        'tag @s add csmc_safe',
    ]
    f['leave_safe'] = ['effect clear @s minecraft:resistance', 'tag @s remove csmc_safe']
    # Movement mode per player, administrators included (csmc_movement_<mode> tag overrides the gamerule):
    # surf on the surf course, competitive inside the two arenas, auto-bhop everywhere else. The tag must be the
    # only csmc_movement_* tag, because the mod picks the first in off, competitive, bhop, surf order; set_<mode>
    # strips every other one. Re-tagging only happens on a change so the client is not resynced.
    all_modes = ('off', 'competitive', 'bhop', 'surf')
    arenas = (pve_arena, ws_map)
    not_bhop = ' '.join(f'unless entity @s[{box_selector(box)}]' for box in (*arenas, surf_box))
    for mode in ('bhop', 'competitive', 'surf'):
        others = ','.join(f'tag=!csmc_movement_{m}' for m in all_modes if m != mode)
        f[f'set_{mode}'] = [f'tag @s remove csmc_movement_{m}' for m in all_modes if m != mode]
        f[f'set_{mode}'].append(f'tag @s add csmc_movement_{mode}')
        f[f'check_{mode}'] = [f'execute unless entity @s[tag=csmc_movement_{mode},{others}] run function {NS}:set_{mode}']
    f['tick'] = [
        # Spectators are left alone (Workshop restores spectator mode for players who were spectating).
        f'execute as @a[tag=!{ADMIN},gamemode=!adventure] unless entity @s[gamemode=spectator] run gamemode adventure',
        # Void world: anything outside the union of the four areas (which includes everything below y 40) goes home.
        f'execute as @a[tag=!{ADMIN}] at @s {outside} run function {NS}:return_to_hub',
        f'execute as @a[tag=csmc_safe] at @s {not_safe} run function {NS}:leave_safe',
        *[f'execute as @a[{box_selector(box)}] run function {NS}:safe' for box in safe_boxes],
        # Surf on the course, competitive (no auto-bhop) inside the arenas, auto-bhop in the hub, lobbies and transit.
        f'execute as @a[{box_selector(surf_box)}] run function {NS}:check_surf',
        *[f'execute as @a[{box_selector(box)}] run function {NS}:check_competitive' for box in arenas],
        f'execute as @a at @s {not_bhop} run function {NS}:check_bhop',
        f'kill @e[type=minecraft:item,{box_selector(hub)}]',
        f'function {NS}:fix_villagers',
        plate_check(pve_plate, 'to_pve'),
        plate_check(surf_plate, 'to_surf'),
        plate_check(ws_plate, 'to_workshop'),
        plate_check(pve_return, 'to_hub'),
        plate_check(ws_return, 'to_hub'),
        plate_check(surf_start_return, 'to_hub'),
        plate_check(surf_finish_return, 'to_hub'),
    ]
    return f


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--world', required=True, type=Path, help='World directory of the production server')
    parser.add_argument('--floor', type=int, default=100, help='Y of the hub floor (hub box is floor-6..floor+20)')
    parser.add_argument('--pve-floor', type=int, default=232, help='--floor given to csmc_arena.py')
    parser.add_argument('--workshop-floor', type=int, default=64, help='--floor given to csmc_workshop_arena.py')
    parser.add_argument('--surf-floor', type=int, default=SURF_DEFAULT_FLOOR, help='--floor given to csmc_surf.py')
    args = parser.parse_args()
    pack = args.world.resolve() / 'datapacks' / NS
    if pack.exists():
        shutil.rmtree(pack)
    functions = pack / 'data' / NS / 'function'
    functions.mkdir(parents=True)
    (pack / 'pack.mcmeta').write_text(json.dumps(
        {'pack': {'pack_format': 48, 'description': 'CSMC hub, portals and world policing'}}, indent=2) + '\n')
    for name, commands in build(args.floor, args.pve_floor, args.workshop_floor, args.surf_floor).items():
        (functions / f'{name}.mcfunction').write_text('\n'.join(commands) + '\n')
    tick = pack / 'data' / 'minecraft' / 'tags' / 'function'
    tick.mkdir(parents=True)
    (tick / 'tick.json').write_text(json.dumps({'values': [f'{NS}:tick']}, indent=2) + '\n')
    print(f'wrote {pack}')


if __name__ == '__main__':
    main()
