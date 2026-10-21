"""Firearm viewmodel clip selection for export-cs2-assets --gun-clips (Python 3 stdlib)."""
import re

CLIP_ROOT = 'animation/anims/viewmodel/'
GRAPH_ROOT = 'animation/graphs/viewmodel/'
# Per-gun data sets are variations of the shared gun graph; three guns have their own graph.
GRAPH_PATTERNS = (re.compile(re.escape(GRAPH_ROOT) + r'viewmodel_gun\.vnmgraph\+(\w+)\.vnmgraph_c$'),
                  re.compile(re.escape(GRAPH_ROOT) + r'viewmodel_gun_(\w+)\.vnmgraph_c$'))
CLIP_REFERENCE = re.compile(r'"(' + re.escape(CLIP_ROOT) + r'[^"]+\.vnmclip)"')
GRAPH_REFERENCE = re.compile(r'"(' + re.escape(GRAPH_ROOT) + r'[^"]+\.vnmgraph)"')
# Fallback when no graph names the gun: its own `<kind>/<kind>_<id>` folder, then the kind default.
DEFAULT_FOLDERS = {'pistol': 'pistol/_default_pistol', 'rifle': 'rifle/_default_rifle'}
PISTOL_TYPES = {'WEAPONTYPE_PISTOL', 'WEAPONTYPE_TASER'}
# Clip base name (gun tag removed) -> contract action. Bases absent here are reported as unmapped.
BASE_ACTIONS = {
    'draw': 'draw', 'draw2': 'draw_alt', 'draw_2': 'draw_alt', 'draw_silenced': 'draw_silenced',
    'idle': 'idle', 'idle1': 'idle', 'idle_slide_back': 'idle_empty',
    'idle_leftempty': 'idle_left_empty', 'idle_leftrightempty': 'idle_empty',
    'ironsight_fidget': 'idle_ironsight',
    'shoot1': 'fire', 'shoot_alt1': 'fire_alt', 'shoot_empty': 'fire_last', 'ironsight_shoot': 'fire_ironsight',
    'shoot_left1': 'fire_left', 'shoot_right1': 'fire_right',
    'shoot_leftlast': 'fire_left_last', 'shoot_rightlast': 'fire_right_last',
    'dryfire': 'dryfire', 'prepare_shoot': 'prime',
    'reload': 'reload', 'reload_empty': 'reload_empty', 'empty_reload': 'reload_empty',
    'reload2': 'reload_alt', 'reload2_empty': 'reload_alt_empty',
    'lookat01': 'inspect', 'lookat02': 'inspect_alt', 'lookat03': 'inspect_alt2',
    'silencer_attach': 'silencer_attach', 'silencer_detach': 'silencer_detach',
}
REQUIRED_ACTIONS = ('draw', 'idle', 'reload', 'inspect')
SHELL_RELOAD = ('nova', 'xm1014', 'sawedoff')
SILENCED = ('m4a1_silencer', 'usp_silencer')
NOTES = {
    'shell_reload': 'CS2 ships one reload clip; shell insertion loops inside it (no reload_start/loop/end clips).',
    'cycle': 'Bolt/pump cycling is baked into the fire clip; there is no separate cycle clip.',
    'elite': 'Dual Berettas alternate fire_right/fire_left; `fire` is intentionally unmapped.',
    'revolver': 'chamber_position_anim_revolver_<n> are additive cylinder poses (unmapped); prime precedes fire.',
    'default': 'No graph or own folder names this gun; clips come from the kind default folder and skeleton.',
}
NO_RELOAD = ('taser',)


def normalize(name):
    return re.sub(r'[^a-z0-9]', '', name.lower())


def graph_variations(entries):
    """Graph key (e.g. `m4a1s`, `elites`) -> compiled graph path."""
    found = {}
    for entry in entries:
        for pattern in GRAPH_PATTERNS:
            match = pattern.match(entry)
            if match:
                found[match.group(1)] = entry
    return found


def resolve_graph(names, graphs):
    """First graph whose key equals a candidate name, else the longest key prefixing one (m4a1s, usp, cz75, glock).

    `names` are the weapon id, its m_szAnimSkeleton stem and its world model folder, in that order.
    A candidate may also prefix a key one character longer (elite -> elites).
    """
    candidates = [normalize(name) for name in names if name]
    keys = {normalize(key): key for key in graphs}
    for candidate in candidates:
        if candidate in keys:
            return keys[candidate]
    prefixed = [(len(key), key) for key in keys for candidate in candidates
                if candidate.startswith(key) or (key.startswith(candidate) and len(key) - len(candidate) == 1)]
    return keys[max(prefixed)[1]] if prefixed else None


def graph_clips(texts, shared=()):
    """Clip resources referenced by a gun graph (and its inspect graph), minus shared plumbing clips.

    `shared` lists clips every gun graph references; they are dropped unless they live in the gun's own folder.
    """
    refs = list(dict.fromkeys(ref for text in texts for ref in CLIP_REFERENCE.findall(text)))
    own = [ref for ref in refs if ref not in shared]
    folders = [folder_of(ref) for ref in own if not folder_of(ref).rsplit('/', 1)[-1].startswith('_default')]
    primary = max(set(folders), key=folders.count) if folders else None
    return [ref for ref in refs if ref not in shared or folder_of(ref) == primary]


