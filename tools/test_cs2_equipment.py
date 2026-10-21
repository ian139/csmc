"""Run: python3 -m unittest discover -s tools -p 'test_*.py' (from payload/)."""
import contextlib
import io
from pathlib import Path
import runpy
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

import cs2_equipment


class EquipmentIdentityTest(unittest.TestCase):
    def test_exactly_three_equipment_ids_with_models(self):
        self.assertEqual(('healthshot', 'c4', 'defuse_kit'), cs2_equipment.EQUIPMENT_IDS)
        self.assertEqual(set(cs2_equipment.EQUIPMENT_IDS), set(cs2_equipment.MODELS))
        self.assertEqual({
            'healthshot': 'weapons/models/healthshot/weapon_healthshot.vmdl',
            'c4': 'weapons/models/c4/weapon_c4.vmdl',
            'defuse_kit': 'weapons/models/defuser/defuser.vmdl',
        }, cs2_equipment.MODELS)

    def test_animation_actions_map_to_known_clips(self):
        self.assertEqual({'draw': 'draw_healthshot', 'idle': 'idle_healthshot', 'use': 'inject_healthshot'},
                         cs2_equipment.ANIMATIONS['healthshot'])
        self.assertEqual({'draw': 'draw_c4', 'idle': 'idle_c4', 'inspect': 'lookat01_c4',
                          'inspect_alt': 'lookat02_c4', 'inspect_alt2': 'lookat03_c4', 'use': 'plant_c4'},
                         cs2_equipment.ANIMATIONS['c4'])
        self.assertEqual({}, cs2_equipment.ANIMATIONS['defuse_kit'])
        for equipment in cs2_equipment.EQUIPMENT_IDS:
            for clip in cs2_equipment.ANIMATIONS[equipment].values():
                self.assertIn(clip, cs2_equipment.CLIPS[equipment])


class ClipSourceTest(unittest.TestCase):
    def test_healthshot_clip_sources(self):
        root = 'animation/anims/viewmodel/equipment/healthshot/'
        clips = cs2_equipment.clip_sources('healthshot')
        self.assertEqual({
            'draw_healthshot': root + 'draw_healthshot.vnmclip_c',
            'idle_healthshot': root + 'idle_healthshot.vnmclip_c',
            'inject_healthshot': root + 'inject_healthshot.vnmclip_c',
        }, clips)

    def test_c4_clip_sources_count_and_names(self):
        clips = cs2_equipment.clip_sources('c4')
        self.assertEqual(6, len(clips))
        self.assertEqual({'draw_c4', 'idle_c4', 'lookat01_c4', 'lookat02_c4', 'lookat03_c4', 'plant_c4'},
                         set(clips))
        self.assertEqual('animation/anims/viewmodel/equipment/c4/plant_c4.vnmclip_c', clips['plant_c4'])

    def test_defuse_kit_has_no_clip_sources(self):
        self.assertEqual({}, cs2_equipment.clip_sources('defuse_kit'))


