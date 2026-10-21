"""Glove viewmodel meshes and glove finish textures for export-cs2-assets --gloves.

Selection and material-layout helpers are Python 3 stdlib. Baking a finish's final albedo samples
multi-megapixel textures, so `bake_color` needs numpy and Pillow (imported lazily).
"""
import math

# Contract ids are the items_game item names.
GLOVE_IDS = ('studded_bloodhound_gloves sporty_gloves slick_gloves leather_handwraps motorcycle_gloves '
             'specialist_gloves studded_hydra_gloves studded_brokenfang_gloves').split()
ICON_PREFIX = 'panorama/images/econ/default_generated/'
ICON_SUFFIX = '_light_png.vtex_c'
TEXTURES = {'color': 'color.png', 'normal': 'normal.png'}
BAKE_SIZE = 1024
APPROXIMATION = ('Final albedo baked offline at wear 0 from the layered glove shader inputs: per-texel material layer, '
                 'tint id, tiled layer detail/surface texture, tint colours, optional pattern and ambient occlusion. '
                 'Wear damage, grime, burnishing, sheen and roughness are not baked.')


def resolve_gloves(items):
    """Glove id -> {defindex, model} from items_game `items` (models are the agent arm glove vmdls)."""
    found = {}
    for index, item in items.items():
        if isinstance(item, dict) and item.get('name') in GLOVE_IDS:
            found[item['name']] = {'defindex': int(index), 'model': item.get('model_player', '')}
    return {glove: found[glove] for glove in GLOVE_IDS if glove in found}


def icon_pairs(entries, kit_names):
    """Glove id -> sorted lowercase kit names from econ icons `<glove_id>_<kit>_light_png`.

    The longest glove id prefix wins so no id can swallow another's icons.
    """
    pairs = {}
    ordered = sorted(GLOVE_IDS, key=len, reverse=True)
    for entry in entries:
        if not (entry.startswith(ICON_PREFIX) and entry.endswith(ICON_SUFFIX)):
            continue
        stem = entry[len(ICON_PREFIX):-len(ICON_SUFFIX)]
        glove = next((g for g in ordered if stem.startswith(g + '_')), None)
        if glove and stem[len(glove) + 1:] in kit_names:
            pairs.setdefault(glove, set()).add(stem[len(glove) + 1:])
    return {glove: sorted(kits) for glove, kits in pairs.items()}


def paint_candidates(kit):
    """Compiled sources that may hold a glove kit's paint, in priority order (kit vcompmat, glove vmat, glove
    vcompmat, legacy vmt)."""
    name = kit['name'].lower()
    result = []
    if kit.get('composite_material_path'):
        result.append(kit['composite_material_path'].lower() + '_c')
    result += ['gloves/paints/' + name + '.vmat_c', 'gloves/paints/' + name + '.vcompmat_c']
    if kit.get('vmt_path'):
        result.append(kit['vmt_path'].lower() + '_c')
    return result


def composite_paint(doc):
    """The paint material a glove vcompmat composites.

    Newer kits name it in a loose `m_strResourceMaterial` (their specific container is the shared compositor
    template); older kits only have a specific container material, which is the paint itself.
    """
    found = {}
    stack = [doc]
    while stack:
        node = stack.pop()
        if isinstance(node, dict):
            for key in ('m_strResourceMaterial', 'm_strSpecificContainerMaterial'):
                value = node.get(key)
                if isinstance(value, str) and value.endswith('.vmat'):
                    found.setdefault(key, value + '_c')
            stack.extend(reversed(list(node.values())))
        elif isinstance(node, list):
            stack.extend(reversed(node))
    return found.get('m_strResourceMaterial') or found.get('m_strSpecificContainerMaterial')


def material_values(doc):
    """name -> value for a compiled VMAT's int/float/vector/texture params (texture paths keep `.vtex`)."""
    values = {}
    for kind, field in (('m_intParams', 'm_nValue'), ('m_floatParams', 'm_flValue'),
                        ('m_vectorParams', 'm_value'), ('m_textureParams', 'm_pValue')):
        for entry in doc.get(kind, []) or []:
            if isinstance(entry, dict) and 'm_name' in entry and field in entry:
                values[entry['m_name']] = entry[field]
    return values


