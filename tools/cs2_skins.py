"""Local-only paint-kit input export. No network access or third-party Python dependencies."""
import collections
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import tempfile

from cs2_knives import icon_pairs, resolve_knives

STYLES = {1: 'solid_color', 2: 'hydrographic', 3: 'spray_paint', 4: 'anodized',
          5: 'anodized_multicolor', 6: 'anodized_airbrushed', 7: 'custom_paint_job',
          8: 'patina', 9: 'gunsmith', 10: 'case_hardening'}
# Historical Source 1 material stems are not the CS2 firearm ids.
LEGACY = dict(zip(
    'ak47 aug awp bizon cz75a deagle elite famas fiveseven g3sg1 galilar glock hkp2000 m249 m4a1 m4a1_silencer mac10 mag7 mp5sd mp7 mp9 negev nova p250 p90 revolver sawedoff scar20 sg556 ssg08 tec9 ump45 usp_silencer xm1014'.split(),
    'rif_ak47 rif_aug snip_awp smg_bizon pist_cz_75 pist_deagle pist_elite rif_famas pist_fiveseven snip_g3sg1 rif_galilar pist_glock18 pist_hkp2000 mach_m249para rif_m4a1 rif_m4a1_s smg_mac10 shot_mag7 smg_mp5sd smg_mp7 smg_mp9 mach_negev shot_nova pist_p250 smg_p90 pist_revolver shot_sawedoff snip_scar20 rif_sg556 snip_ssg08 pist_tec9 smg_ump45 pist_223 shot_xm1014'.split()))
TOKEN = re.compile(r'<!--.*?-->|//[^\n]*|/\*.*?\*/|"(?:\\.|[^"\\])*"|[{}\[\]=,:]|[^\s{}\[\]=,:]+', re.S)
PAIR = re.compile(r'^\[([\w-]+)\]weapon_(\w+)$')
REPO = Path(__file__).resolve().parent.parent


def checked_cache(cache, repo=REPO, create=True):
    """Resolved cache root, refused when it lies inside the repository (exported Valve assets must never be committed)
    or contains symlinks (every export destination must stay inside it). Raises ValueError before anything is written.
    """
    cache = Path(cache).expanduser().resolve()
    repo = Path(repo).resolve()
    if cache == repo or cache.is_relative_to(repo):
        raise ValueError('cache must be outside the repository; exported Valve assets must not be committed')
    if create:
        cache.mkdir(parents=True, exist_ok=True)
    elif not cache.is_dir():
        raise ValueError('cache must be an existing directory')
    if any(path.is_symlink() for path in cache.rglob('*')):
        raise ValueError('cache contents must not contain symlinks; export destinations must remain inside the external cache')
    return cache


def publish(path, write):
    """Atomically replaces `path` with what `write(handle)` writes to a fresh, exclusively created temporary file in
    the same directory; the temporary file is removed on failure, so `path` keeps its previous content."""
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temp = tempfile.mkstemp(dir=path.parent, prefix='.' + path.name + '.', suffix='.tmp')
    try:
        if hasattr(os, 'fchmod'):   # Windows before Python 3.13 has no fchmod; keep native permissions there
            os.fchmod(fd, 0o644)
        with os.fdopen(fd, 'wb') as handle:
            fd = None
            write(handle)
        os.replace(temp, path)
    except BaseException:
        if fd is not None:
            os.close(fd)
        try:
            os.unlink(temp)
        except FileNotFoundError:
            pass
        raise


def save(path, value):
    publish(path, lambda handle: handle.write((json.dumps(value, indent=2) + '\n').encode()))


