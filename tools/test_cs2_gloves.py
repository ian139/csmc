"""Run: python3 -m unittest discover -s tools -p 'test_*.py' (from payload/)."""
import unittest

import cs2_gloves

try:
    import numpy
except ImportError:  # bake tests need numpy; the selection/layout helpers are stdlib.
    numpy = None


class GlovesTest(unittest.TestCase):
    def test_resolve_gloves_by_item_name(self):
        items = {'5027': {'name': 'studded_bloodhound_gloves', 'model_player': 'agents/a/glove_bloodhound.vmdl'},
                 '5028': {'name': 't_gloves', 'model_player': 'x.vmdl'}, 'default': 'x'}
        self.assertEqual({'studded_bloodhound_gloves': {'defindex': 5027, 'model': 'agents/a/glove_bloodhound.vmdl'}},
                         cs2_gloves.resolve_gloves(items))

    def test_icon_pairs_need_light_icon_and_known_kit(self):
        prefix = cs2_gloves.ICON_PREFIX
        entries = [prefix + 'sporty_gloves_sporty_green_light_png.vtex_c',
                   prefix + 'sporty_gloves_sporty_green_heavy_png.vtex_c',
                   prefix + 'studded_hydra_gloves_bloodhound_hydra_black_green_light_png.vtex_c',
                   prefix + 'slick_gloves_unknown_kit_light_png.vtex_c',
                   prefix + 'weapon_ak47_cu_x_light_png.vtex_c']
        pairs = cs2_gloves.icon_pairs(entries, {'sporty_green', 'bloodhound_hydra_black_green'})
        self.assertEqual({'sporty_gloves': ['sporty_green'], 'studded_hydra_gloves': ['bloodhound_hydra_black_green']}, pairs)

    def test_paint_candidates_order(self):
        kit = {'name': 'Glove_X', 'composite_material_path': 'gloves/paints/v/Glove_X.vcompmat',
               'vmt_path': 'materials/p/glove_x.vmat'}
        self.assertEqual(['gloves/paints/v/glove_x.vcompmat_c', 'gloves/paints/glove_x.vmat_c',
                          'gloves/paints/glove_x.vcompmat_c', 'materials/p/glove_x.vmat_c'],
                         cs2_gloves.paint_candidates(kit))

    def test_composite_paint_finds_resource_material(self):
        doc = {'m_Points': [{'containers': [{'m_vecLooseVariables': [
            {'m_strName': 'm_strResourceMaterial', 'm_strResourceMaterial': 'items/assets/p/x.vmat'}]}]}]}
        self.assertEqual('items/assets/p/x.vmat_c', cs2_gloves.composite_paint(doc))
        self.assertIsNone(cs2_gloves.composite_paint({'m_Points': []}))
        legacy = {'m_Points': [{'c': [{'m_strSpecificContainerMaterial': 'gloves/textiles/sporty_jaguar.vmat'}]}]}
        self.assertEqual('gloves/textiles/sporty_jaguar.vmat_c', cs2_gloves.composite_paint(legacy))
        both = {'m_Points': [{'c': [{'m_strSpecificContainerMaterial': 'workshop/t/glove_compositor.vmat'},
                                    {'m_strResourceMaterial': 'items/p/y.vmat'}]}]}
        self.assertEqual('items/p/y.vmat_c', cs2_gloves.composite_paint(both))

    def test_material_values_and_customglove_layout(self):
        doc = {'m_intParams': [{'m_name': 'F_PATTERN', 'm_nValue': 1}, {'m_name': 'g_nPatternReplaceIndex', 'm_nValue': 3}],
               'm_floatParams': [{'m_name': 'g_fDetailScale1', 'm_flValue': 2.5}],
               'm_vectorParams': [{'m_name': 'g_vColorTint1', 'm_value': [0.5, 0.25, 0.0, 0.0]}],
               'm_textureParams': [{'m_name': 'g_tLayerMask', 'm_pValue': 'm.vtex'}, {'m_name': 'g_tDetail1', 'm_pValue': 'd.vtex'},
                                   {'m_name': 'g_tPattern', 'm_pValue': 'p.vtex'}, {'m_name': 'g_tSurface', 'm_pValue': 'ao.vtex'}]}
        layout = cs2_gloves.material_layout(cs2_gloves.material_values(doc))
        self.assertEqual('customglove', layout['family'])
        self.assertEqual([2.5, 2.5], layout['layers'][0]['scale'])
        self.assertEqual([0.5, 0.25, 0.0], layout['tints'][0])
        self.assertEqual([2], layout['pattern']['ids'])
        self.assertEqual('auto', layout['pattern']['mode'])
        hydro = dict(cs2_gloves.material_values(doc), g_tPattern='materials/models/weapons/customization/paints/h/x.vtex')
        self.assertEqual('mask', cs2_gloves.material_layout(hydro)['pattern']['mode'])
        self.assertEqual(['ao.vtex', 'd.vtex', 'm.vtex', 'p.vtex'], cs2_gloves.layout_textures(layout))
        self.assertEqual(['p.vtex'], cs2_gloves.required_missing(layout, {'m.vtex': 1}))
        self.assertEqual(['ao.vtex', 'd.vtex'], cs2_gloves.degraded_inputs(layout, {'m.vtex': 1}))
        self.assertIsNone(cs2_gloves.material_layout({'g_tColor': 'c.vtex'}))

    def test_layered_layout_tints_patterns_and_tint_id(self):
        values = {'g_tLayerId': 'l.vtex', 'g_tTintId': 't.vtex', 'g_tSurface2': 's.vtex', 'g_vUvScale2': [4.0, 4.0, 0, 0],
                  'g_vId3Color': [0.0, 0.5, 1.0, 0.5], 'F_PATTERN': 1, 'g_tPattern': 'p.vtex'}
        layout = cs2_gloves.material_layout(values)
        self.assertIsNone(layout['tint_id'])
        self.assertIsNone(layout['pattern'])  # no id flagged
        self.assertEqual([0.5, 0.75, 1.0], layout['tints'][2])  # blended towards the id colour by its alpha
        self.assertEqual([1.0, 1.0, 1.0], layout['tints'][0])
        layout = cs2_gloves.material_layout(dict(values, F_TINT_ID=1, g_bId8Pattern=1))
        self.assertEqual('t.vtex', layout['tint_id'])
        self.assertEqual([7], layout['pattern']['ids'])
        self.assertEqual([4.0, 4.0], layout['layers'][1]['scale'])
        self.assertEqual('index', layout['layer_mode'])

    def test_glove_materials_split(self):
        self.assertEqual((['glove_x_left', 'glove_x_right'], ['bare_arm_133']),
                         cs2_gloves.glove_materials(['bare_arm_133', 'glove_x_left', 'glove_x_right']))

    def test_merge_index_preserves_weapon_kits(self):
        index = {'schema': 1, 'kits': [{'paint_kit': 5, 'weapons': ['ak47'], 'available_weapons': ['ak47']}]}
        glove = {'paint_kit': 10006, 'weapons': ['studded_bloodhound_gloves'], 'available_weapons': [],
                 'kind': 'glove', 'glove_textures': dict(cs2_gloves.TEXTURES), 'rarity': 'ancient'}
        shared = {'paint_kit': 5, 'weapons': ['sporty_gloves'], 'available_weapons': ['sporty_gloves'],
                  'kind': 'glove', 'glove_textures': {}, 'rarity': 'x'}
        merged = cs2_gloves.merge_index(index, {10006: glove, 5: shared})
        self.assertEqual([5, 10006], [kit['paint_kit'] for kit in merged['kits']])
        self.assertEqual(['ak47', 'sporty_gloves'], merged['kits'][0]['weapons'])
        self.assertEqual(['ak47', 'sporty_gloves'], merged['kits'][0]['available_weapons'])

    def test_drop_images_renumbers_textures(self):
        model = {'images': [{'uri': 'a.png'}, {'uri': 'gone.png'}, {'uri': 'c.png'}],
                 'textures': [{'source': 0}, {'source': 1}, {'source': 2}],
                 'materials': [{'pbrMetallicRoughness': {'baseColorTexture': {'index': 1}},
                                'normalTexture': {'index': 2}}]}
        self.assertEqual(['gone.png'], cs2_gloves.drop_images(model, {'gone.png'}))
        self.assertEqual([{'uri': 'a.png'}, {'uri': 'c.png'}], model['images'])
        self.assertEqual([{'source': 0}, {'source': 1}], model['textures'])
        self.assertEqual({'pbrMetallicRoughness': {}, 'normalTexture': {'index': 1}}, model['materials'][0])

    @unittest.skipIf(numpy is None, 'numpy unavailable')
    def test_bake_tints_layers_and_degrades_missing_detail(self):
        mask = numpy.zeros((4, 4, 4), numpy.uint8)
        mask[:, 2:, 0] = 255           # right half: layer 2
        mask[..., 3] = 18              # tint id 0 everywhere
        mask[2:, :, 3] = 49            # bottom half: tint id 1
        detail = numpy.full((2, 2, 4), 255, numpy.uint8)
        layout = cs2_gloves.material_layout({'g_tLayerMask': 'm', 'g_tDetail1': 'd', 'g_tDetail2': 'absent',
                                             'g_vColorTint1': [1.0, 0.0, 0.0, 0.0], 'g_vColorTint2': [0.0, 1.0, 0.0, 0.0]})
        images = {'m': mask, 'd': detail}
        color = cs2_gloves.bake_color(layout, images.get, size=4)
        self.assertEqual([255, 0, 0], color[0, 0].tolist())   # layer 1, tint 1
        self.assertEqual([0, 255, 0], color[3, 0].tolist())   # layer 1, tint 2
        self.assertEqual([255, 0, 0], color[0, 3].tolist())   # layer 2 detail absent -> flat
        key = numpy.zeros((2, 2, 3), numpy.float32)
        key[0, 0, 0] = key[1, 1, 1] = 1.0
        self.assertTrue(cs2_gloves.is_color_key(key))
        self.assertFalse(cs2_gloves.is_color_key(numpy.full((2, 2, 3), 0.5, numpy.float32)))
        normal = cs2_gloves.normal_rgb(numpy.full((1, 1, 4), 128, numpy.uint8))
        self.assertEqual(255, int(normal[0, 0, 2]))


if __name__ == '__main__':
    unittest.main()
