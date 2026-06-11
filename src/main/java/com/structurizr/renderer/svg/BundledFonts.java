package com.structurizr.renderer.svg;

import java.awt.Font;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Base64;

/**
 * The Inter font (SIL Open Font License), bundled in the JAR subset to Latin
 * coverage (~24KB per weight). It serves two jobs:
 *
 * 1. Text measurement — wrapping and obstacle boxes are computed from the
 *    exact same font on every platform, so output is deterministic.
 * 2. SVG embedding — the font is inlined as a {@code @font-face} data URI,
 *    so diagrams render identically everywhere with no network access and
 *    no dependency on locally installed fonts.
 */
final class BundledFonts {

    static final String FAMILY = "Inter";

    private static final byte[] REGULAR_BYTES = load("/fonts/Inter-Regular.ttf");
    private static final byte[] BOLD_BYTES    = load("/fonts/Inter-Bold.ttf");

    private static final Font REGULAR = create(REGULAR_BYTES);
    private static final Font BOLD    = create(BOLD_BYTES);

    private static final String FONT_FACE_CSS = buildFontFaceCss();

    private BundledFonts() {
    }

    /** Base AWT font for measurement (derive the size), or null if unavailable. */
    static Font font(boolean bold) {
        return bold ? BOLD : REGULAR;
    }

    /** CSS {@code @font-face} rules embedding both weights, or "" if unavailable. */
    static String fontFaceCss() {
        return FONT_FACE_CSS;
    }

    private static String buildFontFaceCss() {
        if (REGULAR_BYTES == null || BOLD_BYTES == null) return "";
        return fontFace(400, REGULAR_BYTES) + "\n" + fontFace(700, BOLD_BYTES);
    }

    private static String fontFace(int weight, byte[] bytes) {
        return "@font-face{font-family:'" + FAMILY + "';font-style:normal;font-weight:" + weight
             + ";src:url(data:font/ttf;base64," + Base64.getEncoder().encodeToString(bytes)
             + ") format('truetype');}";
    }

    private static byte[] load(String resource) {
        try (InputStream in = BundledFonts.class.getResourceAsStream(resource)) {
            return in == null ? null : in.readAllBytes();
        } catch (Exception e) {
            return null;
        }
    }

    private static Font create(byte[] bytes) {
        if (bytes == null) return null;
        try {
            return Font.createFont(Font.TRUETYPE_FONT, new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            return null;
        }
    }
}
