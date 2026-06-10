# Structurizr Renderer — Java Implementation Plan

## Goal

Build a standalone Java CLI tool that accepts a Structurizr DSL (`.dsl`) file and renders
every view (or a named view) to SVG and/or PNG, with no external HTTP calls and no browser
dependency.

Repo: `/mnt/c/work/structurizr-renderer` (currently empty except `.git`).
Reference source: `/mnt/c/work/structurizr` (the original Java + JS application).

---

## Why Java

The Structurizr project already provides Apache-licensed Java libraries for everything except
the actual SVG rendering (which the original application does via headless Chrome/Playwright).
Reusing these libraries eliminates the two hardest problems:

1. **DSL parsing** — `structurizr-dsl` is 140+ Java files. Writing an equivalent parser in
   any other language is the most error-prone part of the whole project.
2. **View resolution** — `include *`, `exclude`, filtered views, element type inheritance —
   this is already implemented in `structurizr-export`'s `AbstractDiagramExporter`.

The only code we need to write is the SVG rendering layer — roughly 800–1200 lines of Java.

---

## License

The Structurizr project uses **Apache License 2.0**. This permits:
- Using and redistributing the library JARs as Maven dependencies
- Modifying source code
- Publishing the resulting tool under any licence (including MIT, Apache 2, etc.)

Requirements:
- Include a copy of the Apache 2.0 licence in the repo
- Retain copyright notices from any copied/modified source files
- Note which files were modified (a NOTICE file or commit history satisfies this)

Recommended: release this tool under **Apache 2.0** for consistency, with a NOTICE file
crediting the Structurizr project.

---

## Technology choices

| Concern | Library | Reason |
|---|---|---|
| DSL parsing | `com.structurizr:structurizr-dsl` | Official parser, zero re-implementation |
| Workspace model | `com.structurizr:structurizr-core` | Included transitively by structurizr-dsl |
| View iteration / resolution | `com.structurizr:structurizr-export` | `AbstractDiagramExporter` handles include/exclude/filtered views |
| Layout (preferred) | `com.structurizr:structurizr-autolayout` + `dot` binary | Already integrates with workspace model; Graphviz on PATH |
| Layout (fallback) | `org.eclipse.elk:elk-core` + `elk-alg-layered` | Pure Java Sugiyama impl when `dot` absent |
| SVG → PNG | `org.apache.xmlgraphics:batik-rasterizer` | Pure Java, no browser |
| CLI args | `info.picocli:picocli` | Annotation-driven, produces native executables, GraalVM-friendly |
| Build | Maven | Consistent with Structurizr project conventions |

No JointJS, no browser, no Playwright, no Node.js.

---

## Project structure

```
structurizr-renderer/
  pom.xml
  src/
    main/
      java/
        com/structurizr/renderer/
          Main.java                    CLI entry point (Picocli @Command)
          RenderCommand.java           Main command logic
          svg/
            SvgDiagramExporter.java    Extends AbstractDiagramExporter — core rendering
            SvgWriter.java             Stateful per-view SVG builder
            Shapes.java                SVG markup for every shape type
            Connectors.java            SVG arrows and relationship labels
            StyleResolver.java         Compute effective ElementStyle/RelationshipStyle
          layout/
            LayoutStrategy.java        Interface: applyLayout(Workspace)
            GraphvizLayoutStrategy.java  Delegates to structurizr-autolayout
            ElkLayoutStrategy.java     Pure-Java ELK adapter
            LayoutStrategyFactory.java Detects `dot` on PATH, picks strategy
      resources/
        META-INF/
          MANIFEST.MF                  (or handled by Maven Shade)
    test/
      java/
        com/structurizr/renderer/
          SvgDiagramExporterTest.java
          ShapesTest.java
      resources/
        fixtures/
          big-bank.dsl                 Canonical test fixture (see below)
          big-bank-expected/           Expected SVG outputs (generated once, locked)
  NOTICE                               Apache 2.0 attribution for Structurizr
  LICENSE                              Apache 2.0 (for this tool)
```

---

## pom.xml — dependencies

```xml
<project>
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.structurizr</groupId>
  <artifactId>structurizr-renderer</artifactId>
  <version>1.0.0-SNAPSHOT</version>
  <packaging>jar</packaging>

  <properties>
    <java.version>17</java.version>
    <structurizr.version>3.0.0</structurizr.version>
  </properties>

  <dependencies>
    <!-- Structurizr DSL parser (brings in core, export, import) -->
    <dependency>
      <groupId>com.structurizr</groupId>
      <artifactId>structurizr-dsl</artifactId>
      <version>${structurizr.version}</version>
    </dependency>

    <!-- Structurizr export base class -->
    <dependency>
      <groupId>com.structurizr</groupId>
      <artifactId>structurizr-export</artifactId>
      <version>${structurizr.version}</version>
    </dependency>

    <!-- Graphviz-based autolayout (optional at runtime) -->
    <dependency>
      <groupId>com.structurizr</groupId>
      <artifactId>structurizr-autolayout</artifactId>
      <version>${structurizr.version}</version>
    </dependency>

    <!-- ELK pure-Java layout fallback -->
    <dependency>
      <groupId>org.eclipse.elk</groupId>
      <artifactId>elk-core</artifactId>
      <version>0.9.1</version>
    </dependency>
    <dependency>
      <groupId>org.eclipse.elk</groupId>
      <artifactId>elk-alg-layered</artifactId>
      <version>0.9.1</version>
    </dependency>

    <!-- SVG -> PNG rasterizer -->
    <dependency>
      <groupId>org.apache.xmlgraphics</groupId>
      <artifactId>batik-rasterizer</artifactId>
      <version>1.17</version>
    </dependency>
    <dependency>
      <groupId>org.apache.xmlgraphics</groupId>
      <artifactId>batik-transcoder</artifactId>
      <version>1.17</version>
    </dependency>

    <!-- CLI -->
    <dependency>
      <groupId>info.picocli</groupId>
      <artifactId>picocli</artifactId>
      <version>4.7.6</version>
    </dependency>

    <!-- Tests -->
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <version>5.10.0</version>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <!-- Fat JAR (single executable JAR with all deps) -->
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-shade-plugin</artifactId>
        <version>3.5.1</version>
        <executions>
          <execution>
            <phase>package</phase>
            <goals><goal>shade</goal></goals>
            <configuration>
              <transformers>
                <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                  <mainClass>com.structurizr.renderer.Main</mainClass>
                </transformer>
                <!-- Batik needs this to find its codecs -->
                <transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
              </transformers>
              <filters>
                <filter>
                  <artifact>*:*</artifact>
                  <excludes>
                    <exclude>META-INF/*.SF</exclude>
                    <exclude>META-INF/*.DSA</exclude>
                    <exclude>META-INF/*.RSA</exclude>
                  </excludes>
                </filter>
              </filters>
            </configuration>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
```

> **Version note**: Check the latest published versions of `structurizr-dsl`, `structurizr-export`,
> and `structurizr-autolayout` on Maven Central before starting. As of mid-2025 they are in
> the 3.x series. Confirm with: https://central.sonatype.com/artifact/com.structurizr/structurizr-dsl

---

## Step 1 — CLI entry point

### `Main.java`

```java
package com.structurizr.renderer;

import picocli.CommandLine;

public class Main {
    public static void main(String[] args) {
        int exitCode = new CommandLine(new RenderCommand()).execute(args);
        System.exit(exitCode);
    }
}
```

### `RenderCommand.java`

```java
package com.structurizr.renderer;

import com.structurizr.Workspace;
import com.structurizr.dsl.StructurizrDslParser;
import com.structurizr.renderer.layout.LayoutStrategyFactory;
import com.structurizr.renderer.svg.SvgDiagramExporter;
import picocli.CommandLine.*;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.Callable;

@Command(
    name = "structurizr-renderer",
    mixinStandardHelpOptions = true,
    description = "Render Structurizr DSL views to SVG or PNG"
)
public class RenderCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Path to .dsl file")
    private File dslFile;

    @Option(names = {"-o", "--output"}, description = "Output directory (default: same dir as input)", defaultValue = "")
    private String outputDir;

    @Option(names = {"-f", "--format"}, description = "Output format: svg, png, or both (default: svg)")
    private String format = "svg";

    @Option(names = {"-v", "--view"}, description = "Key of a single view to render (default: all views)")
    private String viewKey;

    @Option(names = {"--no-autolayout"}, description = "Skip auto-layout even if DSL specifies it")
    private boolean noAutolayout;

    @Override
    public Integer call() throws Exception {
        if (!dslFile.exists()) {
            System.err.println("Error: file not found: " + dslFile);
            return 1;
        }

        // Parse DSL
        StructurizrDslParser parser = new StructurizrDslParser();
        parser.parse(dslFile);
        Workspace workspace = parser.getWorkspace();

        // Apply layout to views that have automaticLayout set
        if (!noAutolayout) {
            LayoutStrategyFactory.create().applyLayout(workspace);
        }

        // Determine output directory
        File outDir = outputDir.isEmpty()
            ? dslFile.getParentFile()
            : new File(outputDir);
        outDir.mkdirs();

        // Render
        SvgDiagramExporter exporter = new SvgDiagramExporter();
        boolean doSvg = format.equals("svg") || format.equals("both");
        boolean doPng = format.equals("png") || format.equals("both");

        // The exporter produces Diagram objects (key → SVG string)
        Collection<Diagram> diagrams = exporter.export(workspace);

        for (Diagram diagram : diagrams) {
            if (viewKey != null && !diagram.getKey().equals(viewKey)) continue;

            String svg = diagram.getDefinition();

            if (doSvg) {
                File svgFile = new File(outDir, diagram.getKey() + ".svg");
                Files.writeString(svgFile.toPath(), svg);
                System.out.println("Written: " + svgFile);
            }

            if (doPng) {
                File pngFile = new File(outDir, diagram.getKey() + ".png");
                PngRenderer.render(svg, pngFile);
                System.out.println("Written: " + pngFile);
            }
        }

        return 0;
    }
}
```