def material_layout(values):
    """Describe how to bake a glove paint VMAT. Two CS2 glove shader families exist:

    `customglove` (gloves/paints/*.vmat): g_tLayerMask RGB select layers 2-4 over layer 1, its alpha is the tint id;
    layer N albedo = g_vColorTint<id> * detail luminance (g_tDetailN.R tiled by g_fDetailScaleN, lifted by black point).
    Its pattern (F_PATTERN) replaces tint id g_nPatternReplaceIndex, tiled by g_fPatternTexCoordScale. Weapon paint
    patterns (`.../customization/paints/...`) are RGB colour-key masks: R/G/B blend g_vColorTint5/6/7 over the
    replaced tint. Other patterns are colour images.
    `layered` (items/assets/paintkits/*.vmat): g_tLayerId R selects the layer, g_tTintId R the tint id; layer N albedo =
    g_tSurfaceN (tiled by g_vUvScaleN, offset g_vUvOffsetN) * g_vSurfaceColorTintN, recoloured towards g_vId<id>Color
    by that colour's alpha. Its pattern colours the ids flagged g_bId<id>Pattern.
    AO (g_tSurface G / g_tObjectProperties R) multiplies.
    """
    def vec(name, default):
        value = values.get(name)
        return [float(v) for v in value[:len(default)]] if isinstance(value, list) else list(default)

    def number(name, default):
        value = values.get(name)
        return float(value) if isinstance(value, (int, float)) else default

    pattern = None
    if values.get('F_PATTERN') and values.get('g_tPattern'):
        pattern = {'texture': values['g_tPattern'], 'mode': 'auto',
                   'offset': vec('g_vPatternTexCoordOffset', (0.0, 0.0)) if 'g_vPatternTexCoordOffset' in values
                   else vec('g_fPatternTexCoordOffset', (0.0, 0.0)),
                   'rotation': number('g_fPatternTexCoordRotation', 0.0)}
    if 'g_tLayerMask' in values:
        family, layers = 'customglove', []
        for n in range(1, 5):
            layers.append({'texture': values.get('g_tDetail' + str(n)), 'channel': 'luminance',
                           'scale': [number('g_fDetailScale' + str(n), 1.0)] * 2, 'offset': [0.0, 0.0],
                           'black_point': number('g_fDetailBlackPoint' + str(n), 0.0), 'tint': [1.0, 1.0, 1.0]})
        tints = [vec('g_vColorTint' + str(n), (1.0, 1.0, 1.0)) for n in range(1, 9)]
        maps = {'layer': values.get('g_tLayerMask'), 'layer_mode': 'rgb_over_base',
                'tint_id': values.get('g_tLayerMask'), 'tint_channel': 3,
                'ao': values.get('g_tSurface'), 'ao_channel': 1}
        if pattern:
            replace = int(number('g_nPatternReplaceIndex', 8))
            pattern.update({'ids': [min(8, max(1, replace)) - 1], 'scale': [number('g_fPatternTexCoordScale', 1.0)] * 2,
                            'mode': 'mask' if '/customization/paints/' in pattern['texture'] else 'auto'})
    elif 'g_tLayerId' in values:
        family, layers = 'layered', []
        for n in range(1, 5):
            layers.append({'texture': values.get('g_tSurface' + str(n)), 'channel': 'rgb',
                           'scale': vec('g_vUvScale' + str(n), (1.0, 1.0)), 'offset': vec('g_vUvOffset' + str(n), (0.0, 0.0)),
                           'black_point': 0.0, 'tint': vec('g_vSurfaceColorTint' + str(n), (1.0, 1.0, 1.0))})
        tints = []
        for n in range(1, 9):
            red, green, blue, alpha = vec('g_vId' + str(n) + 'Color', (1.0, 1.0, 1.0, 0.0))
            tints.append([1 + (channel - 1) * alpha for channel in (red, green, blue)])
        maps = {'layer': values.get('g_tLayerId'), 'layer_mode': 'index',
                'tint_id': values.get('g_tTintId') if values.get('F_TINT_ID') else None, 'tint_channel': 0,
                'ao': values.get('g_tObjectProperties'), 'ao_channel': 0}
        if pattern:
            pattern.update({'ids': [n for n in range(8) if values.get('g_bId' + str(n + 1) + 'Pattern')],
                            'scale': vec('g_vPatternTexCoordScale', (1.0, 1.0))})
            if not pattern['ids']:
                pattern = None
    else:
        return None
    return {'family': family, 'layers': layers, 'tints': tints, 'pattern': pattern,
            'normal': values.get('g_tNormal'), **maps}


