#!/usr/bin/env python3
"""am_grass2 Cell A, derived from Sean Lewis (splewis / bakéd potato).

Geometry source: misc/am_grass2.vmf, mapversion 20, in csgo-multi-1v1
commit a710ab701f58c8ebe2bb4619297ccd37fcd62232; SHA-256
ab420e622e973912de80f85e99df440b7223668a1a247f6c8e6ce96ebff4c1b6.
https://steamcommunity.com/sharedfiles/filedetails/?id=279697343
Modified 2026-10-20: numeric brush transcription and Minecraft voxelization.
Derived geometry: GPL-2.0; see licenses/am_grass2-GPL-2.0.txt. No warranty.
No original VMF/BSP, Valve models, or Valve textures are bundled.

Coordinates: MC X=(Source X-704)/40, MC Z=(1232-Source Y)/40,
MC Y=floor+0.2+Source Z/40. The source floor top Z=32 becomes
floor+1 exactly. Axis-aligned brushes occupy intersected unit voxels: lower
bounds round down, upper bounds round up (exclusive), including negative
axes. Their faces expand by less than one block (40 Hammer units).
The two thin tilted metal brushes instead use native csmc:surf_ramp cells
with their actual six planes, clipped to each cell. Coefficients snap to
the existing 2^-24 block grid, matching SurfRamps.canonicalPlane. Native
CS movement gets planar collision; vanilla entities get the mod's finer
inscribed staircase. No solid bounding cuboids replace those ramps.
Overlapping voxels favor visible cover/walls over invisible sky barriers.

This is ONE source cell, not all 16. Source skybox brushes become invisible
barriers, keeping the sky visible. Grass/concrete/iron and the native ramp
material are Minecraft interpretations, not source textures. Player size,
movement, lighting and non-ramp block collisions differ from Source. Equality
to the published Workshop BSP has NOT been established.

Prop collision dimensions were measured separately from CS:GO's 2023
csgo_legacy depot 731 manifest 1224088799001669801, NOT the 2015 author's
installation. Byte equality across those versions is unproven. Props are
authored stock-Minecraft occupied-cell interpretations, NOT exact model
collision: every unit cell with positive-volume intersection against the
terminal convex-hull UNION is occupied. Relative to the measured supporting
hulls, surface expansion is <1 block per axis (<40 HU; Euclidean
<sqrt(3)*40 HU). Their supporting planes already include at most 0.001505
HU of outward stored-float adjustment. The grid can close sub-block gaps
and merge touching crate rasterizations; 132 unique above-ground prop
cells are affected. Native brush ramp coefficients instead use 2^-24
precision; maximum coefficient-rounding plane residual over this map is
<2.5e-6 blocks (<0.0001 HU).
"""
from itertools import combinations
from math import ceil, floor as floor_int, sqrt

from csmc_arena import fill

SOURCE_COMMIT = 'a710ab701f58c8ebe2bb4619297ccd37fcd62232'
SOURCE_SHA256 = 'ab420e622e973912de80f85e99df440b7223668a1a247f6c8e6ce96ebff4c1b6'
SOURCE_MAPVERSION = 20
HAMMER_UNITS_PER_BLOCK = 40

# All 14 axis-aligned Cell A solids, measured from all six VMF side planes.
# id, source minX/minY/minZ/maxX/maxY/maxZ, Minecraft material.
# Solids 2214/2215 are sloped and are recorded separately below.
_BRUSHES = (
    (2206, (704, 1232, 0, 1728, 2768, 32), 'minecraft:grass_block'),
    (2207, (704, 1232, 480, 1728, 2768, 512), 'minecraft:barrier'),
    (2208, (672, 1232, 192, 704, 2768, 480), 'minecraft:barrier'),
    (2211, (704, 2768, 192, 1728, 2800, 480), 'minecraft:barrier'),
    (2244, (1728, 1232, 192, 1760, 2768, 480), 'minecraft:barrier'),
    (2245, (704, 1200, 192, 1728, 1232, 480), 'minecraft:barrier'),
    (2202, (1728, 1232, 32, 1760, 2768, 192), 'minecraft:light_gray_concrete'),
    (2203, (704, 1200, 32, 1728, 1232, 192), 'minecraft:light_gray_concrete'),
    (2209, (672, 1232, 32, 704, 2768, 192), 'minecraft:light_gray_concrete'),
    (2210, (704, 2768, 32, 1728, 2800, 192), 'minecraft:light_gray_concrete'),
    # The low cover really starts at Z=33 (not the floor's Z=32).
    (2204, (1184, 1776, 33, 1216, 1904, 128), 'minecraft:gray_concrete'),
    (2205, (960, 1744, 33, 1216, 1776, 128), 'minecraft:gray_concrete'),
    (2212, (1216, 2128, 33, 1248, 2256, 128), 'minecraft:gray_concrete'),
    (2213, (1216, 2256, 33, 1472, 2288, 128), 'minecraft:gray_concrete'),
)