---

## Step 2 — Layout strategies

### `LayoutStrategy.java`

```java
package com.structurizr.renderer.layout;

import com.structurizr.Workspace;

public interface LayoutStrategy {
    void applyLayout(Workspace workspace) throws Exception;
}
```

### `LayoutStrategyFactory.java`

```java
package com.structurizr.renderer.layout;

public class LayoutStrategyFactory {
    public static LayoutStrategy create() {
        // Check if `dot` (Graphviz) is on PATH
        try {
            Process p = new ProcessBuilder("dot", "-V")
                .redirectErrorStream(true)
                .start();
            p.waitFor();
            if (p.exitValue() == 0) {
                System.err.println("[layout] Using Graphviz (dot)");
                return new GraphvizLayoutStrategy();
            }
        } catch (Exception ignored) {}

        System.err.println("[layout] dot not found; using ELK (pure Java)");
        return new ElkLayoutStrategy();
    }
}
```

### `GraphvizLayoutStrategy.java`

This delegates directly to the existing `structurizr-autolayout` library. Zero layout logic to
implement — just call it:

```java
package com.structurizr.renderer.layout;

import com.structurizr.Workspace;
import com.structurizr.autolayout.graphviz.GraphvizAutomaticLayout;

public class GraphvizLayoutStrategy implements LayoutStrategy {
    @Override
    public void applyLayout(Workspace workspace) throws Exception {
        new GraphvizAutomaticLayout().apply(workspace);
    }
}
```

### `ElkLayoutStrategy.java`

Pure-Java fallback. Implements Sugiyama hierarchical layout using ELK. This is the most
complex piece to implement from scratch (~300 lines):

```java
package com.structurizr.renderer.layout;

import com.structurizr.Workspace;
import com.structurizr.model.*;
import com.structurizr.view.*;
import org.eclipse.elk.alg.layered.options.LayeredMetaDataProvider;
import org.eclipse.elk.core.IGraphLayoutEngine;
import org.eclipse.elk.core.RecursiveGraphLayoutEngine;
import org.eclipse.elk.core.data.LayoutMetaDataService;
import org.eclipse.elk.core.options.*;
import org.eclipse.elk.core.util.BasicProgressMonitor;
import org.eclipse.elk.graph.*;
import org.eclipse.elk.graph.util.ElkGraphUtil;

import java.util.*;

public class ElkLayoutStrategy implements LayoutStrategy {

    @Override
    public void applyLayout(Workspace workspace) {
        // Register ELK algorithms
        LayoutMetaDataService.getInstance().registerLayoutMetaDataProviders(
            new LayeredMetaDataProvider()
        );

        // Apply layout to every view that has automaticLayout set
        for (CustomView view : workspace.getViews().getCustomViews()) {
            if (view.getAutomaticLayout() != null) applyToView(view, workspace);
        }
        for (SystemLandscapeView view : workspace.getViews().getSystemLandscapeViews()) {
            if (view.getAutomaticLayout() != null) applyToView(view, workspace);
        }
        for (SystemContextView view : workspace.getViews().getSystemContextViews()) {
            if (view.getAutomaticLayout() != null) applyToView(view, workspace);
        }
        for (ContainerView view : workspace.getViews().getContainerViews()) {
            if (view.getAutomaticLayout() != null) applyToView(view, workspace);
        }
        for (ComponentView view : workspace.getViews().getComponentViews()) {
            if (view.getAutomaticLayout() != null) applyToView(view, workspace);
        }
        for (DynamicView view : workspace.getViews().getDynamicViews()) {
            if (view.getAutomaticLayout() != null) applyToView(view, workspace);
        }
        for (DeploymentView view : workspace.getViews().getDeploymentViews()) {
            if (view.getAutomaticLayout() != null) applyToView(view, workspace);
        }
    }

    private void applyToView(View view, Workspace workspace) {
        AutomaticLayout al = view.getAutomaticLayout();

        // Build ELK graph
        ElkNode root = ElkGraphUtil.createGraph();
        root.setProperty(CoreOptions.ALGORITHM, "layered");
        root.setProperty(CoreOptions.DIRECTION, toElkDirection(al.getRankDirection()));
        root.setProperty(CoreOptions.SPACING_NODE_NODE, (double) al.getRankSeparation());
        root.setProperty(CoreOptions.SPACING_EDGE_NODE, (double) al.getNodeSeparation());

        Map<String, ElkNode> nodeMap = new HashMap<>();

        // Add nodes
        for (ElementView ev : view.getElements()) {
            Element element = workspace.getModel().getElement(ev.getId());
            if (element == null) continue;

            int w = ev.getWidth() > 0 ? ev.getWidth() : 450;
            int h = ev.getHeight() > 0 ? ev.getHeight() : 300;

            ElkNode node = ElkGraphUtil.createNode(root);
            node.setIdentifier(ev.getId());
            node.setWidth(w);
            node.setHeight(h);
            nodeMap.put(ev.getId(), node);
        }

        // Add edges
        for (RelationshipView rv : view.getRelationships()) {
            Relationship rel = workspace.getModel().getRelationship(rv.getId());
            if (rel == null) continue;
            ElkNode src = nodeMap.get(rel.getSourceId());
            ElkNode dst = nodeMap.get(rel.getDestinationId());
            if (src == null || dst == null) continue;
            ElkGraphUtil.createSimpleEdge(src, dst);
        }

        // Run layout
        IGraphLayoutEngine engine = new RecursiveGraphLayoutEngine();
        engine.layout(root, new BasicProgressMonitor());

        // Write positions back to view
        for (ElkNode node : root.getChildren()) {
            String id = node.getIdentifier();
            for (ElementView ev : view.getElements()) {
                if (ev.getId().equals(id)) {
                    ev.setX((int) node.getX());
                    ev.setY((int) node.getY());
                    break;
                }
            }
        }
    }

    private Direction toElkDirection(RankDirection rankDirection) {
        if (rankDirection == null) return Direction.RIGHT;
        return switch (rankDirection) {
            case TopBottom -> Direction.DOWN;
            case BottomTop -> Direction.UP;
            case LeftRight -> Direction.RIGHT;
            case RightLeft -> Direction.LEFT;
        };
    }
}
```

> **Note**: The ELK `ElkLayoutStrategy` above handles flat graphs. For views with nested
> boundaries (containers inside a software system boundary, deployment nodes), you'll need
> to create child `ElkNode` graphs inside parent nodes. Implement this after the flat case
> is working.

---

## Step 3 — SVG exporter (the core)

This extends `AbstractDiagramExporter` from `structurizr-export`. The base class:
- Calls `export(Workspace)` which iterates all view types
- For each view calls the abstract methods below in order
- Already resolves `include *` / `exclude` — `view.getElements()` returns only the visible set
- Already handles filtered views, group boundaries, system/container boundaries

You must implement these abstract methods:

```
writeHeader(View view, IndentingWriter writer)
writeFooter(View view, IndentingWriter writer)
writeElement(View view, Element element, IndentingWriter writer)
writeRelationship(View view, RelationshipView rv, IndentingWriter writer)
startGroupBoundary(View view, String groupName, IndentingWriter writer)
endGroupBoundary(View view, IndentingWriter writer)
startSoftwareSystemBoundary(View view, SoftwareSystem ss, IndentingWriter writer)
endSoftwareSystemBoundary(View view, IndentingWriter writer)
startContainerBoundary(View view, Container c, IndentingWriter writer)
endContainerBoundary(View view, IndentingWriter writer)
startDeploymentNodeBoundary(View view, DeploymentNode dn, IndentingWriter writer)
endDeploymentNodeBoundary(View view, IndentingWriter writer)
```

### `SvgDiagramExporter.java` (overview)

