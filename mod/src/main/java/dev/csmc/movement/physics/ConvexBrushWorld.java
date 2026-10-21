package dev.csmc.movement.physics;

import dev.csmc.movement.geometry.BrushPlane;
import dev.csmc.movement.geometry.ConvexBrush;
import java.util.Arrays;
import java.util.List;

/**
 * {@link CsCollisionWorld} over convex brushes given in Minecraft block coordinates; traces are answered in Source
 * units ({@link CsUnits#UNITS_PER_BLOCK}).
 *
 * <p>The sweep is the box-versus-brush clip of the Quake/Source lineage: every brush plane is pushed out by the box
 * extent along its normal (Minkowski expansion) and the swept origin segment is clipped against the expanded planes
 * with enter/leave fractions, stopping {@link #DIST_EPSILON} short of the entering plane
 * (<a href="https://github.com/id-Software/Quake-2/blob/372afde46e7defc9dd2d719a1732b8ace1fa096e/qcommon/cmodel.c#L989-L1096">Quake II
 * CM_ClipBoxToBrush</a>, the ancestor of Source's engine-side clip;
 * <a href="https://github.com/ValveSoftware/source-sdk-2013/blob/be57e3d01ac7d7aa6a2cacbfef187c0b46616db0/src/public/coordsize.h#L17-L35">Source
 * coordsize.h</a> supplies {@code DIST_EPSILON}).
 * Unlike Quake, whether a brush is hit and which plane is entered are decided on the true crossing fractions; the epsilon
 * only pulls the reported fraction back (see {@link #traceHull}).
 *
 * <p>Pushing only the brush's own planes out over-estimates the solid near its edges (the classic "ghost hit" on
 * yaw-rotated ramps and thin brushes), which Source's map compiler hides by adding bevel planes
 * (<a href="https://github.com/ValveSoftware/source-sdk-2013/blob/be57e3d01ac7d7aa6a2cacbfef187c0b46616db0/src/utils/vbsp/map.cpp#L448-L599">AddBrushBevels</a>).
 * Here the expansion is made exact instead: each brush's vertices are computed once, every plane distance is
 * replaced by the polytope's support value, and the plane set is completed with the six axial planes of the true vertex bounds plus every
 * supporting {@code edge x axis} plane. Those are all face normals the Minkowski sum of a convex polytope and an
 * axis-aligned box can have, so the expanded plane set is exactly that sum. At coincident contacts, facets covered
 * by the union of the other expanded brushes are removed before selecting a normal; per-brush exactness alone
 * does not remove hidden cell faces at a shared edge. If that tolerance-based filter rejects every coincident facet
 * (near-active constraints at a microscopically clipped corner), the earliest brush's entering plane is reported, so
 * a true crossing never traces clear. Entries whose contact lies robustly inside the expanded region of a brush
 * touching the entered one (a sweep escaping an embedded start through neighbouring cells) are not union entries:
 * they are discarded and the next entry is resolved, so a split solid normally escapes like the unsplit one
 * (adjacency is vertex-based; see the limitations in {@code docs/dev/movement-reference.md}).
 *
 * <p>Brush geometry is translated to a local origin (its bound centre) while vertices are solved, so ramps far from
 * the world origin (e.g. x = 100000 blocks) keep full precision. Traces do not allocate; {@link #reset} reuses its
 * arrays and only grows them.
 */
public final class ConvexBrushWorld implements CsCollisionWorld {
    /** Source/Quake trace pull-back distance in units ({@code public/coordsize.h}: {@code DIST_EPSILON 0.03125}). */
    public static final double DIST_EPSILON = 0.03125;

    // All tolerances below are project-selected double-precision safeguards, not Source cvars/constants.
    /** Vertex-on-plane tolerance in units for the vertex solve (brushes are at most a few hundred units across). */
    private static final double VERTEX_TOLERANCE = 1.0E-7;
    /** Two vertices closer than this (units) are the same vertex. */
    private static final double MERGE_TOLERANCE = 1.0E-6;
    /** Unit normals closer than this in angle (1 - dot) are the same plane direction. */
    private static final double NORMAL_TOLERANCE = 1.0E-10;
    /**
     * A box exactly touching a face (Minecraft rests entities flush on block tops) counts as outside: a start within
     * this many units of an expanded plane is classified outside it and enters at fraction 0. Source never starts
     * flush because traces stop {@link #DIST_EPSILON} short; positions handed over from Minecraft can, and must not
     * read as {@code startSolid}. Classification only: crossing fractions use the unmodified planes, and crossings
     * this close along the move are treated as one coincident contact.
     */
    public static final double CONTACT_TOLERANCE = 1.0E-6;
    /**
     * A sweep that starts outside a face and approaches it by less than this (units) over the whole move is treated
     * as parallel to it. Without it, rounding after {@code ClipVelocity} (velocity.normal ~ -1e-16) re-hits the plane
     * the player is sliding on at fraction 0 on every bump, and {@code TryPlayerMove} zeroes the velocity.
     */
    public static final double PARALLEL_TOLERANCE = 1.0E-7;
    /** Squared tangent projection below which an active normal is parallel to the candidate facet normal. */
    private static final double TANGENT_PARALLEL_TOLERANCE_SQUARED = 1e-20;
    /** Dimensionless direction-dot / angular-sector degeneracy guard in the local union-boundary solve. */
    private static final double FACET_TOLERANCE = 1e-12;

