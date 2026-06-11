package com.structurizr.renderer;

import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.PNGTranscoder;

import java.io.*;
import java.nio.charset.StandardCharsets;

public class PngRenderer {

    /**
     * Rasterizes the SVG to a PNG file.  When {@code width} is non-null the output
     * is scaled to that pixel width (preserving aspect ratio); otherwise the PNG
     * matches the SVG's natural size.
     */
    public static void render(String svgContent, File outputFile, Integer width) throws Exception {
        PNGTranscoder transcoder = new PNGTranscoder();
        if (width != null) {
            transcoder.addTranscodingHint(PNGTranscoder.KEY_WIDTH, (float) width);
        }

        byte[] svgBytes = svgContent.getBytes(StandardCharsets.UTF_8);
        try (InputStream in  = new ByteArrayInputStream(svgBytes);
             OutputStream out = new FileOutputStream(outputFile)) {
            transcoder.transcode(new TranscoderInput(in), new TranscoderOutput(out));
        }
    }
}