```java
package com.structurizr.renderer.svg;

import com.structurizr.export.AbstractDiagramExporter;
import com.structurizr.export.Diagram;
import com.structurizr.export.IndentingWriter;
import com.structurizr.model.*;
import com.structurizr.util.TagUtils;
import com.structurizr.view.*;

public class SvgDiagramExporter extends AbstractDiagramExporter {

    // Per-view state (reset in writeHeader)
    private int canvasWidth;
    private int canvasHeight;
    private int padding = 50;

    @Override
    protected void writeHeader(View view, IndentingWriter writer) {
        // Compute canvas bounding box from element positions + sizes
        int maxX = 0, maxY = 0;
        for (ElementView ev : view.getElements()) {
            int w = ev.getWidth() > 0 ? ev.getWidth() : 450;
            int h = ev.getHeight() > 0 ? ev.getHeight() : 300;
            maxX = Math.max(maxX, ev.getX() + w);
            maxY = Math.max(maxY, ev.getY() + h);
        }
        canvasWidth  = maxX + padding * 2;
        canvasHeight = maxY + padding * 2;

        writer.writeLine(String.format(
            "<svg xmlns=\"http://www.w3.org/2000/svg\" " +
            "xmlns:xlink=\"http://www.w3.org/1999/xlink\" " +
            "version=\"1.1\" width=\"%d\" height=\"%d\" " +
            "viewBox=\"0 0 %d %d\">",
            canvasWidth, canvasHeight, canvasWidth, canvasHeight
        ));

        // Background rect
        String bg = resolveBackground(view);
        writer.writeLine(String.format(
            "<rect width=\"%d\" height=\"%d\" fill=\"%s\"/>",
            canvasWidth, canvasHeight, bg
        ));

        // Defs: arrowhead markers
        writer.writeLine(Connectors.DEFS_BLOCK);

        // Open <g> with padding offset
        writer.writeLine(String.format("<g transform=\"translate(%d,%d)\">", padding, padding));
    }

    @Override
    protected void writeFooter(View view, IndentingWriter writer) {
        writer.writeLine("</g>");
        writer.writeLine("</svg>");
    }

    @Override
    protected void writeElement(View view, Element element, IndentingWriter writer) {
        ElementView ev = view.getElement(element.getId());
        ElementStyle style = StyleResolver.resolveElementStyle(view, element, getWorkspace());

        int x = ev.getX(), y = ev.getY();
        int w = style.getWidth() != null ? style.getWidth() : 450;
        int h = style.getHeight() != null ? style.getHeight() : 300;

        String svg = Shapes.render(element, style, x, y, w, h);
        writer.writeLine(svg);
    }

    @Override
    protected void writeRelationship(View view, RelationshipView rv, IndentingWriter writer) {
        Relationship rel = rv.getRelationship();
        ElementView srcEv = view.getElement(rel.getSourceId());
        ElementView dstEv = view.getElement(rel.getDestinationId());
        if (srcEv == null || dstEv == null) return;

        RelationshipStyle style = StyleResolver.resolveRelationshipStyle(view, rel, getWorkspace());
        String svg = Connectors.render(rv, srcEv, dstEv, style, view);
        writer.writeLine(svg);
    }

    // Boundary methods — draw a labeled rectangle around children:
    @Override
    protected void startGroupBoundary(View view, String groupName, IndentingWriter writer) {
        // Boundaries require computing the bounding box of all elements inside.
        // Defer to SvgWriter helper — see below.
        writer.writeLine("<!-- group: " + groupName + " -->");
        // Actual boundary rect is drawn AFTER children (use a two-pass approach or SVG layering)
    }

    @Override
    protected void endGroupBoundary(View view, IndentingWriter writer) {
        writer.writeLine("<!-- /group -->");
    }

    // Similarly for software system, container, deployment node boundaries.
    // See "Boundary rendering" section below for the two-pass approach.
}
```

---

## Step 4 — Style resolution

### `StyleResolver.java`

This replicates the `findElementStyle` / `findRelationshipStyle` logic from
`structurizr-ui.js`. The workspace model exposes styles via
`workspace.getViews().getConfiguration().getStyles()` and themes are pre-merged by the
DSL parser (if `!include` or `theme` directives are used).

```java
package com.structurizr.renderer.svg;

import com.structurizr.Workspace;
import com.structurizr.model.Element;
import com.structurizr.model.Relationship;
import com.structurizr.util.TagUtils;
import com.structurizr.view.*;

import java.util.LinkedHashMap;
import java.util.Map;

public class StyleResolver {

    // Default element style values (from structurizr-ui.js)
    static final int    DEFAULT_WIDTH        = 450;
    static final int    DEFAULT_HEIGHT       = 300;
    static final int    DEFAULT_FONT_SIZE    = 24;
    static final int    DEFAULT_OPACITY      = 100;
    static final String DEFAULT_FONT         = "Tahoma, Verdana, Helvetica, Arial";
    static final String DEFAULT_BACKGROUND   = "#dddddd";
    static final String DEFAULT_COLOR        = "#444444";
    static final int    DEFAULT_STROKE_WIDTH = 2;
    static final Shape  DEFAULT_SHAPE        = Shape.Box;

    // Person/Robot default size
    static final int PERSON_DEFAULT_WIDTH  = 400;
    static final int PERSON_DEFAULT_HEIGHT = 400;

    // Default relationship style values
    static final int    DEFAULT_REL_THICKNESS  = 2;
    static final int    DEFAULT_REL_FONT_SIZE  = 24;
    static final int    DEFAULT_REL_WIDTH      = 200;
    static final int    DEFAULT_REL_POSITION   = 50;
    static final String DEFAULT_REL_COLOR      = "#707070";
    static final boolean DEFAULT_REL_DASHED    = true;
    static final Routing DEFAULT_REL_ROUTING   = Routing.Direct;

    public static ElementStyle resolveElementStyle(View view, Element element, Workspace workspace) {
        Styles styles = workspace.getViews().getConfiguration().getStyles();

        // Start with hard-coded defaults
        ElementStyle result = new ElementStyle();
        result.setBackground(DEFAULT_BACKGROUND);
        result.setColor(DEFAULT_COLOR);
        result.setFontSize(DEFAULT_FONT_SIZE);
        result.setOpacity(DEFAULT_OPACITY);
        result.setWidth(DEFAULT_WIDTH);
        result.setHeight(DEFAULT_HEIGHT);
        result.setShape(DEFAULT_SHAPE);
        result.setStrokeWidth(DEFAULT_STROKE_WIDTH);

        // Build a tag → style map (themes already merged by DSL parser)
        // Apply in element tag order — last write wins per property
        for (String tag : TagUtils.getTagsAsSet(element.getTags())) {
            ElementStyle tagStyle = styles.findElementStyle(tag);
            if (tagStyle != null) {
                mergeElementStyle(result, tagStyle);
            }
        }

        // If background was set but stroke was not, derive stroke as shadeColor(background, -10)
        if (result.getStroke() == null && result.getBackground() != null) {
            result.setStroke(shadeColor(result.getBackground(), -10));
        }

        // Person/Robot default size override
        if (result.getShape() == Shape.Person || result.getShape() == Shape.Robot) {
            if (result.getWidth()  == DEFAULT_WIDTH)  result.setWidth(PERSON_DEFAULT_WIDTH);
            if (result.getHeight() == DEFAULT_HEIGHT) result.setHeight(PERSON_DEFAULT_HEIGHT);
        }

        return result;
    }

    public static RelationshipStyle resolveRelationshipStyle(View view, Relationship rel, Workspace workspace) {
        Styles styles = workspace.getViews().getConfiguration().getStyles();

        RelationshipStyle result = new RelationshipStyle();
        result.setThickness(DEFAULT_REL_THICKNESS);
        result.setColor(DEFAULT_REL_COLOR);
        result.setFontSize(DEFAULT_REL_FONT_SIZE);
        result.setWidth(DEFAULT_REL_WIDTH);
        result.setPosition(DEFAULT_REL_POSITION);
        result.setDashed(DEFAULT_REL_DASHED);
        result.setRouting(DEFAULT_REL_ROUTING);

        for (String tag : TagUtils.getTagsAsSet(rel.getTags())) {
            RelationshipStyle tagStyle = styles.findRelationshipStyle(tag);
            if (tagStyle != null) {
                mergeRelationshipStyle(result, tagStyle);
            }
        }

        return result;
    }

    private static void mergeElementStyle(ElementStyle base, ElementStyle override) {
        if (override.getBackground()  != null) base.setBackground(override.getBackground());
        if (override.getColor()       != null) base.setColor(override.getColor());
        if (override.getStroke()      != null) base.setStroke(override.getStroke());
        if (override.getFontSize()    != null) base.setFontSize(override.getFontSize());
        if (override.getOpacity()     != null) base.setOpacity(override.getOpacity());
        if (override.getWidth()       != null) base.setWidth(override.getWidth());
        if (override.getHeight()      != null) base.setHeight(override.getHeight());
        if (override.getShape()       != null) base.setShape(override.getShape());
        if (override.getStrokeWidth() != null) base.setStrokeWidth(override.getStrokeWidth());
        if (override.getBorder()      != null) base.setBorder(override.getBorder());
        if (override.getFontFamily()  != null) base.setFontFamily(override.getFontFamily());
        if (override.getIcon()        != null) base.setIcon(override.getIcon());
        if (override.getMetadata()    != null) base.setMetadata(override.getMetadata());
        if (override.getDescription() != null) base.setDescription(override.getDescription());
    }

    private static void mergeRelationshipStyle(RelationshipStyle base, RelationshipStyle override) {
        if (override.getThickness() != null) base.setThickness(override.getThickness());
        if (override.getColor()     != null) base.setColor(override.getColor());
        if (override.getFontSize()  != null) base.setFontSize(override.getFontSize());
        if (override.getWidth()     != null) base.setWidth(override.getWidth());
        if (override.getPosition()  != null) base.setPosition(override.getPosition());
        if (override.getDashed()    != null) base.setDashed(override.getDashed());
        if (override.getRouting()   != null) base.setRouting(override.getRouting());
    }

    /**
     * Port of the JS shadeColor(color, percent) function.
     * percent = -10 means 10% darker.
     */
    static String shadeColor(String hex, int percent) {
        try {
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
}
```

---

## Step 5 — Shape rendering

### `Shapes.java`

All geometry is derived from `structurizr-diagram.js`. Each shape returns a complete SVG
`<g>` element containing the shape geometry plus label text.

The text label rendering is common to all shapes:

```java
package com.structurizr.renderer.svg;

import com.structurizr.model.Element;
import com.structurizr.view.ElementStyle;
import com.structurizr.view.Shape;

public class Shapes {

    public static String render(Element element, ElementStyle style, int x, int y, int w, int h) {
        return switch (style.getShape()) {
            case Box          -> renderBox(element, style, x, y, w, h, 0);
            case RoundedBox   -> renderBox(element, style, x, y, w, h, 15);
            case Circle       -> renderCircle(element, style, x, y, w, h);
            case Ellipse      -> renderEllipse(element, style, x, y, w, h);
            case Hexagon      -> renderHexagon(element, style, x, y, w, h);
            case Diamond      -> renderDiamond(element, style, x, y, w, h);
            case Person       -> renderPerson(element, style, x, y, w, h);
            case Robot        -> renderRobot(element, style, x, y, w, h);
            case Cylinder      -> renderCylinder(element, style, x, y, w, h);
            case Pipe         -> renderPipe(element, style, x, y, w, h);
            case Component    -> renderComponent(element, style, x, y, w, h);
            case Folder       -> renderFolder(element, style, x, y, w, h);
            case WebBrowser   -> renderWebBrowser(element, style, x, y, w, h);
            case Window       -> renderWindow(element, style, x, y, w, h);
            case MobileDeviceLandscape -> renderMobileDeviceLandscape(element, style, x, y, w, h);
            case MobileDevicePortrait  -> renderMobileDevicePortrait(element, style, x, y, w, h);
            default           -> renderBox(element, style, x, y, w, h, 0);
        };
    }

    // -------------------------------------------------------------------------
    // Helper: render wrapped text in a box
    // -------------------------------------------------------------------------
    private static String renderText(Element element, ElementStyle style, int x, int y, int w, int h,
                                     int textAreaY, int textAreaH) {
        String name = element.getName();
        String description = element.getDescription();
        String type = getTypeName(element);

        int fontSize = style.getFontSize() != null ? style.getFontSize() : 24;
        String color = style.getColor() != null ? style.getColor() : "#444444";
        String fontFamily = StyleResolver.DEFAULT_FONT;

        StringBuilder sb = new StringBuilder();

        // <<type>> line (smaller, italic)
        int typeY = textAreaY + fontSize + 4;
        sb.append(String.format(
            "<text x=\"%d\" y=\"%d\" text-anchor=\"middle\" " +
            "font-family=\"%s\" font-size=\"%d\" font-style=\"italic\" fill=\"%s\">%s</text>\n",
            x + w / 2, typeY, fontFamily, (int)(fontSize * 0.75), color,
            "&lt;&lt;" + htmlEscape(type) + "&gt;&gt;"
        ));

        // Name line (bold)
        int nameY = typeY + fontSize + 4;
        sb.append(String.format(
            "<text x=\"%d\" y=\"%d\" text-anchor=\"middle\" " +
            "font-family=\"%s\" font-size=\"%d\" font-weight=\"bold\" fill=\"%s\">%s</text>\n",
            x + w / 2, nameY, fontFamily, fontSize, color, htmlEscape(name)
        ));

        // Description (smaller, below name, line-wrapped)
        if (description != null && !description.isEmpty()) {
            int descY = nameY + fontSize + 8;
            int descFontSize = (int)(fontSize * 0.75);
            for (String line : wrapText(description, w - 20, descFontSize)) {
                sb.append(String.format(
                    "<text x=\"%d\" y=\"%d\" text-anchor=\"middle\" " +
                    "font-family=\"%s\" font-size=\"%d\" fill=\"%s\">%s</text>\n",
                    x + w / 2, descY, fontFamily, descFontSize, color, htmlEscape(line)
                ));
                descY += descFontSize + 4;
            }
        }

        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Box / RoundedBox
    // rx = 0 for Box, rx = 15 for RoundedBox
    // -------------------------------------------------------------------------
    private static String renderBox(Element element, ElementStyle style, int x, int y, int w, int h, int rx) {
        String bg     = style.getBackground() != null ? style.getBackground() : "#dddddd";
        String stroke = style.getStroke()     != null ? style.getStroke()     : StyleResolver.shadeColor(bg, -10);
        int sw        = style.getStrokeWidth() != null ? style.getStrokeWidth() : 2;

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("<g id=\"%s\">\n", htmlEscape(element.getId())));

        // Shadow (slightly offset filled rect, no stroke)
        sb.append(String.format(
            "<rect x=\"%d\" y=\"%d\" width=\"%d\" height=\"%d\" rx=\"%d\" ry=\"%d\" " +
            "fill=\"#c8c8c8\" opacity=\"0.5\"/>\n",
            x + 4, y + 4, w, h, rx, rx
        ));

        // Main rect
        sb.append(String.format(
            "<rect x=\"%d\" y=\"%d\" width=\"%d\" height=\"%d\" rx=\"%d\" ry=\"%d\" " +
            "fill=\"%s\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            x, y, w, h, rx, rx, bg, stroke, sw
        ));

        // Text
        sb.append(renderText(element, style, x, y, w, h, y, h));

        sb.append("</g>\n");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Person
    // Geometry from structurizr-diagram.js createPerson():
    //   head: circle cx=w/2, cy=w/4.5, r=w/4.5
    //   body: rect at y=w/2.5, height=h*0.3, width=w*0.7, centered
    //   legs: two lines from body bottom to w*0.2/w*0.8 at y=h
    //   arms: two lines from body top to x=0/w at y=w/2
    // -------------------------------------------------------------------------
    private static String renderPerson(Element element, ElementStyle style, int x, int y, int w, int h) {
        String bg     = style.getBackground() != null ? style.getBackground() : "#08427b";
        String stroke = style.getStroke()     != null ? style.getStroke()     : StyleResolver.shadeColor(bg, -10);
        int sw        = style.getStrokeWidth() != null ? style.getStrokeWidth() : 2;
        String color  = style.getColor()      != null ? style.getColor()      : "#ffffff";

        double headR  = w / 4.5;
        double headCx = x + w / 2.0;
        double headCy = y + headR;

        double bodyW  = w * 0.7;
        double bodyH  = h * 0.3;
        double bodyX  = x + (w - bodyW) / 2.0;
        double bodyY  = y + h / 2.5;

        // leg endpoints
        double legTopY  = bodyY + bodyH;
        double legBotY  = y + h;
        double legLX    = x + w * 0.2;
        double legRX    = x + w * 0.8;
        double legTopLX = bodyX + bodyW * 0.25;
        double legTopRX = bodyX + bodyW * 0.75;

        // arm endpoints
        double armY   = bodyY + bodyH * 0.3;
        double armLX  = x;
        double armRX  = x + w;

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("<g id=\"%s\">\n", htmlEscape(element.getId())));

        // Head
        sb.append(String.format(
            "<circle cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            headCx, headCy, headR, bg, stroke, sw
        ));

        // Body
        sb.append(String.format(
            "<rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            bodyX, bodyY, bodyW, bodyH, bg, stroke, sw
        ));

        // Legs
        sb.append(String.format(
            "<line x1=\"%.1f\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            legTopLX, legTopY, legLX, legBotY, stroke, sw
        ));
        sb.append(String.format(
            "<line x1=\"%.1f\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            legTopRX, legTopY, legRX, legBotY, stroke, sw
        ));

        // Arms
        sb.append(String.format(
            "<line x1=\"%.1f\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            armLX, armY, bodyX, armY, stroke, sw
        ));
        sb.append(String.format(
            "<line x1=\"%.1f\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            bodyX + bodyW, armY, armRX, armY, stroke, sw
        ));

        // Name label (centered below body)
        sb.append(renderPersonText(element, style, x, y, w, h, color));

        sb.append("</g>\n");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Cylinder
    // From structurizr-diagram.js createCylinder():
    //   Top ellipse: rx=w/2, ry=60 (capped to element height)
    //   Rect body connecting top and bottom ellipses
    //   Bottom ellipse arc (open top)
    // -------------------------------------------------------------------------
    private static String renderCylinder(Element element, ElementStyle style, int x, int y, int w, int h) {
        String bg     = style.getBackground() != null ? style.getBackground() : "#dddddd";
        String stroke = style.getStroke()     != null ? style.getStroke()     : StyleResolver.shadeColor(bg, -10);
        int sw        = style.getStrokeWidth() != null ? style.getStrokeWidth() : 2;

        int rx = w / 2;
        int ry = Math.min(60, h / 4);  // cap ry so it doesn't overflow small shapes

        // Top ellipse center
        int cx = x + w / 2;
        int topCy = y + ry;

        // Body rect (from top ellipse center to bottom ellipse center)
        int bodyY = topCy;
        int bodyH = h - 2 * ry;

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("<g id=\"%s\">\n", htmlEscape(element.getId())));

        // Body rect (drawn first so top/bottom ellipses overlap it cleanly)
        sb.append(String.format(
            "<rect x=\"%d\" y=\"%d\" width=\"%d\" height=\"%d\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            x, bodyY, w, bodyH, bg, stroke, sw
        ));

        // Top ellipse (solid fill)
        sb.append(String.format(
            "<ellipse cx=\"%d\" cy=\"%d\" rx=\"%d\" ry=\"%d\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            cx, topCy, rx, ry, StyleResolver.shadeColor(bg, 10), stroke, sw
        ));

        // Bottom ellipse (open arc — just the lower half)
        int botCy = y + h - ry;
        sb.append(String.format(
            "<path d=\"M %d %d a %d %d 0 0 0 %d 0\" fill=\"none\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            x, botCy, rx, ry, w, stroke, sw
        ));

        // Text (centered vertically in body)
        sb.append(renderText(element, style, x, bodyY, w, bodyH, bodyY, bodyH));

        sb.append("</g>\n");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Hexagon
    // Regular flat-top hexagon inscribed in the bounding box.
    // Points (flat-top): computed from center + half-width/half-height
    // -------------------------------------------------------------------------
    private static String renderHexagon(Element element, ElementStyle style, int x, int y, int w, int h) {
        String bg     = style.getBackground() != null ? style.getBackground() : "#dddddd";
        String stroke = style.getStroke()     != null ? style.getStroke()     : StyleResolver.shadeColor(bg, -10);
        int sw        = style.getStrokeWidth() != null ? style.getStrokeWidth() : 2;

        double cx = x + w / 2.0;
        double cy = y + h / 2.0;
        double hw = w / 2.0;  // half-width
        double hh = h / 2.0;  // half-height

        // Flat-top hexagon points
        String points = String.format(
            "%.1f,%.1f %.1f,%.1f %.1f,%.1f %.1f,%.1f %.1f,%.1f %.1f,%.1f",
            cx - hw, cy,
            cx - hw * 0.5, cy - hh,
            cx + hw * 0.5, cy - hh,
            cx + hw, cy,
            cx + hw * 0.5, cy + hh,
            cx - hw * 0.5, cy + hh
        );

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("<g id=\"%s\">\n", htmlEscape(element.getId())));
        sb.append(String.format(
            "<polygon points=\"%s\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            points, bg, stroke, sw
        ));
        sb.append(renderText(element, style, x, y, w, h, y, h));
        sb.append("</g>\n");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Diamond
    // -------------------------------------------------------------------------
    private static String renderDiamond(Element element, ElementStyle style, int x, int y, int w, int h) {
        String bg     = style.getBackground() != null ? style.getBackground() : "#dddddd";
        String stroke = style.getStroke()     != null ? style.getStroke()     : StyleResolver.shadeColor(bg, -10);
        int sw        = style.getStrokeWidth() != null ? style.getStrokeWidth() : 2;

        String points = String.format(
            "%d,%d %d,%d %d,%d %d,%d",
            x + w / 2, y,
            x + w, y + h / 2,
            x + w / 2, y + h,
            x, y + h / 2
        );

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("<g id=\"%s\">\n", htmlEscape(element.getId())));
        sb.append(String.format(
            "<polygon points=\"%s\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            points, bg, stroke, sw
        ));
        sb.append(renderText(element, style, x, y, w, h, y, h));
        sb.append("</g>\n");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Circle
    // -------------------------------------------------------------------------
    private static String renderCircle(Element element, ElementStyle style, int x, int y, int w, int h) {
        String bg     = style.getBackground() != null ? style.getBackground() : "#dddddd";
        String stroke = style.getStroke()     != null ? style.getStroke()     : StyleResolver.shadeColor(bg, -10);
        int sw        = style.getStrokeWidth() != null ? style.getStrokeWidth() : 2;

        int r  = Math.min(w, h) / 2;
        int cx = x + w / 2;
        int cy = y + h / 2;

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("<g id=\"%s\">\n", htmlEscape(element.getId())));
        sb.append(String.format(
            "<circle cx=\"%d\" cy=\"%d\" r=\"%d\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            cx, cy, r, bg, stroke, sw
        ));
        sb.append(renderText(element, style, x, y, w, h, y, h));
        sb.append("</g>\n");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Ellipse
    // -------------------------------------------------------------------------
    private static String renderEllipse(Element element, ElementStyle style, int x, int y, int w, int h) {
        String bg     = style.getBackground() != null ? style.getBackground() : "#dddddd";
        String stroke = style.getStroke()     != null ? style.getStroke()     : StyleResolver.shadeColor(bg, -10);
        int sw        = style.getStrokeWidth() != null ? style.getStrokeWidth() : 2;

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("<g id=\"%s\">\n", htmlEscape(element.getId())));
        sb.append(String.format(
            "<ellipse cx=\"%d\" cy=\"%d\" rx=\"%d\" ry=\"%d\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            x + w / 2, y + h / 2, w / 2, h / 2, bg, stroke, sw
        ));
        sb.append(renderText(element, style, x, y, w, h, y, h));
        sb.append("</g>\n");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Component — Box with two small "plug" rectangles on the left side
    // From structurizr-diagram.js createComponent()
    // -------------------------------------------------------------------------
    private static String renderComponent(Element element, ElementStyle style, int x, int y, int w, int h) {
        String bg     = style.getBackground() != null ? style.getBackground() : "#dddddd";
        String stroke = style.getStroke()     != null ? style.getStroke()     : StyleResolver.shadeColor(bg, -10);
        int sw        = style.getStrokeWidth() != null ? style.getStrokeWidth() : 2;

        int notchW = (int)(w * 0.15);
        int notchH = (int)(h * 0.15);
        int notchX = x - notchW / 2;

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("<g id=\"%s\">\n", htmlEscape(element.getId())));

        // Main box
        sb.append(String.format(
            "<rect x=\"%d\" y=\"%d\" width=\"%d\" height=\"%d\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            x, y, w, h, bg, stroke, sw
        ));

        // Upper notch
        sb.append(String.format(
            "<rect x=\"%d\" y=\"%d\" width=\"%d\" height=\"%d\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            notchX, y + h / 4 - notchH / 2, notchW, notchH, bg, stroke, sw
        ));

        // Lower notch
        sb.append(String.format(
            "<rect x=\"%d\" y=\"%d\" width=\"%d\" height=\"%d\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            notchX, y + 3 * h / 4 - notchH / 2, notchW, notchH, bg, stroke, sw
        ));

        sb.append(renderText(element, style, x, y, w, h, y, h));
        sb.append("</g>\n");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Folder, WebBrowser, Window, MobileDevice* — all are Box variants with
    // a small decorative element at the top. Implement as Box + decoration.
    // -------------------------------------------------------------------------
    private static String renderFolder(Element element, ElementStyle style, int x, int y, int w, int h) {
        String bg     = style.getBackground() != null ? style.getBackground() : "#dddddd";
        String stroke = style.getStroke()     != null ? style.getStroke()     : StyleResolver.shadeColor(bg, -10);
        int sw        = style.getStrokeWidth() != null ? style.getStrokeWidth() : 2;
        int tabH      = (int)(h * 0.12);
        int tabW      = (int)(w * 0.4);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("<g id=\"%s\">\n", htmlEscape(element.getId())));
        // Tab
        sb.append(String.format(
            "<rect x=\"%d\" y=\"%d\" width=\"%d\" height=\"%d\" rx=\"4\" ry=\"4\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            x, y, tabW, tabH, bg, stroke, sw
        ));
        // Body
        sb.append(String.format(
            "<rect x=\"%d\" y=\"%d\" width=\"%d\" height=\"%d\" rx=\"0\" ry=\"0\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            x, y + tabH, w, h - tabH, bg, stroke, sw
        ));
        sb.append(renderText(element, style, x, y + tabH, w, h - tabH, y + tabH, h - tabH));
        sb.append("</g>\n");
        return sb.toString();
    }

    private static String renderWebBrowser(Element element, ElementStyle style, int x, int y, int w, int h) {
        // Browser chrome: rect with address bar at top
        String bg     = style.getBackground() != null ? style.getBackground() : "#dddddd";
        String stroke = style.getStroke()     != null ? style.getStroke()     : StyleResolver.shadeColor(bg, -10);
        int sw        = style.getStrokeWidth() != null ? style.getStrokeWidth() : 2;
        int barH      = (int)(h * 0.12);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("<g id=\"%s\">\n", htmlEscape(element.getId())));
        // Outer box
        sb.append(String.format(
            "<rect x=\"%d\" y=\"%d\" width=\"%d\" height=\"%d\" rx=\"6\" ry=\"6\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            x, y, w, h, bg, stroke, sw
        ));
        // Address bar divider
        sb.append(String.format(
            "<line x1=\"%d\" y1=\"%d\" x2=\"%d\" y2=\"%d\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            x, y + barH, x + w, y + barH, stroke, sw
        ));
        // Address bar oval
        sb.append(String.format(
            "<rect x=\"%d\" y=\"%d\" width=\"%d\" height=\"%d\" rx=\"%d\" ry=\"%d\" fill=\"none\" stroke=\"%s\" stroke-width=\"1\"/>\n",
            x + (int)(w * 0.2), y + (int)(barH * 0.2), (int)(w * 0.6), (int)(barH * 0.6),
            (int)(barH * 0.3), (int)(barH * 0.3), stroke
        ));
        sb.append(renderText(element, style, x, y + barH, w, h - barH, y + barH, h - barH));
        sb.append("</g>\n");
        return sb.toString();
    }

    private static String renderWindow(Element element, ElementStyle style, int x, int y, int w, int h) {
        String bg     = style.getBackground() != null ? style.getBackground() : "#dddddd";
        String stroke = style.getStroke()     != null ? style.getStroke()     : StyleResolver.shadeColor(bg, -10);
        int sw        = style.getStrokeWidth() != null ? style.getStrokeWidth() : 2;
        int barH      = (int)(h * 0.1);
        int dotR      = Math.max(3, barH / 4);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("<g id=\"%s\">\n", htmlEscape(element.getId())));
        // Outer box
        sb.append(String.format(
            "<rect x=\"%d\" y=\"%d\" width=\"%d\" height=\"%d\" rx=\"6\" ry=\"6\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            x, y, w, h, bg, stroke, sw
        ));
        // Title bar divider
        sb.append(String.format(
            "<line x1=\"%d\" y1=\"%d\" x2=\"%d\" y2=\"%d\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            x, y + barH, x + w, y + barH, stroke, sw
        ));
        // Three title-bar dots (macOS style)
        for (int i = 0; i < 3; i++) {
            sb.append(String.format(
                "<circle cx=\"%d\" cy=\"%d\" r=\"%d\" fill=\"%s\"/>\n",
                x + dotR * 2 + i * (dotR * 3), y + barH / 2, dotR, stroke
            ));
        }
        sb.append(renderText(element, style, x, y + barH, w, h - barH, y + barH, h - barH));
        sb.append("</g>\n");
        return sb.toString();
    }

    private static String renderMobileDevicePortrait(Element element, ElementStyle style, int x, int y, int w, int h) {
        return renderMobileDevice(element, style, x, y, w, h, false);
    }

    private static String renderMobileDeviceLandscape(Element element, ElementStyle style, int x, int y, int w, int h) {
        return renderMobileDevice(element, style, x, y, w, h, true);
    }

    private static String renderMobileDevice(Element element, ElementStyle style, int x, int y, int w, int h,
                                              boolean landscape) {
        String bg     = style.getBackground() != null ? style.getBackground() : "#dddddd";
        String stroke = style.getStroke()     != null ? style.getStroke()     : StyleResolver.shadeColor(bg, -10);
        int sw        = style.getStrokeWidth() != null ? style.getStrokeWidth() : 2;
        int corner    = 15;
        int bezel     = landscape ? (int)(h * 0.12) : (int)(w * 0.08);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("<g id=\"%s\">\n", htmlEscape(element.getId())));
        // Outer rounded rect (the phone)
        sb.append(String.format(
            "<rect x=\"%d\" y=\"%d\" width=\"%d\" height=\"%d\" rx=\"%d\" ry=\"%d\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            x, y, w, h, corner, corner, bg, stroke, sw
        ));
        // Screen area (inset)
        sb.append(String.format(
            "<rect x=\"%d\" y=\"%d\" width=\"%d\" height=\"%d\" fill=\"none\" stroke=\"%s\" stroke-width=\"1\"/>\n",
            x + bezel, y + bezel, w - 2 * bezel, h - 2 * bezel, stroke
        ));
        sb.append(renderText(element, style, x, y, w, h, y, h));
        sb.append("</g>\n");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Pipe (horizontal cylinder)
    // -------------------------------------------------------------------------
    private static String renderPipe(Element element, ElementStyle style, int x, int y, int w, int h) {
        String bg     = style.getBackground() != null ? style.getBackground() : "#dddddd";
        String stroke = style.getStroke()     != null ? style.getStroke()     : StyleResolver.shadeColor(bg, -10);
        int sw        = style.getStrokeWidth() != null ? style.getStrokeWidth() : 2;

        int ry = h / 2;
        int rx = Math.min(60, w / 4);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("<g id=\"%s\">\n", htmlEscape(element.getId())));

        // Body
        sb.append(String.format(
            "<rect x=\"%d\" y=\"%d\" width=\"%d\" height=\"%d\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            x + rx, y, w - 2 * rx, h, bg, stroke, sw
        ));

        // Left cap (ellipse)
        sb.append(String.format(
            "<ellipse cx=\"%d\" cy=\"%d\" rx=\"%d\" ry=\"%d\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            x + rx, y + ry, rx, ry, StyleResolver.shadeColor(bg, 10), stroke, sw
        ));

        // Right cap arc (open left half only)
        sb.append(String.format(
            "<path d=\"M %d %d a %d %d 0 0 1 0 %d\" fill=\"none\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            x + w - rx, y, rx, ry, h, stroke, sw
        ));

        sb.append(renderText(element, style, x + rx, y, w - 2 * rx, h, y, h));
        sb.append("</g>\n");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Robot — same as Person but with a square head
    // -------------------------------------------------------------------------
    private static String renderRobot(Element element, ElementStyle style, int x, int y, int w, int h) {
        String bg     = style.getBackground() != null ? style.getBackground() : "#dddddd";
        String stroke = style.getStroke()     != null ? style.getStroke()     : StyleResolver.shadeColor(bg, -10);
        int sw        = style.getStrokeWidth() != null ? style.getStrokeWidth() : 2;
        String color  = style.getColor()      != null ? style.getColor()      : "#444444";

        double headW  = w * 0.5;
        double headH  = h * 0.25;
        double headX  = x + (w - headW) / 2.0;
        double headY  = y;

        double bodyW  = w * 0.7;
        double bodyH  = h * 0.3;
        double bodyX  = x + (w - bodyW) / 2.0;
        double bodyY  = headY + headH + h * 0.05;

        // Antenna
        double antX   = x + w / 2.0;
        double antTopY = headY - h * 0.08;

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("<g id=\"%s\">\n", htmlEscape(element.getId())));

        // Antenna
        sb.append(String.format(
            "<line x1=\"%.1f\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            antX, headY, antX, antTopY, stroke, sw
        ));
        sb.append(String.format(
            "<circle cx=\"%.1f\" cy=\"%.1f\" r=\"%d\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            antX, antTopY, sw * 2, bg, stroke, sw
        ));

        // Head
        sb.append(String.format(
            "<rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"4\" ry=\"4\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            headX, headY, headW, headH, bg, stroke, sw
        ));

        // Eyes
        double eyeR  = headH * 0.15;
        double eyeY  = headY + headH / 2.0;
        sb.append(String.format(
            "<circle cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"%s\"/>\n",
            headX + headW * 0.3, eyeY, eyeR, color
        ));
        sb.append(String.format(
            "<circle cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"%s\"/>\n",
            headX + headW * 0.7, eyeY, eyeR, color
        ));

        // Body
        sb.append(String.format(
            "<rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            bodyX, bodyY, bodyW, bodyH, bg, stroke, sw
        ));

        // Legs (same as Person)
        double legTopY = bodyY + bodyH;
        double legBotY = y + h;
        sb.append(String.format(
            "<line x1=\"%.1f\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            bodyX + bodyW * 0.25, legTopY, x + w * 0.2, legBotY, stroke, sw
        ));
        sb.append(String.format(
            "<line x1=\"%.1f\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            bodyX + bodyW * 0.75, legTopY, x + w * 0.8, legBotY, stroke, sw
        ));

        // Arms
        sb.append(String.format(
            "<line x1=\"%.1f\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            x, bodyY + bodyH * 0.3, bodyX, bodyY + bodyH * 0.3, stroke, sw
        ));
        sb.append(String.format(
            "<line x1=\"%.1f\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f\" stroke=\"%s\" stroke-width=\"%d\"/>\n",
            bodyX + bodyW, bodyY + bodyH * 0.3, x + w, bodyY + bodyH * 0.3, stroke, sw
        ));

        sb.append(renderPersonText(element, style, x, y, w, h, color));
        sb.append("</g>\n");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------
    private static String renderPersonText(Element element, ElementStyle style,
                                            int x, int y, int w, int h, String color) {
        int fontSize = style.getFontSize() != null ? style.getFontSize() : 24;
        String fontFamily = StyleResolver.DEFAULT_FONT;
        String type = getTypeName(element);

        // Text below the figure, centered
        int textY = y + h - fontSize;
        StringBuilder sb = new StringBuilder();

        sb.append(String.format(
            "<text x=\"%d\" y=\"%d\" text-anchor=\"middle\" font-family=\"%s\" " +
            "font-size=\"%d\" font-style=\"italic\" fill=\"%s\">%s</text>\n",
            x + w / 2, textY, fontFamily, (int)(fontSize * 0.75), color,
            "&lt;&lt;" + htmlEscape(type) + "&gt;&gt;"
        ));

        textY += fontSize + 4;
        sb.append(String.format(
            "<text x=\"%d\" y=\"%d\" text-anchor=\"middle\" font-family=\"%s\" " +
            "font-size=\"%d\" font-weight=\"bold\" fill=\"%s\">%s</text>\n",
            x + w / 2, textY, fontFamily, fontSize, color, htmlEscape(element.getName())
        ));

        if (element.getDescription() != null && !element.getDescription().isEmpty()) {
            textY += fontSize + 4;
            int descFontSize = (int)(fontSize * 0.75);
            for (String line : wrapText(element.getDescription(), w - 10, descFontSize)) {
                sb.append(String.format(
                    "<text x=\"%d\" y=\"%d\" text-anchor=\"middle\" font-family=\"%s\" " +
                    "font-size=\"%d\" fill=\"%s\">%s</text>\n",
                    x + w / 2, textY, fontFamily, descFontSize, color, htmlEscape(line)
                ));
                textY += descFontSize + 4;
            }
        }

        return sb.toString();
    }

    private static String getTypeName(Element element) {
        return switch (element) {
            case Person p            -> "Person";
            case SoftwareSystem ss   -> "Software System";
            case Container c         -> "Container";
            case Component comp      -> "Component";
            case DeploymentNode dn   -> "Deployment Node";
            case InfrastructureNode in -> "Infrastructure Node";
            case ContainerInstance ci -> "Container Instance";
            default                  -> element.getClass().getSimpleName();
        };
    }

    static String htmlEscape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /**
     * Very simple word-wrap: split text into lines that fit within maxWidth pixels
     * at the given font size. Uses an approximation of 0.6 * fontSize per character.
     */
    static List<String> wrapText(String text, int maxWidth, int fontSize) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) return lines;

        double charWidth = fontSize * 0.6;
        int charsPerLine = Math.max(1, (int)(maxWidth / charWidth));

        String[] words = text.split("\\s+");
        StringBuilder line = new StringBuilder();
        for (String word : words) {
            if (line.length() + word.length() + 1 > charsPerLine && line.length() > 0) {
                lines.add(line.toString());
                line = new StringBuilder();
            }
            if (line.length() > 0) line.append(' ');
            line.append(word);
        }
        if (line.length() > 0) lines.add(line.toString());
        return lines;
    }
}
```