class SoundSelectionTest(unittest.TestCase):
    def test_healthshot_explicit_events_only(self):
        events = {
            'IncGrenade.Draw': '"sounds/items/incgrenade_draw.vsnd"',
            'Healthshot.Thud': '"sounds/items/thud.vsnd" "sounds/items/thud.vsnd" "sounds/items/thud_b.vsnd"',
            'HealthShot.Pickup': '"sounds/items/hs_pickup.vsnd"',
            'Unrelated.Event': '"sounds/other.vsnd"',
        }
        chosen, errors = cs2_equipment.sound_selection('healthshot', events)
        self.assertEqual({
            'IncGrenade.Draw': ('draw', ['sounds/items/incgrenade_draw.vsnd']),
            'Healthshot.Thud': ('inject', ['sounds/items/thud.vsnd', 'sounds/items/thud_b.vsnd']),
        }, chosen)
        self.assertNotIn('HealthShot.Pickup', chosen)
        self.assertEqual(['sound event without resources: Healthshot.Prepare',
                          'sound event without resources: Healthshot.Success'], errors)

    def test_c4_selects_named_events_and_reports_missing_ones(self):
        events = {
            'c4.draw': '"sounds/items/c4_draw.vsnd"',
            'c4.draw.beep': '"sounds/items/c4_beep.vsnd"',
            'c4.plant': 'no resources here',
            'C4.PlantSound': '"sounds/items/beep.vsnd"',
            'c4.explode': '"sounds/x.vsnd_c"',
            'Unrelated.Event': '"sounds/other.vsnd"',
        }
        chosen, errors = cs2_equipment.sound_selection('c4', events)
        self.assertEqual({
            'c4.draw': ('draw', ['sounds/items/c4_draw.vsnd']),
            'c4.draw.beep': ('draw', ['sounds/items/c4_beep.vsnd']),
            'C4.PlantSound': ('beep', ['sounds/items/beep.vsnd']),
        }, chosen)
        self.assertEqual(11, len(errors))
        self.assertIn('sound event without resources: c4.plant', errors)
        self.assertIn('sound event without resources: c4.explode', errors)
        self.assertNotIn('sound event without resources: Unrelated.Event', errors)

    def test_missing_events_all_reported_when_list_empty(self):
        chosen, errors = cs2_equipment.sound_selection('c4', {})
        self.assertEqual({}, chosen)
        self.assertEqual(14, len(errors))
        self.assertEqual('sound event without resources: c4.draw', errors[0])
        self.assertEqual('sound event without resources: c4.explode', errors[-1])

    def test_defuse_kit_uses_direct_owned_sample_regardless_of_events(self):
        expected = ({'defuser.equip': ('equip', ['sounds/items/defuser_equip.vsnd'])}, [])
        self.assertEqual(expected, cs2_equipment.sound_selection('defuse_kit', {}))
        self.assertEqual(expected, cs2_equipment.sound_selection('defuse_kit',
                                                                 {'defuser.equip': '"sounds/other.vsnd"'}))


class DurationTest(unittest.TestCase):
    def test_compiled_duration_returned_as_float(self):
        self.assertEqual(1.5, cs2_equipment.duration('m_flDuration = 1.5'))
        self.assertIsInstance(cs2_equipment.duration('m_flDuration = 2'), float)
        self.assertEqual(2.0, cs2_equipment.duration('m_flDuration = 2'))
        self.assertEqual(0.15, cs2_equipment.duration('m_flDuration = 1.5e-1'))

    def test_duration_returned_when_channels_are_static(self):
        # glTF optimization can leave constant-pose channels with zero max; the compiled duration still wins.
        text = '''
m_flDuration = 0.8
m_channels = [
    {
        m_name = "root"
        m_frames = [ { m_flValue = 0.0 } ]
    },
]
'''
        self.assertEqual(0.8, cs2_equipment.duration(text))

    def test_duration_errors(self):
        with self.assertRaisesRegex(ValueError, 'compiled clip duration missing'):
            cs2_equipment.duration('m_Events = []')
        with self.assertRaisesRegex(ValueError, 'compiled clip duration missing'):
            cs2_equipment.duration('m_flDuration = nan')
        with self.assertRaisesRegex(ValueError, 'invalid compiled clip duration'):
            cs2_equipment.duration('m_flDuration = -0.5')
        with self.assertRaisesRegex(ValueError, 'invalid compiled clip duration'):
            cs2_equipment.duration('m_flDuration = 1e999')

    def test_markers_scale_normalized_times_by_duration_api(self):
        text = '''
m_flDuration = 0.8
m_channels = [ { m_name = "root" m_frames = [ { m_flValue = 0.0 } ] } ]
m_Events = [
    {
        _class = "CNmSoundEvent"
        m_name = "IncGrenade.Draw"
        m_flStartTime = { m_flValue = 0.5 }
        m_flDuration = { m_flValue = 0.25 }
    },
]
'''
        event = cs2_equipment.markers(text)[0]
        self.assertAlmostEqual(0.5 * cs2_equipment.duration(text), event['time_seconds'])
        self.assertAlmostEqual(0.25 * cs2_equipment.duration(text), event['duration_seconds'])
        self.assertAlmostEqual(0.4, event['time_seconds'])
        self.assertAlmostEqual(0.2, event['duration_seconds'])