# Exact VMF plane triples, including the author's decimal precision and
# slightly nonparallel opposing planes. These are thin ramps, NOT blocks.
_RAMPS = (
    (2214, (1344, 2288, 0, 1472, 2480, 128), (
        ((1344, 2288, 128), (1344, 2478.02, 20.627), (1472, 2478.02, 20.627)),
        ((1344, 2480, 0), (1344, 2289.98, 107.372), (1472, 2289.98, 107.373)),
        ((1344, 2289.98, 107.371), (1344, 2480, 0), (1344, 2478.02, 20.6289)),
        ((1472, 2480, 0), (1472, 2289.98, 107.37), (1472, 2288, 128)),
        ((1344, 2480, 0), (1472, 2480, 0), (1472, 2478.02, 20.6273)),
        ((1472, 2289.98, 107.37), (1344, 2289.98, 107.369), (1344, 2288, 128)),
    )),
    (2215, (960, 1552, 0, 1088, 1744, 128), (
        ((960, 1553.98, 20.6309), (960, 1744, 128), (1088, 1744, 128)),
        ((960, 1742.02, 107.365), (960, 1552, 0), (1088, 1552, 0)),
        ((960, 1552, 0), (960, 1742.02, 107.362), (960, 1744, 128)),
        ((1088, 1742.02, 107.367), (1088, 1552, 0), (1088, 1553.98, 20.6289)),
        ((1088, 1552, 0), (960, 1552, 0), (960, 1553.98, 20.6265)),
        ((960, 1742.02, 107.367), (1088, 1742.02, 107.367), (1088, 1744, 128)),
    )),
)


# Map-specific occupied-cell runs, measured from the actual PHY terminal hull
# union, never from MDL render bounds or the nitro broad-phase proxy. Each run
# is a lossless compression of the resulting integer cell set. At this grid,
# all six nitro terminal hulls together occupy each listed prism completely;
# their bevels, flange gaps and top lumps leave NO empty whole voxel. Keeping
# a guessed void would therefore be less faithful to outward quantization.
# Wooden instances each occupy 18 cells; nitro instances each occupy 36.
# Two 6-cell wooden overlaps give 132 distinct prop cells in total.
# id, VMF source origin (angles all zero), inclusive floor-relative MC run, material.
# No PHY triangles, vertices, support planes, or original prop art are included.
_PROP_RASTER_RUNS = (
    (2216, (997, 2476, 31), (6, 1, -33, 8, 2, -31), 'minecraft:oak_planks'),
    (2220, (925, 2476, 31), (4, 1, -33, 6, 2, -31), 'minecraft:oak_planks'),
    (2224, (1440, 1520, 32), (17, 1, -9, 19, 2, -7), 'minecraft:oak_planks'),
    (2228, (1512, 1520, 32), (19, 1, -9, 21, 2, -7), 'minecraft:oak_planks'),
    (2236, (1600, 2000, 32), (21, 1, -21, 23, 4, -19), 'minecraft:iron_block'),
    (2240, (832, 2000, 32), (2, 1, -21, 4, 4, -19), 'minecraft:iron_block'),
)
# Measurement bundle is private scratch; these digests identify its authority.
PROP_METROLOGY_SHA256 = '95b969ed4932d27f176ee02f42a3464576280bec43e6734c0ec008bc6c07f72c'
PROP_PHY_SHA256 = {
    'wooden_crate_64x64_moss': 'd9943b90e3bb7f4bfaf042148ffe747a7d4094f8ddb07bc96ca7af0b2c1abc17',
    'bomb_crate_nitrostack': '54d08e7a146af5319eab5508a64e37142f35f8b7698e7443a83eaf579b3a4ef6',
}


def bounds(floor: int) -> tuple[int, int, int, int, int, int]:
    """Inclusive reset limits; never includes the trading post or lobby."""
    return -1, floor - 1, -40, 26, floor + 13, 0


def spawns(floor: int) -> dict[str, tuple[float, float, float, float, float]]:
    """Chosen T/CT source origins and facing, with feet 1 HU above ground."""
    return {
        'blue': (6.4, floor + 1.025, -34.0, 0.0, 0.0),
        'red': (19.2, floor + 1.025, -4.4, 180.0, 0.0),
    }


def _point(point):
    x, y, z = point
    scale = HAMMER_UNITS_PER_BLOCK
    return (x - 704) / scale, 0.2 + z / scale, (1232 - y) / scale


def _box(source, floor):
    x0, y0, z0, x1, y1, z1 = source
    low = _point((x0, y1, z0))
    high = _point((x1, y0, z1))
    return (floor_int(low[0]), floor + floor_int(low[1]), floor_int(low[2]),
            ceil(high[0]) - 1, floor + ceil(high[1]) - 1, ceil(high[2]) - 1)


def _dot(a, b):
    return sum(x * y for x, y in zip(a, b))