---

## Step 6 — Relationship connectors

### `Connectors.java`

```java
package com.structurizr.renderer.svg;

import com.structurizr.view.*;

public class Connectors {

    // Arrow marker defs — referenced as url(#arrow)
    public static final String DEFS_BLOCK =
        "<defs>" +
        "<marker id=\"arrow\" markerWidth=\"10\" markerHeight=\"7\" refX=\"9\" refY=\"3.5\" orient=\"auto\">" +
        "<polygon points=\"0 0, 10 3.5, 0 7\" fill=\"#707070\"/>" +
        "</marker>" +
        "<marker id=\"arrow-colored\" markerWidth=\"10\" markerHeight=\"7\" refX=\"9\" refY=\"3.5\" orient=\"auto\" fill=\"context-stroke\">" +
        "<polygon points=\"0 0, 10 3.5, 0 7\"/>" +
        "</marker>" +
        "</defs>";

    /**
     * Render a relationship as an SVG line/path with an arrowhead and label.
     *
     * Routing modes:
     *   Direct  — straight line from src center to dst center
     *   Curved  — quadratic bezier via midpoint
     *   Orthogonal — right-angle path (defer to Direct initially)
     */
    public static String render(RelationshipView rv, ElementView srcEv, ElementView dstEv,
                                RelationshipStyle style, View view) {
        Relationship rel = rv.getRelationship();

        // Get element centers (positions are top-left corners)
        int sw = srcEv.getWidth() > 0 ? srcEv.getWidth() : 450;
        int sh = srcEv.getHeight() > 0 ? srcEv.getHeight() : 300;
        int dw = dstEv.getWidth() > 0 ? dstEv.getWidth() : 450;
        int dh = dstEv.getHeight() > 0 ? dstEv.getHeight() : 300;

        double x1 = srcEv.getX() + sw / 2.0;
        double y1 = srcEv.getY() + sh / 2.0;
        double x2 = dstEv.getX() + dw / 2.0;
        double y2 = dstEv.getY() + dh / 2.0;

        // Shorten endpoints to element edges (avoid arrow disappearing inside shape)
        double[] p1 = clipToRect(x1, y1, x2, y2, srcEv.getX(), srcEv.getY(), sw, sh);
        double[] p2 = clipToRect(x2, y2, x1, y1, dstEv.getX(), dstEv.getY(), dw, dh);

        String color     = style.getColor()     != null ? style.getColor()     : "#707070";
        int    thickness = style.getThickness() != null ? style.getThickness() : 2;
        boolean dashed   = style.getDashed()    != null ? style.getDashed()    : true;
        Routing routing  = style.getRouting()   != null ? style.getRouting()   : Routing.Direct;

        String dashAttr = dashed ? " stroke-dasharray=\"8,4\"" : "";

        String pathD;
        double labelX, labelY;
        int position = style.getPosition() != null ? style.getPosition() : 50;

        if (routing == Routing.Curved) {
            // Quadratic bezier: control point is the midpoint offset perpendicularly
            double midX = (p1[0] + p2[0]) / 2;
            double midY = (p1[1] + p2[1]) / 2;
            double dx = p2[0] - p1[0];
            double dy = p2[1] - p1[1];
            double cpX = midX - dy * 0.2;
            double cpY = midY + dx * 0.2;
            pathD = String.format("M %.1f %.1f Q %.1f %.1f %.1f %.1f",
                p1[0], p1[1], cpX, cpY, p2[0], p2[1]);
            // Label at the control point
            labelX = cpX;
            labelY = cpY;
        } else {
            pathD = String.format("M %.1f %.1f L %.1f %.1f", p1[0], p1[1], p2[0], p2[1]);
            // Label at position% along the line
            double t = position / 100.0;
            labelX = p1[0] + t * (p2[0] - p1[0]);
            labelY = p1[1] + t * (p2[1] - p1[1]);
        }

        int fontSize  = style.getFontSize() != null ? style.getFontSize() : 24;
        int labelWidth = style.getWidth()   != null ? style.getWidth()   : 200;

        StringBuilder sb = new StringBuilder();

        // The arrow path
        sb.append(String.format(
            "<path d=\"%s\" fill=\"none\" stroke=\"%s\" stroke-width=\"%d\"%s " +
            "marker-end=\"url(#arrow-colored)\"/>\n",
            pathD, color, thickness, dashAttr
        ));

        // Relationship description label (if any)
        String description = rel.getDescription();
        if (description != null && !description.isEmpty()) {
            sb.append(String.format(
                "<text x=\"%.1f\" y=\"%.1f\" text-anchor=\"middle\" " +
                "font-family=\"%s\" font-size=\"%d\" fill=\"%s\">%s</text>\n",
                labelX, labelY - 6,
                StyleResolver.DEFAULT_FONT, fontSize, color,
                Shapes.htmlEscape(description)
            ));
        }

        // Technology label (smaller, below description)
        String technology = rel.getTechnology();
        if (technology != null && !technology.isEmpty()) {
            sb.append(String.format(
                "<text x=\"%.1f\" y=\"%.1f\" text-anchor=\"middle\" " +
                "font-family=\"%s\" font-size=\"%d\" font-style=\"italic\" fill=\"%s\">[%s]</text>\n",
                labelX, labelY + (int)(fontSize * 0.9),
                StyleResolver.DEFAULT_FONT, (int)(fontSize * 0.75), color,
                Shapes.htmlEscape(technology)
            ));
        }

        return sb.toString();
    }

    /**
     * Find the intersection of the line from (x1,y1) to (x2,y2) with the
     * axis-aligned rectangle at (rx,ry,rw,rh). Returns the intersection point
     * closest to (x1,y1). Falls back to (x1,y1) if the line doesn't intersect.
     */
    private static double[] clipToRect(double x1, double y1, double x2, double y2,
                                        int rx, int ry, int rw, int rh) {
        double dx = x2 - x1;
        double dy = y2 - y1;

        // Check each of the 4 edges
        double bestT = Double.MAX_VALUE;

        // Left edge: x = rx
        if (dx != 0) {
            double t = (rx - x1) / dx;
            if (t >= 0 && t <= 1) {
                double y = y1 + t * dy;
                if (y >= ry && y <= ry + rh) bestT = Math.min(bestT, t);
            }
            // Right edge: x = rx + rw
            t = (rx + rw - x1) / dx;
            if (t >= 0 && t <= 1) {
                double y = y1 + t * dy;
                if (y >= ry && y <= ry + rh) bestT = Math.min(bestT, t);
            }
        }
        if (dy != 0) {
            // Top edge: y = ry
            double t = (ry - y1) / dy;
            if (t >= 0 && t <= 1) {
                double x = x1 + t * dx;
                if (x >= rx && x <= rx + rw) bestT = Math.min(bestT, t);
            }
            // Bottom edge: y = ry + rh
            t = (ry + rh - y1) / dy;
            if (t >= 0 && t <= 1) {
                double x = x1 + t * dx;
                if (x >= rx && x <= rx + rw) bestT = Math.min(bestT, t);
            }
        }

        if (bestT < Double.MAX_VALUE) {
            return new double[]{x1 + bestT * dx, y1 + bestT * dy};
        }
        return new double[]{x1, y1};
    }
}
```

