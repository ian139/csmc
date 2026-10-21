"""Explicit recovered equipment selection and timeline metadata (Python 3 stdlib)."""
import math
import re

EQUIPMENT_IDS = ('healthshot', 'c4', 'defuse_kit')
MODELS = {
    'healthshot': 'weapons/models/healthshot/weapon_healthshot.vmdl',
    'c4': 'weapons/models/c4/weapon_c4.vmdl',
    'defuse_kit': 'weapons/models/defuser/defuser.vmdl',
}
CLIP_ROOT = 'animation/anims/viewmodel/equipment/'
CLIPS = {
    'healthshot': ('draw_healthshot', 'idle_healthshot', 'inject_healthshot'),
    'c4': ('draw_c4', 'idle_c4', 'lookat01_c4', 'lookat02_c4', 'lookat03_c4', 'plant_c4'),
    'defuse_kit': (),
}
ANIMATIONS = {
    'healthshot': {'draw': 'draw_healthshot', 'idle': 'idle_healthshot', 'use': 'inject_healthshot'},
    'c4': {'draw': 'draw_c4', 'idle': 'idle_c4', 'inspect': 'lookat01_c4',
           'inspect_alt': 'lookat02_c4', 'inspect_alt2': 'lookat03_c4', 'use': 'plant_c4'},
    'defuse_kit': {},
}
SOUNDS = {
    'healthshot': {'draw': ('IncGrenade.Draw',), 'prepare': ('Healthshot.Prepare',),
                   'inject': ('Healthshot.Thud',), 'success': ('Healthshot.Success',)},
    'c4': {'draw': ('c4.draw', 'c4.draw.beep', 'c4.draw.grab'),
           'plant': ('c4.plant', 'c4.plantquiet', 'c4.keypressquiet'),
           'beep': ('C4.PlantSound', 'C4.PlantSoundB', 'C4.PlantSound_10sec', 'C4.PlantSoundB_10sec'),
           'initiate': ('c4.initiate',), 'disarm_start': ('c4.disarmstart',),
           'disarm_finish': ('c4.disarmfinish',), 'explode': ('c4.explode',)},
    'defuse_kit': {'equip': ('defuser.equip',)},
}


def clip_sources(equipment):
    return {name: CLIP_ROOT + equipment + '/' + name + '.vnmclip_c' for name in CLIPS[equipment]}


def sound_selection(equipment, events):
    """Choose only explicit equipment events; passive defuser equip is a direct owned sample."""
    chosen, errors = {}, []
    for action, names in SOUNDS[equipment].items():
        for name in names:
            resources = (['sounds/items/defuser_equip.vsnd'] if equipment == 'defuse_kit' else
                         list(dict.fromkeys(re.findall(r'"(sounds/[^"\n]+\.vsnd)"', events.get(name, '')))))
            if resources:
                chosen[name] = (action, resources)
            else:
                errors.append('sound event without resources: ' + name)
    return chosen, errors


def duration(text):
    """Compiled duration survives glTF optimization of constant-pose idle channels."""
    duration_match = re.search(r'\bm_flDuration\s*=\s*([\d.eE+-]+)', text)
    if not duration_match:
        raise ValueError('compiled clip duration missing')
    duration = float(duration_match[1])
    if not math.isfinite(duration) or duration < 0:
        raise ValueError('invalid compiled clip duration')
    return duration


def markers(text):
    """Read compiled DATA event times: normalized values times the actual clip duration.

    Compiled events, unlike reconstructed vnmclip authoring frame positions, carry
    normalized timeline fractions. Preserve all authored fields as well as seconds.
    """
    seconds = duration(text)
    result = []
    for match in re.finditer(r'\{\s*_class\s*=\s*"(CNm[^"\n]+Event)"', text):
        depth, end = 1, match.end()
        # Ignore braces in strings; event fields may include nested curve objects.
        for token in re.finditer(r'"(?:\\.|[^"\\])*"|[{}]', text[end:]):
            if token[0] == '{':
                depth += 1
            elif token[0] == '}':
                depth -= 1
                if not depth:
                    end += token.start()
                    break
        else:
            raise ValueError('unterminated compiled clip event')
        body = text[match.end():end]
        fields = dict(re.findall(r'\b(\w+)\s*=\s*"([^"\n]*)"', body))
        timing = {}
        for key in ('m_flStartTime', 'm_flDuration'):
            value = re.search(r'\b' + key + r'\s*=\s*\{\s*m_flValue\s*=\s*([\d.eE+-]+)', body)
            if not value or not math.isfinite(float(value[1])):
                raise ValueError('compiled event time missing or invalid: ' + key)
            timing[key] = float(value[1])
        result.append({'type': match[1], 'name': fields.get('m_name') or fields.get('m_ID') or fields.get('m_syncID', ''),
                       'time_seconds': round(timing['m_flStartTime'] * seconds, 6),
                       'duration_seconds': round(timing['m_flDuration'] * seconds, 6),
                       'normalized_time': timing['m_flStartTime'], 'normalized_duration': timing['m_flDuration'],
                       'fields': fields, 'compiled_event': text[match.start():end + 1].strip()})
    return result
