package com.structurizr.renderer.svg;

import com.structurizr.model.Relationship;
import com.structurizr.view.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class Connectors {

    static final String DEFS_BLOCK =
        "<defs>" +
        "<marker id=\"arrow\" orient=\"auto\" overflow=\"visible\" markerUnits=\"userSpaceOnUse\">" +
        "<path transform=\"rotate(180)\" d=\"M 20 -10 0 0 20 10 Z\" stroke=\"#444444\" fill=\"#444444\"/>" +
        "</marker>" +
        "</defs>\n";

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

    /**
     * Compute path geometry and label position for one relationship.
     * srcRect/dstRect are {x, y, w, h} of the source/destination — either the
     * element's box, or the boundary rectangle when the endpoint is rendered as
     * a boundary (e.g. deployment nodes).
     */
    static LabelInfo computeLayout(RelationshipView rv, double[] srcRect, double[] dstRect,
                                    RelationshipStyle style) {
        Relationship rel = rv.getRelationship();

        String rawColor  = style.getColor();
        String color     = (rawColor == null || "#707070".equalsIgnoreCase(rawColor)) ? "#444444" : rawColor;
        int    thickness = style.getThickness() != null ? style.getThickness() : 2;
        boolean dashed   = style.getDashed()    != null ? style.getDashed()    : true;
        Routing routing  = style.getRouting()   != null ? style.getRouting()   : Routing.Direct;
        // Default to 25% (near source) rather than 50% (midpoint).
        // Labels near the source end are above the region where edges typically cross,
        // and the repulsion+crossing-avoidance pass then fine-tunes positions.
        int position     = style.getPosition()  != null ? style.getPosition()  : 25;
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
        } else {
            double x1 = srcRect[0] + srcRect[2] / 2.0;
            double y1 = srcRect[1] + srcRect[3] / 2.0;
            double x2 = dstRect[0] + dstRect[2] / 2.0;
            double y2 = dstRect[1] + dstRect[3] / 2.0;

            double[] p1 = clipToRect(x1, y1, x2, y2, srcRect[0], srcRect[1], srcRect[2], srcRect[3]);
            double[] p2 = clipToRect(x2, y2, x1, y1, dstRect[0], dstRect[1], dstRect[2], dstRect[3]);

            // Build the ordered list of path points (clipped endpoints + waypoints) once,
            // for both path-string construction and crossing detection.
            pathPoints.add(p1);
            for (Vertex v : routingVertices) pathPoints.add(new double[]{v.getX(), v.getY()});
            pathPoints.add(p2);

            if (!routingVertices.isEmpty()) {
                StringBuilder path = new StringBuilder();
                path.append(String.format("M %.1f %.1f", p1[0], p1[1]));
                for (Vertex v : routingVertices) {
                    path.append(String.format(" L %d %d", v.getX(), v.getY()));
                }
                path.append(String.format(" L %.1f %.1f", p2[0], p2[1]));
                pathD = path.toString();
                double[] lpos = polylinePointAtFraction(p1, routingVertices, p2, position / 100.0);
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
                labelX = cpX;
                labelY = cpY - 6;
            } else {
                pathD  = String.format("M %.1f %.1f L %.1f %.1f", p1[0], p1[1], p2[0], p2[1]);
                double t = position / 100.0;
                labelX = p1[0] + t * (p2[0] - p1[0]);
                labelY = p1[1] + t * (p2[1] - p1[1]) - 6;
            }
        }

        // Prefer the view-level description (set on dynamic-view steps) over the model-level
        // value, so step labels like "Submits credentials to" are shown correctly.
        String rvDesc = rv.getDescription();
        String description = (rvDesc != null && !rvDesc.isEmpty()) ? rvDesc : rel.getDescription();
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

    /** Render only the path line (arrow) for one relationship. */
    static String renderPath(LabelInfo li) {
        return String.format(
            "<path d=\"%s\" fill=\"none\" stroke=\"%s\" stroke-width=\"%d\"%s marker-end=\"url(#arrow)\"/>\n",
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
            "<rect x=\"%.1f\" y=\"%.1f\" width=\"%d\" height=\"%d\" rx=\"2\" fill=\"#ffffff\"/>\n",
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
                "<text x=\"%.1f\" y=\"%.1f\" text-anchor=\"middle\" font-family=\"%s\" font-size=\"%d\" font-style=\"italic\" fill=\"%s\">%s</text>\n",
                li.labelX, textY, Shapes.DEFAULT_FONT, li.techFontSize, li.color, Shapes.htmlEscape(line)));
            textY += li.techLineH;
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static double[] polylinePointAtFraction(double[] p1, Collection<Vertex> vertices,
                                                      double[] p2, double fraction) {
        List<double[]> pts = new ArrayList<>();
        pts.add(p1);
        for (Vertex v : vertices) pts.add(new double[]{v.getX(), v.getY()});
        pts.add(p2);

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