---

## Step 7 — Boundary rendering

### Two-pass approach

`AbstractDiagramExporter` calls `startXxxBoundary` before writing child elements and
`endXxxBoundary` after. The problem is that you don't know the bounding box until after
the children are laid out.

**Approach A (simpler)**: Collect child element positions within `startXxxBoundary` /
`endXxxBoundary`, compute the bounding box, and emit the boundary rect in `endXxxBoundary`.
Since SVG renders in document order (later elements drawn on top of earlier), emit the
boundary rect at `end` time but with a `z-index`-like trick — use `<defs>` + `<use>` to
draw it behind, or simply accept that the boundary rect is drawn after (and thus on top of)
the children, then make it `fill-opacity="0"` (transparent fill, just a stroke).

**Approach B (recommended)**: Maintain a stack of open boundaries. At `endXxxBoundary`,
pop the stack, compute the bounding box of all ElementViews whose IDs were seen since
`startXxxBoundary`, and write:

```svg
<rect x="..." y="..." width="..." height="..."
      fill="none" stroke="#cccccc" stroke-width="2" stroke-dasharray="8,4"
      rx="6" ry="6"/>
<text ...>Boundary Name</text>
```

The boundary should have `8px` padding around the contained elements. The label should
appear at the top-left inside the rect.

