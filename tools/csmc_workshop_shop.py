"""Deterministic Workshop trading post for Minecraft Java 1.21.1.

Call build(floor) after the lobby shell. This module owns only the east strip
x56..65, z-30..-10, yfloor..floor+6, and replaces only ws_shop villagers.
No player inventories, dropped items, or unrelated entities are touched.
Prices in gold nuggets: Weapon Case 3, Loadout Case 4, gun skin 40, knife skin 60.
The registered Loadout Case item is csmc:inventory_weapon_case.

Skin pairs were checked against the read-only local skins/index.json catalogue:
AK Asiimov 801 [.05,.70], M4A4 (m4a1) Howl 309 [0,.40], AWP Dragon Lore
344 [0,.70]; Karambit and Butterfly Fade 38 / Marble Fade 413 [0,.08],
Case Hardened 44 [0,1]. Every selected pair is in available_weapons as well as
weapons. Wear .06 is within every range; seed 420 satisfies SkinData's 0..1000
codec. Only IDs and authored commands are included, not third-party artwork.
"""
import json

from csmc_arena import fill, sign, text


GUN_SKINS = (("ak47", 801), ("m4a1", 309), ("awp", 344))
KNIFE_SKINS = (
    ("knife_karambit", 38),
    ("knife_karambit", 413),
    ("knife_karambit", 44),
    ("knife_butterfly", 38),
    ("knife_butterfly", 413),
    ("knife_butterfly", 44),
)


def _skin_stack(weapon: str, paint_kit: int) -> str:
    # skin_weapon is the unqualified registry path, not a namespaced item ID.
    return ('{id:"csmc:weapon_skin",count:1,components:{'
            f'"csmc:skin":{{paint_kit:{paint_kit},wear:0.06f,seed:420}},'
            f'"csmc:skin_weapon":{json.dumps(weapon)}}}}}')


def _offer(price: int, sell: str) -> str:
    # Both ItemCost and ItemStack use lowercase count in 1.21.1. Demand and
    # specialPrice are explicitly zero; NoAI prevents reputation-based AI updates.
    return (f'{{buy:{{id:"minecraft:gold_nugget",count:{price}}},sell:{sell},'
            'uses:0,maxUses:2147483647,xp:0,rewardExp:0b,'
            'priceMultiplier:0.0f,demand:0,specialPrice:0}')


def _merchant(floor: int, z: int, role: str, name: str,
              profession: str, offers: list[str]) -> str:
    return (f'summon minecraft:villager 62 {floor + 1} {z} '
            f'{{Tags:["ws_shop","ws_shop_{role}"],PersistenceRequired:1b,'
            'Invulnerable:1b,NoAI:1b,Silent:1b,CanPickUpLoot:0b,'
            'Rotation:[90.0f,0.0f],CustomNameVisible:1b,'
            f'CustomName:{json.dumps(text(name, color="gold"))},'
            f'VillagerData:{{type:"minecraft:plains",profession:"minecraft:{profession}",level:5}},'
            f'Offers:{{Recipes:[{",".join(offers)}]}}}}')


def build(floor: int) -> list[str]:
    """Return shop-only commands; rebuilding resets stock without touching items."""
    commands = [
        # /kill leaves living-entity corpses selectable during their death animation.
        # Retire active and role tags together before replacing the three merchants.
        'execute as @e[type=minecraft:villager,tag=ws_shop] run data merge entity @s {Tags:["ws_shop_retired"]}',
        'kill @e[type=minecraft:villager,tag=ws_shop_retired]',
        fill(56, floor + 1, -30, 65, floor + 6, -10, 'minecraft:air'),
        fill(56, floor, -30, 65, floor, -10, 'minecraft:polished_andesite'),
    ]
    cases = [
        _offer(3, '{id:"csmc:weapon_case",count:1}'),
        _offer(4, '{id:"csmc:inventory_weapon_case",count:1}'),
    ]
    stalls = (
        (-26, 'cases', 'Cases - 3 / 4 nuggets', 'librarian', cases,
         ('WEAPON CASE', '3 gold nuggets', 'Random skin', 'Use to open'),
         ('LOADOUT CASE', '4 gold nuggets', 'Carried guns', 'Use to open')),
        (-20, 'guns', 'Gun skins - 40 nuggets', 'weaponsmith',
         [_offer(40, _skin_stack(weapon, kit)) for weapon, kit in GUN_SKINS],
         ('GUN SKINS', '40 gold nuggets', 'AK / M4A4 / AWP', 'Craft with gun'),
         ('Asiimov / Howl', 'Dragon Lore', 'Fixed finish', 'Keep your gun')),
        (-14, 'knives', 'Knife skins - 60 nuggets', 'toolsmith',
         [_offer(60, _skin_stack(weapon, kit)) for weapon, kit in KNIFE_SKINS],
         ('KNIFE SKINS', '60 gold nuggets', 'Karambit / Bfly', 'Craft with knife'),
         ('Fade / Marble', 'Case Hardened', 'Fixed finish', 'Keep your knife')),
    )
    for z, role, name, profession, offers, left_label, right_label in stalls:
        commands.extend([
            # One-block counters leave villagers' heads and trade hitboxes visible
            # from the broad west aisle. Side walls and roofs do not cross it.
            fill(64, floor + 1, z - 2, 65, floor + 4, z + 2, 'minecraft:smooth_quartz'),
            fill(60, floor + 1, z - 2, 63, floor + 3, z - 2, 'minecraft:birch_planks'),
            fill(60, floor + 1, z + 2, 63, floor + 3, z + 2, 'minecraft:birch_planks'),
            fill(60, floor + 1, z - 1, 60, floor + 1, z + 1, 'minecraft:smooth_quartz'),
            fill(60, floor + 5, z - 2, 65, floor + 5, z + 2,
                 'minecraft:dark_oak_slab[type=bottom]'),
            fill(61, floor + 4, z - 1, 63, floor + 4, z + 1, 'minecraft:sea_lantern'),
            sign(59, floor + 1, z - 1, 'west', *left_label),
            sign(59, floor + 1, z + 1, 'west', *right_label),
            f'setblock 57 {floor} {z} minecraft:sea_lantern',
            _merchant(floor, z, role, name, profession, offers),
        ])
    return commands
