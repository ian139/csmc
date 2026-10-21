#!/usr/bin/env python3
"""Generate the csmc_surf data pack: an original five-stage surf course with checkpoints and a timer.

python3 tools/csmc_surf.py --world /abs/path/to/world [--floor 190]

The course is built with the mod's own builder commands (/csmc surf ramp|ridge, see docs/surf.md) run from
functions, so the world needs the CSMC mod on the server. It lives in the hub world (docs/hub.md) at
x -300..-120, z -6..126, y floor-40..floor+40 and overlaps neither the hub, the PvE arena nor the Workshop.

Console order (same as the other packs; the hub pack is installed and run LAST):
  /reload
  /function csmc_surf:setup      (scoreboards + forceload of the course)
  <wait for the forceloaded chunks>
  /function csmc_surf:build_all  (pads, walls, signs and every ramp)

The course (floor = Y of the start platform; every pad is 3 blocks lower than the one before):
  start platform (north)  ->  stage 1 -> checkpoint 1 (south) -> stage 2 -> checkpoint 2 (north)
  -> stage 3 -> checkpoint 3 (south) -> stage 4 -> checkpoint 4 (north) -> stage 5 -> finish (south)
Each stage is an 80-block ramp, ridge or valley between two pads. The pad beyond a stage is its checkpoint
(or the finish); touching it records the checkpoint. Falling below the stage's catch plane teleports you back
to your last checkpoint (the start platform before the first one, which also resets the run).

The pack owns the run state and timer (scoreboards surf_*; personal bests persist with the world). The hub pack
owns the Back-to-hub plates on the start platform and the finish pad, the movement tag, the safe zone and the
bounds check (tools/csmc_hub.py imports geometry() below), exactly like the two arena lobbies.
"""
import argparse
import json
import shutil
from pathlib import Path

from csmc_arena import fill, tellraw, text

NS = 'csmc_surf'
DECO = 'csmc_surf_deco'
SIGN_WOOD = 'dark_oak'
DEFAULT_FLOOR = 190              # Y of the start platform's walking surface

BOX_X0, BOX_X1 = -300, -120      # course box (inclusive); Y is floor-40 .. floor+40
BOX_Z0, BOX_Z1 = -6, 126
STEP = 3                         # each pad lies this much lower than the previous one
LANE_Z0, LANE_Z1 = 20, 99        # meet the landing pads without overwriting them
NORTH_Z = (0, 19)                # pad z span at the north end (start, checkpoint 2, 4)
SOUTH_Z = (100, 119)             # pad z span at the south end (checkpoint 1, 3, finish)
CP_COUNT = 5                     # checkpoint index of the finish pad

# Pads 0..5: x span and end of the course they sit at. Pad 0 is the start platform, pad 5 the finish.
PADS = (
    (-296, -266, 'N'), (-294, -238, 'S'), (-258, -202, 'N'),
    (-222, -166, 'S'), (-186, -128, 'N'), (-154, -124, 'S'),
)
# Stage k leads from pad k to pad k+1. cx = centre of its flight channel; w = ramp width in cells, h = height.
STAGES = (
    dict(name='WARM-UP', blurb='one ramp, 63 deg', kind='ramp', side='E', angle=63.43, h=16, w=8, cx=-282, color='cyan'),
    dict(name='LEAN-TO', blurb='steeper ramp, 60 deg', kind='ramp', side='W', angle=60, h=16, w=10, cx=-246, color='lime'),
    dict(name='THE RIDGE', blurb='pick a side, 63 deg', kind='ridge', angle=63.43, h=16, w=8, cx=-210, color='yellow'),
    dict(name='THE VALLEY', blurb='two walls, 70 deg', kind='valley', angle=70, h=16, w=6, cx=-174, color='orange'),
    dict(name='SKY WALL', blurb='tall and steep, 70 deg', kind='ramp', side='W', angle=70, h=20, w=8, cx=-138, color='red'),
)
FINISH_COLOR = 'yellow'