def _cross(a, b):
    return (a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0])


def _planes(triples):
    """Normalized outward halfspaces (normal dot point <= distance)."""
    faces = [tuple(_point(p) for p in face) for face in triples]
    center = tuple(sum(p[i] for face in faces for p in face) / 18 for i in range(3))
    result = []
    for a, b, c in faces:
        normal = _cross(tuple(b[i] - a[i] for i in range(3)),
                        tuple(c[i] - a[i] for i in range(3)))
        length = sqrt(_dot(normal, normal))
        normal = tuple(v / length for v in normal)
        distance = _dot(normal, a)
        if _dot(normal, center) > distance:
            normal, distance = tuple(-v for v in normal), -distance
        normal = tuple(round(v * (1 << 24)) / (1 << 24) for v in normal)
        distance = round(distance * (1 << 24)) / (1 << 24)
        result.append((normal, distance))
    return tuple(result)


def _intersects_voxel(planes, x, y, z):
    """Positive-volume convex brush/voxel intersection, not just AABB overlap.

    Enumerate vertices of the clipped convex solid. Their mean is strictly
    interior iff the intersection has volume; face-only touching is excluded.
    Distances are in blocks; numerical tolerance is 1e-8 blocks, not HU.
    """
    clipped = planes + (
        ((-1, 0, 0), -x), ((1, 0, 0), x + 1),
        ((0, -1, 0), -y), ((0, 1, 0), y + 1),
        ((0, 0, -1), -z), ((0, 0, 1), z + 1),
    )
    vertices = []
    for (a, da), (b, db), (c, dc) in combinations(clipped, 3):
        bc = _cross(b, c)
        determinant = _dot(a, bc)
        if abs(determinant) < 1e-12:
            continue
        ca, ab = _cross(c, a), _cross(a, b)
        vertex = tuple((da * bc[i] + db * ca[i] + dc * ab[i]) / determinant
                       for i in range(3))
        if all(_dot(n, vertex) <= d + 1e-8 for n, d in clipped):
            vertices.append(vertex)
    if not vertices:
        return False
    center = tuple(sum(v[i] for v in vertices) / len(vertices) for i in range(3))
    return all(_dot(n, center) < d - 1e-8 for n, d in clipped)


def _ramp_block(planes, x, y, z):
    """Encode the existing SurfRampBlockEntity planes NBT, with no Java changes."""
    cuts = []
    for normal, distance in planes:
        # A halfspace containing the entire cell need not occupy a plane slot.
        maximum = sum(n * (p + (1 if n > 0 else 0))
                      for n, p in zip(normal, (x, y, z)))
        if maximum > distance + 1e-8:
            local_distance = distance - _dot(normal, (x, y, z))
            values = (*normal, local_distance)
            cuts.append('{' + ','.join(f'{key}:{value:.17g}d'
                                      for key, value in zip(('nx', 'ny', 'nz', 'd'), values)) + '}')
    if not cuts:
        return 'minecraft:iron_block'
    if len(cuts) > 4:
        raise ValueError(f'Ramp cell {(x, y, z)} exceeds native four-plane limit')
    return 'csmc:surf_ramp{planes:[' + ','.join(cuts) + ']}'


def build(floor: int) -> list[str]:
    """Synchronous, deterministic block-only reset; never removes entities."""
    commands = [fill(*bounds(floor), 'minecraft:air')]
    # Ground first; outward-rounded concrete is restored after exact ramps so
    # shared edge voxels cannot punch holes into the two L-shaped covers.
    _, ground, material = _BRUSHES[0]
    commands.append(fill(*_box(ground, floor), material))
    for _, source, triples in _RAMPS:
        x0, y0, z0, x1, y1, z1 = _box(source, 0)
        planes = _planes(triples)
        for x in range(x0, x1 + 1):
            for y in range(y0, y1 + 1):
                # Preserve the floor's grass where buried ramp voxels overlap.
                if y <= 0:
                    continue
                for z in range(z0, z1 + 1):
                    if _intersects_voxel(planes, x, y, z):
                        block = _ramp_block(planes, x, y, z)
                        commands.append(f'setblock {x} {floor + y} {z} {block}')
    for _, source, material in _BRUSHES[1:]:
        commands.append(fill(*_box(source, floor), material))
    for _, _, run, material in _PROP_RASTER_RUNS:
        x0, y0, z0, x1, y1, z1 = run
        commands.append(fill(x0, floor + y0, z0, x1, floor + y1, z1, material))
    # Invisible light has no collision, unlike lamps hung into the sightlines.
    # Six-block grid keeps the floor lit even at night; never replaces cover.
    for x in (2, 8, 14, 20, 24):
        for z in (-3, -9, -15, -21, -27, -33, -37):
            commands.append(f'execute if block {x} {floor + 5} {z} minecraft:air run '
                            f'setblock {x} {floor + 5} {z} minecraft:light[level=15]')
    return commands
