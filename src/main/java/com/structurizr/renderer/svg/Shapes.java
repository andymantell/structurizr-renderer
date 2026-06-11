package com.structurizr.renderer.svg;

import com.structurizr.model.*;
import com.structurizr.view.ElementStyle;
import com.structurizr.view.ModelView;
import com.structurizr.view.Shape;

import java.util.ArrayList;
import java.util.List;

public class Shapes {

    static final String DEFAULT_FONT = BundledFonts.FAMILY + ", Helvetica, Arial, sans-serif";

    /** Returns [width, height] with shape-aware defaults matching the reference renderer. */
    static int[] defaultDimensions(Element element, ElementStyle style) {
        int w = style.getWidth()  != null ? style.getWidth()  : 0;
        int h = style.getHeight() != null ? style.getHeight() : 0;
        if (w > 0 && h > 0) return new int[]{w, h};
        Shape shape = style.getShape() != null ? style.getShape() : Shape.Box;
        if (shape == Shape.Person || shape == Shape.Robot) {
            return new int[]{w > 0 ? w : 400, h > 0 ? h : 400};
        }
        return new int[]{w > 0 ? w : 450, h > 0 ? h : 300};
    }

    /**
     * Element rect [x, y, w, h]: the default dimensions, with the height grown when
     * the wrapped text block wouldn't fit the shape's text area. Growth is centred
     * on the laid-out box so the element keeps the centre the layout gave it.
     */
    public static int[] elementRect(ModelView view, Element element, ElementStyle style, int evX, int evY) {
        int[] dims = defaultDimensions(element, style);
        int w = dims[0], h = dims[1];
        Shape shape = style.getShape() != null ? style.getShape() : Shape.Box;

        int textW = shape == Shape.Pipe ? w - 2 * Math.min(60, w / 5) : w;
        int textH = layoutText(view, element, style, textW).height();
        int pad   = 30; // breathing room above and below the text

        // Convert the required text-area height into an element height, accounting
        // for the parts of each shape that can't hold text (heads, tabs, bars, caps).
        int needed = switch (shape) {
            case Person   -> (int) Math.ceil(textH + pad + 0.4 * w);            // head circle occupies ~0.4w
            case Robot    -> (int) Math.ceil((textH + pad + 0.327 * w) / 0.94); // antenna + head occupy 0.06h + ~0.327w
            case Cylinder -> (int) Math.ceil((textH + pad) / 0.8);              // end ellipses occupy 2*(h/10)
            case Folder, WebBrowser -> (int) Math.ceil((textH + pad) / 0.88);   // tab / browser chrome
            case Window   -> (int) Math.ceil((textH + pad) / 0.9);              // title bar
            case Hexagon, Diamond, Ellipse -> (int) Math.ceil((textH + pad) * 1.25); // shape narrows at top/bottom
            case Circle   -> h; // radius is min(w,h)/2, so vertical growth adds no text room
            default       -> textH + pad
                + (style.getIcon() != null && !style.getIcon().isBlank() ? ICON_AREA : 0);
        };

        int grownH = Math.max(h, needed);
        return new int[]{evX, evY - (grownH - h) / 2, w, grownH};
    }

    static String render(ModelView view, Element element, ElementStyle style, int x, int y, int w, int h) {
        Shape shape = style.getShape() != null ? style.getShape() : Shape.Box;
        return switch (shape) {
            case Box          -> renderBox(view, element, style, x, y, w, h, 1);
            case RoundedBox   -> renderBox(view, element, style, x, y, w, h, 10);
            case Circle       -> renderCircle(view, element, style, x, y, w, h);
            case Ellipse      -> renderEllipse(view, element, style, x, y, w, h);
            case Hexagon      -> renderHexagon(view, element, style, x, y, w, h);
            case Diamond      -> renderDiamond(view, element, style, x, y, w, h);
            case Person       -> renderPerson(view, element, style, x, y, w, h);
            case Robot        -> renderRobot(view, element, style, x, y, w, h);
            case Cylinder     -> renderCylinder(view, element, style, x, y, w, h);
            case Pipe         -> renderPipe(view, element, style, x, y, w, h);
            case Component    -> renderComponent(view, element, style, x, y, w, h);
            case Folder       -> renderFolder(view, element, style, x, y, w, h);
            case WebBrowser   -> renderWebBrowser(view, element, style, x, y, w, h);
            case Window       -> renderWindow(view, element, style, x, y, w, h);
            case MobileDeviceLandscape -> renderMobileDevice(view, element, style, x, y, w, h, true);
            case MobileDevicePortrait  -> renderMobileDevice(view, element, style, x, y, w, h, false);
            default           -> renderBox(view, element, style, x, y, w, h, 0);
        };
    }

