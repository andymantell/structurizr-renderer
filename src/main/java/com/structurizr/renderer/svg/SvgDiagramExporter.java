package com.structurizr.renderer.svg;

import com.structurizr.export.AbstractDiagramExporter;
import com.structurizr.export.Diagram;
import com.structurizr.export.IndentingWriter;
import com.structurizr.model.*;
import com.structurizr.view.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public class SvgDiagramExporter extends AbstractDiagramExporter {

    private static final int PADDING = 50;
    private static final int BOUNDARY_PADDING = 30;
    private static final int BOUNDARY_LABEL_HEIGHT = 55; // extra bottom space for the 33px bold label + margin

    // Placeholder tokens replaced in createDiagram once actual bounds are known
    private static final String W_TOKEN  = "__SVG_CANVAS_W__";
    private static final String H_TOKEN  = "__SVG_CANVAS_H__";
    private static final String TX_TOKEN = "__SVG_TRANSLATE_X__";
    private static final String TY_TOKEN = "__SVG_TRANSLATE_Y__";

    // Corridor spacing between parallel lines when multiple relationships connect
    // the same element pair (e.g. a request and its response).  Lines leave the
    // boxes at ~35% of this offset and kink out to the full corridor.
    private static final double PARALLEL_SPACING = 80;

    // Per-view state (reset in writeHeader)
    private ModelView currentView;
    private Deque<BoundaryState> boundaryStack;
    private List<PendingRel> pendingRelData;
    private List<Connectors.LabelInfo> pendingRelationships;
    // Element bounding boxes collected during writeElement, used as static obstacles
    // in repelLabels so relationship labels don't paint over element label text.
    // Each entry: [centerX, centerY, width, height]
    private List<double[]> elementObstacles;
    // Boundary frame rects [x, y, w, h]; labels must not straddle their edges
    private List<double[]> boundaryFrames;
    // Computed boundary rectangles keyed by the boundary's element id (deployment
    // nodes, software system / container boundaries).  Relationships touching these
    // elements connect to the boundary box — the element view's own coordinates are
    // meaningless because boundaries are sized from their children.
    // Each entry: [x, y, width, height]
    private java.util.Map<String, double[]> boundaryRects;
    // Tracks the bounding box of all drawn content in model space.  Min can be
    // negative (manually positioned elements / vertices); createDiagram() shifts
    // the group translate so everything lands inside the canvas.
    private int actualMinX;
    private int actualMinY;
    private int actualMaxX;
    private int actualMaxY;

    // -------------------------------------------------------------------------
    // Header / Footer
    // -------------------------------------------------------------------------

    @Override
    protected void writeHeader(ModelView view, IndentingWriter writer) {
        this.currentView = view;
        this.boundaryStack = new ArrayDeque<>();
        this.pendingRelData = new ArrayList<>();
        this.pendingRelationships = new ArrayList<>();
        this.elementObstacles = new ArrayList<>();
        this.boundaryFrames = new ArrayList<>();
        this.boundaryRects = new java.util.HashMap<>();
        this.actualMinX = 0;
        this.actualMinY = 0;
        this.actualMaxX = 0;
        this.actualMaxY = 0;

        String bg = "#ffffff";

        // Dimensions are placeholders; createDiagram() replaces them with actual tracked bounds.
        writer.writeLine(
            "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" " +
            "version=\"1.1\" width=\"" + W_TOKEN + "\" height=\"" + H_TOKEN + "\" " +
            "viewBox=\"0 0 " + W_TOKEN + " " + H_TOKEN + "\">");
        writer.writeLine(String.format("<rect width=\"" + W_TOKEN + "\" height=\"" + H_TOKEN + "\" fill=\"%s\"/>", bg));
        writer.writeLine(Connectors.defsBlock());
        writer.writeLine("<g transform=\"translate(" + TX_TOKEN + "," + TY_TOKEN + ")\">");
    }

    @Override
    protected void writeFooter(ModelView view, IndentingWriter writer) {
        List<PendingRel> unique = dedupRelationships(pendingRelData);
        spreadAndLayout(unique);

        repelLabels(pendingRelationships, elementObstacles, boundaryFrames);

        // Fold relationship geometry (waypoints and final label boxes) into the tracked
        // bounds so paths routed outside the element extent aren't clipped off-canvas.
        for (Connectors.LabelInfo li : pendingRelationships) {
            for (double[] p : li.pathPoints) {
                actualMinX = Math.min(actualMinX, (int) Math.floor(p[0]));
                actualMinY = Math.min(actualMinY, (int) Math.floor(p[1]));
                actualMaxX = Math.max(actualMaxX, (int) Math.ceil(p[0]));
                actualMaxY = Math.max(actualMaxY, (int) Math.ceil(p[1]));
            }
            if (li.hasLabel) {
                actualMinX = Math.min(actualMinX, (int) Math.floor(li.labelX - li.labelW / 2.0));
                actualMinY = Math.min(actualMinY, (int) Math.floor(li.labelY - li.labelH / 2.0));
                actualMaxX = Math.max(actualMaxX, (int) Math.ceil(li.labelX + li.labelW / 2.0));
                actualMaxY = Math.max(actualMaxY, (int) Math.ceil(li.labelY + li.labelH / 2.0));
            }
        }

        // Two-pass render: all arrow lines first, then all labels on top.
        // This guarantees no line from relationship B can paint over the label of relationship A.
        writer.writeLine("<g id=\"edges\">");
        for (Connectors.LabelInfo li : pendingRelationships) {
            writer.writeLine(Connectors.renderPath(li));
        }
        writer.writeLine("</g>");
        writer.writeLine("<g id=\"edge-labels\">");
        for (Connectors.LabelInfo li : pendingRelationships) {
            writer.writeLine(Connectors.renderLabel(li));
        }
        writer.writeLine("</g>");
        pendingRelationships.clear();

        writer.writeLine("</g>");
        writer.writeLine("</svg>");
    }

    /** Raw relationship data captured during writeRelationship, laid out in writeFooter. */
    private record PendingRel(RelationshipView rv, double[] srcRect, double[] dstRect,
                              RelationshipStyle style) {

        boolean hasLabel() {
            String desc = Connectors.effectiveDescription(rv);
            String tech = rv.getRelationship().getTechnology();
            return (desc != null && !desc.isEmpty()) || (tech != null && !tech.isEmpty());
        }

        String labelKey() {
            return Connectors.effectiveDescription(rv) + "|" + rv.getRelationship().getTechnology();
        }

        boolean isSelf() {
            return rv.getRelationship().getSourceId().equals(rv.getRelationship().getDestinationId());
        }

        String directedKey() {
            return centerKey(srcRect) + ">" + centerKey(dstRect) + "|" + verticesKey();
        }

        /** Endpoint pair ignoring direction, for grouping parallel/opposite lines. */
        String pairKey() {
            String a = centerKey(srcRect), b = centerKey(dstRect);
            return a.compareTo(b) <= 0 ? a + "~" + b : b + "~" + a;
        }

        private String verticesKey() {
            StringBuilder sb = new StringBuilder();
            for (Vertex v : rv.getVertices()) sb.append(v.getX()).append(',').append(v.getY()).append(';');
            return sb.toString();
        }

        private static String centerKey(double[] r) {
            return Math.round(r[0] + r[2] / 2) + "," + Math.round(r[1] + r[3] / 2);
        }
    }

    /**
     * Deduplicate relationship entries before layout.  Entries with the same directed
     * endpoints, route and label text are duplicates (dynamic views supply both a
     * static-model and a dynamic-step RelationshipView for the same pair); unlabelled
     * entries sharing a route with a labelled one are dropped.  Entries with DIFFERENT
     * labels are all kept — parallel relationships must not lose labels.
     */
    private static List<PendingRel> dedupRelationships(List<PendingRel> rels) {
        java.util.Map<String, List<PendingRel>> byRoute = new java.util.LinkedHashMap<>();
        for (PendingRel pr : rels) {
            byRoute.computeIfAbsent(pr.directedKey(), k -> new ArrayList<>()).add(pr);
        }
        List<PendingRel> unique = new ArrayList<>();
        for (List<PendingRel> group : byRoute.values()) {
            boolean anyLabelled = group.stream().anyMatch(PendingRel::hasLabel);
            if (!anyLabelled) {
                unique.add(group.get(0));
                continue;
            }
            java.util.Set<String> seenLabels = new java.util.HashSet<>();
            for (PendingRel pr : group) {
                if (pr.hasLabel() && seenLabels.add(pr.labelKey())) {
                    unique.add(pr);
                }
            }
        }
        return unique;
    }

    /**
     * Compute layouts, spreading relationships that share an element pair (requests
     * and their responses, or parallel relationships) into perpendicular-offset
     * parallel lines so each arrow is visually distinct.  Only direct vertex-less
     * lines participate — routed paths already diverge.
     */
    private void spreadAndLayout(List<PendingRel> rels) {
        java.util.Map<String, List<PendingRel>> byPair = new java.util.LinkedHashMap<>();
        for (PendingRel pr : rels) {
            if (pr.isSelf() || !pr.rv.getVertices().isEmpty()) continue;
            byPair.computeIfAbsent(pr.pairKey(), k -> new ArrayList<>()).add(pr);
        }

        java.util.Map<PendingRel, double[]> offsets = new java.util.IdentityHashMap<>();
        for (List<PendingRel> group : byPair.values()) {
            if (group.size() < 2) continue;
            // Perpendicular of the pair's canonical direction (smaller center key first)
            // so opposite-direction members are pushed to opposite sides.
            PendingRel first = group.get(0);
            double[] a = first.srcRect, b = first.dstRect;
            if (PendingRel.centerKey(a).compareTo(PendingRel.centerKey(b)) > 0) {
                double[] tmp = a; a = b; b = tmp;
            }
            double dx = (b[0] + b[2] / 2) - (a[0] + a[2] / 2);
            double dy = (b[1] + b[3] / 2) - (a[1] + a[3] / 2);
            double len = Math.hypot(dx, dy);
            if (len < 1) continue;
            double px = -dy / len, py = dx / len;
            String canonicalSrc = PendingRel.centerKey(a);
            for (int i = 0; i < group.size(); i++) {
                PendingRel pr = group.get(i);
                double off = (i - (group.size() - 1) / 2.0) * PARALLEL_SPACING;
                // Stagger labels along the line (at thirds for a pair) in the pair's
                // canonical frame so a request label and its response label don't
                // both start at the same point of the corridor.
                int fraction = (int) Math.round(100.0 * (i + 1) / (group.size() + 1));
                boolean canonical = PendingRel.centerKey(pr.srcRect()).equals(canonicalSrc);
                int ownPosition = canonical ? fraction : 100 - fraction;
                offsets.put(pr, new double[]{px * off, py * off, ownPosition});
            }
        }

        for (PendingRel pr : rels) {
            double[] off = offsets.get(pr);
            pendingRelationships.add(off == null
                ? Connectors.computeLayout(pr.rv, pr.srcRect, pr.dstRect, pr.style, 0, 0, null,
                                           avoidRectsFor(pr))
                : Connectors.computeLayout(pr.rv, pr.srcRect, pr.dstRect, pr.style,
                                           off[0], off[1], (int) off[2], avoidRectsFor(pr)));
        }
    }

    /**
     * Foreign boxes a direct line for this relationship must route around: every
     * element box and boundary frame except the endpoints themselves and any frame
     * that contains an endpoint (lines legitimately cross those to get in or out).
     */
    private List<double[]> avoidRectsFor(PendingRel pr) {
        List<double[]> out = new ArrayList<>();
        double[] s = pr.srcRect(), d = pr.dstRect();
        for (double[] o : elementObstacles) {
            double[] r = {o[0] - o[2] / 2.0, o[1] - o[3] / 2.0, o[2], o[3]};
            if (sameRect(r, s) || sameRect(r, d)) continue;
            out.add(r);
        }
        for (double[] f : boundaryFrames) {
            if (sameRect(f, s) || sameRect(f, d)) continue;
            if (containsCenter(f, s) || containsCenter(f, d)) continue;
            out.add(f);
        }
        return out;
    }

    private static boolean sameRect(double[] a, double[] b) {
        return Math.abs(a[0] - b[0]) < 1 && Math.abs(a[1] - b[1]) < 1
            && Math.abs(a[2] - b[2]) < 1 && Math.abs(a[3] - b[3]) < 1;
    }

    private static boolean containsCenter(double[] frame, double[] rect) {
        double cx = rect[0] + rect[2] / 2.0, cy = rect[1] + rect[3] / 2.0;
        return cx >= frame[0] && cx <= frame[0] + frame[2]
            && cy >= frame[1] && cy <= frame[1] + frame[3];
    }

    /**
     * Two-phase label placement:
     *
     * 1. Compute every pairwise edge-segment crossing point.  Each crossing becomes
     *    an immovable obstacle — labels are repelled away from those points so they
     *    don't sit ambiguously on top of an intersection.
     *
     * 2. Iteratively push apart label bounding boxes that overlap each other, and
     *    push labels away from the crossing obstacles.  Runs until no collision
     *    remains or the iteration cap is hit.
     */
    private static void repelLabels(List<Connectors.LabelInfo> labels, List<double[]> elementObstacles,
                                    List<double[]> boundaryFrames) {
        // --- Phase 1: find crossing obstacles ---
        List<double[]> crossings = new ArrayList<>();
        for (int i = 0; i < labels.size(); i++) {
            for (int j = i + 1; j < labels.size(); j++) {
                List<double[]> pi = labels.get(i).pathPoints;
                List<double[]> pj = labels.get(j).pathPoints;
                for (int si = 0; si < pi.size() - 1; si++) {
                    for (int sj = 0; sj < pj.size() - 1; sj++) {
                        double[] cross = segmentIntersect(
                            pi.get(si), pi.get(si + 1),
                            pj.get(sj), pj.get(sj + 1));
                        if (cross != null) crossings.add(cross);
                    }
                }
            }
        }

        // Arrowhead zones: a label box must not mask the pointy end of any line,
        // including its own.
        List<double[]> arrowTips = new ArrayList<>();
        for (Connectors.LabelInfo li : labels) {
            if (!li.pathPoints.isEmpty()) {
                arrowTips.add(li.pathPoints.get(li.pathPoints.size() - 1));
            }
        }

        // --- Phase 2: iterative repulsion with tethering ---
        // Obstacle zone: label-sized forbidden region around each crossing point.
        final int OBS_W = 110, OBS_H = 70;
        final int TIP_W = 70, TIP_H = 70;
        final int MAX_ITER = 60;
        // Tether strength: each iteration the label is pulled 35% toward the nearest
        // point on its OWN path.  Using the nearest path point (not the fixed origin)
        // ensures the label stays anchored to its own line even after being pushed
        // sideways, so it is always closer to its own line than to any foreign line.
        final double TETHER = 0.35;

        for (int iter = 0; iter < MAX_ITER; iter++) {
            boolean moved = false;

            // Label vs label
            for (int i = 0; i < labels.size(); i++) {
                for (int j = i + 1; j < labels.size(); j++) {
                    if (repelPair(labels.get(i), labels.get(j))) moved = true;
                }
            }

            // Label vs crossing obstacle (label moves; obstacle is fixed)
            for (Connectors.LabelInfo li : labels) {
                if (!li.hasLabel) continue;
                for (double[] c : crossings) {
                    double ox = overlapAxis(li.labelX, li.labelW, c[0], OBS_W);
                    double oy = overlapAxis(li.labelY, li.labelH, c[1], OBS_H);
                    if (ox > 0 && oy > 0) {
                        moved = true;
                        double dx = li.labelX - c[0];
                        double dy = li.labelY - c[1];
                        double len = Math.sqrt(dx * dx + dy * dy);
                        if (len < 1) { dx = 0; dy = -1; len = 1; } // default: push upward
                        li.labelX += (ox / 2.0 + 4) * (dx / len);
                        li.labelY += (oy / 2.0 + 4) * (dy / len);
                    }
                }
            }

            // Label vs arrowhead (label moves; arrow tip is fixed)
            for (Connectors.LabelInfo li : labels) {
                if (!li.hasLabel) continue;
                for (double[] tip : arrowTips) {
                    double ox = overlapAxis(li.labelX, li.labelW, tip[0], TIP_W);
                    double oy = overlapAxis(li.labelY, li.labelH, tip[1], TIP_H);
                    if (ox > 0 && oy > 0) {
                        moved = true;
                        double dx = li.labelX - tip[0];
                        double dy = li.labelY - tip[1];
                        double len = Math.sqrt(dx * dx + dy * dy);
                        if (len < 1) { dx = 0; dy = -1; len = 1; }
                        li.labelX += (ox / 2.0 + 4) * (dx / len);
                        li.labelY += (oy / 2.0 + 4) * (dy / len);
                    }
                }
            }

            // Label vs boundary frame edges: nudge the label fully to one side so it
            // doesn't straddle the frame line.
            for (Connectors.LabelInfo li : labels) {
                if (!li.hasLabel) continue;
                for (double[] f : boundaryFrames) {
                    double left = li.labelX - li.labelW / 2.0, right  = li.labelX + li.labelW / 2.0;
                    double top  = li.labelY - li.labelH / 2.0, bottom = li.labelY + li.labelH / 2.0;
                    for (double ex : new double[]{f[0], f[0] + f[2]}) {
                        if (left < ex && right > ex && bottom > f[1] && top < f[1] + f[3]) {
                            moved = true;
                            if (li.labelX >= ex) li.labelX += (ex - left) + 4;
                            else                 li.labelX -= (right - ex) + 4;
                            left  = li.labelX - li.labelW / 2.0;
                            right = li.labelX + li.labelW / 2.0;
                        }
                    }
                    for (double ey : new double[]{f[1], f[1] + f[3]}) {
                        if (top < ey && bottom > ey && right > f[0] && left < f[0] + f[2]) {
                            moved = true;
                            if (li.labelY >= ey) li.labelY += (ey - top) + 4;
                            else                 li.labelY -= (bottom - ey) + 4;
                            top    = li.labelY - li.labelH / 2.0;
                            bottom = li.labelY + li.labelH / 2.0;
                        }
                    }
                }
            }

            // Label vs element bounding box.
            // Relationship labels must not paint over the text inside element boxes.
            for (Connectors.LabelInfo li : labels) {
                if (!li.hasLabel) continue;
                for (double[] obs : elementObstacles) {
                    double ox = overlapAxis(li.labelX, li.labelW, obs[0], (int) obs[2]);
                    double oy = overlapAxis(li.labelY, li.labelH, obs[1], (int) obs[3]);
                    if (ox > 0 && oy > 0) {
                        moved = true;
                        double dx = li.labelX - obs[0];
                        double dy = li.labelY - obs[1];
                        double len = Math.sqrt(dx * dx + dy * dy);
                        if (len < 1) { dx = 0; dy = -1; len = 1; }
                        li.labelX += (ox / 2.0 + 4) * (dx / len);
                        li.labelY += (oy / 2.0 + 4) * (dy / len);
                    }
                }
            }

            // Label vs foreign line segments.
            // For each label, check every segment of every OTHER relationship's path.
            // If a segment comes within the label's bounding-circle radius (plus a small
            // buffer), push the label away from the nearest point on that segment.
            // This prevents labels from visually hugging a line they don't belong to.
            for (int i = 0; i < labels.size(); i++) {
                Connectors.LabelInfo li = labels.get(i);
                if (!li.hasLabel) continue;
                // Exclusion radius: half-diagonal of the label rect + 40px clearance buffer
                double clearance = Math.hypot(li.labelW / 2.0, li.labelH / 2.0) + 40;
                for (int j = 0; j < labels.size(); j++) {
                    if (i == j) continue;
                    List<double[]> pts = labels.get(j).pathPoints;
                    for (int k = 0; k < pts.size() - 1; k++) {
                        double[] cp = closestPointOnSegment(
                            li.labelX, li.labelY,
                            pts.get(k)[0], pts.get(k)[1],
                            pts.get(k + 1)[0], pts.get(k + 1)[1]);
                        double dx = li.labelX - cp[0];
                        double dy = li.labelY - cp[1];
                        double dist = Math.hypot(dx, dy);
                        if (dist < clearance) {
                            moved = true;
                            double force = (clearance - dist) * 0.25;
                            if (dist > 0.1) {
                                li.labelX += (dx / dist) * force;
                                li.labelY += (dy / dist) * force;
                            } else {
                                // Exactly on the segment — push perpendicular to it
                                double sdx = pts.get(k + 1)[0] - pts.get(k)[0];
                                double sdy = pts.get(k + 1)[1] - pts.get(k)[1];
                                double slen = Math.hypot(sdx, sdy);
                                if (slen > 0) {
                                    li.labelX += (-sdy / slen) * force;
                                    li.labelY +=  (sdx / slen) * force;
                                }
                            }
                        }
                    }
                }
            }

            // Tethering: pull each label toward the nearest point on its OWN path.
            // Using the dynamic nearest-path-point (not a fixed origin) ensures the label
            // stays anchored to the correct line even after being pushed sideways by
            // foreign-line repulsion, guaranteeing it remains closer to its own line
            // than to any foreign line at equilibrium.
            for (Connectors.LabelInfo li : labels) {
                if (!li.hasLabel) continue;
                double[] np = nearestPointOnPath(li.pathPoints, li.labelX, li.labelY);
                li.labelX += (np[0] - li.labelX) * TETHER;
                li.labelY += (np[1] - li.labelY) * TETHER;
            }

            if (!moved) break;
        }
    }

    private static boolean repelPair(Connectors.LabelInfo a, Connectors.LabelInfo b) {
        if (!a.hasLabel || !b.hasLabel) return false;
        double ox = overlapAxis(a.labelX, a.labelW, b.labelX, b.labelW);
        double oy = overlapAxis(a.labelY, a.labelH, b.labelY, b.labelH);
        if (ox <= 0 || oy <= 0) return false;
        double dx = b.labelX - a.labelX;
        double dy = b.labelY - a.labelY;
        double len = Math.sqrt(dx * dx + dy * dy);
        if (len < 1) { dx = 0; dy = 1; len = 1; }
        double pushX = (ox / 2.0 + 4) * (dx / len);
        double pushY = (oy / 2.0 + 4) * (dy / len);
        a.labelX -= pushX;  a.labelY -= pushY;
        b.labelX += pushX;  b.labelY += pushY;
        return true;
    }

    /** Returns the nearest point on the polyline defined by pts to (px,py). */
    private static double[] nearestPointOnPath(List<double[]> pts, double px, double py) {
        double bestDist = Double.MAX_VALUE;
        double[] best = pts.get(0);
        for (int k = 0; k < pts.size() - 1; k++) {
            double[] cp = closestPointOnSegment(px, py,
                pts.get(k)[0], pts.get(k)[1], pts.get(k + 1)[0], pts.get(k + 1)[1]);
            double d = Math.hypot(px - cp[0], py - cp[1]);
            if (d < bestDist) { bestDist = d; best = cp; }
        }
        return best;
    }

    /** Returns the closest point on segment (ax,ay)→(bx,by) to point (px,py). */
    private static double[] closestPointOnSegment(double px, double py,
                                                   double ax, double ay, double bx, double by) {
        double dx = bx - ax, dy = by - ay;
        double lenSq = dx * dx + dy * dy;
        if (lenSq < 1e-10) return new double[]{ax, ay};
        double t = Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / lenSq));
        return new double[]{ax + t * dx, ay + t * dy};
    }

    /** Overlap on one axis between two centred spans. Positive means they overlap. */
    private static double overlapAxis(double ca, int wa, double cb, int wb) {
        return Math.min(ca + wa / 2.0, cb + wb / 2.0) - Math.max(ca - wa / 2.0, cb - wb / 2.0);
    }

    /**
     * Returns the intersection point of segments (a1→a2) and (b1→b2), or null if
     * they don't cross.  Skips near-endpoint hits (t,s outside 0.05–0.95) so that
     * elements which share a boundary don't produce false crossings.
     */
    private static double[] segmentIntersect(double[] a1, double[] a2, double[] b1, double[] b2) {
        double dax = a2[0] - a1[0], day = a2[1] - a1[1];
        double dbx = b2[0] - b1[0], dby = b2[1] - b1[1];
        double cross = dax * dby - day * dbx;
        if (Math.abs(cross) < 1e-10) return null; // parallel
        double dx = b1[0] - a1[0], dy = b1[1] - a1[1];
        double t = (dx * dby - dy * dbx) / cross;
        double s = (dx * day  - dy * dax) / cross;
        if (t > 0.05 && t < 0.95 && s > 0.05 && s < 0.95) {
            return new double[]{a1[0] + t * dax, a1[1] + t * day};
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Elements
    // -------------------------------------------------------------------------

    @Override
    protected void writeElement(ModelView view, Element element, IndentingWriter writer) {
        // Track element in ALL open boundaries (innermost and all ancestors)
        for (BoundaryState state : boundaryStack) {
            state.addElement(element.getId());
        }

        ElementView ev = view.getElementView(element);
        if (ev == null) return;

        ElementStyle style = findElementStyle(view, element);
        int[] rect = Shapes.elementRect(view, element, style, ev.getX(), ev.getY());
        int ex = rect[0], ey = rect[1], w = rect[2], h = rect[3];

        actualMinX = Math.min(actualMinX, ex);
        actualMinY = Math.min(actualMinY, ey);
        actualMaxX = Math.max(actualMaxX, ex + w);
        actualMaxY = Math.max(actualMaxY, ey + h);

        // Record element bbox so repelLabels can keep relationship labels clear of element text
        elementObstacles.add(new double[]{ex + w / 2.0, ey + h / 2.0, w, h});

        writer.writeLine(Shapes.render(view, element, style, ex, ey, w, h));
    }

    // -------------------------------------------------------------------------
    // Relationships
    // -------------------------------------------------------------------------

    @Override
    protected void writeRelationship(ModelView view, RelationshipView rv, IndentingWriter writer) {
        Relationship rel = rv.getRelationship();

        ElementView srcEv = view.getElementView(view.getModel().getElement(rel.getSourceId()));
        ElementView dstEv = view.getElementView(view.getModel().getElement(rel.getDestinationId()));
        if (srcEv == null || dstEv == null) return;

        RelationshipStyle style = findRelationshipStyle(view, rel);

        double[] srcRect = connectionRect(view, srcEv);
        double[] dstRect = connectionRect(view, dstEv);

        // Dynamic-view response steps reuse the underlying model relationship but
        // flow in the opposite direction — swap the endpoints so the arrow points
        // back from destination to source.
        if (Boolean.TRUE.equals(rv.isResponse())) {
            double[] tmp = srcRect;
            srcRect = dstRect;
            dstRect = tmp;
        }

        // Defer layout until writeFooter: relationships sharing an element pair are
        // spread into parallel lines, and a repulsion pass runs over all labels.
        pendingRelData.add(new PendingRel(rv, srcRect, dstRect, style));
    }

    /**
     * The rectangle a relationship should connect to: the boundary box when the
     * element is rendered as a boundary (deployment nodes etc.), otherwise the
     * element's own box.
     */
    private double[] connectionRect(ModelView view, ElementView ev) {
        double[] boundary = boundaryRects.get(ev.getElement().getId());
        if (boundary != null) return boundary;
        ElementStyle style = findElementStyle(view, ev.getElement());
        int[] rect = Shapes.elementRect(view, ev.getElement(), style, ev.getX(), ev.getY());
        return new double[]{rect[0], rect[1], rect[2], rect[3]};
    }

    // -------------------------------------------------------------------------
    // Boundaries: push/pop a state; emit rect at end when bbox is known
    // -------------------------------------------------------------------------

    @Override
    protected void startEnterpriseBoundary(ModelView view, String enterpriseName, IndentingWriter writer) {
        boundaryStack.push(new BoundaryState(enterpriseName, BoundaryType.Enterprise, "#444444", "2,4"));
    }

    @Override
    protected void endEnterpriseBoundary(ModelView view, IndentingWriter writer) {
        if (boundaryStack.isEmpty()) return;
        BoundaryState state = boundaryStack.pop();
        writeBoundaryRect(view, state, writer);
    }

    @Override
    protected void startGroupBoundary(ModelView view, String group, IndentingWriter writer) {
        boundaryStack.push(new BoundaryState(group, BoundaryType.Group, "#444444", "2,4"));
    }

    @Override
    protected void endGroupBoundary(ModelView view, IndentingWriter writer) {
        if (boundaryStack.isEmpty()) return;
        BoundaryState state = boundaryStack.pop();
        writeBoundaryRect(view, state, writer);
    }

    @Override
    protected void startSoftwareSystemBoundary(ModelView view, SoftwareSystem softwareSystem,
                                                IndentingWriter writer) {
        ElementStyle style = findElementStyle(view, softwareSystem);
        String bg = style.getBackground() != null ? style.getBackground() : "#1168bd";
        String stroke = Shapes.shadeColor(bg, -10);
        boundaryStack.push(new BoundaryState(softwareSystem.getName(), BoundaryType.SoftwareSystem,
                                              stroke, "", softwareSystem.getId()));
    }

    @Override
    protected void endSoftwareSystemBoundary(ModelView view, IndentingWriter writer) {
        if (boundaryStack.isEmpty()) return;
        BoundaryState state = boundaryStack.pop();
        writeBoundaryRect(view, state, writer);
    }

    @Override
    protected void startContainerBoundary(ModelView view, Container container, IndentingWriter writer) {
        ElementStyle style = findElementStyle(view, container);
        String bg = style.getBackground() != null ? style.getBackground() : "#438dd5";
        String stroke = Shapes.shadeColor(bg, -10);
        boundaryStack.push(new BoundaryState(container.getName(), BoundaryType.Container,
                                              stroke, "", container.getId()));
    }

    @Override
    protected void endContainerBoundary(ModelView view, IndentingWriter writer) {
        if (boundaryStack.isEmpty()) return;
        BoundaryState state = boundaryStack.pop();
        writeBoundaryRect(view, state, writer);
    }

    @Override
    protected void startDeploymentNodeBoundary(DeploymentView view, DeploymentNode deploymentNode,
                                               IndentingWriter writer) {
        ElementStyle style = findElementStyle(view, deploymentNode);
        String strokeColor = style.getStroke() != null ? style.getStroke()
                           : style.getColor()  != null ? style.getColor()
                           : "#444444";
        String iconDataUri = null;
        if (style.getIcon() != null && !style.getIcon().isBlank()) {
            iconDataUri = IconCache.toDataUri(style.getIcon());
        }
        boundaryStack.push(new BoundaryState(deploymentNode.getName(), BoundaryType.DeploymentNode,
                                              strokeColor, "", deploymentNode.getId(), iconDataUri));
    }

    @Override
    protected void endDeploymentNodeBoundary(ModelView view, IndentingWriter writer) {
        if (boundaryStack.isEmpty()) return;
        BoundaryState state = boundaryStack.pop();
        writeBoundaryRect(view, state, writer);
    }

    // -------------------------------------------------------------------------
    // Boundary rect drawing
    // -------------------------------------------------------------------------

    private void writeBoundaryRect(ModelView view, BoundaryState state, IndentingWriter writer) {
        String strokeColor = state.strokeColor;
        String dashArray   = state.dashArray;
        if (state.elementIds.isEmpty() && !state.hasChildBounds()) return;

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;

        for (String id : state.elementIds) {
            Element element = view.getModel().getElement(id);
            if (element == null) continue;
            ElementView ev = view.getElementView(element);
            if (ev == null) continue;

            ElementStyle style = findElementStyle(view, element);
            int[] rect = Shapes.elementRect(view, element, style, ev.getX(), ev.getY());

            minX = Math.min(minX, rect[0]);
            minY = Math.min(minY, rect[1]);
            maxX = Math.max(maxX, rect[0] + rect[2]);
            maxY = Math.max(maxY, rect[1] + rect[3]);
        }

        // Expand to include any nested child boundary rects that were already drawn
        if (state.hasChildBounds()) {
            minX = Math.min(minX, state.childMinX);
            minY = Math.min(minY, state.childMinY);
            maxX = Math.max(maxX, state.childMaxX);
            maxY = Math.max(maxY, state.childMaxY);
        }

        if (minX == Integer.MAX_VALUE) return;

        int bx = minX - BOUNDARY_PADDING;
        int by = minY - BOUNDARY_PADDING;
        int bw = (maxX - minX) + BOUNDARY_PADDING * 2;
        int bh = (maxY - minY) + BOUNDARY_PADDING * 2 + BOUNDARY_LABEL_HEIGHT;
        int fontSize = 33;

        // Track actual drawn extent so createDiagram() can size the canvas correctly
        actualMinX = Math.min(actualMinX, bx);
        actualMinY = Math.min(actualMinY, by);
        actualMaxX = Math.max(actualMaxX, bx + bw);
        actualMaxY = Math.max(actualMaxY, by + bh);

        // Propagate this boundary's rect to the parent boundary (if nested)
        if (!boundaryStack.isEmpty()) {
            boundaryStack.peek().expandChildBounds(bx, by, bx + bw, by + bh);
        }

        // Relationships to/from this boundary's element connect to the boundary box
        if (state.elementId != null) {
            boundaryRects.put(state.elementId, new double[]{bx, by, bw, bh});
        }

        boundaryFrames.add(new double[]{bx, by, bw, bh});

        String dashAttr = dashArray.isEmpty() ? "" : String.format(" stroke-dasharray=\"%s\"", dashArray);
        writer.writeLine(String.format(
            "<rect x=\"%d\" y=\"%d\" width=\"%d\" height=\"%d\" rx=\"0\" " +
            "fill=\"none\" stroke=\"%s\" stroke-width=\"2\"%s/>",
            bx, by, bw, bh, strokeColor, dashAttr));

        int labelTextX;
        if (state.iconDataUri != null) {
            // Small icon to the left of the label at the bottom of the boundary box
            int iconSize = 36;
            int iconX    = bx + 8;
            int iconY    = by + bh - iconSize - 8;
            writer.writeLine(String.format(
                "<image x=\"%d\" y=\"%d\" width=\"%d\" height=\"%d\" href=\"%s\" xlink:href=\"%s\"/>",
                iconX, iconY, iconSize, iconSize, state.iconDataUri, state.iconDataUri));
            labelTextX = iconX + iconSize + 6;
            writer.writeLine(String.format(
                "<text x=\"%d\" y=\"%d\" font-family=\"%s\" font-size=\"%d\" " +
                "font-weight=\"bold\" fill=\"%s\">%s</text>",
                labelTextX, by + bh - 15, Shapes.DEFAULT_FONT, fontSize,
                strokeColor, Shapes.htmlEscape(state.label)));
        } else {
            labelTextX = bx + 15;
            writer.writeLine(String.format(
                "<text x=\"%d\" y=\"%d\" font-family=\"%s\" font-size=\"%d\" " +
                "font-weight=\"bold\" fill=\"%s\">%s</text>",
                labelTextX, by + bh - 15, Shapes.DEFAULT_FONT, fontSize,
                strokeColor, Shapes.htmlEscape(state.label)));
        }

        // Record boundary label text area as a static obstacle so relationship labels
        // are repelled away from it and don't paint over the boundary label text.
        int labelObstacleW = (int) Math.ceil(TextMetrics.width(state.label, fontSize, true)) + 20;
        int labelObstacleH = fontSize + 12;
        elementObstacles.add(new double[]{
            labelTextX + labelObstacleW / 2.0,
            by + bh - 15 - fontSize / 2.0,
            labelObstacleW,
            labelObstacleH
        });
    }

    // -------------------------------------------------------------------------
    // createDiagram
    // -------------------------------------------------------------------------

    @Override
    protected Diagram createDiagram(ModelView view, String definition) {
        // Replace placeholder dimensions with the actual bounds tracked during rendering.
        // The translate shifts content so the minimum coordinate (possibly negative with
        // manual layouts) lands at PADDING from the canvas edge.
        int cw = (actualMaxX - actualMinX) + PADDING * 2;
        int ch = (actualMaxY - actualMinY) + PADDING * 2;
        String fixed = definition
            .replace(W_TOKEN,  String.valueOf(cw))
            .replace(H_TOKEN,  String.valueOf(ch))
            .replace(TX_TOKEN, String.valueOf(PADDING - actualMinX))
            .replace(TY_TOKEN, String.valueOf(PADDING - actualMinY));
        return new SvgDiagram(view, fixed);
    }

    // -------------------------------------------------------------------------
    // Inner types
    // -------------------------------------------------------------------------

    private enum BoundaryType { Enterprise, Group, SoftwareSystem, Container, DeploymentNode }

    private static class BoundaryState {
        final String label;
        final BoundaryType type;
        final String strokeColor;
        final String dashArray;
        final String iconDataUri;  // nullable; base64 data URI for boundary label icon
        final String elementId;    // nullable; id of the model element this boundary represents
        final List<String> elementIds = new ArrayList<>();
        int childMinX = Integer.MAX_VALUE, childMinY = Integer.MAX_VALUE;
        int childMaxX = Integer.MIN_VALUE, childMaxY = Integer.MIN_VALUE;

        BoundaryState(String label, BoundaryType type, String strokeColor, String dashArray) {
            this(label, type, strokeColor, dashArray, null, null);
        }

        BoundaryState(String label, BoundaryType type, String strokeColor, String dashArray, String elementId) {
            this(label, type, strokeColor, dashArray, elementId, null);
        }

        BoundaryState(String label, BoundaryType type, String strokeColor, String dashArray,
                      String elementId, String iconDataUri) {
            this.label       = label;
            this.type        = type;
            this.strokeColor = strokeColor;
            this.dashArray   = dashArray;
            this.elementId   = elementId;
            this.iconDataUri = iconDataUri;
        }

        void addElement(String id) { elementIds.add(id); }

        boolean hasChildBounds() { return childMinX != Integer.MAX_VALUE; }

        void expandChildBounds(int x1, int y1, int x2, int y2) {
            childMinX = Math.min(childMinX, x1);
            childMinY = Math.min(childMinY, y1);
            childMaxX = Math.max(childMaxX, x2);
            childMaxY = Math.max(childMaxY, y2);
        }
    }
}
