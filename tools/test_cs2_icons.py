"""Run: python3 -m unittest discover -s tools -p 'test_*.py' (from payload/)."""
import math
import unittest

import cs2_icons

try:
    import numpy
except ImportError:  # coverage tests need numpy; parsing helpers are stdlib.
    numpy = None

SQUARE_WITH_HOLE = ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 8 8">'
                    '<path fill-rule="{rule}" d="M0,0H8V8H0Z M2,2h4v4h-4z"/></svg>')


class IconsTest(unittest.TestCase):
    def test_icon_names_use_weapon_ids_present_in_vpk(self):
        entries = {cs2_icons.ICON_DIR + name + '.vsvg_c' for name in ('ak47', 'm4a1_silencer', 'usp_silencer')}
        self.assertEqual({'ak47': cs2_icons.ICON_DIR + 'ak47.vsvg_c',
                          'usp_silencer': cs2_icons.ICON_DIR + 'usp_silencer.vsvg_c'},
                         cs2_icons.icon_names(entries, ['usp_silencer', 'ak47', 'm14']))

    def test_defuse_kit_uses_defuser_icon_alias(self):
        entries = {cs2_icons.ICON_DIR + 'defuser.vsvg_c', cs2_icons.ICON_DIR + 'c4.vsvg_c'}
        self.assertEqual({'defuse_kit': cs2_icons.ICON_DIR + 'defuser.vsvg_c',
                          'c4': cs2_icons.ICON_DIR + 'c4.vsvg_c'},
                         cs2_icons.icon_names(entries, ['defuse_kit', 'c4', 'healthshot']))
        # The alias is strict: a raw defuse_kit icon does not satisfy the lookup.
        self.assertEqual({}, cs2_icons.icon_names({cs2_icons.ICON_DIR + 'defuse_kit.vsvg_c'}, ['defuse_kit']))

    def test_path_relative_absolute_and_implicit_lineto(self):
        self.assertEqual([[(1, 1), (3, 1), (3, 4), (1, 4)], [(5, 5), (6, 5), (6, 6)]],
                         cs2_icons.parse_path('M1,1 3,1V4l-2 0z m4,4 1,0 0,1'))

    def test_curves_end_on_their_endpoints(self):
        cubic = cs2_icons.parse_path('M0 0C0,1 1,1 1,0S2,-1 2,0')[0]
        self.assertEqual((1, 0), cubic[16])
        self.assertEqual((2, 0), cubic[-1])
        quadratic = cs2_icons.parse_path('M0 0q1 1 2 0t2 0')[0]
        self.assertEqual((4, 0), quadratic[-1])
        arc = cs2_icons.parse_path('M0 0A1 1 0 0 1 2 0')[0]
        self.assertEqual((2, 0), arc[-1])
        self.assertTrue(all(abs(math.hypot(x - 1, y) - 1) < 1e-9 for x, y in arc))
        self.assertTrue(all(y <= 1e-9 for _, y in arc))  # sweep=1 from (0,0) to (2,0) passes above (y<0).

    def test_compact_number_syntax(self):
        self.assertEqual([[(0.5, -0.5), (1.5, -1.5)]], cs2_icons.parse_path('M.5-.5l1-1'))

    def test_compact_arc_flags(self):
        spaced = cs2_icons.parse_path('M0 0A10 10 0 0 1 10 10')
        self.assertEqual(spaced, cs2_icons.parse_path('M0 0A10 10 0 01 10 10'))
        self.assertEqual(spaced, cs2_icons.parse_path('M0 0A10 10 0 0110 10'))
        self.assertEqual(spaced, cs2_icons.parse_path('M0,0a10,10,0,0,1,10,10'))
        self.assertEqual((10, 10), spaced[0][-1])
        with self.assertRaises(ValueError):
            cs2_icons.parse_path('M0 0A10 10 0 2 1 10 10')

    def test_transforms_and_hidden_fills(self):
        view, filled = cs2_icons.shapes(
            '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"><g transform="translate(2,3) scale(2)">'
            '<rect width="1" height="1"/><rect fill="none" width="5" height="5"/></g>'
            '<g style="display:none"><rect width="9" height="9"/></g></svg>')
        self.assertEqual((0, 0, 10, 10), view)
        self.assertEqual(1, len(filled))
        self.assertEqual([(2, 3), (4, 3), (4, 5), (2, 5)], filled[0][0][0])
        rotate = cs2_icons.parse_transform('rotate(90 1 1)')
        self.assertTrue(all(abs(a - b) < 1e-9 for a, b in zip(rotate, (0, 1, -1, 0, 2, 0))))

    @unittest.skipIf(numpy is None, 'numpy is not installed')
    def test_fill_rules(self):
        evenodd = cs2_icons.coverage(SQUARE_WITH_HOLE.format(rule='evenodd'), 8)
        self.assertEqual((8, 8), evenodd.shape)
        self.assertEqual(1.0, evenodd[0, 0])
        self.assertEqual(0.0, evenodd[4, 4])
        # Both subpaths wind clockwise, so nonzero fills the hole.
        self.assertEqual(1.0, cs2_icons.coverage(SQUARE_WITH_HOLE.format(rule='nonzero'), 8)[4, 4])
        # Inline style declarations may carry whitespace around keys and values.
        styled = SQUARE_WITH_HOLE.replace('fill-rule="{rule}"', 'style="fill:#fff; fill-rule : evenodd "')
        self.assertEqual(0.0, cs2_icons.coverage(styled, 8)[4, 4])
        self.assertEqual(1.0, cs2_icons.coverage(styled, 8)[0, 0])

    @unittest.skipIf(numpy is None, 'numpy is not installed')
    def test_antialiased_edges_and_scale(self):
        triangle = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 2 1"><path d="M0,1 2,0 2,1z"/></svg>'
        mask = cs2_icons.coverage(triangle, 16)
        self.assertEqual((16, 32), mask.shape)
        self.assertAlmostEqual(0.5, float(mask.mean()), delta=0.02)
        self.assertTrue(0 < mask[8, 15] < 1)


if __name__ == '__main__':
    unittest.main()
