package com.structurizr.renderer.svg;

import com.structurizr.model.Relationship;
import com.structurizr.view.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class Connectors {

    /** Shared defs: embedded font, arrowhead marker, soft element shadow. */
    static String defsBlock() {
        String fontCss = BundledFonts.fontFaceCss();
        return "<defs>\n"
            + (fontCss.isEmpty() ? "" : "<style type=\"text/css\">\n" + fontCss + "\n</style>\n")
            // Sleek chevron arrowhead with a slightly concave back
            + "<marker id=\"arrow\" orient=\"auto\" overflow=\"visible\" markerUnits=\"userSpaceOnUse\">"
            + "<path transform=\"rotate(180)\" d=\"M 0 0 L 18 -7 L 14 0 L 18 7 Z\" fill=\"#444444\"/>"
            + "</marker>\n"
            // Soft drop shadow, spelled out long-hand for Batik compatibility
            + "<filter id=\"elem-shadow\" x=\"-15%\" y=\"-15%\" width=\"130%\" height=\"130%\">"
            + "<feGaussianBlur in=\"SourceAlpha\" stdDeviation=\"5\"/>"
            + "<feOffset dx=\"0\" dy=\"3\" result=\"ob\"/>"
            + "<feFlood flood-color=\"#000000\" flood-opacity=\"0.22\"/>"
            + "<feComposite in2=\"ob\" operator=\"in\"/>"
            + "<feMerge><feMergeNode/><feMergeNode in=\"SourceGraphic\"/></feMerge>"
            + "</filter>\n"
            + "</defs>\n";
    }

    // -------------------------------------------------------------------------
    // Public API used by SvgDiagramExporter
    // -------------------------------------------------------------------------

    /**
     * Holds the fully-computed layout for one relationship: the SVG path data plus
     * mutable label position (adjusted by the caller's overlap-repulsion pass).
     */
    static class LabelInfo {
        // Path rendering
        final String pathD;
        final String dashAttr;
        final String color;
        final int    thickness;

        // All points on the path (clipped endpoints + intermediate waypoints).
        // Used by the crossing-detection pass in SvgDiagramExporter.
        final List<double[]> pathPoints;

        // Label content (null lists / false hasLabel → no label)
        final boolean      hasLabel;
              double       labelX;       // mutable — adjusted during repulsion
              double       labelY;       // mutable
        final double       origLabelX;   // initial position before repulsion
        final double       origLabelY;
        final List<String> descLines;
        final List<String> techLines;
        final int fontSize;
        final int techFontSize;
        final int descLineH;
        final int techLineH;

        // Bounding-box dimensions for overlap detection (0 when hasLabel=false)
        final int labelW;  // max line width + padding
        final int labelH;  // total block height including padding

        LabelInfo(String pathD, String dashAttr, String color, int thickness,
                  List<double[]> pathPoints,
                  boolean hasLabel, double labelX, double labelY,
                  List<String> descLines, List<String> techLines,
                  int fontSize, int techFontSize, int descLineH, int techLineH,
                  int labelW, int labelH) {
            this.pathD        = pathD;
            this.dashAttr     = dashAttr;
            this.color        = color;
            this.thickness    = thickness;
            this.pathPoints   = pathPoints;
            this.hasLabel     = hasLabel;
            this.labelX       = labelX;
            this.labelY       = labelY;
            this.origLabelX   = labelX;
            this.origLabelY   = labelY;
            this.descLines    = descLines;
            this.techLines    = techLines;
            this.fontSize     = fontSize;
            this.techFontSize = techFontSize;
            this.descLineH    = descLineH;
            this.techLineH    = techLineH;
            this.labelW       = labelW;
            this.labelH       = labelH;
        }
    }

    /** The label text shown for a relationship view: the view-level description
     *  (set on dynamic-view steps) when present, otherwise the model-level one. */
    static String effectiveDescription(RelationshipView rv) {
        String rvDesc = rv.getDescription();
        return (rvDesc != null && !rvDesc.isEmpty()) ? rvDesc : rv.getRelationship().getDescription();
    }

    /**
     * Compute path geometry and label position for one relationship.
     * srcRect/dstRect are {x, y, w, h} of the source/destination — either the
     * element's box, or the boundary rectangle when the endpoint is rendered as
     * a boundary (e.g. deployment nodes).
     * (offsetX, offsetY) shifts the whole line sideways before clipping, used to
     * spread multiple relationships between the same element pair into visually
     * distinct parallel lines.  positionOverride (nullable) replaces the style's
     * label position percentage, used to stagger the labels of spread lines so
     * they don't start on top of each other.
     */
    static LabelInfo computeLayout(RelationshipView rv, double[] srcRect, double[] dstRect,
                                    RelationshipStyle style, double offsetX, double offsetY,
                                    Integer positionOverride) {
        return computeLayout(rv, srcRect, dstRect, style, offsetX, offsetY, positionOverride, null);
    }

    /**
     * As above; {@code avoid} (nullable) lists foreign boxes [x, y, w, h] that a
     * plain direct line must not pass through — detour waypoints are inserted
     * around them.
     */
    static LabelInfo computeLayout(RelationshipView rv, double[] srcRect, double[] dstRect,
                                    RelationshipStyle style, double offsetX, double offsetY,
                                    Integer positionOverride, List<double[]> avoid) {
        Relationship rel = rv.getRelationship();

        String rawColor  = style.getColor();
        String color     = (rawColor == null || "#707070".equalsIgnoreCase(rawColor)) ? "#444444" : rawColor;
        int    thickness = style.getThickness() != null ? style.getThickness() : 2;
        boolean dashed   = style.getDashed()    != null ? style.getDashed()    : true;
        Routing routing  = style.getRouting()   != null ? style.getRouting()   : Routing.Direct;
        // Default to 25% (near source) rather than 50% (midpoint).
        // Labels near the source end are above the region where edges typically cross,
        // and the repulsion+crossing-avoidance pass then fine-tunes positions.
        int position     = positionOverride != null ? positionOverride
                         : style.getPosition()  != null ? style.getPosition()  : 25;
        int fontSize     = style.getFontSize()  != null ? style.getFontSize()  : 24;

        String dashAttr = dashed ? " stroke-dasharray=\"8 8\"" : "";

        Collection<Vertex> routingVertices = rv.getVertices();
        String pathD;
        double labelX, labelY;
        List<double[]> pathPoints = new ArrayList<>();

        if (rel.getSourceId().equals(rel.getDestinationId())) {
            // Self-relationship: draw a loop bulging out from the element's right edge,
            // exiting a third of the way down and re-entering two thirds of the way down.
            double ex   = srcRect[0] + srcRect[2];
            double topY = srcRect[1] + srcRect[3] / 3.0;
            double botY = srcRect[1] + srcRect[3] * 2 / 3.0;
            double ext  = 180;
            pathD = String.format("M %.1f %.1f C %.1f %.1f %.1f %.1f %.1f %.1f",
                ex, topY, ex + ext, topY, ex + ext, botY, ex, botY);
            for (double t = 0; t <= 1.0001; t += 0.25) {
                pathPoints.add(cubicPoint(ex, topY, ex + ext, topY, ex + ext, botY, ex, botY, t));
            }
            // Centre the label on the loop's rightmost point (the curve at t=0.5
            // reaches ex + 0.75*ext)
            labelX = ex + ext * 0.75;
            labelY = (topY + botY) / 2.0;
        } else if ((offsetX != 0 || offsetY != 0) && routingVertices.isEmpty()
                   && routing != Routing.Curved) {
            // Spread member: kinked route like the reference renderer. The line leaves
            // the box close to the natural anchor (a fraction of the offset), jogs out
            // to the parallel corridor (the full offset), runs along it, and jogs back
            // in — giving the pair's lines and labels real separation in the middle.
            double eoX = offsetX * 0.35, eoY = offsetY * 0.35;
            double x1 = srcRect[0] + srcRect[2] / 2.0 + eoX;
            double y1 = srcRect[1] + srcRect[3] / 2.0 + eoY;
            double x2 = dstRect[0] + dstRect[2] / 2.0 + eoX;
            double y2 = dstRect[1] + dstRect[3] / 2.0 + eoY;

            double[] p1 = clipToRect(x1, y1, x2, y2, srcRect[0], srcRect[1], srcRect[2], srcRect[3]);
            double[] p2 = clipToRect(x2, y2, x1, y1, dstRect[0], dstRect[1], dstRect[2], dstRect[3]);

            double ddx = p2[0] - p1[0], ddy = p2[1] - p1[1];
            double len = Math.hypot(ddx, ddy);
            double inset = Math.min(80, len / 4);
            double ux = len > 0 ? ddx / len : 0, uy = len > 0 ? ddy / len : 0;
            double corrX = offsetX - eoX, corrY = offsetY - eoY;

            pathPoints.add(p1);
            pathPoints.add(new double[]{p1[0] + ux * inset + corrX, p1[1] + uy * inset + corrY});
            pathPoints.add(new double[]{p2[0] - ux * inset + corrX, p2[1] - uy * inset + corrY});
            pathPoints.add(p2);

            if (avoid != null && !avoid.isEmpty()) {
                // Stagger clearance per corridor side so a pair detouring around the
                // same box doesn't collapse onto identical corner points.
                double clearance = 30 + (offsetX + offsetY > 0 ? 0 : 22);
                insertDetours(pathPoints, avoid, clearance);
            }

            StringBuilder path = new StringBuilder();
            path.append(String.format("M %.1f %.1f", p1[0], p1[1]));
            for (int i = 1; i < pathPoints.size(); i++) {
                path.append(String.format(" L %.1f %.1f", pathPoints.get(i)[0], pathPoints.get(i)[1]));
            }
            pathD = path.toString();

            double[] lpos = pathPointAtFraction(pathPoints, position / 100.0);
            labelX = lpos[0];
            labelY = lpos[1] - 6;
        } else {
            double x1 = srcRect[0] + srcRect[2] / 2.0 + offsetX;
            double y1 = srcRect[1] + srcRect[3] / 2.0 + offsetY;
            double x2 = dstRect[0] + dstRect[2] / 2.0 + offsetX;
            double y2 = dstRect[1] + dstRect[3] / 2.0 + offsetY;

            double[] p1 = clipToRect(x1, y1, x2, y2, srcRect[0], srcRect[1], srcRect[2], srcRect[3]);
            double[] p2 = clipToRect(x2, y2, x1, y1, dstRect[0], dstRect[1], dstRect[2], dstRect[3]);

            // Build the ordered list of path points (clipped endpoints + waypoints) once,
            // for both path-string construction and crossing detection.
            pathPoints.add(p1);
            for (Vertex v : routingVertices) pathPoints.add(new double[]{v.getX(), v.getY()});
            pathPoints.add(p2);

            if (!routingVertices.isEmpty()) {
                if (avoid != null && !avoid.isEmpty()) {
                    insertDetours(pathPoints, avoid, 30);
                }
                StringBuilder path = new StringBuilder();
                path.append(String.format("M %.1f %.1f", pathPoints.get(0)[0], pathPoints.get(0)[1]));
                for (int i = 1; i < pathPoints.size(); i++) {
                    path.append(String.format(" L %.1f %.1f", pathPoints.get(i)[0], pathPoints.get(i)[1]));
                }
                pathD = path.toString();
                double[] lpos = pathPointAtFraction(pathPoints, position / 100.0);
                labelX = lpos[0];
                labelY = lpos[1] - 6;
            } else if (routing == Routing.Curved) {
                double midX = (p1[0] + p2[0]) / 2;
                double midY = (p1[1] + p2[1]) / 2;
                double dx = p2[0] - p1[0];
                double dy = p2[1] - p1[1];
                double cpX = midX - dy * 0.2;
                double cpY = midY + dx * 0.2;
                pathD  = String.format("M %.1f %.1f Q %.1f %.1f %.1f %.1f", p1[0], p1[1], cpX, cpY, p2[0], p2[1]);
                // Replace the straight chord with samples along the curve so crossing
                // detection, tethering and clearance act on the visible geometry.
                pathPoints.clear();
                for (double t = 0; t <= 1.0001; t += 0.125) {
                    pathPoints.add(quadPoint(p1, cpX, cpY, p2, t));
                }
                double[] lp = quadPoint(p1, cpX, cpY, p2, position / 100.0);
                labelX = lp[0];
                labelY = lp[1] - 6;
            } else {
                if (avoid != null && !avoid.isEmpty()) {
                    insertDetours(pathPoints, avoid, 30);
                }
                if (pathPoints.size() > 2) {
                    StringBuilder path = new StringBuilder();
                    path.append(String.format("M %.1f %.1f", pathPoints.get(0)[0], pathPoints.get(0)[1]));
                    for (int i = 1; i < pathPoints.size(); i++) {
                        path.append(String.format(" L %.1f %.1f", pathPoints.get(i)[0], pathPoints.get(i)[1]));
                    }
                    pathD = path.toString();
                    double[] lpos = pathPointAtFraction(pathPoints, position / 100.0);
                    labelX = lpos[0];
                    labelY = lpos[1] - 6;
                } else {
                    pathD  = String.format("M %.1f %.1f L %.1f %.1f", p1[0], p1[1], p2[0], p2[1]);
                    double t = position / 100.0;
                    labelX = p1[0] + t * (p2[0] - p1[0]);
                    labelY = p1[1] + t * (p2[1] - p1[1]) - 6;
                }
            }
        }

        String description = effectiveDescription(rv);
        String technology  = rel.getTechnology();
        boolean hasDesc    = description != null && !description.isEmpty();
        boolean hasTech    = technology  != null && !technology.isEmpty();
        boolean hasLabel   = hasDesc || hasTech;

        int techFontSize = (int)(fontSize * 0.75);
        int descLineH    = (int)(fontSize  * 1.2);
        int techLineH    = (int)(techFontSize * 1.2);
        int maxLabelWidth = fontSize * 8;

        List<String> descLines = hasDesc
            ? Shapes.wrapText(description, maxLabelWidth, fontSize) : new ArrayList<>();
        List<String> techLines = hasTech
            ? Shapes.wrapText("[" + technology + "]", maxLabelWidth, techFontSize) : new ArrayList<>();

        int labelW = 0, labelH = 0;
        if (hasLabel) {
            int maxLineW = 0;
            for (String line : descLines)
                maxLineW = Math.max(maxLineW, (int) Math.ceil(TextMetrics.width(line, fontSize, false)));
            for (String line : techLines)
                maxLineW = Math.max(maxLineW, (int) Math.ceil(TextMetrics.width(line, techFontSize, false)));
            labelW = maxLineW + 10;
            labelH = descLines.size() * descLineH + techLines.size() * techLineH + 8;
        }

        return new LabelInfo(pathD, dashAttr, color, thickness,
                             pathPoints,
                             hasLabel, labelX, labelY,
                             descLines, techLines,
                             fontSize, techFontSize, descLineH, techLineH,
                             labelW, labelH);
    }

    /**
     * Inserts corner waypoints so the polyline routes around foreign boxes instead
     * of cutting through them. Greedy: for each piercing segment, detour via the
     * cheapest inflated-box corner (or pair of adjacent corners) that clears it.
     */
    private static void insertDetours(List<double[]> pts, List<double[]> avoid, double CLEAR) {
        for (int guard = 0; guard < 12; guard++) {
            boolean changed = false;
            outer:
            for (int i = 0; i < pts.size() - 1; i++) {
                double[] a = pts.get(i), b = pts.get(i + 1);
                for (double[] r : avoid) {
                    double rx = r[0] - CLEAR, ry = r[1] - CLEAR;
                    double rw = r[2] + 2 * CLEAR, rh = r[3] + 2 * CLEAR;
                    if (!segmentIntersectsRect(a, b, rx, ry, rw, rh)) continue;

                    double[][] corners = {
                        {rx, ry}, {rx + rw, ry}, {rx + rw, ry + rh}, {rx, ry + rh}
                    };
                    double straight = Math.hypot(b[0] - a[0], b[1] - a[1]);

                    // Single corner that clears the box
                    double bestExtra = Double.MAX_VALUE;
                    double[] best = null;
                    for (double[] c : corners) {
                        if (segmentIntersectsRect(a, c, rx, ry, rw, rh)
                                || segmentIntersectsRect(c, b, rx, ry, rw, rh)) continue;
                        double extra = Math.hypot(c[0] - a[0], c[1] - a[1])
                                     + Math.hypot(b[0] - c[0], b[1] - c[1]) - straight;
                        if (extra < bestExtra) { bestExtra = extra; best = c; }
                    }
                    if (best != null) {
                        pts.add(i + 1, best);
                        changed = true;
                        break outer;
                    }

                    // Pair of adjacent corners (around one side of the box)
                    double[] bestC1 = null, bestC2 = null;
                    bestExtra = Double.MAX_VALUE;
                    for (int s = 0; s < 4; s++) {
                        double[] c1 = corners[s], c2 = corners[(s + 1) % 4];
                        for (int order = 0; order < 2; order++) {
                            double[] f = order == 0 ? c1 : c2;
                            double[] g = order == 0 ? c2 : c1;
                            if (segmentIntersectsRect(a, f, rx, ry, rw, rh)
                                    || segmentIntersectsRect(g, b, rx, ry, rw, rh)) continue;
                            double extra = Math.hypot(f[0] - a[0], f[1] - a[1])
                                         + Math.hypot(g[0] - f[0], g[1] - f[1])
                                         + Math.hypot(b[0] - g[0], b[1] - g[1]) - straight;
                            if (extra < bestExtra) { bestExtra = extra; bestC1 = f; bestC2 = g; }
                        }
                    }
                    if (bestC1 != null) {
                        pts.add(i + 1, bestC2);
                        pts.add(i + 1, bestC1);
                        changed = true;
                        break outer;
                    }
                }
            }
            if (!changed) return;
        }
    }

    /** Liang–Barsky: true when the segment passes through the rect's interior
     *  (slightly shrunk so edge-grazing detour segments don't count). */
    private static boolean segmentIntersectsRect(double[] a, double[] b,
                                                 double rx, double ry, double rw, double rh) {
        double e = 0.5;
        double minX = rx + e, minY = ry + e, maxX = rx + rw - e, maxY = ry + rh - e;
        double dx = b[0] - a[0], dy = b[1] - a[1];
        double t0 = 0, t1 = 1;
        double[] p = {-dx, dx, -dy, dy};
        double[] q = {a[0] - minX, maxX - a[0], a[1] - minY, maxY - a[1]};
        for (int i = 0; i < 4; i++) {
            if (Math.abs(p[i]) < 1e-12) {
                if (q[i] < 0) return false;
            } else {
                double t = q[i] / p[i];
                if (p[i] < 0) t0 = Math.max(t0, t);
                else          t1 = Math.min(t1, t);
                if (t0 > t1) return false;
            }
        }
        return t1 - t0 > 1e-6;
    }

    /** Render only the path line (arrow) for one relationship. */
    static String renderPath(LabelInfo li) {
        return String.format(
            "<path d=\"%s\" fill=\"none\" stroke=\"%s\" stroke-width=\"%d\"%s " +
            "stroke-linecap=\"round\" stroke-linejoin=\"round\" marker-end=\"url(#arrow)\"/>\n",
            li.pathD, li.color, li.thickness, li.dashAttr);
    }

    /**
     * Render only the label (background rect + text) for one relationship.
     * Called in a separate pass after all paths so labels always appear on top of lines.
     */
    static String renderLabel(LabelInfo li) {
        if (!li.hasLabel) return "";
        StringBuilder sb = new StringBuilder();
        // One background rect covers the whole label block so that when two labels overlap
        // the later-drawn one fully blanks the earlier one rather than leaving scrambled text.
        double blockTop = li.labelY - li.labelH / 2.0;
        sb.append(String.format(
            "<rect x=\"%.1f\" y=\"%.1f\" width=\"%d\" height=\"%d\" rx=\"6\" fill=\"#ffffff\"/>\n",
            li.labelX - li.labelW / 2.0, blockTop, li.labelW, li.labelH));

        double textY = blockTop + li.descLineH;
        for (String line : li.descLines) {
            sb.append(String.format(
                "<text x=\"%.1f\" y=\"%.1f\" text-anchor=\"middle\" font-family=\"%s\" font-size=\"%d\" fill=\"%s\">%s</text>\n",
                li.labelX, textY, Shapes.DEFAULT_FONT, li.fontSize, li.color, Shapes.htmlEscape(line)));
            textY += li.descLineH;
        }
        for (String line : li.techLines) {
            sb.append(String.format(
                "<text x=\"%.1f\" y=\"%.1f\" text-anchor=\"middle\" font-family=\"%s\" font-size=\"%d\" " +
                "font-style=\"italic\" opacity=\"0.75\" fill=\"%s\">%s</text>\n",
                li.labelX, textY, Shapes.DEFAULT_FONT, li.techFontSize, li.color, Shapes.htmlEscape(line)));
            textY += li.techLineH;
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /** Point at the given length fraction along a polyline. */
    private static double[] pathPointAtFraction(List<double[]> pts, double fraction) {
        double totalLen = 0;
        for (int i = 0; i < pts.size() - 1; i++) {
            double dx = pts.get(i + 1)[0] - pts.get(i)[0];
            double dy = pts.get(i + 1)[1] - pts.get(i)[1];
            totalLen += Math.sqrt(dx * dx + dy * dy);
        }

        double target = totalLen * fraction;
        double cum = 0;
        for (int i = 0; i < pts.size() - 1; i++) {
            double dx = pts.get(i + 1)[0] - pts.get(i)[0];
            double dy = pts.get(i + 1)[1] - pts.get(i)[1];
            double seg = Math.sqrt(dx * dx + dy * dy);
            if (cum + seg >= target) {
                double t = seg > 0 ? (target - cum) / seg : 0;
                return new double[]{pts.get(i)[0] + t * dx, pts.get(i)[1] + t * dy};
            }
            cum += seg;
        }
        return pts.get(pts.size() - 1);
    }

    /** Point on a quadratic Bézier (p1, control cp, p2) at parameter t. */
    private static double[] quadPoint(double[] p1, double cpX, double cpY, double[] p2, double t) {
        double u = 1 - t;
        return new double[]{
            u*u*p1[0] + 2*u*t*cpX + t*t*p2[0],
            u*u*p1[1] + 2*u*t*cpY + t*t*p2[1]};
    }

    /** Point on a cubic Bézier (p0, control c1, control c2, p3) at parameter t. */
    private static double[] cubicPoint(double p0x, double p0y, double c1x, double c1y,
                                        double c2x, double c2y, double p3x, double p3y, double t) {
        double u = 1 - t;
        double x = u*u*u*p0x + 3*u*u*t*c1x + 3*u*t*t*c2x + t*t*t*p3x;
        double y = u*u*u*p0y + 3*u*u*t*c1y + 3*u*t*t*c2y + t*t*t*p3y;
        return new double[]{x, y};
    }

    private static double[] clipToRect(double x1, double y1, double x2, double y2,
                                        double rx, double ry, double rw, double rh) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double bestT = Double.MAX_VALUE;

        if (dx != 0) {
            double t = (rx - x1) / dx;
            if (t >= 0 && t <= 1) {
                double y = y1 + t * dy;
                if (y >= ry && y <= ry + rh) bestT = Math.min(bestT, t);
            }
            t = (rx + rw - x1) / dx;
            if (t >= 0 && t <= 1) {
                double y = y1 + t * dy;
                if (y >= ry && y <= ry + rh) bestT = Math.min(bestT, t);
            }
        }
        if (dy != 0) {
            double t = (ry - y1) / dy;
            if (t >= 0 && t <= 1) {
                double x = x1 + t * dx;
                if (x >= rx && x <= rx + rw) bestT = Math.min(bestT, t);
            }
            t = (ry + rh - y1) / dy;
            if (t >= 0 && t <= 1) {
                double x = x1 + t * dx;
                if (x >= rx && x <= rx + rw) bestT = Math.min(bestT, t);
            }
        }

        if (bestT < Double.MAX_VALUE) {
            return new double[]{x1 + bestT * dx, y1 + bestT * dy};
        }
        return new double[]{x1, y1};
    }
}
