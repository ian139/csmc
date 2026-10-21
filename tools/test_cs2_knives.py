"""Run: python3 -m unittest discover -s tools -p 'test_*.py' (from payload/)."""
import unittest

import cs2_knives
from cs2_skins import paint_kits


class KnifeSelectionTest(unittest.TestCase):
    def test_resolves_numeric_vdata_keys_by_name(self):
        records = {'507': {'m_szName': 'weapon_knife_karambit'}, '42': {'m_szName': 'weapon_knife'},
                   'weapon_knife': {'m_szName': 'weapon_knife', 'alias': True},
                   '41': {'m_szName': 'weapon_knifegg'}, '524': {'m_szName': 'weapon_knife_twinblade'}}
        knives = cs2_knives.resolve_knives(records, lambda body: body)
        self.assertEqual(['knife', 'knife_karambit'], list(knives))
        self.assertTrue(knives['knife']['alias'])

    def test_clip_map_skips_variants(self):
        clips = ['draw_navaja', 'draw2_navaja', 'heavy_miss1_navaja', 'heavy_backstab_navaja', 'idle1_navaja',
                 'light_backstab_navaja', 'light_backstab2_navaja', 'light_miss1_navaja', 'light_hit1_navaja',
                 'lookat01_draw_navaja', 'lookat01_navaja']
        mapped = cs2_knives.clip_map(clips)
        self.assertEqual('draw_navaja', mapped['draw'])
        self.assertEqual('lookat01_navaja', mapped['inspect'])
        self.assertEqual('heavy_miss1_navaja', mapped['stab_hit'])  # no heavy_hit1 clip
        self.assertEqual('light_backstab_navaja', mapped['slash_backstab'])
        self.assertEqual('idle_knife', cs2_knives.clip_map(['idle_knife', 'idle2_knife'])['idle'])

    def test_clip_folder_falls_back_for_ct_default(self):
        folders = {'_default_knife': {}, 'knife_m9': {}}
        self.assertEqual('_default_knife', cs2_knives.clip_folder('knife_default_ct', folders))
        self.assertEqual('knife_m9', cs2_knives.clip_folder('knife_m9', folders))

    def test_sound_overrides_replace_common_events(self):
        events = {'Weapon_Knife.Deploy': '"sounds/weapons/knife/knife_deploy1.vsnd"',
                  'KnifeFalchion.draw': '"sounds/weapons/knife_falchion/knife_falchion_draw.vsnd"'}
        chosen, errors = cs2_knives.sound_selection('knife_falchion', events)
        self.assertEqual({'KnifeFalchion.draw': ('deploy', ['sounds/weapons/knife_falchion/knife_falchion_draw.vsnd'])}, chosen)
        self.assertIn('sound event without resources: KnifeFalchion.inspect', errors)
        chosen, _ = cs2_knives.sound_selection('knife_gut', events)
        self.assertEqual(['Weapon_Knife.Deploy'], list(chosen))

    def test_icon_pairs_prefer_longest_knife_id(self):
        prefix = cs2_knives.ICON_PREFIX
        entries = [prefix + 'knife_karambit_aa_fade_light_png.vtex_c', prefix + 'knife_aa_fade_light_png.vtex_c',
                   prefix + 'knife_karambit_aa_fade_medium_png.vtex_c', prefix + 'ak47_aa_fade_light_png.vtex_c',
                   prefix + 'knife_flip_unknown_light_png.vtex_c']
        self.assertEqual({'knife_karambit': {'aa_fade'}, 'knife': {'aa_fade'}},
                         cs2_knives.icon_pairs(entries, {'aa_fade'}))

    def test_paint_kits_gain_icon_knife_pairs(self):
        game = '''"items_game" { "paint_kits" { "0" { "name" "default" }
            "38" { "name" "aa_Fade" "style" "6" }
            "39" { "name" "gun_only" "style" "2" } }
          "item_sets" { "set" { "items" { "[gun_only]weapon_ak47" "1" } } } }'''
        entries = [cs2_knives.ICON_PREFIX + 'bayonet_aa_fade_light_png.vtex_c']
        kits = {kit['name']: kit['weapons'] for kit in paint_kits(game, '', entries)}
        self.assertEqual({'aa_Fade': ['bayonet'], 'gun_only': ['ak47']}, kits)


if __name__ == '__main__':
    unittest.main()
