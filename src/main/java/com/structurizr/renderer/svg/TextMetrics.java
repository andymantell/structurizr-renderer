package com.structurizr.renderer.svg;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.font.FontRenderContext;
import java.awt.geom.AffineTransform;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Measures rendered text widths using AWT font metrics (works headless — no
 * display required). Measurement uses the bundled Inter font — the same font
 * embedded into the SVG output — so wrapping decisions are deterministic
 * across platforms and match what the viewer renders. A small safety factor
 * covers antialiasing/hinting differences.
 */
final class TextMetrics {

    private static final FontRenderContext FRC =
        new FontRenderContext(new AffineTransform(), true, true);
    private static final double SAFETY = 1.03;
    private static final ConcurrentHashMap<Integer, Font> FONT_CACHE = new ConcurrentHashMap<>();

    private TextMetrics() {
    }

    /** Width in SVG user units of the text at the given font size. */
    static double width(String text, int fontSize, boolean bold) {
        if (text == null || text.isEmpty()) return 0;
        Font font = FONT_CACHE.computeIfAbsent(fontSize * 2 + (bold ? 1 : 0),
            k -> sized(fontSize, bold));
        return font.getStringBounds(text, FRC).getWidth() * SAFETY;
    }

    private static Font sized(int fontSize, boolean bold) {
        Font base = BundledFonts.font(bold);
        if (base != null) {
            return base.deriveFont((float) fontSize);
        }
        return new Font(fallbackFamily(), bold ? Font.BOLD : Font.PLAIN, fontSize);
    }

    private static String fallbackFamily() {
        try {
            String[] available = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getAvailableFontFamilyNames();
            for (String wanted : new String[]{"Helvetica", "Arial", "Tahoma", "Verdana"}) {
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
