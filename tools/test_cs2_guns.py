"""Run: python3 -m unittest discover -s tools -p 'test_*.py' (from payload/)."""
import unittest

import cs2_guns

GRAPH = 'animation/graphs/viewmodel/'
CLIPS = 'animation/anims/viewmodel/'


class GraphResolutionTest(unittest.TestCase):
    graphs = cs2_guns.graph_variations([
        GRAPH + 'viewmodel_gun.vnmgraph+' + key + '.vnmgraph_c'
        for key in ('ak47', 'm4a1s', 'm4a4', 'usp', 'glock', 'five_seven', 'galil', 'ssg08', 'ssg08_legacy')
    ] + [GRAPH + 'viewmodel_gun_' + key + '.vnmgraph_c' for key in ('cz75', 'elites', 'revolver')]
      + [GRAPH + 'viewmodel_gun.vnmgraph_c', GRAPH + 'viewmodel_inspects.vnmgraph+ak47.vnmgraph_c'])

    def test_variations_and_own_graphs(self):
        self.assertIn('m4a1s', self.graphs)
        self.assertIn('elites', self.graphs)
        self.assertNotIn('vnmgraph', ''.join(self.graphs))

    def test_resolves_from_id_skeleton_and_world_folder(self):
        resolve = lambda *names: cs2_guns.resolve_graph(names, self.graphs)
        self.assertEqual('ak47', resolve('ak47', 'ak47', 'ak47'))
        self.assertEqual('m4a4', resolve('m4a1', 'm4a4', 'm4a4'))  # exact skeleton beats the m4a1 -> m4a1s prefix
        self.assertEqual('m4a1s', resolve('m4a1_silencer', 'm4a1_silencer', 'm4a1_silencer'))
        self.assertEqual('usp', resolve('usp_silencer', 'usp_silencer', 'usp_silencer'))
        self.assertEqual('five_seven', resolve('fiveseven', 'fiveseven', 'fiveseven'))
        self.assertEqual('galil', resolve('galilar', 'galil', 'galilar'))
        self.assertEqual('cz75', resolve('cz75a', 'cz75a', 'cz75a'))
        self.assertEqual('elites', resolve('elite', 'elite', 'elite'))
        self.assertEqual('ssg08', resolve('ssg08', 'ssg08', 'ssg08'))
        self.assertIsNone(resolve('m14', None, None))

    def test_fallback_folder(self):
        folders = {'rifle/_default_rifle': [], 'pistol/_default_pistol': [], 'rifle/rifle_mp7': []}
        self.assertEqual('rifle/rifle_mp7', cs2_guns.fallback_folder('mp7', 'WEAPONTYPE_SUBMACHINEGUN', folders))
        self.assertEqual('rifle/_default_rifle', cs2_guns.fallback_folder('m14', 'WEAPONTYPE_RIFLE', folders))
        self.assertEqual('pistol/_default_pistol', cs2_guns.fallback_folder('zeus', 'WEAPONTYPE_TASER', folders))

    def test_graph_clips_drop_shared_plumbing_except_own_folder(self):
        shared_ref = CLIPS + 'rifle/rifle_m249/idle_from_activity_m249.vnmclip'
        per_graph = [{shared_ref, CLIPS + 'rifle/rifle_ak/draw_ak.vnmclip'}, {shared_ref}, set()]
        shared = cs2_guns.shared_clips(per_graph)
        self.assertEqual({shared_ref}, shared)
        m4a1s = '"' + CLIPS + 'rifle/_default_rifle/draw_rifle.vnmclip" "' + shared_ref + '"'
        self.assertEqual([CLIPS + 'rifle/_default_rifle/draw_rifle.vnmclip'], cs2_guns.graph_clips([m4a1s], shared))
        m249 = '"' + CLIPS + 'rifle/rifle_m249/draw_m249.vnmclip" "' + shared_ref + '"'
        self.assertEqual(2, len(cs2_guns.graph_clips([m249], shared)))
        inspect = '"' + CLIPS + 'rifle/rifle_ak/lookat01_ak.vnmclip"'
        self.assertEqual(['draw_ak', 'lookat01_ak'], [cs2_guns.clip_name(ref) for ref in cs2_guns.graph_clips(
            ['"' + CLIPS + 'rifle/rifle_ak/draw_ak.vnmclip"', inspect])])


