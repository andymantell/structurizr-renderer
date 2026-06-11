package com.structurizr.renderer.svg;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.font.FontRenderContext;
import java.awt.geom.AffineTransform;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Measures rendered text widths using AWT font metrics (works headless — no
 * display required).  The SVG output declares the font stack
 * "Tahoma, Verdana, Helvetica, Arial"; measurement uses the first of those
 * families installed locally, falling back to the logical sans-serif font.
 * A small safety factor compensates for metric differences between the
 * measuring font and whatever font the viewer ultimately renders with, so
 * wrapped lines and obstacle boxes never under-estimate.
 */
final class TextMetrics {

    private static final FontRenderContext FRC =
        new FontRenderContext(new AffineTransform(), true, true);
    private static final double SAFETY = 1.05;
    private static final String FAMILY = pickFamily();
    private static final ConcurrentHashMap<Integer, Font> FONT_CACHE = new ConcurrentHashMap<>();

    private TextMetrics() {
    }

    /** Width in SVG user units of the text at the given font size. */
    static double width(String text, int fontSize, boolean bold) {
        if (text == null || text.isEmpty()) return 0;
        Font font = FONT_CACHE.computeIfAbsent(fontSize * 2 + (bold ? 1 : 0),
            k -> new Font(FAMILY, bold ? Font.BOLD : Font.PLAIN, fontSize));
        return font.getStringBounds(text, FRC).getWidth() * SAFETY;
    }

    private static String pickFamily() {
        try {
            String[] available = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getAvailableFontFamilyNames();
            for (String wanted : new String[]{"Tahoma", "Verdana", "Helvetica", "Arial"}) {
                for (String name : available) {
                    if (name.equalsIgnoreCase(wanted)) return name;
                }
            }
        } catch (Throwable ignored) {
            // fall through to logical font
        }
        return Font.SANS_SERIF;
    }
}
