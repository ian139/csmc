"""CS2 equipment icon SVG -> white-alpha PNG for the csmc ammo HUD (numpy + Pillow, no SVG library).

Supports what the decompiled panorama icons use: <path>/<polygon>/<polyline>/<rect>/<circle>/<ellipse> with
nested <g> transforms, full path syntax including arcs, and nonzero/evenodd fill rules. Only shape coverage is
kept; every pixel is written white with the covered alpha.
"""
import math
import re
import xml.etree.ElementTree as ElementTree

ICON_DIR = 'panorama/images/icons/equipment/'
ICON_HEIGHT = 256
SUPERSAMPLE = 4
_NUMBER = re.compile(r'[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?')
_SEPARATORS = re.compile(r'[\s,]*')
_COMMAND = re.compile(r'[MmLlHhVvCcSsQqTtAaZz]')
_FLAG = re.compile(r'[01]')
_SKIPPED = {'defs', 'clipPath', 'mask', 'symbol', 'style', 'title', 'metadata', 'pattern', 'linearGradient',
            'radialGradient'}


def icon_names(entries, weapon_ids):
    """Item id -> owned equipment icon; csmc's passive kit uses CS2's defuser icon."""
    aliases = {'defuse_kit': 'defuser'}
    return {weapon: ICON_DIR + aliases.get(weapon, weapon) + '.vsvg_c' for weapon in sorted(weapon_ids)
            if ICON_DIR + aliases.get(weapon, weapon) + '.vsvg_c' in entries}


def _matrix_multiply(a, b):
    return (a[0] * b[0] + a[2] * b[1], a[1] * b[0] + a[3] * b[1], a[0] * b[2] + a[2] * b[3],
            a[1] * b[2] + a[3] * b[3], a[0] * b[4] + a[2] * b[5] + a[4], a[1] * b[4] + a[3] * b[5] + a[5])


IDENTITY = (1.0, 0.0, 0.0, 1.0, 0.0, 0.0)


def parse_transform(text):
    """SVG transform attribute -> affine (a, b, c, d, e, f)."""
    result = IDENTITY
    for name, body in re.findall(r'(\w+)\s*\(([^)]*)\)', text or ''):
        v = [float(n) for n in _NUMBER.findall(body)]
        if name == 'matrix' and len(v) == 6:
            m = tuple(v)
        elif name == 'translate' and v:
            m = (1, 0, 0, 1, v[0], v[1] if len(v) > 1 else 0)
        elif name == 'scale' and v:
            m = (v[0], 0, 0, v[1] if len(v) > 1 else v[0], 0, 0)
        elif name == 'rotate' and v:
            r = math.radians(v[0])
            m = (math.cos(r), math.sin(r), -math.sin(r), math.cos(r), 0, 0)
            if len(v) == 3:
                m = _matrix_multiply(_matrix_multiply((1, 0, 0, 1, v[1], v[2]), m), (1, 0, 0, 1, -v[1], -v[2]))
        elif name == 'skewX' and v:
            m = (1, 0, math.tan(math.radians(v[0])), 1, 0, 0)
        elif name == 'skewY' and v:
            m = (1, math.tan(math.radians(v[0])), 0, 1, 0, 0)
        else:
            continue
        result = _matrix_multiply(result, m)
    return result


def _cubic(p0, p1, p2, p3, steps=16):
    return [((1 - t) ** 3 * p0[0] + 3 * (1 - t) ** 2 * t * p1[0] + 3 * (1 - t) * t * t * p2[0] + t ** 3 * p3[0],
             (1 - t) ** 3 * p0[1] + 3 * (1 - t) ** 2 * t * p1[1] + 3 * (1 - t) * t * t * p2[1] + t ** 3 * p3[1])
            for t in (i / steps for i in range(1, steps + 1))]


def _quadratic(p0, p1, p2, steps=12):
    return [((1 - t) ** 2 * p0[0] + 2 * (1 - t) * t * p1[0] + t * t * p2[0],
             (1 - t) ** 2 * p0[1] + 2 * (1 - t) * t * p1[1] + t * t * p2[1])
            for t in (i / steps for i in range(1, steps + 1))]