def parse_kv(text, kv3=False):
    """KV1 repeated object sections merge; KV3 supports typed resources, arrays and scalars."""
    tokens = [m.group() for m in TOKEN.finditer(text.lstrip('\ufeff'))
              if not m.group().startswith(('//', '/*', '<!--'))]
    pos = 0

    def scalar(token):
        if token.startswith('"'):
            return re.sub(r'\\(["\\])', r'\1', token[1:-1])
        if kv3:
            if token in ('true', 'false'): return token == 'true'
            try: return float(token) if any(c in token for c in '.eE') else int(token)
            except ValueError: pass
        return token

    def value():
        nonlocal pos
        if pos >= len(tokens): raise ValueError('Missing KeyValues value')
        token = tokens[pos]
        pos += 1
        if token == '{': return obj(True)
        if token == '[':
            values = []
            while pos < len(tokens) and tokens[pos] != ']':
                if tokens[pos] == ',': pos += 1
                else: values.append(value())
            if pos == len(tokens): raise ValueError('Unclosed KeyValues array')
            pos += 1
            return values
        if token in ('}', ']'): raise ValueError('Unexpected KeyValues closer')
        if kv3 and pos < len(tokens) and tokens[pos] == ':':
            pos += 1
            return value()
        return scalar(token)

    def obj(closed):
        nonlocal pos
        out = {}
        while pos < len(tokens):
            if tokens[pos] == '}':
                if not closed: raise ValueError('Unexpected KeyValues closer')
                pos += 1
                return out
            if tokens[pos] == ',': pos += 1; continue
            key = scalar(tokens[pos]); pos += 1
            if kv3 and pos < len(tokens) and tokens[pos] == '=': pos += 1
            val = value()
            if key in out and isinstance(out[key], dict) and isinstance(val, dict):
                out[key].update(val)
            else: out[key] = val
        if closed: raise ValueError('Unclosed KeyValues object')
        return out

    result = value() if kv3 and tokens and tokens[0] == '{' else obj(False)
    if pos != len(tokens): raise ValueError('Trailing KeyValues tokens')
    return result


def walk(obj):
    if isinstance(obj, dict):
        yield obj
        for val in obj.values(): yield from walk(val)
    elif isinstance(obj, list):
        for val in obj: yield from walk(val)


def paint_kits(text, localization='', entries=()):
    game = parse_kv(text)['items_game']
    kits = game['paint_kits']
    local = {}
    if localization:
        lang = parse_kv(localization)
        local = {k.lower(): v for k, v in lang.get('lang', {}).get('Tokens', {}).items() if isinstance(v, str)}
    pairs = collections.defaultdict(set)
    # Loot lists and item sets contain canonical [paint]weapon_id keys. Rarity alone has no weapon association.
    for block in ('client_loot_lists', 'item_sets'):
        for obj in walk(game.get(block, {})):
            for key in obj:
                match = PAIR.fullmatch(key)
                if match: pairs[match[1]].add(match[2])
    # Knife finishes have no schema association; the VPK's generated econ icons name each knife×kit pair.
    names = {kit['name'].lower(): kit['name'] for kit in kits.values() if isinstance(kit, dict) and 'name' in kit}
    for knife, found in icon_pairs(entries, names).items():
        for kit in found: pairs[names[kit]].add(knife)
    default = kits.get('0', {})
    result = []
    for ident, kit in kits.items():
        if not ident.isdigit() or not isinstance(kit, dict): continue
        name = kit.get('name', '')
        if name not in pairs or int(ident) >= 10000: continue
        tag = kit.get('description_tag', '').lstrip('#').lower()
        result.append({'paint_kit': int(ident), 'name': name,
                       'display_name': local.get(tag, name), 'style': int(kit.get('style', 0)),
                       'wear_min': float(kit.get('wear_remap_min', default.get('wear_remap_min', 0))),
                       'wear_max': float(kit.get('wear_remap_max', default.get('wear_remap_max', 1))),
                       'rarity': game.get('paint_kits_rarity', {}).get(name, 'rare'),
                       'weapons': sorted(pairs[name]), 'available_weapons': [],
                       'legacy_model': kit.get('use_legacy_model') == '1',
                       'composite_material': kit.get('composite_material_path', 'weapons/paints/legacy/' + name + '.vcompmat')})
    return sorted(result, key=lambda k: k['paint_kit'])


