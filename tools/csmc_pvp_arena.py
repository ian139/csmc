#!/usr/bin/env python3
"""Generate the csmc_pvp data pack: a mirrored 1v1 deathmatch arena + lobby, driven by command blocks.

The arena sits opposite the PvE arena (tools/csmc_arena.py), mirrored across x=0. Two players join a
team from the lobby; first to 5 kills wins. A killed player respawns at once on their own side.
Base blocks (bedrock, obsidian) are unbreakable. Cover is a mix: obsidian hiding spots are bulletproof
and permanent, while planks, glass, wool and stone bricks are shot away by the guns' block damage and
come back whenever the arena is rebuilt (before every match, and via the REBUILD button).

Usage: python3 tools/csmc_pvp_arena.py [--world mod/run-server/csmc-test] [--floor 232]
Then on the server: /reload, /function csmc_pvp:setup, wait a few seconds for the forceloaded chunks,
then /function csmc_pvp:build_all. The server needs `enable-command-block=true`.
"""
import argparse
import json
import re
import shutil
from pathlib import Path

from csmc_arena import BED_COLORS, BIOMES, PROFESSIONS, RAINBOW, command_block, fill, sign, text, tellraw

NS = 'csmc_pvp'
WIN = 5                                   # kills needed
CX, CZ = -100, 100                        # arena centre
A_LO, A_HI = -121, -79                    # arena bedrock shell (x); z is 79..121
L_X0, L_X1, L_Z0, L_Z1 = -111, -89, 122, 140  # lobby bedrock shell
BLUE_SPAWN = (-115.5, 100.5, -90)         # x, z, yaw (facing east)
RED_SPAWN = (-84.5, 100.5, 90)            # facing west


def X(u):
    return CX + u


def Z(v):
    return CZ + v


def mirror(u, v):
    """The four points symmetric about both axes."""
    return {(u, v), (-u, v), (u, -v), (-u, -v)}


def score(name, color):
    return {'score': {'name': name, 'objective': 'pvp_state'}, 'color': color, 'bold': True}


def comp(*parts):
    return json.dumps(list(parts), separators=(',', ':'))