def layout_textures(layout):
    """Every texture (`.vtex`) a layout samples, sorted."""
    names = [layer['texture'] for layer in layout['layers']]
    names += [layout.get(key) for key in ('layer', 'tint_id', 'ao', 'normal')]
    if layout['pattern']:
        names.append(layout['pattern']['texture'])
    return sorted({name for name in names if isinstance(name, str) and name.endswith('.vtex')})


def required_missing(layout, decoded):
    """Textures the bake cannot do without (layer/tint id maps, pattern) that are not decoded."""
    needed = [layout['layer']] + ([layout['tint_id']] if layout['tint_id'] else [])
    if layout['pattern']:
        needed.append(layout['pattern']['texture'])
    return sorted({name for name in needed if name and name not in decoded})


def degraded_inputs(layout, decoded):
    """Optional textures (layer detail/surface, AO) that are not decoded; the bake substitutes flat values."""
    optional = [layer['texture'] for layer in layout['layers']] + [layout['ao']]
    return sorted({name for name in optional if name and name not in decoded})


def is_color_key(rgb):
    """True when a float RGB pattern (0-1) is a colour-key mask: >= 90% of texels have every channel near 0 or 1."""
    extreme = ((rgb < 0.07) | (rgb > 0.93)).all(-1)
    return bool(extreme.mean() >= 0.9)