**Implementation sketch** (add to `SvgDiagramExporter`):

```java
private Deque<BoundaryState> boundaryStack = new ArrayDeque<>();

// Called at start
protected void startSoftwareSystemBoundary(View view, SoftwareSystem ss, IndentingWriter writer) {
    boundaryStack.push(new BoundaryState(ss.getName()));
}

// Called at end — now we can compute the bbox
protected void endSoftwareSystemBoundary(View view, IndentingWriter writer) {
    BoundaryState state = boundaryStack.pop();
    // compute bbox from state.elementIds using view.getElement(id).getX/Y/Width/Height
    // write rect + label
}

// In writeElement, register this element with the current boundary:
protected void writeElement(View view, Element element, IndentingWriter writer) {
    if (!boundaryStack.isEmpty()) {
        boundaryStack.peek().addElement(element.getId());
    }
    // ... rest of writeElement
}

private static class BoundaryState {
    String label;
    List<String> elementIds = new ArrayList<>();
    BoundaryState(String label) { this.label = label; }
    void addElement(String id) { elementIds.add(id); }
}
```

---

## Step 8 — PNG rendering

### `PngRenderer.java`

```java
package com.structurizr.renderer;

import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.PNGTranscoder;

import java.io.*;
import java.nio.charset.StandardCharsets;

public class PngRenderer {

    public static void render(String svgContent, File outputFile) throws Exception {
        PNGTranscoder transcoder = new PNGTranscoder();

        // High-DPI: render at 2x (192 DPI)
        // transcoder.addTranscodingHint(PNGTranscoder.KEY_PIXEL_UNIT_TO_MILLIMETER, 0.2645833f);

        byte[] svgBytes = svgContent.getBytes(StandardCharsets.UTF_8);
        try (InputStream in = new ByteArrayInputStream(svgBytes);
             OutputStream out = new FileOutputStream(outputFile)) {
            transcoder.transcode(new TranscoderInput(in), new TranscoderOutput(out));
        }
    }
}
```

---

## Step 9 — Test fixture

The canonical Structurizr test case is the "Big Bank" example from the official docs.

### `src/test/resources/fixtures/big-bank.dsl`