class Vpk:
    def __init__(self, path):
        self.path = path
        self.entries = {}
        with path.open('rb') as stream:
            signature, version, size = struct.unpack('<III', stream.read(12))
            if signature != 0x55aa1234 or version not in (1, 2): raise ValueError('Unsupported VPK index')
            if version == 2: stream.read(16)
            tree = stream.read(size)
        pos = 0
        def string():
            nonlocal pos
            end = tree.index(b'\0', pos)
            out = tree[pos:end].decode('utf-8'); pos = end + 1
            return out
        while True:
            ext = string()
            if not ext: break
            while True:
                folder = string()
                if not folder: break
                while True:
                    name = string()
                    if not name: break
                    crc, preload, archive, offset, length, terminator = struct.unpack_from('<IHHIIH', tree, pos)
                    pos += 18 + preload
                    if terminator != 0xffff: raise ValueError('Invalid VPK directory entry')
                    key = (folder + '/' if folder != ' ' else '') + name + ('.' + ext if ext != ' ' else '')
                    self.entries[key] = (archive, crc)

    def chunk(self, source):
        archive = self.entries[source][0]
        return self.path if archive == 0x7fff else self.path.with_name(self.path.name.replace('_dir.vpk', '_%03d.vpk' % archive))

    def present(self, source):
        return source in self.entries and self.chunk(source).is_file()


def material_params(doc):
    params = {}
    bounds = {}
    for obj in walk(doc):
        key = obj.get('m_name')
        if key:
            for field in ('m_nValue', 'm_flValue', 'm_value', 'm_pValue'):
                if field in obj: params[key] = obj[field]; break
        key = obj.get('m_strName')
        if not key: continue
        if 'm_cValueColor4' in obj:
            params[key] = [n / 255 for n in obj['m_cValueColor4']]
        elif 'm_strTextureRuntimeResourcePath' in obj:
            params[key] = obj['m_strTextureRuntimeResourcePath']
        elif 'm_bValueBoolean' in obj: params[key] = obj['m_bValueBoolean']
        elif 'm_nValueIntX' in obj: params[key] = obj['m_nValueIntX']
        elif 'm_flValueFloatX' in obj:
            vals = [obj['m_flValueFloat' + c] for c in 'XYZW' if 'm_flValueFloat' + c in obj]
            params[key] = vals[0] if len(vals) == 1 else vals
            if obj.get('m_bHasFloatBounds'):
                bounds[key] = {'min': [obj.get('m_flValueFloat' + c + '_Min', n) for c, n in zip('XYZW', vals)],
                               'max': [obj.get('m_flValueFloat' + c + '_Max', n) for c, n in zip('XYZW', vals)]}
    return params, bounds


SHINE_SCHEMA = 1
GRENADES = {'hegrenade', 'flashbang', 'smokegrenade', 'molotov', 'incgrenade', 'decoy'}
# Paint styles CS2 lays over bare metal (anodized candy coats: Fade, Doppler, Marble Fade, ...).
METALLIC_STYLES = {4, 5, 6}
# Multicolour anodized coats (Doppler, Fade, Marble Fade) visibly change hue as the weapon turns in CS2.
CANDY_STYLES = {5, 6}
CANDY_PEARLESCENCE = 0.35
# Unskinned finishes: CS2's default weapon materials are worn painted metal; knives are polished steel.
DEFAULT_SHINE = {'gun': {'roughness': 0.55, 'metalness': 0.45, 'pearlescence': 0.0},
                 'knife': {'roughness': 0.3, 'metalness': 0.9, 'pearlescence': 0.0}}


def _clamp(value, low=0.0, high=1.0):
    try:
        value = float(value)
    except (TypeError, ValueError):
        return low
    return low if value != value else min(high, max(low, value))