def _arc(p0, rx, ry, angle, large, sweep, p1):
    """SVG endpoint arc -> points after p0 (spec F.6.5 conversion)."""
    if p0 == p1:
        return []
    rx, ry = abs(rx), abs(ry)
    if rx == 0 or ry == 0:
        return [p1]
    phi = math.radians(angle)
    cos, sin = math.cos(phi), math.sin(phi)
    dx, dy = (p0[0] - p1[0]) / 2, (p0[1] - p1[1]) / 2
    x1, y1 = cos * dx + sin * dy, -sin * dx + cos * dy
    scale = x1 * x1 / (rx * rx) + y1 * y1 / (ry * ry)
    if scale > 1:
        rx, ry = rx * math.sqrt(scale), ry * math.sqrt(scale)
    numerator = rx * rx * ry * ry - rx * rx * y1 * y1 - ry * ry * x1 * x1
    factor = math.sqrt(max(0.0, numerator / (rx * rx * y1 * y1 + ry * ry * x1 * x1)))
    if large == sweep:
        factor = -factor
    cx1, cy1 = factor * rx * y1 / ry, -factor * ry * x1 / rx
    cx = cos * cx1 - sin * cy1 + (p0[0] + p1[0]) / 2
    cy = sin * cx1 + cos * cy1 + (p0[1] + p1[1]) / 2
    start = math.atan2((y1 - cy1) / ry, (x1 - cx1) / rx)
    end = math.atan2((-y1 - cy1) / ry, (-x1 - cx1) / rx)
    delta = end - start
    if sweep and delta < 0:
        delta += 2 * math.pi
    elif not sweep and delta > 0:
        delta -= 2 * math.pi
    steps = max(4, int(abs(delta) / (math.pi / 16)))
    points = []
    for i in range(1, steps + 1):
        t = start + delta * i / steps
        x, y = rx * math.cos(t), ry * math.sin(t)
        points.append((cos * x - sin * y + cx, sin * x + cos * y + cy))
    points[-1] = p1
    return points


def parse_path(d):
    """Path data -> list of subpaths (lists of (x, y) points), curves flattened."""
    d = d or ''
    subpaths, current = [], []
    x = y = sx = sy = 0.0
    command, previous, control = None, None, None
    i = 0

    def skip():
        nonlocal i
        i = _SEPARATORS.match(d, i).end()

    def scan(pattern, what):
        nonlocal i
        skip()
        match = pattern.match(d, i)
        if not match:
            raise ValueError('expected ' + what + ' at offset ' + str(i) + ' of path data')
        i = match.end()
        return float(match.group())

    def numbers(count):
        return [scan(_NUMBER, 'a number') for _ in range(count)]

    while True:
        skip()
        if i >= len(d):
            break
        letter = _COMMAND.match(d, i)
        if letter:
            command = letter.group()
            i = letter.end()
        elif command is None:
            raise ValueError('path data must start with a command')
        relative = command.islower()
        upper = command.upper()
        ox, oy = (x, y) if relative else (0.0, 0.0)
        if upper == 'Z':
            if current:
                subpaths.append(current)
            current = []
            x, y = sx, sy
            previous, control = 'Z', None
            continue
        if upper == 'M':
            if current:
                subpaths.append(current)
            px, py = numbers(2)
            x, y = ox + px, oy + py
            sx, sy = x, y
            current = [(x, y)]
            command = 'l' if relative else 'L'
            previous, control = 'M', None
            continue
        if not current:
            current = [(x, y)]
        if upper == 'L':
            px, py = numbers(2)
            x, y = ox + px, oy + py
            current.append((x, y))
            control = None
        elif upper == 'H':
            x = ox + numbers(1)[0]
            current.append((x, y))
            control = None
        elif upper == 'V':
            y = oy + numbers(1)[0]
            current.append((x, y))
            control = None
        elif upper in 'CS':
            if upper == 'C':
                x1, y1, x2, y2, px, py = numbers(6)
                c1 = (ox + x1, oy + y1)
            else:
                x2, y2, px, py = numbers(4)
                c1 = (2 * x - control[0], 2 * y - control[1]) if previous in ('C', 'S') and control else (x, y)
            c2, end = (ox + x2, oy + y2), (ox + px, oy + py)
            current.extend(_cubic((x, y), c1, c2, end))
            control, (x, y) = c2, end
        elif upper in 'QT':
            if upper == 'Q':
                x1, y1, px, py = numbers(4)
                c1 = (ox + x1, oy + y1)
            else:
                px, py = numbers(2)
                c1 = (2 * x - control[0], 2 * y - control[1]) if previous in ('Q', 'T') and control else (x, y)
            end = (ox + px, oy + py)
            current.extend(_quadratic((x, y), c1, end))
            control, (x, y) = c1, end
        elif upper == 'A':
            # Arc flags are single characters and may abut the next value ("0 01 10 10").
            rx, ry, angle = numbers(3)
            large, sweep = scan(_FLAG, 'an arc flag'), scan(_FLAG, 'an arc flag')
            px, py = numbers(2)
            end = (ox + px, oy + py)
            current.extend(_arc((x, y), rx, ry, angle, bool(large), bool(sweep), end))
            x, y = end
            control = None
        else:
            raise ValueError('unsupported path command ' + command)
        previous = upper
    if current:
        subpaths.append(current)
    return subpaths


def _style(element, name):
    declarations = (part.split(':', 1) for part in (element.get('style') or '').split(';') if ':' in part)
    value = {key.strip(): value.strip() for key, value in declarations}.get(name)
    return value if value is not None else element.get(name)


def _ellipse(cx, cy, rx, ry, steps=48):
    return [[(cx + rx * math.cos(2 * math.pi * i / steps), cy + ry * math.sin(2 * math.pi * i / steps))
             for i in range(steps)]]