def geometry(floor=DEFAULT_FLOOR):
    """Everything the hub pack needs: box, start platform, spawn and plate positions (all inclusive blocks)."""
    ys = [floor - STEP * k for k in range(len(PADS))]
    sx0, sx1, _ = PADS[0]
    fx0, fx1, _ = PADS[-1]
    return {
        'ys': ys,
        'box': (BOX_X0, floor - 40, BOX_Z0, BOX_X1, floor + 40, BOX_Z1),
        'start_box': (sx0, ys[0] - 2, NORTH_Z[0], sx1, ys[0] + 40, NORTH_Z[1]),
        'spawn': f'{STAGES[0]["cx"] + 0.5} {ys[0]} 8.5 0 0',
        'spawnpoint': f'{STAGES[0]["cx"]} {ys[0]} 8 0',
        'hub_plate_start': (sx1 - 4, ys[0], NORTH_Z[0] + 4),
        'hub_plate_finish': (fx1 - 3, ys[-1], SOUTH_Z[1] - 4),
    }


def snbt(value):
    return "'" + value.replace('\\', '\\\\').replace("'", "\\'") + "'"


def wall_sign(x, y, z, facing, lines):
    padded = list(lines) + [''] * (4 - len(lines))
    msgs = ','.join(snbt(text(t, **s)) for t, s in padded)
    return (f'setblock {x} {y} {z} minecraft:{SIGN_WOOD}_wall_sign[facing={facing}]'
            f'{{front_text:{{messages:[{msgs}],color:"white",has_glowing_text:1b}},is_waxed:1b}}')


def label(x, y, z, title, subtitle, color, scale):
    """Billboarded text display (cannot be picked up or broken in adventure mode)."""
    parts = [{'text': title, 'color': color, 'bold': True}]
    if subtitle:
        parts.append({'text': '\n' + subtitle, 'color': 'gray', 'bold': False})
    return (f'summon minecraft:text_display {x} {y} {z} {{Tags:["{DECO}"],billboard:"center",'
            f'text:{snbt(json.dumps(parts, separators=(",", ":")))},'
            'transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],'
            f'translation:[0f,0f,0f],scale:[{scale}f,{scale}f,{scale}f]}}}}')


def box_selector(box):
    x0, y0, z0, x1, y1, z1 = box
    return f'x={x0},y={y0},z={z0},dx={x1 - x0},dy={y1 - y0},dz={z1 - z0}'