def shine_params(params, effective_style):
    """Scalar specular inputs of one paint kit: the CS2 paint shader's roughness, metalness and pearlescence.

    `g_vPaintMetalness` holds one metalness per paint colour slot; the strongest slot wins. Anodized styles are
    clear coats over bare metal, so they are at least 0.8 metallic. `g_flPearlescentScale` is the paint shader's
    view-angle hue shift (Doppler/Fade use about 1-6); it is normalised to 0..1 by dividing by 6. Multicolour
    anodized coats are at least CANDY_PEARLESCENCE so their angle-dependent colour reads in Minecraft's lighting.
    """
    metal = params.get('g_vPaintMetalness')
    metalness = max((_clamp(v) for v in metal), default=0.0) if isinstance(metal, list) else _clamp(metal)
    if effective_style in METALLIC_STYLES:
        metalness = max(metalness, 0.8)
    roughness = _clamp(params.get('g_flPaintRoughness', 0.6), 0.04, 1.0)
    pearl = _clamp(abs(_clamp(params.get('g_flPearlescentScale', 0.0), -100, 100)) / 6.0)
    if effective_style in CANDY_STYLES:
        pearl = max(pearl, CANDY_PEARLESCENCE)
    return {'shine_schema': SHINE_SCHEMA, 'roughness': round(roughness, 4), 'metalness': round(metalness, 4),
            'pearlescence': round(pearl, 4), 'style': effective_style}


SHINE_MASK_EDGE = 512


def shine_mask_name(mesh_set):
    """File of one body's mask in `skins/<weapon>/_default/`; the client reads it for the mesh set it draws."""
    return f'shine_mask_{mesh_set}.png'


def primary_orms(model_dir, weapon):
    """Mesh set (`body_legacy`, `body_hd`, ...) -> ORM image of its painted atlas: the base-colour texture drawing the
    most vertices in that set, as the renderer picks it. glTF ORM packs roughness in G and metalness in B."""
    gltf = model_dir / f'{weapon}.gltf'
    if not gltf.is_file():
        return {}
    model = json.loads(gltf.read_text())
    textures, images, accessors = model.get('textures', []), model.get('images', []), model.get('accessors', [])
    materials = model.get('materials', [])
    counts, orm = collections.defaultdict(collections.Counter), {}
    for mesh in model.get('meshes', []):
        mesh_set = mesh.get('name', '').rsplit('.', 1)[-1]
        for primitive in mesh.get('primitives', []):
            pbr = materials[primitive['material']].get('pbrMetallicRoughness', {}) if 'material' in primitive else {}
            color = pbr.get('baseColorTexture', {}).get('index')
            if color is None:
                continue
            counts[mesh_set][color] += accessors[primitive['attributes']['POSITION']].get('count', 0)
            rough = pbr.get('metallicRoughnessTexture', {}).get('index')
            if rough is not None:
                orm.setdefault(color, images[textures[rough]['source']].get('uri'))
    result = {}
    for mesh_set, colors in counts.items():
        uri = orm.get(colors.most_common(1)[0][0])
        if uri and (model_dir / uri).is_file():
            result[mesh_set] = model_dir / uri
    return result


def shine_mask(orm_path, cavity_path, output, edge=SHINE_MASK_EDGE):
    """Writes the weapon's per-texel shine mask in its painted-atlas UVs: R roughness and G metalness of the bare
    weapon (ORM G/B), B paint-wear exposure (linear cavity red, where CS2 wear first bites through paint)."""
    import numpy
    from PIL import Image

    with Image.open(orm_path) as source:
        scale = min(1.0, edge / max(source.size))
        size = (max(1, round(source.width * scale)), max(1, round(source.height * scale)))
        orm = numpy.asarray(source.convert('RGB').resize(size, Image.BILINEAR))
    mask = numpy.zeros(orm.shape, dtype=numpy.uint8)
    mask[..., 0], mask[..., 1] = orm[..., 1], orm[..., 2]
    if cavity_path is not None and cavity_path.is_file():
        with Image.open(cavity_path) as cavity:
            red = numpy.asarray(cavity.convert('RGB').resize(size, Image.BILINEAR))[..., 0] / 255.0
        linear = numpy.where(red <= 0.04045, red / 12.92, ((red + 0.055) / 1.055) ** 2.4)
        mask[..., 2] = numpy.round(linear * 255).astype(numpy.uint8)
    image = Image.fromarray(mask, 'RGB')
    publish(output, lambda handle: image.save(handle, format='PNG'))
    return size


