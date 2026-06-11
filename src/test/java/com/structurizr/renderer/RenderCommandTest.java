package com.structurizr.renderer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RenderCommandTest {

    private static final String MINIMAL_DSL = """
        workspace {
            model {
                a = softwareSystem "System A"
                b = softwareSystem "System B"
                a -> b "Uses"
            }
            views {
                systemLandscape "Landscape" {
                    include *
                    autoLayout
                }
            }
        }
        """;

    private Path writeDsl(Path dir) throws Exception {
        Path dsl = dir.resolve("workspace.dsl");
        Files.writeString(dsl, MINIMAL_DSL);
        return dsl;
    }

    private int execute(String... args) {
        return new CommandLine(new RenderCommand()).execute(args);
    }

    @Test
    void rendersSvgToOutputDir(@TempDir Path tmp) throws Exception {
        Path dsl = writeDsl(tmp);
        Path out = tmp.resolve("out");

        int exit = execute("-o", out.toString(), dsl.toString());

        assertEquals(0, exit);
        assertTrue(Files.exists(out.resolve("Landscape.svg")), "SVG not written");
    }

    @Test
    void rendersPngWithRequestedWidth(@TempDir Path tmp) throws Exception {
        Path dsl = writeDsl(tmp);
        Path out = tmp.resolve("out");

        int exit = execute("-f", "png", "--png-width", "400", "-o", out.toString(), dsl.toString());

        assertEquals(0, exit);
        Path png = out.resolve("Landscape.png");
        assertTrue(Files.exists(png), "PNG not written");

        byte[] bytes = Files.readAllBytes(png);
        assertTrue(bytes.length > 8, "PNG file is empty");
        assertEquals((byte) 0x89, bytes[0]);
        assertEquals('P', bytes[1]);
        assertEquals('N', bytes[2]);
        assertEquals('G', bytes[3]);
        // IHDR width is a big-endian int at offset 16
        int width = ((bytes[16] & 0xff) << 24) | ((bytes[17] & 0xff) << 16)
                  | ((bytes[18] & 0xff) << 8)  |  (bytes[19] & 0xff);
        assertEquals(400, width, "--png-width not honoured");
    }

    @Test
    void unknownViewKeyFails(@TempDir Path tmp) throws Exception {
        Path dsl = writeDsl(tmp);

        int exit = execute("-v", "NoSuchView", dsl.toString());

        assertEquals(1, exit);
    }

    @Test
    void unknownFormatFails(@TempDir Path tmp) throws Exception {
        Path dsl = writeDsl(tmp);

        int exit = execute("-f", "gif", dsl.toString());

        assertEquals(1, exit);
    }

    @Test
    void missingFileFails() {
        int exit = execute("does-not-exist.dsl");

        assertEquals(1, exit);
    }
}
