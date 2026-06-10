package com.structurizr.renderer.svg;

import com.structurizr.model.*;
import com.structurizr.view.ElementStyle;
import com.structurizr.view.ModelView;
import com.structurizr.view.Shape;

import java.util.ArrayList;
import java.util.List;

public class Shapes {

    static final String DEFAULT_FONT = "Tahoma, Verdana, Helvetica, Arial";

    static String render(ModelView view, Element element, ElementStyle style, int x, int y, int w, int h) {
        Shape shape = style.getShape() != null ? style.getShape() : Shape.Box;
        return switch (shape) {
            case Box          -> renderBox(view, element, style, x, y, w, h, 0);
            case RoundedBox   -> renderBox(view, element, style, x, y, w, h, 15);
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
        // Soft shadow
        sb.append(String.format(
            "<rect x=\"%d\" y=\"%d\" width=\"%d\" height=\"%d\" rx=\"%d\" fill=\"#c8c8c8\" opacity=\"0.5\"/>\n",
            x + 4, y + 4, w, h, rx));
        // Main rect
        sb.append(String.format(
            "<rect x=\"%d\" y=\"%d\" width=\"%d\" height=\"%d\" rx=\"%d\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            x, y, w, h, rx, bg, stroke, sw));
        sb.append(renderBoxText(view, element, style, x, y, w, h));
        sb.append("</g>\n");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Person
    // -------------------------------------------------------------------------
    private static String renderPerson(ModelView view, Element element, ElementStyle style,
                                        int x, int y, int w, int h) {
        String bg     = bg(style);
        String stroke = stroke(style, bg);
        int    sw     = strokeWidth(style);

        // Geometry from structurizr-diagram.js
        double headR  = w / 4.5;
        double headCx = x + w / 2.0;
        double headCy = y + headR;

        double bodyW = w * 0.7;
        double bodyH = h * 0.3;
        double bodyX = x + (w - bodyW) / 2.0;
        double bodyY = y + h / 2.5;

        double legTopY  = bodyY + bodyH;
        double legBotY  = y + h * 0.9;
        double legTopLX = bodyX + bodyW * 0.25;
        double legTopRX = bodyX + bodyW * 0.75;

        double armY  = bodyY + bodyH * 0.3;

        StringBuilder sb = new StringBuilder();
        sb.append(openGroup(element));
        sb.append(String.format("<circle cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            headCx, headCy, headR, bg, stroke, sw));
        sb.append(String.format("<rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            bodyX, bodyY, bodyW, bodyH, bg, stroke, sw));
        sb.append(String.format("<line x1=\"%.1f\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            legTopLX, legTopY, x + w * 0.2, legBotY, stroke, sw));
        sb.append(String.format("<line x1=\"%.1f\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            legTopRX, legTopY, x + w * 0.8, legBotY, stroke, sw));
        sb.append(String.format("<line x1=\"%.1f\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            (double) x, armY, bodyX, armY, stroke, sw));
        sb.append(String.format("<line x1=\"%.1f\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            bodyX + bodyW, armY, (double)(x + w), armY, stroke, sw));
        sb.append(renderPersonText(view, element, style, x, y, w, h));
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

        double headW = w * 0.5;
        double headH = h * 0.2;
        double headX = x + (w - headW) / 2.0;
        double headY = y + h * 0.05;

        double bodyW = w * 0.7;
        double bodyH = h * 0.3;
        double bodyX = x + (w - bodyW) / 2.0;
        double bodyY = headY + headH + h * 0.05;

        double antX   = x + w / 2.0;
        double antTopY = headY - h * 0.08;

        double eyeR = headH * 0.15;
        double eyeY = headY + headH / 2.0;

        StringBuilder sb = new StringBuilder();
        sb.append(openGroup(element));
        // Antenna
        sb.append(String.format("<line x1=\"%.1f\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            antX, headY, antX, antTopY, stroke, sw));
        sb.append(String.format("<circle cx=\"%.1f\" cy=\"%.1f\" r=\"%d\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            antX, antTopY, sw * 2, bg, stroke, sw));
        // Head
        sb.append(String.format("<rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"4\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            headX, headY, headW, headH, bg, stroke, sw));
        // Eyes
        sb.append(String.format("<circle cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"%s\"/>\n", headX + headW * 0.3, eyeY, eyeR, color));
        sb.append(String.format("<circle cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"%s\"/>\n", headX + headW * 0.7, eyeY, eyeR, color));
        // Body
        sb.append(String.format("<rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            bodyX, bodyY, bodyW, bodyH, bg, stroke, sw));
        // Arms
        double armY = bodyY + bodyH * 0.3;
        sb.append(String.format("<line x1=\"%.1f\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            (double) x, armY, bodyX, armY, stroke, sw));
        sb.append(String.format("<line x1=\"%.1f\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            bodyX + bodyW, armY, (double)(x + w), armY, stroke, sw));
        // Legs
        double legTopY = bodyY + bodyH;
        double legBotY = y + h * 0.9;
        sb.append(String.format("<line x1=\"%.1f\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            bodyX + bodyW * 0.25, legTopY, x + w * 0.2, legBotY, stroke, sw));
        sb.append(String.format("<line x1=\"%.1f\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            bodyX + bodyW * 0.75, legTopY, x + w * 0.8, legBotY, stroke, sw));

        sb.append(renderPersonText(view, element, style, x, y, w, h));
        sb.append("</g>\n");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Cylinder
    // -------------------------------------------------------------------------
    private static String renderCylinder(ModelView view, Element element, ElementStyle style,
                                          int x, int y, int w, int h) {
        String bg     = bg(style);
        String stroke = stroke(style, bg);
        int    sw     = strokeWidth(style);

        int rx    = w / 2;
        int ry    = Math.min(60, h / 5);
        int cx    = x + w / 2;
        int topCy = y + ry;
        int botCy = y + h - ry;
        int bodyH = botCy - topCy;

        StringBuilder sb = new StringBuilder();
        sb.append(openGroup(element));
        // Body: left edge down, bottom arc (sweep=0 curves downward in SVG y-down coords), right edge up, closed at top
        sb.append(String.format(
            "<path d=\"M %d %d L %d %d A %d %d 0 0 0 %d %d L %d %d Z\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            x, topCy, x, botCy, rx, ry, x + w, botCy, x + w, topCy, bg, stroke, sw));
        // Top ellipse (lighter shade) drawn on top to give the lid effect
        sb.append(String.format(
            "<ellipse cx=\"%d\" cy=\"%d\" rx=\"%d\" ry=\"%d\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            cx, topCy, rx, ry, shadeColor(bg, 15), stroke, sw));
        sb.append(renderBoxText(view, element, style, x, topCy, w, bodyH));
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
        sb.append(String.format("<rect x=\"%d\" y=\"%d\" width=\"%d\" height=\"%d\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            x + rx, y, w - 2 * rx, h, bg, stroke, sw));
        sb.append(String.format("<ellipse cx=\"%d\" cy=\"%d\" rx=\"%d\" ry=\"%d\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            x + rx, y + ry, rx, ry, shadeColor(bg, 15), stroke, sw));
        sb.append(String.format("<path d=\"M %d %d a %d %d 0 0 1 0 %d\" fill=\"none\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            x + w - rx, y, rx, ry, h, stroke, sw));
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

        int notchW = Math.max(8, (int)(w * 0.15));
        int notchH = Math.max(8, (int)(h * 0.15));
        int notchX = x - notchW / 2;

        StringBuilder sb = new StringBuilder();
        sb.append(openGroup(element));
        sb.append(String.format("<rect x=\"%d\" y=\"%d\" width=\"%d\" height=\"%d\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            x, y, w, h, bg, stroke, sw));
        sb.append(String.format("<rect x=\"%d\" y=\"%d\" width=\"%d\" height=\"%d\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            notchX, y + h / 4 - notchH / 2, notchW, notchH, bg, stroke, sw));
        sb.append(String.format("<rect x=\"%d\" y=\"%d\" width=\"%d\" height=\"%d\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            notchX, y + 3 * h / 4 - notchH / 2, notchW, notchH, bg, stroke, sw));
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
        // Address bar oval
        int barInner = (int)(barH * 0.6);
        sb.append(String.format("<rect x=\"%d\" y=\"%d\" width=\"%d\" height=\"%d\" rx=\"%d\" fill=\"none\" stroke=\"%s\" stroke-width=\"1\"/>\n",
            x + w / 5, y + (barH - barInner) / 2, w * 3 / 5, barInner, barInner / 2, stroke));
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
     * Renders the type+name+description labels centred in a rectangular area.
     */
    static String renderBoxText(ModelView view, Element element, ElementStyle style,
                                 int x, int y, int w, int h) {
        int fontSize   = style.getFontSize() != null ? style.getFontSize() : 24;
        String color   = color(style);
        String typeStr = typeLabel(view, element);
        String name    = element.getName();
        String desc    = element.getDescription();

        int lineH = fontSize + 4;
        int totalLines = (typeStr.isEmpty() ? 0 : 1) + 1;  // type + name
        List<String> descLines = new ArrayList<>();
        if (desc != null && !desc.isEmpty()) {
            int descFontSize = (int)(fontSize * 0.75);
            descLines = wrapText(desc, w - 20, descFontSize);
            totalLines += descLines.size();
        }

        // Centre the text block vertically
        int totalH = totalLines * lineH + (descLines.isEmpty() ? 0 : 4);
        int startY = y + (h - totalH) / 2 + lineH;
        int cx = x + w / 2;

        StringBuilder sb = new StringBuilder();

        if (!typeStr.isEmpty()) {
            sb.append(String.format(
                "<text x=\"%d\" y=\"%d\" text-anchor=\"middle\" font-family=\"%s\" font-size=\"%d\" " +
                "font-style=\"italic\" fill=\"%s\">%s</text>\n",
                cx, startY, DEFAULT_FONT, (int)(fontSize * 0.75), color, htmlEscape(typeStr)));
            startY += lineH;
        }

        sb.append(String.format(
            "<text x=\"%d\" y=\"%d\" text-anchor=\"middle\" font-family=\"%s\" font-size=\"%d\" " +
            "font-weight=\"bold\" fill=\"%s\">%s</text>\n",
            cx, startY, DEFAULT_FONT, fontSize, color, htmlEscape(name)));
        startY += lineH + 4;

        if (!descLines.isEmpty()) {
            int descFontSize = (int)(fontSize * 0.75);
            for (String line : descLines) {
                sb.append(String.format(
                    "<text x=\"%d\" y=\"%d\" text-anchor=\"middle\" font-family=\"%s\" font-size=\"%d\" fill=\"%s\">%s</text>\n",
                    cx, startY, DEFAULT_FONT, descFontSize, color, htmlEscape(line)));
                startY += descFontSize + 4;
            }
        }

        return sb.toString();
    }

    /** Text below the person/robot figure. */
    private static String renderPersonText(ModelView view, Element element, ElementStyle style,
                                            int x, int y, int w, int h) {
        int fontSize   = style.getFontSize() != null ? style.getFontSize() : 24;
        String color   = color(style);
        String typeStr = typeLabel(view, element);
        String name    = element.getName();
        String desc    = element.getDescription();

        // Place below the figure, using the bottom 30% of the height
        int textAreaY = y + (int)(h * 0.72);
        int cx = x + w / 2;
        int cur = textAreaY + fontSize;

        StringBuilder sb = new StringBuilder();

        if (!typeStr.isEmpty()) {
            sb.append(String.format(
                "<text x=\"%d\" y=\"%d\" text-anchor=\"middle\" font-family=\"%s\" font-size=\"%d\" " +
                "font-style=\"italic\" fill=\"%s\">%s</text>\n",
                cx, cur, DEFAULT_FONT, (int)(fontSize * 0.75), color, htmlEscape(typeStr)));
            cur += fontSize;
        }

        sb.append(String.format(
            "<text x=\"%d\" y=\"%d\" text-anchor=\"middle\" font-family=\"%s\" font-size=\"%d\" " +
            "font-weight=\"bold\" fill=\"%s\">%s</text>\n",
            cx, cur, DEFAULT_FONT, fontSize, color, htmlEscape(name)));
        cur += fontSize + 4;

        if (desc != null && !desc.isEmpty()) {
            int descFontSize = (int)(fontSize * 0.75);
            for (String line : wrapText(desc, w - 10, descFontSize)) {
                sb.append(String.format(
                    "<text x=\"%d\" y=\"%d\" text-anchor=\"middle\" font-family=\"%s\" font-size=\"%d\" fill=\"%s\">%s</text>\n",
                    cx, cur, DEFAULT_FONT, descFontSize, color, htmlEscape(line)));
                cur += descFontSize + 4;
            }
        }

        return sb.toString();
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
        return String.format("<g id=\"element-%s\">\n", htmlEscape(element.getId()));
    }

    private static String bg(ElementStyle style) {
        return style.getBackground() != null ? style.getBackground() : "#dddddd";
    }

    private static String stroke(ElementStyle style, String bg) {
        return style.getStroke() != null ? style.getStroke() : shadeColor(bg, -10);
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
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) return lines;
        double charWidth = fontSize * 0.6;
        int charsPerLine = Math.max(1, (int)(maxWidth / charWidth));
        String[] words = text.split("\\s+");
        StringBuilder line = new StringBuilder();
        for (String word : words) {
            if (line.length() > 0 && line.length() + 1 + word.length() > charsPerLine) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                if (line.length() > 0) line.append(' ');
                line.append(word);
            }
        }
        if (line.length() > 0) lines.add(line.toString());
        return lines;
    }
}
