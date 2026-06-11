package com.structurizr.renderer;

import com.structurizr.Workspace;
import com.structurizr.dsl.StructurizrDslParser;
import com.structurizr.export.Diagram;
import com.structurizr.renderer.layout.LayoutStrategyFactory;
import com.structurizr.renderer.svg.SvgDiagramExporter;
import com.structurizr.renderer.svg.ThemeCache;
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

        LayoutStrategyFactory.create().applyLayout(workspace);

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

    @Test
    void parallelRelationshipsKeepBothLabels() throws Exception {
        // Two relationships between the same element pair produce identical SVG paths;
        // the dedup pass must not drop either label.
        Workspace workspace = new Workspace("test", "");
        var a = workspace.getModel().addSoftwareSystem("System A");
        var b = workspace.getModel().addSoftwareSystem("System B");
        a.uses(b, "Reads from");
        a.uses(b, "Writes to");

        var view = workspace.getViews().createSystemLandscapeView("landscape", "");
        view.addAllElements();
        view.getElementView(a).setX(100);
        view.getElementView(a).setY(100);
        view.getElementView(b).setX(1200);
        view.getElementView(b).setY(100);

        Diagram d = new SvgDiagramExporter().export(workspace).iterator().next();
        String svg = d.getDefinition();
        assertTrue(svg.contains("Reads from"), "First parallel relationship label missing");
        assertTrue(svg.contains("Writes to"), "Second parallel relationship label missing");
    }

    @Test
    void selfRelationshipRendersAsLoop() throws Exception {
        Workspace workspace = new Workspace("test", "");
        var a = workspace.getModel().addSoftwareSystem("System A");
        a.uses(a, "Calls itself");

        var view = workspace.getViews().createSystemLandscapeView("landscape", "");
        view.addAllElements();
        view.getElementView(a).setX(100);
        view.getElementView(a).setY(100);

        Diagram d = new SvgDiagramExporter().export(workspace).iterator().next();
        String svg = d.getDefinition();
        assertTrue(svg.contains("Calls itself"), "Self-relationship label missing");
        assertTrue(svg.contains(" C "), "Self-relationship should render as a curved loop path");
    }

    @Test
    void dynamicViewResponseStepsPointBackwards() throws Exception {
        Workspace workspace = new Workspace("test", "");
        var a = workspace.getModel().addSoftwareSystem("System A");
        var b = workspace.getModel().addSoftwareSystem("System B");
        a.uses(b, "Requests data from");

        var view = workspace.getViews().createDynamicView("dyn", "");
        view.add(a, "Requests data from", b);
        view.add(b, "Sends data back to", a); // response step over the same relationship
        view.getElementView(a).setX(100);
        view.getElementView(a).setY(100);
        view.getElementView(b).setX(1200);
        view.getElementView(b).setY(100);

        Diagram d = new SvgDiagramExporter().export(workspace).iterator().next();
        String svg = d.getDefinition();
        // Forward step: right edge of A -> left edge of B; response: the reverse
        assertTrue(svg.contains("M 550.0 250.0 L 1200.0 250.0"), "Forward step path missing");
        assertTrue(svg.contains("M 1200.0 250.0 L 550.0 250.0"), "Response step should be reversed");
    }

    @Test
    void negativeCoordinatesAreShiftedOntoCanvas() throws Exception {
        Workspace workspace = new Workspace("test", "");
        var a = workspace.getModel().addSoftwareSystem("System A");
        var b = workspace.getModel().addSoftwareSystem("System B");
        a.uses(b, "");

        var view = workspace.getViews().createSystemLandscapeView("landscape", "");
        view.addAllElements();
        view.getElementView(a).setX(-500);
        view.getElementView(a).setY(-300);
        view.getElementView(b).setX(500);
        view.getElementView(b).setY(300);

        Diagram d = new SvgDiagramExporter().export(workspace).iterator().next();
        String svg = d.getDefinition();
        // PADDING(50) - minX(-500) = 550, PADDING(50) - minY(-300) = 350
        assertTrue(svg.contains("translate(550,350)"),
            "Group translate should shift negative coordinates onto the canvas");
    }

    @Test
    void rendersAwsAllServices() throws Exception {
        File dsl = new File("src/test/resources/fixtures/aws-all-services.dsl");
        assertTrue(dsl.exists(), "Fixture not found: " + dsl.getAbsolutePath());

        // Theme and icons are bundled locally — no network required
        StructurizrDslParser parser = new StructurizrDslParser();
        parser.parse(dsl);
        Workspace workspace = parser.getWorkspace();

        // Theme is served from the JAR's bundled classpath resources — no HTTP calls made
        ThemeCache.loadThemes(workspace);
        LayoutStrategyFactory.create().applyLayout(workspace);

        Collection<Diagram> diagrams = new SvgDiagramExporter().export(workspace);
        assertEquals(1, diagrams.size(), "Expected 1 deployment view");

        Path outDir = Path.of("target/test-output");
        Files.createDirectories(outDir);

        Diagram d = diagrams.iterator().next();
        String svg = d.getDefinition();
        assertNotNull(svg);
        assertTrue(svg.contains("<svg"), "Should contain <svg>");
        assertTrue(svg.contains("</svg>"), "Should contain </svg>");

        // Verify icon embedding worked: at least some <image> elements should be present
        assertTrue(svg.contains("<image "), "SVG should contain embedded icon <image> elements");

        // Verify base64 data URIs are present (not broken external references)
        assertTrue(svg.contains("data:image/png;base64,"), "Icons should be embedded as base64 data URIs");

        Files.writeString(outDir.resolve(d.getKey() + ".svg"), svg);
        System.out.println("Written: " + outDir.resolve(d.getKey() + ".svg"));
        System.out.println("SVG size: " + svg.length() + " chars");

        long iconCount = svg.lines().filter(l -> l.contains("<image ")).count();
        System.out.println("Icon elements rendered: " + iconCount);
    }
}