    // Vertical space reserved for an icon at the top of a box (icon + top/bottom padding)
    private static final int ICON_SIZE = 56;
    private static final int ICON_PAD  = 8;
    private static final int ICON_AREA = ICON_SIZE + ICON_PAD * 2;

    // -------------------------------------------------------------------------
    // Box / RoundedBox (rx=0 for Box, rx=15 for RoundedBox)
    // -------------------------------------------------------------------------
    private static String renderBox(ModelView view, Element element, ElementStyle style,
                                     int x, int y, int w, int h, int rx) {
        String bg     = bg(style);
        String stroke = stroke(style, bg);
        int    sw     = strokeWidth(style);

        StringBuilder sb = new StringBuilder();
        sb.append(openGroup(element));
        sb.append(String.format(
            "<rect x=\"%d\" y=\"%d\" width=\"%d\" height=\"%d\" rx=\"%d\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            x, y, w, h, rx, bg, stroke, sw));

        int textY = y;
        int textH = h;
        String iconUrl = style.getIcon();
        if (iconUrl != null && !iconUrl.isBlank()) {
            String dataUri = IconCache.toDataUri(iconUrl);
            if (dataUri != null) {
                int iconX = x + (w - ICON_SIZE) / 2;
                int iconY = y + ICON_PAD;
                sb.append(String.format(
                    "<image x=\"%d\" y=\"%d\" width=\"%d\" height=\"%d\" href=\"%s\" xlink:href=\"%s\"/>\n",
                    iconX, iconY, ICON_SIZE, ICON_SIZE, dataUri, dataUri));
                textY = y + ICON_AREA;
                textH = h - ICON_AREA;
            }
        }

        sb.append(renderBoxText(view, element, style, x, textY, w, textH));
        sb.append("</g>\n");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Person — matches reference: large rounded-rect body, head circle, leg lines, text in body
    // -------------------------------------------------------------------------
    private static String renderPerson(ModelView view, Element element, ElementStyle style,
                                        int x, int y, int w, int h) {
        String bg     = bg(style);
        String stroke = stroke(style, bg);
        int    sw     = strokeWidth(style);

        double headR   = w / 4.5;
        double headCx  = x + w / 2.0;
        double headCy  = y + headR;
        // Body starts where head circle center + 0.8r (slight overlap with head)
        double bodyTop = headCy + headR * 0.8;
        double bodyH   = (y + h) - bodyTop;
        double bodyRx  = Math.min(70, w * 0.175);
        // Leg lines: from 2/3 down body to element bottom
        double legStartY = bodyTop + bodyH * (2.0 / 3.0);

        StringBuilder sb = new StringBuilder();
        sb.append(openGroup(element));
        // Head
        sb.append(String.format("<circle cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            headCx, headCy, headR, bg, stroke, sw));
        // Body
        sb.append(String.format("<rect x=\"%d\" y=\"%.1f\" width=\"%d\" height=\"%.1f\" rx=\"%.1f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            x, bodyTop, w, bodyH, bodyRx, bg, stroke, sw));
        // Legs (two vertical lines at 20%/80% x, overlaid on lower body; thin stroke like reference)
        sb.append(String.format("<line x1=\"%.1f\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f\" stroke=\"%s\" stroke-width=\"1\"/>\n",
            x + w * 0.2, legStartY, x + w * 0.2, (double)(y + h), stroke));
        sb.append(String.format("<line x1=\"%.1f\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f\" stroke=\"%s\" stroke-width=\"1\"/>\n",
            x + w * 0.8, legStartY, x + w * 0.8, (double)(y + h), stroke));
        // Text centred in body area
        sb.append(renderBoxText(view, element, style, x, (int) bodyTop, w, (int) bodyH));
        sb.append("</g>\n");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Robot (square head, same stick figure as Person)
    // -------------------------------------------------------------------------
    private static String renderRobot(ModelView view, Element element, ElementStyle style,
                                       int x, int y, int w, int h) {
        String bg     = bg(style);
        String stroke = stroke(style, bg);
        int    sw     = strokeWidth(style);
        String color  = color(style);

        // Same layout as Person — full-width body carries the text — but with a
        // square antenna'd head instead of a circle.
        double headW = w / 2.2;
        double headH = headW * 0.8;
        double headX = x + (w - headW) / 2.0;
        double headY = y + h * 0.06;
        double antX  = x + w / 2.0;
        double antTopY = y + sw * 2.0;

        double bodyTop = headY + headH * 0.9; // slight overlap with head
        double bodyH   = (y + h) - bodyTop;
        double bodyRx  = Math.min(70, w * 0.175);
        double legStartY = bodyTop + bodyH * (2.0 / 3.0);

        double eyeR = headH * 0.12;
        double eyeY = headY + headH * 0.45;

        StringBuilder sb = new StringBuilder();
        sb.append(openGroup(element));
        // Antenna
        sb.append(String.format("<line x1=\"%.1f\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            antX, headY, antX, antTopY, stroke, sw));
        sb.append(String.format("<circle cx=\"%.1f\" cy=\"%.1f\" r=\"%d\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            antX, antTopY, sw * 2, bg, stroke, sw));
        // Head
        sb.append(String.format("<rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"8\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            headX, headY, headW, headH, bg, stroke, sw));
        // Eyes
        sb.append(String.format("<circle cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"%s\"/>\n", headX + headW * 0.3, eyeY, eyeR, color));
        sb.append(String.format("<circle cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"%s\"/>\n", headX + headW * 0.7, eyeY, eyeR, color));
        // Body
        sb.append(String.format("<rect x=\"%d\" y=\"%.1f\" width=\"%d\" height=\"%.1f\" rx=\"%.1f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            x, bodyTop, w, bodyH, bodyRx, bg, stroke, sw));
        // Legs (two vertical lines overlaid on the lower body, like Person)
        sb.append(String.format("<line x1=\"%.1f\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f\" stroke=\"%s\" stroke-width=\"1\"/>\n",
            x + w * 0.2, legStartY, x + w * 0.2, (double)(y + h), stroke));
        sb.append(String.format("<line x1=\"%.1f\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f\" stroke=\"%s\" stroke-width=\"1\"/>\n",
            x + w * 0.8, legStartY, x + w * 0.8, (double)(y + h), stroke));
        // Text centred in body area
        sb.append(renderBoxText(view, element, style, x, (int) bodyTop, w, (int) bodyH));
        sb.append("</g>\n");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Cylinder — path format matches reference SVG exactly
    // -------------------------------------------------------------------------
    private static String renderCylinder(ModelView view, Element element, ElementStyle style,
                                          int x, int y, int w, int h) {
        String bg     = bg(style);
        String stroke = stroke(style, bg);
        int    sw     = strokeWidth(style);

        int rx    = w / 2;
        int ry    = Math.max(4, h / 10);
        int bodyH = h - 2 * ry;

        // Single closed path: full top ellipse (two arcs) + sides + front bottom arc
        StringBuilder sb = new StringBuilder();
        sb.append(openGroup(element));
        sb.append(String.format(
            "<path d=\"M %d,%d a %d,%d 0,0,0 %d 0 a %d,%d 0,0,0 -%d 0 l 0,%d a %d,%d 0,0,0 %d 0 l 0,-%d\" " +
            "fill=\"%s\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            x, y + ry,
            rx, ry, w,
            rx, ry, w,
            bodyH,
            rx, ry, w,
            bodyH,
            bg, stroke, sw));
        sb.append(renderBoxText(view, element, style, x, y + ry, w, bodyH));
        sb.append("</g>\n");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Pipe (horizontal cylinder)
    // -------------------------------------------------------------------------
    private static String renderPipe(ModelView view, Element element, ElementStyle style,
                                      int x, int y, int w, int h) {
        String bg     = bg(style);
        String stroke = stroke(style, bg);
        int    sw     = strokeWidth(style);

        int ry = h / 2;
        int rx = Math.min(60, w / 5);

        StringBuilder sb = new StringBuilder();
        sb.append(openGroup(element));
        // Body as a single closed path so the fill includes the bulging right end cap;
        // the concave left edge is covered by the full end ellipse drawn on top.
        sb.append(String.format(
            "<path d=\"M %d %d l %d 0 a %d %d 0 0 1 0 %d l -%d 0 a %d %d 0 0 0 0 -%d\" " +
            "fill=\"%s\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            x + rx, y, w - 2 * rx, rx, ry, h, w - 2 * rx, rx, ry, h, bg, stroke, sw));
        sb.append(String.format("<ellipse cx=\"%d\" cy=\"%d\" rx=\"%d\" ry=\"%d\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            x + rx, y + ry, rx, ry, shadeColor(bg, 15), stroke, sw));
        sb.append(renderBoxText(view, element, style, x + rx, y, w - 2 * rx, h));
        sb.append("</g>\n");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Hexagon
    // -------------------------------------------------------------------------
    private static String renderHexagon(ModelView view, Element element, ElementStyle style,
                                         int x, int y, int w, int h) {
        String bg     = bg(style);
        String stroke = stroke(style, bg);
        int    sw     = strokeWidth(style);

        double cx = x + w / 2.0, cy = y + h / 2.0;
        double hw = w / 2.0,     hh = h / 2.0;

        String pts = String.format("%.1f,%.1f %.1f,%.1f %.1f,%.1f %.1f,%.1f %.1f,%.1f %.1f,%.1f",
            cx - hw, cy, cx - hw * 0.5, cy - hh, cx + hw * 0.5, cy - hh,
            cx + hw, cy, cx + hw * 0.5, cy + hh, cx - hw * 0.5, cy + hh);

        StringBuilder sb = new StringBuilder();
        sb.append(openGroup(element));
        sb.append(String.format("<polygon points=\"%s\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%d\"/>\n", pts, bg, stroke, sw));
        sb.append(renderBoxText(view, element, style, x, y, w, h));
        sb.append("</g>\n");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Diamond
    // -------------------------------------------------------------------------
    private static String renderDiamond(ModelView view, Element element, ElementStyle style,
                                         int x, int y, int w, int h) {
        String bg     = bg(style);
        String stroke = stroke(style, bg);
        int    sw     = strokeWidth(style);

        String pts = String.format("%d,%d %d,%d %d,%d %d,%d",
            x + w / 2, y, x + w, y + h / 2, x + w / 2, y + h, x, y + h / 2);

        StringBuilder sb = new StringBuilder();
        sb.append(openGroup(element));
        sb.append(String.format("<polygon points=\"%s\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%d\"/>\n", pts, bg, stroke, sw));
        sb.append(renderBoxText(view, element, style, x, y, w, h));
        sb.append("</g>\n");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Circle
    // -------------------------------------------------------------------------
    private static String renderCircle(ModelView view, Element element, ElementStyle style,
                                        int x, int y, int w, int h) {
        String bg     = bg(style);
        String stroke = stroke(style, bg);
        int    sw     = strokeWidth(style);

        int r = Math.min(w, h) / 2;

        StringBuilder sb = new StringBuilder();
        sb.append(openGroup(element));
        sb.append(String.format("<circle cx=\"%d\" cy=\"%d\" r=\"%d\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            x + w / 2, y + h / 2, r, bg, stroke, sw));
        sb.append(renderBoxText(view, element, style, x, y, w, h));
        sb.append("</g>\n");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Ellipse
    // -------------------------------------------------------------------------
    private static String renderEllipse(ModelView view, Element element, ElementStyle style,
                                         int x, int y, int w, int h) {
        String bg     = bg(style);
        String stroke = stroke(style, bg);
        int    sw     = strokeWidth(style);

        StringBuilder sb = new StringBuilder();
        sb.append(openGroup(element));
        sb.append(String.format("<ellipse cx=\"%d\" cy=\"%d\" rx=\"%d\" ry=\"%d\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            x + w / 2, y + h / 2, w / 2, h / 2, bg, stroke, sw));
        sb.append(renderBoxText(view, element, style, x, y, w, h));
        sb.append("</g>\n");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Component (box with two notch plugs on the left)
    // -------------------------------------------------------------------------
    private static String renderComponent(ModelView view, Element element, ElementStyle style,
                                           int x, int y, int w, int h) {
        String bg     = bg(style);
        String stroke = stroke(style, bg);
        int    sw     = strokeWidth(style);

        // UML component glyph: a small, tight pair of plugs at the top-left corner
        int notchW = Math.min(46, Math.max(16, w / 10));
        int notchH = Math.min(22, Math.max(10, h / 12));
        int notchX = x - notchW / 2;

        StringBuilder sb = new StringBuilder();
        sb.append(openGroup(element));
        sb.append(String.format("<rect x=\"%d\" y=\"%d\" width=\"%d\" height=\"%d\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            x, y, w, h, bg, stroke, sw));
        sb.append(String.format("<rect x=\"%d\" y=\"%d\" width=\"%d\" height=\"%d\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            notchX, y + notchH, notchW, notchH, bg, stroke, sw));
        sb.append(String.format("<rect x=\"%d\" y=\"%d\" width=\"%d\" height=\"%d\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            notchX, y + notchH * 5 / 2, notchW, notchH, bg, stroke, sw));
        sb.append(renderBoxText(view, element, style, x, y, w, h));
        sb.append("</g>\n");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Folder
    // -------------------------------------------------------------------------
    private static String renderFolder(ModelView view, Element element, ElementStyle style,
                                        int x, int y, int w, int h) {
        String bg     = bg(style);
        String stroke = stroke(style, bg);
        int    sw     = strokeWidth(style);
        int tabH = Math.max(8, (int)(h * 0.12));
        int tabW = (int)(w * 0.4);

        StringBuilder sb = new StringBuilder();
        sb.append(openGroup(element));
        // Tab
        sb.append(String.format("<rect x=\"%d\" y=\"%d\" width=\"%d\" height=\"%d\" rx=\"4\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            x, y, tabW, tabH, bg, stroke, sw));
        // Body
        sb.append(String.format("<rect x=\"%d\" y=\"%d\" width=\"%d\" height=\"%d\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            x, y + tabH, w, h - tabH, bg, stroke, sw));
        sb.append(renderBoxText(view, element, style, x, y + tabH, w, h - tabH));
        sb.append("</g>\n");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // WebBrowser
    // -------------------------------------------------------------------------
    private static String renderWebBrowser(ModelView view, Element element, ElementStyle style,
                                            int x, int y, int w, int h) {
        String bg     = bg(style);
        String stroke = stroke(style, bg);
        int    sw     = strokeWidth(style);
        int barH = Math.max(10, (int)(h * 0.12));

        StringBuilder sb = new StringBuilder();
        sb.append(openGroup(element));
        sb.append(String.format("<rect x=\"%d\" y=\"%d\" width=\"%d\" height=\"%d\" rx=\"6\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            x, y, w, h, bg, stroke, sw));
        sb.append(String.format("<line x1=\"%d\" y1=\"%d\" x2=\"%d\" y2=\"%d\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            x, y + barH, x + w, y + barH, stroke, sw));
        // Address bar oval, filled a fraction lighter than the body so it stands out
        int barInner = (int)(barH * 0.6);
        sb.append(String.format("<rect x=\"%d\" y=\"%d\" width=\"%d\" height=\"%d\" rx=\"%d\" fill=\"%s\" stroke=\"%s\" stroke-width=\"1\"/>\n",
            x + w / 5, y + (barH - barInner) / 2, w * 3 / 5, barInner, barInner / 2, shadeColor(bg, 15), stroke));
        sb.append(renderBoxText(view, element, style, x, y + barH, w, h - barH));
        sb.append("</g>\n");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Window (box with title bar and three dots)
    // -------------------------------------------------------------------------
    private static String renderWindow(ModelView view, Element element, ElementStyle style,
                                        int x, int y, int w, int h) {
        String bg     = bg(style);
        String stroke = stroke(style, bg);
        int    sw     = strokeWidth(style);
        int barH = Math.max(10, (int)(h * 0.1));
        int dotR = Math.max(3, barH / 4);

        StringBuilder sb = new StringBuilder();
        sb.append(openGroup(element));
        sb.append(String.format("<rect x=\"%d\" y=\"%d\" width=\"%d\" height=\"%d\" rx=\"6\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            x, y, w, h, bg, stroke, sw));
        sb.append(String.format("<line x1=\"%d\" y1=\"%d\" x2=\"%d\" y2=\"%d\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            x, y + barH, x + w, y + barH, stroke, sw));
        for (int i = 0; i < 3; i++) {
            int dotCx = x + dotR * 2 + i * (dotR * 3);
            sb.append(String.format("<circle cx=\"%d\" cy=\"%d\" r=\"%d\" fill=\"%s\"/>\n",
                dotCx, y + barH / 2, dotR, stroke));
        }
        sb.append(renderBoxText(view, element, style, x, y + barH, w, h - barH));
        sb.append("</g>\n");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // MobileDevice (portrait or landscape)
    // -------------------------------------------------------------------------
    private static String renderMobileDevice(ModelView view, Element element, ElementStyle style,
                                              int x, int y, int w, int h, boolean landscape) {
        String bg     = bg(style);
        String stroke = stroke(style, bg);
        int    sw     = strokeWidth(style);
        int corner = 15;
        int bezel  = landscape ? Math.max(6, (int)(h * 0.12)) : Math.max(6, (int)(w * 0.08));

        StringBuilder sb = new StringBuilder();
        sb.append(openGroup(element));
        sb.append(String.format("<rect x=\"%d\" y=\"%d\" width=\"%d\" height=\"%d\" rx=\"%d\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            x, y, w, h, corner, bg, stroke, sw));
        sb.append(String.format("<rect x=\"%d\" y=\"%d\" width=\"%d\" height=\"%d\" fill=\"none\" stroke=\"%s\" stroke-width=\"1\"/>\n",
            x + bezel, y + bezel, w - 2 * bezel, h - 2 * bezel, stroke));
        sb.append(renderBoxText(view, element, style, x, y, w, h));
        sb.append("</g>\n");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Text rendering helpers
    // -------------------------------------------------------------------------

    /**
     * Renders the name+[type]+description labels centred in a rectangular area,
     * matching reference: name bold large, [type] small in brackets, description normal.
     */
    static String renderBoxText(ModelView view, Element element, ElementStyle style,
                                 int x, int y, int w, int h) {
        TextBlock tb = layoutText(view, element, style, w);
        String color = color(style);

        int curY = y + Math.max((h - tb.height()) / 2, 0) + tb.nameFontSize;
        int cx   = x + w / 2;

        StringBuilder sb = new StringBuilder();

        for (String nameLine : tb.nameLines) {
            sb.append(String.format(
                "<text x=\"%d\" y=\"%d\" text-anchor=\"middle\" font-family=\"%s\" font-size=\"%d\" " +
                "font-weight=\"bold\" fill=\"%s\">%s</text>\n",
                cx, curY, DEFAULT_FONT, tb.nameFontSize, color, htmlEscape(nameLine)));
            curY += tb.nameLineH;
        }

        if (!tb.typeStr.isEmpty()) {
            sb.append(String.format(
                "<text x=\"%d\" y=\"%d\" text-anchor=\"middle\" font-family=\"%s\" font-size=\"%d\" " +
                "opacity=\"0.75\" fill=\"%s\">[%s]</text>\n",
                cx, curY, DEFAULT_FONT, tb.typeFontSize, color, htmlEscape(tb.typeStr)));
            curY += tb.typeLineH;
            // Extra gap so description text (larger font) doesn't crowd the type subheading
            if (!tb.descLines.isEmpty()) curY += tb.descFontSize / 3;
        }

        for (String line : tb.descLines) {
            sb.append(String.format(
                "<text x=\"%d\" y=\"%d\" text-anchor=\"middle\" font-family=\"%s\" font-size=\"%d\" fill=\"%s\">%s</text>\n",
                cx, curY, DEFAULT_FONT, tb.descFontSize, color, htmlEscape(line)));
            curY += tb.descLineH;
        }

        return sb.toString();
    }

    /** The wrapped name/type/description block for a given available width. */
    private static TextBlock layoutText(ModelView view, Element element, ElementStyle style, int w) {
        int fontSize     = style.getFontSize() != null ? style.getFontSize() : 24;
        String typeStr   = typeLabel(view, element);
        String name      = element.getName();
        String desc      = element.getDescription();

        int nameFontSize = (int)(fontSize * 1.4);
        int typeFontSize = (int)(fontSize * 0.7);
        int descFontSize = fontSize;

        List<String> nameLines = wrapText(name, w - 20, nameFontSize, true);
        if (nameLines.isEmpty()) nameLines.add(name);
        List<String> descLines = (desc != null && !desc.isEmpty())
            ? wrapText(desc, w - 20, descFontSize) : new ArrayList<>();

        return new TextBlock(nameLines, typeStr, descLines,
            nameFontSize, typeFontSize, descFontSize,
            (int)(nameFontSize * 1.4), (int)(typeFontSize * 1.4), (int)(descFontSize * 1.4));
    }

    private record TextBlock(List<String> nameLines, String typeStr, List<String> descLines,
                             int nameFontSize, int typeFontSize, int descFontSize,
                             int nameLineH, int typeLineH, int descLineH) {
        int height() {
            return nameLines.size() * nameLineH
                 + (typeStr.isEmpty() ? 0 : typeLineH)
                 + (typeStr.isEmpty() || descLines.isEmpty() ? 0 : descFontSize / 3)
                 + descLines.size() * descLineH;
        }
    }

    // -------------------------------------------------------------------------
    // Small utilities
    // -------------------------------------------------------------------------

    private static String typeLabel(ModelView view, Element element) {
        return switch (element) {
            case Person p              -> "Person";
            case SoftwareSystem ss     -> "Software System";
            case Container c           -> {
                String tech = c.getTechnology();
                yield tech != null && !tech.isEmpty() ? "Container: " + tech : "Container";
            }
            case Component comp        -> {
                String tech = comp.getTechnology();
                yield tech != null && !tech.isEmpty() ? "Component: " + tech : "Component";
            }
            case DeploymentNode dn     -> {
                String tech = dn.getTechnology();
                yield tech != null && !tech.isEmpty() ? "Deployment Node: " + tech : "Deployment Node";
            }
            case InfrastructureNode in -> {
                String tech = in.getTechnology();
                yield tech != null && !tech.isEmpty() ? "Infrastructure Node: " + tech : "Infrastructure Node";
            }
            case ContainerInstance ci  -> "Container Instance";
            default                    -> "";
        };
    }

    private static String openGroup(Element element) {
        return String.format("<g id=\"element-%s\" filter=\"url(#elem-shadow)\">\n",
            htmlEscape(element.getId()));
    }

    private static String bg(ElementStyle style) {
        return style.getBackground() != null ? style.getBackground() : "#dddddd";
    }

    private static String stroke(ElementStyle style, String bg) {
        if (style.getStroke() != null && !style.getStroke().isBlank()) {
            return style.getStroke();
        }
        return shadeColor(bg, -10);
    }

    private static String color(ElementStyle style) {
        return style.getColor() != null ? style.getColor() : "#444444";
    }

    private static int strokeWidth(ElementStyle style) {
        return style.getStrokeWidth() != null ? style.getStrokeWidth() : 2;
    }

    static String shadeColor(String hex, int percent) {
        try {
            if (!hex.startsWith("#") || hex.length() < 7) return hex;
            int r = Integer.parseInt(hex.substring(1, 3), 16);
            int g = Integer.parseInt(hex.substring(3, 5), 16);
            int b = Integer.parseInt(hex.substring(5, 7), 16);
            r = Math.min(255, Math.max(0, r + (int)(r * percent / 100.0)));
            g = Math.min(255, Math.max(0, g + (int)(g * percent / 100.0)));
            b = Math.min(255, Math.max(0, b + (int)(b * percent / 100.0)));
            return String.format("#%02x%02x%02x", r, g, b);
        } catch (Exception e) {
            return hex;
        }
    }

    static String htmlEscape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    static List<String> wrapText(String text, int maxWidth, int fontSize) {
        return wrapText(text, maxWidth, fontSize, false);
    }

    /** Greedy word wrap using measured text widths; words wider than a line are hard-broken. */
    static List<String> wrapText(String text, int maxWidth, int fontSize, boolean bold) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) return lines;
        StringBuilder line = new StringBuilder();
        for (String word : text.split("\\s+")) {
            // Hard-break any word that can't fit on a line by itself
            while (word.length() > 1 && TextMetrics.width(word, fontSize, bold) > maxWidth) {
                int cut = word.length() - 1;
                while (cut > 1 && TextMetrics.width(word.substring(0, cut), fontSize, bold) > maxWidth) {
                    cut--;
                }
                if (line.length() > 0) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                lines.add(word.substring(0, cut));
                word = word.substring(cut);
            }
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (!line.isEmpty() && TextMetrics.width(candidate, fontSize, bold) > maxWidth) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (line.length() > 0) lines.add(line.toString());
        return lines;
    }
}
