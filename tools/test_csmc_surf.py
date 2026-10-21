"""Regression checks for the csmc_surf course generator and its csmc_hub integration."""
import re
import unittest

import csmc_arena
import csmc_hub
import csmc_surf
import csmc_workshop_map

HUB_FLOOR, PVE_FLOOR, WORKSHOP_FLOOR, SURF_FLOOR = 100, 232, 64, 190
HUB_ARGS = (HUB_FLOOR, PVE_FLOOR, WORKSHOP_FLOOR, SURF_FLOOR)      # csmc_hub.build(floor, pve, workshop, surf)
CUSTOM_SURF_FLOOR = 200
MAX_BLOCKS = 32768
SURF_GATE_PLATE = (-50, HUB_FLOOR + 1, -18)     # hub portal plate for the surf course
SURF_START_RETURN = (-270, 190, 4)              # hub return plate on the surf start platform
SURF_FINISH_RETURN = (-127, 175, 115)           # hub return plate on the surf finish pad


def inclusive_box(command):
    """(x1, y1, z1, x2, y2, z2) of a `fill` or `csmc surf` command, corners ordered low to high."""
    tokens = command.split()
    start = 1 if tokens[0] == 'fill' else 3
    a = [int(t) for t in tokens[start:start + 3]]
    b = [int(t) for t in tokens[start + 3:start + 6]]
    return tuple(min(p, q) for p, q in zip(a, b)) + tuple(max(p, q) for p, q in zip(a, b))


def volume(box):
    x1, y1, z1, x2, y2, z2 = box
    return (x2 - x1 + 1) * (y2 - y1 + 1) * (z2 - z1 + 1)


def inside(inner, outer):
    return all(outer[i] <= inner[i] and inner[i + 3] <= outer[i + 3] for i in range(3))


def overlaps(a, b):
    """True when two inclusive boxes share at least one block."""
    return all(a[i] <= b[i + 3] and b[i] <= a[i + 3] for i in range(3))


def geometry_commands(functions):
    """(function, command) for every fill and csmc surf box in a generated pack."""
    return [(name, line) for name, lines in functions.items() for line in lines
            if line.startswith(('fill ', 'csmc surf '))]


def shift_y(command, delta):
    tokens = command.split()
    for i in ((2, 5) if tokens[0] == 'fill' else (4, 7)):
        tokens[i] = str(int(tokens[i]) + delta)
    return ' '.join(tokens)


def lines_ending(lines, suffix):
    return [line for line in lines if line.endswith(suffix)]


def default_user_areas():
    """Inclusive boxes of the default hub, PvE arena, Workshop and their lobbies."""
    return {
        'hub': (csmc_hub.CX - csmc_hub.RADIUS, HUB_FLOOR - 6, csmc_hub.CZ - csmc_hub.RADIUS,
                csmc_hub.CX + csmc_hub.RADIUS, HUB_FLOOR + 20, csmc_hub.CZ + csmc_hub.RADIUS),
        'pve arena': (csmc_arena.A_LO, PVE_FLOOR - 2, csmc_arena.A_LO,
                      csmc_arena.A_HI, PVE_FLOOR + 15, csmc_arena.A_HI),
        'pve lobby': (csmc_arena.L_X0, PVE_FLOOR - 2, csmc_arena.L_Z0,
                      csmc_arena.L_X1, PVE_FLOOR + 9, csmc_arena.L_Z1),
        'workshop arena': csmc_workshop_map.bounds(WORKSHOP_FLOOR),
        # csmc_hub.build keeps the Workshop lobby as a local, so its default box is spelled out here.
        'workshop lobby': (38, WORKSHOP_FLOOR - 1, -34, 66, WORKSHOP_FLOOR + 14, -6),
    }


class SurfCourseLayoutTest(unittest.TestCase):
    def test_course_box_overlaps_no_default_user_area(self):
        box = csmc_surf.geometry(SURF_FLOOR)['box']
        for name, area in default_user_areas().items():
            with self.subTest(area=name):
                self.assertFalse(overlaps(box, area))

    def test_five_stages_lead_pad_to_pad_to_finish(self):
        self.assertEqual(5, len(csmc_surf.STAGES))
        self.assertEqual(len(csmc_surf.STAGES) + 1, len(csmc_surf.PADS))
        self.assertEqual(len(csmc_surf.STAGES), csmc_surf.CP_COUNT)

    def test_each_pad_is_one_step_lower_than_the_previous(self):
        ys = csmc_surf.geometry(SURF_FLOOR)['ys']
        self.assertEqual([-csmc_surf.STEP] * (len(ys) - 1), [b - a for a, b in zip(ys, ys[1:])])

    def test_pads_at_the_same_end_are_disjoint(self):
        pairs = 0
        for i, (a0, a1, end) in enumerate(csmc_surf.PADS):
            for b0, b1, other in csmc_surf.PADS[i + 1:]:
                if end == other:
                    pairs += 1
                    with self.subTest(pads=((a0, a1), (b0, b1))):
                        self.assertTrue(a1 < b0 or b1 < a0)
        self.assertGreater(pairs, 0)

    def test_ramp_lanes_sit_between_the_north_and_south_pad_rows(self):
        self.assertLess(csmc_surf.NORTH_Z[1], csmc_surf.LANE_Z0)
        self.assertLess(csmc_surf.LANE_Z1, csmc_surf.SOUTH_Z[0])


