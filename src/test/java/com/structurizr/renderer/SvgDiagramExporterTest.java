package com.structurizr.renderer;

import com.structurizr.Workspace;
import com.structurizr.dsl.StructurizrDslParser;
import com.structurizr.export.Diagram;
import com.structurizr.renderer.layout.ElkLayoutStrategy;
import com.structurizr.renderer.svg.SvgDiagramExporter;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;

import static org.junit.jupiter.api.Assertions.*;

class SvgDiagramExporterTest {

    @Test
    void rendersBigBank() throws Exception {
        File dsl = new File("src/test/resources/fixtures/big-bank.dsl");
        assertTrue(dsl.exists(), "Fixture not found: " + dsl.getAbsolutePath());

        StructurizrDslParser parser = new StructurizrDslParser();
        parser.parse(dsl);
        Workspace workspace = parser.getWorkspace();

        // Use ELK so this test has no external dependency on graphviz
        new ElkLayoutStrategy().applyLayout(workspace);

        Collection<Diagram> diagrams = new SvgDiagramExporter().export(workspace);

        // Official big-bank fixture has 7 views: SystemLandscape, SystemContext,
        // Containers, Components, SignIn (dynamic), DevelopmentDeployment, LiveDeployment
        assertEquals(7, diagrams.size(), "Expected 7 views from official big-bank fixture");

        Path outDir = Path.of("target/test-output");
        Files.createDirectories(outDir);

        for (Diagram d : diagrams) {
            String svg = d.getDefinition();
            assertNotNull(svg, d.getKey() + ": SVG should not be null");
            assertTrue(svg.contains("<svg"), d.getKey() + ": should contain <svg>");
            assertTrue(svg.contains("</svg>"), d.getKey() + ": should contain </svg>");
            assertTrue(svg.contains("<g ") || svg.contains("<g>"), d.getKey() + ": should have element groups");

            Files.writeString(outDir.resolve(d.getKey() + ".svg"), svg);
            System.out.println("Written: " + outDir.resolve(d.getKey() + ".svg"));
        }
    }
}
