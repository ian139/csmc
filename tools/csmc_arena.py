#!/usr/bin/env python3
"""Generate the csmc_arena data pack: a sky arena + lobby driven by command blocks.

The pack is the single source of truth for the build, so rebuilding is always exact.
Command blocks (buttons in the lobby) call its functions; a hidden repeating command
block runs `csmc_arena:tick`. The server needs `enable-command-block=true`.

Usage: python3 tools/csmc_arena.py [--world mod/run-server/csmc-test] [--floor 232]
Then on the server: /reload, /function csmc_arena:setup, wait a few seconds for the
forceloaded chunks, then /function csmc_arena:build_all.
"""
import argparse
import json
import re
import shutil
from pathlib import Path

NS = 'csmc_arena'
RAINBOW = ['red', 'orange', 'yellow', 'lime', 'green', 'cyan', 'light_blue', 'blue', 'purple', 'magenta', 'pink', 'white']
PROFESSIONS = ['armorer', 'butcher', 'cartographer', 'cleric', 'farmer', 'fisherman', 'fletcher', 'leatherworker',
               'librarian', 'mason', 'nitwit', 'shepherd', 'toolsmith', 'weaponsmith']
BIOMES = ['plains', 'desert', 'jungle', 'savanna', 'snow', 'swamp', 'taiga']
BED_COLORS = ['red', 'orange', 'yellow', 'lime', 'cyan', 'light_blue', 'blue', 'purple', 'pink']

CX = CZ = 100                 # arena centre
A_LO, A_HI = 79, 121          # arena bedrock shell, x and z
L_X0, L_X1, L_Z0, L_Z1 = 89, 111, 122, 140   # lobby bedrock shell
DANGEROUS_MOBS = (
    'blaze', 'bogged', 'breeze', 'cave_spider', 'creeper', 'drowned', 'elder_guardian', 'enderman',
    'endermite', 'ender_dragon', 'evoker', 'ghast', 'giant', 'guardian', 'hoglin', 'husk',
    'illusioner', 'magma_cube', 'phantom', 'piglin', 'piglin_brute', 'pillager', 'ravager',
    'shulker', 'silverfish', 'skeleton', 'slime', 'spider', 'stray', 'vex', 'vindicator',
    'warden', 'witch', 'wither', 'wither_skeleton', 'zoglin', 'zombie', 'zombie_villager',
    'zombified_piglin',
)


def text(value, **style):
    return json.dumps({'text': value, **style}, separators=(',', ':'))


def tellraw(selector, *parts):
    return f'tellraw {selector} {json.dumps(list(parts), separators=(",", ":"))}'


def score(name, color):
    return {'score': {'name': name, 'objective': 'arena_state'}, 'color': color, 'bold': True}


def fill(x1, y1, z1, x2, y2, z2, block):
    return f'fill {x1} {y1} {z1} {x2} {y2} {z2} {block}'


def command_block(x, y, z, command, kind='command_block', extra=''):
    return (f'setblock {x} {y} {z} minecraft:{kind}[facing=up]'
            f'{{Command:{json.dumps(command)},TrackOutput:0b{extra}}}')


def sign(x, y, z, facing, *lines):
    padded = list(lines) + [''] * (4 - len(lines))
    msgs = ','.join("'" + text(line, bold=True) + "'" for line in padded)
    return (f'setblock {x} {y} {z} minecraft:oak_wall_sign[facing={facing}]'
            f'{{front_text:{{messages:[{msgs}],color:"black",has_glowing_text:0b}},is_waxed:1b}}')


def _registered_weapons(root):
    """Read item registrations, not definition filenames (imports can override those)."""
    source = (root / 'mod/src/main/java/dev/csmc/CsmcMod.java').read_text()
    source = re.sub(r'//[^\n]*|/\*.*?\*/', '', source, flags=re.S)
    result = {}
    for name in ('FIREARM_IDS', 'KNIFE_IDS'):
        match = re.search(r'\b' + name + r'\s*=\s*List\.of\((.*?)\);', source, re.S)
        if match is None:
            raise ValueError(f'Cannot find {name} registrations in CsmcMod.java')
        ids = re.findall(r'"([a-z0-9_]+)"', match.group(1))
        if not ids or len(ids) != len(set(ids)):
            raise ValueError(f'Invalid {name} registrations in CsmcMod.java')
        result[name] = [f'csmc:{item}' for item in ids]
    if not {'csmc:ak47', 'csmc:m4a1', 'csmc:awp'} <= set(result['FIREARM_IDS']):
        raise ValueError('Arena loadout guns are missing from the item registry')
    return result