class SurfBuildTest(unittest.TestCase):
    def setUp(self):
        self.functions = csmc_surf.build(SURF_FLOOR)
        self.surf_commands = [line for _, line in geometry_commands(self.functions)
                              if line.startswith('csmc surf ')]

    def test_every_fill_and_ramp_box_is_at_most_32768_blocks(self):
        for floor in (SURF_FLOOR, CUSTOM_SURF_FLOOR):
            for name, line in geometry_commands(csmc_surf.build(floor)):
                with self.subTest(floor=floor, function=name):
                    self.assertLessEqual(volume(inclusive_box(line)), MAX_BLOCKS, line)

    def test_ramp_and_ridge_angles_are_between_60_and_70_degrees(self):
        self.assertTrue(self.surf_commands)
        for line in self.surf_commands:
            with self.subTest(command=line):
                self.assertTrue(60 <= float(line.split()[10]) <= 70)

    def test_stages_use_both_ramps_and_ridges(self):
        self.assertEqual({'ramp', 'ridge'}, {line.split()[2] for line in self.surf_commands})

    def test_every_stage_has_a_ramp_or_ridge_in_its_channel(self):
        boxes = [inclusive_box(line) for line in self.surf_commands]
        for stage in csmc_surf.STAGES:
            cx = stage['cx']
            with self.subTest(stage=stage['name']):
                self.assertTrue(any(cx - 16 <= b[0] and b[3] <= cx + 16 for b in boxes))

    def test_ramps_span_only_the_lanes_between_the_pad_rows(self):
        for line in self.surf_commands:
            box = inclusive_box(line)
            with self.subTest(command=line):
                self.assertGreater(box[2], csmc_surf.NORTH_Z[1])
                self.assertLess(box[5], csmc_surf.SOUTH_Z[0])

    def test_every_generated_block_stays_inside_the_course_box(self):
        box = csmc_surf.geometry(SURF_FLOOR)['box']
        for name, line in geometry_commands(self.functions):
            with self.subTest(function=name):
                self.assertTrue(inside(inclusive_box(line), box), line)

    def test_clear_empties_exactly_the_course_box(self):
        box = csmc_surf.geometry(SURF_FLOOR)['box']
        clears = [inclusive_box(line) for line in self.functions['clear']]
        self.assertTrue(clears)
        self.assertEqual(volume(box), sum(volume(c) for c in clears))
        for c in clears:
            self.assertTrue(inside(c, box))

    def test_build_all_clears_before_it_builds(self):
        self.assertEqual('function csmc_surf:clear', self.functions['build_all'][0])

    def test_setup_forceloads_the_whole_course(self):
        loads = [line for line in self.functions['setup'] if line.startswith('forceload add ')]
        self.assertEqual(1, len(loads))
        x0, z0, x1, z1 = (int(t) for t in loads[0].split()[2:])
        self.assertLessEqual(x0, csmc_surf.BOX_X0)
        self.assertLessEqual(z0, csmc_surf.BOX_Z0)
        self.assertGreaterEqual(x1, csmc_surf.BOX_X1)
        self.assertGreaterEqual(z1, csmc_surf.BOX_Z1)

    def test_checkpoint_signs_are_mounted_on_the_wall_they_face(self):
        signs = {}
        for line in self.functions['build_pads']:
            match = re.match(r'setblock (-?\d+) (-?\d+) (-?\d+) minecraft:\w+_wall_sign\[facing=(\w+)\].*CHECKPOINT (\d+)', line)
            if match:
                _, _, z, facing, k = match.groups()
                signs.setdefault(int(k), set()).add((int(z), facing))
        self.assertIn((119, 'north'), signs[1])
        self.assertIn((0, 'south'), signs[2])


