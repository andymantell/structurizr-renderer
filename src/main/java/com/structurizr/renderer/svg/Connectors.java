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

    /** Compute path geometry and label position for one relationship. */
    static LabelInfo computeLayout(RelationshipView rv, ElementView srcEv, ElementView dstEv,
                                    RelationshipStyle style, ModelView view) {
        Relationship rel = rv.getRelationship();

        ElementStyle srcStyle = view.getViewSet().getConfiguration().getStyles().findElementStyle(srcEv.getElement());
        ElementStyle dstStyle = view.getViewSet().getConfiguration().getStyles().findElementStyle(dstEv.getElement());

        int[] srcDims = Shapes.defaultDimensions(srcEv.getElement(), srcStyle);
        int[] dstDims = Shapes.defaultDimensions(dstEv.getElement(), dstStyle);

        double x1 = srcEv.getX() + srcDims[0] / 2.0;
        double y1 = srcEv.getY() + srcDims[1] / 2.0;
        double x2 = dstEv.getX() + dstDims[0] / 2.0;
        double y2 = dstEv.getY() + dstDims[1] / 2.0;

        double[] p1 = clipToRect(x1, y1, x2, y2, srcEv.getX(), srcEv.getY(), srcDims[0], srcDims[1]);
        double[] p2 = clipToRect(x2, y2, x1, y1, dstEv.getX(), dstEv.getY(), dstDims[0], dstDims[1]);

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

        // Build the ordered list of path points (clipped endpoints + waypoints) once,
        // for both path-string construction and crossing detection.
        List<double[]> pathPoints = new ArrayList<>();
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

        String description = rel.getDescription();
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
                maxLineW = Math.max(maxLineW, (int)(line.length() * fontSize * 0.55));
            for (String line : techLines)
                maxLineW = Math.max(maxLineW, (int)(line.length() * techFontSize * 0.55));
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

    /** Render one relationship to SVG using (potentially adjusted) label position. */
    static String renderLayout(LabelInfo li) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(
            "<g>\n<path d=\"%s\" fill=\"none\" stroke=\"%s\" stroke-width=\"%d\"%s marker-end=\"url(#arrow)\"/>\n",
            li.pathD, li.color, li.thickness, li.dashAttr));

        if (li.hasLabel) {
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
        }

        sb.append("</g>\n");
        return sb.toString();
    }

    /** Convenience wrapper: compute then immediately render (no overlap correction). */
    static String render(RelationshipView rv, ElementView srcEv, ElementView dstEv,
                         RelationshipStyle style, ModelView view) {
        return renderLayout(computeLayout(rv, srcEv, dstEv, style, view));
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

    private static double[] clipToRect(double x1, double y1, double x2, double y2,
                                        int rx, int ry, int rw, int rh) {
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
