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

    static String render(RelationshipView rv, ElementView srcEv, ElementView dstEv,
                         RelationshipStyle style, ModelView view) {
        Relationship rel = rv.getRelationship();

        // Look up actual element dimensions from styles
        ElementStyle srcStyle = view.getViewSet().getConfiguration().getStyles().findElementStyle(srcEv.getElement());
        ElementStyle dstStyle = view.getViewSet().getConfiguration().getStyles().findElementStyle(dstEv.getElement());

        int srcW = srcStyle.getWidth() != null ? srcStyle.getWidth() : 450;
        int srcH = srcStyle.getHeight() != null ? srcStyle.getHeight() : 300;
        int dstW = dstStyle.getWidth() != null ? dstStyle.getWidth() : 450;
        int dstH = dstStyle.getHeight() != null ? dstStyle.getHeight() : 300;

        double x1 = srcEv.getX() + srcW / 2.0;
        double y1 = srcEv.getY() + srcH / 2.0;
        double x2 = dstEv.getX() + dstW / 2.0;
        double y2 = dstEv.getY() + dstH / 2.0;

        double[] p1 = clipToRect(x1, y1, x2, y2, srcEv.getX(), srcEv.getY(), srcW, srcH);
        double[] p2 = clipToRect(x2, y2, x1, y1, dstEv.getX(), dstEv.getY(), dstW, dstH);

        String color     = style.getColor()     != null ? style.getColor()     : "#444444";
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
            StringBuilder path = new StringBuilder();
            path.append(String.format("M %.1f %.1f", p1[0], p1[1]));
            for (Vertex v : routingVertices) {
                path.append(String.format(" L %d %d", v.getX(), v.getY()));
            }
            path.append(String.format(" L %.1f %.1f", p2[0], p2[1]));
            pathD = path.toString();
            double[] lp = polylinePointAtFraction(p1, routingVertices, p2, position / 100.0);
            labelX = lp[0];
            labelY = lp[1] - 6;
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
            // Estimate label block height for white background rect
            int descH = hasDesc ? fontSize : 0;
            int techH = hasTech ? (int)(fontSize * 0.75) + 4 : 0;
            int totalLabelH = descH + techH + 8;
            int estWidth = 120;

            sb.append(String.format(
                "<rect x=\"%.1f\" y=\"%.1f\" width=\"%d\" height=\"%d\" rx=\"3\" fill=\"#ffffff\"/>\n",
                labelX - estWidth / 2.0, labelY - fontSize - 2, estWidth, totalLabelH
            ));
        }

        if (hasDesc) {
            sb.append(String.format(
                "<text x=\"%.1f\" y=\"%.1f\" text-anchor=\"middle\" font-family=\"%s\" font-size=\"%d\" fill=\"%s\">%s</text>\n",
                labelX, labelY, Shapes.DEFAULT_FONT, fontSize, color,
                Shapes.htmlEscape(description)
            ));
        }

        if (hasTech) {
            double techY = labelY + (hasDesc ? fontSize : 0) + (int)(fontSize * 0.85);
            sb.append(String.format(
                "<text x=\"%.1f\" y=\"%.1f\" text-anchor=\"middle\" font-family=\"%s\" font-size=\"%d\" font-style=\"italic\" fill=\"%s\">[%s]</text>\n",
                labelX, techY, Shapes.DEFAULT_FONT, (int)(fontSize * 0.75), color,
                Shapes.htmlEscape(technology)
            ));
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