def shapes(svg_text):
    """SVG document -> (view box (x, y, w, h), [(subpaths in view-box units, rule)])."""
    root = ElementTree.fromstring(svg_text)
    view = [float(n) for n in _NUMBER.findall(root.get('viewBox') or '')]
    if len(view) != 4:
        view = [0.0, 0.0, float(_NUMBER.match(root.get('width', '0')).group()),
                float(_NUMBER.match(root.get('height', '0')).group())]
    result = []

    def walk(element, matrix, rule, visible):
        tag = element.tag.rsplit('}', 1)[-1]
        if tag in _SKIPPED or _style(element, 'display') == 'none':
            return
        matrix = _matrix_multiply(matrix, parse_transform(element.get('transform')))
        rule = _style(element, 'fill-rule') or rule
        fill = _style(element, 'fill')
        if fill is not None:
            visible = fill != 'none'
        geometry = None
        number = lambda name: float(_NUMBER.match(element.get(name) or '0').group())
        if tag == 'path':
            geometry = parse_path(element.get('d'))
        elif tag in ('polygon', 'polyline'):
            values = [float(n) for n in _NUMBER.findall(element.get('points') or '')]
            geometry = [list(zip(values[0::2], values[1::2]))]
        elif tag == 'rect':
            x, y, w, h = number('x'), number('y'), number('width'), number('height')
            geometry = [[(x, y), (x + w, y), (x + w, y + h), (x, y + h)]]
        elif tag == 'circle':
            geometry = _ellipse(number('cx'), number('cy'), number('r'), number('r'))
        elif tag == 'ellipse':
            geometry = _ellipse(number('cx'), number('cy'), number('rx'), number('ry'))
        if geometry and visible:
            a, b, c, d, e, f = matrix
            result.append(([[(a * px + c * py + e, b * px + d * py + f) for px, py in sub] for sub in geometry],
                           rule))
        for child in element:
            walk(child, matrix, rule, visible)

    walk(root, IDENTITY, 'nonzero', True)
    return tuple(view), result


def coverage(svg_text, height):
    """Coverage mask (numpy float32 0..1, shape (height, width)) of the SVG scaled to {@code height} pixels."""
    import numpy

    (vx, vy, vw, vh), filled = shapes(svg_text)
    if vw <= 0 or vh <= 0:
        raise ValueError('svg has no size')
    scale = height * SUPERSAMPLE / vh
    rows, columns = height * SUPERSAMPLE, max(1, round(vw * scale / SUPERSAMPLE)) * SUPERSAMPLE
    mask = numpy.zeros((rows, columns), dtype=bool)
    centers = numpy.arange(columns) + 0.5
    for subpaths, rule in filled:
        edges = []
        for sub in subpaths:
            points = [((px - vx) * scale, (py - vy) * scale) for px, py in sub]
            edges += [(points[k], points[(k + 1) % len(points)]) for k in range(len(points))]
        if not edges:
            continue
        e = numpy.array([(p[0], p[1], q[0], q[1]) for p, q in edges if p[1] != q[1]], dtype=numpy.float64)
        if not len(e):
            continue
        x0, y0, x1, y1 = e.T
        direction = numpy.where(y1 > y0, 1, -1)
        low, high = numpy.minimum(y0, y1), numpy.maximum(y0, y1)
        shape_mask = numpy.zeros_like(mask)
        for row in range(max(0, int(low.min())), min(rows, int(math.ceil(high.max())) + 1)):
            sample = row + 0.5
            hit = (low <= sample) & (sample < high)
            if not hit.any():
                continue
            xs = x0[hit] + (sample - y0[hit]) * (x1[hit] - x0[hit]) / (y1[hit] - y0[hit])
            # Winding number at each pixel center: crossings to its left, signed by edge direction.
            order = numpy.argsort(xs)
            xs, winds = xs[order], numpy.cumsum(direction[hit][order])
            index = numpy.searchsorted(xs, centers, side='right')
            winding = numpy.where(index > 0, winds[numpy.maximum(index - 1, 0)], 0)
            inside = (winding % 2 == 1) if rule == 'evenodd' else (winding != 0)
            shape_mask[row] = inside
        mask |= shape_mask
    return mask.reshape(height, SUPERSAMPLE, columns // SUPERSAMPLE, SUPERSAMPLE).mean(axis=(1, 3)).astype('float32')


def rasterize(svg_text, height, output):
    """Writes a white RGBA PNG of the SVG at {@code height} pixels; returns (width, height)."""
    import numpy
    from PIL import Image

    alpha = coverage(svg_text, height)
    rgba = numpy.zeros(alpha.shape + (4,), dtype=numpy.uint8)
    rgba[..., :3] = 255
    rgba[..., 3] = numpy.round(alpha * 255).astype(numpy.uint8)
    Image.fromarray(rgba, 'RGBA').save(output)
    return alpha.shape[1], alpha.shape[0]