    private int brushCount;
    /** Index of each brush's first plane in the plane arrays; brush i owns [planeStart[i], planeStart[i + 1]). */
    private int[] planeStart = new int[17];
    private double[] planeNx = new double[64];
    private double[] planeNy = new double[64];
    private double[] planeNz = new double[64];
    /** Support distance of each plane in world units. */
    private double[] planeD = new double[64];
    private int planeCount;
    /** Brush vertex bounds in world units: minX, minY, minZ, maxX, maxY, maxZ per brush. */
    private double[] bounds = new double[96];
    // Reused trace scratch: the union-boundary solve works in each candidate facet's two-dimensional tangent plane.
    private double[] brushEntry = new double[16];
    /** Entering plane (largest true crossing fraction) of each brush's clip, as in CM_ClipBoxToBrush. */
    private int[] brushEnterPlane = new int[16];
    private boolean[] contactBrush = new boolean[16];
    private double[] contactDistance = new double[64];
    private double[] tangentA = new double[64];
    private double[] tangentB = new double[64];
    private double[] contactAngles = new double[128];
    /** World-unit vertices of every brush; brush i owns [vertexStart[i], vertexStart[i + 1]). */
    private int[] vertexStart = new int[17];
    private double[] brushVertX = new double[128];
    private double[] brushVertY = new double[128];
    private double[] brushVertZ = new double[128];
    private int brushVertexCount;

    // Scratch for the per-brush vertex solve (reset only).
    private double[] srcNx = new double[16];
    private double[] srcNy = new double[16];
    private double[] srcNz = new double[16];
    private double[] srcD = new double[16];
    private double[] vertX = new double[64];
    private double[] vertY = new double[64];
    private double[] vertZ = new double[64];

    public ConvexBrushWorld(List<ConvexBrush> brushes) {
        reset(brushes);
    }

    /** Replaces the brush set (block coordinates), reusing internal storage. */
    public void reset(List<ConvexBrush> brushes) {
        brushCount = 0;
        planeCount = 0;
        brushVertexCount = 0;
        for (ConvexBrush brush : brushes) {
            addBrush(brush);
        }
    }

    /** Number of non-empty brushes kept from the last {@link #reset}. */
    public int brushCount() {
        return brushCount;
    }