def bake_color(layout, load, size=BAKE_SIZE):
    """Return an RGB uint8 (size, size, 3) numpy array: the finish's baked albedo.

    `load(vtex)` -> RGBA uint8 array, or None when absent: an absent layer texture bakes flat (luminance 1 for
    `customglove` detail, mid grey for `layered` surfaces) and absent AO is skipped.
    """
    import numpy as np

    def resample(image):
        if image.shape[0] == size and image.shape[1] == size:
            return image
        ys = (np.arange(size) * image.shape[0] // size)
        xs = (np.arange(size) * image.shape[1] // size)
        return image[ys][:, xs]

    v, u = np.meshgrid((np.arange(size) + 0.5) / size, (np.arange(size) + 0.5) / size, indexing='ij')

    def tiled(image, scale, offset, rotation=0.0):
        uu, vv = u * scale[0] + offset[0], v * scale[1] + offset[1]
        if rotation:
            c, s = math.cos(math.radians(rotation)), math.sin(math.radians(rotation))
            uu, vv = (uu - 0.5) * c - (vv - 0.5) * s + 0.5, (uu - 0.5) * s + (vv - 0.5) * c + 0.5
        h, w = image.shape[:2]
        return image[(np.floor(vv * h).astype(np.int64) % h), (np.floor(uu * w).astype(np.int64) % w)]

    mask = resample(load(layout['layer'])).astype(np.int32)
    if layout['layer_mode'] == 'index':
        layer = np.clip(mask[..., 0] * 4 // 256, 0, 3)
    else:
        layer = np.zeros((size, size), np.int32)
        # Highest set channel wins, matching layer_index.
        for index in (1, 2, 3):
            layer = np.where(mask[..., index - 1] >= 128, index, layer)
    if layout['tint_id']:
        tint_map = resample(load(layout['tint_id']))[..., layout['tint_channel']].astype(np.int32)
        tint = np.clip(tint_map * 8 // 256, 0, 7)
    else:
        tint = np.zeros((size, size), np.int32)
    tints = np.asarray(layout['tints'], np.float32)[:, :3]
    color = np.zeros((size, size, 3), np.float32)
    for index, spec in enumerate(layout['layers']):
        where = layer == index
        if not where.any():
            continue
        image = load(spec['texture']) if spec['texture'] else None
        if spec['channel'] == 'luminance':
            value = tiled(image, spec['scale'], spec['offset'])[..., :1].astype(np.float32) / 255 if image is not None \
                else np.ones((size, size, 1), np.float32)
            value = spec['black_point'] + (1 - spec['black_point']) * value
            albedo = np.repeat(value, 3, -1) * tints[tint]
        else:
            sample = tiled(image, spec['scale'], spec['offset'])[..., :3].astype(np.float32) / 255 if image is not None \
                else np.full((size, size, 3), 0.5, np.float32)
            albedo = sample * np.asarray(spec['tint'], np.float32) * tints[tint]
        color[where] = albedo[where]
    if layout['pattern']:
        spec = layout['pattern']
        sample = tiled(load(spec['texture']), spec['scale'], spec['offset'], spec['rotation'])[..., :3].astype(np.float32) / 255
        if spec.get('mode') == 'mask' or (spec.get('mode') == 'auto' and is_color_key(sample)):
            # Colour-key mask: R/G/B blend tints 5/6/7 over the replaced tint.
            painted = np.broadcast_to(tints[spec['ids'][0]], sample.shape).copy()
            for channel, index in ((0, 4), (1, 5), (2, 6)):
                weight = sample[..., channel:channel + 1]
                painted = painted * (1 - weight) + tints[index] * weight
            sample = painted
        where = np.isin(tint, spec['ids'])
        color[where] = sample[where]
    ao = load(layout['ao']) if layout['ao'] else None
    if ao is not None:
        color *= (resample(ao)[..., layout['ao_channel']].astype(np.float32) / 255)[..., None]
    return (np.clip(color, 0, 1) * 255 + 0.5).astype(np.uint8)


def glove_materials(materials):
    """Split glTF material names into (glove, bare arm) lists."""
    glove = [name for name in materials if not name.startswith('bare_arm')]
    return glove, [name for name in materials if name.startswith('bare_arm')]


def merge_index(index, glove_kits):
    """Add/refresh glove kit entries in skins/index.json `kits`, leaving every other kit untouched.

    `glove_kits`: paint_kit -> entry (with `weapons`/`available_weapons` holding only glove ids). Weapon kits that
    share a paint kit id keep their weapons; glove ids are unioned in.
    """
    kits = index.setdefault('kits', [])
    by_id = {kit['paint_kit']: kit for kit in kits}
    for paint_kit, entry in sorted(glove_kits.items()):
        kit = by_id.get(paint_kit)
        if kit is None:
            kits.append(entry)
            continue
        for key in ('weapons', 'available_weapons'):
            kit[key] = sorted((set(kit.get(key, [])) - set(GLOVE_IDS)) | set(entry[key]))
        for key in ('kind', 'glove_textures', 'rarity'):
            kit.setdefault(key, entry[key])
    kits.sort(key=lambda kit: kit['paint_kit'])
    return index


def normal_rgb(image):
    """RGB tangent normal from a Source 2 normal texture that stores only X/Y in R/G (Z rebuilt, +Z up)."""
    import numpy as np
    xy = image[..., :2].astype(np.float32) / 127.5 - 1
    z = np.sqrt(np.clip(1 - (xy ** 2).sum(-1), 0, 1))[..., None]
    return ((np.concatenate([xy, z], -1) * 0.5 + 0.5) * 255 + 0.5).clip(0, 255).astype(np.uint8)


def drop_images(model, absent):
    """Remove images whose URI is in `absent`, their textures and every material reference to them (in place).

    Returns the removed URIs. Texture indices in materials are renumbered.
    """
    images = model.get('images', [])
    gone = {index for index, image in enumerate(images) if image.get('uri') in absent}
    if not gone:
        return []
    image_map = {old: new for new, old in enumerate(i for i in range(len(images)) if i not in gone)}
    textures = model.get('textures', [])
    dead = {index for index, texture in enumerate(textures) if texture.get('source') in gone}
    texture_map = {old: new for new, old in enumerate(i for i in range(len(textures)) if i not in dead)}
    removed = [images[index]['uri'] for index in sorted(gone)]
    model['images'] = [image for index, image in enumerate(images) if index not in gone]
    model['textures'] = [dict(texture, source=image_map[texture['source']]) if 'source' in texture else texture
                         for index, texture in enumerate(textures) if index not in dead]

    def rewrite(node):
        if isinstance(node, dict):
            for key in list(node):
                value = node[key]
                if isinstance(value, dict) and 'index' in value and key.endswith(('Texture', 'texture')):
                    if value['index'] in dead:
                        del node[key]
                    else:
                        value['index'] = texture_map[value['index']]
                        rewrite(value)
                else:
                    rewrite(value)
        elif isinstance(node, list):
            for value in node:
                rewrite(value)

    rewrite(model.get('materials', []))
    return removed