def build(floor):
    lo, hi, lobby_hi = floor - 2, floor + 15, floor + 9
    y = floor + 1                          # standing height
    lobby_spawn = f'{CX}.5 {y} 133.5 180 0'
    f: dict[str, list[str]] = {}

    # Match item IDs only: never rewrite retained stacks or their skin/name/components.
    slots = ([f'hotbar.{i}' for i in range(9)] + [f'inventory.{i}' for i in range(27)]
             + ['armor.head', 'armor.chest', 'armor.legs', 'armor.feet', 'weapon.offhand'])
    f['loadout'] = [
        f'execute unless items entity @s {slot} #{NS}:kept_weapons run item replace entity @s {slot} with minecraft:air'
        for slot in slots
    ]
    for item in ('ak47', 'm4a1', 'awp'):
        checks = ' '.join(f'unless items entity @s {slot} csmc:{item}'
                          for slot in ('hotbar.*', 'inventory.*', 'weapon.offhand', 'armor.*'))
        f['loadout'].append(f'execute {checks} run give @s csmc:{item}')
    checks = ' '.join(f'unless items entity @s {slot} #{NS}:knives'
                      for slot in ('hotbar.*', 'inventory.*', 'weapon.offhand', 'armor.*'))
    f['loadout'] += [f'execute {checks} run give @s csmc:knife',
                     'give @s csmc:rifle_ammo 180', 'give @s csmc:pistol_ammo 60',
                     'give @s csmc:sniper_ammo 30', 'give @s csmc:shotgun_shells 32']
    for slot, item in (('head', 'helmet'), ('chest', 'chestplate'), ('legs', 'leggings'), ('feet', 'boots')):
        f['loadout'].append(
            f'item replace entity @s armor.{slot} with minecraft:chainmail_{item}'
            '[minecraft:enchantments={levels:{"minecraft:unbreaking":3,"minecraft:blast_protection":4}}]')
    f['loadout'].append('give @s minecraft:golden_apple 5')
    f['clear_hostiles'] = [
        f'kill @e[type=#{NS}:dangerous_mobs,tag=!pvp_visitor,tag=!pvp_lobby,'
        f'x={x0},y={lo},z={z0},dx={x1 - x0},dy={top - lo},dz={z1 - z0}]'
        for x0, z0, x1, z1, top in ((A_LO, 79, A_HI, 121, hi), (L_X0, L_Z0, L_X1, L_Z1, lobby_hi))
    ]
    f['upgrade'] = [
        'scoreboard objectives add pvp_session dummy',
        'scoreboard players add #session pvp_state 0',
    ]

    # ---- setup ---------------------------------------------------------------------------------
    f['setup'] = [
        'scoreboard objectives add pvp_kills playerKillCount ' + text(f'PvP - First to {WIN}', color='gold', bold=True),
        'scoreboard objectives add pvp_deaths deathCount',
        'scoreboard objectives add pvp_seen dummy',
        'scoreboard objectives add pvp_state dummy',
        f'function {NS}:upgrade',
        'scoreboard players add #session pvp_state 1',
        'scoreboard players set #20 pvp_state 20',
        'scoreboard players set #pstate pvp_state 0',
        'scoreboard players set #clk pvp_state 0',
        'team add pvp_blue', 'team modify pvp_blue color blue',
        'team add pvp_red', 'team modify pvp_red color red',
        'gamerule commandBlockOutput false',
        'forceload add -130 70 -70 150',
    ]
    f['build_all'] = [f'function {NS}:build_lobby', f'function {NS}:build_arena']

    # ---- breakable cover (planks, glass, stone bricks, wool) -----------------------------------
    cover = []
    for u, v in mirror(6, 5):              # 2x2x2 plank crates
        cover.append(fill(X(u), y, Z(v), X(u + 1 if u > 0 else u - 1), y + 1, Z(v + 1 if v > 0 else v - 1), 'oak_planks'))
    for v in (8, -8):                      # glass partitions
        cover.append(fill(X(-4), y, Z(v), X(4), y + 2, Z(v), 'glass'))
    for u in (9, -9):                      # stone-brick half walls
        cover.append(fill(X(u), y, Z(-2), X(u), y + 1, Z(2), 'stone_bricks'))
    for u, v in mirror(11, 13):            # wool stacks
        cover.append(fill(X(u), y, Z(v), X(u + 1 if u > 0 else u - 1), y + 2, Z(v + 1 if v > 0 else v - 1), 'white_wool'))
    f['build_cover'] = cover

    # ---- arena ---------------------------------------------------------------------------------
    mid = (lo + hi) // 2
    a = [
        f'tp @a[x={A_LO},y={lo},z=79,dx=43,dy=18,dz=43] {lobby_spawn}',
        f'kill @e[type=!player,tag=pvp_visitor,x={A_LO},y={lo},z=79,dx=42,dy=17,dz=42]',
        f'execute as @e[type=item,x={A_LO},y={lo},z=79,dx=42,dy=17,dz=42] '
        f'unless items entity @s contents #{NS}:kept_weapons '
        'unless items entity @s contents csmc:weapon_case '
        'unless items entity @s contents csmc:inventory_weapon_case run kill @s',
        fill(A_LO, lo, 79, A_HI, mid, 121, 'bedrock'),     # halves keep fills under 32768 blocks
        fill(A_LO, mid + 1, 79, A_HI, hi, 121, 'bedrock'),
        fill(A_LO + 1, lo + 1, 80, A_HI - 1, hi - 1, 120, 'obsidian'),
        fill(A_LO + 2, floor, 81, A_HI - 2, hi - 2, 119, 'air'),
    ]
    # Floor: cyan/pink spawn pads, blue and red striped halves, yellow centre line.
    a += [fill(X(-19), floor, 81, X(-14), floor, 119, 'cyan_concrete'),
          fill(X(14), floor, 81, X(19), floor, 119, 'pink_concrete'),
          fill(X(0), floor, 81, X(0), floor, 119, 'yellow_concrete')]
    for u in range(1, 14):
        a.append(fill(X(-u), floor, 81, X(-u), floor, 119, 'blue_concrete' if u % 2 else 'light_blue_concrete'))
        a.append(fill(X(u), floor, 81, X(u), floor, 119, 'red_concrete' if u % 2 else 'orange_concrete'))
    # Wall band: blue west half, red east half.
    ys = (floor + 8, floor + 9)
    a += [fill(A_LO + 1, ys[0], 81, A_LO + 1, ys[1], 119, 'blue_concrete'),
          fill(A_HI - 1, ys[0], 81, A_HI - 1, ys[1], 119, 'red_concrete'),
          fill(A_LO + 1, ys[0], 80, CX, ys[1], 80, 'blue_concrete'), fill(CX + 1, ys[0], 80, A_HI - 1, ys[1], 80, 'red_concrete'),
          fill(A_LO + 1, ys[0], 120, CX, ys[1], 120, 'blue_concrete'), fill(CX + 1, ys[0], 120, A_HI - 1, ys[1], 120, 'red_concrete')]
    for x in range(-115, -84, 6):
        for z in range(85, 116, 6):
            a.append(f'setblock {x} {hi - 1} {z} minecraft:sea_lantern')
    # Unbreakable hiding spots (obsidian blocks bullets as well): spawn rooms, centre pillar, side corners.
    for sign_u in (-13, 13):
        a += [fill(X(sign_u), y, Z(-19), X(sign_u), floor + 5, Z(-6), 'obsidian'),
              fill(X(sign_u), y, Z(6), X(sign_u), floor + 5, Z(19), 'obsidian')]
    a.append(fill(X(-1), y, Z(-1), X(1), floor + 5, Z(1), 'obsidian'))
    for u, v in mirror(5, 13):
        a.append(fill(X(u), y, Z(v), X(u + 2 if u > 0 else u - 2), floor + 2, Z(v), 'obsidian'))
    a.append(f'function {NS}:build_cover')
    # Colour-coded stands along the north wall with villager visitors.
    row_colors = ['purple', 'magenta', 'pink']
    for r in range(3):
        a += [fill(X(-10), y, 81 + r, X(10), floor + 3 - r, 81 + r, f'{row_colors[r]}_concrete'),
              fill(X(-10), floor + 4 - r, 81 + r, X(10), floor + 4 - r, 81 + r,
                   'smooth_quartz_stairs[facing=south,half=bottom]')]
    n = 0
    for r in range(3):
        for u in (-8, -4, 0, 4, 8):
            prof, biome = PROFESSIONS[n % len(PROFESSIONS)], BIOMES[n % len(BIOMES)]
            a.append(f'summon minecraft:villager {X(u)}.5 {floor + 4 - r}.5 {81 + r}.5 {{Tags:["pvp_visitor"],'
                     f'NoAI:1b,Invulnerable:1b,Silent:1b,PersistenceRequired:1b,Rotation:[0.0f,0.0f],'
                     f'VillagerData:{{profession:"minecraft:{prof}",type:"minecraft:{biome}",level:2}}}}')
            n += 1
    # Exit button on the stands (pedestal over a command block).
    a += [command_block(X(2), floor + 3, 81, f'tp @p {lobby_spawn}'),
          f'setblock {X(2)} {floor + 4} 81 minecraft:quartz_block',
          f'setblock {X(2)} {floor + 5} 81 minecraft:polished_blackstone_button[face=floor,facing=north]',
          sign(X(2), floor + 4, 82, 'south', 'EXIT', 'to lobby')]
    a.append(fill(X(-4) - 0, floor + 2, 120, X(4), floor + 6, 123, 'glass'))
    f['build_arena'] = a

    # ---- lobby ---------------------------------------------------------------------------------
    lbox = (L_X0, lo, L_Z0, L_X1, lobby_hi, L_Z1)
    lb = [f'function {NS}:upgrade',
          f'kill @e[type=!player,tag=pvp_lobby,x={L_X0},y={lo},z={L_Z0},dx=22,dy=11,dz=18]',
          fill(*lbox, 'air'), fill(*lbox, 'bedrock'),
          fill(L_X0 + 1, lo + 1, L_Z0 + 1, L_X1 - 1, lobby_hi - 1, L_Z1 - 1, 'obsidian'),
          fill(L_X0 + 2, floor, L_Z0 + 2, L_X1 - 2, lobby_hi - 2, L_Z1 - 2, 'air')]
    for k in range(8):
        lb.append(fill(L_X0 + 2 + k, floor, 124 + k, L_X1 - 2 - k, floor, 138 - k, f'{RAINBOW[(k + 6) % 12]}_concrete'))
    for x in (-106, -100, -94):
        for z in (128, 134):
            lb.append(f'setblock {x} {lobby_hi - 1} {z} minecraft:sea_lantern')
    for x in (L_X0 + 1, L_X1 - 1):
        for z in (125, 130, 135, 138):
            lb.append(f'setblock {x} {floor + 4} {z} minecraft:sea_lantern')
    lb += [f'setblock -109 {floor + 1} 132 minecraft:ender_chest[facing=east]',
           f'setblock -109 {floor + 2} 132 minecraft:air',
           sign(-110, floor + 2, 132, 'east', 'ENDER CHEST', 'Personal storage')]
    for x in (-106, -100, -94):
        for z in (130, 133):
            lb.append(f'setblock {x} {floor} {z} minecraft:sea_lantern')
    consoles = [
        (-107, 'blue', 'JOIN', 'BLUE', f'function {NS}:join_blue'),
        (-104, 'red', 'JOIN', 'RED', f'function {NS}:join_red'),
        (-101, 'lime', 'START', '1v1', f'function {NS}:start'),
        (-98, 'gray', 'STOP', '/ LEAVE', f'function {NS}:stop'),
        (-95, 'light_blue', 'WATCH', 'stands', f'tp @p {X(-2)}.5 {floor + 5} 81.5 0 0'),
        (-92, 'orange', 'REBUILD', 'arena', f'function {NS}:rebuild'),
    ]
    for x, color, l1, l2, cmd in consoles:
        lb += [command_block(x, floor, 127, cmd),
               f'setblock {x} {floor + 1} 127 minecraft:{color}_concrete',
               f'setblock {x} {floor + 2} 127 minecraft:polished_blackstone_button[face=floor,facing=north]',
               sign(x, floor + 1, 128, 'south', l1, l2)]
    lb.append(command_block(-109, floor - 1, 138, f'function {NS}:tick', 'repeating_command_block', ',auto:1b'))
    for i, color in enumerate(BED_COLORS):
        x = -109 + 2 * i
        lb += [f'setblock {x} {floor + 1} 136 minecraft:{color}_bed[facing=south,part=foot]',
               f'setblock {x} {floor + 1} 137 minecraft:{color}_bed[facing=south,part=head]']
    lb.append(f'summon minecraft:text_display {CX}.5 {floor + 3}.5 126.5 {{Tags:["pvp_lobby"],billboard:"center",'
              f'text:\'{text("1v1 ARENA", color="red", bold=True)}\','
              'transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],'
              'translation:[0f,0f,0f],scale:[3f,3f,3f]}}')
    lb.append(fill(X(-4), floor + 2, 120, X(4), floor + 6, 123, 'glass'))
    f['build_lobby'] = lb

    # ---- teams ---------------------------------------------------------------------------------
    for team, other in (('blue', 'red'), ('red', 'blue')):
        # Capture the button's nearest player once; all mutations target that executor.
        f[f'join_{team}'] = [
            f'execute as @p[distance=..8] at @s run function {NS}:join_{team}_player',
        ]
        f[f'join_{team}_player'] = [
            'execute unless score #pstate pvp_state matches 0 run ' +
            tellraw('@s', {'text': 'Wait for the current match to finish.', 'color': 'red'}),
            'execute unless score #pstate pvp_state matches 0 run return 0',
            f'execute if entity @a[tag=pvp_{team}] unless entity @s[tag=pvp_{team}] run ' +
            tellraw('@s', {'text': f'{team.upper()} is already taken.', 'color': 'red'}),
            f'execute if entity @a[tag=pvp_{team}] unless entity @s[tag=pvp_{team}] run return 0',
            f'function {NS}:clear_hostiles',
            f'tag @s remove pvp_{other}',
            f'tag @s add pvp_{team}',
            'tag @s add pvp_player',
            'scoreboard players operation @s pvp_session = #session pvp_state',
            f'team join pvp_{team} @s',
            f'function {NS}:loadout',
            tellraw('@s', {'text': f'You joined {team.upper()}.', 'color': team}),
        ]

    # ---- match loop ----------------------------------------------------------------------------
    f['tick'] = [
        f'execute as @a[tag=pvp_player] unless score @s pvp_session = #session pvp_state run function {NS}:leave_player',
        f'execute if score #pstate pvp_state matches 1..2 unless entity @a[tag=pvp_blue] run function {NS}:abort',
        f'execute if score #pstate pvp_state matches 1..2 unless entity @a[tag=pvp_red] run function {NS}:abort',
        'scoreboard players add #clk pvp_state 1',
        f'execute if score #clk pvp_state matches 20.. run function {NS}:second',
        f'execute if score #pstate pvp_state matches 1 run function {NS}:countdown',
        f'execute if score #pstate pvp_state matches 2 run function {NS}:running',
    ]
    f['second'] = [
        'scoreboard players set #clk pvp_state 0',
        f'execute if score #pstate pvp_state matches 2 run function {NS}:hud',
    ]
    f['start'] = [
        'execute unless entity @a[tag=pvp_blue] run ' + tellraw('@p', {'text': 'Nobody joined BLUE.', 'color': 'red'}),
        'execute unless entity @a[tag=pvp_red] run ' + tellraw('@p', {'text': 'Nobody joined RED.', 'color': 'red'}),
        'execute unless score #pstate pvp_state matches 0 run ' + tellraw('@p', {'text': 'A match is already running.', 'color': 'red'}),
        f'execute if score #pstate pvp_state matches 0 if entity @a[tag=pvp_blue] if entity @a[tag=pvp_red] run function {NS}:start_match',
    ]
    f['start_match'] = [
        'execute unless score #pstate pvp_state matches 0 run return 0',
        'execute unless entity @a[tag=pvp_blue] run return 0',
        'execute unless entity @a[tag=pvp_red] run return 0',
        f'function {NS}:clear_hostiles',
        'scoreboard players set #pstate pvp_state 1',
        'scoreboard players set #ptimer pvp_state 100',
        'scoreboard players set @a[tag=pvp_player] pvp_kills 0',
        'scoreboard players set @a[tag=pvp_player] pvp_deaths 0',
        'scoreboard players set @a[tag=pvp_player] pvp_seen 0',
        'scoreboard objectives setdisplay sidebar pvp_kills',
        f'schedule clear {NS}:end_match',
        f'function {NS}:build_arena',
        'gamerule doImmediateRespawn true',
        'gamerule keepInventory true',
        'gamemode adventure @a[tag=pvp_player]',
        f'spawnpoint @a[tag=pvp_blue] {int(BLUE_SPAWN[0] // 1)} {y} {int(BLUE_SPAWN[1] // 1)} {BLUE_SPAWN[2]}',
        f'spawnpoint @a[tag=pvp_red] {int(RED_SPAWN[0] // 1)} {y} {int(RED_SPAWN[1] // 1)} {RED_SPAWN[2]}',
        f'tp @a[tag=pvp_blue] {BLUE_SPAWN[0]} {y} {BLUE_SPAWN[1]} {BLUE_SPAWN[2]} 0',
        f'tp @a[tag=pvp_red] {RED_SPAWN[0]} {y} {RED_SPAWN[1]} {RED_SPAWN[2]} 0',
        f'execute as @a[tag=pvp_player] run function {NS}:loadout',
        'effect give @a[tag=pvp_player] minecraft:slowness 5 255 true',
        'scoreboard players set #sec pvp_state 5',
        f'function {NS}:count_tick',
    ]
    # Respawn uses the same bounded refill as join/start, preserving retained weapons.
    f['count_tick'] = [
        'title @a[tag=pvp_player] title ' + json.dumps(score('#sec', 'gold'), separators=(',', ':')),
        'title @a[tag=pvp_player] subtitle ' + text(f'First to {WIN} kills', color='yellow'),
        'execute as @a[tag=pvp_player] at @s run playsound minecraft:block.note_block.pling master @s ~ ~ ~ 1 1.5',
    ]
    f['countdown'] = [
        'scoreboard players remove #ptimer pvp_state 1',
        'scoreboard players operation #mod pvp_state = #ptimer pvp_state',
        'scoreboard players operation #mod pvp_state %= #20 pvp_state',
        'scoreboard players operation #sec pvp_state = #ptimer pvp_state',
        'scoreboard players add #sec pvp_state 19',
        'scoreboard players operation #sec pvp_state /= #20 pvp_state',
        f'execute if score #mod pvp_state matches 0 if score #ptimer pvp_state matches 1.. run function {NS}:count_tick',
        f'execute if score #ptimer pvp_state matches ..0 run function {NS}:begin',
    ]
    f['begin'] = [
        'scoreboard players set #pstate pvp_state 2',
        'title @a[tag=pvp_player] title ' + text('FIGHT!', color='red', bold=True),
        'title @a[tag=pvp_player] subtitle ' + text('', color='gray'),
        'execute as @a[tag=pvp_player] at @s run playsound minecraft:entity.ender_dragon.growl master @s ~ ~ ~ 0.6 1.2',
        f'function {NS}:hud',
    ]
    f['running'] = [
        f'execute unless entity @a[tag=pvp_blue] run function {NS}:abort',
        f'execute if score #pstate pvp_state matches 2 unless entity @a[tag=pvp_red] run function {NS}:abort',
        f'execute if score #pstate pvp_state matches 2 as @a[tag=pvp_player] if score @s pvp_deaths > @s pvp_seen run function {NS}:respawned',
        f'execute if score #pstate pvp_state matches 2 as @a[tag=pvp_player,scores={{pvp_kills={WIN}..}},limit=1] run function {NS}:win',
    ]
    f['respawned'] = [
        'scoreboard players operation @s pvp_seen = @s pvp_deaths',
        f'execute if entity @s[tag=pvp_blue] run tp @s {BLUE_SPAWN[0]} {y} {BLUE_SPAWN[1]} {BLUE_SPAWN[2]} 0',
        f'execute if entity @s[tag=pvp_red] run tp @s {RED_SPAWN[0]} {y} {RED_SPAWN[1]} {RED_SPAWN[2]} 0',
        f'function {NS}:loadout',
        'effect give @s minecraft:resistance 3 4 true',
        'effect give @s minecraft:glowing 3 0 true',
    ]
    f['hud'] = [
        'execute store result score #blue pvp_state run scoreboard players get @a[tag=pvp_blue,limit=1] pvp_kills',
        'execute store result score #red pvp_state run scoreboard players get @a[tag=pvp_red,limit=1] pvp_kills',
        'title @a[tag=pvp_player] actionbar ' + comp(
            {'text': 'BLUE ', 'color': 'blue', 'bold': True}, score('#blue', 'aqua'), {'text': '  -  ', 'color': 'gray'},
            score('#red', 'gold'), {'text': ' RED', 'color': 'red', 'bold': True},
            {'text': f'   first to {WIN}', 'color': 'gray'}),
    ]
    f['win'] = [
        'execute unless score #pstate pvp_state matches 2 run return 0',
        f'execute unless entity @s[tag=pvp_player,scores={{pvp_kills={WIN}..}}] run return 0',
        'execute unless score @s pvp_session = #session pvp_state run return 0',
        'execute unless entity @a[tag=pvp_blue] run return 0',
        'execute unless entity @a[tag=pvp_red] run return 0',
        'scoreboard players set #pstate pvp_state 3',
        'tag @s add pvp_winner',
        'give @s csmc:weapon_case 2',
        'give @s csmc:inventory_weapon_case 1',
        'give @a[tag=pvp_player,tag=!pvp_winner] csmc:weapon_case 1',
        f'function {NS}:hud',
        'title @a[tag=pvp_player] title ' + comp({'selector': '@s', 'bold': True}, {'text': ' WINS!', 'color': 'gold', 'bold': True}),
        tellraw('@a', {'text': '--- 1v1 over ---', 'color': 'gold'}),
        tellraw('@a', {'selector': '@s', 'color': 'yellow', 'bold': True}, {'text': ' wins ', 'color': 'gray'},
                score('#blue', 'aqua'), {'text': '-', 'color': 'gray'}, score('#red', 'gold')),
        'effect give @a[tag=pvp_player] minecraft:resistance 6 255 true',
        'execute as @a[tag=pvp_player] at @s run playsound minecraft:ui.toast.challenge_complete master @s ~ ~ ~ 1 1',
        'execute at @s run summon minecraft:firework_rocket ~ ~1 ~ {LifeTime:20,FireworksItem:{id:"minecraft:firework_rocket",count:1,'
        'components:{"minecraft:fireworks":{flight_duration:1b,explosions:[{shape:"star",colors:[I;16711680,16755200],has_trail:1b}]}}}}',
        tellraw('@a', {'text': 'Back to the lobby in 5 seconds.', 'color': 'aqua'}),
        f'schedule function {NS}:end_match 100t',
    ]
    f['abort'] = [
        'scoreboard players set #pstate pvp_state 0',
        tellraw('@a', {'text': '1v1 stopped.', 'color': 'red'}),
        f'function {NS}:end_match',
    ]
    f['leave_player'] = [
        'effect clear @s',
        'gamemode survival @s',
        f'spawnpoint @s {CX} {y} 133',
        f'tp @s {lobby_spawn}',
        'team leave @s',
        'tag @s remove pvp_blue', 'tag @s remove pvp_red',
        'tag @s remove pvp_winner', 'tag @s remove pvp_player',
    ]
    f['end_match'] = [
        f'schedule clear {NS}:end_match',
        'scoreboard players set #pstate pvp_state 0',
        f'execute as @a[tag=pvp_player] run function {NS}:leave_player',
        # Offline participants retire on reconnect; end cleanup never clears inventory.
        'scoreboard players add #session pvp_state 1',
        'gamerule keepInventory false',
        'gamerule doImmediateRespawn false',
        f'function {NS}:build_arena',
    ]
    f['stop'] = [
        f'execute if score #pstate pvp_state matches 1..2 run return run function {NS}:abort',
        f'function {NS}:end_match',
    ]
    f['rebuild'] = [
        f'execute if score #pstate pvp_state matches 0 run function {NS}:build_arena',
        'execute unless score #pstate pvp_state matches 0 run ' + tellraw('@p', {'text': 'Stop the match before rebuilding.', 'color': 'red'}),
    ]
    return f