def shared_clips(per_graph):
    """Clips referenced by more than half of the gun graphs: graph plumbing such as `idle_from_activity_m249`."""
    counts = {}
    for refs in per_graph:
        for ref in refs:
            counts[ref] = counts.get(ref, 0) + 1
    return {ref for ref, count in counts.items() if count * 2 > len(per_graph)}


def folder_of(resource):
    return resource[len(CLIP_ROOT):].rsplit('/', 1)[0]


def fallback_folder(weapon, weapon_type, folders):
    kind = 'pistol' if weapon_type in PISTOL_TYPES else 'rifle'
    own = kind + '/' + kind + '_' + weapon
    return own if own in folders else DEFAULT_FOLDERS[kind] if DEFAULT_FOLDERS[kind] in folders else None


def clip_name(resource):
    return resource.rsplit('/', 1)[-1].split('.', 1)[0]


def gun_tag(clips):
    tokens = [name.rsplit('_', 1)[-1] for name in clips if '_' in name]
    return max(sorted(set(tokens)), key=tokens.count) if tokens else ''


def clip_base(name, tag):
    return re.sub(r'_' + re.escape(tag) + r'(?=_|$)', '', name) if tag else name


def action_map(clips, weapon=None):
    """({action: clip}, [unmapped clips]) from clip names like `shoot1_ak`, `reload_empty_glock`.

    Silencer clips map only for silenced guns: kind-default folders carry them for every fallback gun.
    """
    clips = [clip for clip in clips if not clip.endswith('_lgcy')]
    tag = gun_tag(clips)
    actions, unmapped = {}, []
    for clip in sorted(clips):
        action = BASE_ACTIONS.get(clip_base(clip, tag))
        if action and action.startswith('silencer_') and weapon not in SILENCED:
            action = None
        if action and action not in actions:
            actions[action] = clip
        else:
            unmapped.append(clip)
    return actions, unmapped


def missing_actions(weapon, actions):
    required = [action for action in REQUIRED_ACTIONS if not (action == 'reload' and weapon in NO_RELOAD)]
    required += ['fire_left', 'fire_right'] if weapon == 'elite' else ['fire']
    if weapon in SILENCED:
        required += ['silencer_attach', 'silencer_detach']
    if weapon == 'revolver':
        required += ['prime', 'fire_alt']
    return [action for action in required if action not in actions]


def notes(weapon, resolution=''):
    result = []
    if weapon in SHELL_RELOAD:
        result.append(NOTES['shell_reload'])
    if weapon in SHELL_RELOAD + ('awp', 'ssg08', 'mag7'):
        result.append(NOTES['cycle'])
    if weapon in ('elite', 'revolver'):
        result.append(NOTES[weapon])
    if resolution.startswith('default:'):
        result.append(NOTES['default'])
    return result


def viewmodel_status(errors, missing):
    """`failed` when any clip errored or a required action has no clip, else `ok`."""
    return 'failed' if errors or missing else 'ok'


def viewmodel_cache_ok(record):
    """A firearm record may be reused only after a completed, successful viewmodel clip export."""
    return record.get('viewmodel_status') == 'ok' and bool(record.get('viewmodel_clips'))


def prune_candidates(names, selected, failed):
    """Files in clips/ to delete: those whose clip stem is no longer selected. Nothing after any failure,
    so a selected clip whose refresh failed keeps its previous files."""
    if failed:
        return []
    return sorted(name for name in names if name.split('.', 1)[0] not in selected)


def duration(model):
    """Longest animation input time in a glTF (accessor max is mandatory for animation inputs)."""
    times = [model['accessors'][sampler['input']].get('max', [0])[0]
             for animation in model.get('animations', []) for sampler in animation.get('samplers', [])]
    return round(max(times), 4) if times else 0.0


def skeleton_report(model, world_nodes):
    """Compare the clip's weapon skeleton (descendants of its `*.vnmskel` node) with the world model's bones."""
    nodes = model.get('nodes', [])
    root = next((index for index, node in enumerate(nodes) if node.get('name', '').startswith('animation/skeletons/weapons/')), None)
    if root is None:
        return {'skeleton': None, 'matched': 0, 'unmatched': [], 'clip_only': []}
    bones, stack = [], list(nodes[root].get('children', []))
    while stack:
        index = stack.pop()
        bones.append(nodes[index].get('name', ''))
        stack.extend(nodes[index].get('children', []))
    world = [name for name in world_nodes if name and '/' not in name and '.vmdl' not in name]
    return {'skeleton': nodes[root]['name'], 'matched': sum(name in bones for name in world),
            'unmatched': sorted(name for name in world if name not in bones),
            'clip_only': sorted(set(bones) - set(world))}
