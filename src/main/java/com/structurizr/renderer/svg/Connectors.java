package com.structurizr.renderer.svg;

import com.structurizr.model.Relationship;
import com.structurizr.renderer.layout.ElkLayoutStrategy;
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

    static String render(RelationshipView rv, ElementView srcEv, ElementView dstEv,
                         RelationshipStyle style, ModelView view) {
        Relationship rel = rv.getRelationship();

        // Look up actual element dimensions from styles
        ElementStyle srcStyle = view.getViewSet().getConfiguration().getStyles().findElementStyle(srcEv.getElement());
        ElementStyle dstStyle = view.getViewSet().getConfiguration().getStyles().findElementStyle(dstEv.getElement());

        int[] srcDims = ElkLayoutStrategy.defaultDimensions(srcEv.getElement(), srcStyle);
        int[] dstDims = ElkLayoutStrategy.defaultDimensions(dstEv.getElement(), dstStyle);
        int srcW = srcDims[0], srcH = srcDims[1];
        int dstW = dstDims[0], dstH = dstDims[1];

        double x1 = srcEv.getX() + srcW / 2.0;
        double y1 = srcEv.getY() + srcH / 2.0;
        double x2 = dstEv.getX() + dstW / 2.0;
        double y2 = dstEv.getY() + dstH / 2.0;

        double[] p1 = clipToRect(x1, y1, x2, y2, srcEv.getX(), srcEv.getY(), srcW, srcH);
        double[] p2 = clipToRect(x2, y2, x1, y1, dstEv.getX(), dstEv.getY(), dstW, dstH);

        // Structurizr's library default is #707070; replace that with our reference-matching #444444
        String rawColor  = style.getColor();
        String color     = (rawColor == null || "#707070".equalsIgnoreCase(rawColor)) ? "#444444" : rawColor;
        int    thickness = style.getThickness() != null ? style.getThickness() : 2;
        boolean dashed   = style.getDashed()    != null ? style.getDashed()    : true;
        Routing routing  = style.getRouting()   != null ? style.getRouting()   : Routing.Direct;
        int position     = style.getPosition()  != null ? style.getPosition()  : 50;
        int fontSize     = style.getFontSize()  != null ? style.getFontSize()  : 24;

        String dashAttr = dashed ? " stroke-dasharray=\"8 8\"" : "";

        Collection<Vertex> routingVertices = rv.getVertices();
        String pathD;
        double labelX, labelY;

        if (!routingVertices.isEmpty()) {
            // ELK stores the full edge path: [port_exit, ...bends..., port_entry].
            // Use those directly as the path — don't re-apply clipToRect, which would
            // misalign with ELK's horizontal port exits and produce a jog at each end.
            List<Vertex> vList = new ArrayList<>(routingVertices);
            Vertex first = vList.get(0);
            Vertex last  = vList.get(vList.size() - 1);
            StringBuilder path = new StringBuilder();
            path.append(String.format("M %d %d", first.getX(), first.getY()));
            for (int vi = 1; vi < vList.size(); vi++) {
                path.append(String.format(" L %d %d", vList.get(vi).getX(), vList.get(vi).getY()));
            }
            pathD = path.toString();
            double[] fp = {first.getX(), first.getY()};
            double[] lp = {last.getX(),  last.getY()};
            Collection<Vertex> midVerts = vList.subList(1, vList.size() - 1);
            double[] lpos = polylinePointAtFraction(fp, midVerts, lp, position / 100.0);
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

        StringBuilder sb = new StringBuilder();

        sb.append(String.format(
            "<g>\n<path d=\"%s\" fill=\"none\" stroke=\"%s\" stroke-width=\"%d\"%s marker-end=\"url(#arrow)\"/>\n",
            pathD, color, thickness, dashAttr
        ));

        String description = rel.getDescription();
        String technology  = rel.getTechnology();
        boolean hasDesc    = description != null && !description.isEmpty();
        boolean hasTech    = technology  != null && !technology.isEmpty();

        if (hasDesc || hasTech) {
            int techFontSize = (int)(fontSize * 0.75);
            int descLineH    = (int)(fontSize  * 1.2);
            int techLineH    = (int)(techFontSize * 1.2);
            // Wrap at ~8x font-size width (roughly matches reference proportions)
            int maxLabelWidth = fontSize * 8;

            List<String> descLines = hasDesc
                ? Shapes.wrapText(description, maxLabelWidth, fontSize) : new ArrayList<>();
            List<String> techLines = hasTech
                ? Shapes.wrapText("[" + technology + "]", maxLabelWidth, techFontSize) : new ArrayList<>();

            int totalH = descLines.size() * descLineH + techLines.size() * techLineH + 8;
            int rectW  = maxLabelWidth + 12;

            // Background rect, centered horizontally on label midpoint
            sb.append(String.format(
                "<rect x=\"%.1f\" y=\"%.1f\" width=\"%d\" height=\"%d\" rx=\"3\" fill=\"#ffffff\"/>\n",
                labelX - rectW / 2.0, labelY - totalH / 2.0, rectW, totalH
            ));

            double textY = labelY - totalH / 2.0 + descLineH;
            for (String line : descLines) {
                sb.append(String.format(
                    "<text x=\"%.1f\" y=\"%.1f\" text-anchor=\"middle\" font-family=\"%s\" font-size=\"%d\" fill=\"%s\">%s</text>\n",
                    labelX, textY, Shapes.DEFAULT_FONT, fontSize, color, Shapes.htmlEscape(line)));
                textY += descLineH;
            }
            for (String line : techLines) {
                sb.append(String.format(
                    "<text x=\"%.1f\" y=\"%.1f\" text-anchor=\"middle\" font-family=\"%s\" font-size=\"%d\" font-style=\"italic\" fill=\"%s\">%s</text>\n",
                    labelX, textY, Shapes.DEFAULT_FONT, techFontSize, color, Shapes.htmlEscape(line)));
                textY += techLineH;
            }
        }

        sb.append("</g>\n");
        return sb.toString();
    }

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