class SurfTimerTest(unittest.TestCase):
    def setUp(self):
        self.functions = csmc_surf.build(SURF_FLOOR)
        self.tick = self.functions['tick']

    def test_sounds_are_positioned_at_the_player_even_after_a_teleport(self):
        sounds = [line for commands in self.functions.values() for line in commands if 'playsound ' in line]
        self.assertTrue(sounds)
        for line in sounds:
            with self.subTest(command=line):
                self.assertTrue(line.startswith('execute at @s run playsound '), line)

    def test_each_checkpoint_requires_the_previous_checkpoint(self):
        for k in range(1, csmc_surf.CP_COUNT):
            with self.subTest(checkpoint=k):
                calls = lines_ending(self.tick, f'run function csmc_surf:reach_{k}')
                self.assertEqual(1, len(calls))
                self.assertIn(f'scores={{surf_state=1,surf_cp={k - 1}}}', calls[0])

    def test_finish_requires_checkpoint_four_while_running(self):
        calls = lines_ending(self.tick, 'run function csmc_surf:finish')
        self.assertEqual(1, len(calls))
        self.assertIn(f'scores={{surf_state=1,surf_cp={csmc_surf.CP_COUNT - 1}}}', calls[0])

    def test_personal_best_is_replaced_only_when_conditional_holds(self):
        writes = [(name, line) for name, lines in self.functions.items() for line in lines
                  if re.search(r'scoreboard players (?:set|operation) @s surf_best\b', line)]
        self.assertTrue(writes)
        for name, line in writes:
            with self.subTest(function=name):
                self.assertEqual('finish', name)
                self.assertTrue(line.startswith('execute if score #pb surf_tmp matches 1 run '), line)
        flags = [line for lines in self.functions.values() for line in lines if 'set #pb surf_tmp 1' in line]
        self.assertTrue(all(line.startswith('execute if score') and 'surf_best' in line for line in flags))
        self.assertTrue(any('surf_best matches 0' in line for line in flags))
        self.assertTrue(any('surf_time < @s surf_best' in line for line in flags))

    def test_checkpoint_is_written_only_for_players(self):
        targets = [target for lines in self.functions.values() for line in lines
                   for target in re.findall(r'scoreboard players \w+ (\S+) surf_cp\b', line)]
        self.assertTrue(targets)
        self.assertEqual({'@s'}, set(targets))
        for line in self.tick:
            if 'surf_cp' in line:
                with self.subTest(command=line):
                    self.assertTrue(line.startswith('execute as @a['))

    def test_each_checkpoint_respawns_on_its_own_pad(self):
        spawns = {int(k): (float(x), float(y), float(z)) for k, x, y, z in re.findall(
            r'matches (\d+) run tp @s (\S+) (\S+) (\S+) ', '\n'.join(self.functions['to_checkpoint']))}
        self.assertEqual(set(range(csmc_surf.CP_COUNT + 1)), set(spawns))
        ys = csmc_surf.geometry(SURF_FLOOR)['ys']
        for k, (x, y, z) in spawns.items():
            x0, x1, end = csmc_surf.PADS[k]
            z0, z1 = csmc_surf.pad_z(end)
            with self.subTest(checkpoint=k):
                self.assertTrue(x0 <= x <= x1)
                self.assertTrue(z0 <= z <= z1)
                self.assertEqual(ys[k], y)


