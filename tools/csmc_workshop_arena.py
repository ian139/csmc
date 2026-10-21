#!/usr/bin/env python3
"""Generate the isolated Workshop1v1 data pack (Minecraft 1.21.1).

Only use this pack on the NEW dedicated Workshop server: its setup owns server-wide
keepInventory/doImmediateRespawn/CS movement rules, world spawn and spawnRadius.
No existing arena or server is a target. The open-sky lobby keeps heightmap-based
first-login spawning on its walkable floor rather than an invisible ceiling.

python3 tools/csmc_workshop_arena.py --world /explicit/new/server/world --floor 64
Then: /reload; /function workshop:setup; wait for chunks;
/function workshop:build_all. The server needs enable-command-block=true.

Geometry and shop generators own their respective builds. Match resets call ONLY
build_map, never build_lobby/build_shop. Player inventories, armor, components and
dropped items are never cleared or rewritten by this generator.
"""
import argparse
import json
import math
import re
from pathlib import Path

from csmc_arena import command_block, fill, sign, tellraw, text
from csmc_workshop_map import bounds, build as build_geometry, spawns
from csmc_workshop_shop import build as build_trading_post

NS = 'workshop'
WIN = 5


def score(name, color):
    return {'score': {'name': name, 'objective': 'ws_state'}, 'color': color, 'bold': True}


def comp(*parts):
    return json.dumps(list(parts), separators=(',', ':'))