def main():
    root = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--world', type=Path, default=root / 'mod/run-server/csmc-test')
    parser.add_argument('--floor', type=int, default=232, help='Y of the arena floor (the sky platform)')
    args = parser.parse_args()
    # The PvE pack owns separate tags; discover this pack's IDs from the real item registry.
    registry = (root / 'mod/src/main/java/dev/csmc/CsmcMod.java').read_text()
    weapon_ids = []
    knife_ids = []
    for name, target in (('FIREARM_IDS', weapon_ids), ('KNIFE_IDS', knife_ids)):
        match = re.search(rf'\b{name}\s*=\s*List\.of\((.*?)\);', registry, re.S)
        if match is None:
            raise ValueError(f'Cannot locate {name} in CsmcMod.java')
        target.extend(f'csmc:{item}' for item in re.findall(r'"([a-z0-9_]+)"', match.group(1)))
        if not target:
            raise ValueError(f'{name} contains no registered items')
    pack = args.world / 'datapacks' / NS
    if pack.exists():
        shutil.rmtree(pack)
    functions = pack / 'data' / NS / 'function'
    functions.mkdir(parents=True)
    (pack / 'pack.mcmeta').write_text(json.dumps(
        {'pack': {'pack_format': 48, 'description': 'CSMC 1v1 command-block arena'}}, indent=2) + '\n')
    for name, commands in build(args.floor).items():
        (functions / f'{name}.mcfunction').write_text('\n'.join(commands) + '\n')
    tags = pack / 'data' / NS / 'tags'
    for kind, name, values in (
        ('item', 'kept_weapons', weapon_ids + knife_ids),
        ('item', 'knives', knife_ids),
        ('entity_type', 'dangerous_mobs', [
            f'minecraft:{mob}' for mob in (
                'blaze', 'bogged', 'breeze', 'cave_spider', 'creeper', 'drowned', 'elder_guardian',
                'ender_dragon', 'enderman', 'endermite', 'evoker', 'ghast', 'guardian',
                'hoglin', 'husk', 'illusioner',
                'magma_cube', 'phantom', 'piglin', 'piglin_brute', 'pillager', 'ravager',
                'shulker', 'silverfish', 'skeleton', 'slime', 'spider', 'stray', 'vex',
                'vindicator', 'warden', 'witch', 'wither', 'wither_skeleton', 'zoglin',
                'zombie', 'zombie_villager', 'zombified_piglin')]),
    ):
        directory = tags / kind
        directory.mkdir(parents=True, exist_ok=True)
        (directory / f'{name}.json').write_text(json.dumps({'replace': False, 'values': values}, indent=2) + '\n')
    print(f'wrote {pack}')


if __name__ == '__main__':
    main()