def clear(x0, y0, z0, x1, y1, z1):
    """fill ... air, sliced along Y so that no single fill exceeds 32768 blocks."""
    rows, per = [], (x1 - x0 + 1) * (z1 - z0 + 1)
    step = max(1, 32768 // per)
    for y in range(y0, y1 + 1, step):
        rows.append(fill(x0, y, z0, x1, min(y + step - 1, y1), z1, 'air'))
    return rows


def pad_z(end):
    return NORTH_Z if end == 'N' else SOUTH_Z


def pad_spawn(k, ys):
    """Where a player who reached checkpoint k stands: at the start of the next stage's channel, facing along it."""
    if k == 0:
        return geometry(ys[0])['spawn']
    stage = STAGES[min(k, len(STAGES) - 1)]
    cx = stage['cx']
    if stage['kind'] == 'ridge':
        cx -= 6                        # enter the west flank below the crest
    if k == CP_COUNT:                  # finish pad: middle of the landing zone, facing the course
        x0, x1, _ = PADS[k]
        return f'{(x0 + x1) // 2 + 0.5} {ys[k]} 110.5 180 0'
    north = PADS[k][2] == 'N'
    return f'{cx + 0.5} {ys[k]} {12.5 if north else 106.5} {0 if north else 180} 0'


def build(floor):
    g = geometry(floor)
    ys = g['ys']
    bx0, by0, bz0, bx1, by1, bz1 = g['box']
    f: dict[str, list[str]] = {}

    # ---- setup -------------------------------------------------------------------------------------------
    f['setup'] = [
        'scoreboard objectives add surf_cp dummy',
        'scoreboard objectives add surf_state dummy',
        'scoreboard objectives add surf_time dummy',
        'scoreboard objectives add surf_best dummy',
        'scoreboard objectives add surf_sec dummy',
        'scoreboard objectives add surf_c10 dummy',
        'scoreboard objectives add surf_c1 dummy',
        'scoreboard objectives add surf_bsec dummy',
        'scoreboard objectives add surf_bc10 dummy',
        'scoreboard objectives add surf_bc1 dummy',
        'scoreboard objectives add surf_tmp dummy',
        'scoreboard players set #5 surf_tmp 5',
        'scoreboard players set #10 surf_tmp 10',
        'scoreboard players set #20 surf_tmp 20',
        f'forceload add {bx0 - 4} {bz0 - 2} {bx1 + 4} {bz1 + 2}',
    ]
    f['build_all'] = [f'function {NS}:clear', f'function {NS}:build_ramps', f'function {NS}:build_pads']
    f['clear'] = clear(bx0, by0, bz0, bx1, by1, bz1)

    # ---- the pads ----------------------------------------------------------------------------------------
    b = [f'kill @e[type=minecraft:text_display,tag={DECO}]']
    for k, (x0, x1, end) in enumerate(PADS):
        y = ys[k]
        z0, z1 = pad_z(end)
        color = 'white' if k == 0 else (FINISH_COLOR if k == CP_COUNT else STAGES[k - 1]['color'])
        far = z0 if end == 'N' else z1           # the edge with the back wall
        out = -1 if end == 'N' else 1             # direction from the pad to its wall
        w0, w1 = (far - 2, far - 1) if end == 'N' else (far + 1, far + 2)
        # Body: three layers, a tapering underside, a coloured rim and lit stripes.
        b += [fill(x0, y - 3, z0, x1, y - 2, z1, 'gray_concrete'),
              fill(x0, y - 1, z0, x1, y - 1, z1, 'white_concrete')]
        for i, (inset, block) in enumerate(((2, 'polished_deepslate'), (5, 'deepslate_tiles'), (8, 'blackstone'))):
            if x1 - x0 > 2 * inset + 2 and z1 - z0 > 2 * inset + 2:
                b.append(fill(x0 + inset, y - 4 - i, z0 + inset, x1 - inset, y - 4 - i, z1 - inset, block))
        for sx in range(x0 + 3, x1 - 2, 6):
            b.append(fill(sx, y - 1, z0 + 1, sx + 1, y - 1, z1 - 1, 'light_gray_concrete'))
        rim = f'{color}_concrete'
        b += [fill(x0, y - 1, z0, x1, y - 1, z0, rim), fill(x0, y - 1, z1, x1, y - 1, z1, rim),
              fill(x0, y - 1, z0, x0, y - 1, z1, rim), fill(x1, y - 1, z0, x1, y - 1, z1, rim)]
        for lx in list(range(x0 + 3, x1 - 1, 8)) + [x0, x1]:
            for lz in (z0, z1):
                b.append(f'setblock {lx} {y - 1} {lz} minecraft:sea_lantern')
        # Back wall: dark base, coloured band, white face, then an invisible barrier so nobody leaves the pad.
        b += [fill(x0, y, w0, x1, y + 1, w1, 'black_concrete'),
              fill(x0, y + 2, w0, x1, y + 2, w1, rim),
              fill(x0, y + 3, w0, x1, y + 6, w1, 'white_concrete'),
              fill(x0, y + 7, w0, x1, y + 7, w1, rim),
              fill(x0, y + 8, w0, x1, y + 26, w1, 'barrier')]
        for lx in range(x0 + 4, x1 - 2, 8):
            b.append(f'setblock {lx} {y + 4} {far + out} minecraft:sea_lantern')
        # Lit corner posts at the wall end.
        for px in (x0, x1):
            b += [fill(px, y, far, px, y + 4, far, 'quartz_pillar'), f'setblock {px} {y + 5} {far} minecraft:sea_lantern']
        cx = (x0 + x1) // 2
        sign_z = far                              # directly in front of the wall, with solid backing
        facing = 'south' if end == 'N' else 'north'
        if k == 0:
            continue
        if k == CP_COUNT:
            b += [wall_sign(cx - 2, y + 2, sign_z, facing, [('FINISH', {'bold': True}), ('Run complete', {}), ('Time on the', {}), ('action bar', {})]),
                  wall_sign(cx + 2, y + 2, sign_z, facing, [('RESTART', {'bold': True}), ('Plate on the left', {}), ('goes back to the', {}), ('start platform', {})]),
                  label(cx + 0.5, y + 10, sign_z - out * 1.5, 'FINISH', 'Plates: restart or hub', 'gold', 6)]
        else:
            stage = STAGES[k]
            b += [wall_sign(cx - 2, y + 2, sign_z, facing, [(f'CHECKPOINT {k}', {'bold': True}), ('Reached', {}), ('Falling returns', {}), ('you here', {})]),
                  wall_sign(cx + 2, y + 2, sign_z, facing, [(f'STAGE {k + 1}', {'bold': True}), (stage['name'], {}), (stage['blurb'], {}), ('', {})]),
                  label(cx + 0.5, y + 10, sign_z - out * 1.5, f'CHECKPOINT {k}', f'Next: {stage["name"]}',
                        {'cyan': 'aqua', 'lime': 'green', 'orange': 'gold'}.get(color, color), 5)]
    # Start platform: title, controls and the START line.
    x0, x1, _ = PADS[0]
    y = ys[0]
    cxs = STAGES[0]['cx']
    b += [fill(cxs - 6, y - 1, 17, cxs + 6, y - 1, 18, 'lime_concrete'),
          fill(cxs - 1, y - 1, 8, cxs + 1, y - 1, 15, 'lime_concrete'),
          fill(cxs - 2, y - 1, 15, cxs + 2, y - 1, 15, 'lime_concrete'),
          fill(cxs - 3, y - 1, 16, cxs + 3, y - 1, 16, 'lime_concrete'),
          f'setblock {cxs} {y - 1} 8 minecraft:sea_lantern']
    signs = (
        (x0 + 2, [('HOW TO SURF', {'bold': True}), ('Jump off the pad', {}), ('towards the', {}), ('big ramp wall', {})]),
        (x0 + 7, [('STRAFE', {'bold': True}), ('Hold A or D', {}), ('towards the', {}), ('ramp surface', {})]),
        (x0 + 12, [('TURN', {'bold': True}), ('Move the mouse', {}), ('the same way', {}), ('you strafe', {})]),
        (x0 + 17, [('THE RUN', {'bold': True}), ('Timer starts as', {}), ('you leave this', {}), ('platform', {})]),
        (x0 + 22, [('FALLING', {'bold': True}), ('Take you back', {}), ('to the last', {}), ('checkpoint', {})]),
    )
    for sx, lines in signs:
        b.append(wall_sign(sx, y + 2, 0, 'south', lines))
    b += [label(cxs + 0.5, y + 11, 3.5, 'SURF', 'Five stages. Beat your best time.', 'light_purple', 7),
          label(cxs + 0.5, y + 3.5, 18.5, 'START', 'Jump off and strafe into the ramp', 'green', 3)]
    f['build_pads'] = b

    # ---- the ramps (all through /csmc surf, each box well under 32768 cells) --------------------------------
    r = []
    for k, st in enumerate(STAGES):
        y0 = ys[k] - 12             # enter above the low flank, with room to drop onto the slope
        y1 = y0 + st['h'] - 1
        filler = f'minecraft:{st["color"]}_concrete'
        ang = st['angle']
        cx, w = st['cx'], st['w']
        east = (cx + 2, cx + 2 + w - 1)
        west = (cx - 2 - w + 1, cx - 2)
        boxes = []
        if st['kind'] == 'ramp':
            span = east if st['side'] == 'E' else west
            boxes.append(('ramp', span, 'east' if st['side'] == 'E' else 'west'))
        elif st['kind'] == 'valley':
            boxes += [('ramp', west, 'west'), ('ramp', east, 'east')]
        else:
            boxes.append(('ridge', (cx - w, cx + w - 1), 'z'))
        for shape, (xa, xb), arg in boxes:
            r.append(f'csmc surf {shape} {xa} {y0} {LANE_Z0} {xb} {y1} {LANE_Z1} {arg} {ang} {filler}')
            # A glowing keel and repeated light fins keep the floating slopes readable at night.
            r.append(fill(xa, y0 - 1, LANE_Z0, xb, y0 - 1, LANE_Z1, 'polished_deepslate'))
            for z in range(LANE_Z0, LANE_Z1 + 1, 10):
                r.append(fill(xa, y0 - 1, z, xb, y0 - 1, z, 'sea_lantern'))
    f['build_ramps'] = r

    # ---- run state ---------------------------------------------------------------------------------------
    def calc(prefix, source):
        s, a, c = f'surf_{prefix}sec', f'surf_{prefix}c10', f'surf_{prefix}c1'
        return [
            f'scoreboard players operation @s {s} = @s {source}',
            f'scoreboard players operation @s {s} /= #20 surf_tmp',
            f'scoreboard players operation @s {c} = @s {source}',
            f'scoreboard players operation @s {c} %= #20 surf_tmp',
            f'scoreboard players operation @s {c} *= #5 surf_tmp',
            f'scoreboard players operation @s {a} = @s {c}',
            f'scoreboard players operation @s {a} /= #10 surf_tmp',
            f'scoreboard players operation @s {c} %= #10 surf_tmp',
        ]

    def sc(objective, **style):
        return {'score': {'name': '@s', 'objective': objective}, **style}

    def clock(prefix, **style):
        return [sc(f'surf_{prefix}sec', **style), {'text': '.', **style},
                sc(f'surf_{prefix}c10', **style), sc(f'surf_{prefix}c1', **style), {'text': ' s', **style}]

    def actionbar(*parts):
        return 'title @s actionbar ' + json.dumps(list(parts), separators=(',', ':'))

    gray = {'color': 'gray'}
    white = {'color': 'white', 'bold': True}
    f['calc_time'] = calc('', 'surf_time')
    f['calc_best'] = calc('b', 'surf_best')
    f['ready'] = [
        'scoreboard players set @s surf_state 0',
        'scoreboard players set @s surf_cp 0',
        'scoreboard players set @s surf_time 0',
        'scoreboard players add @s surf_best 0',
        f'function {NS}:calc_best',
        f'execute if score @s surf_best matches 1.. run ' + actionbar(
            {'text': 'Best ', **gray}, *clock('b', **white), {'text': '   -   leave the platform to start', **gray}),
        f'execute if score @s surf_best matches 0 run ' + actionbar(
            {'text': 'No time yet   -   leave the platform to start', **gray}),
    ]
    f['begin'] = [
        'scoreboard players set @s surf_state 1',
        'scoreboard players set @s surf_time 0',
        'execute at @s run playsound minecraft:entity.experience_orb.pickup master @s ~ ~ ~ 1 1.5',
    ]
    f['hud_run'] = [
        f'function {NS}:calc_time',
        f'function {NS}:calc_best',
        f'execute if score @s surf_best matches 1.. run ' + actionbar(
            {'text': 'Time ', **gray}, *clock('', **white), {'text': f'   Checkpoint ', **gray}, sc('surf_cp', **white),
            {'text': f'/{CP_COUNT}   Best ', **gray}, *clock('b', **white)),
        f'execute if score @s surf_best matches 0 run ' + actionbar(
            {'text': 'Time ', **gray}, *clock('', **white), {'text': f'   Checkpoint ', **gray}, sc('surf_cp', **white),
            {'text': f'/{CP_COUNT}', **gray}),
    ]
    f['hud_done'] = [
        f'function {NS}:calc_time',
        f'function {NS}:calc_best',
        actionbar({'text': 'Finished ', 'color': 'green'}, *clock('', **white), {'text': '   Best ', **gray},
                  *clock('b', **white)),
    ]
    for k in range(1, CP_COUNT):
        f[f'reach_{k}'] = [
            f'scoreboard players set @s surf_cp {k}',
            f'function {NS}:calc_time',
            'execute at @s run playsound minecraft:block.note_block.chime master @s ~ ~ ~ 1 1.4',
            tellraw('@s', {'text': f'Checkpoint {k}/{CP_COUNT} ', 'color': 'aqua', 'bold': True},
                    {'text': 'reached at ', 'color': 'gray', 'bold': False},
                    {'score': {'name': '@s', 'objective': 'surf_sec'}, 'color': 'white', 'bold': False},
                    {'text': '.', 'color': 'white', 'bold': False},
                    {'score': {'name': '@s', 'objective': 'surf_c10'}, 'color': 'white', 'bold': False},
                    {'score': {'name': '@s', 'objective': 'surf_c1'}, 'color': 'white', 'bold': False},
                    {'text': ' s', 'color': 'white', 'bold': False}),
        ]
    f['finish'] = [
        'scoreboard players set @s surf_state 2',
        f'scoreboard players set @s surf_cp {CP_COUNT}',
        'scoreboard players set #pb surf_tmp 0',
        'execute if score @s surf_best matches 0 run scoreboard players set #pb surf_tmp 1',
        'execute if score @s surf_best matches 1.. if score @s surf_time < @s surf_best run scoreboard players set #pb surf_tmp 1',
        'execute if score #pb surf_tmp matches 1 run scoreboard players operation @s surf_best = @s surf_time',
        f'function {NS}:calc_time',
        f'function {NS}:calc_best',
        'execute at @s run playsound minecraft:ui.toast.challenge_complete master @s ~ ~ ~ 1 1',
        'title @s times 5 70 20',
        'title @s subtitle ' + json.dumps([*clock('', color='white', bold=True)], separators=(',', ':')),
        'title @s title ' + text('FINISH!', color='gold', bold=True),
        tellraw('@s', {'text': 'Surf finished in ', 'color': 'green', 'bold': True},
                *clock('', color='white', bold=True),
                {'text': '.', 'color': 'green', 'bold': True}),
        'execute if score #pb surf_tmp matches 1 run ' + tellraw(
            '@s', {'text': 'New personal best!', 'color': 'gold', 'bold': True}),
        'execute if score #pb surf_tmp matches 0 run ' + tellraw(
            '@s', {'text': 'Personal best ', 'color': 'gray'}, *clock('b', color='white')),
    ]
    f['to_checkpoint'] = [
        'execute unless score @s surf_cp matches 0.. run scoreboard players set @s surf_cp 0',
        *[f'execute if score @s surf_cp matches {k} run tp @s {pad_spawn(k, ys)}' for k in range(CP_COUNT + 1)],
        'execute at @s run playsound minecraft:entity.enderman.teleport master @s ~ ~ ~ 0.6 1.2',
        'execute unless score @s surf_cp matches 0 run ' + actionbar(
            {'text': 'You fell. Back to checkpoint ', 'color': 'yellow'}, sc('surf_cp', **white)),
    ]
    f['to_start'] = [
        'scoreboard players set @s surf_cp 0',
        f'tp @s {g["spawn"]}',
        f'spawnpoint @s {g["spawnpoint"]}',
        f'function {NS}:ready',
        'execute at @s run playsound minecraft:entity.enderman.teleport master @s ~ ~ ~ 0.6 1.2',
    ]

    # Catch planes: five vertical slabs that together cover the whole course width, each with the plane of its
    # stage. Every plane lies well below every pad inside its slab.
    lanes = []
    for st in STAGES:
        cx, w = st['cx'], st['w']
        if st['kind'] == 'ramp':
            lanes.append((cx - 6, cx + 7 + w) if st['side'] == 'E' else (cx - 7 - w, cx + 6))
        elif st['kind'] == 'valley':
            lanes.append((cx - 7 - w, cx + 7 + w))
        else:
            lanes.append((cx - 15 - 1, cx + 15))
    cuts = [bx0] + [(lanes[i][1] + lanes[i + 1][0]) // 2 for i in range(len(lanes) - 1)] + [bx1 + 1]
    catch = []
    for k in range(len(STAGES)):
        plane = ys[k] - 20
        slab = (cuts[k], by0, bz0, cuts[k + 1] - 1, plane - 1, bz1)
        catch.append(f'execute as @a[{box_selector(slab)}] at @s run function {NS}:to_checkpoint')

    # ---- tick ----------------------------------------------------------------------------------------------
    course = box_selector(g['box'])
    start = box_selector(g['start_box'])
    tick = [
        f'execute as @a[{start}] run function {NS}:ready',
        f'execute as @a[{course},scores={{surf_state=0}}] at @s unless entity @s[{start}] run function {NS}:begin',
        f'execute as @a[{course},scores={{surf_state=1}}] run scoreboard players add @s surf_time 1',
        *catch,
    ]
    for k in range(1, CP_COUNT + 1):
        x0, x1, end = PADS[k]
        z0, z1 = pad_z(end)
        pad = (x0, ys[k] - 0.2, z0, x1, ys[k] + 1, z1)
        if k == CP_COUNT:
            tick.append(f'execute as @a[{box_selector(pad)},scores={{surf_state=1,surf_cp={k - 1}}}] run function {NS}:finish')
        else:
            tick.append(f'execute as @a[{box_selector(pad)},scores={{surf_state=1,surf_cp={k - 1}}}] run function {NS}:reach_{k}')
    tick += [
        f'execute as @a[{course},scores={{surf_state=1}}] run function {NS}:hud_run',
        f'execute as @a[{course},scores={{surf_state=2}}] run function {NS}:hud_done',
    ]
    # Restart plate on the finish pad (the hub pack owns the Back-to-hub plates).
    rx, ry, rz = restart_plate(ys)
    f['build_pads'] += [
        fill(rx - 1, ry - 1, rz - 1, rx + 1, ry - 1, rz + 1, 'light_blue_concrete'),
        f'setblock {rx} {ry - 1} {rz} minecraft:sea_lantern',
        f'setblock {rx} {ry} {rz} minecraft:stone_pressure_plate',
        label(rx + 0.5, ry + 2.5, rz + 0.5, 'RESTART', 'Back to the start platform', 'aqua', 2),
    ]
    tick.append(f'execute as @a[gamemode=!spectator,x={rx},y={ry},z={rz},dx=0,dy=1,dz=0] at @s '
                f'if block {rx} {ry} {rz} minecraft:stone_pressure_plate[powered=true] run function {NS}:to_start')
    f['tick'] = tick
    return f


def restart_plate(ys):
    fx0, fx1, _ = PADS[-1]
    return fx0 + 3, ys[-1], SOUTH_Z[1] - 4


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--world', required=True, type=Path, help='World directory of the server')
    parser.add_argument('--floor', type=int, default=DEFAULT_FLOOR,
                        help='Y of the start platform (box is floor-40..floor+40); csmc_hub.py needs the same value')
    args = parser.parse_args()
    pack = args.world.resolve() / 'datapacks' / NS
    if pack.exists():
        shutil.rmtree(pack)
    functions = pack / 'data' / NS / 'function'
    functions.mkdir(parents=True)
    (pack / 'pack.mcmeta').write_text(json.dumps(
        {'pack': {'pack_format': 48, 'description': 'CSMC surf course: stages, checkpoints and timer'}}, indent=2) + '\n')
    for name, commands in build(args.floor).items():
        (functions / f'{name}.mcfunction').write_text('\n'.join(commands) + '\n')
    tick = pack / 'data' / 'minecraft' / 'tags' / 'function'
    tick.mkdir(parents=True)
    (tick / 'tick.json').write_text(json.dumps({'values': [f'{NS}:tick']}, indent=2) + '\n')
    print(f'wrote {pack}')


if __name__ == '__main__':
    main()