    private void addBrush(ConvexBrush brush) {
        List<BrushPlane> planes = brush.planes();
        int n = planes.size();
        ensureSource(n);
        double cx = (brush.bounds().minX + brush.bounds().maxX) * 0.5 * CsUnits.UNITS_PER_BLOCK;
        double cy = (brush.bounds().minY + brush.bounds().maxY) * 0.5 * CsUnits.UNITS_PER_BLOCK;
        double cz = (brush.bounds().minZ + brush.bounds().maxZ) * 0.5 * CsUnits.UNITS_PER_BLOCK;
        for (int i = 0; i < n; i++) {
            BrushPlane plane = planes.get(i);
            // BrushPlane admits |n| within 1e-6 of 1 (grid-canonicalised planes are slightly off unit length):
            // rescale to an exact unit normal so direction dedup, support values and stored traces assume |n| = 1.
            double len = Math.sqrt(plane.nx() * plane.nx() + plane.ny() * plane.ny() + plane.nz() * plane.nz());
            double nx = plane.nx() / len, ny = plane.ny() / len, nz = plane.nz() / len;
            srcNx[i] = nx;
            srcNy[i] = ny;
            srcNz[i] = nz;
            // Plane in units, relative to the local centre c: n.(p - c) <= d*40 - n.c
            srcD[i] = plane.distance() / len * CsUnits.UNITS_PER_BLOCK - (nx * cx + ny * cy + nz * cz);
        }

        int vertexCount = solveVertices(n);
        if (vertexCount < 4 || !hasVolume(vertexCount)) {
            return; // empty or flat intersection: not a solid
        }

        ensureBrushCapacity(brushCount + 1);
        int start = planeCount;
        planeStart[brushCount] = start;

        // Bounds of the true polytope.
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        for (int v = 0; v < vertexCount; v++) {
            minX = Math.min(minX, vertX[v]);
            minY = Math.min(minY, vertY[v]);
            minZ = Math.min(minZ, vertZ[v]);
            maxX = Math.max(maxX, vertX[v]);
            maxY = Math.max(maxY, vertY[v]);
            maxZ = Math.max(maxZ, vertZ[v]);
        }

        // 1. The brush's own planes, tightened to their support value.
        for (int i = 0; i < n; i++) {
            addPlane(start, srcNx[i], srcNy[i], srcNz[i], vertexCount);
        }
        // 2. Axial bevels from the vertex bounds.
        addPlane(start, 1, 0, 0, vertexCount);
        addPlane(start, -1, 0, 0, vertexCount);
        addPlane(start, 0, 1, 0, vertexCount);
        addPlane(start, 0, -1, 0, vertexCount);
        addPlane(start, 0, 0, 1, vertexCount);
        addPlane(start, 0, 0, -1, vertexCount);
        // 3. Edge x axis bevels for every edge (pair of faces sharing at least two vertices).
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                addEdgeBevels(i, j, vertexCount, start);
            }
        }

        // Translate support distances and bounds back to world units.
        for (int p = start; p < planeCount; p++) {
            planeD[p] += planeNx[p] * cx + planeNy[p] * cy + planeNz[p] * cz;
        }
        int b = brushCount * 6;
        bounds[b] = minX + cx;
        bounds[b + 1] = minY + cy;
        bounds[b + 2] = minZ + cz;
        bounds[b + 3] = maxX + cx;
        bounds[b + 4] = maxY + cy;
        bounds[b + 5] = maxZ + cz;
        vertexStart[brushCount] = brushVertexCount;
        if (brushVertX.length < brushVertexCount + vertexCount) {
            int size = Math.max(brushVertexCount + vertexCount, brushVertX.length * 2);
            brushVertX = Arrays.copyOf(brushVertX, size);
            brushVertY = Arrays.copyOf(brushVertY, size);
            brushVertZ = Arrays.copyOf(brushVertZ, size);
        }
        for (int v = 0; v < vertexCount; v++, brushVertexCount++) {
            brushVertX[brushVertexCount] = vertX[v] + cx;
            brushVertY[brushVertexCount] = vertY[v] + cy;
            brushVertZ[brushVertexCount] = vertZ[v] + cz;
        }
        brushCount++;
        planeStart[brushCount] = planeCount;
        vertexStart[brushCount] = brushVertexCount;
    }

    /** Intersects every plane triple and keeps the points inside all half-spaces; returns the vertex count. */
    private int solveVertices(int n) {
        int count = 0;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                for (int k = j + 1; k < n; k++) {
                    // Cramer's rule on [ni; nj; nk] p = [di; dj; dk]
                    double ax = srcNx[i], ay = srcNy[i], az = srcNz[i];
                    double bx = srcNx[j], by = srcNy[j], bz = srcNz[j];
                    double kx = srcNx[k], ky = srcNy[k], kz = srcNz[k];
                    // c1 = nj x nk, c2 = nk x ni, c3 = ni x nj
                    double c1x = by * kz - bz * ky, c1y = bz * kx - bx * kz, c1z = bx * ky - by * kx;
                    double det = ax * c1x + ay * c1y + az * c1z;
                    if (Math.abs(det) < 1.0E-12) continue;
                    double c2x = ky * az - kz * ay, c2y = kz * ax - kx * az, c2z = kx * ay - ky * ax;
                    double c3x = ay * bz - az * by, c3y = az * bx - ax * bz, c3z = ax * by - ay * bx;
                    double inv = 1.0 / det;
                    double px = (srcD[i] * c1x + srcD[j] * c2x + srcD[k] * c3x) * inv;
                    double py = (srcD[i] * c1y + srcD[j] * c2y + srcD[k] * c3y) * inv;
                    double pz = (srcD[i] * c1z + srcD[j] * c2z + srcD[k] * c3z) * inv;
                    if (!inside(px, py, pz, n)) continue;
                    if (isDuplicate(px, py, pz, count)) continue;
                    ensureVertices(count + 1);
                    vertX[count] = px;
                    vertY[count] = py;
                    vertZ[count] = pz;
                    count++;
                }
            }
        }
        return count;
    }

    private boolean inside(double x, double y, double z, int n) {
        for (int p = 0; p < n; p++) {
            if (srcNx[p] * x + srcNy[p] * y + srcNz[p] * z - srcD[p] > VERTEX_TOLERANCE) return false;
        }
        return true;
    }

    private boolean isDuplicate(double x, double y, double z, int count) {
        for (int v = 0; v < count; v++) {
            if (Math.abs(vertX[v] - x) < MERGE_TOLERANCE && Math.abs(vertY[v] - y) < MERGE_TOLERANCE
                    && Math.abs(vertZ[v] - z) < MERGE_TOLERANCE) {
                return true;
            }
        }
        return false;
    }

    /** At least four vertices that are not coplanar. */
    private boolean hasVolume(int count) {
        double ox = vertX[0], oy = vertY[0], oz = vertZ[0];
        for (int a = 1; a < count; a++) {
            double ux = vertX[a] - ox, uy = vertY[a] - oy, uz = vertZ[a] - oz;
            for (int b = a + 1; b < count; b++) {
                double vx = vertX[b] - ox, vy = vertY[b] - oy, vz = vertZ[b] - oz;
                double cx = uy * vz - uz * vy, cy = uz * vx - ux * vz, cz = ux * vy - uy * vx;
                for (int c = b + 1; c < count; c++) {
                    double vol = cx * (vertX[c] - ox) + cy * (vertY[c] - oy) + cz * (vertZ[c] - oz);
                    if (Math.abs(vol) > 1.0E-9) return true;
                }
            }
        }
        return false;
    }

    private void addEdgeBevels(int i, int j, int vertexCount, int start) {
        double ex = srcNy[i] * srcNz[j] - srcNz[i] * srcNy[j];
        double ey = srcNz[i] * srcNx[j] - srcNx[i] * srcNz[j];
        double ez = srcNx[i] * srcNy[j] - srcNy[i] * srcNx[j];
        double len = Math.sqrt(ex * ex + ey * ey + ez * ez);
        if (len < 1.0E-12) return; // parallel faces share no edge
        // Edge present only if at least two distinct vertices lie on both faces.
        int first = -1;
        int second = -1;
        for (int v = 0; v < vertexCount && second < 0; v++) {
            if (onPlane(i, v) && onPlane(j, v)) {
                if (first < 0) first = v;
                else second = v;
            }
        }
        if (second < 0) return;
        ex /= len;
        ey /= len;
        ez /= len;
        // edge x X = (0, ez, -ey); edge x Y = (-ez, 0, ex); edge x Z = (ey, -ex, 0)
        addBevel(start, 0, ez, -ey, first, vertexCount);
        addBevel(start, -ez, 0, ex, first, vertexCount);
        addBevel(start, ey, -ex, 0, first, vertexCount);
    }

    private boolean onPlane(int plane, int v) {
        return Math.abs(srcNx[plane] * vertX[v] + srcNy[plane] * vertY[v] + srcNz[plane] * vertZ[v] - srcD[plane])
                <= 1.0E-6;
    }

    /** Adds {@code +-c} if it is a supporting plane through the edge vertex {@code onEdge}. */
    private void addBevel(int start, double cx, double cy, double cz, int onEdge, int vertexCount) {
        double len = Math.sqrt(cx * cx + cy * cy + cz * cz);
        if (len < 1.0E-9) return; // edge parallel to this axis
        cx /= len;
        cy /= len;
        cz /= len;
        for (int sign = -1; sign <= 1; sign += 2) {
            double nx = cx * sign, ny = cy * sign, nz = cz * sign;
            double support = support(nx, ny, nz, vertexCount);
            double atEdge = nx * vertX[onEdge] + ny * vertY[onEdge] + nz * vertZ[onEdge];
            if (support - atEdge <= 1.0E-6) {
                addPlane(start, nx, ny, nz, vertexCount);
            }
        }
    }

    private double support(double nx, double ny, double nz, int vertexCount) {
        double max = Double.NEGATIVE_INFINITY;
        for (int v = 0; v < vertexCount; v++) {
            max = Math.max(max, nx * vertX[v] + ny * vertY[v] + nz * vertZ[v]);
        }
        return max;
    }

    /** Adds a plane with its support distance (local units) unless the brush already has that direction. */
    private void addPlane(int start, double nx, double ny, double nz, int vertexCount) {
        for (int p = start; p < planeCount; p++) {
            if (planeNx[p] * nx + planeNy[p] * ny + planeNz[p] * nz > 1.0 - NORMAL_TOLERANCE) return;
        }
        ensurePlanes(planeCount + 1);
        planeNx[planeCount] = nx;
        planeNy[planeCount] = ny;
        planeNz[planeCount] = nz;
        planeD[planeCount] = support(nx, ny, nz, vertexCount);
        planeCount++;
    }

    @Override
    public void traceHull(double sx, double sy, double sz, double ex, double ey, double ez, double halfWidth,
            double height, CsTrace out) {
        out.fraction = 1.0;
        out.startSolid = false;
        out.allSolid = false;
        out.normal.set(0, 0, 0);
        double hitFrac = Double.POSITIVE_INFINITY;

        double mx = ex - sx, my = ey - sy, mz = ez - sz;
        double moveLength = Math.sqrt(mx * mx + my * my + mz * mz);
        // Crossing fractions closer than CONTACT_TOLERANCE units along the move are one coincident contact.
        double tieFrac = moveLength > 0.0 ? CONTACT_TOLERANCE / moveLength : 0.0;

        double sweepMinX = Math.min(sx, ex) - halfWidth - 1.0, sweepMaxX = Math.max(sx, ex) + halfWidth + 1.0;
        double sweepMinY = Math.min(sy, ey) - 1.0, sweepMaxY = Math.max(sy, ey) + height + 1.0;
        double sweepMinZ = Math.min(sz, ez) - halfWidth - 1.0, sweepMaxZ = Math.max(sz, ez) + halfWidth + 1.0;

        for (int brush = 0; brush < brushCount; brush++) {
            brushEntry[brush] = Double.POSITIVE_INFINITY;
            int b = brush * 6;
            if (bounds[b] > sweepMaxX || bounds[b + 3] < sweepMinX || bounds[b + 1] > sweepMaxY
                    || bounds[b + 4] < sweepMinY || bounds[b + 2] > sweepMaxZ || bounds[b + 5] < sweepMinZ) {
                continue;
            }
            // CM_ClipBoxToBrush (Quake II qcommon/cmodel.c 989-1096), with deliberate changes: the hit test, the
            // entering plane and the choice between brushes use the true crossing fractions d1 / (d1 - d2) of the
            // unmodified expanded planes; CONTACT_TOLERANCE only classifies flush starts as outside, and
            // DIST_EPSILON only pulls the reported fraction back once the globally earliest contact is known. Quake
            // widens enter/leave by DIST_EPSILON first, which reports hits for boxes passing within 1/32 unit of an
            // edge and, at the edge of a brush, can pick a hidden face (a block cell's top face under a ramp).
            double enterFrac = -1.0;
            int enterPlane = -1;
            double leaveFrac = 1.0;
            boolean hasEntry = false;
            boolean getOut = false;
            boolean startOut = false;
            boolean miss = false;
            for (int p = planeStart[brush], end = planeStart[brush + 1]; p < end; p++) {
                double nx = planeNx[p], ny = planeNy[p], nz = planeNz[p];
                // Push the plane out by the box: offset is the box corner furthest against the normal.
                double ofs = (nx < 0 ? halfWidth : -halfWidth) * nx + (ny < 0 ? height : 0.0) * ny
                        + (nz < 0 ? halfWidth : -halfWidth) * nz;
                double dist = planeD[p] - ofs;
                double d1 = nx * sx + ny * sy + nz * sz - dist;
                double d2 = nx * ex + ny * ey + nz * ez - dist;
                // Classification only: a box within CONTACT_TOLERANCE of the face counts as outside it.
                boolean outStart = d1 > -CONTACT_TOLERANCE;
                if (d2 > -CONTACT_TOLERANCE) getOut = true;
                if (outStart) startOut = true;
                if (outStart && d2 >= d1 - PARALLEL_TOLERANCE) {
                    miss = true; // completely in front of this face
                    break;
                }
                if (outStart) {
                    // Entering (d1 > d2 here); a flush start enters at 0.
                    double f = Math.max(d1, 0.0) / (d1 - d2);
                    hasEntry = true;
                    if (f > enterFrac) {
                        enterFrac = f;
                        enterPlane = p;
                    }
                } else if (d2 > -CONTACT_TOLERANCE) {
                    double f = d1 / (d1 - d2);
                    if (f < leaveFrac) leaveFrac = f;
                }
            }
            if (miss) continue;
            if (!startOut) {
                out.startSolid = true;
                if (!getOut) out.allSolid = true;
                continue;
            }
            if (hasEntry && enterFrac < leaveFrac) {
                brushEntry[brush] = enterFrac;
                brushEnterPlane[brush] = enterPlane;
                hitFrac = Math.min(hitFrac, enterFrac);
            }
        }

        int hitPlane = -1;
        double hitD1 = 0.0;
        double hitApproach = 0.0;
        while (Double.isFinite(hitFrac) && !out.allSolid) {
            prepareContact(sx + mx * hitFrac, sy + my * hitFrac, sz + mz * hitFrac, halfWidth, height);
            for (int brush = 0; brush < brushCount; brush++) {
                if (!contactBrush[brush] || brushEntry[brush] > hitFrac + tieFrac) continue;
                for (int p = planeStart[brush]; p < planeStart[brush + 1]; p++) {
                    if (Math.abs(contactDistance[p]) > CONTACT_TOLERANCE) continue;
                    double d1 = expandedDistance(p, sx, sy, sz, halfWidth, height);
                    double approach = planeNx[p] * mx + planeNy[p] * my + planeNz[p] * mz;
                    if (d1 <= -CONTACT_TOLERANCE || approach >= -PARALLEL_TOLERANCE) continue;
                    double f = Math.max(d1, 0.0) / -approach;
                    if (Math.abs(f - hitFrac) > tieFrac || !exposedFacet(brush, p)) continue;
                    // Opposition only chooses between genuine exposed facets at a real union corner, never
                    // between a boundary and a hidden cell constraint. Equal opposition is independent of order.
                    if (hitPlane < 0 || approach < hitApproach
                            || (approach == hitApproach && normalBefore(p, hitPlane))) {
                        hitPlane = p;
                        hitD1 = d1;
                        hitApproach = approach;
                    }
                }
            }
            if (hitPlane < 0) {
                // Exposure filtering is a tolerance-based local solve. Near-active constraints (strictly inside by
                // less than CONTACT_TOLERANCE) can make every coincident facet look hidden, e.g. a corner clipped
                // microscopically. A true union entry exists there, so never report clear: fall back to the
                // entering plane of the earliest brush (Quake's choice); equal entries choose by opposition, then
                // normal order. An entry whose contact lies robustly inside the expanded region of a brush touching
                // it (a sweep that starts embedded crosses into a neighbouring cell) is not a union entry and is
                // never restored.
                double fallbackFrac = Double.POSITIVE_INFINITY;
                for (int brush = 0; brush < brushCount; brush++) {
                    if (brushEntry[brush] > hitFrac + tieFrac || entryHiddenByNeighbour(brush)) continue;
                    int p = brushEnterPlane[brush];
                    double approach = planeNx[p] * mx + planeNy[p] * my + planeNz[p] * mz;
                    if (hitPlane < 0 || brushEntry[brush] < fallbackFrac || (brushEntry[brush] == fallbackFrac
                            && (approach < hitApproach || (approach == hitApproach && normalBefore(p, hitPlane))))) {
                        hitPlane = p;
                        hitD1 = expandedDistance(p, sx, sy, sz, halfWidth, height);
                        hitApproach = approach;
                        fallbackFrac = brushEntry[brush];
                    }
                }
            }
            if (hitPlane >= 0) {
                out.fraction = Math.max(0.0, (hitD1 - DIST_EPSILON) / -hitApproach);
                break;
            }
            // Every entry at this contact is hidden inside the union: the swept hull never leaves the solid here.
            // Discard those entries and resolve the next one (a sweep escaping an embedded start can still hit).
            double next = Double.POSITIVE_INFINITY;
            for (int brush = 0; brush < brushCount; brush++) {
                if (brushEntry[brush] <= hitFrac + tieFrac) brushEntry[brush] = Double.POSITIVE_INFINITY;
                else next = Math.min(next, brushEntry[brush]);
            }
            hitFrac = next;
        }

        if (out.allSolid) {
            out.fraction = 0.0;
        }
        if (hitPlane >= 0 && !out.allSolid) {
            out.normal.set(planeNx[hitPlane], planeNy[hitPlane], planeNz[hitPlane]);
        }
        double f = out.fraction;
        if (f >= 1.0) {
            out.end.set(ex, ey, ez);
        } else {
            out.end.set(sx + (ex - sx) * f, sy + (ey - sy) * f, sz + (ez - sz) * f);
        }
    }

    private double expandedDistance(int p, double x, double y, double z, double halfWidth, double height) {
        double expansion = halfWidth * (Math.abs(planeNx[p]) + Math.abs(planeNz[p]))
                + height * Math.max(0.0, -planeNy[p]);
        return planeNx[p] * x + planeNy[p] * y + planeNz[p] * z - (planeD[p] + expansion);
    }

    /** Finds the expanded brushes containing the true contact, including brushes tangent to the sweep. */
    private void prepareContact(double x, double y, double z, double halfWidth, double height) {
        for (int brush = 0; brush < brushCount; brush++) {
            contactBrush[brush] = false;
            int b = brush * 6;
            if (x < bounds[b] - halfWidth - CONTACT_TOLERANCE
                    || x > bounds[b + 3] + halfWidth + CONTACT_TOLERANCE
                    || y < bounds[b + 1] - height - CONTACT_TOLERANCE || y > bounds[b + 4] + CONTACT_TOLERANCE
                    || z < bounds[b + 2] - halfWidth - CONTACT_TOLERANCE
                    || z > bounds[b + 5] + halfWidth + CONTACT_TOLERANCE) continue;
            boolean contains = true;
            for (int p = planeStart[brush]; p < planeStart[brush + 1]; p++) {
                double d = expandedDistance(p, x, y, z, halfWidth, height);
                contactDistance[p] = d;
                if (d > CONTACT_TOLERANCE) contains = false;
            }
            contactBrush[brush] = contains;
        }
    }

    /**
     * Whether {@code owner}'s entry at the prepared contact is hidden: the contact lies inside another expanded brush
     * by more than tolerance, and that brush touches or overlaps {@code owner} in real space (a neighbouring cell of
     * the same solid). A separate solid across a gap narrower than the hull is still a genuine entry.
     */
    private boolean entryHiddenByNeighbour(int owner) {
        for (int brush = 0; brush < brushCount; brush++) {
            if (brush == owner || !contactBrush[brush]) continue;
            boolean inside = true;
            for (int p = planeStart[brush]; p < planeStart[brush + 1] && inside; p++) {
                inside = contactDistance[p] < -CONTACT_TOLERANCE;
            }
            if (inside && (vertexInside(owner, brush) || vertexInside(brush, owner))) return true;
        }
        return false;
    }

    /**
     * Whether a vertex of {@code a} lies within {@link #CONTACT_TOLERANCE} of the unexpanded solid {@code b}. Cells
     * of one split solid share vertices; solids overlapping without any vertex inside the other read as separate,
     * which keeps their entries (the conservative side: a hit, never a pass-through).
     */
    private boolean vertexInside(int a, int b) {
        for (int v = vertexStart[a]; v < vertexStart[a + 1]; v++) {
            double x = brushVertX[v], y = brushVertY[v], z = brushVertZ[v];
            boolean inside = true;
            for (int p = planeStart[b]; p < planeStart[b + 1] && inside; p++) {
                inside = planeNx[p] * x + planeNy[p] * y + planeNz[p] * z - planeD[p] <= CONTACT_TOLERANCE;
            }
            if (inside) return true;
        }
        return false;
    }

    /**
     * Tests local facet exposure, not an outward-normal probe (that probe cannot distinguish a hidden bevel at a
     * ramp seam). Active planes intersect the candidate tangent plane in lines through the contact. Their rays
     * partition its circle into sectors on which every brush's membership is constant. A facet is exposed iff
     * one sector lies in its relative interior and its outward side is not covered by ANY of the other brushes.
     * This also handles coverage by several neighbors, arbitrary clipped/rotated solids and redundant bevels.
     */
    private boolean exposedFacet(int owner, int candidate) {
        double nx = planeNx[candidate], ny = planeNy[candidate], nz = planeNz[candidate];
        double ux, uy, uz;
        if (Math.abs(nx) > Math.abs(nz)) {
            double length = Math.hypot(nx, ny);
            ux = -ny / length;
            uy = nx / length;
            uz = 0;
        } else {
            double length = Math.hypot(ny, nz);
            ux = 0;
            uy = -nz / length;
            uz = ny / length;
        }
        double vx = ny * uz - nz * uy, vy = nz * ux - nx * uz, vz = nx * uy - ny * ux;
        int count = 0;
        for (int brush = 0; brush < brushCount; brush++) {
            if (!contactBrush[brush]) continue;
            for (int p = planeStart[brush]; p < planeStart[brush + 1]; p++) {
                if (Math.abs(contactDistance[p]) > CONTACT_TOLERANCE) continue;
                double a = planeNx[p] * ux + planeNy[p] * uy + planeNz[p] * uz;
                double b = planeNx[p] * vx + planeNy[p] * vy + planeNz[p] * vz;
                tangentA[p] = a;
                tangentB[p] = b;
                if (a * a + b * b <= TANGENT_PARALLEL_TOLERANCE_SQUARED) continue;
                double angle = Math.atan2(-a, b);
                if (angle < 0) angle += 2 * Math.PI;
                contactAngles[count++] = angle;
                contactAngles[count++] = angle < Math.PI ? angle + Math.PI : angle - Math.PI;
            }
        }
        if (count == 0) return exposedSector(owner, candidate, 1, 0);
        sortContactAngles(count);
        for (int i = 0; i < count; i++) {
            double next = i + 1 < count ? contactAngles[i + 1] : contactAngles[0] + 2 * Math.PI;
            if (next - contactAngles[i] <= FACET_TOLERANCE) continue;
            double middle = (contactAngles[i] + next) * 0.5;
            if (exposedSector(owner, candidate, Math.cos(middle), Math.sin(middle))) return true;
        }
        return false;
    }

    /** In-place heapsort keeps trace allocation independent of the JDK primitive sort's merge-workspace choices. */
    private void sortContactAngles(int count) {
        for (int root = count / 2 - 1; root >= 0; root--) siftContactAngle(root, count);
        for (int end = count - 1; end > 0; end--) {
            double value = contactAngles[0];
            contactAngles[0] = contactAngles[end];
            contactAngles[end] = value;
            siftContactAngle(0, end);
        }
    }

    private void siftContactAngle(int root, int count) {
        double value = contactAngles[root];
        for (int child = root * 2 + 1; child < count; child = root * 2 + 1) {
            if (child + 1 < count && contactAngles[child] < contactAngles[child + 1]) child++;
            if (value >= contactAngles[child]) break;
            contactAngles[root] = contactAngles[child];
            root = child;
        }
        contactAngles[root] = value;
    }

    private boolean exposedSector(int owner, int candidate, double a, double b) {
        // A supporting plane touching only an edge/vertex has no relative-interior sector and is not a facet.
        for (int p = planeStart[owner]; p < planeStart[owner + 1]; p++) {
            if (Math.abs(contactDistance[p]) > CONTACT_TOLERANCE) continue;
            if (tangentA[p] * tangentA[p] + tangentB[p] * tangentB[p] <= TANGENT_PARALLEL_TOLERANCE_SQUARED) continue;
            if (tangentA[p] * a + tangentB[p] * b >= -FACET_TOLERANCE) return false;
        }
        for (int brush = 0; brush < brushCount; brush++) {
            if (brush == owner || !contactBrush[brush]) continue;
            boolean coversOutside = true;
            for (int p = planeStart[brush]; p < planeStart[brush + 1]; p++) {
                if (Math.abs(contactDistance[p]) > CONTACT_TOLERANCE) continue;
                if (tangentA[p] * tangentA[p] + tangentB[p] * tangentB[p] <= TANGENT_PARALLEL_TOLERANCE_SQUARED) {
                    // A coplanar same-facing facet leaves the union boundary exposed. An opposite-facing one
                    // covers its outward side, including two solids meeting flush on an internal face.
                    if (planeNx[p] * planeNx[candidate] + planeNy[p] * planeNy[candidate]
                            + planeNz[p] * planeNz[candidate] > 0) {
                        coversOutside = false;
                        break;
                    }
                } else if (tangentA[p] * a + tangentB[p] * b >= -FACET_TOLERANCE) {
                    coversOutside = false;
                    break;
                }
            }
            if (coversOutside) return false;
        }
        return true;
    }

    private boolean normalBefore(int p, int other) {
        int x = Double.compare(planeNx[p], planeNx[other]);
        if (x != 0) return x < 0;
        int y = Double.compare(planeNy[p], planeNy[other]);
        if (y != 0) return y < 0;
        int z = Double.compare(planeNz[p], planeNz[other]);
        return z != 0 ? z < 0 : planeD[p] < planeD[other];
    }

    private void ensureSource(int n) {
        if (srcNx.length < n) {
            int size = Math.max(n, srcNx.length * 2);
            srcNx = Arrays.copyOf(srcNx, size);
            srcNy = Arrays.copyOf(srcNy, size);
            srcNz = Arrays.copyOf(srcNz, size);
            srcD = Arrays.copyOf(srcD, size);
        }
    }

    private void ensureVertices(int n) {
        if (vertX.length < n) {
            int size = Math.max(n, vertX.length * 2);
            vertX = Arrays.copyOf(vertX, size);
            vertY = Arrays.copyOf(vertY, size);
            vertZ = Arrays.copyOf(vertZ, size);
        }
    }

    private void ensurePlanes(int n) {
        if (planeNx.length < n) {
            int size = Math.max(n, planeNx.length * 2);
            planeNx = Arrays.copyOf(planeNx, size);
            planeNy = Arrays.copyOf(planeNy, size);
            planeNz = Arrays.copyOf(planeNz, size);
            planeD = Arrays.copyOf(planeD, size);
            contactDistance = Arrays.copyOf(contactDistance, size);
            tangentA = Arrays.copyOf(tangentA, size);
            tangentB = Arrays.copyOf(tangentB, size);
            contactAngles = Arrays.copyOf(contactAngles, size * 2);
        }
    }

    private void ensureBrushCapacity(int brushes) {
        if (planeStart.length < brushes + 1) {
            int size = Math.max(brushes + 1, planeStart.length * 2);
            planeStart = Arrays.copyOf(planeStart, size);
            vertexStart = Arrays.copyOf(vertexStart, size);
            brushEntry = Arrays.copyOf(brushEntry, size - 1);
            brushEnterPlane = Arrays.copyOf(brushEnterPlane, size - 1);
            contactBrush = Arrays.copyOf(contactBrush, size - 1);
        }
        if (bounds.length < brushes * 6) {
            bounds = Arrays.copyOf(bounds, Math.max(brushes * 6, bounds.length * 2));
        }
    }
}