```
workspace "Big Bank plc" "This is an example workspace." {

    !docs workspace-docs
    !adrs workspace-adrs

    model {
        tags "Organisation" "Big Bank plc"

        customer = person "Personal Banking Customer" "A customer of the bank, with personal bank accounts."
        bbs = softwareSystem "Big Bank plc" "Stores all of the core banking information about customers, accounts, transactions, etc." {
            singlePageApplication = container "Single-Page Application" "Provides all of the Internet banking functionality to customers via their web browser." "JavaScript and Angular" {
                tags "Web Browser"
            }
            mobileApp = container "Mobile App" "Provides a limited subset of the Internet banking functionality to customers via their mobile device." "Xamarin" {
                tags "Mobile App"
            }
            webApplication = container "Web Application" "Delivers the static content and the Internet banking single page application." "Java and Spring MVC"
            apiApplication = container "API Application" "Provides Internet banking functionality via a JSON/HTTPS API." "Java and Spring MVC" {
                signinController = component "Sign In Controller" "Allows users to sign in to the Internet Banking System." "Spring MVC Rest Controller"
                accountsSummaryController = component "Accounts Summary Controller" "Provides customers with a summary of their bank accounts." "Spring MVC Rest Controller"
                resetPasswordController = component "Reset Password Controller" "Allows users to reset their passwords with a single use URL." "Spring MVC Rest Controller"
                securityComponent = component "Security Component" "Provides functionality related to signing in, changing passwords, etc." "Spring Bean"
                mainframeBankingSystemFacade = component "Mainframe Banking System Facade" "A facade onto the mainframe banking system." "Spring Bean"
                emailComponent = component "Email Component" "Sends emails to users." "Spring Bean"
            }
            database = container "Database" "Stores user registration information, hashed authentication credentials, access logs, etc." "Oracle Database Schema" {
                tags "Database"
            }
        }
        mainframe = softwareSystem "Mainframe Banking System" "Stores all of the core banking information about customers, accounts, transactions, etc." {
            tags "Existing System"
        }
        email = softwareSystem "Email System" "The internal Microsoft Exchange email system." {
            tags "Existing System"
        }

        customer -> bbs.singlePageApplication "Uses" "HTTPS"
        customer -> bbs.mobileApp "Uses"
        customer -> bbs.webApplication "Uses" "HTTPS"
        bbs.webApplication -> bbs.singlePageApplication "Delivers"
        bbs.singlePageApplication -> bbs.apiApplication "Makes API calls to" "JSON/HTTPS"
        bbs.mobileApp -> bbs.apiApplication "Makes API calls to" "JSON/HTTPS"
        bbs.apiApplication -> bbs.database "Reads from and writes to" "JDBC"
        bbs.apiApplication -> mainframe "Makes API calls to" "XML/HTTPS"
        bbs.apiApplication -> email "Sends emails using" "SMTP"
        email -> customer "Sends emails to"
    }

    views {
        systemContext bbs "SystemContext" {
            include *
            autoLayout
        }

        container bbs "Containers" {
            include *
            autoLayout
        }

        component bbs.apiApplication "Components" {
            include *
            autoLayout
        }

        styles {
            element "Person" {
                background #08427b
                color #ffffff
                shape Person
            }
            element "Software System" {
                background #1168bd
                color #ffffff
            }
            element "Existing System" {
                background #999999
                color #ffffff
            }
            element "Container" {
                background #438dd5
                color #ffffff
            }
            element "Web Browser" {
                shape WebBrowser
            }
            element "Mobile App" {
                shape MobileDeviceLandscape
            }
            element "Database" {
                shape Cylinder
            }
            element "Component" {
                background #85bbf0
                color #000000
            }
        }
    }
}
```

### Test: `SvgDiagramExporterTest.java`

```java
@Test
void rendersBigBankSystemContext() throws Exception {
    File dsl = new File("src/test/resources/fixtures/big-bank.dsl");
    StructurizrDslParser parser = new StructurizrDslParser();
    parser.parse(dsl);
    Workspace workspace = parser.getWorkspace();

    new LayoutStrategyFactory().create().applyLayout(workspace);

    SvgDiagramExporter exporter = new SvgDiagramExporter();
    Collection<Diagram> diagrams = exporter.export(workspace);

    // Should produce 3 diagrams
    assertEquals(3, diagrams.size());

    // Each SVG should be well-formed and non-empty
    for (Diagram d : diagrams) {
        String svg = d.getDefinition();
        assertTrue(svg.contains("<svg"), d.getKey() + " should contain <svg>");
        assertTrue(svg.contains("</svg>"), d.getKey() + " should contain </svg>");

        // Write to target/test-output/ for manual visual inspection
        Path out = Path.of("target/test-output/" + d.getKey() + ".svg");
        Files.createDirectories(out.getParent());
        Files.writeString(out, svg);
    }
}
```

---

## Step 10 — Build and run

### Build

```bash
cd /mnt/c/work/structurizr-renderer
mvn package -DskipTests   # builds target/structurizr-renderer-1.0.0-SNAPSHOT.jar
```

### Run

```bash
# Render all views to SVG
java -jar target/structurizr-renderer-1.0.0-SNAPSHOT.jar workspace.dsl

# Render to PNG
java -jar target/structurizr-renderer-1.0.0-SNAPSHOT.jar workspace.dsl -f png

# Render both
java -jar target/structurizr-renderer-1.0.0-SNAPSHOT.jar workspace.dsl -f both

# Single named view, custom output dir
java -jar target/structurizr-renderer-1.0.0-SNAPSHOT.jar workspace.dsl -v SystemContext -o ./out
```

---

## Implementation order

Work through these in order. Each step is independently testable.

1. **Bootstrap**: Create `pom.xml`, `Main.java`, `RenderCommand.java`. Verify `mvn package` compiles.
2. **Parse only**: Add DSL parsing in `RenderCommand`. Print view names to stdout. Test with `big-bank.dsl`.
3. **Layout**: Implement `LayoutStrategyFactory`, `GraphvizLayoutStrategy`. Verify that element x/y are set after calling `applyLayout`.
4. **Flat SVG**: Implement `SvgDiagramExporter` without boundary support. Implement `StyleResolver`, `Shapes`, `Connectors`. Render `SystemContext` view — should produce a valid SVG with positioned shapes and arrows.
5. **Boundaries**: Add boundary stack to `SvgDiagramExporter`. Test with `Containers` view (which has a software system boundary).
6. **PNG**: Add `PngRenderer`, wire into `RenderCommand`.
7. **ELK fallback**: Implement `ElkLayoutStrategy`.
8. **Tests**: Write `SvgDiagramExporterTest`, generate outputs to `target/test-output/`, visually inspect.
9. **Polish**: Handle edge cases — elements with no explicit position after layout, relationships with `rv.getVertices()`, long descriptions, deployment node nesting.

---

## Known limitations to handle explicitly

- **Manual layout (no `autoLayout` in DSL)**: Views that don't use `autoLayout` already have
  x/y positions set by the DSL parser (from the `!include` or inline coordinate syntax).
  These should pass through untouched — `LayoutStrategy.applyLayout` should only touch views
  where `view.getAutomaticLayout() != null`.

- **Themes**: The DSL `theme default` directive fetches
  `https://static.structurizr.com/themes/default/theme.json` at parse time. This is an HTTP
  call inside the DSL parser. If the environment has no internet access, parsing will fail or
  produce unstyled output. Mitigation: bundle a local copy of the default theme JSON and
  pre-populate it before parsing. The `StructurizrDslParser` has a `setTheme()` method or
  equivalent — check the API; alternatively, inline the theme styles in the test fixture.

- **Element icons**: `style.getIcon()` may return a URL (e.g. data URI or `https://...`).
  Skip icons in v1 — they are not critical for correct rendering.

- **Dynamic views**: Numbered sequence steps — render relationships as in other views but
  prepend the step number to the relationship label.

- **Filtered views**: These are views defined with `filtered`. `AbstractDiagramExporter`
  handles filtered views automatically — no special-casing needed.

- **Deployment diagrams**: Container instances inside deployment nodes require boundary
  rendering (Step 7). Deployment node hierarchy can be deep — the boundary stack must handle
  arbitrary nesting depth.

- **Text wrapping**: The simple character-count approximation in `Shapes.wrapText` will
  produce slightly different wrapping than the browser renderer (which uses actual font
  metrics). This is acceptable for v1.

---

## Reference source files

All of these are in `/mnt/c/work/structurizr/`:

| File | Purpose |
|---|---|
| `structurizr-application/src/main/resources/static/static/js/structurizr-diagram.js` | All shape geometry (createBox, createPerson, createCylinder, etc.) |
| `structurizr-application/src/main/resources/static/static/js/structurizr-ui.js` | All default style constants, `findElementStyle`, `findRelationshipStyle` |
| `structurizr-export/src/main/java/com/structurizr/export/AbstractDiagramExporter.java` | Base class to extend; read all abstract methods and their javadoc |
| `structurizr-export/src/main/java/com/structurizr/export/plantuml/StructurizrPlantUMLExporter.java` | Reference implementation pattern |
| `structurizr-autolayout/src/main/java/com/structurizr/autolayout/graphviz/GraphvizAutomaticLayout.java` | Graphviz layout — just call `new GraphvizAutomaticLayout().apply(workspace)` |
| `structurizr-autolayout/src/main/java/com/structurizr/autolayout/graphviz/DOTExporter.java` | How graphviz-autolayout sizes nodes and handles boundaries |

---

## NOTICE file content

```
Structurizr Renderer
Copyright 2024 [Your Name]

This product includes software developed by the Structurizr project
(https://structurizr.com/), licensed under the Apache License, Version 2.0.
```