class MarkerTimingTest(unittest.TestCase):
    def test_normalized_times_convert_to_seconds(self):
        text = '''
m_flDuration = 2.0
m_Events = [
    {
        _class = "CNmSoundEvent"
        m_name = "c4.draw"
        m_flStartTime = { m_flValue = 0.25 }
        m_flDuration = { m_flValue = 0.5 }
    },
]
'''
        events = cs2_equipment.markers(text)
        self.assertEqual(1, len(events))
        event = events[0]
        self.assertEqual('CNmSoundEvent', event['type'])
        self.assertEqual('c4.draw', event['name'])
        self.assertAlmostEqual(0.5, event['time_seconds'])
        self.assertAlmostEqual(1.0, event['duration_seconds'])
        self.assertEqual(0.25, event['normalized_time'])
        self.assertEqual(0.5, event['normalized_duration'])
        self.assertEqual({'m_name': 'c4.draw'}, event['fields'])
        self.assertTrue(event['compiled_event'].startswith('{'))
        self.assertTrue(event['compiled_event'].endswith('}'))

    def test_nested_braces_and_brace_strings_do_not_end_events(self):
        text = '''
m_flDuration = 10
m_Events = [
    {
        _class = "CNmSoundEvent"
        m_name = "beep {one} }"
        m_flStartTime = { m_flValue = 0.1234567 }
        m_flDuration = { m_flValue = 0.5 }
        m_curve = { m_keys = [ { m_flValue = 0.9 } ] }
    },
    {
        _class = "CNmFootstepEvent"
        m_ID = "7"
        m_flStartTime = { m_flValue = 0.75 }
        m_flDuration = { m_flValue = 0 }
    },
]
'''
        first, second = cs2_equipment.markers(text)
        self.assertEqual('beep {one} }', first['name'])
        self.assertAlmostEqual(1.234567, first['time_seconds'], places=6)
        self.assertAlmostEqual(5.0, first['duration_seconds'])
        self.assertIn('m_curve', first['compiled_event'])
        self.assertTrue(first['compiled_event'].endswith('}'))
        self.assertNotIn('CNmFootstepEvent', first['compiled_event'])

        self.assertEqual('CNmFootstepEvent', second['type'])
        self.assertEqual('7', second['name'])
        self.assertAlmostEqual(7.5, second['time_seconds'])
        self.assertEqual(0.0, second['duration_seconds'])
        self.assertEqual({'m_ID': '7'}, second['fields'])

    def test_empty_event_list(self):
        self.assertEqual([], cs2_equipment.markers('m_flDuration = 1.5\nm_Events = []'))

    def test_non_cnm_classes_are_ignored(self):
        text = 'm_flDuration = 1.5\n{ _class = "CSomethingElse" m_flStartTime = { m_flValue = 0.1 } }'
        self.assertEqual([], cs2_equipment.markers(text))

    def test_missing_clip_duration_raises(self):
        with self.assertRaisesRegex(ValueError, 'compiled clip duration missing'):
            cs2_equipment.markers('m_Events = []')

    def test_invalid_clip_duration_raises(self):
        with self.assertRaisesRegex(ValueError, 'invalid compiled clip duration'):
            cs2_equipment.markers('m_flDuration = -1')
        with self.assertRaisesRegex(ValueError, 'invalid compiled clip duration'):
            cs2_equipment.markers('m_flDuration = 1e999')

    def test_missing_event_start_time_raises(self):
        text = 'm_flDuration = 2\n{ _class = "CNmSoundEvent" m_name = "x" m_flDuration = { m_flValue = 0.5 } }'
        with self.assertRaisesRegex(ValueError, 'compiled event time missing or invalid: m_flStartTime'):
            cs2_equipment.markers(text)

    def test_missing_event_duration_raises(self):
        text = 'm_flDuration = 2\n{ _class = "CNmSoundEvent" m_name = "x" m_flStartTime = { m_flValue = 0.5 } }'
        with self.assertRaisesRegex(ValueError, 'compiled event time missing or invalid: m_flDuration'):
            cs2_equipment.markers(text)

    def test_non_numeric_or_non_finite_event_times_raise(self):
        nan_start = ('m_flDuration = 2\n{ _class = "CNmSoundEvent" m_flStartTime = { m_flValue = nan } '
                     'm_flDuration = { m_flValue = 0.5 } }')
        inf_duration = ('m_flDuration = 2\n{ _class = "CNmSoundEvent" m_flStartTime = { m_flValue = 0.5 } '
                        'm_flDuration = { m_flValue = 1e999 } }')
        with self.assertRaisesRegex(ValueError, 'm_flStartTime'):
            cs2_equipment.markers(nan_start)
        with self.assertRaisesRegex(ValueError, 'm_flDuration'):
            cs2_equipment.markers(inf_duration)

    def test_unterminated_event_raises(self):
        text = ('m_flDuration = 1.0\n{ _class = "CNmSoundEvent" m_name = "x" '
                'm_flStartTime = { m_flValue = 0.1 } m_flDuration = { m_flValue = 0.2 }')
        with self.assertRaisesRegex(ValueError, 'unterminated compiled clip event'):
            cs2_equipment.markers(text)


