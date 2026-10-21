"""Grenade-only audio aliases; no firearm action or animation semantics."""
import re

from cs2_skins import parse_kv

GRENADE_ALIASES = {
    'hegrenade': 'HEGrenade', 'flashbang': 'Flashbang', 'smokegrenade': 'SmokeGrenade',
    'molotov': 'Molotov', 'incgrenade': 'IncGrenade', 'decoy': 'Decoy',
}
ACTIONS = ('throw', 'bounce', 'detonate', 'loop', 'extinguish', 'decoy')


def sound_selection(weapon, events):
    """Resolve real event children, retaining their names and direct source recordings.

    CS sound-graph mixing/curves are not recreated. The cache exposes recordings by
    gameplay action. Decoy uses an authored fixed AK report, not a firearm FIRE event.
    """
    alias = GRENADE_ALIASES[weapon]
    roots = {}
    for name in events:
        prefix, _, suffix = name.partition('.')
        if prefix != alias:
            continue
        suffix = suffix.lower()
        if suffix == 'throw':
            roots[name] = 'throw'
        elif suffix.startswith('bounce'):
            roots[name] = 'bounce'
        elif suffix in ('explode', 'start'):
            roots[name] = 'detonate'
        elif suffix == 'loop':
            roots[name] = 'loop'
        elif suffix == 'extinguish':
            roots[name] = 'extinguish'
    if weapon in ('hegrenade', 'decoy'):
        roots['BaseGrenade.Explode'] = 'detonate'
    if weapon == 'smokegrenade':
        roots['BaseSmokeEffect.Sound'] = 'detonate'
    if weapon in ('molotov', 'incgrenade'):
        roots['Inferno.Loop'] = 'loop'
        roots['Molotov.Extinguish'] = 'extinguish'
        roots['Inferno.FadeOut'] = 'extinguish'
    if weapon == 'decoy':
        roots['Weapon_AK47.Single'] = 'decoy'
    chosen, errors, visited = {}, [], set()

    def resolve(name, action):
        if name in visited:
            return
        visited.add(name)
        if name not in events:
            errors.append('missing referenced sound event: ' + name)
            return
        body = events[name]
        sources = list(dict.fromkeys(re.findall(r'"(sounds/[^"\n]+\.vsnd)"', body)))
        if sources:
            chosen[name] = (action, sources)
        document = parse_kv('{' + body + '}', kv3=True)
        if document.get('enable_child_events', False):
            for key, children in document.items():
                if key.startswith('soundevent_'):
                    for child in children if isinstance(children, list) else [children]:
                        if child:
                            resolve(child, action)

    for name, action in roots.items():
        resolve(name, action)
    return chosen, errors