def build(floor: int) -> dict[str, list[str]]:
    """Return synchronous functions; state: idle=0, countdown=1, fight=2,
    finished=3, resetting=4. Only win may create reward debt, after locking state.
    """
    y = floor + 1
    lobby = f'48 {y} -20 90 0'
    x0, y0, z0, x1, y1, z1 = bounds(floor)
    box = f'x={x0},y={y0},z={z0},dx={x1-x0},dy={y1-y0},dz={z1-z0}'
    spawn = spawns(floor)
    f: dict[str, list[str]] = {}

    # Add only absent basic items. In particular, NEVER use clear/item replace,
    # including on armor or non-gun slots. Existing component-bearing stacks stay.
    slots = ('hotbar.*', 'inventory.*', 'weapon.offhand', 'armor.*')
    f['loadout'] = []
    for item, count in (('ak47', 1), ('m4a1', 1), ('awp', 1),
                        ('rifle_ammo', 180), ('pistol_ammo', 60),
                        ('sniper_ammo', 30), ('shotgun_shells', 32)):
        checks = ' '.join(f'unless items entity @s {slot} csmc:{item}' for slot in slots)
        f['loadout'].append(f'execute {checks} run give @s csmc:{item} {count}')
    checks = ' '.join(f'unless items entity @s {slot} #{NS}:knives' for slot in slots)
    f['loadout'].append(f'execute {checks} run give @s csmc:knife')

    f['setup'] = [
        'scoreboard objectives add ws_state dummy',
        'scoreboard objectives add ws_kills playerKillCount ' + text('Workshop - First to 5', color='gold'),
        'scoreboard objectives add ws_deaths deathCount',
        'scoreboard objectives add ws_seen dummy',
        'scoreboard objectives add ws_session dummy',
        'scoreboard objectives add ws_owed dummy',
        'scoreboard objectives add ws_left minecraft.custom:minecraft.leave_game',
        'scoreboard objectives add ws_leftseen dummy',
        'scoreboard objectives add ws_mode dummy',
        f'execute unless score #session ws_state matches 1.. run function {NS}:initialize',
        'scoreboard players set #20 ws_state 20',
        'team add ws_blue', 'team modify ws_blue color blue',
        'team add ws_red', 'team modify ws_red color red',
        'gamerule commandBlockOutput false',
        'gamerule keepInventory true',
        'gamerule doImmediateRespawn true',
        'gamerule csmcMovement competitive',
        f'setworldspawn 48 {y} -20',
        'gamerule spawnRadius 0',
        f'forceload add {x0} {z0} {x1} {z1}',
        'forceload add 38 -34 66 -6',
    ]
    f['initialize'] = [
        'scoreboard players set #state ws_state 0',
        'scoreboard players set #session ws_state 1',
        'scoreboard players set #clock ws_state 0',
    ]
    f['build_all'] = [
        'execute unless score #state ws_state matches 0 run return 0',
        f'function {NS}:build_lobby',
        f'function {NS}:build_shop',
        f'function {NS}:build_map',
    ]
    # This wrapper is used by every reset, including explicit administrative
    # rebuild. Evacuate people but leave ALL dropped player property intact.
    f['build_map'] = [
        'execute if score #state ws_state matches 1..3 run return 0',
        f'tp @a[{box}] {lobby}',
        *build_geometry(floor),
    ]
    f['build_shop'] = [
        'execute unless score #state ws_state matches 0 run return 0',
        *build_trading_post(floor),
    ]

    # Lobby controls are west of the spawn, separate from x56..65 shop stalls.
    # No stands, crowds, decorative villagers, entity cleanup or lobby roof.
    lb = [
        'execute unless score #state ws_state matches 0 run return 0',
        fill(38, floor-1, -34, 66, floor-1, -6, 'bedrock'),
        fill(38, floor, -34, 66, floor, -6, 'smooth_quartz'),
        fill(39, y, -33, 55, floor+7, -7, 'air'),
        fill(38, y, -34, 38, floor+3, -6, 'polished_deepslate'),
        fill(66, y, -34, 66, floor+3, -6, 'polished_deepslate'),
        fill(39, y, -34, 65, floor+3, -34, 'polished_deepslate'),
        fill(39, y, -6, 65, floor+3, -6, 'polished_deepslate'),
        fill(38, floor+4, -34, 38, floor+8, -6, 'barrier'),
        fill(66, floor+4, -34, 66, floor+8, -6, 'barrier'),
        fill(39, floor+4, -34, 65, floor+8, -34, 'barrier'),
        fill(39, floor+4, -6, 65, floor+8, -6, 'barrier'),
        # Also remove the previous lobby ceiling when rebuilding an existing pack.
        fill(39, floor+8, -33, 65, floor+8, -7, 'air'),
    ]
    for x in (42, 48, 54, 60, 64):
        for z in (-32, -26, -20, -14, -8):
            lb.append(f'setblock {x} {floor} {z} minecraft:sea_lantern')
    for z in (-32, -20, -8):
        lb.append(f'setblock 38 {floor+3} {z} minecraft:sea_lantern')
    controls = (
        (40, -30, 'blue', 'JOIN BLUE', 'join_blue'),
        (40, -24, 'red', 'JOIN RED', 'join_red'),
        (40, -18, 'lime', 'START', 'start'),
        (40, -12, 'gray', 'STOP / LEAVE', 'stop'),
        (46, -30, 'orange', 'REBUILD MAP', 'rebuild'),
    )
    for x, z, color, label, fn in controls:
        lb += [
            command_block(x, floor, z, f'function {NS}:{fn}'),
            f'setblock {x} {y} {z} minecraft:{color}_concrete',
            f'setblock {x} {floor+2} {z} minecraft:polished_blackstone_button[face=floor,facing=north]',
            sign(x+1, y, z, 'east', label, 'First to 5 kills'),
        ]
    lb += [
        command_block(48, floor-1, -8, f'function {NS}:tick', 'repeating_command_block', ',auto:1b'),
        f'setblock 48 {floor} -8 minecraft:sea_lantern',
        f'setblock 54 {y} -32 minecraft:ender_chest[facing=south]',
        sign(54, floor+2, -33, 'south', 'WORKSHOP 1v1', 'Winner: 5 gold', 'Loser: 1 gold', 'No abort rewards'),
    ]
    f['build_lobby'] = lb

    f['prune_participants'] = [
        f'execute as @a[tag=ws_player] unless score @s ws_session = #session ws_state run function {NS}:leave_player',
        f'execute as @a[tag=ws_player] unless score @s ws_left = @s ws_leftseen run function {NS}:leave_player',
    ]
    for team, other in (('blue', 'red'), ('red', 'blue')):
        f[f'join_{team}'] = [
            f'execute as @p[distance=..8,gamemode=!spectator] at @s run function {NS}:join_{team}_player',
        ]
        f[f'join_{team}_player'] = [
            'execute unless entity @s[type=minecraft:player,gamemode=!spectator] run return 0',
            'execute unless score #state ws_state matches 0 run ' +
            tellraw('@s', {'text': 'Wait for this match to finish.', 'color': 'red'}),
            'execute unless score #state ws_state matches 0 run return 0',
            f'function {NS}:prune_participants',
            f'execute if entity @a[tag=ws_{team}] unless entity @s[tag=ws_{team}] run ' +
            tellraw('@s', {'text': f'{team.upper()} is already taken.', 'color': 'red'}),
            f'execute if entity @a[tag=ws_{team}] unless entity @s[tag=ws_{team}] run return 0',
            f'execute unless entity @s[tag=ws_player] run function {NS}:save_mode',
            f'tag @s remove ws_{other}',
            f'tag @s add ws_{team}',
            'tag @s add ws_player',
            'scoreboard players operation @s ws_session = #session ws_state',
            'scoreboard players add @s ws_left 0',
            'scoreboard players operation @s ws_leftseen = @s ws_left',
            f'team join ws_{team} @s',
            f'function {NS}:loadout',
            tellraw('@s', {'text': f'Joined {team.upper()}.', 'color': team}),
        ]
    f['save_mode'] = [
        f'execute if entity @s[gamemode={mode}] run scoreboard players set @s ws_mode {value}'
        for value, mode in enumerate(('survival', 'creative', 'adventure', 'spectator'))
    ]
    f['count_roster'] = [
        'execute store result score #bluecount ws_state if entity @a[tag=ws_player,tag=ws_blue]',
        'execute store result score #redcount ws_state if entity @a[tag=ws_player,tag=ws_red]',
    ]
    f['start'] = [
        'execute unless score #state ws_state matches 0 run ' +
        tellraw('@p', {'text': 'A match is already running.', 'color': 'red'}),
        'execute unless score #state ws_state matches 0 run return 0',
        f'function {NS}:prune_participants',
        f'function {NS}:count_roster',
        'execute unless score #bluecount ws_state matches 1 run ' +
        tellraw('@p', {'text': 'Exactly one BLUE player must join.', 'color': 'red'}),
        'execute unless score #redcount ws_state matches 1 run ' +
        tellraw('@p', {'text': 'Exactly one RED player must join.', 'color': 'red'}),
        f'execute if score #bluecount ws_state matches 1 if score #redcount ws_state matches 1 run function {NS}:start_match',
    ]
    f['start_match'] = [
        'execute unless score #state ws_state matches 0 run return 0',
        f'function {NS}:prune_participants',
        f'function {NS}:count_roster',
        'execute unless score #bluecount ws_state matches 1 run return 0',
        'execute unless score #redcount ws_state matches 1 run return 0',
        'scoreboard players set #state ws_state 4',
        f'schedule clear {NS}:end_match',
        # Synchronous full geometry rebuild finishes BEFORE countdown can start.
        f'function {NS}:build_map',
        'scoreboard players set @a[tag=ws_player] ws_kills 0',
        'scoreboard players set @a[tag=ws_player] ws_deaths 0',
        'scoreboard players set @a[tag=ws_player] ws_seen 0',
        'scoreboard objectives setdisplay sidebar ws_kills',
        'gamemode adventure @a[tag=ws_player]',
    ]
    for team in ('blue', 'red'):
        sx, sy, sz, yaw, pitch = spawn[team]
        f['start_match'] += [
            f'spawnpoint @a[tag=ws_{team}] {math.floor(sx)} {math.floor(sy)} {math.floor(sz)} {yaw}',
            f'tp @a[tag=ws_{team}] {sx} {sy} {sz} {yaw} {pitch}',
        ]
    f['start_match'] += [
        f'execute as @a[tag=ws_player,nbt=!{{Health:0.0f}}] run function {NS}:loadout',
        'scoreboard players set #timer ws_state 100',
        'scoreboard players set #sec ws_state 5',
        'scoreboard players set #state ws_state 1',
        f'function {NS}:count_tick',
    ]
    f['tick'] = [
        # Detect a leave/rejoin even if it occurred between two command-block ticks.
        f'execute if score #state ws_state matches 1..2 as @a[tag=ws_player] unless score @s ws_left = @s ws_leftseen run function {NS}:abort',
        f'function {NS}:prune_participants',
        f'execute if score #state ws_state matches 1..2 run function {NS}:count_roster',
        f'execute if score #state ws_state matches 1..2 unless score #bluecount ws_state matches 1 run function {NS}:abort',
        f'execute if score #state ws_state matches 1..2 unless score #redcount ws_state matches 1 run function {NS}:abort',
        f'execute if score #state ws_state matches 1..2 as @a[tag=ws_player] at @s unless entity @s[{box}] run function {NS}:abort',
        # Spectators/visitors cannot mingle with a live duel. No inventory changes.
        f'execute if score #state ws_state matches 1..2 run tp @a[tag=!ws_player,{box}] {lobby}',
        'scoreboard players add #clock ws_state 1',
        f'execute if score #clock ws_state matches 20.. run function {NS}:second',
        f'execute if score #state ws_state matches 1 run function {NS}:countdown',
        f'execute if score #state ws_state matches 2 run function {NS}:running',
        f'function {NS}:deliver_rewards',
    ]
    f['second'] = [
        'scoreboard players set #clock ws_state 0',
        f'execute if score #state ws_state matches 2 run function {NS}:hud',
    ]
    f['count_tick'] = [
        'title @a[tag=ws_player] title ' + comp(score('#sec', 'gold')),
        'title @a[tag=ws_player] subtitle ' + text('First to 5 player kills', color='yellow'),
        'execute as @a[tag=ws_player] at @s run playsound minecraft:block.note_block.pling master @s ~ ~ ~ 1 1.5',
    ]
    f['countdown'] = [
        # Teleport anchoring avoids replacing the player's existing movement effects.
        f'execute as @a[tag=ws_player,nbt=!{{Health:0.0f}}] if score @s ws_deaths > @s ws_seen run function {NS}:respawned',
        'effect give @a[tag=ws_player] minecraft:resistance 1 4 true',
    ]
    for team in ('blue', 'red'):
        sx, sy, sz, yaw, pitch = spawn[team]
        f['countdown'].append(f'tp @a[tag=ws_{team}] {sx} {sy} {sz} {yaw} {pitch}')
    f['countdown'] += [
        'scoreboard players remove #timer ws_state 1',
        'scoreboard players operation #mod ws_state = #timer ws_state',
        'scoreboard players operation #mod ws_state %= #20 ws_state',
        'scoreboard players operation #sec ws_state = #timer ws_state',
        'scoreboard players add #sec ws_state 19',
        'scoreboard players operation #sec ws_state /= #20 ws_state',
        f'execute if score #mod ws_state matches 0 if score #timer ws_state matches 1.. run function {NS}:count_tick',
        f'execute if score #timer ws_state matches ..0 run function {NS}:begin',
    ]
    f['begin'] = [
        'execute unless score #state ws_state matches 1 run return 0',
        'scoreboard players set @a[tag=ws_player] ws_kills 0',
        'scoreboard players set #state ws_state 2',
        'title @a[tag=ws_player] title ' + text('FIGHT!', color='red', bold=True),
        'title @a[tag=ws_player] subtitle ' + text(''),
        f'function {NS}:hud',
    ]
    f['running'] = [
        'execute unless score #state ws_state matches 2 run return 0',
        f'execute as @a[tag=ws_player,nbt=!{{Health:0.0f}}] if score @s ws_deaths > @s ws_seen run function {NS}:respawned',
        f'execute if score #state ws_state matches 2 as @a[tag=ws_player,scores={{ws_kills={WIN}..}},limit=1] run function {NS}:win',
    ]
    f['respawned'] = [
        'execute unless score #state ws_state matches 1..2 run return 0',
        'execute unless score @s ws_session = #session ws_state run return 0',
        'execute unless entity @s[tag=ws_player,nbt=!{Health:0.0f}] run return 0',
        'scoreboard players operation @s ws_seen = @s ws_deaths',
    ]
    for team in ('blue', 'red'):
        sx, sy, sz, yaw, pitch = spawn[team]
        f['respawned'].append(f'execute if entity @s[tag=ws_{team}] run tp @s {sx} {sy} {sz} {yaw} {pitch}')
    f['respawned'] += [
        f'function {NS}:loadout',
        'effect give @s minecraft:resistance 3 4 true',
        'effect give @s minecraft:glowing 3 0 true',
    ]
    f['hud'] = [
        'execute store result score #blue ws_state run scoreboard players get @a[tag=ws_blue,limit=1] ws_kills',
        'execute store result score #red ws_state run scoreboard players get @a[tag=ws_red,limit=1] ws_kills',
        'title @a[tag=ws_player] actionbar ' + comp(
            {'text': 'BLUE ', 'color': 'blue'}, score('#blue', 'aqua'),
            {'text': ' - ', 'color': 'gray'}, score('#red', 'gold'),
            {'text': ' RED   First to 5', 'color': 'red'}),
    ]
    f['win'] = [
        'execute unless score #state ws_state matches 2 run return 0',
        f'execute unless entity @s[type=minecraft:player,tag=ws_player,scores={{ws_kills={WIN}..}}] run return 0',
        'execute unless score @s ws_session = #session ws_state run return 0',
        f'function {NS}:count_roster',
        'execute unless score #bluecount ws_state matches 1 run return 0',
        'execute unless score #redcount ws_state matches 1 run return 0',
        'execute as @a[tag=ws_player] unless score @s ws_session = #session ws_state run return 0',
        'execute as @a[tag=ws_player] unless score @s ws_left = @s ws_leftseen run return 0',
        # Lock BEFORE adding either reward. Repeated win/abort/end cannot accrue debt.
        'scoreboard players set #state ws_state 3',
        'tag @s add ws_winner',
        'scoreboard players add @s ws_owed 5',
        'scoreboard players add @a[tag=ws_player,tag=!ws_winner] ws_owed 1',
        f'function {NS}:hud',
        'title @a[tag=ws_player] title ' + comp(
            {'selector': '@s', 'bold': True}, {'text': ' WINS!', 'color': 'gold', 'bold': True}),
        tellraw('@a', {'text': 'Workshop match complete: winner 5 gold nuggets; loser 1. Lobby in 5 seconds.', 'color': 'gold'}),
        'effect give @a[tag=ws_player] minecraft:resistance 6 4 true',
        f'function {NS}:deliver_rewards',
        f'schedule function {NS}:end_match 100t replace',
    ]
    # Debt is independent of participant tags/session, survives death, disconnect,
    # setup/reload and end cleanup. Give only to a living player and decrement only
    # after success; a full inventory drops the earned nugget without deleting gear.
    f['deliver_rewards'] = [
        f'execute as @a[nbt=!{{Health:0.0f}},scores={{ws_owed=1..}}] run function {NS}:deliver_nugget',
    ]
    f['deliver_nugget'] = [
        'execute unless entity @s[type=minecraft:player,nbt=!{Health:0.0f},scores={ws_owed=1..}] run return 0',
        'execute store success score #paid ws_state run give @s minecraft:gold_nugget 1',
        'execute if score #paid ws_state matches 1 run scoreboard players remove @s ws_owed 1',
    ]
    f['abort'] = [
        'execute unless score #state ws_state matches 1..2 run return 0',
        'scoreboard players set #state ws_state 4',
        tellraw('@a', {'text': 'Workshop match aborted. No match rewards.', 'color': 'red'}),
        f'function {NS}:end_match',
    ]
    f['leave_player'] = [
        f'spawnpoint @s 48 {y} -20 90',
        f'tp @s {lobby}',
        *[f'execute if score @s ws_mode matches {value} run gamemode {mode} @s'
          for value, mode in enumerate(('survival', 'creative', 'adventure', 'spectator'))],
        # In the hub world (csmc_hub pinned the world), non-admins stay in adventure even for one tick.
        'execute if score #hub csmc_hub_state matches 1 unless entity @s[tag=csmc_admin] '
        'unless entity @s[gamemode=spectator] run gamemode adventure @s',
        'execute if entity @s[team=ws_blue] run team leave @s',
        'execute if entity @s[team=ws_red] run team leave @s',
        'tag @s remove ws_blue', 'tag @s remove ws_red',
        'tag @s remove ws_winner', 'tag @s remove ws_player',
    ]
    f['end_match'] = [
        # No reward creation here: safe for aborts and repeated direct calls.
        'scoreboard players set #state ws_state 4',
        f'schedule clear {NS}:end_match',
        f'execute as @a[tag=ws_player] run function {NS}:leave_player',
        'scoreboard players add #session ws_state 1',
        f'function {NS}:build_map',
        'scoreboard players set #state ws_state 0',
    ]
    f['stop'] = [
        f'execute if score #state ws_state matches 1..2 run return run function {NS}:abort',
        f'function {NS}:end_match',
    ]
    f['rebuild'] = [
        'execute unless score #state ws_state matches 0 run ' +
        tellraw('@p', {'text': 'Stop the match before rebuilding.', 'color': 'red'}),
        f'execute if score #state ws_state matches 0 run function {NS}:build_map',
    ]
    return f


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--world', required=True, type=Path,
                        help='Explicit world directory of the NEW isolated Workshop server')
    parser.add_argument('--floor', type=int, default=64, help='Y of the arena/lobby floor')
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    registry = (root / 'mod/src/main/java/dev/csmc/CsmcMod.java').read_text()
    match = re.search(r'\bKNIFE_IDS\s*=\s*List\.of\((.*?)\);', registry, re.S)
    if match is None:
        raise ValueError('Cannot locate KNIFE_IDS in CsmcMod.java')
    knives = [f'csmc:{name}' for name in re.findall(r'"([a-z0-9_]+)"', match.group(1))]
    if not knives:
        raise ValueError('KNIFE_IDS contains no registered knives')
    generated = build(args.floor)
    pack = args.world.resolve() / 'datapacks' / NS
    functions = pack / 'data' / NS / 'function'
    functions.mkdir(parents=True, exist_ok=True)
    (pack / 'pack.mcmeta').write_text(json.dumps(
        {'pack': {'pack_format': 48, 'description': 'CSMC Workshop1v1 + skin trading post'}}, indent=2) + '\n')
    for name, commands in generated.items():
        (functions / f'{name}.mcfunction').write_text('\n'.join(commands) + '\n')
    tags = pack / 'data' / NS / 'tags' / 'item'
    tags.mkdir(parents=True, exist_ok=True)
    (tags / 'knives.json').write_text(json.dumps({'replace': False, 'values': knives}, indent=2) + '\n')
    print(f'wrote {pack}')


if __name__ == '__main__':
    main()
