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
    private static final String W_TOKEN = "__SVG_CANVAS_W__";
    private static final String H_TOKEN = "__SVG_CANVAS_H__";

    // Per-view state (reset in writeHeader)
    private ModelView currentView;
    private Deque<BoundaryState> boundaryStack;
    private List<Connectors.LabelInfo> pendingRelationships;
    // Element bounding boxes collected during writeElement, used as static obstacles
    // in repelLabels so relationship labels don't paint over element label text.
    // Each entry: [centerX, centerY, width, height]
    private List<double[]> elementObstacles;
    // Tracks max right/bottom edge in group (translated) space across all drawn content
    private int actualMaxX;
    private int actualMaxY;

    // -------------------------------------------------------------------------
    // Header / Footer
    // -------------------------------------------------------------------------

    @Override
    protected void writeHeader(ModelView view, IndentingWriter writer) {
        this.currentView = view;
        this.boundaryStack = new ArrayDeque<>();
        this.pendingRelationships = new ArrayList<>();
        this.elementObstacles = new ArrayList<>();
        this.actualMaxX = 0;
        this.actualMaxY = 0;

        String bg = "#ffffff";

        // Dimensions are placeholders; createDiagram() replaces them with actual tracked bounds.
        writer.writeLine(
            "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" " +
            "version=\"1.1\" width=\"" + W_TOKEN + "\" height=\"" + H_TOKEN + "\" " +
            "viewBox=\"0 0 " + W_TOKEN + " " + H_TOKEN + "\">");
        writer.writeLine(String.format("<rect width=\"" + W_TOKEN + "\" height=\"" + H_TOKEN + "\" fill=\"%s\"/>", bg));
        writer.writeLine(Connectors.DEFS_BLOCK);
        writer.writeLine(String.format("<g transform=\"translate(%d,%d)\">", PADDING, PADDING));
    }

    @Override
    protected void writeFooter(ModelView view, IndentingWriter writer) {
        // Deduplicate: dynamic views supply both a static-model RelationshipView and a
        // dynamic-step RelationshipView for the same element pair.  When two entries share
        // an identical path, keep the one that has a label (the dynamic step) and drop the
        // plain static duplicate.
        java.util.Map<String, Connectors.LabelInfo> deduped = new java.util.LinkedHashMap<>();
        for (Connectors.LabelInfo li : pendingRelationships) {
            Connectors.LabelInfo existing = deduped.get(li.pathD);
            if (existing == null || (!existing.hasLabel && li.hasLabel)) {
                deduped.put(li.pathD, li);
            }
        }
        pendingRelationships = new ArrayList<>(deduped.values());

        repelLabels(pendingRelationships, elementObstacles);
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
    private static void repelLabels(List<Connectors.LabelInfo> labels, List<double[]> elementObstacles) {
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

        // --- Phase 2: iterative repulsion with tethering ---
        // Obstacle zone: label-sized forbidden region around each crossing point.
        final int OBS_W = 110, OBS_H = 70;
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
        int[] dims = Shapes.defaultDimensions(element, style);
        int w = dims[0], h = dims[1];

        actualMaxX = Math.max(actualMaxX, ev.getX() + w);
        actualMaxY = Math.max(actualMaxY, ev.getY() + h);

        // Record element bbox so repelLabels can keep relationship labels clear of element text
        elementObstacles.add(new double[]{ev.getX() + w / 2.0, ev.getY() + h / 2.0, w, h});

        writer.writeLine(Shapes.render(view, element, style, ev.getX(), ev.getY(), w, h));
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

        // Defer rendering: collect layout data so we can run a repulsion pass over all labels
        // before writing anything, ensuring crossing-edge labels don't overlap.
        pendingRelationships.add(Connectors.computeLayout(rv, srcEv, dstEv, style, view));
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
        boundaryStack.push(new BoundaryState(softwareSystem.getName(), BoundaryType.SoftwareSystem, stroke, ""));
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
        boundaryStack.push(new BoundaryState(container.getName(), BoundaryType.Container, stroke, ""));
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
                                              strokeColor, "", iconDataUri));
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
            int[] dims = Shapes.defaultDimensions(element, style);
            int w = dims[0], h = dims[1];

            minX = Math.min(minX, ev.getX());
            minY = Math.min(minY, ev.getY());
            maxX = Math.max(maxX, ev.getX() + w);
            maxY = Math.max(maxY, ev.getY() + h);
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
        actualMaxX = Math.max(actualMaxX, bx + bw);
        actualMaxY = Math.max(actualMaxY, by + bh);

        // Propagate this boundary's rect to the parent boundary (if nested)
        if (!boundaryStack.isEmpty()) {
            boundaryStack.peek().expandChildBounds(bx, by, bx + bw, by + bh);
        }

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
        int labelObstacleW = (int)(state.label.length() * fontSize * 0.6) + 20;
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
        // actualMaxX/Y are in group (translated) space; add PADDING for the right/bottom margins.
        int cw = actualMaxX + PADDING * 2;
        int ch = actualMaxY + PADDING * 2;
        String fixed = definition
            .replace(W_TOKEN, String.valueOf(cw))
            .replace(H_TOKEN, String.valueOf(ch));
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
        final List<String> elementIds = new ArrayList<>();
        int childMinX = Integer.MAX_VALUE, childMinY = Integer.MAX_VALUE;
        int childMaxX = Integer.MIN_VALUE, childMaxY = Integer.MIN_VALUE;

        BoundaryState(String label, BoundaryType type, String strokeColor, String dashArray) {
            this(label, type, strokeColor, dashArray, null);
        }

        BoundaryState(String label, BoundaryType type, String strokeColor, String dashArray, String iconDataUri) {
            this.label       = label;
            this.type        = type;
            this.strokeColor = strokeColor;
            this.dashArray   = dashArray;
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
