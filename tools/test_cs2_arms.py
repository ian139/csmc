"""Run: python3 -m unittest discover -s tools -p 'test_*.py' (from payload/)."""
import unittest

import cs2_arms


class ArmsTest(unittest.TestCase):
    def test_mesh_suffix(self):
        self.assertEqual('firstperson_sleeves', cs2_arms.mesh_suffix('agents/models/ctm_sas/ctm_sas.vmdl_c.firstperson_sleeves'))
        self.assertEqual('root_motion', cs2_arms.mesh_suffix('root_motion'))

    def test_missing_chunks_only_when_every_error_is_a_chunk(self):
        message = ("System.IO.FileNotFoundException: Could not find file '/g/csgo/pak01_498.vpk'.; "
                   "System.AggregateException: One or more errors occurred. (Could not find file '/g/csgo/pak01_390.vpk'.)")
        self.assertEqual(['pak01_390.vpk', 'pak01_498.vpk'], cs2_arms.missing_chunks(message))
        self.assertEqual([], cs2_arms.missing_chunks(message + '; System.InvalidDataException: bad mesh'))
        self.assertEqual([], cs2_arms.missing_chunks('CLI exit 1'))

    def test_sidecars_dedupe_buffers_and_images(self):
        model = {'buffers': [{'uri': 'a.bin'}], 'images': [{'uri': 'c.png'}, {'uri': 'c.png'}, {'bufferView': 0}]}
        self.assertEqual(['a.bin', 'c.png'], cs2_arms.sidecars(model))

    def test_anchors_prefer_ancestor_then_descendant(self):
        parents = {'pelvis': 'root_motion', 'spine_3': 'pelvis', 'clavicle_R': 'spine_3', 'arm_upper_R': 'clavicle_R',
                   'arm_upper_R_TWIST': 'arm_upper_R', 'scapula_R': 'clavicle_R'}
        names = ['root_motion', 'pelvis', 'spine_3', 'clavicle_R', 'arm_upper_R', 'arm_upper_R_TWIST', 'scapula_R']
        anchors = cs2_arms.joint_anchors(names, parents, {'root_motion', 'arm_upper_R'}, 'root_motion')
        self.assertEqual('arm_upper_R', anchors['arm_upper_R_TWIST'])
        self.assertEqual('arm_upper_R', anchors['clavicle_R'])  # descendant: spine is not animated
        self.assertEqual('root_motion', anchors['scapula_R'])
        self.assertNotIn('arm_upper_R', anchors)

    def test_skin_report_counts_clip_overlap(self):
        model = {'nodes': [{'name': 'x.vmdl_c', 'children': [1]}, {'name': 'root_motion', 'children': [2]},
                           {'name': 'hand_R', 'children': [3]}, {'name': 'hand_R_TWIST'},
                           {'name': 'x.vmdl_c.viewmodel', 'mesh': 0, 'skin': 0}],
                 'skins': [{'joints': [1, 2, 3]}], 'meshes': [{'primitives': [{'material': 0}]}],
                 'materials': [{'name': 'glove'}]}
        report = cs2_arms.skin_report(model, {'root_motion', 'hand_R', 'wpn'})['viewmodel']
        self.assertEqual('root_motion', report['skeleton_root'])
        self.assertEqual(2, report['matched_joints'])
        self.assertEqual(['hand_R_TWIST'], report['unmatched_joints'])
        self.assertEqual({'hand_R_TWIST': 'hand_R'}, report['joint_anchors'])
        self.assertEqual(['glove'], report['materials'])


if __name__ == '__main__':
    unittest.main()
