package com.structurizr.renderer;

import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.PNGTranscoder;

import java.io.*;
import java.nio.charset.StandardCharsets;

public class PngRenderer {

    public static void render(String svgContent, File outputFile) throws Exception {
        PNGTranscoder transcoder = new PNGTranscoder();

        byte[] svgBytes = svgContent.getBytes(StandardCharsets.UTF_8);
        try (InputStream in  = new ByteArrayInputStream(svgBytes);
             OutputStream out = new FileOutputStream(outputFile)) {
            transcoder.transcode(new TranscoderInput(in), new TranscoderOutput(out));
        }
    }
}