class HubSurfIntegrationTest(unittest.TestCase):
    def setUp(self):
        self.hub = csmc_hub.build(*HUB_ARGS)
        self.surf_selector = csmc_surf.box_selector(csmc_surf.geometry(SURF_FLOOR)['box'])

    def test_set_surf_removes_off_bhop_and_competitive_then_adds_surf(self):
        tags = re.findall(r'tag @s (add|remove) csmc_movement_(\w+)', '\n'.join(self.hub['set_surf']))
        self.assertEqual({'off', 'competitive', 'bhop'}, {m for op, m in tags if op == 'remove'})
        self.assertEqual({'surf'}, {m for op, m in tags if op == 'add'})

    def test_check_surf_sets_the_mode_only_when_it_is_missing(self):
        self.assertEqual(1, len(self.hub['check_surf']))
        check = self.hub['check_surf'][0]
        for required in ('tag=csmc_movement_surf', 'tag=!csmc_movement_off',
                         'tag=!csmc_movement_competitive', 'tag=!csmc_movement_bhop'):
            self.assertIn(required, check)
        self.assertTrue(check.endswith('run function csmc_hub:set_surf'))

    def test_tick_applies_surf_movement_inside_the_course(self):
        self.assertIn(f'execute as @a[{self.surf_selector}] run function csmc_hub:check_surf', self.hub['tick'])

    def test_surf_course_is_in_the_outside_union_and_the_safe_zone(self):
        tick = self.hub['tick']
        outside = lines_ending(tick, 'run function csmc_hub:return_to_hub')
        self.assertEqual(1, len(outside))
        self.assertIn(f'unless entity @s[{self.surf_selector}]', outside[0])
        self.assertIn(f'execute as @a[{self.surf_selector}] run function csmc_hub:safe', tick)
        leave = lines_ending(tick, 'run function csmc_hub:leave_safe')
        self.assertEqual(1, len(leave))
        self.assertIn(f'unless entity @s[{self.surf_selector}]', leave[0])

    def test_bhop_skips_the_course_and_competitive_never_applies_to_it(self):
        tick = self.hub['tick']
        bhop = lines_ending(tick, 'run function csmc_hub:check_bhop')
        self.assertEqual(1, len(bhop))
        self.assertIn(f'unless entity @s[{self.surf_selector}]', bhop[0])
        for line in lines_ending(tick, 'run function csmc_hub:check_competitive'):
            self.assertNotIn(self.surf_selector, line)

    def test_surf_gate_plate_starts_the_course(self):
        x, y, z = SURF_GATE_PLATE
        plates = lines_ending(self.hub['tick'], 'run function csmc_hub:to_surf')
        self.assertEqual(1, len(plates))
        self.assertIn(f'x={x},y={y},z={z},dx=0,dy=1,dz=0', plates[0])
        self.assertIn(f'if block {x} {y} {z} minecraft:stone_pressure_plate[powered=true]', plates[0])
        self.assertIn(f'setblock {x} {y} {z} minecraft:stone_pressure_plate', self.hub['build'])
        self.assertIn('function csmc_surf:to_start', self.hub['to_surf'])

    def test_start_and_finish_return_plates_send_players_home(self):
        geometry = csmc_surf.geometry(SURF_FLOOR)
        self.assertEqual(SURF_START_RETURN, geometry['hub_plate_start'])
        self.assertEqual(SURF_FINISH_RETURN, geometry['hub_plate_finish'])
        tick = self.hub['tick']
        for x, y, z in (SURF_START_RETURN, SURF_FINISH_RETURN):
            with self.subTest(plate=(x, y, z)):
                self.assertIn(f'setblock {x} {y} {z} minecraft:stone_pressure_plate', self.hub['build_returns'])
                checks = [line for line in lines_ending(tick, 'run function csmc_hub:to_hub')
                          if f'x={x},y={y},z={z},dx=0,dy=1,dz=0' in line]
                self.assertEqual(1, len(checks))


class CustomSurfFloorTest(unittest.TestCase):
    def test_custom_floor_shifts_every_fill_and_ramp_by_the_floor_change(self):
        base = csmc_surf.build(SURF_FLOOR)
        moved = csmc_surf.build(CUSTOM_SURF_FLOOR)
        self.assertEqual(set(base), set(moved))
        delta = CUSTOM_SURF_FLOOR - SURF_FLOOR
        for name in base:
            with self.subTest(function=name):
                expected = [shift_y(line, delta) for line in base[name] if line.startswith(('fill ', 'csmc surf '))]
                actual = [line for line in moved[name] if line.startswith(('fill ', 'csmc surf '))]
                self.assertEqual(expected, actual)

    def test_custom_floor_moves_the_hub_selector_and_return_plates(self):
        box = csmc_surf.geometry(CUSTOM_SURF_FLOOR)['box']
        self.assertEqual((-300, CUSTOM_SURF_FLOOR - 40, -6, -120, CUSTOM_SURF_FLOOR + 40, 126), box)
        selector = csmc_surf.box_selector(box)
        old_selector = csmc_surf.box_selector(csmc_surf.geometry(SURF_FLOOR)['box'])
        hub = csmc_hub.build(HUB_FLOOR, PVE_FLOOR, WORKSHOP_FLOOR, CUSTOM_SURF_FLOOR)
        tick = hub['tick']
        self.assertIn(f'execute as @a[{selector}] run function csmc_hub:check_surf', tick)
        self.assertIn(f'execute as @a[{selector}] run function csmc_hub:safe', tick)
        self.assertIn(f'unless entity @s[{selector}]', lines_ending(tick, 'run function csmc_hub:return_to_hub')[0])
        self.assertFalse(any(old_selector in line for line in tick))
        self.assertIn('setblock -270 200 4 minecraft:stone_pressure_plate', hub['build_returns'])
        self.assertIn('setblock -127 185 115 minecraft:stone_pressure_plate', hub['build_returns'])
        self.assertIn(f'x=-270,y=200,z=4,dx=0,dy=1,dz=0',
                      ' '.join(lines_ending(tick, 'run function csmc_hub:to_hub')))
        self.assertIn('x=-50,y=101,z=-18,dx=0,dy=1,dz=0', ' '.join(lines_ending(tick, 'run function csmc_hub:to_surf')))


if __name__ == '__main__':
    unittest.main()