def build(floor):
    lo, hi, lobby_hi = floor - 2, floor + 15, floor + 9
    lobby_spawn = f'{CX}.5 {floor + 1} 133.5 180 0'
    bounds = f'x={A_LO},y={lo},z={A_LO},dx={A_HI - A_LO},dy={hi - lo},dz={L_Z1 - A_LO}'
    participants = '@a[tag=arena_player]'
    f: dict[str, list[str]] = {}

    def window():
        return fill(96, floor + 2, 120, 104, floor + 6, 123, 'glass')

    # ---- setup -----------------------------------------------------------------------------------
    f['upgrade'] = [
        'scoreboard objectives add arena_cases dummy',
        'scoreboard objectives add arena_loadouts dummy',
    ]
    f['setup'] = [
        f'function {NS}:upgrade',
        'scoreboard objectives add arena_kills totalKillCount ' + text('Arena Kills', color='gold', bold=True),
        'scoreboard objectives add arena_state dummy',
        'scoreboard players set #20 arena_state 20',
        'scoreboard players set #100 arena_state 100',
        'scoreboard players set #state arena_state 0',
        'scoreboard players set #clk arena_state 0',
        'tag @a[tag=arena_player] remove arena_player',
        'scoreboard objectives setdisplay sidebar arena_kills',
        f'bossbar add {NS}:timer ' + text('Arena'),
        f'bossbar set {NS}:timer visible false',
        'gamerule commandBlockOutput false',
        'forceload add 70 70 130 150',
    ]
    f['build_all'] = [f'function {NS}:build_lobby', f'function {NS}:build_arena']

    # Commands only examine carried/equipped slots and never remove anything: personal items (Workshop nuggets,
    # cases, skin attachments, gloves, kevlar, other weapons) stay untouched, including skin, name, magazine and
    # other components. Missing guns, a knife and armor are supplied; arena consumables (ammunition and golden
    # apples) are replaced with a fresh supply rather than accumulating.
    f['loadout'] = []
    for gun in ('ak47', 'm4a1', 'awp'):
        f['loadout'].append(f'execute unless items entity @s container.* csmc:{gun} unless items entity @s weapon.offhand csmc:{gun} run give @s csmc:{gun}')
    f['loadout'] += [
        f'execute unless items entity @s container.* #{NS}:knives unless items entity @s weapon.offhand #{NS}:knives run give @s csmc:knife',
        'clear @s csmc:rifle_ammo',
        'clear @s csmc:pistol_ammo',
        'clear @s csmc:sniper_ammo',
        'clear @s csmc:shotgun_shells',
        'clear @s minecraft:golden_apple',
        'give @s csmc:rifle_ammo 180',
        'give @s csmc:pistol_ammo 60',
        'give @s csmc:sniper_ammo 30',
        'give @s csmc:shotgun_shells 32',
        'give @s minecraft:golden_apple 5',
    ]
    enchantments = '[minecraft:enchantments={levels:{"minecraft:unbreaking":3,"minecraft:blast_protection":4}}]'
    for slot, item in (('head', 'helmet'), ('chest', 'chestplate'), ('legs', 'leggings'), ('feet', 'boots')):
        f['loadout'].append(f'execute unless items entity @s armor.{slot} * run item replace entity @s armor.{slot} with minecraft:chainmail_{item}{enchantments}')
    f['clear_hostiles'] = [
        f'kill @e[type=#{NS}:dangerous_mobs,tag=!arena_visitor,tag=!arena_lobby,{bounds}]',
        f'kill @e[type=!player,tag=arena_mob,tag=!arena_visitor,tag=!arena_lobby,{bounds}]',
    ]
    # A generation stamp expires offline players' persistent tags after a round ends.
    f['prune_participants'] = [
        'scoreboard players add #round arena_state 0',
        f'execute as {participants} unless score @s arena_state = #round arena_state run tag @s remove arena_player',
    ]
    f['join'] = [
        f'function {NS}:clear_hostiles',
        f'function {NS}:prune_participants',
        'execute unless entity @s[tag=arena_player] run scoreboard players set @s arena_kills 0',
        'tag @s add arena_player',
        'scoreboard players operation @s arena_state = #round arena_state',
        f'function {NS}:loadout',
        f'function {NS}:deliver_rewards',
        f'tp @s {CX}.5 {floor + 1} 94.5 0 0',
    ]

    # ---- arena -----------------------------------------------------------------------------------
    mid = (lo + hi) // 2
    a = [
        f'tp @a[x={A_LO},y={lo},z={A_LO},dx=43,dy=18,dz=43] {lobby_spawn}',
        'kill @e[type=!player,tag=arena_visitor]',
        f'function {NS}:clear_hostiles',
        'kill @e[type=marker,tag=arena_spawn]',
        # Only arena litter (wave-mob drops and arena-issued supplies) is removed; dropped personal property stays.
        f'execute as @e[type=item,x={A_LO},y={lo},z={A_LO},dx=43,dy=18,dz=43] if items entity @s contents #{NS}:arena_litter run kill @s',
        # Two halves keep each fill under the 32768-block limit.
        fill(A_LO, lo, A_LO, A_HI, mid, A_HI, 'bedrock'),
        fill(A_LO, mid + 1, A_LO, A_HI, hi, A_HI, 'bedrock'),
        fill(A_LO + 1, lo + 1, A_LO + 1, A_HI - 1, hi - 1, A_HI - 1, 'obsidian'),
        fill(A_LO + 2, floor, A_LO + 2, A_HI - 2, hi - 2, A_HI - 2, 'air'),
    ]
    for k in range(20):
        a.append(fill(81 + k, floor, 81 + k, 119 - k, floor, 119 - k, f'{RAINBOW[k % 12]}_concrete'))
    # Rainbow stripe around the lining, three blocks per colour.
    for i in range(13):
        c = f'{RAINBOW[i % 12]}_concrete'
        s, e = 81 + 3 * i, 83 + 3 * i
        for y in (floor + 8,):
            a += [fill(80, y, s, 80, y + 1, e, c), fill(120, y, s, 120, y + 1, e, c),
                  fill(s, y, 80, e, y + 1, 80, c), fill(s, y, 120, e, y + 1, 120, c)]
    for x in range(85, 116, 6):
        for z in range(85, 116, 6):
            a.append(f'setblock {x} {hi - 1} {z} minecraft:sea_lantern')
    for i, (x, z) in enumerate([(90, 90), (110, 90), (90, 110), (110, 110)]):
        a += [fill(x, floor + 1, z, x + 1, floor + 5, z + 1, f'{RAINBOW[i * 3]}_concrete'),
              fill(x, floor + 6, z, x + 1, floor + 6, z + 1, 'glowstone')]
    # Podium with the return button.
    a += [fill(98, floor + 1, 98, 102, floor + 1, 102, 'smooth_quartz'),
          fill(99, floor + 2, 99, 101, floor + 2, 101, 'gold_block'),
          command_block(CX, floor + 1, CZ, f'tp @p[distance=..4] {lobby_spawn}'),
          f'setblock {CX} {floor + 3} {CZ} minecraft:polished_blackstone_button[face=floor,facing=north]',
          sign(CX, floor + 2, 102, 'south', 'RETURN', 'to lobby')]
    # Stands, with colour-coded rows.
    row_colors = ['purple', 'magenta', 'pink']
    for r in range(3):
        for x, facing in ((81 + r, 'east'), (119 - r, 'west')):
            a += [fill(x, floor + 1, 86, x, floor + 3 - r, 114, f'{row_colors[r]}_concrete'),
                  fill(x, floor + 4 - r, 86, x, floor + 4 - r, 114,
                       f'smooth_quartz_stairs[facing={facing},half=bottom]')]
    # Visitors: villagers sitting in the stands.
    n = 0
    for r in range(3):
        for x, yaw in ((81 + r, -90), (119 - r, 90)):
            for z in (88, 94, 100, 106, 112):
                prof, biome = PROFESSIONS[n % len(PROFESSIONS)], BIOMES[n % len(BIOMES)]
                a.append(f'summon minecraft:villager {x}.5 {floor + 4 - r}.5 {z}.5 {{Tags:["arena_visitor"],'
                         f'NoAI:1b,Invulnerable:1b,Silent:1b,PersistenceRequired:1b,Rotation:[{yaw}.0f,0.0f],'
                         f'VillagerData:{{profession:"minecraft:{prof}",type:"minecraft:{biome}",level:2}}}}')
                n += 1
    for x, z, tag in ((94, 94, 'sp_a'), (106, 94, 'sp_a'), (94, 106, 'sp_a'), (106, 106, 'sp_a'),
                      (100, 88, 'sp_b'), (100, 112, 'sp_b'), (89, 100, 'sp_b'), (111, 100, 'sp_b')):
        a.append(f'summon minecraft:marker {x}.5 {floor + 1} {z}.5 {{Tags:["arena_spawn","{tag}"]}}')
    a.append(window())
    f['build_arena'] = a

    # ---- lobby -----------------------------------------------------------------------------------
    box = (L_X0, lo, L_Z0, L_X1, lobby_hi, L_Z1)
    lb = [
        f'function {NS}:upgrade',
        'kill @e[type=!player,tag=arena_lobby]',
        fill(*box, 'air'),
        fill(*box, 'bedrock'),
        fill(L_X0 + 1, lo + 1, L_Z0 + 1, L_X1 - 1, lobby_hi - 1, L_Z1 - 1, 'obsidian'),
        fill(L_X0 + 2, floor, L_Z0 + 2, L_X1 - 2, lobby_hi - 2, L_Z1 - 2, 'air'),
    ]
    for k in range(8):
        lb.append(fill(91 + k, floor, 124 + k, 109 - k, floor, 138 - k, f'{RAINBOW[k]}_concrete'))
    for i in range(6):
        c = f'{RAINBOW[(i * 2) % 12]}_concrete'
        s, e = 91 + 3 * i, 93 + 3 * i
        lb.append(fill(s, floor + 4, 139, min(e, 109), floor + 5, 139, c))
    for i in range(5):
        c = f'{RAINBOW[(i * 2 + 1) % 12]}_concrete'
        s, e = 124 + 3 * i, 126 + 3 * i
        lb += [fill(90, floor + 4, s, 90, floor + 5, e, c), fill(110, floor + 4, s, 110, floor + 5, e, c)]
    for x in (94, 100, 106):
        for z in (128, 134):
            lb.append(f'setblock {x} {lobby_hi - 1} {z} minecraft:sea_lantern')
    # Visible low lighting reaches the walking surface rather than only the tall ceiling.
    for x in (92, 97, 102, 108):
        for z in (125, 131, 138):
            lb.append(f'setblock {x} {floor} {z} minecraft:sea_lantern')
    for x in (90, 110):
        for z in (125, 131, 137):
            lb.append(f'setblock {x} {floor + 2} {z} minecraft:sea_lantern')
    lb += [f'setblock 108 {floor + 1} 132 minecraft:ender_chest[facing=west]',
           f'setblock 108 {floor + 2} 132 minecraft:air']
    consoles = [
        (93, 'lime', 'START', 'round', f'function {NS}:start'),
        (96, 'light_blue', 'TP / JOIN', 'to arena', f'execute as @p run function {NS}:join'),
        (100, 'orange', 'REBUILD', 'arena', f'function {NS}:rebuild'),
        (104, 'red', 'STOP', 'round', f'function {NS}:stop'),
        (107, 'yellow', 'SET SPAWN', 'at lobby', f'spawnpoint @p {CX} {floor + 1} 133'),
    ]
    for x, color, line1, line2, cmd in consoles:
        lb += [command_block(x, floor, 127, cmd),
               f'setblock {x} {floor + 1} 127 minecraft:{color}_concrete',
               f'setblock {x} {floor + 2} 127 minecraft:polished_blackstone_button[face=floor,facing=north]',
               sign(x, floor + 1, 128, 'south', line1, line2)]
    lb.append(command_block(91, floor - 1, 138, f'function {NS}:tick', 'repeating_command_block', ',auto:1b'))
    for i, color in enumerate(BED_COLORS):
        x = 91 + 2 * i
        lb += [f'setblock {x} {floor + 1} 136 minecraft:{color}_bed[facing=south,part=foot]',
               f'setblock {x} {floor + 1} 137 minecraft:{color}_bed[facing=south,part=head]']
    lb.append(f'summon minecraft:text_display {CX}.5 {floor + 3}.5 126.5 {{Tags:["arena_lobby"],billboard:"center",'
              f'text:\'{text("CSMC ARENA", color="gold", bold=True)}\','
              'transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],'
              'translation:[0f,0f,0f],scale:[3f,3f,3f]}}')
    lb.append(window())
    f['build_lobby'] = lb

    # ---- game loop ---------------------------------------------------------------------------------
    f['tick'] = [
        f'function {NS}:prune_participants',
        'scoreboard players add #clk arena_state 1',
        f'execute if score #clk arena_state matches 20.. run function {NS}:second',
        f'execute if score #state arena_state matches 1 run function {NS}:countdown',
        f'execute if score #state arena_state matches 2 run function {NS}:running',
        f'function {NS}:deliver_rewards',
    ]
    # Scores survive death/respawn; a dead player's inventory does not when keepInventory is false (the
    # combined hub world pins it true, which is also fine). Drain one owed item of each type per living-player tick.
    f['deliver_rewards'] = [
        f'execute as @a[nbt=!{{Health:0.0f}},scores={{arena_cases=1..}}] run function {NS}:deliver_case',
        f'execute as @a[nbt=!{{Health:0.0f}},scores={{arena_loadouts=1..}}] run function {NS}:deliver_loadout',
    ]
    f['deliver_case'] = [
        'execute store success score #paid arena_state run give @s csmc:weapon_case 1',
        'execute if score #paid arena_state matches 1 run scoreboard players remove @s arena_cases 1',
    ]
    f['deliver_loadout'] = [
        'execute store success score #paid arena_state run give @s csmc:inventory_weapon_case 1',
        'execute if score #paid arena_state matches 1 run scoreboard players remove @s arena_loadouts 1',
    ]
    f['second'] = [
        'scoreboard players set #clk arena_state 0',
        f'scoreboard players add {participants} arena_kills 0',
        f'bossbar set {NS}:timer players {participants}',
    ]
    f['start'] = [
        f'execute unless score #state arena_state matches 0 run ' + tellraw('@p', {'text': 'A round is already running.', 'color': 'red'}),
        f'execute if score #state arena_state matches 0 run function {NS}:start_round',
    ]
    f['start_round'] = [
        'tag @a[tag=arena_player] remove arena_player',
        'scoreboard players add #round arena_state 1',
        f'tag @a[{bounds}] add arena_player',
        f'execute as {participants} run scoreboard players operation @s arena_state = #round arena_state',
        f'function {NS}:clear_hostiles',
        f'execute as {participants} run function {NS}:loadout',
        f'function {NS}:deliver_rewards',
        'scoreboard players set #state arena_state 1',
        'scoreboard players set #timer arena_state 200',
        f'scoreboard players set {participants} arena_kills 0',
        'scoreboard objectives setdisplay sidebar arena_kills',
        f'schedule clear {NS}:build_arena',
        f'bossbar set {NS}:timer color yellow',
        f'bossbar set {NS}:timer max 200',
        f'bossbar set {NS}:timer value 200',
        f'bossbar set {NS}:timer players {participants}',
        f'bossbar set {NS}:timer visible true',
        'scoreboard players set #sec arena_state 10',
        f'function {NS}:count_tick',
    ]
    f['count_tick'] = [
        f'bossbar set {NS}:timer name ' + json.dumps([{'text': 'Round starts in ', 'color': 'yellow'}, score('#sec', 'gold'), {'text': 's', 'color': 'yellow'}], separators=(',', ':')),
        f'title {participants} title ' + json.dumps(score('#sec', 'gold'), separators=(',', ':')),
        f'title {participants} subtitle ' + text('Get ready!', color='yellow'),
        f'execute as {participants} at @s run playsound minecraft:block.note_block.pling master @s ~ ~ ~ 1 1.5',
    ]
    f['countdown'] = [
        'scoreboard players remove #timer arena_state 1',
        f'execute store result bossbar {NS}:timer value run scoreboard players get #timer arena_state',
        'scoreboard players operation #mod arena_state = #timer arena_state',
        'scoreboard players operation #mod arena_state %= #20 arena_state',
        'scoreboard players operation #sec arena_state = #timer arena_state',
        'scoreboard players add #sec arena_state 19',
        'scoreboard players operation #sec arena_state /= #20 arena_state',
        f'execute if score #mod arena_state matches 0 if score #timer arena_state matches 1.. run function {NS}:count_tick',
        f'execute if score #timer arena_state matches ..0 run function {NS}:begin',
    ]
    f['begin'] = [
        'scoreboard players set #state arena_state 2',
        'scoreboard players set #timer arena_state 1200',
        'scoreboard players set #wave arena_state 0',
        f'bossbar set {NS}:timer color red',
        f'bossbar set {NS}:timer max 1200',
        f'bossbar set {NS}:timer value 1200',
        f'title {participants} title ' + text('FIGHT!', color='red', bold=True),
        f'title {participants} subtitle ' + text('Survive 60 seconds', color='gray'),
        f'execute as {participants} at @s run playsound minecraft:entity.ender_dragon.growl master @s ~ ~ ~ 0.6 1.2',
        f'function {NS}:wave',
        f'function {NS}:hud',
    ]
    f['running'] = [
        'scoreboard players remove #timer arena_state 1',
        f'execute store result bossbar {NS}:timer value run scoreboard players get #timer arena_state',
        'scoreboard players operation #mod arena_state = #timer arena_state',
        'scoreboard players operation #mod arena_state %= #100 arena_state',
        f'execute if score #mod arena_state matches 0 run function {NS}:wave',
        'scoreboard players operation #mod arena_state = #timer arena_state',
        'scoreboard players operation #mod arena_state %= #20 arena_state',
        f'execute if score #mod arena_state matches 0 run function {NS}:hud',
        f'execute if score #timer arena_state matches ..0 run function {NS}:complete',
    ]
    f['hud'] = [
        'scoreboard players operation #sec arena_state = #timer arena_state',
        'scoreboard players add #sec arena_state 19',
        'scoreboard players operation #sec arena_state /= #20 arena_state',
        f'execute store result score #mobs arena_state if entity @e[type=!player,tag=arena_mob,{bounds}]',
        f'bossbar set {NS}:timer name ' + json.dumps([{'text': 'Time left: ', 'color': 'white'}, score('#sec', 'red'), {'text': 's', 'color': 'white'}], separators=(',', ':')),
        f'title {participants} actionbar ' + json.dumps([{'text': 'Mobs left: ', 'color': 'gray'}, score('#mobs', 'red')], separators=(',', ':')),
    ]
    f['wave'] = [
        'scoreboard players add #wave arena_state 1',
        f'execute store result score #mobs arena_state if entity @e[type=!player,tag=arena_mob,{bounds}]',
        f'execute if score #mobs arena_state matches ..23 run function {NS}:spawn_mobs',
    ]
    mob_nbt = '{Tags:["arena_mob"],PersistenceRequired:1b,DeathLootTable:"minecraft:empty"}'
    f['spawn_mobs'] = [
        f'execute at @e[type=marker,tag=sp_a] run summon minecraft:zombie ~ ~ ~ {mob_nbt}',
        f'execute at @e[type=marker,tag=sp_b] run summon minecraft:skeleton ~ ~ ~ {mob_nbt}',
        f'execute if score #wave arena_state matches 3.. at @e[type=marker,tag=sp_b,limit=2,sort=random] run summon minecraft:spider ~ ~ ~ {mob_nbt}',
        f'execute if score #wave arena_state matches 6.. at @e[type=marker,tag=sp_a,limit=2,sort=random] run summon minecraft:husk ~ ~ ~ {mob_nbt}',
    ]
    f['stop'] = [
        'execute if score #state arena_state matches 0 run ' + tellraw('@p', {'text': 'No round is running.', 'color': 'red'}),
        f'execute if score #state arena_state matches 1..2 run function {NS}:finish',
    ]
    # Only natural expiration reaches complete. Change state before awarding, then clear
    # participant tags in finish so neither repeated ticks nor STOP can repeat a payout.
    f['complete'] = [
        f'function {NS}:prune_participants',
        f'execute if score #state arena_state matches 2 run function {NS}:complete_round',
    ]
    f['complete_round'] = [
        'scoreboard players set #state arena_state 3',
        'scoreboard players set #max arena_state 0',
        f'execute as {participants} run scoreboard players operation #max arena_state > @s arena_kills',
        f'execute as {participants} if score @s arena_kills = #max arena_state if score #max arena_state matches 1.. run function {NS}:reward_winner',
        f'execute if score #max arena_state matches 1.. as {participants} unless score @s arena_kills = #max arena_state run scoreboard players add @s arena_cases 1',
        f'execute if score #max arena_state matches ..0 as {participants} run scoreboard players add @s arena_cases 1',
        f'function {NS}:finish',
    ]
    f['reward_winner'] = [
        'scoreboard players add @s arena_cases 2',
        'scoreboard players add @s arena_loadouts 1',
        tellraw(participants, {'text': 'Winner: ', 'color': 'gold'}, {'selector': '@s', 'color': 'yellow', 'bold': True}),
    ]
    f['finish'] = [
        'scoreboard players set #state arena_state 0',
        f'bossbar set {NS}:timer visible false',
        f'function {NS}:clear_hostiles',
        f'title {participants} title ' + text('ROUND OVER', color='gold', bold=True),
        f'execute as {participants} at @s run playsound minecraft:ui.toast.challenge_complete master @s ~ ~ ~ 1 1',
        tellraw(participants, {'text': '--- Round over ---', 'color': 'gold'}),
        f'execute as {participants} run ' + tellraw(participants, {'selector': '@s', 'color': 'yellow'}, {'text': ': ', 'color': 'gray'},
                                       {'score': {'name': '@s', 'objective': 'arena_kills'}, 'color': 'green'},
                                       {'text': ' kills', 'color': 'gray'}),
        tellraw(participants, {'text': 'The arena regenerates in 5 seconds.', 'color': 'aqua'}),
        f'tag {participants} remove arena_player',
        'scoreboard players add #round arena_state 1',
        f'schedule function {NS}:build_arena 100t',
    ]
    f['rebuild'] = [
        f'execute if score #state arena_state matches 0 run function {NS}:build_arena',
        'execute unless score #state arena_state matches 0 run ' + tellraw('@p', {'text': 'Stop the round before rebuilding.', 'color': 'red'}),
    ]
    return f