def refresh_shine(cache):
    """Adds shine.json beside every published params.json, plus `_default/shine.json` and one
    `_default/shine_mask_<mesh set>.png` per gun/knife body. Only these shine files are written; params.json, inputs
    and every other cache record stay untouched.
    """
    root = Path(cache) / 'skins'
    manifest = Path(cache) / 'manifest.json'
    kinds = {}
    if manifest.is_file():
        kinds = {k: v.get('kind') for k, v in json.loads(manifest.read_text()).get('weapons', {}).items()}
    pairs = defaults = masks = 0
    cavities = {}
    for params_path in sorted(root.glob('*/*/params.json')):
        record = json.loads(params_path.read_text())
        if record.get('kind') == 'glove':
            continue
        effective = record.get('effective_style') or int(record.get('params', {}).get('F_PAINT_STYLE', 0)) + 1
        save(params_path.with_name('shine.json'), shine_params(record.get('params', {}), effective))
        cavity = record.get('inputs', {}).get('cavity')
        if cavity:
            weapon = params_path.parent.parent.name
            body = 'body_legacy' if record.get('legacy_model') or kinds.get(weapon) == 'knife' else 'body_hd'
            cavities.setdefault((weapon, body), params_path.with_name(cavity))
        pairs += 1
    for weapon, kind in sorted(kinds.items()):
        if kind not in (None, 'gun', 'knife') or weapon in GRENADES or weapon.startswith('_'):
            continue
        values = DEFAULT_SHINE['knife' if kind == 'knife' else 'gun']
        save(root / weapon / '_default' / 'shine.json', {'shine_schema': SHINE_SCHEMA, **values, 'style': 0})
        defaults += 1
        for mesh_set, orm in sorted(primary_orms(Path(cache) / 'models' / weapon, weapon).items()):
            shine_mask(orm, cavities.get((weapon, mesh_set)), root / weapon / '_default' / shine_mask_name(mesh_set))
            masks += 1
    return {'pairs': pairs, 'defaults': defaults, 'masks': masks}


