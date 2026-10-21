"""Knife selection for export-cs2-assets and cs2_skins (Python 3 stdlib)."""
import re

# Main's contract order; ids are vdata m_szName without `weapon_`.
KNIFE_IDS = ('knife knife_t bayonet knife_css knife_flip knife_gut knife_karambit knife_m9_bayonet knife_tactical '
             'knife_falchion knife_survival_bowie knife_butterfly knife_push knife_cord knife_canis knife_ursus '
             'knife_gypsy_jackknife knife_outdoor knife_stiletto knife_widowmaker knife_skeleton knife_kukri').split()
ACTIONS = ('deploy', 'slash', 'stab', 'hit', 'hitwall', 'backstab', 'inspect')
CLIP_ROOT = 'animation/anims/viewmodel/knife/'
# Viewmodel clip folders are named after the model stem, except the CT default knife.
CLIP_FOLDER_FALLBACK = '_default_knife'
# Action -> clip-name prefixes in priority order; the clip name is `<prefix>_<suffix>`.
CLIP_ACTIONS = {
    'draw': ('draw',),
    'slash': ('light_miss1',),
    'slash_alt': ('light_miss2',),
    'slash_hit': ('light_hit1', 'light_miss1'),
    'slash_backstab': ('light_backstab',),
    'stab': ('heavy_miss1',),
    'stab_hit': ('heavy_hit1', 'heavy_miss1'),
    'backstab': ('heavy_backstab',),
    'inspect': ('lookat01',),
    'idle': ('idle1', 'idle'),
}
COMMON_SOUNDS = {
    'deploy': ('Weapon_Knife.Deploy',),
    'slash': ('Weapon_Knife.Slash',),
    'stab': ('Weapon_Knife.Stab',),
    'hit': ('Weapon_Knife.Hit',),
    'hitwall': ('Weapon_Knife.HitWall',),
    'backstab': ('Weapon_Knife.Hit.Heavy.Backstab',),
    'inspect': ('Weapon_Knife.Inspect.Turn',),
}
# Per-knife authored events replace the common event for that action.
KNIFE_SOUNDS = {
    'knife_butterfly': {'deploy': ('ButterflyKnife.draw01', 'ButterflyKnife.draw02'),
                        'backstab': ('ButterflyKnife.backstab01', 'ButterflyKnife.backstab02'),
                        'inspect': ('ButterflyKnife.look01_a', 'ButterflyKnife.look01_b', 'ButterflyKnife.look02_a',
                                    'ButterflyKnife.look02_b', 'ButterflyKnife.look03_a', 'ButterflyKnife.look03_b')},
    'knife_falchion': {'deploy': ('KnifeFalchion.draw',), 'inspect': ('KnifeFalchion.inspect',)},
    'knife_survival_bowie': {'deploy': ('KnifeBowie.draw',), 'inspect': ('KnifeBowie.LookAtStart', 'KnifeBowie.LookAtEnd')},
    'knife_push': {'deploy': ('KnifePush.Draw',), 'slash': ('KnifePush.Swish.Light',), 'stab': ('KnifePush.Attack1Heavy',),
                   'backstab': ('KnifePush.Hit.Heavy.Backstab',), 'inspect': ('KnifePush.LookAtStart', 'KnifePush.LookAtEnd')},
    'knife_ursus': {'inspect': ('Knife.Ursus.Flip.01', 'Knife.Ursus.Flip.02', 'Knife.Ursus.Flip.03',
                                'Knife.Ursus.Flip.04', 'Knife.Ursus.Flip.05')},
    'knife_widowmaker': {'deploy': ('Knife.Widow.Deploy.01',), 'inspect': ('Knife.Widow.LookAt2.Start', 'Knife.Widow.LookAt2.End')},
    'knife_stiletto': {'deploy': ('Knife.Stilleto.Draw.01',), 'inspect': ('Knife.Stilleto.Flip.01', 'Knife.Stilleto.Flip.02',
                                                                         'Knife.Stilleto.Flip.03', 'Knife.Stilleto.Flip.04')},
    'knife_flip': {'deploy': ('Knife.Flip.Draw',)},
    'knife_gypsy_jackknife': {'deploy': ('KnifeNavaja.Draw',), 'inspect': ('KnifeNavaja.Open',)},
    'knife_cord': {'inspect': ('Knife.Cord.Flip.Loop',)},
    'knife_skeleton': {'inspect': ('Weapon_Knife.Skeleton.Spin',)},
    'knife_karambit': {'inspect': ('KnifeKarambit.Catch',)},
    'knife_outdoor': {'deploy': ('Weapon_Knife.Outdoor.Draw.Click',), 'inspect': ('Weapon_Knife.Outdoor.Gear.Shake',)},
}
ICON_PREFIX = 'panorama/images/econ/default_generated/weapon_'
ICON_SUFFIX = '_light_png.vtex_c'


def resolve_knives(records, fields_of):
    """Map contract ids to vdata bodies; most knife records use numeric keys, so resolve via m_szName."""
    named = {}
    for key, body in records.items():
        name = fields_of(body).get('m_szName', '')
        if name.startswith('weapon_') and name[7:] in KNIFE_IDS:
            # Prefer the canonical `weapon_<id>` key when both an alias and a numeric record exist.
            if name not in named or key == name:
                named[name] = key
    for knife in KNIFE_IDS:
        if 'weapon_' + knife in records:
            named.setdefault('weapon_' + knife, 'weapon_' + knife)
    return {knife: records[named['weapon_' + knife]] for knife in KNIFE_IDS if 'weapon_' + knife in named}


def clip_folder(stem, folders):
    return stem if stem in folders else CLIP_FOLDER_FALLBACK if CLIP_FOLDER_FALLBACK in folders else None


def clip_map(clips):
    """Contract action -> viewmodel clip name, from clip names like `light_miss1_karambit`."""
    result = {}
    for action, prefixes in CLIP_ACTIONS.items():
        for prefix in prefixes:
            match = sorted(clip for clip in clips if re.fullmatch(re.escape(prefix) + r'_[a-z0-9_]+', clip)
                           and not re.match(re.escape(prefix) + r'_(draw|transfix|noflip)', clip))
            if match:
                result[action] = match[0]
                break
    return result


def sound_selection(knife, events):
    """Return ({event: (action, [resources])}, errors) using per-knife events before common knife events."""
    chosen, errors = {}, []
    overrides = KNIFE_SOUNDS.get(knife, {})
    for action in ACTIONS:
        names = overrides.get(action, COMMON_SOUNDS[action])
        for name in names:
            definition = events.get(name)
            resources = list(dict.fromkeys(re.findall(r'"(sounds/[^"\n]+\.vsnd)"', definition or '')))
            if resources:
                chosen[name] = (action, resources)
            else:
                errors.append('sound event without resources: ' + name)
    return chosen, errors


def icon_pairs(entries, kit_names):
    """Knife id -> lowercase kit names from econ generated icons `weapon_<id>_<kit>_light_png`."""
    pairs = {}
    ids = sorted(KNIFE_IDS, key=len, reverse=True)
    for entry in entries:
        if not (entry.startswith(ICON_PREFIX) and entry.endswith(ICON_SUFFIX)):
            continue
        stem = entry[len(ICON_PREFIX):-len(ICON_SUFFIX)]
        for knife in ids:
            kit = stem[len(knife) + 1:]
            if stem.startswith(knife + '_') and kit in kit_names:
                pairs.setdefault(knife, set()).add(kit)
                break
    return pairs
