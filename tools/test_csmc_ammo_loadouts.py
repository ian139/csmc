"""Arena loadouts must replenish every ammunition category after clearing carried supplies."""
import unittest

import csmc_arena
import csmc_pvp_arena


class AmmoLoadoutTest(unittest.TestCase):
    def test_both_arenas_supply_all_four_ammunition_categories(self):
        expected = {
            'give @s csmc:pistol_ammo 60',
            'give @s csmc:rifle_ammo 180',
            'give @s csmc:sniper_ammo 30',
            'give @s csmc:shotgun_shells 32',
        }
        for arena in (csmc_arena, csmc_pvp_arena):
            with self.subTest(arena=arena.NS):
                loadout = arena.build(232)['loadout']
                for command in expected:
                    self.assertEqual(1, loadout.count(command), command)


if __name__ == '__main__':
    unittest.main()