def main():
    root = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--world', type=Path, default=root / 'mod/run-server/csmc-test')
    parser.add_argument('--floor', type=int, default=232, help='Y of the arena floor (the sky platform)')
    args = parser.parse_args()
    weapons = _registered_weapons(root)
    pack = args.world / 'datapacks' / NS
    if pack.exists():
        shutil.rmtree(pack)
    functions = pack / 'data' / NS / 'function'
    functions.mkdir(parents=True)
    (pack / 'pack.mcmeta').write_text(json.dumps(
        {'pack': {'pack_format': 48, 'description': 'CSMC command-block arena'}}, indent=2) + '\n')
    for name, commands in build(args.floor).items():
        (functions / f'{name}.mcfunction').write_text('\n'.join(commands) + '\n')
    tags = pack / 'data' / NS / 'tags'
    (tags / 'item').mkdir(parents=True)
    litter = [f'minecraft:{item}' for item in (
        'rotten_flesh', 'bone', 'arrow', 'string', 'spider_eye', 'iron_ingot', 'carrot', 'potato', 'bow',
        'iron_sword', 'iron_shovel', 'golden_apple', 'chainmail_helmet', 'chainmail_chestplate',
        'chainmail_leggings', 'chainmail_boots')] + [
        'csmc:rifle_ammo', 'csmc:pistol_ammo', 'csmc:sniper_ammo', 'csmc:shotgun_shells']
    for name, ids in (('kept_weapons', weapons['FIREARM_IDS'] + weapons['KNIFE_IDS']),
                      ('knives', weapons['KNIFE_IDS']),
                      ('arena_litter', litter)):
        (tags / 'item' / f'{name}.json').write_text(json.dumps({'replace': True, 'values': ids}, indent=2) + '\n')
    (tags / 'entity_type').mkdir()
    (tags / 'entity_type' / 'dangerous_mobs.json').write_text(json.dumps(
        {'replace': True, 'values': [f'minecraft:{mob}' for mob in DANGEROUS_MOBS]}, indent=2) + '\n')
    print(f'wrote {pack}')


if __name__ == '__main__':
    main()
