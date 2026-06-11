package com.structurizr.renderer;

import com.structurizr.Workspace;
import com.structurizr.dsl.StructurizrDslParser;
import com.structurizr.export.Diagram;
import com.structurizr.renderer.layout.LayoutStrategyFactory;
import com.structurizr.renderer.svg.SvgDiagramExporter;
import com.structurizr.renderer.svg.ThemeCache;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.File;
import java.nio.file.Files;
import java.util.Collection;
import java.util.concurrent.Callable;

@Command(
    name = "structurizr-renderer",
    mixinStandardHelpOptions = true,
    versionProvider = RenderCommand.ManifestVersionProvider.class,
    description = "Render Structurizr DSL views to SVG or PNG"
)
public class RenderCommand implements Callable<Integer> {

    /** Reads the version stamped into the JAR manifest by the release build. */
    static class ManifestVersionProvider implements picocli.CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            String v = RenderCommand.class.getPackage().getImplementationVersion();
            return new String[]{"structurizr-renderer " + (v != null ? v : "(development build)")};
        }
    }

    @Parameters(index = "0", description = "Path to .dsl file")
    private File dslFile;

    @Option(names = {"-o", "--output"},
            description = "Output directory (default: same directory as input file)",
            defaultValue = "")
    private String outputDir;

    @Option(names = {"-f", "--format"},
            description = "Output format: svg, png, or both (default: svg)",
            defaultValue = "svg")
    private String format;

    @Option(names = {"-v", "--view"},
            description = "Key of a single view to render (default: all views)",
            defaultValue = "")
    private String viewKey;

    @Option(names = {"--no-autolayout"},
            description = "Skip automatic layout even when the DSL requests it")
    private boolean noAutolayout;

    @Option(names = {"--proxy"},
            description = "HTTP(S) proxy used when downloading non-bundled themes or icons, "
                        + "e.g. http://proxy.example.com:8080 or proxy.example.com:8080")
    private String proxy;

    @Override
    public Integer call() throws Exception {
        if (!dslFile.exists()) {
            System.err.println("Error: file not found: " + dslFile);
            return 1;
        }

        if (proxy != null && !proxy.isBlank() && !configureProxy(proxy)) {
            return 1;
        }

        // Parse DSL
        StructurizrDslParser parser = new StructurizrDslParser();
        parser.parse(dslFile);
        Workspace workspace = parser.getWorkspace();

        // Load themes (downloads once, then serves from disk cache)
        ThemeCache.loadThemes(workspace);

        // Apply layout
        if (!noAutolayout) {
            LayoutStrategyFactory.create().applyLayout(workspace);
        }

        // Determine output directory. getAbsoluteFile() ensures a parent exists even
        // when the DSL file is given as a bare filename like "diagram.dsl".
        File outDir = outputDir.isEmpty()
            ? dslFile.getAbsoluteFile().getParentFile()
            : new File(outputDir);
        outDir.mkdirs();

        boolean doSvg = format.equals("svg") || format.equals("both");
        boolean doPng = format.equals("png") || format.equals("both");

        if (!doSvg && !doPng) {
            System.err.println("Error: unknown format '" + format + "'. Use svg, png, or both.");
            return 1;
        }

        // Render
        Collection<Diagram> diagrams = new SvgDiagramExporter().export(workspace);

        if (!viewKey.isEmpty() && diagrams.stream().noneMatch(d -> d.getKey().equals(viewKey))) {
            System.err.println("Error: no view with key '" + viewKey + "'. Available views: "
                + String.join(", ", diagrams.stream().map(Diagram::getKey).toList()));
            return 1;
        }

        for (Diagram diagram : diagrams) {
            if (!viewKey.isEmpty() && !diagram.getKey().equals(viewKey)) continue;

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

    /**
     * Routes all HTTP(S) connections (theme/icon downloads) through the given proxy
     * by setting the standard JVM proxy system properties.
     */
    private static boolean configureProxy(String proxy) {
        String spec = proxy.contains("://") ? proxy : "http://" + proxy;
        java.net.URI uri = java.net.URI.create(spec);
        String host = uri.getHost();
        int port = uri.getPort();
        if (host == null || port == -1) {
            System.err.println("Error: invalid proxy '" + proxy + "'. Expected host:port, "
                + "e.g. proxy.example.com:8080");
            return false;
        }
        System.setProperty("http.proxyHost",  host);
        System.setProperty("http.proxyPort",  String.valueOf(port));
        System.setProperty("https.proxyHost", host);
        System.setProperty("https.proxyPort", String.valueOf(port));
        return true;
    }
}