def export_skins(vpk_path, cli, cache, selected='all'):
    vpk = Vpk(vpk_path)
    root = cache / 'skins'
    root.mkdir(exist_ok=True)
    logs = cache / 'logs'; logs.mkdir(exist_ok=True)
    missing = set()
    errors = []
    def require(source):
        if source not in vpk.entries:
            errors.append('Not in VPK index: ' + source)
            return False
        if not vpk.present(source):
            missing.add(vpk.chunk(source).name)
            return False
        return True

    def run(options, label):
        cmd = [str(cli), '-i', str(vpk_path)] + options
        done = subprocess.run(cmd, cwd=cache, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        (logs / ('skins-' + label + '.log')).write_text(done.stdout)
        if done.returncode: errors.append(label + ': CLI exit ' + str(done.returncode))
        for chunk in re.findall(r'pak01_\d{3}\.vpk', done.stdout):
            if not (vpk_path.parent / chunk).is_file(): missing.add(chunk)
        return done.stdout

    for source, target in [('scripts/items/items_game.txt', cache / 'data/items_game.txt'),
                           ('resource/csgo_english.txt', cache / 'data/csgo_english.txt')]:
        if require(source):
            target.parent.mkdir(parents=True, exist_ok=True)
            run(['--vpk_filepath', source, '-d', '-o', str(target)], target.stem)
    game_file = cache / 'data/items_game.txt'
    if not game_file.is_file(): raise RuntimeError('items_game is unavailable; missing chunks: ' + ', '.join(sorted(missing)))
    locale = cache / 'data/csgo_english.txt'
    kits = paint_kits(game_file.read_text(encoding='utf-8-sig'), locale.read_text(encoding='utf-8-sig') if locale.exists() else '', vpk.entries)
    rarity_definitions = parse_kv(game_file.read_text(encoding='utf-8-sig'))['items_game'].get('rarities', {})
    rarity_weights = {name: int(fields['weight']) for name, fields in rarity_definitions.items()
                      if isinstance(fields, dict) and 'weight' in fields and int(fields['weight']) > 0}
    # Data's list of firearm types, rather than knife/glove name heuristics.
    datafile = cache / 'data/weapons.vdata'
    refreshed = datafile.with_suffix('.vdata.new')
    if refreshed.exists(): refreshed.unlink()
    errors_before = len(errors)
    run(['--vpk_filepath', 'scripts/weapons.vdata_c', '-d', '-o', str(refreshed)], 'weapon-data')
    if len(errors) != errors_before or not refreshed.is_file():
        raise RuntimeError('Cannot refresh weapon data from the selected VPK; see skins-weapon-data.log')
    data = parse_kv(refreshed.read_text(), kv3=True)
    refreshed.replace(datafile)
    firearms = {k[7:]: v for k, v in data.items() if k.startswith('weapon_') and isinstance(v, dict)
                and v.get('m_WeaponType') in {'WEAPONTYPE_PISTOL', 'WEAPONTYPE_RIFLE', 'WEAPONTYPE_SUBMACHINEGUN',
                                            'WEAPONTYPE_SNIPER_RIFLE', 'WEAPONTYPE_SHOTGUN', 'WEAPONTYPE_MACHINEGUN', 'WEAPONTYPE_TASER'}
                and not k.endswith('_prefab')}
    knives = resolve_knives(data, lambda body: body if isinstance(body, dict) else {})
    weapons = {**firearms, **knives}
    # `knives` selects every knife id; otherwise ids are comma/space separated.
    tokens = set(re.split(r'[,\s]+', selected.strip()))
    selection = set(weapons) if selected == 'all' else (tokens - {'knives'}) | (set(knives) if 'knives' in tokens else set())
    if selection - weapons.keys(): raise ValueError('Unknown weapon ids: ' + ', '.join(sorted(selection - weapons.keys())))
    for kit in kits: kit['weapons'] = sorted(set(kit['weapons']) & weapons.keys())
    kits = [kit for kit in kits if kit['weapons']]
    docs = {}
    source_dir = root / '_sources'; source_dir.mkdir(exist_ok=True)

    def load_docs(sources):
        pending = []
        for source in sorted(set(sources)):
            if source in docs or not require(source): continue
            path = source_dir / (hashlib.sha256((source + str(vpk.entries[source][1])).encode()).hexdigest()[:24] + '.json')
            if path.is_file(): docs[source] = json.loads(path.read_text())
            else: pending.append((source, path))
        for start in range(0, len(pending), 80):
            group = pending[start:start + 80]
            text = run(['--vpk_filepath', ','.join(s for s, _ in group), '-b', 'DATA'], 'data-' + str(start))
            parts = re.split(r'(?m)^\[\d+/\d+\] (\S+)\s*$', text)
            paths = dict(group)
            for i in range(1, len(parts), 2):
                source, body = parts[i], parts[i + 1]
                marker = body.find('<!-- kv3')
                if source not in paths or marker < 0: continue
                try:
                    doc = parse_kv(body[marker:], kv3=True)
                    docs[source] = doc
                    save(paths[source], doc)
                except ValueError as error: errors.append(source + ': ' + str(error))

    sources = {}
    for kit in kits:
        # Engine resource lookup is case-insensitive (e.g. kit cu_bizon_Curse has a lowercase resource).
        graph = (kit['composite_material'] + '_c').lower()
        legacy = 'materials/models/weapons/customization/paints/vmats/' + kit['name'].lower() + '.vmat_c'
        # A compiled legacy paint VMAT contains the same paint inputs without requiring legacy vcompmat chunk 504.
        sources[kit['paint_kit']] = graph if vpk.present(graph) else legacy if vpk.present(legacy) else graph
    input_sources = {}
    for weapon, fields in weapons.items():
        model = fields.get('m_szWorldModel', '')
        folder = model.rsplit('/', 1)[0]
        hd = sorted(p for p in vpk.entries if p.startswith(folder + '/materials/composite_inputs/') and p.endswith('_composite_inputs.vmat_c')) if folder else []
        stem = LEGACY.get(weapon)
        old = 'materials/models/weapons/customization/' + stem + '/' + stem + '_composite_inputs.vmat_c' if stem else ''
        # Knives ship only HD composite inputs; their legacy-flagged kits paint the same inputs.
        input_sources[(weapon, True)] = old if weapon not in knives else hd[0] if hd else ''
        input_sources[(weapon, False)] = hd[0] if hd else ''
    load_docs(list(sources.values()) + [p for p in input_sources.values() if p])
    # Graph templates supply defaults (including wear/grunge); collect every material/texture dependency, not just existing PNGs.
    templates = set()
    for doc in list(docs.values()):
        for obj in walk(doc):
            template = obj.get('m_strSpecificContainerMaterial')
            if template: templates.add(template + '_c')
    load_docs(templates)
    params_by_kit = {}
    all_textures = set()
    for kit in kits:
        doc = docs.get(sources[kit['paint_kit']], {})
        params, bounds = {}, {}
        for obj in walk(doc):
            template = obj.get('m_strSpecificContainerMaterial')
            if template and template + '_c' in docs:
                params.update(material_params(docs[template + '_c'])[0])
        values, ranges = material_params(doc); params.update(values); bounds.update(ranges)
        params_by_kit[kit['paint_kit']] = (params, bounds)
        for val in params.values():
            if isinstance(val, str) and val.endswith('.vtex'): all_textures.add(val + '_c')
    for p in input_sources.values():
        if p in docs:
            for val in material_params(docs[p])[0].values():
                if isinstance(val, str) and val.endswith('.vtex'): all_textures.add(val + '_c')
    # ForceLDR is important for position/surface resources that would otherwise export EXR.
    textures = root / '_textures'; textures.mkdir(exist_ok=True)
    decoded = {}
    pending = []
    for source in sorted(all_textures):
        if not require(source): continue
        target = textures / ('raw_' + hashlib.sha256((source + str(vpk.entries[source][1])).encode()).hexdigest()[:24] + '.png')
        if target.is_file(): decoded[source[:-2]] = target
        else: pending.append((source, target))
    with tempfile.TemporaryDirectory(prefix='.decode-', dir=root) as tmp:
        work = Path(tmp)
        for start in range(0, len(pending), 100):
            group = pending[start:start + 100]
            run(['--vpk_filepath', ','.join(s for s, _ in group), '-d', '--texture_decode_flags', 'ForceLDR', '-o', str(work) + os.sep], 'textures-' + str(start))
            for source, target in group:
                exported = (work / source).with_suffix('.png')
                if not exported.is_file():
                    errors.append('Texture not decoded: ' + source)
                    continue
                # Never use a premultiplying image resizer: Source alpha often packs masks, not opacity.
                # Keep every raw channel; only the finished, opaque runtime albedo is downscaled.
                exported.replace(target)
                decoded[source[:-2]] = target
    coverage = collections.Counter()
    for kit in kits:
        params, bounds = params_by_kit[kit['paint_kit']]
        for weapon in kit['weapons']:
            coverage[kit['style']] += 1
            if weapon not in selection: continue
            directory = root / weapon / str(kit['paint_kit']); directory.mkdir(parents=True, exist_ok=True)
            inputs_doc = docs.get(input_sources.get((weapon, kit['legacy_model'])), {})
            weapon_params = material_params(inputs_doc)[0]
            inputs = {}
            slots = {'color': weapon_params.get('g_tColor'), 'masks': weapon_params.get('g_tMasks'),
                     'cavity': weapon_params.get('g_tAmbientOcclusion'),
                     'pattern': params.get('g_tPattern'), 'wear': params.get('g_tWear'), 'grunge': params.get('g_tGrunge')}
            if params.get('g_bOverrideDefaultMasks'): slots['masks'] = params.get('g_tPaintByNumberMasks')
            if params.get('g_bUseOverlay'): slots['overlay'] = params.get('g_tOverlay')
            if params.get('g_bOverrideAmbientOcclusion'): slots['cavity'] = params.get('g_tFinalAmbientOcclusion')
            for slot, source in slots.items():
                if not source: continue
                target = decoded.get(source)
                if target:
                    dest = directory / (slot + '.png')
                    temporary = directory / (slot + '.png.new')
                    if temporary.exists(): temporary.unlink()
                    os.link(target, temporary)
                    temporary.replace(dest)
                    inputs[slot] = dest.name
            effective = int(params.get('F_PAINT_STYLE', 0)) + 1
            needs_pattern = effective not in (1, 4)
            required = {'color', 'masks', 'cavity', 'wear', 'grunge'} | ({'pattern'} if needs_pattern else set())
            if params.get('g_bUseOverlay'): required.add('overlay')
            ready = kit['style'] in STYLES and bool(inputs_doc) and required <= inputs.keys()
            if ready:
                kit['available_weapons'].append(weapon)
            record = {'schema': 1, 'weapon': weapon, 'paint_kit': kit['paint_kit'], 'style': kit['style'],
                      'effective_style': effective, 'legacy_model': kit['legacy_model'],
                      'material': sources[kit['paint_kit']], 'params': params, 'bounds': bounds,
                      'weapon_params': {k: v for k, v in weapon_params.items() if not isinstance(v, str) or not v.endswith('.vtex')},
                      'inputs': inputs, 'available': ready, 'missing_inputs': sorted(required - inputs.keys()),
                      'approximation': 'Base-color only; shader/seed RNG/3D projection/wear equations approximated. Patina and case-hardening use tinted pattern fallback.'}
            save(directory / 'params.json', record)
            save(directory / 'shine.json', shine_params(params, effective))
    # Preserve ready associations for pairs outside a focused rerun, checking their published params.
    for kit in kits:
        for weapon in set(kit['weapons']) - selection:
            path = root / weapon / str(kit['paint_kit']) / 'params.json'
            if path.is_file() and json.loads(path.read_text()).get('available'):
                kit['available_weapons'].append(weapon)
        kit['available_weapons'].sort()
    report = {'schema': 1, 'kits': kits, 'rarity_weights': rarity_weights,
              'coverage_pairs': {str(s): {'name': STYLES[s], 'pairs': coverage[s], 'available': sum(len(k['available_weapons']) for k in kits if k['style'] == s)} for s in STYLES},
              'missing_chunks': sorted(missing), 'errors': sorted(set(errors)),
              'source': {'vpk': str(vpk_path), 'mtime_ns': vpk_path.stat().st_mtime_ns}}
    # Glove kits belong to --gloves (cs2_gloves); keep the published entries.
    index_path = root / 'index.json'
    if index_path.is_file():
        from cs2_gloves import merge_index
        previous = json.loads(index_path.read_text()).get('kits', [])
        merge_index(report, {kit['paint_kit']: kit for kit in previous if kit.get('kind') == 'glove'})
    save(index_path, report)
    print(json.dumps({'shine': refresh_shine(cache)}))
    if selected == 'all':
        for old in textures.glob('*.png'):
            if not old.name.startswith('raw_'): old.unlink()
    save(root / 'missing.json', {'chunks': sorted(missing), 'errors': report['errors']})
    filelist = root / 'filelist-missing.txt'
    filelist.write_text(''.join('game/csgo/' + name + '\n' for name in sorted(missing)))
    print(json.dumps({'pairs': sum(coverage.values()), 'coverage': report['coverage_pairs'],
                      'missing_chunks': sorted(missing), 'filelist': str(filelist), 'errors': report['errors']}, indent=2))
    return 1 if missing or errors else 0


def main(argv=None):
    import argparse
    parser = argparse.ArgumentParser(description='Refresh shine.json files in an existing skin cache.')
    parser.add_argument('--shine', action='store_true', required=True, help='write shine.json files only')
    parser.add_argument('--cache', default=os.environ.get('CSMC_CACHE', str(Path.home() / '.csmc' / 'cache')))
    args = parser.parse_args(argv)
    try:
        cache = checked_cache(args.cache, create=False)
    except ValueError as error:
        parser.error(str(error))
    print(json.dumps(refresh_shine(cache)))


if __name__ == '__main__':
    main()