class ActionMapTest(unittest.TestCase):
    def test_pistol_variants(self):
        actions, unmapped = cs2_guns.action_map(['draw_glock', 'idle_glock', 'idle_slide_back_glock', 'lookat01_glock',
                                                 'reload_empty_glock', 'reload_glock', 'shoot1_glock', 'shoot_empty_glock'])
        self.assertEqual({'draw': 'draw_glock', 'idle': 'idle_glock', 'idle_empty': 'idle_slide_back_glock',
                          'inspect': 'lookat01_glock', 'reload_empty': 'reload_empty_glock', 'reload': 'reload_glock',
                          'fire': 'shoot1_glock', 'fire_last': 'shoot_empty_glock'}, actions)
        self.assertEqual([], unmapped)

    def test_elite_left_right(self):
        actions, unmapped = cs2_guns.action_map(['draw_elite', 'shoot_left1_elite', 'shoot_right1_elite',
                                                 'shoot_leftlast_elite', 'shoot_rightlast_elite', 'ui_keychain_elite'])
        self.assertEqual('shoot_left1_elite', actions['fire_left'])
        self.assertEqual('shoot_rightlast_elite', actions['fire_right_last'])
        self.assertNotIn('fire', actions)
        self.assertEqual(['ui_keychain_elite'], unmapped)
        self.assertEqual(['idle', 'reload', 'inspect'], cs2_guns.missing_actions('elite', actions))

    def test_revolver_tag_inside_name(self):
        actions, unmapped = cs2_guns.action_map(['draw_revolver', 'draw_2_revolver', 'prepare_shoot_revolver',
                                                 'shoot_alt1_revolver', 'dryfire_revolver', 'idle_revolver',
                                                 'chamber_position_anim_revolver_0'])
        self.assertEqual('prepare_shoot_revolver', actions['prime'])
        self.assertEqual('shoot_alt1_revolver', actions['fire_alt'])
        self.assertEqual('draw_2_revolver', actions['draw_alt'])
        self.assertEqual('dryfire_revolver', actions['dryfire'])
        self.assertEqual(['chamber_position_anim_revolver_0'], unmapped)

    def test_silencer_only_for_silenced_guns(self):
        clips = ['draw_rifle', 'silencer_attach_rifle', 'silencer_detach_rifle']
        self.assertEqual('silencer_attach_rifle', cs2_guns.action_map(clips, 'm4a1_silencer')[0]['silencer_attach'])
        actions, unmapped = cs2_guns.action_map(clips, 'm14')
        self.assertNotIn('silencer_attach', actions)
        self.assertEqual(['silencer_attach_rifle', 'silencer_detach_rifle'], unmapped)

    def test_legacy_and_negev_and_ironsight(self):
        actions, _ = cs2_guns.action_map(['shoot1_ssg08', 'shoot1_ssg08_lgcy', 'reload_ssg08_lgcy'])
        self.assertEqual({'fire': 'shoot1_ssg08'}, actions)
        actions, unmapped = cs2_guns.action_map(['empty_reload_negev', 'reload_negev', 'bullet_hide_negev', 'idle_negev'])
        self.assertEqual('empty_reload_negev', actions['reload_empty'])
        self.assertEqual(['bullet_hide_negev'], unmapped)
        actions, _ = cs2_guns.action_map(['ironsight_shoot_aug', 'ironsight_fidget_aug', 'shoot1_aug'])
        self.assertEqual('ironsight_shoot_aug', actions['fire_ironsight'])

    def test_missing_actions_taser_and_notes(self):
        self.assertEqual([], cs2_guns.missing_actions('taser', {'draw': 1, 'idle': 1, 'inspect': 1, 'fire': 1}))
        self.assertIn(cs2_guns.NOTES['shell_reload'], cs2_guns.notes('nova'))
        self.assertIn(cs2_guns.NOTES['default'], cs2_guns.notes('m14', 'default:rifle/_default_rifle'))


class LifecycleTest(unittest.TestCase):
    def test_status(self):
        self.assertEqual('ok', cs2_guns.viewmodel_status([], []))
        self.assertEqual('failed', cs2_guns.viewmodel_status(['shoot1_ak: missing sidecar'], []))
        self.assertEqual('failed', cs2_guns.viewmodel_status([], ['reload']))

    def test_cache_requires_completed_nonempty_export(self):
        clips = {'draw_ak': {'file': 'models/ak47/clips/draw_ak.gltf'}}
        self.assertTrue(cs2_guns.viewmodel_cache_ok({'viewmodel_status': 'ok', 'viewmodel_clips': clips}))
        self.assertFalse(cs2_guns.viewmodel_cache_ok({'viewmodel_status': 'in_progress', 'viewmodel_clips': clips}))
        self.assertFalse(cs2_guns.viewmodel_cache_ok({'viewmodel_status': 'failed', 'viewmodel_clips': clips}))
        self.assertFalse(cs2_guns.viewmodel_cache_ok({'viewmodel_status': 'ok', 'viewmodel_clips': {}}))
        self.assertFalse(cs2_guns.viewmodel_cache_ok({'status': 'ok'}))  # exported before gun clips existed

    def test_prune_uses_selection_and_skips_after_failure(self):
        names = ['draw_ak.gltf', 'draw_ak.bin', 'shoot1_ak.gltf', 'shoot1_ak.bin', 'idle_from_activity_m249.gltf',
                 'idle_from_activity_m249.bin']
        selected = {'draw_ak', 'shoot1_ak'}
        self.assertEqual(['idle_from_activity_m249.bin', 'idle_from_activity_m249.gltf'],
                         cs2_guns.prune_candidates(names, selected, failed=False))
        # shoot1_ak failed to refresh: its previous files and every other file stay.
        self.assertEqual([], cs2_guns.prune_candidates(names, selected, failed=True))


class GltfReportTest(unittest.TestCase):
    model = {
        'nodes': [{'name': 'arm_root', 'children': [1]}, {'name': 'animation/skeletons/weapons/ak47.vnmskel', 'children': [2]},
                  {'name': 'weapon', 'children': [3, 4]}, {'name': 'bolt'}, {'name': 'muzzle'}],
        'accessors': [{'max': [0.75]}, {'max': [1.25]}, {}],
        'animations': [{'samplers': [{'input': 0}, {'input': 1}]}],
    }

    def test_duration_is_longest_input(self):
        self.assertEqual(1.25, cs2_guns.duration(self.model))
        self.assertEqual(0.0, cs2_guns.duration({}))

    def test_skeleton_report_against_world_nodes(self):
        world = ['weapons/models/ak47/weapon_rif_ak47.vmdl_c', 'weapon', 'bolt', 'ag1_hand_r',
                 'weapons/models/ak47/weapon_rif_ak47.vmdl_c.body_hd']
        report = cs2_guns.skeleton_report(self.model, world)
        self.assertEqual('animation/skeletons/weapons/ak47.vnmskel', report['skeleton'])
        self.assertEqual(2, report['matched'])
        self.assertEqual(['ag1_hand_r'], report['unmatched'])
        self.assertEqual(['muzzle'], report['clip_only'])


if __name__ == '__main__':
    unittest.main()