EXPORTER = Path(__file__).resolve().parent / 'export-cs2-assets'
SOUND_EVENTS = '"Healthshot.Success" = {\n"vsnd_files" = "sounds/items/healthshot_success.vsnd"\n}\n'


def load_exporter():
    """Import the extensionless exporter without running its CLI entry point."""
    return runpy.run_path(str(EXPORTER), run_name='export_cs2_assets')


class EquipmentInputPlanTest(unittest.TestCase):
    def test_equipment_plans_only_private_sound_events(self):
        self.assertEqual([('soundevents/game_sounds_weapons.vsndevts_c', 'game_sounds_weapons.vsndevts',
                           'equipment-sound-events')], load_exporter()['input_plan'](True))

    def test_shared_modes_keep_three_original_inputs(self):
        self.assertEqual([
            ('scripts/weapons.vdata_c', 'weapons.vdata', 'weapons-data'),
            ('scripts/surfaceproperties_game.txt', 'surfaceproperties_game.txt', 'surface-data'),
            ('soundevents/game_sounds_weapons.vsndevts_c', 'game_sounds_weapons.vsndevts', 'sound-events'),
        ], load_exporter()['input_plan'](False))


class EquipmentInputIsolationTest(unittest.TestCase):
    def test_equipment_run_never_writes_shared_data(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            vpk = root / 'pak01_dir.vpk'
            vpk.write_bytes(b'')
            cli = root / 'Source2Viewer-CLI'
            cli.write_text('#!/bin/sh\n')
            cli.chmod(0o755)
            cache = root / 'cache'
            shared = cache / 'data' / 'weapons.vdata'
            shared.parent.mkdir(parents=True)
            shared.write_text('sentinel')
            commands = []

            def fake_run(command, **_):
                commands.append(list(command))
                if 'soundevents/game_sounds_weapons.vsndevts_c' in command:
                    Path(command[command.index('-o') + 1]).write_text(SOUND_EVENTS)
                return subprocess.CompletedProcess(command, 0, stdout='')

            argv = ['export-cs2-assets', '--equipment', '--vpk', str(vpk), '--cli', str(cli), '--cache', str(cache)]
            with mock.patch.object(sys, 'argv', argv), \
                    mock.patch('subprocess.run', side_effect=fake_run), \
                    mock.patch('subprocess.check_output', return_value='test-version\n'), \
                    mock.patch('shutil.which', return_value=None), \
                    contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                load_exporter()['main']()

            sources = [command[command.index('--vpk_filepath') + 1] for command in commands if '--vpk_filepath' in command]
            self.assertNotIn('scripts/weapons.vdata_c', sources)
            self.assertNotIn('scripts/surfaceproperties_game.txt', sources)
            events = [command for command in commands if 'soundevents/game_sounds_weapons.vsndevts_c' in command]
            self.assertEqual(1, len(events))
            target = Path(events[0][events[0].index('-o') + 1])
            self.assertTrue(target.parent.name.startswith('.equipment-inputs-'))
            self.assertEqual(cache, target.parent.parent)
            outputs = [Path(command[command.index('-o') + 1]) for command in commands if '-o' in command]
            self.assertFalse(any(path.is_relative_to(cache / 'data') for path in outputs))
            self.assertEqual('sentinel', shared.read_text())
            self.assertEqual(['weapons.vdata'], sorted(path.name for path in (cache / 'data').iterdir()))
            self.assertEqual([], list(cache.glob('.equipment-inputs-*')))


if __name__ == '__main__':
    unittest.main()
